package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.AiDraftRequest
import com.distronode.districtai.core.model.DraftSaveRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the composer's five requests actually put on the wire.
 *
 * ⛔ WHY THIS EXISTS SEPARATELY FROM THE CONTRACT FIXTURES, same as [InboxRequestBodyTest]: those
 * pin what the SERVER sends. Nothing pins what this client sends, and every one of these routes
 * reads its fields off a bare `req.json()` or `req.formData()` with no schema — so a renamed part,
 * a query parameter that should have been a form field, or a verb that should have been PUT is not
 * rejected. It is silently absent, and the route answers 400 or does the wrong thing quietly.
 *
 * ⛔ AND THE MULTIPART UPLOAD IS THE ONLY NON-JSON REQUEST IN THE WHOLE CLIENT. Everything about it
 * is unlike the rest — the part names, the filename, the content type and the fact that its body
 * has to survive being written TWICE. Driven over real HTTP through MockWebServer, because the
 * multipart encoder is OkHttp's and encoding one by hand here would test a different writer than
 * the one that ships.
 */
class ComposerRequestTest {

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

    private fun client() = DistrictApiClient(
        baseUrl = server.url("/"),
        httpClient = OkHttpClient(),
        tokens = signedInCoordinator(refreshApi),
    )

    private fun inbox(): InboxApi = HttpInboxApi(client())

    private fun drafts(): MessageDraftsApi = HttpMessageDraftsApi(client())

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    private fun okUpload() = MockResponse(
        code = 200,
        body = """{"success":true,"media":{"id":"m1","mimeType":"image/png","sizeBytes":8,
                   "url":"https://www.distronode.test/api/media/m1"}}""",
    )

    // ── The multipart upload ─────────────────────────────────────────────────

    @Test
    fun `an upload posts multipart with a workspace field and a named file part`() = runTest {
        server.enqueue(okUpload())

        val result = inbox().uploadMedia(
            workspaceId = "ws-1",
            fileName = "roof.png",
            mimeType = "image/png",
            bytes = pngBytes,
        )

        assertTrue(result is ApiResult.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/messages/media", recorded.url.encodedPath)

        val contentType = recorded.headers["Content-Type"].orEmpty()
        assertTrue("the body must be multipart/form-data", contentType.startsWith("multipart/form-data"))
        assertTrue("OkHttp must have set a boundary", "boundary=" in contentType)

        val body = recorded.body?.utf8().orEmpty()
        // ⛔ `workspaceId` IS A FORM FIELD, NOT A QUERY PARAMETER. The route reads it off
        // `req.formData()`, so sending it in the URL would leave it undefined and trip the guard's
        // 400 while the URL looked perfectly correct.
        assertTrue("workspaceId must travel as a form part", """name="workspaceId"""" in body)
        assertTrue(body.contains("ws-1"))
        assertFalse("and not as a query parameter", "workspaceId" in recorded.url.query.orEmpty())

        // ⛔ THE PART MUST BE NAMED `file`. The route does `form.get("file")` and nothing else; a
        // part named `image` or `upload` is not an error, it simply is not found — and the 400 that
        // follows reads like a client that sent no body at all.
        assertTrue("the file part must be named file", """name="file"""" in body)
        // ⛔ AND IT MUST CARRY A FILENAME, or the part is a plain field rather than a file and the
        // route's `file instanceof File` check fails.
        assertTrue("the file part must carry a filename", """filename="roof.png"""" in body)
        assertTrue("the file part must declare its type", "Content-Type: image/png" in body)
    }

    /**
     * ⛔ THE RETRY MUST RE-SEND THE BYTES INTACT, AND THIS IS THE ONLY TEST THAT CAN PROVE IT. The
     * body is written a second time after a 401 refresh; a one-shot body (a `ContentResolver`
     * InputStream, say) would write ZERO bytes on the second pass — and the server accepts that as
     * a 400 "between 1 byte and 5MB" rather than reporting it as the transport bug it is. Holding
     * the bytes in a `ByteArray` is what makes the body repeatable, and the assertion below is what
     * keeps that property from being optimised away into a stream.
     */
    @Test
    fun `a 401 refresh re-sends the whole multipart body, not an empty one`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"Session expired."}"""))
        server.enqueue(okUpload())

        val result = inbox().uploadMedia("ws-1", "roof.png", "image/png", pngBytes)
        assertTrue(result is ApiResult.Success)

        val first = server.takeRequest()
        val retry = server.takeRequest()

        assertEquals("Bearer access-1", first.headers["Authorization"])
        // A DIFFERENT token on the retry — the proof that invalidateAccessToken took effect.
        assertEquals("Bearer access-2", retry.headers["Authorization"])

        val firstBody = first.body?.utf8().orEmpty()
        val retryBody = retry.body?.utf8().orEmpty()
        assertTrue("the first attempt must have carried the file", """filename="roof.png"""" in firstBody)
        assertTrue("and so must the retry", """filename="roof.png"""" in retryBody)
        // ⚠️ Compared on LENGTH rather than equality: OkHttp mints a fresh boundary per request, so
        // the two bodies are legitimately different strings carrying identical content.
        assertEquals(
            "the retry must re-send the same number of bytes",
            firstBody.length,
            retryBody.length,
        )
    }

    // ── Draft persistence ────────────────────────────────────────────────────

    @Test
    fun `reading one thread's draft sends both the workspace and the thread key`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"draft":null}"""))

        drafts().draft("ws-1", "contact:c1")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/messages/drafts", recorded.url.encodedPath)
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
        // ⛔ WITHOUT THIS, THE SAME PATH IS THE LIST ENDPOINT. It answers `{success, drafts: [...]}`
        // — a different key and a different type — and does NOT 400. A dropped thread key therefore
        // decodes as a draft-less response and the composer silently restores nothing.
        assertEquals("contact:c1", recorded.url.queryParameter("threadKey"))
    }

    @Test
    fun `listing drafts omits the thread key entirely`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"drafts":[]}"""))

        drafts().drafts("ws-1")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
        // ⚠️ ABSENT, not empty. The route branches on `threadKey !== null`, so an empty string
        // would take the single-draft path and fail its `contact:`/`addr:` prefix check with a 400.
        assertFalse("threadKey must not appear at all", "threadKey" in recorded.url.query.orEmpty())
    }

    @Test
    fun `saving a draft is a PUT with a JSON body`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"draft":{"threadKey":"contact:c1","body":"hi",
                           "subject":null,"mediaUrls":[],"updatedAt":"2026-08-18T12:00:00.000Z"}}""",
            ),
        )

        drafts().saveDraft(
            DraftSaveRequest(
                workspaceId = "ws-1",
                threadKey = "contact:c1",
                body = "Thanks — Thursday works.",
                mediaUrls = listOf("https://www.distronode.test/api/media/m1"),
            ),
        )

        val recorded = server.takeRequest()
        // ⛔ PUT. The route exports GET/PUT/DELETE only, so a POST is a 405 — and PUT is the honest
        // verb anyway, because autosave has no create-versus-update distinction to express.
        assertEquals("PUT", recorded.method)
        assertEquals("/api/district/messages/drafts", recorded.url.encodedPath)

        val body = Json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject
        assertEquals(setOf("workspaceId", "threadKey", "body", "mediaUrls"), body.keys)
        // ⚠️ OMITTED, not null: the shared body encoder sets `explicitNulls = false`.
        assertFalse("an absent subject must not be sent as null", "subject" in body.keys)
    }

    @Test
    fun `a draft with no attachments omits mediaUrls, which the route reads as empty`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"draft":{"threadKey":"contact:c1","body":"hi",
                           "subject":null,"mediaUrls":[],"updatedAt":"2026-08-18T12:00:00.000Z"}}""",
            ),
        )

        drafts().saveDraft(
            DraftSaveRequest(workspaceId = "ws-1", threadKey = "contact:c1", body = "hi"),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        // ⚠️ kotlinx OMITS A DEFAULT-VALUED PROPERTY, AND THAT IS CORRECT HERE. `sanitizeMediaUrls`
        // maps an absent value to `[]`, so omission and an empty array mean the same thing. It was
        // NOT correct for `SendMessageRequest.channel`, whose server default could drift away from
        // the client's — which is why that field carries no default at all.
        assertEquals(setOf("workspaceId", "threadKey", "body"), body.keys)
    }

    @Test
    fun `deleting a draft is a DELETE with query parameters and no body`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true}"""))

        drafts().deleteDraft("ws-1", "contact:c1")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
        assertEquals("contact:c1", recorded.url.queryParameter("threadKey"))
        // ⚠️ The route reads `url.searchParams`; a JSON body here would be silently ignored, so
        // sending one would look correct and delete nothing.
        assertTrue("a DELETE must carry no body", recorded.body?.size ?: 0L == 0L)
    }

    // ── AI generation ────────────────────────────────────────────────────────

    /**
     * ⛔ THE SINGULAR PATH IS THE BILLED ONE, AND ONE LETTER SEPARATES IT FROM AUTOSAVE'S. This
     * assertion is the whole reason the two paths are separate constants: an autosave pointed at
     * `messages/draft` would run a Vertex generation on every debounce, and nothing about the name
     * would suggest it had.
     */
    @Test
    fun `generating a draft posts to the singular path, not the drafts one`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"draft":"Sure — 2pm works."}"""))

        drafts().generateDraft(AiDraftRequest(workspaceId = "ws-1", contactId = "c1"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/messages/draft", recorded.url.encodedPath)

        val body = Json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject
        assertEquals(setOf("workspaceId", "contactId"), body.keys)
        // ⚠️ An address-only thread has no contactId, and the route requires ONE of the two. A null
        // sent explicitly would satisfy `!phoneNumber && !contactId` in exactly the same way as an
        // omission, but the omission is what `explicitNulls = false` guarantees — asserted so a
        // change to that setting shows up here rather than as a 400 in the field.
        assertFalse("phoneNumber must be omitted when the thread is contact-keyed", "phoneNumber" in body.keys)
    }

    @Test
    fun `an address-keyed thread generates against phoneNumber, the server's historical name`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"draft":"Hello."}"""))

        drafts().generateDraft(
            AiDraftRequest(workspaceId = "ws-1", phoneNumber = "ada@contract.test"),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        assertEquals(setOf("workspaceId", "phoneNumber"), body.keys)
        // ⚠️ AN EMAIL IN A FIELD CALLED `phoneNumber`. That is the server's own name for the address
        // selector and it now carries either; renaming it here would 400.
        assertEquals("ada@contract.test", (body["phoneNumber"] as JsonPrimitive).content)
    }
}
