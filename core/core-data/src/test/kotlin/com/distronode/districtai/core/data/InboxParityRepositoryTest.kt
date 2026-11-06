package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.ConversationAssignResponse
import com.distronode.districtai.core.model.ConversationAssignment
import com.distronode.districtai.core.model.ConversationAssignmentsResponse
import com.distronode.districtai.core.model.MetaWindowResponse
import com.distronode.districtai.core.model.SendMessageRequest
import com.distronode.districtai.core.model.SendMessageResponse
import com.distronode.districtai.core.model.SentMessage
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.WhatsAppTemplatesResponse
import com.distronode.districtai.core.model.WhatsAppWindowResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeConversationAssignmentApi
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeWhatsAppApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The repositories behind thread owners (parity 3) and WhatsApp's window and templates (parity 5). */
class InboxParityRepositoryTest {

    private val nbsp = 0xA0.toChar()

    @Test
    fun `a thread's owner is its row in the assignments, and no row is nobody`() = runTest {
        val api = FakeConversationAssignmentApi().apply {
            assignmentsResult = ApiResult.Success(
                ConversationAssignmentsResponse(
                    success = true,
                    assignments = listOf(ConversationAssignment("contact:c1", "ada@example.com")),
                ),
            )
        }
        val repository = ConversationAssignmentRepository(api)

        assertEquals(ApiResult.Success("ada@example.com"), repository.owner("ws-1", "contact:c1"))
        assertEquals(ApiResult.Success(null), repository.owner("ws-1", "contact:c2"))
        assertEquals(listOf("ws-1", "ws-1"), api.reads)
    }

    @Test
    fun `an owners read that does not affirm success is drift, and a failure passes through`() = runTest {
        val api = FakeConversationAssignmentApi()
        val repository = ConversationAssignmentRepository(api)

        api.assignmentsResult = ApiResult.Success(ConversationAssignmentsResponse(success = false))
        assertTrue(repository.owner("ws-1", "contact:c1") is ApiResult.DecodeFailure)
        api.assignmentsResult = ApiResult.Forbidden("no")
        assertEquals(ApiResult.Forbidden("no"), repository.owner("ws-1", "contact:c1"))
    }

    @Test
    fun `an assignment answers the owner recorded, and an unassign answers nobody`() = runTest {
        val api = FakeConversationAssignmentApi()
        val repository = ConversationAssignmentRepository(api)

        assertEquals(ApiResult.Success("b@example.com"), repository.assign("ws-1", "contact:c1", "b@example.com"))
        assertEquals(ApiResult.Success(null), repository.assign("ws-1", "contact:c1", null))
        assertEquals(
            listOf(
                FakeConversationAssignmentApi.Write("ws-1", "contact:c1", "b@example.com"),
                FakeConversationAssignmentApi.Write("ws-1", "contact:c1", null),
            ),
            api.writes,
        )
    }

    @Test
    fun `a refused assignment carries the server's code, and a false envelope is drift`() = runTest {
        val api = FakeConversationAssignmentApi()
        val repository = ConversationAssignmentRepository(api)
        val refusal = ApiResult.HttpFailure(status = 400, message = "Viewer", code = "assignee_is_viewer")

        api.assignResult = refusal
        assertEquals(refusal, repository.assign("ws-1", "contact:c1", "v@example.com"))
        api.assignResult = ApiResult.Success(ConversationAssignResponse(success = false))
        assertTrue(repository.assign("ws-1", "contact:c1", "v@example.com") is ApiResult.DecodeFailure)
    }

    @Test
    fun `the window passes through, and a false envelope or a failure is not an open window`() = runTest {
        val api = FakeWhatsAppApi()
        val repository = WhatsAppRepository(api)

        assertEquals(api.windowResult, repository.window("ws-1", "+14165550177"))
        assertEquals(listOf("ws-1" to "+14165550177"), api.windowReads)
        api.windowResult = ApiResult.Success(WhatsAppWindowResponse(success = false))
        assertTrue(repository.window("ws-1", "+1") is ApiResult.DecodeFailure)
        api.windowResult = ApiResult.Forbidden("no")
        assertEquals(ApiResult.Forbidden("no"), repository.window("ws-1", "+1"))
    }

    @Test
    fun `the Meta window passes through, and a false envelope or a failure is not an open window`() = runTest {
        val api = FakeWhatsAppApi()
        val repository = WhatsAppRepository(api)

        assertEquals(api.metaWindowResult, repository.metaWindow("ws-1", "instagram", "ig:5550102"))
        assertEquals(listOf(Triple("ws-1", "instagram", "ig:5550102")), api.metaWindowReads)
        api.metaWindowResult = ApiResult.Success(MetaWindowResponse(success = false))
        assertTrue(repository.metaWindow("ws-1", "messenger", "fb:1") is ApiResult.DecodeFailure)
        api.metaWindowResult = ApiResult.Forbidden("no")
        assertEquals(ApiResult.Forbidden("no"), repository.metaWindow("ws-1", "messenger", "fb:1"))
    }

    @Test
    fun `only the sendable templates of this sender are offered`() = runTest {
        val good = WhatsAppTemplate(id = "t1", status = "approved", body = "Hi {{1}}", variableCount = 1)
        val api = FakeWhatsAppApi().apply {
            templatesResult = ApiResult.Success(
                WhatsAppTemplatesResponse(
                    success = true,
                    templates = listOf(
                        good,
                        good.copy(id = "p", status = "pending"),
                        good.copy(id = "o", senderId = "s2"),
                    ),
                ),
            )
        }
        val repository = WhatsAppRepository(api)

        assertEquals(ApiResult.Success(listOf(good)), repository.templates("ws-1", "s1"))
        assertEquals(listOf("ws-1" to "s1"), api.templateReads)
        api.templatesResult = ApiResult.Success(WhatsAppTemplatesResponse(success = false))
        assertTrue(repository.templates("ws-1", "s1") is ApiResult.DecodeFailure)
        api.templatesResult = ApiResult.Forbidden("no")
        assertEquals(ApiResult.Forbidden("no"), repository.templates("ws-1", "s1"))
    }

    @Test
    fun `a template send carries the template, the trimmed values and their fill as the body`() = runTest {
        val api = FakeDistrictApi().apply {
            sendResult = ApiResult.Success(SendMessageResponse(success = true, message = SentMessage(id = "m9")))
        }
        val template = WhatsAppTemplate(id = "wa_tpl_reminder", body = "Hi {{1}}, see you {{2}}.", variableCount = 2)

        val result = InboxRepository(api).sendTemplate(
            workspaceId = "ws-1",
            to = "+14165550177",
            senderId = "wa_sender_contract",
            template = template,
            values = listOf("${nbsp}Ada ", "Thursday"),
        )

        assertEquals(ApiResult.Success("m9"), result)
        assertEquals(
            SendMessageRequest(
                workspaceId = "ws-1",
                to = "+14165550177",
                body = "Hi Ada, see you Thursday.",
                channel = "whatsapp",
                whatsappSenderId = "wa_sender_contract",
                templateId = "wa_tpl_reminder",
                templateVariables = listOf("Ada", "Thursday"),
            ),
            api.sends.single(),
        )
    }

    @Test
    fun `a WhatsApp text names the window's sender, and a refusal is still a refusal`() = runTest {
        val api = FakeDistrictApi()
        val repository = InboxRepository(api)

        repository.send("ws-1", "+14165550177", "hi", "whatsapp", whatsappSenderId = "wa_sender_1")
        assertEquals("wa_sender_1", api.sends.single().whatsappSenderId)

        api.sendResult = ApiResult.Success(SendMessageResponse(success = false, error = "closed"))
        val template = WhatsAppTemplate(id = "t", body = "Hello", variableCount = 0)
        assertEquals(
            ApiResult.HttpFailure(status = 200, message = "closed"),
            repository.sendTemplate("ws-1", "+1", "s", template, emptyList()),
        )
        assertEquals(emptyList<String>(), api.sends.last().templateVariables)
    }
}
