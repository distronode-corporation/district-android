package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.SupportRequestDraft
import com.distronode.districtai.core.model.SupportRequestKind
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
 * What the support client puts on the wire.
 *
 * ⛔ THE TWO ASSERTIONS THIS FILE EXISTS FOR, AND THEY ARE BOTH ABOUT THE DESK ONE FAMILY OVER:
 * 1. **The reply field is `body`, where the desk's is `message`.** Two surfaces one word apart, and
 *    transposing them is a silent 400 on a screen whose whole job is to deliver a sentence to a
 *    human. `DeskRequestTest` asserts the mirror image.
 * 2. **The create payload is EXACTLY four keys and nothing may be added.** This is backed by a real
 *    Atlassian service desk where `requestFieldValues` may carry only the fields the REQUEST TYPE
 *    exposes on its portal form, and an unknown field is a hard 400 rather than an ignored key —
 *    the failure that once cost every ticket the platform tried to file.
 *
 * ⛔ AND A THIRD, WHICH IS A SECURITY PROPERTY RATHER THAN A CORRECTNESS ONE: no request on this
 * surface carries an identifying field. The workspace is the only scope on the wire; the requester
 * is derived from the session and hashed server-side.
 */
class SupportRequestTest {

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

    private fun api(): SupportApi = HttpSupportApi(
        DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient(),
            tokens = signedInCoordinator(refreshApi),
        ),
    )

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    private val draft = SupportRequestDraft(
        kind = SupportRequestKind.PROBLEM,
        subject = "Calls drop after 30 seconds",
        message = "Every inbound call ends abruptly.",
    )

    @Test
    fun `the list is a GET with the workspace in the query`() = runTest {
        server.enqueue(ok("""{"success":true,"requests":[]}"""))
        api().supportRequests("ws-1")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/support/requests", recorded.url.encodedPath)
        assertEquals(setOf("workspaceId"), recorded.url.queryParameterNames)
    }

    @Test
    fun `the detail, reply and close paths are built from the key`() = runTest {
        server.enqueue(
            ok(
                """{"success":true,"request":{"issueKey":"DA-42","id":"r","subject":"s",
                   "statusName":"Open","statusCategory":"NEW","createdAt":"a","updatedAt":"b",
                   "filed":true,"source":"workspace","region":"ca","closeable":true,
                   "messages":[]}}""",
            ),
        )
        api().supportRequest("ws-1", "DA-42")
        assertEquals(
            "/api/district/support/requests/DA-42",
            server.takeRequest().url.encodedPath,
        )

        server.enqueue(
            ok(
                """{"success":true,"message":{"id":"c","role":"customer","author":"You",
                   "body":"ok","createdAt":"a"}}""",
            ),
        )
        api().replyToSupportRequest("ws-1", "DA-42", "Thanks.")
        assertEquals(
            "/api/district/support/requests/DA-42/reply",
            server.takeRequest().url.encodedPath,
        )

        server.enqueue(ok("""{"success":true,"statusName":"Done"}"""))
        api().closeSupportRequest("ws-1", "DA-42")
        assertEquals(
            "/api/district/support/requests/DA-42/close",
            server.takeRequest().url.encodedPath,
        )
    }

    @Test
    fun `an unfiled request is addressed by our own row id, which the route also resolves`() =
        runTest {
            // ⛔ BOTH FORMS MUST KEEP WORKING. A request we hold but have not filed has NO issue
            // key, so a client that could only address `DA-nn` would make exactly the requests a
            // customer is most anxious about unreachable.
            server.enqueue(
                ok(
                    """{"success":true,"request":{"issueKey":null,"id":"row_2","subject":"s",
                       "statusName":"Received","statusCategory":"NEW","createdAt":"a",
                       "updatedAt":"b","filed":false,"source":"workspace","region":"ca",
                       "closeable":false,"messages":[]}}""",
                ),
            )
            api().supportRequest("ws-1", "row_2")

            assertEquals(
                "/api/district/support/requests/row_2",
                server.takeRequest().url.encodedPath,
            )
        }

    @Test
    fun `a reply sends body, NOT message`() = runTest {
        server.enqueue(
            ok(
                """{"success":true,"message":{"id":"c","role":"customer","author":"You",
                   "body":"ok","createdAt":"a"}}""",
            ),
        )
        api().replyToSupportRequest("ws-1", "DA-42", "Thanks, that fixed it.")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val body = Json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject
        // ⛔ THE ASSERTION THIS FILE EXISTS FOR. The desk's reply one family over spells the same
        // idea `message`, and `DeskRequestTest` asserts that one. Harmonising the two is a 400.
        assertEquals(setOf("body"), body.keys)
        assertEquals(JsonPrimitive("Thanks, that fixed it."), body["body"])
        assertFalse("support's reply field is never `message`", "message" in body.keys)
    }

    @Test
    fun `close sends NO body at all and carries the workspace in the query`() = runTest {
        server.enqueue(ok("""{"success":true,"statusName":"Done"}"""))
        api().closeSupportRequest("ws-1", "DA-42")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
        // ⛔ THE HANDLER NEVER CALLS `req.json()`. Sending a body would be this client inventing a
        // contract, and the field it invented would be ignored rather than rejected — which is the
        // shape that survives review and then decays.
        assertTrue("close must send no body", recorded.body?.utf8().isNullOrEmpty())
    }

    @Test
    fun `a create sends exactly kind, subject, message and the key`() = runTest {
        server.enqueue(ok("""{"success":true,"issueKey":"DA-43"}"""))
        api().createSupportRequest("ws-1", draft, idempotencyKey = "key-1")

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        // ⛔ FOUR KEYS. An unknown field is a hard 400 at Atlassian AFTER the local claim row
        // already exists, so an extra key here does not degrade — it strands a request.
        assertEquals(setOf("kind", "subject", "message", "idempotencyKey"), body.keys)
        assertEquals(JsonPrimitive("problem"), body["kind"])
    }

    @Test
    fun `a create carries no identifying field, which is the boundary against the voice rule`() =
        runTest {
            server.enqueue(ok("""{"success":true,"issueKey":"DA-43"}"""))
            api().createSupportRequest("ws-1", draft, idempotencyKey = "key-1")

            val recorded = server.takeRequest()
            val raw = recorded.body!!.utf8()
            // ⛔ THE REQUESTER IS DERIVED FROM THE SESSION AND HASHED SERVER-SIDE. Nothing here may
            // name a person: a parameter for one is a lookup key a future screen could populate
            // from anything, which is precisely why the VOICE path takes no identity argument.
            listOf("requester", "email", "phone", "hash", "userId").forEach { word ->
                assertFalse(
                    "a support create must carry no identifying field, found `$word`",
                    raw.lowercase().contains(word.lowercase()),
                )
            }
            // ⚠️ And the workspace is the ONLY scope, in the query where the guard reads it.
            assertEquals(setOf("workspaceId"), recorded.url.queryParameterNames)
        }

    @Test
    fun `a create with no idempotency key omits it rather than sending null`() = runTest {
        server.enqueue(ok("""{"success":true,"pending":true}"""))
        api().createSupportRequest("ws-1", draft, idempotencyKey = null)

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        // ⚠️ `z.string().uuid().optional()` rejects an explicit null. Omitting is the only legal way
        // for an older client to forgo the protection.
        assertEquals(setOf("kind", "subject", "message"), body.keys)
    }

    @Test
    fun `every kind reaches the wire as its own short string`() = runTest {
        SupportRequestKind.entries.forEach { kind ->
            server.enqueue(ok("""{"success":true,"issueKey":"DA-1"}"""))
            api().createSupportRequest("ws-1", draft.copy(kind = kind), "key-$kind")

            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
            assertEquals(JsonPrimitive(kind.wire), body["kind"])
        }
    }

    // ── The refusals that are answers rather than faults ─────────────────────

    @Test
    fun `a 409 on close keeps its status and its sentence`() = runTest {
        // ⚠️ `not-closeable` IS AN ANSWER. The desk's workflow either offers no resolving transition
        // or offers several, and the sentence names the way forward (reply and we will close it).
        server.enqueue(
            MockResponse(
                code = 409,
                body = """{"success":false,"error":"This request cannot be closed from here."}""",
            ),
        )
        val result = api().closeSupportRequest("ws-1", "DA-42")

        assertTrue(result is ApiResult.HttpFailure)
        assertEquals(409, (result as ApiResult.HttpFailure).status)
        assertEquals("This request cannot be closed from here.", result.message)
    }

    @Test
    fun `a 409 on reply means the request is still being opened`() = runTest {
        // ⚠️ A STATE, NOT A FAULT: we hold it, it has no Atlassian thread yet, and accepting the
        // reply would silently drop the one message the customer wanted us to see.
        server.enqueue(
            MockResponse(
                code = 409,
                body = """{"success":false,"error":"This request is still being opened."}""",
            ),
        )
        val result = api().replyToSupportRequest("ws-1", "row_2", "Any news?")

        assertEquals(409, (result as ApiResult.HttpFailure).status)
        assertEquals("This request is still being opened.", result.message)
    }

    @Test
    fun `a 503 carries the unconfigured-desk sentence, which names a PATH not a host`() = runTest {
        // ⚠️ The sentence points at `/support/report` as a PATH because Canada's canonical host is
        // distronode.ca. Replacing it with a generic message would drop the only useful thing in it.
        server.enqueue(
            MockResponse(
                code = 503,
                // ⚠️ Concatenated rather than wrapped in a raw string: a newline INSIDE a JSON
                // string value is invalid JSON, and the decoder would fail for the wrong reason.
                body = """{"success":false,"error":"Support requests are temporarily """ +
                    """unavailable here. The support form at /support/report still """ +
                    """records your request."}""",
            ),
        )
        val result = api().createSupportRequest("ws-1", draft, "key-1")

        assertEquals(503, (result as ApiResult.HttpFailure).status)
        assertTrue(result.message.contains("/support/report"))
    }

    @Test
    fun `a 404 stays a NotFound and the client does not try to tell the three cases apart`() =
        runTest {
            // ⛔ "no such request", "not this workspace's" and "erased" answer IDENTICALLY so that a
            // sequential key like DA-41 cannot be probed by anyone with a session and a loop.
            server.enqueue(MockResponse(code = 404, body = """{"error":"Not found"}"""))
            val result = api().supportRequest("ws-1", "DA-41")

            assertTrue(result is ApiResult.NotFound)
            assertEquals("Not found", (result as ApiResult.NotFound).message)
        }

    @Test
    fun `a 429 carries the server's own sentence, which names the remedy`() = runTest {
        server.enqueue(
            MockResponse(
                code = 429,
                body = """{"success":false,"error":"You have reached the daily limit for """ +
                    """new requests. Please reply on an existing one."}""",
            ),
        )
        val result = api().createSupportRequest("ws-1", draft, "key-1")

        assertTrue(result is ApiResult.RateLimited)
        assertTrue((result as ApiResult.RateLimited).message.contains("reply on an existing one"))
    }
}
