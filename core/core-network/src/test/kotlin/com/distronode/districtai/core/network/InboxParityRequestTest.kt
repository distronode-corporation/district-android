package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.SendMessageRequest
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * What app parity 3 and 5 put on the wire (thread owners, the WhatsApp window and templates, the
 * template send), answered with the server's recorded fixtures.
 *
 * ⛔ THE BODIES ARE ASSERTED AS JSON, KEY BY KEY, because two of them turn on a key's presence: an
 * unassign must send `"assigneeEmail": null` (an absent key is a 400), and an SMS send must stay
 * exactly the four keys it always was now that the WhatsApp keys exist.
 */
class InboxParityRequestTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client() = testApiClient(server, FakeRefreshApi().apply { rotating() })

    private fun fixture(name: String): MockResponse {
        val dir = System.getProperty("district.contracts.dir")
        assertTrue("district.contracts.dir must be set by core-network's build", !dir.isNullOrBlank())
        return MockResponse(code = 200, body = File(dir, name).readText())
    }

    private fun RecordedRequest.json(): JsonObject =
        Json.parseToJsonElement(body?.utf8().orEmpty()) as JsonObject

    @Test
    fun `an assignment posts the thread and the member, and decodes the recorded answer`() = runTest {
        server.enqueue(fixture("district-conversation-assign.json"))

        val result = HttpConversationAssignmentApi(client())
            .assignConversation("ws-1", "contact:contact_contract_1", "teammate@contract.test")

        assertEquals("teammate@contract.test", (result as ApiResult.Success).value.assignment?.assigneeEmail)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/conversations/assign", recorded.url.encodedPath)
        assertEquals(
            """{"workspaceId":"ws-1","threadKey":"contact:contact_contract_1",""" +
                """"assigneeEmail":"teammate@contract.test"}""",
            recorded.body?.utf8(),
        )
    }

    @Test
    fun `an unassign sends an explicit null, never an absent key`() = runTest {
        server.enqueue(fixture("district-conversation-unassign.json"))

        val result = HttpConversationAssignmentApi(client()).assignConversation("ws-1", "addr:fb:5550101", null)

        assertNull((result as ApiResult.Success).value.assignment)
        val body = server.takeRequest().json()
        assertTrue("the key must be present", body.containsKey("assigneeEmail"))
        assertEquals(JsonNull, body["assigneeEmail"])
    }

    @Test
    fun `the owners are a GET scoped by query`() = runTest {
        server.enqueue(fixture("district-conversation-assignments.json"))

        val result = HttpConversationAssignmentApi(client()).conversationAssignments("ws-1")

        assertTrue((result as ApiResult.Success).value.assignments.isNotEmpty())
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/conversations/assignments", recorded.url.encodedPath)
        assertEquals("workspaceId=ws-1", recorded.url.encodedQuery)
    }

    @Test
    fun `the Messenger window is a GET on the send path, with the channel and the scoped address`() = runTest {
        server.enqueue(fixture("district-meta-window-human-agent.json"))

        val result = HttpWhatsAppApi(client()).metaWindow("ws-1", "messenger", "fb:5550101")

        assertEquals("human_agent", (result as ApiResult.Success).value.window.state)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/messages/send", recorded.url.encodedPath)
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
        assertEquals("messenger", recorded.url.queryParameter("channel"))
        assertEquals("fb:5550101", recorded.url.queryParameter("to"))
        assertEquals("a GET carries no body", 0, recorded.body?.size ?: 0)
    }

    @Test
    fun `the window is read for one number, its plus sign encoded`() = runTest {
        server.enqueue(fixture("district-whatsapp-window-closed.json"))

        val result = HttpWhatsAppApi(client()).whatsAppWindow("ws-1", "+14165550177")

        assertEquals("wa_sender_contract", (result as ApiResult.Success).value.senderId)
        val recorded = server.takeRequest()
        assertEquals("/api/district/workspace/whatsapp/window", recorded.url.encodedPath)
        // ⛔ A raw `+` in a query is a space to the server, which would read the window of nobody.
        assertEquals("workspaceId=ws-1&to=%2B14165550177", recorded.url.encodedQuery)
        assertEquals("+14165550177", recorded.url.queryParameter("to"))
    }

    @Test
    fun `the templates are read for one sender`() = runTest {
        server.enqueue(fixture("district-whatsapp-templates.json"))

        val result = HttpWhatsAppApi(client()).whatsAppTemplates("ws-1", "wa_sender_contract")

        assertEquals(2, (result as ApiResult.Success).value.templates.size)
        val recorded = server.takeRequest()
        assertEquals("/api/district/workspace/whatsapp/templates", recorded.url.encodedPath)
        assertEquals("workspaceId=ws-1&senderId=wa_sender_contract", recorded.url.encodedQuery)
    }

    @Test
    fun `an SMS send is byte for byte the body it was before WhatsApp`() = runTest {
        server.enqueue(fixture("district-message-send.json"))

        HttpInboxApi(client()).sendMessage(
            SendMessageRequest(workspaceId = "ws-1", to = "+14165550142", body = "Hi", channel = "sms"),
        )

        assertEquals(
            """{"workspaceId":"ws-1","to":"+14165550142","body":"Hi","channel":"sms"}""",
            server.takeRequest().body?.utf8(),
        )
    }

    @Test
    fun `a WhatsApp text names its sender and carries no template keys`() = runTest {
        server.enqueue(fixture("district-message-send.json"))

        HttpInboxApi(client()).sendMessage(
            SendMessageRequest(
                workspaceId = "ws-1",
                to = "+14165550177",
                body = "Yes, we do flat roofs.",
                channel = "whatsapp",
                whatsappSenderId = "wa_sender_contract",
            ),
        )

        assertEquals(
            """{"workspaceId":"ws-1","to":"+14165550177","body":"Yes, we do flat roofs.",""" +
                """"channel":"whatsapp","whatsappSenderId":"wa_sender_contract"}""",
            server.takeRequest().body?.utf8(),
        )
    }

    /** The request shape of `district-message-send-whatsapp-template.json` (parity-35 section 4). */
    @Test
    fun `a template send carries the template, its variables and the filled body`() = runTest {
        server.enqueue(fixture("district-message-send-whatsapp-template.json"))

        val result = HttpInboxApi(client()).sendMessage(
            SendMessageRequest(
                workspaceId = "ws-contract-test",
                to = "+14165550177",
                body = "Hi Ada, this is a reminder of your appointment on Thursday.",
                channel = "whatsapp",
                whatsappSenderId = "wa_sender_contract",
                templateId = "wa_tpl_reminder",
                templateVariables = listOf("Ada", "Thursday"),
            ),
        )

        assertEquals("whatsapp", (result as ApiResult.Success).value.message?.type)
        val body = server.takeRequest().json()
        assertEquals(
            setOf("workspaceId", "to", "body", "channel", "whatsappSenderId", "templateId", "templateVariables"),
            body.keys,
        )
        assertEquals("wa_tpl_reminder", body["templateId"]?.jsonPrimitive?.content)
        assertEquals("""["Ada","Thursday"]""", body["templateVariables"].toString())
    }

    @Test
    fun `a template without variables still sends an empty array`() = runTest {
        server.enqueue(fixture("district-message-send-whatsapp-template.json"))

        HttpInboxApi(client()).sendMessage(
            SendMessageRequest(
                workspaceId = "ws-1",
                to = "+14165550177",
                body = "Hello from Acme",
                channel = "whatsapp",
                whatsappSenderId = "wa_sender_contract",
                templateId = "wa_tpl_hello",
                templateVariables = emptyList(),
            ),
        )

        // ⛔ The route refuses a template send whose variables are not an array.
        assertEquals("[]", server.takeRequest().json()["templateVariables"].toString())
    }
}
