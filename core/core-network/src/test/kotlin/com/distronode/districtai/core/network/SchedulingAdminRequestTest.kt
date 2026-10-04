package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.SchedulingMe
import com.distronode.districtai.core.model.SchedulingNoContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The VERB, the PATH, the BODY and the ENVELOPE of the scheduling-admin RPC.
 *
 * ⛔ A FAILED OP IS AN HTTP **200**, WHICH IS THE ONE THING NO OTHER ROUTE IN THIS CLIENT DOES, and
 * every assertion below that reads a refusal is checking that the 200 is NOT reported as a
 * success. A type whose fields all default decodes `{ok:false}` cleanly, so the envelope has to be
 * read before the payload — and this file is what proves it is.
 *
 * ⛔ AND THE BODY KEYS ARE ASSERTED AS A SET. `params` must be present even when empty: the route
 * defaults a missing one to `{}` itself, so the two agree today, and sending the empty object
 * makes the agreement a contract rather than a coincidence.
 */
class SchedulingAdminRequestTest {

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

    private fun adminApi(): SchedulingAdminApi = HttpSchedulingAdminApi(testApiClient(server, refreshApi))

    private fun recordedBody(): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()) as JsonObject

    @Test
    fun `an op is a POST to scheduling admin carrying the workspace, the op name and its params`() =
        runTest {
            server.enqueue(
                MockResponse(code = 200, body = """{"ok":true,"data":{"ok":true}}"""),
            )

            val result = adminApi().performSchedulingOp(
                workspaceId = "ws-1",
                op = SchedulingAdminOp.EVENT_TYPES_DELETE,
                params = buildJsonObject { put("slug", "phone-consultation") },
                serializer = SchedulingNoContent.serializer(),
            )

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/district/scheduling/admin", recorded.url.encodedPath)

            // ⚠️ NOTHING IN THE QUERY STRING. The workspace travels in the body on this route,
            // unlike the upload beside it, and a query parameter is the half of a request that
            // reaches access logs.
            assertEquals("", recorded.url.encodedQuery.orEmpty())

            val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
            assertEquals(setOf("workspaceId", "op", "params"), body.keys)
            assertEquals("ws-1", (body["workspaceId"] as JsonPrimitive).content)

            // ⛔ THE WIRE NAME, DOTS AND ALL. A key renamed on the server is a 400 rather than a
            // compile error, which is why `SchedulingAdminOpTest` re-types all 64.
            assertEquals("eventTypes.delete", (body["op"] as JsonPrimitive).content)

            // ⛔ THE PATH KEY STAYS IN THE BODY. `slug` is BOTH the address and a required member
            // of the op's params schema; the route strips it AFTER validating, so a client that
            // removed it first gets a 400 naming the field it was being tidy about.
            assertEquals(
                buildJsonObject { put("slug", "phone-consultation") },
                body["params"],
            )

            val envelope = (result as ApiResult.Success).value
            assertTrue(envelope is SchedulingAdminEnvelope.Data)
        }

    @Test
    fun `an op that takes nothing sends an EMPTY params object rather than omitting the key`() =
        runTest {
            server.enqueue(MockResponse(code = 200, body = """{"ok":true,"data":{"ok":true}}"""))

            adminApi().performSchedulingOp(
                workspaceId = "ws-1",
                op = SchedulingAdminOp.SETTINGS_BRANDING_LOGO_DELETE,
                params = JsonObject(emptyMap()),
                serializer = SchedulingNoContent.serializer(),
            )

            val body = recordedBody()
            assertEquals(setOf("workspaceId", "op", "params"), body.keys)
            assertEquals(JsonObject(emptyMap()), body["params"])
        }

    @Test
    fun `a successful op hands back the decoded payload and not the envelope`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """
                    {"ok":true,"data":{"id":"u","email":"a@b.test","name":"A",
                     "timezone":"America/Toronto","time_format":"24h","week_start":1,
                     "date_format":"ymd","is_admin":false,"is_owner":false,"role":"member",
                     "notify_confirmation":true,"notify_cancellation":true,
                     "notify_reschedule":true,"notify_reminder":true,"notify_host_booking":true,
                     "notify_host_cancel":true,"notify_host_reschedule":true}}
                """.trimIndent(),
            ),
        )

        val result = adminApi().performSchedulingOp(
            workspaceId = "ws-1",
            op = SchedulingAdminOp.ME_GET,
            params = JsonObject(emptyMap()),
            serializer = SchedulingMe.serializer(),
        )

        val data = (result as ApiResult.Success).value as SchedulingAdminEnvelope.Data
        assertEquals("America/Toronto", data.value.timezone)
    }

    @Test
    fun `a scheduler refusal arrives at HTTP 200 and is NOT reported as a success payload`() =
        runTest {
            // ⛔ THE CENTRAL CASE. `failureResponse` answers this at 200 on purpose: the request
            // reached us, cleared auth, cleared the role bar, validated, and the SCHEDULER refused.
            // Decoding it straight into the caller's type would either fail (blaming the contract
            // for an outage) or, worse, succeed against a lenient type and report an outage as an
            // empty answer.
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """{"ok":false,"failure":"slot_taken","status":409}""",
                ),
            )

            val result = adminApi().performSchedulingOp(
                workspaceId = "ws-1",
                op = SchedulingAdminOp.BOOKINGS_RESCHEDULE,
                params = buildJsonObject { put("id", "bk-1") },
                serializer = SchedulingNoContent.serializer(),
            )

            val envelope = (result as ApiResult.Success).value
            val refusal = envelope as SchedulingAdminEnvelope.Refusal
            assertEquals("slot_taken", refusal.failure)

            // ⚠️ THE SCHEDULER'S OWN STATUS, carried for a log line while the HTTP response was
            // 200. Nothing may branch on it.
            assertEquals(409, refusal.status)
        }

    @Test
    fun `a 2xx that is neither envelope shape is contract drift rather than a refusal`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"unexpected":true}"""))

        val result = adminApi().performSchedulingOp(
            workspaceId = "ws-1",
            op = SchedulingAdminOp.ME_GET,
            params = JsonObject(emptyMap()),
            serializer = SchedulingMe.serializer(),
        )

        val failure = result as ApiResult.DecodeFailure
        // ⛔ NO BODY PREVIEW. These bodies are customer bookings, transcripts and meeting notes;
        // the op name is the whole diagnostic budget.
        assertEquals("SchedulingAdmin{op=me.get,noEnvelope}", failure.bodyPreview)
    }

    @Test
    fun `an ok envelope whose payload is the wrong shape is a decode failure naming only the op`() =
        runTest {
            server.enqueue(MockResponse(code = 200, body = """{"ok":true,"data":{"wrong":1}}"""))

            val result = adminApi().performSchedulingOp(
                workspaceId = "ws-1",
                op = SchedulingAdminOp.ME_GET,
                params = JsonObject(emptyMap()),
                serializer = SchedulingMe.serializer(),
            )

            val failure = result as ApiResult.DecodeFailure
            assertTrue(failure.bodyPreview.startsWith("SchedulingAdmin{op=me.get,payload="))
            assertTrue(
                "the preview may not quote the body",
                !failure.bodyPreview.contains("wrong"),
            )
        }

    @Test
    fun `a 409 scheduling_not_ready arrives as an HTTP failure carrying the code`() = runTest {
        // ⛔ THE WORKSPACE HAS NO TENANCY. Not a fault and not retryable — somebody has to press
        // Enable, which is a different route and a different screen. The `error` string is what
        // distinguishes it from a future 409 that is not about provisioning.
        server.enqueue(
            MockResponse(code = 409, body = """{"error":"scheduling_not_ready"}"""),
        )

        val result = adminApi().performSchedulingOp(
            workspaceId = "ws-1",
            op = SchedulingAdminOp.ME_GET,
            params = JsonObject(emptyMap()),
            serializer = SchedulingMe.serializer(),
        )

        val failure = result as ApiResult.HttpFailure
        assertEquals(409, failure.status)
        assertEquals("scheduling_not_ready", failure.message)
    }

    @Test
    fun `a 403 arrives as Forbidden and a 401 as Unauthorized`() = runTest {
        server.enqueue(MockResponse(code = 403, body = """{"error":"forbidden"}"""))
        val forbidden = adminApi().performSchedulingOp(
            workspaceId = "ws-1",
            op = SchedulingAdminOp.SETTINGS_BRANDING_PATCH,
            params = JsonObject(emptyMap()),
            serializer = SchedulingNoContent.serializer(),
        )
        assertEquals("forbidden", (forbidden as ApiResult.Forbidden).message)

        // ⚠️ A 401 IS RETRIED ONCE BY THE CLIENT, so both responses have to be enqueued or the
        // second call would read the next test's stub. The retry is the shared client's and is
        // asserted here only so the arm is exercised on THIS route.
        server.enqueue(MockResponse(code = 401, body = """{"error":"Unauthorized"}"""))
        server.enqueue(MockResponse(code = 401, body = """{"error":"Unauthorized"}"""))
        val unauthorized = adminApi().performSchedulingOp(
            workspaceId = "ws-1",
            op = SchedulingAdminOp.ME_GET,
            params = JsonObject(emptyMap()),
            serializer = SchedulingNoContent.serializer(),
        )
        assertTrue(unauthorized is ApiResult.Unauthorized)
    }

    @Test
    fun `a 400 unknown_op carries the code the repository reports on`() = runTest {
        // ⛔ A PROGRAMMER ERROR AND NOTHING A USER CAN ACT ON: this enum and `ADMIN_OPS` have
        // diverged. It is invisible to the compiler because the op crosses the wire as a string.
        server.enqueue(MockResponse(code = 400, body = """{"error":"unknown_op"}"""))

        val result = adminApi().performSchedulingOp(
            workspaceId = "ws-1",
            op = SchedulingAdminOp.ME_GET,
            params = JsonObject(emptyMap()),
            serializer = SchedulingNoContent.serializer(),
        )

        val failure = result as ApiResult.HttpFailure
        assertEquals(400, failure.status)
        assertEquals("unknown_op", failure.message)
    }

    @Test
    fun `the shipped decoder tolerates a field added server-side`() = runTest {
        // ⛔ STRICT IN THE GATE, LENIENT IN THE FIELD, AND THIS IS THE FIELD HALF. The contract
        // tests read the committed fixtures with `ignoreUnknownKeys = false`; the shipped parser
        // must degrade to "the app ignores a key it does not know" rather than "every scheduling
        // screen fails to parse" on an already-installed build.
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"ok":true,"data":{"ok":true},"brandNewServerKey":"boom"}""",
            ),
        )

        val result = adminApi().performSchedulingOp(
            workspaceId = "ws-1",
            op = SchedulingAdminOp.WEBHOOKS_DELETE,
            params = buildJsonObject { put("id", "wh-1") },
            serializer = SchedulingNoContent.serializer(),
        )

        assertTrue((result as ApiResult.Success).value is SchedulingAdminEnvelope.Data)
    }

    @Test
    fun `a strict decoder injected in place of the default rejects that same field`() = runTest {
        // ⚠️ THE INJECTION POINT EXISTS SO THIS PAIR CAN BE WRITTEN AT ALL. Production passes
        // nothing; the default is the lenient client decoder.
        val strict = HttpSchedulingAdminApi(
            testApiClient(server, refreshApi),
            json = Json { ignoreUnknownKeys = false },
        )
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"ok":true,"data":{"ok":true},"brandNewServerKey":"boom"}""",
            ),
        )

        val result = strict.performSchedulingOp(
            workspaceId = "ws-1",
            op = SchedulingAdminOp.WEBHOOKS_DELETE,
            params = buildJsonObject { put("id", "wh-1") },
            serializer = String.serializer(),
        )

        assertTrue(result is ApiResult.DecodeFailure)
    }
}
