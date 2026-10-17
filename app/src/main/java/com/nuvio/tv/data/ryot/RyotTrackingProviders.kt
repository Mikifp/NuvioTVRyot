package com.nuvio.tv.data.ryot

import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@Singleton
class RyotTrackingProvider @Inject constructor(
    auth: RyotAuthStore,
    override val scrobbler: RyotTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.RYOT,
        "Ryot",
        setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE
        )
    )

    override val isAuthenticated: StateFlow<Boolean> = auth.state
        .map { it.isAuthenticated }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, auth.state.value.isAuthenticated)
}

@Singleton
class RyotTrackingScrobbler @Inject constructor(
    private val repository: RyotRepository
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.RYOT

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) =
        repository.scrobble(action, event)
}

@Singleton
class RyotTrackingHistoryWriter @Inject constructor(
    private val repository: RyotRepository
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.RYOT

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult =
        repository.addToHistory(profileId, items)

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult =
        repository.removeFromHistory(profileId, items)
}

@Singleton
class RyotTrackingProgressProvider @Inject constructor(
    private val repository: RyotRepository,
    auth: RyotAuthStore,
    layout: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.RYOT
    override val isAuthenticated: Flow<Boolean> = auth.state.map { it.isAuthenticated }.distinctUntilChanged()

    private val projection: Flow<RyotProjection> = repository.projection

    override val allProgress: Flow<List<WatchProgress>> = projection.map { it.progress }.loadOnStart()
    override val watchedItems: Flow<List<WatchedItem>> = projection.map { it.watchedItems }.loadOnStart()
    override val watchedMovieIds: Flow<Set<String>> = projection.map { it.watchedMovieIds }.loadOnStart()
    override val nextUpSeeds: Flow<List<WatchProgress>> =
        combine(projection, layout.nextUpFromFurthestEpisode) { current, furthest -> current.nextUp(furthest) }.loadOnStart()
    // A failed first sync also counts as "loaded", so Continue Watching never waits on an unreachable server.
    override val remoteProgressLoaded: Flow<Boolean> =
        combine(repository.state, auth.state) { state, authState ->
            authState.isAuthenticated && state.profileId == authState.profileId && (state.hasLoaded || state.error != null)
        }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        projection.map { it.episodeProgress(contentId) }.loadOnStart()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> =
        projection.map { it.isWatched(contentId, season, episode) }.loadOnStart()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        repository.ensureLoaded()
        return repository.projection.value.watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        repository.ensureLoaded()
        return repository.projection.value.showIdSiblings
    }

    override fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? {
        val parts = videoId.split(':')
        if (parts.size < 3 || parts.last().toIntOrNull() != episode) return null
        val season = parts[parts.lastIndex - 1].toIntOrNull() ?: return null
        val parent = parts.dropLast(2).joinToString(":")
        if (retainsLocalProgress(parent)) return null
        return repository.projection.value.isWatched(parent, season, episode)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = repository.refresh(intent)

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) =
        repository.hideFromProgress(contentId)

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit
    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit
    override fun clearOptimistic() = Unit
    override fun isHiddenFromProgress(contentId: String): Boolean = repository.projection.value.isHidden(contentId)
    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress

    /** Ryot can only track titles Nuvio can name by IMDb, TMDB or TVDB id; anything else stays local. */
    override fun retainsLocalProgress(contentId: String): Boolean {
        val ids = parseTrackingExternalIds(contentId)
        return ids.imdb == null && ids.tmdb == null && ids.tvdb == null
    }

    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = retainsLocalProgress(item.contentId)

    private fun <T> Flow<T>.loadOnStart(): Flow<T> = onStart {
        repository.refreshAsync(TrackingRefreshIntent.AUTOMATIC)
    }.distinctUntilChanged()
}

@Singleton
class RyotTrackingLibraryProvider @Inject constructor(
    private val repository: RyotRepository,
    auth: RyotAuthStore
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.RYOT
    override val isAuthenticated: Flow<Boolean> = auth.state.map { it.isAuthenticated }.distinctUntilChanged()
    override val isRefreshing: Flow<Boolean> = repository.state.map { it.refreshing }.distinctUntilChanged()
    override val items = repository.projection.map { it.libraryEntries }
        .onStart { repository.refreshAsync(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()
    override val tabs: Flow<List<LibraryListTab>> = repository.projection.map { it.tabs }.distinctUntilChanged()

    override fun recognizesListKey(key: String): Boolean = key.startsWith(RYOT_COLLECTION_KEY_PREFIX)

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> =
        repository.projection.map { it.membership(itemId, itemType) }.distinctUntilChanged()

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> {
        val watchlistKey = repository.projection.value.tabs
            .firstOrNull { it.type == LibraryListTab.Type.WATCHLIST }?.key
            ?: return currentMembership
        return currentMembership + (watchlistKey to (currentMembership[watchlistKey] != true))
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot =
        repository.membershipSnapshot(item)

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) = repository.applyMembership(item, changes)

    override suspend fun refresh(intent: TrackingRefreshIntent) = repository.refresh(intent)
}
