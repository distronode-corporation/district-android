package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SendMessageRequest
import com.distronode.districtai.core.model.SendMessageResponse
import com.distronode.districtai.core.model.SentMessage
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.WhatsAppTemplatesResponse
import com.distronode.districtai.core.model.WhatsAppWindowResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeWhatsAppApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The repositories behind WhatsApp's window and templates (parity 5). */
class InboxParityRepositoryTest {

    private val nbsp = 0xA0.toChar()

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
