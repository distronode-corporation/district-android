package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.DirectoryEntry
import com.distronode.districtai.core.model.KnowledgeCreateRequest
import com.distronode.districtai.core.model.KnowledgeModePatchRequest
import com.distronode.districtai.core.model.MessagingAccountRequest
import com.distronode.districtai.core.model.MessagingChannelDefaultRequest
import com.distronode.districtai.core.model.MessagingDefaultRequest
import com.distronode.districtai.core.model.MessagingDeleteRequest
import com.distronode.districtai.core.model.MessagingMetaRequest
import com.distronode.districtai.core.model.MessagingProviderConfig
import com.distronode.districtai.core.model.MessagingTestRequest
import com.distronode.districtai.core.model.RoutingRule
import com.distronode.districtai.core.model.RoutingRuleField
import com.distronode.districtai.core.model.directoryPatch
import com.distronode.districtai.core.model.routingRulesRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The VERBS, the PATHS and the PARAMETER SPELLINGS of the A4b writes.
 *
 * ⛔ WHY THIS EXISTS SEPARATELY FROM THE CONTRACT FIXTURES. Those pin what the server SENDS. Every
 * failure this file catches is one this client can commit entirely on its own, and every one of
 * them is quiet:
 *
 *   - `workspace/directory` exports PATCH only and `workspace/routing-rules` exports POST only.
 *     Getting either wrong is a 405 that reads as an outage rather than as a client bug.
 *   - the knowledge DELETE reads `searchParams.get("documentId")`. Spelling it `id` or `docId`
 *     answers 400 "Missing documentId" — a client that can never delete anything, whose error names
 *     a field rather than a cause.
 *   - `callDirectory` and `routingRules` must reach the wire as ARRAYS and must never be omitted.
 *     The directory route treats an absent array exactly like an empty one and WIPES the stored
 *     directory with a 200.
 *
 * ⚠️ Driven over real HTTP through MockWebServer rather than by encoding directly, for the reason
 * `HqRequestBodyTest` states: the `explicitNulls = false` Json that decides these shapes is private
 * to HttpDistrictApi.kt, so a locally-built encoder would be testing a different configuration from
 * the one that ships.
 */
class WorkspaceEditRequestTest {

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

    private fun configApi(): ConfigApi = HttpConfigApi(client())

    private fun knowledgeApi(): KnowledgeApi = HttpKnowledgeApi(client())

    private fun messagingApi(): MessagingApi = HttpMessagingApi(client())

    private fun ok(body: String = """{"success":true}""") = MockResponse(code = 200, body = body)

    // ── The two destructive arrays ───────────────────────────────────────────

    @Test
    fun `saveDirectory is a PATCH to workspace directory carrying the whole array`() = runTest {
        server.enqueue(ok())

        configApi().saveDirectory(
            directoryPatch(
                "ws-1",
                listOf(
                    DirectoryEntry.newEntry("Ops desk", "+14165550177"),
                    DirectoryEntry.newEntry("Night desk", "+14165550100"),
                ),
            ),
        )

        val recorded = server.takeRequest()
        // ⛔ PATCH. The route exports PATCH only; a POST would 405 and read as an outage.
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/district/workspace/directory", recorded.url.encodedPath)

        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals(setOf("workspaceId", "callDirectory"), body.keys)
        val entries = body["callDirectory"] as JsonArray
        assertEquals(2, entries.size)
        assertEquals("Ops desk", ((entries[0] as JsonObject)["name"] as JsonPrimitive).content)
    }

    @Test
    fun `an EMPTY directory reaches the wire as an empty array rather than an omitted key`() =
        runTest {
            // ⛔ THE DIFFERENCE BETWEEN THESE TWO IS NOTHING TO THE SERVER — `callDirectory || []`
            // wipes either way — but it is everything to a reader of this client. The array is
            // non-nullable on the request type precisely so `explicitNulls = false` cannot drop it,
            // and that is asserted on the wire rather than on the type.
            server.enqueue(ok())

            configApi().saveDirectory(directoryPatch("ws-1", emptyList()))

            val body = Json.parseToJsonElement(
                server.takeRequest().body?.utf8().orEmpty(),
            ) as JsonObject
            assertTrue("callDirectory must be present", body.containsKey("callDirectory"))
            assertEquals(0, (body["callDirectory"] as JsonArray).size)
        }

    @Test
    fun `saveRoutingRules is a POST, not a PATCH, and carries unmodelled keys through`() = runTest {
        // ⛔ POST IS THE ONE VERB ASYMMETRY ON THIS SURFACE. Every other workspace-settings write is
        // a PATCH; this route exports POST only.
        server.enqueue(ok())

        val foreign = RoutingRule(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("rule-1"),
                    "match" to JsonPrimitive("billing"),
                    "action" to JsonPrimitive("transfer"),
                ),
            ),
        )

        configApi().saveRoutingRules(
            routingRulesRequest("ws-1", listOf(foreign.with(RoutingRuleField.VOICE, "Kore"))),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/workspace/routing-rules", recorded.url.encodedPath)

        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        val rule = (body["routingRules"] as JsonArray)[0] as JsonObject
        // ⛔ THE KEYS THE ROUTE'S SCHEMA DOES NOT NAME SURVIVED. Its per-rule schema is
        // `.passthrough()` for exactly this reason, and a client that stripped them would leave a
        // workspace of rules that match nothing.
        assertEquals(setOf("id", "match", "action", "voice"), rule.keys)
        assertEquals("billing", (rule["match"] as JsonPrimitive).content)
    }

    // ── Knowledge ────────────────────────────────────────────────────────────

    @Test
    fun `the knowledge DELETE spells its parameter documentId and carries no body`() = runTest {
        // ⛔ THE SPELLING IS THE CONTRACT. `id` or `docId` answers 400 "Missing documentId", which
        // presents as a delete button that never works and an error naming a field nobody sent.
        server.enqueue(ok())

        knowledgeApi().deleteDocument("ws-1", "doc-2")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/district/workspace/knowledge", recorded.url.encodedPath)
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
        assertEquals("doc-2", recorded.url.queryParameter("documentId"))
        assertTrue(
            "the route parses only query parameters; a body would be dead weight",
            recorded.body?.utf8().isNullOrEmpty(),
        )
    }

    @Test
    fun `the knowledge create POSTs to the same path the list GETs`() = runTest {
        server.enqueue(ok("""{"success":true,"document":{"id":"doc-new"}}"""))

        knowledgeApi().createDocument(
            KnowledgeCreateRequest(
                workspaceId = "ws-1",
                title = "Holiday hours",
                content = "Closed on the 25th.",
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/workspace/knowledge", recorded.url.encodedPath)

        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        // ⚠️ `sourceType` AND `sourceUrl` ARE DROPPED WHEN NULL, which is what `explicitNulls =
        // false` buys: the route defaults `sourceType` to "text" for an absent or non-string value,
        // so omitting is the way to accept that default rather than sending an explicit null.
        assertEquals(setOf("workspaceId", "title", "content"), body.keys)
    }

    @Test
    fun `the knowledge mode is its own SIBLING route, patched not posted`() = runTest {
        // ⚠️ `workspace/knowledge-mode`, NOT `workspace/knowledge/mode` — the latter does not exist
        // and would 404. The split is the server's: `toolConfig` gates which tools may be called,
        // while the mode decides where a question is ANSWERED.
        server.enqueue(ok("""{"success":true,"mode":"linked"}"""))

        knowledgeApi().saveKnowledgeMode(
            KnowledgeModePatchRequest(workspaceId = "ws-1", mode = "linked"),
        )

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/district/workspace/knowledge-mode", recorded.url.encodedPath)
    }

    @Test
    fun `the messaging read is a GET on the shared path`() = runTest {
        // ⚠️ The five writes below share this path; the read's own shape is pinned here on its own.
        server.enqueue(ok("""{"success":true,"accounts":[]}"""))

        messagingApi().messaging("ws-1")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/workspace/messaging", recorded.url.encodedPath)
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
    }

    @Test
    fun `a blank secret reaches the wire as an ABSENT key, not as an empty string`() = runTest {
        // ⛔ THE ASSERTION THE WHOLE EDIT FORM RESTS ON, AND THE ONLY LAYER THAT CAN MAKE IT. The
        // route keeps the stored ciphertext when a secret is absent or blank; a client that sent
        // `"accountSid": ""` for an untouched box would be relying on the second half of that rule
        // for the SECRET fields and would silently overwrite the PLAINTEXT ones (`projectId`) with
        // a blank. `explicitNulls = false` is what drops them, and it lives in a private Json inside
        // HttpDistrictApi.kt — so only a real request over the wire proves it is in effect.
        server.enqueue(ok("""{"success":true,"accountId":"acct-1","defaultAccountId":"acct-1"}"""))

        messagingApi().saveMessagingAccount(
            MessagingAccountRequest(
                workspaceId = "ws-1",
                activeProvider = "twilio",
                credentialSource = "byok",
                providerConfig = MessagingProviderConfig(),
                accountId = "acct-1",
                label = "Renamed",
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/district/workspace/messaging", recorded.url.encodedPath)

        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        // ⛔ NO `action` KEY AT ALL. The route reaches `handleUpsert` through its switch's `default`
        // arm, so any value here would route this body somewhere else entirely.
        assertEquals(
            setOf("workspaceId", "activeProvider", "credentialSource", "providerConfig", "accountId", "label"),
            body.keys,
        )
        // ⛔ AND THE CONFIG IS AN EMPTY OBJECT, not an object of empty strings. Every secret key is
        // gone, and so is `phoneNumbers` — which is what keeps a label change from rewriting the
        // numbers that route this workspace's inbound calls.
        assertEquals(emptySet<String>(), (body["providerConfig"] as JsonObject).keys)
    }

    @Test
    fun `a typed secret and an edited number list DO reach the wire`() = runTest {
        // ⚠️ THE OTHER HALF OF THE TEST ABOVE. An omission rule that omitted everything would pass
        // that test and ship a form that can never save a credential.
        server.enqueue(ok("""{"success":true,"accountId":"acct-1"}"""))

        messagingApi().saveMessagingAccount(
            MessagingAccountRequest(
                workspaceId = "ws-1",
                activeProvider = "twilio",
                credentialSource = "byok",
                providerConfig = MessagingProviderConfig(
                    phoneNumbers = listOf("+14165550111"),
                    accountSid = "AC_typed",
                    authToken = "typed",
                ),
                makeDefault = true,
            ),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()) as JsonObject
        // ⚠️ NO `accountId`: this is a create, and inventing one would make the route answer 404.
        assertTrue("accountId" !in body.keys)
        assertEquals(JsonPrimitive(true), body["makeDefault"])
        val config = body["providerConfig"] as JsonObject
        assertEquals(JsonPrimitive("AC_typed"), config["accountSid"])
        assertEquals(JsonPrimitive("typed"), config["authToken"])
        assertEquals(
            JsonArray(listOf(JsonPrimitive("+14165550111"))),
            config["phoneNumbers"],
        )
        // ⚠️ `provider` IS OMITTED ON A SAVE. The route deletes the key on arrival, so sending it
        // would be harmless and misleading — it is the TEST route that dispatches on it.
        assertTrue("provider" !in config.keys)
    }

    @Test
    fun `the four action writes are PATCHes on ONE path, told apart only by the action string`() =
        runTest {
            // ⛔ THERE IS NO `messaging/default` AND NO `messaging/delete` — both would 404 — so the
            // only thing separating "re-point every outbound send" from "delete this account and
            // release its phone numbers" is a string inside the JSON. Nothing about the URL or the
            // verb distinguishes them, which is exactly why each has its own request type with the
            // action baked in, and why this test asserts the string rather than the route.
            val api = messagingApi()

            server.enqueue(ok("""{"success":true,"defaultAccountId":"acct-1"}"""))
            api.setDefaultAccount(MessagingDefaultRequest(workspaceId = "ws-1", accountId = "acct-1"))
            assertAction(setOf("workspaceId", "accountId", "action"), "setDefault")

            server.enqueue(ok("""{"success":true,"channelDefaults":{}}"""))
            api.setChannelDefault(
                MessagingChannelDefaultRequest(
                    workspaceId = "ws-1",
                    channel = "voice",
                    accountId = "acct-1",
                ),
            )
            assertAction(setOf("workspaceId", "channel", "accountId", "action"), "setChannelDefault")

            server.enqueue(ok("""{"success":true}"""))
            api.deleteMessagingAccount(
                MessagingDeleteRequest(workspaceId = "ws-1", accountId = "acct-1"),
            )
            assertAction(setOf("workspaceId", "accountId", "action"), "delete")

            server.enqueue(ok("""{"success":true}"""))
            api.saveCreatorCell(
                MessagingMetaRequest(workspaceId = "ws-1", creatorCellNumber = "+14165550170"),
            )
            assertAction(setOf("workspaceId", "creatorCellNumber", "action"), "meta")
        }

    @Test
    fun `the delete is a PATCH with a BODY, not a DELETE with a query`() = runTest {
        // ⛔ THREE CONVENTIONS ACROSS THIS CLIENT AND THIS ONE IS THE ODD ONE OUT. `contacts` and
        // `knowledge` both remove with `DELETE` plus query parameters; messaging removes with an
        // action on the shared PATCH body. A `DELETE` to this path is a 405.
        server.enqueue(ok("""{"success":true}"""))

        messagingApi().deleteMessagingAccount(
            MessagingDeleteRequest(workspaceId = "ws-1", accountId = "acct-1"),
        )

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/district/workspace/messaging", recorded.url.encodedPath)
        assertEquals(null, recorded.url.queryParameter("accountId"))
    }

    @Test
    fun `the credential probe is a POST on a SIBLING path and carries the provider`() = runTest {
        // ⛔ NOT A SIXTH ACTION. `messaging/test` is its own route, its own verb, and its failure
        // answer is a 200 — none of which can be inferred from the five PATCHes above. And it
        // dispatches on `providerConfig.provider`, which the save route deletes: omitting it here
        // is a 400 "Unknown provider" for credentials that are perfectly good.
        server.enqueue(ok("""{"success":true,"details":{"friendlyName":"Acme"}}"""))

        messagingApi().testMessagingCredentials(
            MessagingTestRequest(
                workspaceId = "ws-1",
                providerConfig = MessagingProviderConfig(
                    provider = "twilio",
                    accountSid = "AC_typed",
                    authToken = "typed",
                ),
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/workspace/messaging/test", recorded.url.encodedPath)

        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals(setOf("workspaceId", "providerConfig"), body.keys)
        assertEquals(
            JsonPrimitive("twilio"),
            (body["providerConfig"] as JsonObject)["provider"],
        )
    }

    /** ⚠️ Reads the request the server just took and pins its key set and its `action`. */
    private fun assertAction(keys: Set<String>, action: String) {
        val body = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()) as JsonObject
        assertEquals(keys, body.keys)
        assertEquals(JsonPrimitive(action), body["action"])
    }
}
