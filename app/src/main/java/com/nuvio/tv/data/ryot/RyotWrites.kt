package com.nuvio.tv.data.ryot

import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import java.util.concurrent.ConcurrentHashMap

data class RyotResolution(
    val metadataId: String,
    val lot: RyotLot,
    /** Set when the id came from a search, so the caller can remember it in [RyotSnapshot.resolvedIds]. */
    val cacheKey: String? = null
)

internal fun ryotResolvedKey(lot: RyotLot, tmdbId: Long) = "${lot.wire}:$tmdbId"

internal fun TrackingMediaReference.ryotLot(): RyotLot =
    if (kind == TrackingMediaKind.MOVIE) RyotLot.MOVIE else RyotLot.SHOW

/**
 * Maps what Nuvio is playing to a Ryot metadata id: the synced library first, then ids resolved
 * earlier, and only then a title search in Ryot (which also adds the title to Ryot's catalog).
 */
class RyotResolver(
    private val api: RyotApi,
    private val ids: RyotIdMapper
) {
    suspend fun resolve(
        snapshot: RyotSnapshot?,
        lot: RyotLot,
        aliases: Collection<String?>,
        tmdbHint: Long?,
        imdbHint: String?,
        title: String?
    ): RyotResolution? {
        aliases.filterNotNull().filter(String::isNotBlank).forEach { alias ->
            snapshot?.findByAlias(lot, alias)?.let { return RyotResolution(it.id, lot) }
        }
        val tmdbId = tmdbHint ?: imdbHint?.takeIf { it.startsWith("tt") }?.let { runCatching { ids.tmdbFor(lot, it) }.getOrNull() }
            ?: return null
        snapshot?.findByTmdb(lot, tmdbId)?.let { return RyotResolution(it.id, lot) }
        val key = ryotResolvedKey(lot, tmdbId)
        snapshot?.resolvedIds?.get(key)?.let { return RyotResolution(it, lot) }
        val query = title?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val candidates = api.search(lot, query).take(SEARCH_CANDIDATES)
        if (candidates.isEmpty()) return null
        val details = api.details(candidates, includeMetadata = true, includeUser = false)
        val match = candidates.firstOrNull { id ->
            val metadata = details[id]?.metadata ?: return@firstOrNull false
            metadata.source == RYOT_SOURCE_TMDB && metadata.identifier == tmdbId.toString()
        } ?: return null
        return RyotResolution(match, lot, key)
    }

    suspend fun resolve(snapshot: RyotSnapshot?, media: TrackingMediaReference): RyotResolution? {
        val lot = media.ryotLot()
        return resolve(
            snapshot = snapshot,
            lot = lot,
            aliases = listOf(
                media.ids.imdb,
                media.ids.tmdb?.let { "tmdb:$it" },
                media.ids.tvdb?.let { "tvdb:$it" },
                media.catalog?.contentId
            ),
            tmdbHint = media.ids.tmdb,
            imdbHint = media.ids.imdb ?: media.catalog?.contentId,
            title = media.title
        )
    }

    companion object {
        private const val SEARCH_CANDIDATES = 8
    }
}

/** What a scrobble did, so the caller can update its snapshot before Ryot's job queue catches up. */
data class RyotScrobbleOutcome(
    val metadataId: String,
    val season: Int?,
    val episode: Int?,
    val percent: Double,
    val completed: Boolean
)

/**
 * Mirrors how Ryot's own media-server sinks record playback: a new in-progress entry when
 * a title (or a different episode) starts, then percentage updates on it, completed at the end.
 *
 * Ryot applies progress through a background job, so a START followed quickly by a PAUSE
 * would not yet see the entry the START created. [pending] bridges that gap.
 */
class RyotScrobbleWriter(
    private val api: RyotApi,
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Pending(val season: Int?, val episode: Int?, val percent: Double, val at: Long)

    private val pending = ConcurrentHashMap<String, Pending>()

    suspend fun scrobble(
        resolution: RyotResolution,
        season: Int?,
        episode: Int?,
        action: TrackingScrobbleAction,
        progressPercent: Double
    ): RyotScrobbleOutcome? {
        if (!progressPercent.isFinite()) return null
        if (resolution.lot == RyotLot.SHOW && (season == null || episode == null)) return null
        val percent = progressPercent.coerceIn(0.0, 100.0)
        // Ryot's sinks ignore the first couple of percent too; it keeps zapping and trailers out of history.
        if (percent < MIN_PERCENT) return null
        val completed = action != TrackingScrobbleAction.START && percent >= COMPLETE_PERCENT
        val target = if (completed) 100.0 else minOf(percent, MAX_IN_PROGRESS_PERCENT)
        val id = resolution.metadataId
        val time = now()

        val memo = pending[id]?.takeIf { time - it.at in 0 until PENDING_TTL_MS && it.season == season && it.episode == episode }
        if (memo != null && ryotDecimal(memo.percent) == ryotDecimal(target)) return null

        val hasMatchingEntry = memo != null || run {
            val current = api.details(listOf(id), includeMetadata = false, includeUser = true)[id]?.user?.inProgress
            current != null && current.state == RyotSeenState.IN_PROGRESS && current.isSameEpisode(season, episode)
        }
        val changes = buildList {
            if (!hasMatchingEntry) add(RyotChanges.newInProgress(time, season, episode))
            add(RyotChanges.latestInProgress(target))
        }
        api.deployProgressUpdates(changes.map { RyotProgressChange(id, it) })
        if (completed) pending.remove(id) else pending[id] = Pending(season, episode, target, time)
        return RyotScrobbleOutcome(id, season, episode, target, completed)
    }

    fun clearPending() = pending.clear()

    companion object {
        const val MIN_PERCENT = 2.0
        const val COMPLETE_PERCENT = 90.0
        const val MAX_IN_PROGRESS_PERCENT = 99.0
        private const val PENDING_TTL_MS = 3L * 60 * 60 * 1000
    }
}

// ---------------------------------------------------------------------------------------------
// History marks
// ---------------------------------------------------------------------------------------------

data class RyotHistoryMark(
    val resolution: RyotResolution,
    val season: Int?,
    val episode: Int?,
    val watchedAt: Long?
)

internal fun TrackingHistoryItem.ryotEpisode(): Pair<Int?, Int?> =
    if (media.kind == TrackingMediaKind.MOVIE) null to null
    else media.episode?.season to media.episode?.number

/** Change payloads for marks that Ryot doesn't already have (a repeated mark within minutes is a no-op). */
internal fun ryotHistoryChanges(snapshot: RyotSnapshot?, marks: List<RyotHistoryMark>): List<RyotProgressChange> =
    marks.mapNotNull { mark ->
        val isShow = mark.resolution.lot == RyotLot.SHOW
        if (isShow && (mark.season == null || mark.episode == null)) return@mapNotNull null
        val existing = snapshot?.items?.get(mark.resolution.metadataId)?.user?.history.orEmpty()
        val duplicate = existing.any { seen ->
            seen.isCompleted && seen.isSameEpisode(mark.season, mark.episode) &&
                mark.watchedAt != null && kotlin.math.abs(seen.watchedAt - mark.watchedAt) < DUPLICATE_WINDOW_MS
        }
        if (duplicate) return@mapNotNull null
        RyotProgressChange(
            mark.resolution.metadataId,
            RyotChanges.completed(mark.watchedAt, mark.season, mark.episode)
        )
    }.distinctBy { it.metadataId to it.change.toString() }

/** Completed entries to delete when the user marks something unwatched in Nuvio. */
internal fun ryotSeensToDelete(state: RyotUserState, lot: RyotLot, season: Int?, episode: Int?): List<RyotSeen> =
    state.history.filter { seen ->
        seen.isCompleted && when {
            lot == RyotLot.MOVIE -> true
            season == null || episode == null -> true
            else -> seen.isSameEpisode(season, episode)
        }
    }

private const val DUPLICATE_WINDOW_MS = 10L * 60 * 1000

// ---------------------------------------------------------------------------------------------
// Optimistic snapshot updates
// ---------------------------------------------------------------------------------------------

internal fun RyotSnapshot.withScrobble(outcome: RyotScrobbleOutcome, at: Long): RyotSnapshot {
    val item = items[outcome.metadataId] ?: return this
    val previous = item.user.inProgress?.takeIf { it.isSameEpisode(outcome.season, outcome.episode) }
    val seen = RyotSeen(
        id = previous?.id ?: "pending:${outcome.metadataId}:${outcome.season}:${outcome.episode}",
        state = if (outcome.completed) RyotSeenState.COMPLETED else RyotSeenState.IN_PROGRESS,
        progress = outcome.percent,
        season = outcome.season,
        episode = outcome.episode,
        startedOn = previous?.startedOn ?: at,
        finishedOn = if (outcome.completed) at else null,
        lastUpdatedOn = at
    )
    val user = if (outcome.completed) {
        item.user.copy(inProgress = null, history = listOf(seen) + item.user.history.filterNot { it.id == seen.id })
    } else {
        item.user.copy(inProgress = seen, history = listOf(seen) + item.user.history.filterNot { it.id == seen.id })
    }
    return copy(items = items + (item.id to item.copy(user = user)))
}

internal fun RyotSnapshot.withHistoryMarks(marks: List<RyotHistoryMark>, at: Long): RyotSnapshot {
    var updated = items
    marks.forEach { mark ->
        val item = updated[mark.resolution.metadataId] ?: return@forEach
        val time = mark.watchedAt ?: at
        val seen = RyotSeen(
            id = "pending:${item.id}:${mark.season}:${mark.episode}:$time",
            state = RyotSeenState.COMPLETED,
            progress = 100.0,
            season = mark.season,
            episode = mark.episode,
            startedOn = time,
            finishedOn = time,
            lastUpdatedOn = at
        )
        val inProgress = item.user.inProgress?.takeUnless { it.isSameEpisode(mark.season, mark.episode) }
        updated = updated + (item.id to item.copy(user = item.user.copy(inProgress = inProgress, history = listOf(seen) + item.user.history)))
    }
    return copy(items = updated)
}

internal fun RyotSnapshot.withoutSeens(metadataId: String, seenIds: Set<String>): RyotSnapshot {
    val item = items[metadataId] ?: return this
    val user = item.user.copy(
        inProgress = item.user.inProgress?.takeUnless { it.id in seenIds },
        history = item.user.history.filterNot { it.id in seenIds }
    )
    return copy(items = items + (metadataId to item.copy(user = user)))
}

internal fun RyotSnapshot.withHidden(metadataId: String, at: Long): RyotSnapshot =
    copy(hiddenFromProgress = hiddenFromProgress + (metadataId to at))

internal fun RyotSnapshot.withResolved(resolution: RyotResolution): RyotSnapshot =
    resolution.cacheKey?.let { copy(resolvedIds = resolvedIds + (it to resolution.metadataId)) } ?: this

internal fun RyotSnapshot.withMembership(collectionId: String, metadataId: String, member: Boolean): RyotSnapshot {
    val current = collectionItems[collectionId].orEmpty()
    val next = when {
        member && metadataId !in current -> listOf(metadataId) + current
        !member -> current - metadataId
        else -> current
    }
    return copy(collectionItems = collectionItems + (collectionId to next))
}
