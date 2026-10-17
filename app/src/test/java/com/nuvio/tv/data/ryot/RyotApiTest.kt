package com.nuvio.tv.data.ryot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RyotApiTest {

    @Test
    fun `server urls typed on a remote are normalized`() {
        assertEquals("https://ryot.example.com", normalizeRyotServerUrl("ryot.example.com"))
        assertEquals("https://ryot.example.com", normalizeRyotServerUrl(" https://ryot.example.com/ "))
        assertEquals("https://ryot.example.com", normalizeRyotServerUrl("https://ryot.example.com/backend/graphql"))
        assertEquals("http://192.168.1.10:8000", normalizeRyotServerUrl("192.168.1.10:8000"))
        assertEquals("http://nas.local:8000", normalizeRyotServerUrl("nas.local:8000"))
        assertEquals("https://example.com/ryot", normalizeRyotServerUrl("https://example.com/ryot/backend"))
        assertNull(normalizeRyotServerUrl(""))
        assertNull(normalizeRyotServerUrl("ftp://example.com"))
    }

    @Test
    fun `requests carry the bearer token to the graphql endpoint`() = runBlocking {
        val transport = FakeRyotTransport { _, _ -> data("""{"userDetails":{"__typename":"UserDetails","id":"usr_1","name":"falk"}}""") }
        val user = RyotApi(RyotGraphQlClient(transport, "https://ryot.example.com/", "tok")).userDetails()
        assertEquals(RyotUser("usr_1", "falk"), user)
        val request = transport.requests.single()
        assertEquals("https://ryot.example.com/backend/graphql", request.url)
        assertEquals("Bearer tok", request.headers["Authorization"])
    }

    @Test
    fun `expired sessions surface as auth errors`() = runBlocking {
        val transport = FakeRyotTransport { _, _ -> """{"data":null,"errors":[{"message":"SESSION_EXPIRED"}]}""" }
        try {
            RyotApi(RyotGraphQlClient(transport, "https://r", "tok")).collections()
            fail("expected auth error")
        } catch (expected: RyotAuthException) {
        }
        val invalid = FakeRyotTransport { _, _ -> data("""{"userDetails":{"__typename":"UserDetailsError","error":"SESSION_INVALID"}}""") }
        try {
            RyotApi(RyotGraphQlClient(invalid, "https://r", "tok")).userDetails()
            fail("expected auth error")
        } catch (expected: RyotAuthException) {
        }
    }

    @Test
    fun `other graphql errors keep their message`() = runBlocking {
        val transport = FakeRyotTransport { _, _ -> """{"data":null,"errors":[{"message":"Something broke"}]}""" }
        try {
            RyotApi(RyotGraphQlClient(transport, "https://r", "tok")).collections()
            fail("expected error")
        } catch (expected: RyotApiException) {
            assertTrue(expected !is RyotAuthException)
            assertEquals("Something broke", expected.message)
        }
    }

    @Test
    fun `login distinguishes keys, bad credentials and two-factor accounts`() = runBlocking {
        fun api(body: String) = RyotApi(RyotGraphQlClient(FakeRyotTransport { _, _ -> data(body) }, "https://r", null))
        assertEquals("key-1", api("""{"loginUser":{"__typename":"ApiKeyResponse","apiKey":"key-1"}}""").login("u", "p"))
        try {
            api("""{"loginUser":{"__typename":"StringIdObject","id":"usr"}}""").login("u", "p")
            fail("expected two-factor")
        } catch (expected: RyotTwoFactorRequiredException) {
        }
        try {
            api("""{"loginUser":{"__typename":"LoginError","error":"CREDENTIALS_MISMATCH"}}""").login("u", "p")
            fail("expected failure")
        } catch (expected: RyotApiException) {
            assertEquals("Wrong Ryot username or password", expected.message)
        }
    }

    @Test
    fun `details are batched with aliases and decoded`() = runBlocking {
        val ids = (1..25).map { "meta_$it" }
        val transport = FakeRyotTransport { query, variables ->
            answerDetails(
                query, variables,
                metadata = ids.associateWith { metadataJson(it, "SHOW", "100", "Show $it") },
                users = mapOf(
                    "meta_3" to userJson(
                        seenJson("seen_a", "IN_PROGRESS", "42.5", "2026-10-01T20:00:00Z", season = 2, episode = 4),
                        listOf(seenJson("seen_b", "COMPLETED", "100", "2026-09-30T20:00:00Z", 2, 3, finished = "2026-09-30T21:00:00Z"))
                    )
                )
            )
        }
        val result = RyotApi(RyotGraphQlClient(transport, "https://r", "tok")).details(ids, includeMetadata = true, includeUser = true)
        assertEquals(2, transport.requests.size) // 20 + 5
        assertEquals(25, result.size)
        val details = result.getValue("meta_3")
        assertEquals(RyotLot.SHOW, details.metadata!!.lot)
        assertEquals("https://img/meta_3-poster.jpg", details.metadata!!.poster)
        assertEquals(42.5, details.user!!.inProgress!!.progress, 0.0001)
        assertEquals(2, details.user!!.inProgress!!.season)
        assertEquals(ms("2026-09-30T21:00:00Z"), details.user!!.history.single().finishedOn)
    }

    @Test
    fun `decimal and change payloads match the ryot wire format`() {
        assertEquals("42.5", ryotDecimal(42.5))
        assertEquals("100", ryotDecimal(100.0))
        assertEquals("33.33", ryotDecimal(33.3333))
        assertEquals("0", ryotDecimal(-4.0))
        assertEquals("""{"changeLatestInProgress":"57"}""", RyotChanges.latestInProgress(57.0).toString())
        assertEquals(
            """{"createNewInProgress":{"startedOn":"2026-10-01T20:00:00Z","showSeasonNumber":1,"showEpisodeNumber":2}}""",
            RyotChanges.newInProgress(ms("2026-10-01T20:00:00Z"), 1, 2).toString()
        )
        assertEquals(
            """{"createNewCompleted":{"withoutDates":{}}}""",
            RyotChanges.completed(null, null, null).toString()
        )
    }
}
