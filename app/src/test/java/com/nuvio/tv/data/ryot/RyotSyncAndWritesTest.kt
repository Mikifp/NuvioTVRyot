package com.nuvio.tv.data.ryot

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RyotSyncAndWritesTest {

    /** A small fake Ryot server: two movies, one show, a watchlist and an auto-managed collection. */
    private class Server {
        val metadata = mutableMapOf(
            "movie_a" to metadataJson("movie_a", "MOVIE", "603", "The Matrix", 1999, 136),
            "movie_b" to metadataJson("movie_b", "MOVIE", "27205", "Inception", 2010, 148),
            "show_a" to metadataJson("show_a", "SHOW", "1396", "Breaking Bad", 2008, 47)
        )
        val users = mutableMapOf(
            "movie_a" to userJson(null, listOf(seenJson("s1", "COMPLETED", "100", "2026-10-01T20:00:00Z", finished = "2026-10-01T22:00:00Z"))),
            "movie_b" to userJson(seenJson("s2", "IN_PROGRESS", "40", "2026-10-05T20:00:00Z"), listOf(seenJson("s2", "IN_PROGRESS", "40", "2026-10-05T20:00:00Z"))),
            "show_a" to userJson(
                null,
                listOf(
                    seenJson("s3", "COMPLETED", "100", "2026-10-03T20:00:00Z", 1, 1, "2026-10-03T21:00:00Z"),
                    seenJson("s4", "COMPLETED", "100", "2026-10-04T20:00:00Z", 1, 2, "2026-10-04T21:00:00Z")
                )
            )
        )
        var movies = listOf("movie_b", "movie_a")
        var shows = listOf("show_a")
        val watchlist = mutableListOf("movie_b")

        val transport = FakeRyotTransport { query, variables -> answer(query, variables) }
        val api = RyotApi(RyotGraphQlClient(transport, "https://ryot.test", "tok"))

        private fun answer(query: String, variables: JsonObject): String = when {
            "userDetails" in query -> data("""{"userDetails":{"__typename":"UserDetails","id":"usr_1","name":"falk"}}""")
            "userMetadataList" in query -> {
                val input = variables.obj("input")!!
                val ids = if (input.string("lot") == "MOVIE") movies else shows
                data("""{"userMetadataList":{"response":{"items":[${ids.joinToString(",") { "\"$it\"" }}],"details":{"nextPage":null,"totalItems":${ids.size}}}}}""")
            }
            "userCollectionsList" in query -> data(
                """{"userCollectionsList":{"response":[
                    {"id":"col_w","name":"Watchlist","isDefault":true,"count":1,"creator":{"id":"usr_1","name":"falk"}},
                    {"id":"col_ip","name":"In Progress","isDefault":true,"count":1,"creator":{"id":"usr_1","name":"falk"}}]}}"""
            )
            "collectionContents" in query -> data(
                """{"collectionContents":{"response":{"results":{"items":[${watchlist.joinToString(",") { """{"entityId":"$it","entityLot":"METADATA"}""" }},{"entityId":"person_1","entityLot":"PERSON"}],"details":{"nextPage":null}}}}}"""
            )
            "metadataSearch" in query -> data("""{"metadataSearch":{"response":{"items":["movie_x","movie_c"]}}}""")
            "metadataDetails" in query || "userMetadataDetails" in query -> answerDetails(query, variables, metadata, users)
            "deployBulkMetadataProgressUpdate" in query -> data("""{"deployBulkMetadataProgressUpdate":true}""")
            "deleteSeenItem" in query -> data("""{"deleteSeenItem":{"id":"x"}}""")
            "EntitiesToCollectionJob" in query -> data("""{"ok":true}""")
            else -> error("unexpected query: $query")
        }
    }

    private val ids = FakeIdMapper(
        mapOf(
            (RyotLot.MOVIE to 603L) to "tt0133093",
            (RyotLot.MOVIE to 27205L) to "tt1375666",
            (RyotLot.SHOW to 1396L) to "tt0903747",
            (RyotLot.MOVIE to 550L) to "tt0137523"
        )
    )

    private fun fullSnapshot(server: Server, clock: Long = ms("2026-10-10T12:00:00Z")): RyotSnapshot = runBlocking {
        RyotSyncEngine(server.api, ids) { clock }.sync(null, forceFull = true)
    }

    @Test
    fun `full sync builds items, collections and imdb ids`() {
        val snapshot = fullSnapshot(Server())
        assertEquals("usr_1", snapshot.userId)
        assertEquals(setOf("movie_a", "movie_b", "show_a"), snapshot.items.keys)
        assertEquals("tt0133093", snapshot.items.getValue("movie_a").imdbId)
        assertEquals("tt0133093", snapshot.items.getValue("movie_a").contentId)
        // Auto-managed collections are not offered as library lists.
        assertEquals(listOf("Watchlist"), snapshot.collections.map { it.name })
        // People in collections are ignored.
        assertEquals(listOf("movie_b"), snapshot.collectionItems["col_w"])
        // Survives persistence.
        assertEquals(snapshot, RyotSnapshotCodec.decode(RyotSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `incremental sync reuses metadata and imdb ids`() = runBlocking {
        val server = Server()
        val first = fullSnapshot(server)
        server.transport.requests.clear()
        ids.imdbLookups.clear()
        server.users["movie_b"] = userJson(
            seenJson("s2", "IN_PROGRESS", "75", "2026-10-11T20:00:00Z"),
            listOf(seenJson("s2", "IN_PROGRESS", "75", "2026-10-11T20:00:00Z"))
        )
        val second = RyotSyncEngine(server.api, ids) { ms("2026-10-10T21:00:00Z") }.sync(first, forceFull = false)
        assertEquals(75.0, second.items.getValue("movie_b").user.inProgress!!.progress, 0.0)
        assertTrue("imdb ids are not looked up again", ids.imdbLookups.isEmpty())
        assertFalse(
            "catalog metadata is not refetched",
            server.transport.requests.any { "metadataDetails(" in it.query && "userMetadataDetails" !in it.query.substringAfter("metadataDetails(") }
        )
        assertEquals(first.fullSyncedAt, second.fullSyncedAt)
    }

    @Test
    fun `projection feeds continue watching, next up and watched badges`() {
        val projection = RyotProjection(fullSnapshot(Server()), "https://ryot.test")
        val progress = projection.progress.single()
        assertEquals("tt1375666", progress.contentId)
        assertEquals(40f, progress.progressPercent)
        assertEquals(148L * 60_000L, progress.duration)
        assertEquals("https://ryot.test/media/item/movie_b", progress.trackingSourceUrl)

        val seed = projection.nextUp(preferFurthestEpisode = false).single()
        assertEquals("tt0903747", seed.contentId)
        assertEquals(1 to 2, seed.season to seed.episode)

        assertTrue(projection.isWatched("tt0133093", null, null))
        assertTrue(projection.isWatched("tmdb:603", null, null))
        assertFalse(projection.isWatched("tt1375666", null, null))
        assertTrue(projection.isWatched("tt0903747", 1, 1))
        assertFalse(projection.isWatched("tt0903747", 1, 3))

        assertEquals(listOf("Watchlist"), projection.tabs.map { it.title })
        assertEquals(setOf("ryot:collection:col_w"), projection.membership("tt1375666", "movie"))
        assertEquals("tt1375666", projection.libraryEntries.single().id)
    }

    @Test
    fun `a finished later episode supersedes a stale in-progress one`() {
        val server = Server()
        server.users["show_a"] = userJson(
            seenJson("s5", "IN_PROGRESS", "30", "2026-10-05T20:00:00Z", 1, 3),
            listOf(
                seenJson("s5", "IN_PROGRESS", "30", "2026-10-05T20:00:00Z", 1, 3),
                seenJson("s6", "COMPLETED", "100", "2026-10-06T20:00:00Z", 1, 4, "2026-10-06T21:00:00Z")
            )
        )
        val projection = RyotProjection(fullSnapshot(server), null)
        assertNull(projection.progress.firstOrNull { it.contentId == "tt0903747" })
    }

    @Test
    fun `hidden titles leave continue watching until there is new activity`() {
        val snapshot = fullSnapshot(Server()).withHidden("movie_b", ms("2026-10-06T00:00:00Z"))
        assertTrue(RyotProjection(snapshot, null).progress.isEmpty())
        assertTrue(RyotProjection(snapshot, null).isHidden("tt1375666"))
        val outcome = RyotScrobbleOutcome("movie_b", null, null, 60.0, completed = false)
        val resumed = snapshot.withScrobble(outcome, ms("2026-10-07T00:00:00Z"))
        assertEquals(60f, RyotProjection(resumed, null).progress.single().progressPercent)
    }

    // -----------------------------------------------------------------------------------------
    // Resolver
    // -----------------------------------------------------------------------------------------

    private fun movie(imdb: String?, tmdb: Long? = null, title: String? = "Fight Club") = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = title,
        ids = TrackingExternalIds(imdb = imdb, tmdb = tmdb)
    )

    @Test
    fun `resolver prefers the synced library and only searches as a last resort`() = runBlocking {
        val server = Server()
        val snapshot = fullSnapshot(server)
        server.transport.requests.clear()
        val resolver = RyotResolver(server.api, ids)

        assertEquals(RyotResolution("movie_a", RyotLot.MOVIE), resolver.resolve(snapshot, movie("tt0133093")))
        assertTrue(server.transport.requests.isEmpty())

        server.metadata["movie_c"] = metadataJson("movie_c", "MOVIE", "550", "Fight Club", 1999)
        server.metadata["movie_x"] = metadataJson("movie_x", "MOVIE", "999", "Fight Club 2", 2021)
        val searched = resolver.resolve(snapshot, movie("tt0137523"))!!
        assertEquals("movie_c", searched.metadataId)
        assertEquals("MOVIE:550", searched.cacheKey)

        server.transport.requests.clear()
        val remembered = snapshot.withResolved(searched)
        assertEquals("movie_c", resolver.resolve(remembered, movie("tt0137523"))!!.metadataId)
        assertTrue(server.transport.requests.isEmpty())

        assertNull("no ids and no match", resolver.resolve(snapshot, movie(null, null, null)))
    }

    // -----------------------------------------------------------------------------------------
    // Scrobbling
    // -----------------------------------------------------------------------------------------

    private fun changesOf(request: FakeRyotTransport.Request): List<JsonObject> =
        (request.variables["input"] as JsonArray).map { (it as JsonObject).obj("change")!! }

    @Test
    fun `scrobbling follows the ryot in-progress lifecycle`() = runBlocking {
        val server = Server()
        var clock = ms("2026-10-12T20:00:00Z")
        val writer = RyotScrobbleWriter(server.api) { clock }
        val movie = RyotResolution("movie_c", RyotLot.MOVIE)
        server.users["movie_c"] = userJson(null, emptyList())

        assertNull("zapping is ignored", writer.scrobble(movie, null, null, TrackingScrobbleAction.START, 0.5))
        assertTrue(server.transport.mutations().isEmpty())

        val paused = writer.scrobble(movie, null, null, TrackingScrobbleAction.PAUSE, 25.0)!!
        assertFalse(paused.completed)
        val first = changesOf(server.transport.mutations().last())
        assertEquals(listOf("createNewInProgress", "changeLatestInProgress"), first.map { it.keys.single() })

        // Ryot hasn't run the job yet; the writer must not open a second entry.
        clock += 60_000
        writer.scrobble(movie, null, null, TrackingScrobbleAction.START, 25.0)
        assertEquals("same percentage is skipped", 1, server.transport.mutations().size)
        writer.scrobble(movie, null, null, TrackingScrobbleAction.PAUSE, 50.0)
        assertEquals(listOf("changeLatestInProgress"), changesOf(server.transport.mutations().last()).map { it.keys.single() })

        val done = writer.scrobble(movie, null, null, TrackingScrobbleAction.STOP, 93.0)!!
        assertTrue(done.completed)
        assertEquals("100", changesOf(server.transport.mutations().last()).single().string("changeLatestInProgress"))

        assertEquals("starting near the end does not complete", 99.0,
            writer.scrobble(movie, null, null, TrackingScrobbleAction.START, 99.5)!!.percent, 0.0)
    }

    @Test
    fun `a new episode opens a new entry and shows need episode numbers`() = runBlocking {
        val server = Server()
        server.users["show_a"] = userJson(seenJson("s7", "IN_PROGRESS", "20", "2026-10-05T20:00:00Z", 1, 3), emptyList())
        val writer = RyotScrobbleWriter(server.api) { ms("2026-10-12T20:00:00Z") }
        val show = RyotResolution("show_a", RyotLot.SHOW)

        assertNull(writer.scrobble(show, null, null, TrackingScrobbleAction.PAUSE, 50.0))

        writer.scrobble(show, 1, 3, TrackingScrobbleAction.PAUSE, 40.0)
        assertEquals(listOf("changeLatestInProgress"), changesOf(server.transport.mutations().last()).map { it.keys.single() })

        writer.scrobble(show, 1, 4, TrackingScrobbleAction.PAUSE, 10.0)
        val changes = changesOf(server.transport.mutations().last())
        assertEquals(listOf("createNewInProgress", "changeLatestInProgress"), changes.map { it.keys.single() })
        assertEquals(4, changes.first().obj("createNewInProgress")!!.int("showEpisodeNumber"))
    }

    // -----------------------------------------------------------------------------------------
    // History
    // -----------------------------------------------------------------------------------------

    @Test
    fun `history marks skip series-level marks and recent duplicates`() {
        val snapshot = fullSnapshot(Server())
        val show = RyotResolution("show_a", RyotLot.SHOW)
        val changes = ryotHistoryChanges(
            snapshot,
            listOf(
                RyotHistoryMark(show, null, null, ms("2026-10-12T20:00:00Z")),
                RyotHistoryMark(show, 1, 2, ms("2026-10-04T21:05:00Z")),
                RyotHistoryMark(show, 1, 3, ms("2026-10-12T20:00:00Z")),
                RyotHistoryMark(RyotResolution("movie_b", RyotLot.MOVIE), null, null, null)
            )
        )
        assertEquals(2, changes.size)
        val episode = changes.first().change.obj("createNewCompleted")!!.obj("finishedOnDate")!!
        assertEquals(3, episode.int("showEpisodeNumber"))
        assertNotNull(changes.last().change.obj("createNewCompleted")!!.obj("withoutDates"))
    }

    @Test
    fun `unwatching removes only the matching completed entries`() {
        val state = RyotUserState(
            inProgress = RyotSeen("ip", RyotSeenState.IN_PROGRESS, 30.0, 1, 3),
            history = listOf(
                RyotSeen("a", RyotSeenState.COMPLETED, 100.0, 1, 1),
                RyotSeen("b", RyotSeenState.COMPLETED, 100.0, 1, 2),
                RyotSeen("ip", RyotSeenState.IN_PROGRESS, 30.0, 1, 3)
            )
        )
        assertEquals(listOf("b"), ryotSeensToDelete(state, RyotLot.SHOW, 1, 2).map { it.id })
        assertEquals(listOf("a", "b"), ryotSeensToDelete(state, RyotLot.SHOW, null, null).map { it.id })
    }

    @Test
    fun `episode references carry season and number`() {
        val ref = TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            ids = TrackingExternalIds(imdb = "tt0903747"),
            episode = TrackingEpisode(season = 2, number = 5)
        )
        assertEquals(RyotLot.SHOW, ref.ryotLot())
        assertEquals(2 to 5, com.nuvio.tv.core.tracking.TrackingHistoryItem(ref).ryotEpisode())
    }
}
