package com.nuvio.tv.data.ryot

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TmdbRyotIdMapper(private val tmdb: TmdbService) : RyotIdMapper {
    override suspend fun imdbFor(lot: RyotLot, tmdbId: Long): String? =
        tmdbId.takeIf { it in 1..Int.MAX_VALUE }?.let { tmdb.tmdbToImdb(it.toInt(), lot.tmdbType()) }

    override suspend fun tmdbFor(lot: RyotLot, imdbId: String): Long? =
        tmdb.imdbToTmdb(imdbId, lot.tmdbType())?.toLong()

    private fun RyotLot.tmdbType() = if (this == RyotLot.MOVIE) "movie" else "tv"
}

class RyotSnapshotStorage(context: Context) {
    private val directory = File(context.filesDir, "ryot")

    fun read(profileId: Int): String? = runCatching { file(profileId).takeIf(File::exists)?.readText() }.getOrNull()

    fun write(profileId: Int, value: String?) {
        runCatching {
            val target = file(profileId)
            if (value == null) {
                target.delete()
                return
            }
            directory.mkdirs()
            val temp = File(directory, "${target.name}.tmp")
            temp.writeText(value)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }.onFailure { Log.w(TAG, "Could not persist Ryot snapshot", it) }
    }

    private fun file(profileId: Int) = File(directory, "snapshot_$profileId.json")
}

data class RyotSyncState(
    val profileId: Int = 1,
    val serverUrl: String? = null,
    val snapshot: RyotSnapshot? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val lastAttemptAt: Long = 0L
) {
    val hasLoaded: Boolean get() = snapshot != null
}

/**
 * Owns the Ryot snapshot for the active profile: loads it from disk, keeps it in sync with the
 * server, and applies Nuvio's writes (scrobbles, watched marks, list changes) to both.
 *
 * Writes go to Ryot immediately; because Ryot applies them through a job queue, the snapshot is
 * updated optimistically and the touched items are re-read a few seconds later.
 */
class RyotRepository(
    private val auth: RyotAuthStore,
    private val transport: RyotTransport,
    private val ids: RyotIdMapper,
    private val storage: RyotSnapshotStorage,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RyotSyncState())
    private var scrobbleWriter: Pair<RyotCredentials, RyotScrobbleWriter>? = null
    private var followUp: Job? = null
    private val followUpIds = linkedSetOf<String>()

    val state: StateFlow<RyotSyncState> = mutableState.asStateFlow()

    val projection: StateFlow<RyotProjection> = state
        .map { current -> current.snapshot?.let { RyotProjection(it, current.serverUrl) } ?: RyotProjection.Empty }
        .stateIn(scope, SharingStarted.Eagerly, RyotProjection.Empty)

    init {
        scope.launch {
            auth.state
                .map { it.profileId to it.credentials }
                .distinctUntilChanged()
                .collect { (profileId, credentials) -> onAccountChanged(profileId, credentials) }
        }
    }

    private suspend fun onAccountChanged(profileId: Int, credentials: RyotCredentials?) = mutex.withLock {
        followUp?.cancel()
        synchronized(followUpIds) { followUpIds.clear() }
        val stored = credentials?.let { RyotSnapshotCodec.decode(storage.read(profileId)) }?.takeIf { it.userId == credentials.userId }
        mutableState.value = RyotSyncState(profileId = profileId, serverUrl = credentials?.serverUrl, snapshot = stored)
        if (credentials != null) scope.launch { safeRefresh(if (stored == null) TrackingRefreshIntent.USER_INITIATED else TrackingRefreshIntent.AUTOMATIC) }
    }

    fun currentProfileId(): Int = auth.state.value.profileId

    // -----------------------------------------------------------------------------------------
    // Sync
    // -----------------------------------------------------------------------------------------

    suspend fun refresh(intent: TrackingRefreshIntent) {
        val credentials = auth.credentials() ?: return
        val profileId = auth.state.value.profileId
        mutex.withLock {
            if (!isCurrent(profileId, credentials)) return
            val current = mutableState.value
            val time = now()
            if (intent == TrackingRefreshIntent.AUTOMATIC) {
                val fresh = current.snapshot?.let { time - it.syncedAt in 0 until AUTOMATIC_INTERVAL_MS } == true
                val backingOff = current.error != null && time - current.lastAttemptAt in 0 until ERROR_RETRY_MS
                if (fresh || backingOff) return
            }
            mutableState.value = current.copy(refreshing = true, lastAttemptAt = time)
            try {
                val engine = RyotSyncEngine(api(credentials), ids, now)
                val synced = engine.sync(current.snapshot, forceFull = intent == TrackingRefreshIntent.USER_INITIATED)
                if (!isCurrent(profileId, credentials)) return
                commit(profileId, synced)
                mutableState.value = mutableState.value.copy(refreshing = false, error = null)
            } catch (error: CancellationException) {
                mutableState.value = mutableState.value.copy(refreshing = false)
                throw error
            } catch (error: Exception) {
                handleError(profileId, error)
                mutableState.value = mutableState.value.copy(refreshing = false, error = error.message ?: "Ryot sync failed")
                if (intent != TrackingRefreshIntent.AUTOMATIC) throw error
            }
        }
    }

    suspend fun ensureLoaded() {
        if (mutableState.value.snapshot == null) safeRefresh(TrackingRefreshIntent.AUTOMATIC)
    }

    fun refreshAsync(intent: TrackingRefreshIntent = TrackingRefreshIntent.AUTOMATIC) {
        scope.launch { safeRefresh(intent) }
    }

    private suspend fun safeRefresh(intent: TrackingRefreshIntent) {
        try {
            refresh(intent)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Ryot refresh failed", error)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------------------------------

    suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        val credentials = auth.credentials() ?: return
        val profileId = auth.state.value.profileId
        guarded(profileId) {
            val resolution = RyotResolver(api(credentials), ids).resolve(mutableState.value.snapshot, event.media) ?: return@guarded
            val (season, episode) = if (resolution.lot == RyotLot.SHOW) event.media.episode?.season to event.media.episode?.number else null to null
            val outcome = writerFor(credentials).scrobble(resolution, season, episode, action, event.progressPercent) ?: return@guarded
            mutate(profileId) { it.withResolved(resolution).withScrobble(outcome, now()) }
            scheduleFollowUp(listOf(outcome.metadataId))
        }
    }

    suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val credentials = auth.credentials() ?: return TrackingMutationResult(0)
        if (profileId != auth.state.value.profileId) return TrackingMutationResult(0)
        // A mark without episode numbers would read as "watched the whole show"; Nuvio sends episodes individually.
        val pushable = items.filterNot(TrackingHistoryItem::isWholeSeriesMark)
        if (pushable.isEmpty()) return TrackingMutationResult(0)
        return guarded(profileId) {
            val api = api(credentials)
            val resolver = RyotResolver(api, ids)
            var notFound = 0
            val marks = pushable.mapNotNull { item ->
                val resolution = resolver.resolve(mutableState.value.snapshot, item.media)
                if (resolution == null) {
                    notFound++
                    return@mapNotNull null
                }
                val (season, episode) = item.ryotEpisode()
                RyotHistoryMark(resolution, season, episode, item.watchedAtEpochMs)
            }
            api.deployProgressUpdates(ryotHistoryChanges(mutableState.value.snapshot, marks))
            mutate(profileId) { snapshot ->
                marks.fold(snapshot) { acc, mark -> acc.withResolved(mark.resolution) }.withHistoryMarks(marks, now())
            }
            scheduleFollowUp(marks.map { it.resolution.metadataId })
            TrackingMutationResult(pushable.size, notFound)
        } ?: TrackingMutationResult(pushable.size, pushable.size)
    }

    suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val credentials = auth.credentials() ?: return TrackingMutationResult(0)
        if (profileId != auth.state.value.profileId || items.isEmpty()) return TrackingMutationResult(0)
        return guarded(profileId) {
            val api = api(credentials)
            val resolver = RyotResolver(api, ids)
            var notFound = 0
            items.forEach { media ->
                val resolution = resolver.resolve(mutableState.value.snapshot, media)
                if (resolution == null) {
                    notFound++
                    return@forEach
                }
                val state = api.details(listOf(resolution.metadataId), includeMetadata = false, includeUser = true)[resolution.metadataId]?.user
                    ?: return@forEach
                val season = media.episode?.season.takeIf { media.kind != TrackingMediaKind.MOVIE }
                val episode = media.episode?.number.takeIf { media.kind != TrackingMediaKind.MOVIE }
                val doomed = ryotSeensToDelete(state, resolution.lot, season, episode)
                doomed.forEach { api.deleteSeen(it.id) }
                val doomedIds = doomed.mapTo(hashSetOf(), RyotSeen::id)
                mutate(profileId) { snapshot ->
                    // Optimistic entries carry made-up ids, so drop matching episodes too.
                    val item = snapshot.items[resolution.metadataId]
                    val local = item?.user?.let { ryotSeensToDelete(it, resolution.lot, season, episode).map(RyotSeen::id) }.orEmpty()
                    snapshot.withoutSeens(resolution.metadataId, doomedIds + local)
                }
                scheduleFollowUp(listOf(resolution.metadataId))
            }
            TrackingMutationResult(items.size, notFound)
        } ?: TrackingMutationResult(items.size, items.size)
    }

    /**
     * Removing something from Continue Watching hides it on this device until there is new
     * activity. It deliberately does not delete anything in Ryot: a TV shortcut shouldn't erase history.
     */
    suspend fun hideFromProgress(contentId: String) {
        val profileId = auth.state.value.profileId
        val snapshot = mutableState.value.snapshot ?: return
        val item = snapshot.items.values.firstOrNull { contentId in it.aliases } ?: return
        mutate(profileId) { it.withHidden(item.id, now()) }
    }

    suspend fun membershipSnapshot(input: LibraryEntryInput): ListMembershipSnapshot {
        ensureLoaded()
        val projection = projection.value
        val members = projection.membership(input.itemId, input.itemType) +
            listOfNotNull(input.imdbId, input.tmdbId?.let { "tmdb:$it" }).flatMap { projection.membership(it, input.itemType) }
        return ListMembershipSnapshot(projection.tabs.associate { it.key to (it.key in members) })
    }

    suspend fun applyMembership(input: LibraryEntryInput, changes: ListMembershipChanges) {
        val credentials = auth.credentials() ?: throw RyotApiException("Ryot is not connected")
        val profileId = auth.state.value.profileId
        val current = membershipSnapshot(input).listMembership
        val snapshot = mutableState.value.snapshot ?: throw RyotApiException("Ryot library is still loading")
        val api = api(credentials)
        val lot = RyotLot.fromContentType(input.itemType)
        val resolution = try {
            RyotResolver(api, ids).resolve(
                snapshot = snapshot,
                lot = lot,
                aliases = listOf(input.itemId, input.imdbId, input.tmdbId?.let { "tmdb:$it" }),
                tmdbHint = input.tmdbId?.toLong(),
                imdbHint = input.imdbId ?: input.itemId,
                title = input.title
            )
        } catch (error: RyotAuthException) {
            handleError(profileId, error)
            throw error
        } ?: throw RyotApiException("Couldn't find \"${input.title}\" in Ryot")
        val collectionsByKey = snapshot.collections.associateBy(RyotCollection::key)
        changes.desiredMembership.forEach { (key, desired) ->
            val collection = collectionsByKey[key] ?: return@forEach
            if (current[key] == desired) return@forEach
            try {
                api.changeCollection(add = desired, collection = collection, metadataIds = listOf(resolution.metadataId))
            } catch (error: RyotAuthException) {
                handleError(profileId, error)
                throw error
            }
            mutate(profileId) { it.withResolved(resolution).withMembership(collection.id, resolution.metadataId, desired) }
        }
        scheduleFollowUp(listOf(resolution.metadataId))
    }

    // -----------------------------------------------------------------------------------------
    // Account
    // -----------------------------------------------------------------------------------------

    /** Signs in with username/password and keeps a non-expiring token, never the password. */
    suspend fun connectWithPassword(profileId: Int, rawServerUrl: String, username: String, password: String): RyotUser {
        val serverUrl = normalizeRyotServerUrl(rawServerUrl) ?: throw RyotApiException("That doesn't look like a server address")
        val session = RyotApi(RyotGraphQlClient(transport, serverUrl, null)).login(username.trim(), password)
        val sessionApi = RyotApi(RyotGraphQlClient(transport, serverUrl, session))
        val token = sessionApi.generateAuthToken()
        runCatching { sessionApi.logout() }
        return finishConnect(profileId, serverUrl, token, username.trim())
    }

    suspend fun connectWithToken(profileId: Int, rawServerUrl: String, token: String): RyotUser {
        val serverUrl = normalizeRyotServerUrl(rawServerUrl) ?: throw RyotApiException("That doesn't look like a server address")
        return finishConnect(profileId, serverUrl, token.trim(), null)
    }

    private suspend fun finishConnect(profileId: Int, serverUrl: String, token: String, username: String?): RyotUser {
        val user = RyotApi(RyotGraphQlClient(transport, serverUrl, token)).userDetails()
        storage.write(profileId, null)
        auth.save(RyotCredentials(serverUrl, token, user.id, username ?: user.name), profileId)
        return user
    }

    /** Disconnects locally and revokes the token on the server when it's reachable. */
    suspend fun disconnect(profileId: Int) {
        val credentials = auth.state.value.takeIf { it.profileId == profileId }?.credentials
        if (credentials != null) {
            runCatching { api(credentials).logout() }
        }
        storage.write(profileId, null)
        auth.disconnect(profileId)
    }

    // -----------------------------------------------------------------------------------------

    private fun api(credentials: RyotCredentials) =
        RyotApi(RyotGraphQlClient(transport, credentials.serverUrl, credentials.token))

    private fun writerFor(credentials: RyotCredentials): RyotScrobbleWriter = synchronized(this) {
        scrobbleWriter?.takeIf { it.first == credentials }?.second
            ?: RyotScrobbleWriter(api(credentials), now).also { scrobbleWriter = credentials to it }
    }

    private fun isCurrent(profileId: Int, credentials: RyotCredentials): Boolean {
        val current = auth.state.value
        return current.profileId == profileId && current.credentials == credentials
    }

    private suspend fun <T> guarded(profileId: Int, block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        handleError(profileId, error)
        throw error
    }

    private fun handleError(profileId: Int, error: Exception) {
        if (error is RyotAuthException) {
            Log.w(TAG, "Ryot token rejected; disconnecting profile $profileId")
            auth.markRejected(profileId, "Ryot rejected the saved login. Connect again.")
        }
    }

    private suspend fun mutate(profileId: Int, transform: (RyotSnapshot) -> RyotSnapshot) = mutex.withLock {
        val current = mutableState.value
        if (current.profileId != profileId) return@withLock
        val snapshot = current.snapshot ?: return@withLock
        commit(profileId, transform(snapshot))
    }

    private fun commit(profileId: Int, snapshot: RyotSnapshot) {
        mutableState.value = mutableState.value.copy(snapshot = snapshot)
        storage.write(profileId, RyotSnapshotCodec.encode(snapshot))
    }

    /** Re-reads touched items once Ryot's job queue has had time to apply the writes. */
    private fun scheduleFollowUp(metadataIds: Collection<String>) {
        if (metadataIds.isEmpty()) return
        synchronized(followUpIds) { followUpIds += metadataIds }
        followUp?.cancel()
        followUp = scope.launch {
            delay(FOLLOW_UP_DELAY_MS)
            val pending = synchronized(followUpIds) { followUpIds.toList().also { followUpIds.clear() } }
            val credentials = auth.credentials() ?: return@launch
            val profileId = auth.state.value.profileId
            try {
                mutex.withLock {
                    val snapshot = mutableState.value.snapshot ?: return@withLock
                    if (!isCurrent(profileId, credentials)) return@withLock
                    val refreshed = RyotSyncEngine(api(credentials), ids, now).refreshItems(snapshot, pending)
                    if (isCurrent(profileId, credentials)) commit(profileId, refreshed)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                handleError(profileId, error)
                Log.w(TAG, "Ryot follow-up refresh failed", error)
            }
        }
    }

    companion object {
        const val AUTOMATIC_INTERVAL_MS = 10L * 60 * 1000
        const val ERROR_RETRY_MS = 2L * 60 * 1000
        private const val FOLLOW_UP_DELAY_MS = 6_000L
    }
}

private const val TAG = "Ryot"
