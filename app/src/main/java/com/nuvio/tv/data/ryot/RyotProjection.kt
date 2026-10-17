package com.nuvio.tv.data.ryot

import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

/** Everything Nuvio reads from Ryot, derived once per snapshot. */
class RyotProjection(snapshot: RyotSnapshot, private val serverUrl: String?) {
    private val items = snapshot.items.values.toList()

    val watchedMovieIds: Set<String> = items
        .filter { it.lot == RyotLot.MOVIE && it.user.history.any(RyotSeen::isCompleted) }
        .flatMapTo(linkedSetOf()) { it.aliases }

    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>> = buildMap {
        items.filter { it.lot == RyotLot.SHOW }.forEach { item ->
            val episodes = item.user.history
                .filter(RyotSeen::isCompleted)
                .mapNotNullTo(linkedSetOf()) { seen -> seen.season?.let { s -> seen.episode?.let { e -> s to e } } }
            if (episodes.isNotEmpty()) item.aliases.forEach { alias -> put(alias, episodes) }
        }
    }

    val showIdSiblings: Map<String, Set<String>> = buildMap {
        items.filter { it.lot == RyotLot.SHOW }.forEach { item ->
            val aliases = item.aliases
            aliases.forEach { put(it, aliases) }
        }
    }

    val watchedItems: List<WatchedItem> = items.flatMap { item ->
        item.user.history.filter(RyotSeen::isCompleted).map { seen ->
            WatchedItem(
                contentId = item.contentId,
                contentType = item.lot.contentType,
                title = item.metadata.title,
                season = seen.season,
                episode = seen.episode,
                watchedAt = seen.watchedAt,
                poster = item.metadata.poster,
                releaseInfo = item.metadata.year?.toString(),
                trackingProviderId = RYOT_PROVIDER_STORAGE_ID,
                trackingProviderItemId = "ryot:${item.id}",
                trackingSourceUrl = sourceUrl(item)
            )
        }
    }.sortedByDescending(WatchedItem::watchedAt)

    /** Partially watched movies and episodes, newest first, one entry per title. */
    val progress: List<WatchProgress> = items.mapNotNull { item ->
        val seen = item.user.inProgress ?: return@mapNotNull null
        if (seen.state != RyotSeenState.IN_PROGRESS || seen.progress >= 100.0 || snapshot.isHidden(item)) return@mapNotNull null
        if (item.lot == RyotLot.SHOW && (seen.season == null || seen.episode == null)) return@mapNotNull null
        // A stale in-progress entry is superseded by anything finished after it on the same or a later episode.
        val superseded = item.user.history.any { other ->
            other.isCompleted && other.lastUpdatedOn >= seen.lastUpdatedOn &&
                (item.lot == RyotLot.MOVIE || episodeOrder(other) >= episodeOrder(seen))
        }
        if (superseded) return@mapNotNull null
        toProgress(item, seen, seen.progress.toFloat(), seen.lastUpdatedOn, WatchProgress.SOURCE_REMOTE_PLAYBACK)
    }.sortedByDescending(WatchProgress::lastWatched)

    private val seeds: List<WatchProgress> = items
        .filter { it.lot == RyotLot.SHOW && !snapshot.isHidden(it) }
        .flatMap { item ->
            item.user.history
                .filter { it.isCompleted && (it.season ?: 0) > 0 && it.episode != null }
                .map { seen -> toProgress(item, seen, 100f, seen.watchedAt, WatchProgress.SOURCE_REMOTE_HISTORY) }
        }

    fun nextUp(preferFurthestEpisode: Boolean): List<WatchProgress> =
        selectPreferredTrackingNextUpSeeds(seeds, preferFurthestEpisode)

    private val episodeProgressById: Map<String, List<WatchProgress>> = progress
        .filter { it.season != null && it.episode != null }
        .flatMap { entry -> (showIdSiblings[entry.contentId] ?: setOf(entry.contentId)).map { it to entry } }
        .groupBy({ it.first }, { it.second })

    fun episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> =
        episodeProgressById[contentId].orEmpty().associateBy { it.season!! to it.episode!! }

    fun isWatched(contentId: String, season: Int?, episode: Int?): Boolean =
        if (season != null && episode != null) (season to episode) in watchedShowEpisodes[contentId].orEmpty()
        else contentId in watchedMovieIds

    private val hiddenAliases: Set<String> = items.filter(snapshot::isHidden).flatMapTo(hashSetOf()) { it.aliases }

    fun isHidden(contentId: String): Boolean = contentId in hiddenAliases

    // -----------------------------------------------------------------------------------------
    // Library
    // -----------------------------------------------------------------------------------------

    val tabs: List<LibraryListTab> = snapshot.collections.map { collection ->
        LibraryListTab(
            key = collection.key,
            title = collection.name,
            type = if (collection.isWatchlist) LibraryListTab.Type.WATCHLIST else LibraryListTab.Type.PERSONAL,
            trackingProviderId = RYOT_PROVIDER_STORAGE_ID,
            supportedContentTypes = setOf("movie", "series")
        )
    }

    private val membershipByAlias = mutableMapOf<Pair<RyotLot, String>, MutableSet<String>>()

    val libraryEntries: List<LibraryEntry> = run {
        val combined = linkedMapOf<String, LibraryEntry>()
        snapshot.collections.forEach { collection ->
            snapshot.collectionItems[collection.id].orEmpty().forEachIndexed { position, metadataId ->
                val item = snapshot.items[metadataId] ?: return@forEachIndexed
                val previous = combined[metadataId]
                combined[metadataId] = LibraryEntry(
                    id = item.contentId,
                    type = item.lot.contentType,
                    name = item.metadata.title,
                    poster = item.metadata.poster,
                    background = item.metadata.backdrop,
                    logo = null,
                    description = null,
                    releaseInfo = item.metadata.year?.toString(),
                    imdbRating = null,
                    genres = emptyList(),
                    addonBaseUrl = null,
                    listKeys = previous?.listKeys.orEmpty() + collection.key,
                    listRanks = previous?.listRanks.orEmpty() + (collection.key to position),
                    // Ryot returns collection items newest first; rank keeps that order stable.
                    listedAt = maxOf(previous?.listedAt ?: 0L, item.user.lastActivity),
                    imdbId = item.imdbId,
                    tmdbId = item.tmdbId?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
                    trackingProviderId = RYOT_PROVIDER_STORAGE_ID,
                    trackingProviderItemId = "ryot:${item.id}",
                    trackingSourceUrl = sourceUrl(item),
                    rawPosterUrl = item.metadata.poster
                )
                item.aliases.forEach { alias ->
                    membershipByAlias.getOrPut(item.lot to alias) { linkedSetOf() } += collection.key
                }
            }
        }
        combined.values.toList()
    }

    fun membership(contentId: String, contentType: String): Set<String> =
        membershipByAlias[RyotLot.fromContentType(contentType) to contentId].orEmpty()

    // -----------------------------------------------------------------------------------------

    private fun toProgress(item: RyotItem, seen: RyotSeen, percent: Float, timestamp: Long, source: String): WatchProgress {
        val isMovie = item.lot == RyotLot.MOVIE
        return WatchProgress(
            contentId = item.contentId,
            contentType = item.lot.contentType,
            name = item.metadata.title,
            poster = item.metadata.poster,
            backdrop = item.metadata.backdrop,
            logo = null,
            videoId = if (isMovie) item.contentId else "${item.contentId}:${seen.season}:${seen.episode}",
            season = if (isMovie) null else seen.season,
            episode = if (isMovie) null else seen.episode,
            episodeTitle = null,
            position = 0L,
            duration = (item.metadata.runtimeMinutes ?: 0).toLong() * 60_000L,
            lastWatched = timestamp,
            progressPercent = percent,
            source = source,
            trackingProviderId = RYOT_PROVIDER_STORAGE_ID,
            trackingProviderItemId = "ryot:${item.id}",
            trackingSourceUrl = sourceUrl(item)
        )
    }

    private fun sourceUrl(item: RyotItem): String? = serverUrl?.let { "${it.trimEnd('/')}/media/item/${item.id}" }

    private fun episodeOrder(seen: RyotSeen): Long =
        (seen.season ?: 0).toLong() * 100_000L + (seen.episode ?: 0).toLong()

    companion object {
        val Empty = RyotProjection(RyotSnapshot(userId = ""), null)
    }
}
