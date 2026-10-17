package com.nuvio.tv.data.ryot

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Ryot only knows TMDB/TVDB ids while Nuvio's catalogs are mostly IMDb-keyed, so both
 * directions go through this seam (backed by Nuvio's TmdbService in the app).
 */
interface RyotIdMapper {
    suspend fun imdbFor(lot: RyotLot, tmdbId: Long): String?
    suspend fun tmdbFor(lot: RyotLot, imdbId: String): Long?
}

/**
 * Builds a [RyotSnapshot] from the server.
 *
 * A full sync walks the whole movie and show library; it runs on first connect, when the user
 * asks for it, and once a day so removals made in Ryot's web UI are picked up. Between those,
 * an incremental sync re-reads only the most recently updated items, which Ryot lists first.
 * Catalog metadata never changes for a given Ryot id, so it is fetched once and kept.
 */
class RyotSyncEngine(
    private val api: RyotApi,
    private val ids: RyotIdMapper,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun sync(previous: RyotSnapshot?, forceFull: Boolean): RyotSnapshot {
        val user = api.userDetails()
        val base = previous?.takeIf { it.userId == user.id }
        val startedAt = now()
        val full = forceFull || base == null || startedAt - base.fullSyncedAt !in 0 until FULL_SYNC_INTERVAL_MS
        val known = base?.items.orEmpty()
        val items = LinkedHashMap<String, RyotItem>()
        if (!full) items.putAll(known)

        for (lot in RyotLot.entries) {
            var page: Int? = 1
            var pages = 0
            while (page != null && pages < (if (full) MAX_FULL_PAGES else MAX_INCREMENTAL_PAGES)) {
                val result = api.userMetadataList(lot, page, PAGE_SIZE)
                pages++
                val fetched = fetchItems(result.ids, known + items)
                var changed = 0
                result.ids.forEach { id ->
                    val item = fetched[id] ?: return@forEach
                    val previousItem = known[id]
                    if (previousItem == null || previousItem.user != item.user) changed++
                    items[id] = item
                }
                page = result.nextPage
                // Ryot lists the most recently touched items first, so once a page contains
                // anything unchanged, everything after it is older than the last sync.
                if (!full && changed < result.ids.size) break
            }
        }

        val collections = api.collections().filter(RyotCollection::isEditable)
        val collectionItems = linkedMapOf<String, List<String>>()
        collections.forEach { collection ->
            val memberIds = api.collectionMetadataIds(collection.id)
            collectionItems[collection.id] = memberIds
            val missing = memberIds.filter { it !in items }
            if (missing.isNotEmpty()) {
                val fetched = fetchItems(missing, known + items)
                missing.forEach { id -> fetched[id]?.let { items[id] = it } }
            }
        }
        // Collection contents can include books, games and so on; only movies and shows were fetched.
        val filteredCollectionItems = collectionItems.mapValues { (_, memberIds) -> memberIds.filter { it in items } }

        val withImdb = resolveImdbIds(items)
        return RyotSnapshot(
            userId = user.id,
            items = withImdb,
            collections = collections.sortedWith(compareByDescending<RyotCollection> { it.isWatchlist }.thenBy { it.name.lowercase() }),
            collectionItems = filteredCollectionItems,
            hiddenFromProgress = base?.hiddenFromProgress.orEmpty().filterKeys { it in withImdb },
            resolvedIds = base?.resolvedIds.orEmpty(),
            syncedAt = startedAt,
            fullSyncedAt = if (full) startedAt else base?.fullSyncedAt ?: startedAt
        )
    }

    /** Re-reads the user's history for specific items, e.g. shortly after a write was queued. */
    suspend fun refreshItems(snapshot: RyotSnapshot, metadataIds: Collection<String>): RyotSnapshot {
        if (metadataIds.isEmpty()) return snapshot
        val fetched = fetchItems(metadataIds.toList(), snapshot.items)
        if (fetched.isEmpty()) return snapshot
        val merged = resolveImdbIds(snapshot.items + fetched)
        return snapshot.copy(items = merged)
    }

    private suspend fun fetchItems(metadataIds: List<String>, known: Map<String, RyotItem>): Map<String, RyotItem> {
        if (metadataIds.isEmpty()) return emptyMap()
        val newIds = metadataIds.filter { it !in known }
        val knownIds = metadataIds.filter { it in known }
        val details = buildMap {
            putAll(api.details(newIds, includeMetadata = true, includeUser = true))
            putAll(api.details(knownIds, includeMetadata = false, includeUser = true))
        }
        return metadataIds.mapNotNull { id ->
            val detail = details[id] ?: return@mapNotNull null
            val previous = known[id]
            // Anime, books and the like decode to null metadata and are skipped here.
            val metadata = detail.metadata ?: previous?.metadata ?: return@mapNotNull null
            id to RyotItem(
                metadata = metadata,
                imdbId = previous?.imdbId,
                imdbResolved = previous?.imdbResolved ?: false,
                user = detail.user ?: previous?.user ?: RyotUserState()
            )
        }.toMap()
    }

    private suspend fun resolveImdbIds(items: Map<String, RyotItem>): Map<String, RyotItem> = coroutineScope {
        // Most relevant first: what is in progress or recently watched decides what the home screen shows.
        val pending = items.values
            .filter { !it.imdbResolved && it.tmdbId != null }
            .sortedByDescending { it.user.lastActivity }
            .take(MAX_ID_LOOKUPS_PER_SYNC)
        if (pending.isEmpty()) return@coroutineScope items
        val permits = Semaphore(ID_LOOKUP_CONCURRENCY)
        val resolved = pending.map { item ->
            async {
                permits.withPermit {
                    val imdb = runCatching { ids.imdbFor(item.lot, item.tmdbId!!) }.getOrNull()
                    item.id to imdb
                }
            }
        }.awaitAll().toMap()
        items.mapValues { (id, item) ->
            val imdb = resolved[id] ?: return@mapValues item
            item.copy(imdbId = imdb, imdbResolved = true)
        }
    }

    companion object {
        const val PAGE_SIZE = 100
        const val MAX_FULL_PAGES = 100
        const val MAX_INCREMENTAL_PAGES = 5
        const val FULL_SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val MAX_ID_LOOKUPS_PER_SYNC = 400
        const val ID_LOOKUP_CONCURRENCY = 6
    }
}
