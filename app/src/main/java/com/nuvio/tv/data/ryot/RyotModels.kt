package com.nuvio.tv.data.ryot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val RYOT_SOURCE_TMDB = "TMDB"
internal const val RYOT_SOURCE_TVDB = "TVDB"
const val RYOT_PROVIDER_STORAGE_ID = "ryot"
const val RYOT_COLLECTION_KEY_PREFIX = "ryot:collection:"
const val RYOT_WATCHLIST_NAME = "Watchlist"

/** Ryot manages these collections itself; letting people edit them from a TV would fight the server. */
internal val RYOT_SYSTEM_COLLECTIONS = setOf("In Progress", "Completed", "Reminders")

enum class RyotLot(val wire: String) {
    MOVIE("MOVIE"),
    SHOW("SHOW");

    val contentType: String get() = if (this == MOVIE) "movie" else "series"

    companion object {
        fun fromWire(value: String?): RyotLot? = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
        fun fromContentType(type: String?): RyotLot =
            if (type?.trim()?.lowercase() in setOf("movie", "film")) MOVIE else SHOW
    }
}

enum class RyotSeenState(val wire: String) {
    IN_PROGRESS("IN_PROGRESS"),
    COMPLETED("COMPLETED"),
    ON_A_HOLD("ON_A_HOLD"),
    DROPPED("DROPPED");

    companion object {
        fun fromWire(value: String?): RyotSeenState =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: IN_PROGRESS
    }
}

data class RyotSeen(
    val id: String,
    val state: RyotSeenState,
    val progress: Double,
    val season: Int? = null,
    val episode: Int? = null,
    val startedOn: Long? = null,
    val finishedOn: Long? = null,
    val lastUpdatedOn: Long = 0L
) {
    val isCompleted: Boolean get() = state == RyotSeenState.COMPLETED
    val watchedAt: Long get() = finishedOn ?: lastUpdatedOn

    fun isSameEpisode(season: Int?, episode: Int?): Boolean = this.season == season && this.episode == episode
}

data class RyotMetadata(
    val id: String,
    val lot: RyotLot,
    val source: String,
    val identifier: String,
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val runtimeMinutes: Int? = null
)

data class RyotUserState(
    val inProgress: RyotSeen? = null,
    val history: List<RyotSeen> = emptyList()
) {
    val lastActivity: Long
        get() = maxOf(inProgress?.lastUpdatedOn ?: 0L, history.maxOfOrNull(RyotSeen::lastUpdatedOn) ?: 0L)
}

data class RyotDetails(val metadata: RyotMetadata?, val user: RyotUserState?)

data class RyotCollection(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    val creatorUserId: String
) {
    val key: String get() = "$RYOT_COLLECTION_KEY_PREFIX$id"
    val isWatchlist: Boolean get() = isDefault && name == RYOT_WATCHLIST_NAME
    val isEditable: Boolean get() = name !in RYOT_SYSTEM_COLLECTIONS
}

/** One movie or show the user has interacted with, with the IDs Nuvio needs to match it to catalogs. */
data class RyotItem(
    val metadata: RyotMetadata,
    val imdbId: String? = null,
    val imdbResolved: Boolean = false,
    val user: RyotUserState = RyotUserState()
) {
    val id: String get() = metadata.id
    val lot: RyotLot get() = metadata.lot

    val tmdbId: Long? get() = metadata.identifier.toLongOrNull()?.takeIf { metadata.source == RYOT_SOURCE_TMDB }
    val tvdbId: String? get() = metadata.identifier.takeIf { metadata.source == RYOT_SOURCE_TVDB && it.isNotBlank() }

    /** The ID Nuvio's catalogs use: IMDb when known, else a prefixed TMDB/TVDB id Nuvio already understands. */
    val contentId: String
        get() = imdbId ?: tmdbId?.let { "tmdb:$it" } ?: tvdbId?.let { "tvdb:$it" } ?: "ryot:${metadata.id}"

    val aliases: Set<String>
        get() = buildSet {
            imdbId?.let(::add)
            tmdbId?.let { add("tmdb:$it") }
            tvdbId?.let { add("tvdb:$it") }
            add(contentId)
        }
}

data class RyotSnapshot(
    val userId: String,
    val items: Map<String, RyotItem> = emptyMap(),
    val collections: List<RyotCollection> = emptyList(),
    /** Collection id -> metadata ids in collection order. */
    val collectionItems: Map<String, List<String>> = emptyMap(),
    /** Metadata id -> when the user removed it from Continue Watching in Nuvio. */
    val hiddenFromProgress: Map<String, Long> = emptyMap(),
    /** "MOVIE:123" (TMDB) -> Ryot metadata id, for titles resolved but not in the library yet. */
    val resolvedIds: Map<String, String> = emptyMap(),
    val syncedAt: Long = 0L,
    val fullSyncedAt: Long = 0L
) {
    fun findByAlias(lot: RyotLot, alias: String): RyotItem? =
        items.values.firstOrNull { it.lot == lot && alias in it.aliases }

    fun findByTmdb(lot: RyotLot, tmdbId: Long): RyotItem? =
        items.values.firstOrNull { it.lot == lot && it.tmdbId == tmdbId }

    fun isHidden(item: RyotItem): Boolean {
        val hiddenAt = hiddenFromProgress[item.id] ?: return false
        return item.user.lastActivity <= hiddenAt
    }
}

// ---------------------------------------------------------------------------------------------
// Persistence codec (hand-written so the core can be compiled and tested without the plugin)
// ---------------------------------------------------------------------------------------------

internal object RyotSnapshotCodec {
    private const val VERSION = 1

    fun encode(snapshot: RyotSnapshot): String = buildJsonObject {
        put("version", VERSION)
        put("userId", snapshot.userId)
        put("syncedAt", snapshot.syncedAt)
        put("fullSyncedAt", snapshot.fullSyncedAt)
        putJsonArray("items") { snapshot.items.values.forEach { add(encodeItem(it)) } }
        putJsonArray("collections") {
            snapshot.collections.forEach { collection ->
                add(buildJsonObject {
                    put("id", collection.id)
                    put("name", collection.name)
                    put("isDefault", collection.isDefault)
                    put("creatorUserId", collection.creatorUserId)
                })
            }
        }
        putJsonObject("collectionItems") {
            snapshot.collectionItems.forEach { (id, ids) -> putJsonArray(id) { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
        }
        putJsonObject("hiddenFromProgress") { snapshot.hiddenFromProgress.forEach { (id, at) -> put(id, at) } }
        putJsonObject("resolvedIds") { snapshot.resolvedIds.forEach { (key, id) -> put(key, id) } }
    }.toString()

    fun decode(raw: String?): RyotSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = RyotJson.parseToJsonElement(raw).jsonObject
            if (root.int("version") != VERSION) return null
            RyotSnapshot(
                userId = root.string("userId") ?: return null,
                items = (root["items"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.let(::decodeItem) }
                    .associateBy(RyotItem::id),
                collections = (root["collections"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val json = element as? JsonObject ?: return@mapNotNull null
                    RyotCollection(
                        id = json.string("id") ?: return@mapNotNull null,
                        name = json.string("name") ?: return@mapNotNull null,
                        isDefault = json.bool("isDefault") ?: false,
                        creatorUserId = json.string("creatorUserId").orEmpty()
                    )
                },
                collectionItems = root.obj("collectionItems").orEmptyObject().mapValues { (_, value) ->
                    (value as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                },
                hiddenFromProgress = root.obj("hiddenFromProgress").orEmptyObject()
                    .mapNotNull { (key, value) -> (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()?.let { key to it } }
                    .toMap(),
                resolvedIds = root.obj("resolvedIds").orEmptyObject()
                    .mapNotNull { (key, value) -> (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { key to it } }
                    .toMap(),
                syncedAt = root.long("syncedAt") ?: 0L,
                fullSyncedAt = root.long("fullSyncedAt") ?: 0L
            )
        }.getOrNull()
    }

    private fun encodeItem(item: RyotItem): JsonObject = buildJsonObject {
        val m = item.metadata
        putJsonObject("metadata") {
            put("id", m.id)
            put("lot", m.lot.wire)
            put("source", m.source)
            put("identifier", m.identifier)
            put("title", m.title)
            m.year?.let { put("year", it) }
            m.poster?.let { put("poster", it) }
            m.backdrop?.let { put("backdrop", it) }
            m.runtimeMinutes?.let { put("runtimeMinutes", it) }
        }
        item.imdbId?.let { put("imdbId", it) }
        put("imdbResolved", item.imdbResolved)
        item.user.inProgress?.let { put("inProgress", encodeSeen(it)) }
        putJsonArray("history") { item.user.history.forEach { add(encodeSeen(it)) } }
    }

    private fun decodeItem(json: JsonObject): RyotItem? {
        val m = json.obj("metadata") ?: return null
        val metadata = RyotMetadata(
            id = m.string("id") ?: return null,
            lot = RyotLot.fromWire(m.string("lot")) ?: return null,
            source = m.string("source").orEmpty(),
            identifier = m.string("identifier").orEmpty(),
            title = m.string("title").orEmpty(),
            year = m.int("year"),
            poster = m.string("poster"),
            backdrop = m.string("backdrop"),
            runtimeMinutes = m.int("runtimeMinutes")
        )
        return RyotItem(
            metadata = metadata,
            imdbId = json.string("imdbId"),
            imdbResolved = json.bool("imdbResolved") ?: false,
            user = RyotUserState(
                inProgress = json.obj("inProgress")?.let(::decodeStoredSeen),
                history = (json["history"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeStoredSeen) }
            )
        )
    }

    private fun encodeSeen(seen: RyotSeen): JsonObject = buildJsonObject {
        put("id", seen.id)
        put("state", seen.state.wire)
        put("progress", seen.progress)
        seen.season?.let { put("season", it) }
        seen.episode?.let { put("episode", it) }
        seen.startedOn?.let { put("startedOn", it) }
        seen.finishedOn?.let { put("finishedOn", it) }
        put("lastUpdatedOn", seen.lastUpdatedOn)
    }

    private fun decodeStoredSeen(json: JsonObject): RyotSeen? {
        val id = json.string("id") ?: return null
        return RyotSeen(
            id = id,
            state = RyotSeenState.fromWire(json.string("state")),
            progress = json.double("progress") ?: 0.0,
            season = json.int("season"),
            episode = json.int("episode"),
            startedOn = json.long("startedOn"),
            finishedOn = json.long("finishedOn"),
            lastUpdatedOn = json.long("lastUpdatedOn") ?: 0L
        )
    }

    private fun JsonObject?.orEmptyObject(): JsonObject = this ?: JsonObject(emptyMap())
}
