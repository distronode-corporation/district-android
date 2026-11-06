package com.distronode.districtai.ui.inbox

import androidx.lifecycle.SavedStateHandle
import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.WhatsAppRepository
import com.distronode.districtai.core.model.CHANNEL_EMAIL
import com.distronode.districtai.core.model.CHANNEL_INSTAGRAM
import com.distronode.districtai.core.model.CHANNEL_MESSENGER
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.CHANNEL_WHATSAPP
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.SendMessageRequest
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.TimelineResponse
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.WhatsAppTemplatesResponse
import com.distronode.districtai.core.model.WhatsAppWindow
import com.distronode.districtai.core.model.WhatsAppWindowResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeWhatsAppApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The reply channel picker and WhatsApp's window and templates, through [ThreadViewModel] (app
 * parity 1, 2 and 5). Each rule here is one `parity.rs` in core-rust also holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadChannelsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val sms = ReplyTarget("+14165550142", CHANNEL_SMS)
    private val email = ReplyTarget("ada@example.com", CHANNEL_EMAIL)
    private val whatsApp = ReplyTarget("+14165550142", CHANNEL_WHATSAPP)
    private val messenger = ReplyTarget("fb:5550101", CHANNEL_MESSENGER)
    private val instagram = ReplyTarget("ig:5550102", CHANNEL_INSTAGRAM)

    private val reminder = WhatsAppTemplate(
        id = "wa_tpl_reminder",
        name = "appointment_reminder",
        language = "en_US",
        status = "approved",
        body = "Hi {{1}}, see you {{2}}.",
        variableCount = 2,
    )

    private val api = FakeDistrictApi().apply {
        timelineResult = ApiResult.Success(
            TimelineResponse(
                success = true,
                timeline = listOf(
                    TimelineEvent(id = "m1", type = "sms", timestamp = "2026-08-15T09:00:00.000Z", body = "hi"),
                ),
            ),
        )
    }
    private val whatsAppApi = FakeWhatsAppApi()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

    private fun closed(senderId: String? = "wa_sender_1") = ApiResult.Success(
        WhatsAppWindowResponse(success = true, window = WhatsAppWindow(open = false), senderId = senderId),
    )

    private fun templates(vararg templates: WhatsAppTemplate) =
        ApiResult.Success(WhatsAppTemplatesResponse(success = true, templates = templates.toList()))

    private fun viewModel(
        vararg choices: ReplyTarget,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = ThreadViewModel(
        services = ThreadServices(InboxRepository(api), ComposerRepository(api), WhatsAppRepository(whatsAppApi)),
        target = ThreadTarget(
            workspaceId = "ws-1",
            contactId = "c1",
            address = null,
            threadKey = "contact:c1",
            replyTarget = choices.firstOrNull(),
            moreReplies = choices.drop(1),
        ),
        role = role,
        attachmentReader = AttachmentReader { PickedAttachment("roof.png", "image/png", png) },
        savedState = SavedStateHandle(),
    )

    private fun ThreadViewModel.via() = channels.state.value

    private fun ThreadViewModel.content() = state.value as ThreadUiState.Content

    // ── Reply channels (parity 1, 2) ─────────────────────────────────────────

    @Test
    fun `the first choice is the default and the rest are offered`() = runTest(dispatcher) {
        val vm = viewModel(email, sms, messenger)
        advanceUntilIdle()

        assertEquals(listOf(email, sms, messenger), vm.via().choices)
        assertEquals(email, vm.via().selected)
        assertFalse("email carries no attachment", vm.canAttach)

        vm.send("Friday works.")
        advanceUntilIdle()
        assertEquals(CHANNEL_EMAIL to "ada@example.com", api.sends.single().let { it.channel to it.to })
    }

    @Test
    fun `a send goes on the chosen channel with that channel's own address`() = runTest(dispatcher) {
        val vm = viewModel(sms, instagram)
        advanceUntilIdle()

        vm.channels.choose(instagram)
        vm.send("We are open Saturday.")
        advanceUntilIdle()

        assertEquals(
            SendMessageRequest(
                workspaceId = "ws-1",
                to = "ig:5550102",
                body = "We are open Saturday.",
                channel = "instagram",
            ),
            api.sends.single(),
        )
    }

    @Test
    fun `switching is refused while a send is on its way, and for a channel not on offer`() = runTest(dispatcher) {
        val vm = viewModel(sms, email)
        advanceUntilIdle()

        vm.send("on our way")
        vm.channels.choose(email)
        assertEquals("the reply in flight keeps its channel", sms, vm.via().selected)
        advanceUntilIdle()

        vm.channels.choose(instagram)
        assertEquals(sms, vm.via().selected)
        vm.channels.choose(sms)
        assertEquals(sms, vm.via().selected)
        vm.channels.choose(email)
        assertEquals(email, vm.via().selected)
    }

    @Test
    fun `no choices means no reply box, and a viewer gets none whatever the server offered`() = runTest(dispatcher) {
        val none = viewModel()
        val viewer = viewModel(sms, email, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(none.canReply)
        assertFalse(viewer.canReply)
        assertTrue(viewer.via().choices.isEmpty())
        none.send("hello")
        advanceUntilIdle()
        assertTrue(api.sends.isEmpty())
    }

    @Test
    fun `attachments go by SMS only, and Send waits until they are removed`() = runTest(dispatcher) {
        val vm = viewModel(sms, messenger)
        advanceUntilIdle()
        assertTrue(vm.canAttach)
        vm.attach("content://picked/1")
        advanceUntilIdle()
        val url = vm.content().attachments.single().url

        vm.channels.choose(messenger)
        assertFalse(vm.canAttach)
        assertFalse(vm.via().attachmentsAllowed)
        vm.attach("content://picked/2")
        vm.send("Here is the photo")
        advanceUntilIdle()
        assertTrue("an image never goes to Messenger", api.sends.isEmpty())
        assertEquals("no second picture on Messenger", 1, vm.content().attachments.size)

        vm.removeAttachment(url)
        vm.send("Here is the photo")
        advanceUntilIdle()
        assertEquals(CHANNEL_MESSENGER, api.sends.single().channel)
        assertEquals(emptyList<String>(), api.sends.single().mediaUrls)
    }

    // ── WhatsApp window (parity 5) ───────────────────────────────────────────

    @Test
    fun `an open window sends free text from the sender it named`() = runTest(dispatcher) {
        val vm = viewModel(whatsApp)
        assertEquals(WhatsAppWindowState.Checking, vm.via().whatsApp)
        advanceUntilIdle()

        assertEquals(listOf("ws-1" to "+14165550142"), whatsAppApi.windowReads)
        assertEquals(WhatsAppWindowState.Open("wa_sender_1"), vm.via().whatsApp)
        vm.send("Yes, we do flat roofs.")
        advanceUntilIdle()
        assertEquals("wa_sender_1", api.sends.single().whatsappSenderId)
        assertEquals(CHANNEL_WHATSAPP, api.sends.single().channel)
    }

    @Test
    fun `an unreadable window blocks nothing, and the route picks the sender`() = runTest(dispatcher) {
        whatsAppApi.windowResult = ApiResult.NetworkFailure(IOException("offline"))
        val vm = viewModel(whatsApp)
        advanceUntilIdle()

        assertEquals(WhatsAppWindowState.Open(null), vm.via().whatsApp)
        assertTrue(vm.via().allowsFreeText)
        vm.send("hi")
        advanceUntilIdle()
        assertNull(api.sends.single().whatsappSenderId)
    }

    @Test
    fun `a closed window turns off text and the AI draft and loads the sender's sendable templates`() =
        runTest(dispatcher) {
            whatsAppApi.windowResult = closed()
            whatsAppApi.templatesResult = templates(reminder, reminder.copy(id = "pending", status = "pending"))
            val vm = viewModel(whatsApp)
            advanceUntilIdle()

            assertEquals(
                WhatsAppWindowState.Closed("wa_sender_1", WhatsAppTemplatesState.Ready(listOf(reminder))),
                vm.via().whatsApp,
            )
            assertEquals(listOf("ws-1" to "wa_sender_1"), whatsAppApi.templateReads)
            assertFalse(vm.via().allowsFreeText)
            vm.send("free text")
            vm.generateDraft()
            advanceUntilIdle()
            assertTrue(api.sends.isEmpty())
            assertTrue("no AI draft is spent", api.generations.isEmpty())
            assertEquals("", vm.composerText.value)
        }

    @Test
    fun `a closed window with no sender has no templates to read`() = runTest(dispatcher) {
        whatsAppApi.windowResult = closed(senderId = null)
        val vm = viewModel(whatsApp)
        advanceUntilIdle()

        assertEquals(
            WhatsAppWindowState.Closed(null, WhatsAppTemplatesState.Ready(emptyList())),
            vm.via().whatsApp,
        )
        assertTrue(whatsAppApi.templateReads.isEmpty())
    }

    @Test
    fun `templates that cannot be read say so`() = runTest(dispatcher) {
        whatsAppApi.windowResult = closed()
        whatsAppApi.templatesResult = ApiResult.NetworkFailure(IOException("offline"))
        val vm = viewModel(whatsApp)
        advanceUntilIdle()

        assertEquals(WhatsAppWindowState.Closed("wa_sender_1", WhatsAppTemplatesState.Failed), vm.via().whatsApp)
    }

    @Test
    fun `leaving WhatsApp forgets a late window answer`() = runTest(dispatcher) {
        whatsAppApi.windowResult = closed()
        whatsAppApi.windowGate = CompletableDeferred()
        val vm = viewModel(whatsApp, sms)
        advanceUntilIdle()
        assertEquals(WhatsAppWindowState.Checking, vm.via().whatsApp)

        vm.channels.choose(sms)
        whatsAppApi.windowGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(WhatsAppWindowState.NotWhatsApp, vm.via().whatsApp)
        assertTrue("SMS keeps its text box", vm.via().allowsFreeText)
        assertTrue(whatsAppApi.templateReads.isEmpty())
    }

    @Test
    fun `a free-text send refused as outside the window turns the composer to templates`() = runTest(dispatcher) {
        whatsAppApi.templatesResult = templates(reminder)
        api.sendResult = ApiResult.HttpFailure(status = 409, message = "closed", code = "whatsapp_window_closed")
        val vm = viewModel(whatsApp)
        advanceUntilIdle()

        vm.send("hello?")
        advanceUntilIdle()

        assertEquals(
            WhatsAppWindowState.Closed("wa_sender_1", WhatsAppTemplatesState.Ready(listOf(reminder))),
            vm.via().whatsApp,
        )
        assertNotNull("the server's sentence still shows", vm.content().sendFailure)
    }

    @Test
    fun `that refusal with no sender known reads the window again`() = runTest(dispatcher) {
        whatsAppApi.windowResult = ApiResult.NetworkFailure(IOException("offline"))
        api.sendResult = ApiResult.HttpFailure(status = 409, message = "closed", code = "whatsapp_window_closed")
        val vm = viewModel(whatsApp)
        advanceUntilIdle()

        whatsAppApi.windowResult = closed()
        vm.send("hello?")
        advanceUntilIdle()

        assertEquals(2, whatsAppApi.windowReads.size)
        assertTrue(vm.via().whatsApp is WhatsAppWindowState.Closed)
        // A second refusal on an already-closed window changes nothing.
        vm.channels.windowClosed()
        advanceUntilIdle()
        assertEquals(2, whatsAppApi.windowReads.size)
    }

    @Test
    fun `that refusal keeps the window closed when the re-read fails too`() = runTest(dispatcher) {
        // The first read failed, so the window read as open with no sender; the send is refused,
        // and the re-read for a sender fails again.
        whatsAppApi.windowResult = ApiResult.NetworkFailure(IOException("offline"))
        api.sendResult = ApiResult.HttpFailure(status = 409, message = "closed", code = "whatsapp_window_closed")
        val vm = viewModel(whatsApp)
        advanceUntilIdle()
        assertEquals(WhatsAppWindowState.Open(null), vm.via().whatsApp)

        vm.send("hello?")
        advanceUntilIdle()

        assertEquals(2, whatsAppApi.windowReads.size)
        assertEquals(
            "closed, with no sender to read templates for",
            WhatsAppWindowState.Closed(null, WhatsAppTemplatesState.Ready(emptyList())),
            vm.via().whatsApp,
        )
        assertFalse(vm.via().allowsFreeText)
        vm.send("still there?")
        advanceUntilIdle()
        assertEquals("nothing more is sent into a closed window", 1, api.sends.size)
    }

    @Test
    fun `a re-check that fails leaves a closed window closed`() = runTest(dispatcher) {
        whatsAppApi.windowResult = closed()
        val vm = viewModel(whatsApp)
        advanceUntilIdle()
        assertTrue(vm.via().whatsApp is WhatsAppWindowState.Closed)

        whatsAppApi.windowResult = ApiResult.NetworkFailure(IOException("offline"))
        vm.channels.recheckWindow()
        advanceUntilIdle()

        assertTrue(vm.via().whatsApp is WhatsAppWindowState.Closed)
        assertFalse(vm.via().allowsFreeText)
    }

    @Test
    fun `that code on another channel, or another code on WhatsApp, leaves the window alone`() = runTest(dispatcher) {
        api.sendResult = ApiResult.HttpFailure(status = 409, message = "closed", code = "whatsapp_window_closed")
        val onSms = viewModel(sms)
        advanceUntilIdle()
        onSms.send("hi")
        advanceUntilIdle()
        assertEquals(WhatsAppWindowState.NotWhatsApp, onSms.via().whatsApp)

        api.sendResult = ApiResult.HttpFailure(status = 400, message = "bad", code = "whatsapp_unsupported")
        val onWhatsApp = viewModel(whatsApp)
        advanceUntilIdle()
        onWhatsApp.send("hi")
        advanceUntilIdle()
        assertEquals(WhatsAppWindowState.Open("wa_sender_1"), onWhatsApp.via().whatsApp)
    }

    // ── The template sheet (parity 5) ────────────────────────────────────────

    private fun closedThread(): ThreadViewModel {
        whatsAppApi.windowResult = closed()
        whatsAppApi.templatesResult =
            templates(reminder, reminder.copy(id = "hello", body = "Hello", variableCount = 0))
        return viewModel(whatsApp)
    }

    @Test
    fun `the sheet opens only on a closed window`() = runTest(dispatcher) {
        val open = viewModel(whatsApp)
        advanceUntilIdle()
        open.channels.openTemplates()
        assertFalse(open.via().sheet.open)

        val vm = closedThread()
        advanceUntilIdle()
        vm.channels.openTemplates()
        assertTrue(vm.via().sheet.open)
    }

    @Test
    fun `one field per variable, a live preview, and Send only once all are filled`() = runTest(dispatcher) {
        val vm = closedThread()
        advanceUntilIdle()
        vm.channels.openTemplates()

        vm.channels.chooseTemplate(reminder)
        assertEquals(listOf("", ""), vm.via().sheet.values)
        assertEquals("Hi {{1}}, see you {{2}}.", vm.via().sheet.preview)
        assertFalse(vm.via().sheet.complete)

        vm.channels.setTemplateValue(0, "Ada")
        vm.channels.setTemplateValue(5, "ignored")
        assertEquals("Hi Ada, see you {{2}}.", vm.via().sheet.preview)
        vm.channels.sendTemplate()
        advanceUntilIdle()
        assertTrue("an incomplete template is not sent", api.sends.isEmpty())

        vm.channels.setTemplateValue(1, " Thursday ")
        assertTrue(vm.via().sheet.complete)
        // Choosing the same template again keeps what was filled.
        vm.channels.chooseTemplate(reminder)
        assertEquals(listOf("Ada", " Thursday "), vm.via().sheet.values)
    }

    @Test
    fun `a template send is single-flight, goes once, and re-reads the thread`() = runTest(dispatcher) {
        val vm = closedThread()
        advanceUntilIdle()
        vm.channels.openTemplates()
        vm.channels.chooseTemplate(reminder)
        vm.channels.setTemplateValue(0, "Ada")
        vm.channels.setTemplateValue(1, "Thursday")
        val reads = api.timelineCursors.size

        vm.channels.sendTemplate()
        vm.channels.sendTemplate()
        assertTrue(vm.via().sheet.sending)
        // Nothing moves while it is on its way.
        vm.channels.dismissTemplates()
        vm.channels.setTemplateValue(0, "Bob")
        vm.channels.chooseTemplate(reminder.copy(id = "other"))
        vm.channels.choose(whatsApp)
        assertTrue(vm.via().sheet.open)
        advanceUntilIdle()

        val sent = api.sends.single()
        assertEquals("Hi Ada, see you Thursday.", sent.body)
        assertEquals("wa_tpl_reminder", sent.templateId)
        assertEquals(listOf("Ada", "Thursday"), sent.templateVariables)
        assertEquals("wa_sender_1", sent.whatsappSenderId)
        assertEquals(TemplateSheetState(), vm.via().sheet)
        assertEquals("the thread is read again", reads + 1, api.timelineCursors.size)
    }

    @Test
    fun `a template with no variables is complete as soon as it is chosen, and sends an empty list`() =
        runTest(dispatcher) {
            val vm = closedThread()
            advanceUntilIdle()
            vm.channels.chooseTemplate(reminder.copy(id = "hello", body = "Hello", variableCount = 0))

            vm.channels.sendTemplate()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), api.sends.single().templateVariables)
        }

    @Test
    fun `a refused template keeps what was filled and says why`() = runTest(dispatcher) {
        api.sendResult =
            ApiResult.HttpFailure(status = 400, message = "Not sendable", code = "whatsapp_template_invalid")
        val vm = closedThread()
        advanceUntilIdle()
        vm.channels.openTemplates()
        vm.channels.chooseTemplate(reminder)
        vm.channels.setTemplateValue(0, "Ada")
        vm.channels.setTemplateValue(1, "Thursday")

        vm.channels.sendTemplate()
        advanceUntilIdle()

        val sheet = vm.via().sheet
        assertFalse(sheet.sending)
        assertNotNull(sheet.failure)
        assertEquals(listOf("Ada", "Thursday"), sheet.values)
        vm.channels.dismissTemplates()
        assertFalse(vm.via().sheet.open)
        assertNull(vm.via().sheet.failure)
        assertEquals("closing keeps the values", listOf("Ada", "Thursday"), vm.via().sheet.values)
    }

    @Test
    fun `a template needs a chosen template, a closed window's sender and a reply choice`() = runTest(dispatcher) {
        val noTemplate = closedThread()
        advanceUntilIdle()
        noTemplate.channels.sendTemplate()

        whatsAppApi.windowResult = closed(senderId = null)
        val noSender = viewModel(whatsApp)
        advanceUntilIdle()
        noSender.channels.chooseTemplate(reminder.copy(variableCount = 0))
        noSender.channels.sendTemplate()

        val nobody = viewModel(role = WorkspaceRole.VIEWER)
        advanceUntilIdle()
        nobody.channels.chooseTemplate(reminder.copy(variableCount = 0))
        nobody.channels.sendTemplate()
        advanceUntilIdle()

        assertTrue(api.sends.isEmpty())
    }

    @Test
    fun `a template with a null variable count opens with no fields`() = runTest(dispatcher) {
        val vm = closedThread()
        advanceUntilIdle()
        vm.channels.chooseTemplate(reminder.copy(variableCount = null))
        assertEquals(emptyList<String>(), vm.via().sheet.values)
        vm.channels.windowClosed()
        assertTrue("a closed window ignores a second refusal", vm.via().whatsApp is WhatsAppWindowState.Closed)
    }

    @Test
    fun `the empty states read as nothing chosen and nothing to preview`() {
        assertEquals("", TemplateSheetState().preview)
        assertFalse(TemplateSheetState().complete)
        assertEquals(ReplyChannelState(choices = emptyList()), ReplyChannelState())
    }

    @Test
    fun `a channel can be chosen while the thread is still loading`() = runTest(dispatcher) {
        val vm = viewModel(sms, email)
        vm.channels.choose(email)
        assertEquals(email, vm.via().selected)
    }

    @Test
    fun `no choice, an open window or an index out of range does nothing`() = runTest(dispatcher) {
        val nobody = viewModel(role = WorkspaceRole.VIEWER)
        val open = viewModel(whatsApp)
        advanceUntilIdle()

        nobody.channels.windowClosed()
        open.channels.chooseTemplate(reminder)
        open.channels.setTemplateValue(-1, "x")
        open.channels.setTemplateValue(0, "Ada")
        open.channels.setTemplateValue(1, "Thursday")
        open.channels.sendTemplate()
        advanceUntilIdle()

        assertEquals(WhatsAppWindowState.NotWhatsApp, nobody.via().whatsApp)
        assertEquals(listOf("Ada", "Thursday"), open.via().sheet.values)
        assertTrue("an open window sends no template", api.sends.isEmpty())
    }
}
