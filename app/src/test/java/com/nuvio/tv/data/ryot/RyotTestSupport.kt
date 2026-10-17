package com.nuvio.tv.data.ryot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Records requests and answers them from a handler keyed on the GraphQL text. */
internal class FakeRyotTransport(
    private val handler: (query: String, variables: JsonObject) -> String
) : RyotTransport {
    data class Request(val url: String, val headers: Map<String, String>, val query: String, val variables: JsonObject)

    val requests = mutableListOf<Request>()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): RyotHttpResponse {
        val root = RyotJson.parseToJsonElement(body).jsonObject
        val query = root.string("query").orEmpty()
        val variables = root["variables"] as? JsonObject ?: JsonObject(emptyMap())
        requests += Request(url, headers, query, variables)
        return RyotHttpResponse(200, handler(query, variables))
    }

    fun mutations(): List<Request> = requests.filter { it.query.trimStart().startsWith("mutation") }
}

internal class FakeIdMapper(
    private val tmdbToImdb: Map<Pair<RyotLot, Long>, String> = emptyMap()
) : RyotIdMapper {
    val imdbLookups = mutableListOf<Long>()

    override suspend fun imdbFor(lot: RyotLot, tmdbId: Long): String? {
        imdbLookups += tmdbId
        return tmdbToImdb[lot to tmdbId]
    }

    override suspend fun tmdbFor(lot: RyotLot, imdbId: String): Long? =
        tmdbToImdb.entries.firstOrNull { it.key.first == lot && it.value == imdbId }?.key?.second
}

internal fun data(json: String) = """{"data":$json}"""

internal fun metadataJson(id: String, lot: String, tmdb: String, title: String, year: Int = 2020, runtime: Int? = 100) = """
    {"response":{"id":"$id","identifier":"$tmdb","source":"TMDB","lot":"$lot","title":"$title","publishYear":$year,
     "assets":{"remoteImages":["https://img/$id-poster.jpg","https://img/$id-back.jpg"]},
     "movieSpecifics":${if (lot == "MOVIE") """{"runtime":$runtime}""" else "null"},
     "showSpecifics":${if (lot == "SHOW") """{"runtime":$runtime}""" else "null"}}}
""".trimIndent()

internal fun seenJson(
    id: String,
    state: String,
    progress: String,
    updated: String,
    season: Int? = null,
    episode: Int? = null,
    finished: String? = null
) = """
    {"id":"$id","state":"$state","progress":"$progress","startedOn":"$updated","finishedOn":${finished?.let { "\"$it\"" } ?: "null"},
     "lastUpdatedOn":"$updated","showExtraInformation":${if (season != null) """{"season":$season,"episode":$episode}""" else "null"}}
""".trimIndent()

internal fun userJson(inProgress: String?, history: List<String>) =
    """{"response":{"inProgress":${inProgress ?: "null"},"history":[${history.joinToString(",")}]}}"""

/** Answers a batched details query using per-id metadata and user payloads. */
internal fun answerDetails(
    query: String,
    variables: JsonObject,
    metadata: Map<String, String>,
    users: Map<String, String>
): String {
    val parts = mutableListOf<String>()
    variables.keys.forEach { key ->
        val index = key.removePrefix("id")
        val id = variables.string(key)!!
        if (query.contains("m$index: metadataDetails")) parts += "\"m$index\":${metadata[id] ?: "null"}"
        if (query.contains("u$index: userMetadataDetails")) parts += "\"u$index\":${users[id] ?: userJson(null, emptyList())}"
    }
    return data("{${parts.joinToString(",")}}")
}

internal fun ms(iso: String): Long = java.time.Instant.parse(iso).toEpochMilli()
