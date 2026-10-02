package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.HqConfirmAction
import com.distronode.districtai.core.model.HqConfirmRequest
import com.distronode.districtai.core.model.HqPromptRequest
import com.distronode.districtai.core.model.HqTurn
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What District HQ's two turns actually put on the wire.
 *
 * ⛔ WHY THIS EXISTS SEPARATELY FROM THE CONTRACT FIXTURES. Those pin what the server SENDS. The
 * request body is where this client can be wrong entirely on its own, and the HQ route reads its
 * fields off a bare `req.json()` with no schema: it branches on the mere PRESENCE of `confirm`, and
 * it drops any history turn whose role is not `user`/`model` without complaining. Both mistakes are
 * silent — one turns a write into a chat message, the other quietly forgets the conversation.
 *
 * ⛔ AND THE CONFIRM ARGUMENTS MUST SURVIVE THE ROUND TRIP BYTE FOR BYTE. The operator approved a
 * sentence composed from those arguments server-side, so an encoder that reordered, coerced or
 * dropped part of them would apply a different change from the one that was described — and the
 * description is the only thing the operator saw.
 *
 * ⚠️ Driven over real HTTP through MockWebServer rather than by calling the serializer directly. The
 * setting that decides the shape (`explicitNulls = false`) lives on a PRIVATE Json instance inside
 * HttpDistrictApi.kt, so encoding here with a locally-built Json would test a different
 * configuration than the one that ships.
 */
class HqRequestBodyTest {

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

    private fun api(): HqApi = HttpHqApi(testApiClient(server, refreshApi))

    private fun recordedBody(): JsonObject {
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val raw = recorded.body?.utf8().orEmpty()
        assertTrue("the request must carry a body", raw.isNotBlank())
        return Json.parseToJsonElement(raw) as JsonObject
    }

    private fun okAnswer() = MockResponse(
        code = 200,
        body = """{"success":true,"answer":"You had 19 calls this week."}""",
    )

    private fun okConfirm() = MockResponse(
        code = 200,
        body = """{"success":true,"executed":true,"tool":"update_persona",
                   "args":{"greeting":"Good afternoon."},"result":{"ok":true}}""",
    )

    @Test
    fun `a prompt turn carries its history in the SERVER's role vocabulary`() = runTest {
        server.enqueue(okAnswer())

        api().hqPrompt(
            HqPromptRequest(
                workspaceId = "ws-1",
                prompt = "How did we do this week?",
                history = listOf(
                    HqTurn(role = "user", text = "Hello"),
                    HqTurn(role = "model", text = "Hi — ask me about your calls."),
                ),
            ),
        )

        val body = recordedBody()
        assertEquals(setOf("workspaceId", "prompt", "history"), body.keys)
        val history = body["history"] as JsonArray
        assertEquals(2, history.size)
        // ⛔ `user`/`model`, NEVER `assistant`. The route feeds these straight into Gemini's Content
        // list and DROPS anything else, silently — the only symptom is answers that stop following
        // the thread.
        assertEquals("user", (history[0] as JsonObject)["role"]?.asString())
        assertEquals("model", (history[1] as JsonObject)["role"]?.asString())
    }

    @Test
    fun `a prompt turn NEVER carries a confirm key`() = runTest {
        // ⛔ THE ROUTE BRANCHES ON PRESENCE ALONE. A `confirm` key that leaked into a prompt body —
        // even holding null — would take the confirm path, which executes a write without the model
        // ever running. `explicitNulls = false` and a separate request type are what prevent it.
        server.enqueue(okAnswer())

        api().hqPrompt(HqPromptRequest(workspaceId = "ws-1", prompt = "Just asking"))

        val body = recordedBody()
        assertFalse("a question must not be able to execute anything", "confirm" in body.keys)

        // ⛔ AN EMPTY HISTORY IS OMITTED ENTIRELY, NOT SENT AS `[]`, AND THAT IS THE SAME MECHANISM
        // THAT BIT `SendMessageRequest.channel`: kotlinx.serialization does not encode a property
        // still holding its declared default unless `encodeDefaults` is on, which this client
        // deliberately leaves off. Pinned rather than assumed, because the last time this was
        // assumed the field silently never reached the wire and the SERVER's own default applied
        // instead.
        //
        // ⚠️ Harmless HERE, and that is a fact about this route rather than a general reprieve. The
        // handler gates the replay on `Array.isArray(body.history)`, so absent and `[]` take the
        // same branch and mean the same thing: no prior turns. `channel` was dangerous precisely
        // because the server had a DIFFERENT default to fall back on. Before giving any future
        // field on these requests a default, check which of those two cases it is.
        assertFalse("an empty history is omitted, not sent empty", "history" in body.keys)
    }

    @Test
    fun `a confirm turn sends the tool and its arguments VERBATIM, and no prompt`() = runTest {
        server.enqueue(okConfirm())

        // ⚠️ Deliberately awkward arguments: a nested object, an array, a null, a number and a
        // boolean. The model chooses these, so their shape is whatever the tool declared — a client
        // that re-typed them would flatten exactly this.
        val args = JsonObject(
            mapOf(
                "contact" to JsonPrimitive("Ada"),
                "rules" to JsonArray(
                    listOf(
                        JsonObject(mapOf("day" to JsonPrimitive("mon"), "open" to JsonPrimitive(9))),
                    ),
                ),
                "note" to JsonNull,
                "notify" to JsonPrimitive(true),
            ),
        )

        api().hqConfirm(
            HqConfirmRequest(
                workspaceId = "ws-1",
                confirm = HqConfirmAction(tool = "update_call_routing", args = args),
            ),
        )

        val body = recordedBody()
        assertEquals(setOf("workspaceId", "confirm"), body.keys)
        // ⛔ NO `prompt`. The confirm path must not re-run the model: the action is precisely the one
        // the operator approved, and re-planning it would be free to substitute another.
        assertFalse("a confirm must not re-plan anything", "prompt" in body.keys)

        val confirm = body["confirm"] as JsonObject
        assertEquals("update_call_routing", confirm["tool"]?.asString())
        // ⛔ BYTE-FOR-BYTE, INCLUDING THE EXPLICIT NULL INSIDE `args`. `explicitNulls = false`
        // governs the DTO's own nullable properties, NOT the contents of a JsonObject — a JsonNull
        // the model chose is part of the approved payload and survives. If it did not, the applied
        // action would differ from the described one on exactly the field nobody would check.
        assertEquals(args, confirm["args"])
    }

    @Test
    fun `both turns post to the one route the server actually exports`() = runTest {
        // ⚠️ ONE PATH, TWO BODIES. There is no /hq/confirm to point the second at; the route
        // distinguishes them by the body alone, and a helpfully-invented sub-path would 404.
        server.enqueue(okAnswer())
        api().hqPrompt(HqPromptRequest(workspaceId = "ws-1", prompt = "hi"))
        assertEquals("/api/district/hq", server.takeRequest().url.encodedPath)

        server.enqueue(okConfirm())
        api().hqConfirm(
            HqConfirmRequest(
                workspaceId = "ws-1",
                confirm = HqConfirmAction(tool = "update_persona"),
            ),
        )
        assertEquals("/api/district/hq", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a 429 surfaces as a rate limit rather than a generic failure`() = runTest {
        // ⚠️ 30/min per ACCOUNT, shared by both turns. The session is intact, so this must not read
        // as a sign-out — see ApiResult.RateLimited.
        server.enqueue(
            MockResponse(
                code = 429,
                body = """{"error":"You're sending requests too quickly — give it a second."}""",
            ),
        )

        val result = api().hqPrompt(HqPromptRequest(workspaceId = "ws-1", prompt = "fast"))

        assertEquals(
            "You're sending requests too quickly — give it a second.",
            (result as ApiResult.RateLimited).message,
        )
    }

    @Test
    fun `a non-confirmable tool answers 400 and is surfaced with the server's wording`() = runTest {
        // ⚠️ 400, not 403 — nothing about the caller is wrong, the action simply is not confirmable.
        server.enqueue(
            MockResponse(
                code = 400,
                body = """{"success":false,"error":"That action can't be confirmed."}""",
            ),
        )

        val result = api().hqConfirm(
            HqConfirmRequest(
                workspaceId = "ws-1",
                confirm = HqConfirmAction(tool = "search_contacts"),
            ),
        )

        val failure = result as ApiResult.HttpFailure
        assertEquals(400, failure.status)
        assertEquals("That action can't be confirmed.", failure.message)
    }

    private fun kotlinx.serialization.json.JsonElement.asString(): String =
        (this as JsonPrimitive).content
}
