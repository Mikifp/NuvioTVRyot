package com.nuvio.tv.data.ryot

import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal HTTP seam so the Ryot client can be exercised without Android or OkHttp.
 * The production implementation lives in [OkHttpRyotTransport].
 */
interface RyotTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): RyotHttpResponse
}

data class RyotHttpResponse(val status: Int, val body: String)

open class RyotApiException(message: String, val status: Int? = null) : Exception(message)

/** The server rejected the token (revoked, expired, or never valid). The account must reconnect. */
class RyotAuthException(message: String) : RyotApiException(message, 401)

/** The account has two-factor login enabled, so username/password alone cannot produce a token. */
class RyotTwoFactorRequiredException : RyotApiException("This Ryot account uses two-factor login. Generate an auth token in Ryot and paste it instead.")

/**
 * Accepts what people actually type on a TV remote: "ryot.example.com", "192.168.1.10:8000",
 * "https://ryot.example.com/", or the full GraphQL URL, and returns the bare base URL.
 */
fun normalizeRyotServerUrl(raw: String): String? {
    var value = raw.trim()
    if (value.isEmpty()) return null
    if (!value.contains("://")) {
        // Local addresses are almost always plain HTTP; anything else defaults to HTTPS.
        val host = value.substringBefore('/').substringBefore(':')
        value = if (isLikelyLocalHost(host)) "http://$value" else "https://$value"
    }
    value = value.trimEnd('/')
    for (suffix in listOf("/backend/graphql", "/backend")) {
        if (value.endsWith(suffix, ignoreCase = true)) value = value.dropLast(suffix.length)
    }
    value = value.trimEnd('/')
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrBlank()) return null
    return value
}

private fun isLikelyLocalHost(host: String): Boolean {
    val lower = host.lowercase()
    if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".lan") || lower.endsWith(".home.arpa")) return true
    val parts = lower.split('.').mapNotNull(String::toIntOrNull)
    if (parts.size != 4) return false
    return parts[0] == 10 || parts[0] == 127 ||
        (parts[0] == 192 && parts[1] == 168) ||
        (parts[0] == 172 && parts[1] in 16..31)
}

internal val RyotJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

class RyotGraphQlClient(
    private val transport: RyotTransport,
    serverUrl: String,
    private val token: String?
) {
    val baseUrl: String = serverUrl.trimEnd('/')
    private val endpoint = "$baseUrl/backend/graphql"

    suspend fun execute(query: String, variables: JsonObject = JsonObject(emptyMap())): JsonObject {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            token?.takeIf(String::isNotBlank)?.let { put("Authorization", "Bearer $it") }
        }
        val response = transport.post(endpoint, headers, body)
        if (response.status == 401 || response.status == 403) {
            throw RyotAuthException("Ryot rejected the saved token")
        }
        val root = runCatching { RyotJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
            ?: throw RyotApiException(
                if (response.status in 200..299) "Ryot returned an unreadable response"
                else "Ryot server answered HTTP ${response.status}",
                response.status
            )
        val errors = root["errors"] as? JsonArray
        if (!errors.isNullOrEmpty()) {
            val messages = errors.mapNotNull { (it as? JsonObject)?.string("message") }
            if (messages.any(::isRyotAuthMessage)) throw RyotAuthException("Ryot session is no longer valid")
            throw RyotApiException(messages.firstOrNull() ?: "Ryot request failed", response.status)
        }
        if (response.status !in 200..299) throw RyotApiException("Ryot server answered HTTP ${response.status}", response.status)
        return root["data"] as? JsonObject ?: throw RyotApiException("Ryot returned no data", response.status)
    }
}

internal fun isRyotAuthMessage(message: String): Boolean {
    val upper = message.uppercase()
    return "NO_SESSION_ID" in upper || "SESSION_EXPIRED" in upper || "NO_USER_ID" in upper
}

// ---------------------------------------------------------------------------------------------
// Typed operations
// ---------------------------------------------------------------------------------------------

data class RyotUser(val id: String, val name: String)

data class RyotIdPage(val ids: List<String>, val nextPage: Int?)

data class RyotProgressChange(val metadataId: String, val change: JsonObject)

class RyotApi(private val client: RyotGraphQlClient) {
    val baseUrl: String get() = client.baseUrl

    /** Returns a short-lived session key, or throws for bad credentials or two-factor accounts. */
    suspend fun login(username: String, password: String): String {
        val data = client.execute(
            """
            mutation LoginUser(${'$'}input: AuthUserInput!) {
              loginUser(input: ${'$'}input) {
                __typename
                ... on ApiKeyResponse { apiKey }
                ... on LoginError { error }
                ... on StringIdObject { id }
              }
            }
            """.trimIndent(),
            buildJsonObject {
                putJsonObject("input") {
                    putJsonObject("password") {
                        put("username", username)
                        put("password", password)
                    }
                }
            }
        )
        val result = data.obj("loginUser") ?: throw RyotApiException("Ryot returned no login result")
        result.string("apiKey")?.let { return it }
        when (result.string("__typename")) {
            "StringIdObject" -> throw RyotTwoFactorRequiredException()
            "LoginError" -> throw RyotApiException(loginErrorMessage(result.string("error")))
        }
        throw RyotApiException("Ryot login failed")
    }

    /** Creates a session with no expiry, which is what a TV should keep. */
    suspend fun generateAuthToken(): String =
        client.execute("mutation { generateAuthToken }").string("generateAuthToken")
            ?: throw RyotApiException("Ryot did not return a token")

    suspend fun logout() {
        client.execute("mutation { logoutUser }")
    }

    suspend fun userDetails(): RyotUser {
        val data = client.execute(
            """
            query {
              userDetails {
                __typename
                ... on UserDetails { id name }
                ... on UserDetailsError { error }
              }
            }
            """.trimIndent()
        )
        val details = data.obj("userDetails") ?: throw RyotApiException("Ryot returned no user")
        val id = details.string("id") ?: throw RyotAuthException("Ryot session is no longer valid")
        return RyotUser(id, details.string("name") ?: id)
    }

    suspend fun userMetadataList(lot: RyotLot, page: Int, take: Int): RyotIdPage {
        val data = client.execute(
            """
            query UserMetadataList(${'$'}input: UserMetadataListInput!) {
              userMetadataList(input: ${'$'}input) {
                response { items details { nextPage totalItems } }
              }
            }
            """.trimIndent(),
            buildJsonObject {
                putJsonObject("input") {
                    put("lot", lot.wire)
                    putJsonObject("search") {
                        put("page", page)
                        put("take", take)
                    }
                    putJsonObject("sort") {
                        put("by", "LAST_UPDATED")
                        put("order", "DESC")
                    }
                }
            }
        )
        return data.obj("userMetadataList")?.obj("response")?.idPage()
            ?: throw RyotApiException("Ryot returned no library page")
    }

    suspend fun search(lot: RyotLot, query: String): List<String> {
        val data = client.execute(
            """
            query MetadataSearch(${'$'}input: MetadataSearchInput!) {
              metadataSearch(input: ${'$'}input) { response { items } }
            }
            """.trimIndent(),
            buildJsonObject {
                putJsonObject("input") {
                    put("lot", lot.wire)
                    put("source", RYOT_SOURCE_TMDB)
                    putJsonObject("search") {
                        put("query", query)
                        put("page", 1)
                    }
                }
            }
        )
        return data.obj("metadataSearch")?.obj("response")?.stringList("items").orEmpty()
    }

    /**
     * Fetches catalog details and/or the user's history for many items in one request, using
     * GraphQL aliases. Ryot caches both server-side, so batches are cheap.
     */
    suspend fun details(
        metadataIds: List<String>,
        includeMetadata: Boolean,
        includeUser: Boolean
    ): Map<String, RyotDetails> {
        if (metadataIds.isEmpty() || (!includeMetadata && !includeUser)) return emptyMap()
        val result = linkedMapOf<String, RyotDetails>()
        metadataIds.distinct().chunked(DETAILS_BATCH_SIZE).forEach { batch ->
            val query = buildString {
                append("query Details(")
                append(batch.indices.joinToString(", ") { "\$id$it: String!" })
                append(") {\n")
                batch.indices.forEach { index ->
                    if (includeMetadata) append("  m$index: metadataDetails(metadataId: \$id$index) { response { $METADATA_FIELDS } }\n")
                    if (includeUser) append("  u$index: userMetadataDetails(metadataId: \$id$index) { response { $USER_FIELDS } }\n")
                }
                append("}")
            }
            val variables = buildJsonObject { batch.forEachIndexed { index, id -> put("id$index", id) } }
            val data = client.execute(query, variables)
            batch.forEachIndexed { index, id ->
                val metadata = if (includeMetadata) data.obj("m$index")?.obj("response")?.let(::decodeRyotMetadata) else null
                val user = if (includeUser) data.obj("u$index")?.obj("response")?.let(::decodeRyotUserState) else null
                result[id] = RyotDetails(metadata, user)
            }
        }
        return result
    }

    suspend fun collections(): List<RyotCollection> {
        val data = client.execute(
            """
            query {
              userCollectionsList {
                response { id name isDefault count creator { id name } }
              }
            }
            """.trimIndent()
        )
        val items = data.obj("userCollectionsList")?.get("response") as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            RyotCollection(
                id = item.string("id") ?: return@mapNotNull null,
                name = item.string("name") ?: return@mapNotNull null,
                isDefault = item.bool("isDefault") ?: false,
                creatorUserId = item.obj("creator")?.string("id").orEmpty()
            )
        }
    }

    /** Metadata ids in the collection, in the collection's own order. People and groups are skipped. */
    suspend fun collectionMetadataIds(collectionId: String): List<String> {
        val ids = mutableListOf<String>()
        var page: Int? = 1
        var guard = 0
        while (page != null && guard++ < MAX_COLLECTION_PAGES) {
            val data = client.execute(
                """
                query CollectionContents(${'$'}input: CollectionContentsInput!) {
                  collectionContents(input: ${'$'}input) {
                    response { results { items { entityId entityLot } details { nextPage } } }
                  }
                }
                """.trimIndent(),
                buildJsonObject {
                    putJsonObject("input") {
                        put("collectionId", collectionId)
                        putJsonObject("search") {
                            put("page", page)
                            put("take", COLLECTION_PAGE_SIZE)
                        }
                    }
                }
            )
            val results = data.obj("collectionContents")?.obj("response")?.obj("results") ?: break
            (results["items"] as? JsonArray).orEmpty().forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                if (item.string("entityLot") == "METADATA") item.string("entityId")?.let(ids::add)
            }
            page = results.obj("details")?.int("nextPage")
        }
        return ids
    }

    suspend fun deployProgressUpdates(changes: List<RyotProgressChange>) {
        if (changes.isEmpty()) return
        client.execute(
            """
            mutation Progress(${'$'}input: [MetadataProgressUpdateInput!]!) {
              deployBulkMetadataProgressUpdate(input: ${'$'}input)
            }
            """.trimIndent(),
            buildJsonObject {
                putJsonArray("input") {
                    changes.forEach { change ->
                        add(buildJsonObject {
                            put("metadataId", change.metadataId)
                            put("change", change.change)
                        })
                    }
                }
            }
        )
    }

    suspend fun deleteSeen(seenId: String) {
        client.execute(
            """
            mutation DeleteSeen(${'$'}seenId: String!) { deleteSeenItem(seenId: ${'$'}seenId) { id } }
            """.trimIndent(),
            buildJsonObject { put("seenId", seenId) }
        )
    }

    suspend fun changeCollection(add: Boolean, collection: RyotCollection, metadataIds: List<String>) {
        if (metadataIds.isEmpty()) return
        val field = if (add) "deployAddEntitiesToCollectionJob" else "deployRemoveEntitiesFromCollectionJob"
        client.execute(
            """
            mutation Collection(${'$'}input: ChangeCollectionToEntitiesInput!) { $field(input: ${'$'}input) }
            """.trimIndent(),
            buildJsonObject {
                putJsonObject("input") {
                    put("collectionName", collection.name)
                    put("creatorUserId", collection.creatorUserId)
                    putJsonArray("entities") {
                        metadataIds.forEach { id ->
                            add(buildJsonObject {
                                put("entityId", id)
                                put("entityLot", "METADATA")
                            })
                        }
                    }
                }
            }
        )
    }

    companion object {
        const val DETAILS_BATCH_SIZE = 20
        private const val COLLECTION_PAGE_SIZE = 200
        private const val MAX_COLLECTION_PAGES = 50

        private const val METADATA_FIELDS =
            "id identifier source lot title publishYear assets { remoteImages } " +
                "movieSpecifics { runtime } showSpecifics { runtime }"
        private const val SEEN_FIELDS =
            "id state progress startedOn finishedOn lastUpdatedOn showExtraInformation { season episode }"
        private const val USER_FIELDS = "inProgress { $SEEN_FIELDS } history { $SEEN_FIELDS }"

        private fun loginErrorMessage(code: String?): String = when (code) {
            "CREDENTIALS_MISMATCH", "USERNAME_DOES_NOT_EXIST" -> "Wrong Ryot username or password"
            "ACCOUNT_DISABLED" -> "This Ryot account is disabled"
            "INCORRECT_PROVIDER_CHOSEN" -> "This Ryot account signs in with OIDC. Generate an auth token in Ryot and paste it instead."
            else -> "Ryot login failed"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Progress change payloads (wire format of MetadataProgressUpdateChange)
// ---------------------------------------------------------------------------------------------

internal object RyotChanges {
    fun newInProgress(startedOnEpochMs: Long, season: Int?, episode: Int?): JsonObject = buildJsonObject {
        putJsonObject("createNewInProgress") {
            put("startedOn", ryotTimestamp(startedOnEpochMs))
            putEpisode(season, episode)
        }
    }

    fun latestInProgress(percent: Double): JsonObject = buildJsonObject {
        put("changeLatestInProgress", ryotDecimal(percent))
    }

    fun completed(finishedOnEpochMs: Long?, season: Int?, episode: Int?): JsonObject = buildJsonObject {
        putJsonObject("createNewCompleted") {
            if (finishedOnEpochMs != null) {
                putJsonObject("finishedOnDate") {
                    put("timestamp", ryotTimestamp(finishedOnEpochMs))
                    putEpisode(season, episode)
                }
            } else {
                putJsonObject("withoutDates") { putEpisode(season, episode) }
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putEpisode(season: Int?, episode: Int?) {
        if (season != null && episode != null) {
            put("showSeasonNumber", season)
            put("showEpisodeNumber", episode)
        }
    }
}

/** Ryot's Decimal scalar travels as a string; two decimals is plenty for a percentage. */
internal fun ryotDecimal(value: Double): String {
    val clamped = value.coerceIn(0.0, 100.0)
    val rounded = Math.round(clamped * 100.0) / 100.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
}

internal fun ryotTimestamp(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

internal fun parseRyotTimestamp(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

// ---------------------------------------------------------------------------------------------
// Decoding helpers
// ---------------------------------------------------------------------------------------------

internal fun decodeRyotMetadata(json: JsonObject): RyotMetadata? {
    val id = json.string("id") ?: return null
    val lot = RyotLot.fromWire(json.string("lot")) ?: return null
    val images = json.obj("assets")?.stringList("remoteImages").orEmpty()
    return RyotMetadata(
        id = id,
        lot = lot,
        source = json.string("source").orEmpty(),
        identifier = json.string("identifier").orEmpty(),
        title = json.string("title").orEmpty(),
        year = json.int("publishYear"),
        poster = images.firstOrNull(),
        backdrop = images.getOrNull(1),
        runtimeMinutes = json.obj("movieSpecifics")?.int("runtime") ?: json.obj("showSpecifics")?.int("runtime")
    )
}

internal fun decodeRyotUserState(json: JsonObject): RyotUserState {
    val inProgress = json.obj("inProgress")?.let(::decodeRyotSeen)
    val history = (json["history"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeRyotSeen) }
    return RyotUserState(inProgress = inProgress, history = history)
}

internal fun decodeRyotSeen(json: JsonObject): RyotSeen? {
    val id = json.string("id") ?: return null
    val show = json.obj("showExtraInformation")
    return RyotSeen(
        id = id,
        state = RyotSeenState.fromWire(json.string("state")),
        progress = json.string("progress")?.toDoubleOrNull() ?: json.double("progress") ?: 0.0,
        season = show?.int("season"),
        episode = show?.int("episode"),
        startedOn = parseRyotTimestamp(json.string("startedOn")),
        finishedOn = parseRyotTimestamp(json.string("finishedOn")),
        lastUpdatedOn = parseRyotTimestamp(json.string("lastUpdatedOn")) ?: 0L
    )
}

private fun JsonObject.idPage(): RyotIdPage =
    RyotIdPage(stringList("items"), obj("details")?.int("nextPage"))

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

internal fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()

internal fun JsonObject.double(key: String): Double? = string(key)?.toDoubleOrNull()

internal fun JsonObject.bool(key: String): Boolean? = string(key)?.toBooleanStrictOrNull()

internal fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

internal fun JsonElement?.orEmptyArray(): JsonArray = this as? JsonArray ?: buildJsonArray { }
