package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.ToolsPatchRequest
import com.distronode.districtai.core.model.WorkspaceRenameRequest
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What every section of [HttpDistrictApi] puts on the wire, and what it makes of the answer.
 *
 * ⛔ THE REQUEST IS THE HALF THIS CLIENT CAN GET WRONG ON ITS OWN. The contract fixtures pin what
 * the server SENDS; the verb, the path, which ids travel in the query and which in a body are
 * decided here, and each route reads exactly one of those places. A read sent with its id in the
 * wrong place answers a 400 that looks like a broken screen, and a write sent with the wrong verb
 * reaches a different handler on the same path. So every test below asserts the verb, the path,
 * the complete query and the body, not just that something came back.
 *
 * ⚠️ THE RESPONSES ARE THE REAL FIXTURES. Each call is answered with the committed body the
 * server's own test suite generated for that route, and the decoded value is compared with an
 * independent decode of the same file. That proves the section hands the answer to the serializer
 * its interface promises, with nothing lost on the way through the client.
 *
 * ⚠️ Driven over real HTTP through MockWebServer, like [HqRequestBodyTest]: the setting that
 * decides a body's shape (`explicitNulls = false`) lives on a private Json instance inside
 * HttpDistrictApi.kt, so encoding here with a locally built Json would test a different
 * configuration than the one that ships.
 */
class HttpDistrictApiTransportTest {

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

    private fun api(): DistrictApi = HttpDistrictApi(testApiClient(server, refreshApi))

    private fun fixture(name: String): String {
        val configured = System.getProperty("district.contracts.dir")
        assertTrue(
            "System property district.contracts.dir is not set. It is configured in " +
                "core/core-network/build.gradle.kts; without it this test cannot find the " +
                "fixture and would otherwise pass by verifying nothing.",
            !configured.isNullOrBlank(),
        )
        val file = File(configured!!, name)
        assertTrue("Missing contract fixture ${file.absolutePath}.", file.isFile)
        return file.readText()
    }

    /**
     * Answer the next request with [fixtureName], run [call], and hand back what was sent together
     * with the decoded value, after checking that value is exactly what [serializer] makes of the
     * same file.
     */
    private suspend fun <T> exchange(
        fixtureName: String,
        serializer: DeserializationStrategy<T>,
        call: suspend DistrictApi.() -> ApiResult<T>,
    ): Pair<RecordedRequest, T> {
        val body = fixture(fixtureName)
        server.enqueue(MockResponse(code = 200, body = body))
        val result = api().call()
        assertTrue("expected a decoded success, got $result", result is ApiResult.Success)
        val value = (result as ApiResult.Success).value
        assertEquals(DistrictApiClient.DEFAULT_JSON.decodeFromString(serializer, body), value)
        return server.takeRequest() to value
    }

    private fun RecordedRequest.query(): Map<String, String?> =
        url.queryParameterNames.associateWith { url.queryParameter(it) }

    private fun RecordedRequest.jsonBody(): JsonObject =
        Json.parseToJsonElement(body?.utf8().orEmpty()) as JsonObject

    private fun RecordedRequest.assertNoBody() {
        assertEquals("a read or a query-addressed delete carries no body", 0, body?.size ?: 0)
    }

    private fun json(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (key, value) ->
            key to when (value) {
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is List<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
                is JsonElement -> value
                else -> error("unsupported $value")
            }
        },
    )

    // ── Workspace, overview, calls ─────────────────────────────────────────────────────────

    @Test
    fun `the workspace list is a bare GET with no workspace`() = runTest {
        // ⚠️ USER-SCOPED: the one district read with no `workspaceId` at all.
        val (recorded, list) = exchange(
            "district-workspace-list.json",
            com.distronode.districtai.core.model.WorkspaceListResponse.serializer(),
        ) { workspaceList() }

        assertEquals("GET", recorded.method)
        assertEquals("/api/district/workspace/list", recorded.url.encodedPath)
        assertEquals(emptyMap<String, String?>(), recorded.query())
        assertTrue(list.workspaces.isNotEmpty())
    }

    @Test
    fun `the overview names its workspace, and omits it rather than sending it empty`() = runTest {
        val (named, _) = exchange(
            "district-overview.json",
            com.distronode.districtai.core.model.OverviewResponse.serializer(),
        ) { overview("ws-1") }
        assertEquals("GET", named.method)
        assertEquals("/api/district/overview", named.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), named.query())

        // ⛔ ABSENT, NOT EMPTY. The server re-derives the workspace when the key is missing and
        // rejects an empty string against its id pattern.
        val (unnamed, _) = exchange(
            "district-overview.json",
            com.distronode.districtai.core.model.OverviewResponse.serializer(),
        ) { overview(null) }
        assertEquals(emptyMap<String, String?>(), unnamed.query())
    }

    @Test
    fun `a call's detail puts the id in ONE path segment`() = runTest {
        // ⛔ A slash in the id must stay inside its segment rather than addressing another route.
        val (recorded, _) = exchange(
            "district-call-detail.json",
            com.distronode.districtai.core.model.CallDetailResponse.serializer(),
        ) { callDetail("ws-1", "call/1") }

        assertEquals("GET", recorded.method)
        assertEquals("/api/district/calls/call%2F1", recorded.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), recorded.query())
    }

    // ── Contacts and intelligence ──────────────────────────────────────────────────────────

    @Test
    fun `the contact list pages through the query string`() = runTest {
        val (recorded, _) = exchange(
            "district-contacts.json",
            com.distronode.districtai.core.model.ContactListResponse.serializer(),
        ) { contacts("ws-1", limit = 25, offset = 50) }

        assertEquals("GET", recorded.method)
        assertEquals("/api/district/contacts", recorded.url.encodedPath)
        assertEquals(
            mapOf("workspaceId" to "ws-1", "limit" to "25", "offset" to "50"),
            recorded.query(),
        )
    }

    @Test
    fun `one contact is read by QUERY, not by path`() = runTest {
        val (recorded, _) = exchange(
            "district-contact-detail.json",
            com.distronode.districtai.core.model.ContactDetailResponse.serializer(),
        ) { contact("ws-1", "ct-9") }

        assertEquals("GET", recorded.method)
        assertEquals("/api/district/contacts/get", recorded.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1", "contactId" to "ct-9"), recorded.query())
        recorded.assertNoBody()
    }

    @Test
    fun `creating a contact POSTs a body that omits what was not given`() = runTest {
        val (recorded, _) = exchange(
            "district-contact-update.json",
            com.distronode.districtai.core.model.ContactMutationResponse.serializer(),
        ) { createContact(CreateContactRequest(workspaceId = "ws-1", name = "Ada", phoneNumber = "+15550100")) }

        assertEquals("POST", recorded.method)
        assertEquals("/api/district/contacts/create", recorded.url.encodedPath)
        assertEquals(emptyMap<String, String?>(), recorded.query())
        // ⚠️ `email` is null and must be ABSENT: an explicit null is a different instruction.
        assertEquals(
            json("workspaceId" to "ws-1", "name" to "Ada", "phoneNumber" to "+15550100"),
            recorded.jsonBody(),
        )
    }

    @Test
    fun `deleting a contact is a DELETE addressed by query with no body`() = runTest {
        val (recorded, _) = exchange(
            "district-contact-delete.json",
            com.distronode.districtai.core.model.ContactMutationResponse.serializer(),
        ) { deleteContact("ws-1", "ct-9") }

        assertEquals("DELETE", recorded.method)
        assertEquals("/api/district/contacts/delete", recorded.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1", "contactId" to "ct-9"), recorded.query())
        recorded.assertNoBody()
    }

    @Test
    fun `enrichment and clearing intel are POSTs to two sibling routes`() = runTest {
        val (enrich, answer) = exchange(
            "district-enrich.json",
            com.distronode.districtai.core.model.EnrichResponse.serializer(),
        ) { enrichContact(EnrichRequest("ws-1", "ct-9")) }
        assertEquals("POST", enrich.method)
        assertEquals("/api/district/contacts/enrich", enrich.url.encodedPath)
        assertEquals(json("workspaceId" to "ws-1", "contactId" to "ct-9"), enrich.jsonBody())
        assertEquals("pending", answer.status)

        val (clear, _) = exchange(
            "district-clear-intel.json",
            com.distronode.districtai.core.model.ClearIntelResponse.serializer(),
        ) { clearContactIntel(ClearIntelRequest("ws-1", "ct-9")) }
        assertEquals("POST", clear.method)
        assertEquals("/api/district/contacts/clear-intel", clear.url.encodedPath)
        assertEquals(json("workspaceId" to "ws-1", "contactId" to "ct-9"), clear.jsonBody())
    }

    // ── Inbox ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the conversation list and the unread count are workspace-scoped reads`() = runTest {
        val (conversations, _) = exchange(
            "district-conversations.json",
            com.distronode.districtai.core.model.ConversationsResponse.serializer(),
        ) { conversations("ws-1") }
        assertEquals("GET", conversations.method)
        assertEquals("/api/district/conversations", conversations.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), conversations.query())

        val (unread, count) = exchange(
            "district-messages-unread-count.json",
            com.distronode.districtai.core.model.UnreadCountResponse.serializer(),
        ) { unreadCount("ws-1") }
        assertEquals("GET", unread.method)
        assertEquals("/api/district/messages/unread-count", unread.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), unread.query())
        assertEquals(3, count.count)
    }

    // ── Analytics and usage ────────────────────────────────────────────────────────────────

    @Test
    fun `analytics sends the range as its wire value`() = runTest {
        val (recorded, _) = exchange(
            "district-analytics.json",
            com.distronode.districtai.core.model.AnalyticsResponse.serializer(),
        ) { analytics("ws-1", AnalyticsRange.NINETY_DAYS) }

        assertEquals("GET", recorded.method)
        assertEquals("/api/district/analytics", recorded.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1", "timeRange" to "90d"), recorded.query())
    }

    @Test
    fun `usage and its history share one path and differ only in the query`() = runTest {
        // ⛔ ONE ROUTE, TWO RESPONSE SHAPES: the current month omits `history` and `months`
        // entirely, and asking for history says so explicitly.
        val (current, _) = exchange(
            "district-usage.json",
            com.distronode.districtai.core.model.UsageResponse.serializer(),
        ) { usage("ws-1") }
        assertEquals("/api/district/workspace/usage", current.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), current.query())

        val (history, _) = exchange(
            "district-usage-history.json",
            com.distronode.districtai.core.model.UsageHistoryResponse.serializer(),
        ) { usageHistory("ws-1", months = 6) }
        assertEquals("GET", history.method)
        assertEquals("/api/district/workspace/usage", history.url.encodedPath)
        assertEquals(
            mapOf("workspaceId" to "ws-1", "history" to "true", "months" to "6"),
            history.query(),
        )
    }

    // ── Numbers ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a number search sends only the filters that were given`() = runTest {
        val (recorded, _) = exchange(
            "district-numbers-search.json",
            com.distronode.districtai.core.model.NumberSearchResponse.serializer(),
        ) { searchNumbers("ws-1", areaCode = "416", country = null, type = "local", provider = null) }

        assertEquals("GET", recorded.method)
        assertEquals("/api/district/workspace/numbers/search", recorded.url.encodedPath)
        assertEquals(
            mapOf("workspaceId" to "ws-1", "areaCode" to "416", "type" to "local"),
            recorded.query(),
        )
    }

    @Test
    fun `owned numbers come from the provider route`() = runTest {
        val (recorded, _) = exchange(
            "district-provider-numbers.json",
            com.distronode.districtai.core.model.OwnedNumbersResponse.serializer(),
        ) { ownedNumbers("ws-1") }

        assertEquals("GET", recorded.method)
        assertEquals("/api/district/workspace/provider/numbers", recorded.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), recorded.query())
    }

    // ── Devices (under /api/auth/native, not /api/district) ───────────────────────────────

    @Test
    fun `the device list is account-scoped and lives under native auth`() = runTest {
        val (recorded, _) = exchange(
            "district-devices.json",
            com.distronode.districtai.core.model.DeviceListResponse.serializer(),
        ) { devices() }

        assertEquals("GET", recorded.method)
        assertEquals("/api/auth/native/devices", recorded.url.encodedPath)
        assertEquals(emptyMap<String, String?>(), recorded.query())
    }

    @Test
    fun `revoking one device names it in the body`() = runTest {
        val (recorded, answer) = exchange(
            "district-device-revoke.json",
            com.distronode.districtai.core.model.DeviceRevokeResponse.serializer(),
        ) { revokeDevice(DeviceRevokeRequest("device-7")) }

        assertEquals("POST", recorded.method)
        assertEquals("/api/auth/native/devices/revoke", recorded.url.encodedPath)
        assertEquals(json("deviceId" to "device-7"), recorded.jsonBody())
        assertEquals(1, answer.revoked)
    }

    @Test
    fun `revoking every device is a sibling route with an empty object body`() = runTest {
        // ⚠️ `revoke-all` is NOT under `devices/`, and OkHttp refuses a POST with no body, so the
        // smallest body it will carry is sent.
        val (recorded, answer) = exchange(
            "district-revoke-all.json",
            com.distronode.districtai.core.model.DeviceRevokeResponse.serializer(),
        ) { revokeAllDevices() }

        assertEquals("POST", recorded.method)
        assertEquals("/api/auth/native/revoke-all", recorded.url.encodedPath)
        assertEquals(JsonObject(emptyMap()), recorded.jsonBody())
        assertEquals(2, answer.revoked)
    }

    // ── Billing ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `workspace billing is scoped and Stripe billing is caller-scoped`() = runTest {
        val (workspace, _) = exchange(
            "district-workspace-billing.json",
            com.distronode.districtai.core.model.WorkspaceBillingResponse.serializer(),
        ) { workspaceBilling("ws-1") }
        assertEquals("GET", workspace.method)
        assertEquals("/api/district/workspace/billing", workspace.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), workspace.query())

        // ⛔ NOT UNDER /api/district, and it takes no workspace: the customer is the caller.
        val (stripe, _) = exchange(
            "district-billing.json",
            com.distronode.districtai.core.model.StripeBilling.serializer(),
        ) { stripeBilling() }
        assertEquals("GET", stripe.method)
        assertEquals("/api/billing", stripe.url.encodedPath)
        assertEquals(emptyMap<String, String?>(), stripe.query())
    }

    // ── Workspace configuration ────────────────────────────────────────────────────────────

    @Test
    fun `the config read is scoped by query`() = runTest {
        val (recorded, _) = exchange(
            "district-workspace-config.json",
            com.distronode.districtai.core.model.WorkspaceConfigResponse.serializer(),
        ) { workspaceConfig("ws-1") }

        assertEquals("GET", recorded.method)
        assertEquals("/api/district/workspace/config", recorded.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), recorded.query())
    }

    @Test
    fun `a persona save PATCHes only the fields it carries`() = runTest {
        // ⛔ AN EMPTY STRING CLEARS AND AN ABSENT KEY LEAVES ALONE, so the two must not collapse:
        // `greeting` is sent as "" and `personality` is not sent at all.
        val (recorded, _) = exchange(
            "district-persona-patch.json",
            com.distronode.districtai.core.model.WorkspaceConfigSaveResponse.serializer(),
        ) { savePersona(PersonaPatchRequest(workspaceId = "ws-1", name = "Ava", greeting = "")) }

        assertEquals("PATCH", recorded.method)
        assertEquals("/api/district/workspace/persona", recorded.url.encodedPath)
        assertEquals(json("workspaceId" to "ws-1", "name" to "Ava", "greeting" to ""), recorded.jsonBody())
    }

    @Test
    fun `a tools save PATCHes the whole allowlist in order`() = runTest {
        val (recorded, _) = exchange(
            "district-tools-patch.json",
            com.distronode.districtai.core.model.WorkspaceConfigSaveResponse.serializer(),
        ) { saveTools(ToolsPatchRequest(workspaceId = "ws-1", allowedTools = listOf("b", "a"))) }

        assertEquals("PATCH", recorded.method)
        assertEquals("/api/district/workspace/tools", recorded.url.encodedPath)
        assertEquals(
            json("workspaceId" to "ws-1", "allowedTools" to listOf("b", "a")),
            recorded.jsonBody(),
        )
    }

    @Test
    fun `the knowledge reads are two routes, both scoped by query`() = runTest {
        val (documents, _) = exchange(
            "district-knowledge.json",
            com.distronode.districtai.core.model.KnowledgeListResponse.serializer(),
        ) { knowledgeDocuments("ws-1") }
        assertEquals("GET", documents.method)
        assertEquals("/api/district/workspace/knowledge", documents.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), documents.query())

        val (mode, _) = exchange(
            "district-knowledge-mode.json",
            com.distronode.districtai.core.model.KnowledgeModeResponse.serializer(),
        ) { knowledgeMode("ws-1") }
        assertEquals("GET", mode.method)
        assertEquals("/api/district/workspace/knowledge-mode", mode.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), mode.query())
    }

    // ── Members ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the roster is read by query`() = runTest {
        val (recorded, _) = exchange(
            "district-members.json",
            com.distronode.districtai.core.model.MemberListResponse.serializer(),
        ) { members("ws-1") }

        assertEquals("GET", recorded.method)
        assertEquals("/api/district/workspace/members", recorded.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), recorded.query())
    }

    @Test
    fun `removing a member is a DELETE addressed by email in the query`() = runTest {
        val (recorded, _) = exchange(
            "district-member-remove.json",
            com.distronode.districtai.core.model.MemberMutationResponse.serializer(),
        ) { removeMember("ws-1", "ada+ops@example.com") }

        assertEquals("DELETE", recorded.method)
        assertEquals("/api/district/workspace/members", recorded.url.encodedPath)
        assertEquals(
            mapOf("workspaceId" to "ws-1", "email" to "ada+ops@example.com"),
            recorded.query(),
        )
        recorded.assertNoBody()
    }

    @Test
    fun `a rename PATCHes its own route`() = runTest {
        val (recorded, answer) = exchange(
            "district-rename.json",
            com.distronode.districtai.core.model.RenameResponse.serializer(),
        ) { renameWorkspace(WorkspaceRenameRequest(workspaceId = "ws-1", name = "Renamed Workspace")) }

        assertEquals("PATCH", recorded.method)
        assertEquals("/api/district/workspace/rename", recorded.url.encodedPath)
        assertEquals(json("workspaceId" to "ws-1", "name" to "Renamed Workspace"), recorded.jsonBody())
        assertEquals("Renamed Workspace", answer.name)
    }

    // ── Meetings ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a room token is a POST naming the room`() = runTest {
        val (recorded, answer) = exchange(
            "district-room-token.json",
            com.distronode.districtai.core.model.RoomTokenResponse.serializer(),
        ) { roomToken(RoomTokenRequest(roomName = "meet_ws-1_abc")) }

        assertEquals("POST", recorded.method)
        assertEquals("/api/district/calls/token", recorded.url.encodedPath)
        val body = recorded.jsonBody()
        assertEquals(JsonPrimitive("meet_ws-1_abc"), body["roomName"])
        // ⚠️ Nothing beyond the room and the constant identity may ride along.
        assertTrue(body.keys.all { it == "roomName" || it == "identity" })
        assertEquals("contract-livekit-room-jwt", answer.token)
    }

    @Test
    fun `the meeting list is a bare array and one meeting is addressed by path`() = runTest {
        val (list, _) = exchange(
            "district-meetings.json",
            ListSerializer(com.distronode.districtai.core.model.MeetingSummary.serializer()),
        ) { meetings("ws-1") }
        assertEquals("GET", list.method)
        assertEquals("/api/district/meetings", list.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), list.query())

        val (detail, _) = exchange(
            "district-meeting-detail.json",
            com.distronode.districtai.core.model.MeetingDetail.serializer(),
        ) { meetingDetail("ws-1", "m/1") }
        assertEquals("GET", detail.method)
        assertEquals("/api/district/meetings/m%2F1", detail.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), detail.query())
    }

    // ── Workflows and the campaign ─────────────────────────────────────────────────────────

    @Test
    fun `workflows are listed by query and their runs page by query`() = runTest {
        val (list, _) = exchange(
            "district-workflows.json",
            com.distronode.districtai.core.model.WorkflowListResponse.serializer(),
        ) { workflows("ws-1") }
        assertEquals("GET", list.method)
        assertEquals("/api/district/workflows", list.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), list.query())

        // ⛔ BOTH IDS IN THE QUERY: there is no per-workflow path, so `workflows/{id}` would 404.
        val (runs, _) = exchange(
            "district-workflow-runs.json",
            com.distronode.districtai.core.model.WorkflowRunsResponse.serializer(),
        ) { workflowRuns("ws-1", "wf-2", limit = 10, offset = 20) }
        assertEquals("GET", runs.method)
        assertEquals("/api/district/workflows/runs", runs.url.encodedPath)
        assertEquals(
            mapOf("workspaceId" to "ws-1", "workflowId" to "wf-2", "limit" to "10", "offset" to "20"),
            runs.query(),
        )
    }

    @Test
    fun `switching a workflow PATCHes the collection with the id in the body`() = runTest {
        val (recorded, _) = exchange(
            "district-workflow-toggle.json",
            com.distronode.districtai.core.model.WorkflowToggleResponse.serializer(),
        ) { setWorkflowActive(WorkflowToggleRequest("ws-1", "wf-2", active = false)) }

        assertEquals("PATCH", recorded.method)
        assertEquals("/api/district/workflows", recorded.url.encodedPath)
        assertEquals(emptyMap<String, String?>(), recorded.query())
        assertEquals(
            json("workspaceId" to "ws-1", "workflowId" to "wf-2", "active" to false),
            recorded.jsonBody(),
        )
    }

    @Test
    fun `the campaign is read and paused on one route with two verbs`() = runTest {
        val (status, _) = exchange(
            "district-campaign-status.json",
            com.distronode.districtai.core.model.CampaignStatusResponse.serializer(),
        ) { campaignStatus("ws-1") }
        assertEquals("GET", status.method)
        assertEquals("/api/district/workspace/campaign-status", status.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), status.query())

        val (pause, answer) = exchange(
            "district-campaign-pause.json",
            com.distronode.districtai.core.model.CampaignStatusResponse.serializer(),
        ) { setCampaignEnabled(CampaignPauseRequest("ws-1", infiniteSdrEnabled = false)) }
        assertEquals("PATCH", pause.method)
        assertEquals("/api/district/workspace/campaign-status", pause.url.encodedPath)
        assertEquals(json("workspaceId" to "ws-1", "infiniteSdrEnabled" to false), pause.jsonBody())
        assertEquals(false, answer.campaign?.infiniteSdrEnabled)
    }

    // ── Scheduling ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the scheduling status is read and enabled on two sibling routes`() = runTest {
        val (status, _) = exchange(
            "district-scheduling-status-ready.json",
            com.distronode.districtai.core.model.SchedulingStatusResponse.serializer(),
        ) { schedulingStatus("ws-1") }
        assertEquals("GET", status.method)
        assertEquals("/api/district/scheduling/status", status.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1"), status.query())

        val (enable, _) = exchange(
            "district-scheduling-enable.json",
            com.distronode.districtai.core.model.SchedulingEnableResponse.serializer(),
        ) { enableScheduling(SchedulingEnableRequest("ws-1")) }
        assertEquals("POST", enable.method)
        assertEquals("/api/district/scheduling/enable", enable.url.encodedPath)
        assertEquals(json("workspaceId" to "ws-1"), enable.jsonBody())
    }

    @Test
    fun `the scheduling sso target is the Location of a 302 that is NOT followed`() = runTest {
        // ⛔ FOLLOWING THE REDIRECT WOULD SPEND THE SINGLE-USE TOKEN IN THE Location, so the client
        // reads the header and stops. A request to the Location would show up here as a second
        // request, which is asserted absent.
        server.enqueue(
            MockResponse(
                code = 302,
                headers = okhttp3.Headers.headersOf("Location", "https://book.example.test/sso?t=one-use"),
            ),
        )

        val result = api().schedulingSsoTarget("ws-1", next = "/event-types")

        assertEquals(ApiResult.Success("https://book.example.test/sso?t=one-use"), result)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/scheduling/sso", recorded.url.encodedPath)
        assertEquals(mapOf("workspaceId" to "ws-1", "next" to "/event-types"), recorded.query())
        assertEquals("the redirect must not be followed", 1, server.requestCount)
    }

    @Test
    fun `a scheduling sso answer that is not a redirect maps through the ordinary failure path`() =
        runTest {
            server.enqueue(MockResponse(code = 404, body = """{"error":"Not found"}"""))

            val result = api().schedulingSsoTarget("ws-1", next = "/event-types")

            assertEquals(ApiResult.NotFound("Not found"), result)
            assertNull(server.takeRequest().body?.utf8()?.takeIf { it.isNotEmpty() })
        }
}
