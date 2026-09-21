package com.distronode.districtai.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The calls feed and the lazy transcript fetch, over real HTTP. */
class CallsFeedTest {

    private lateinit var server: MockWebServer
    private lateinit var refreshApi: FakeRefreshApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        refreshApi = FakeRefreshApi().apply { rotating() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api() = HttpDistrictApi(
        DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient(),
            tokens = signedInCoordinator(refreshApi),
        ),
    )

    private val oneCall = """
        [{"id":"c1","type":"inbound","number":"Ada","status":"completed","duration":"1m 5s",
          "time":"Aug 15, 02:30 PM","aiSummary":"s","hasTranscript":false,"callerName":"Ada",
          "summary":"","createdAt":"2026-08-15T14:30:00.000Z"}]
    """.trimIndent()

    @Test
    fun `decodes a bare array, not an envelope`() = runTest {
        // ⛔ This route is `NextResponse.json(calls)` while almost every other district route
        // answers `{success, ...}`. Expecting an envelope here fails to parse every page.
        server.enqueue(MockResponse(code = 200, body = oneCall))

        val result = api().calls("ws-1", limit = 25, offset = 0)

        assertTrue("expected Success, got $result", result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).value.size)
        assertEquals("c1", result.value.single().id)
    }

    @Test
    fun `sends limit and offset as query parameters`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "[]"))

        api().calls("ws-7", limit = 25, offset = 50)

        val url = server.takeRequest().url
        assertEquals("/api/district/calls", url.encodedPath)
        assertEquals("ws-7", url.queryParameter("workspaceId"))
        assertEquals("25", url.queryParameter("limit"))
        assertEquals("50", url.queryParameter("offset"))
    }

    @Test
    fun `an empty feed is a success with no rows, not a failure`() = runTest {
        // A workspace that has never taken a call. Distinguishing this from a failed read is the
        // difference between "no calls yet" and a misleading error.
        server.enqueue(MockResponse(code = 200, body = "[]"))

        val result = api().calls("ws-1", limit = 25, offset = 0)

        assertEquals(ApiResult.Success(emptyList<Nothing>()), result)
    }

    @Test
    fun `a feed row carrying an analysis object decodes`() = runTest {
        // ⛔ REGRESSION GUARD. `analysis` is a Prisma Json? column and reaches the client as an
        // OBJECT. It was originally typed String? here, which would have thrown on the phone for
        // every call that had one — the seed data all had it null, so nothing caught it until a
        // real database-backed response was compared.
        val withAnalysis = """
            [{"id":"c1","type":"inbound","number":"Ada","status":"completed","duration":"1m 5s",
              "time":"t","aiSummary":"s","hasTranscript":false,"callerName":"Ada","summary":"",
              "createdAt":"2026-08-15T14:30:00.000Z",
              "analysis":{"keyPoints":["a"],"objections":[],"topics":["t"],"actionItems":[],
                          "followUpSuggested":true}}]
        """.trimIndent()
        server.enqueue(MockResponse(code = 200, body = withAnalysis))

        val result = api().calls("ws-1", limit = 25, offset = 0) as ApiResult.Success

        assertEquals(listOf("a"), result.value.single().analysis?.keyPoints)
    }

    @Test
    fun `a partially-populated analysis object still decodes`() = runTest {
        // ⚠️ Every field on CallAnalysis is optional with a default because the column is Json?
        // and nothing in the database enforces its shape — rows written by an earlier pipeline
        // legitimately carry fewer keys.
        val partial = """
            [{"id":"c1","type":"inbound","number":"Ada","status":"completed","duration":"0s",
              "time":"t","aiSummary":"s","hasTranscript":false,"callerName":"Ada","summary":"",
              "createdAt":"2026-08-15T14:30:00.000Z","analysis":{"topics":["only-topics"]}}]
        """.trimIndent()
        server.enqueue(MockResponse(code = 200, body = partial))

        val result = api().calls("ws-1", limit = 25, offset = 0) as ApiResult.Success
        val analysis = result.value.single().analysis

        assertEquals(listOf("only-topics"), analysis?.topics)
        assertEquals("missing keys default rather than failing", emptyList<String>(), analysis?.keyPoints)
    }

    @Test
    fun `a feed failure is mapped, not returned as an empty page`() = runTest {
        server.enqueue(MockResponse(code = 403, body = """{"error":"Forbidden: Insufficient workspace privileges"}"""))

        val result = api().calls("ws-1", limit = 25, offset = 0)

        assertEquals(ApiResult.Forbidden("Forbidden: Insufficient workspace privileges"), result)
    }

    // ── Transcript ───────────────────────────────────────────────────────────

    @Test
    fun `decodes the transcript envelope`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"transcript":"Agent: hello."}"""))

        val result = api().callTranscript("ws-1", "c1") as ApiResult.Success

        assertEquals("Agent: hello.", result.value.transcript)
        assertTrue(result.value.hasTranscript)
    }

    @Test
    fun `an absent transcript is empty, not null`() = runTest {
        // ⚠️ The handler does `call.transcript || ""`, so a call with no transcript sends "". A null
        // check would never fire; emptiness is the test.
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"transcript":""}"""))

        val result = api().callTranscript("ws-1", "c1") as ApiResult.Success

        assertEquals("", result.value.transcript)
        assertTrue("an empty transcript must not read as present", !result.value.hasTranscript)
    }

    @Test
    fun `a missing call is a NotFound`() = runTest {
        server.enqueue(MockResponse(code = 404, body = """{"success":false,"error":"Call not found"}"""))

        assertEquals(ApiResult.NotFound("Call not found"), api().callTranscript("ws-1", "gone"))
    }

    @Test
    fun `percent-encodes a call id so it cannot escape its route`() = runTest {
        // ⚠️ addPathSegments encodes each segment, so an id containing a slash stays one segment
        // rather than redirecting the request at another endpoint.
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"transcript":""}"""))

        api().callTranscript("ws-1", "a/../../admin")

        val path = server.takeRequest().url.encodedPath
        assertTrue("id must stay inside its segment, was $path", path.startsWith("/api/district/calls/"))
        assertTrue("id must be encoded, was $path", !path.contains("/../"))
        assertTrue(path.endsWith("/transcript"))
    }
}
