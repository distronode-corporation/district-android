package com.distronode.districtai.ui.inbox

import androidx.lifecycle.SavedStateHandle
import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.WhatsAppRepository
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.CHANNEL_WHATSAPP
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.WhatsAppTemplatesResponse
import com.distronode.districtai.core.model.WhatsAppWindow
import com.distronode.districtai.core.model.WhatsAppWindowResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeWhatsAppApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * WhatsApp's 24-hour window over time: its `expiresAt`, the re-read when it passes, and the
 * re-check when the screen comes back (review L2 of PR #117).
 *
 * ⚠️ NEVER `advanceUntilIdle` WITH A FUTURE `expiresAt` HERE: the re-read at expiry is a delay, and
 * these tests move virtual time by hand ([advanceTimeBy]) so each read is counted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadWhatsAppWindowTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val whatsApp = ReplyTarget("+14165550142", CHANNEL_WHATSAPP)
    private val sms = ReplyTarget("+14165550142", CHANNEL_SMS)
    private val reminder = WhatsAppTemplate(
        id = "wa_tpl_reminder",
        name = "appointment_reminder",
        language = "en_US",
        status = "approved",
        body = "Hi {{1}}.",
        variableCount = 1,
    )

    private val api = FakeDistrictApi()
    private val whatsAppApi = FakeWhatsAppApi().apply {
        templatesResult = ApiResult.Success(WhatsAppTemplatesResponse(success = true, templates = listOf(reminder)))
    }

    private fun window(open: Boolean, expiresAt: String? = null, senderId: String? = "wa_sender_1") =
        ApiResult.Success(
            WhatsAppWindowResponse(success = true, window = WhatsAppWindow(open, expiresAt), senderId = senderId),
        )

    private fun inMillis(millis: Long): String = Instant.ofEpochMilli(System.currentTimeMillis() + millis).toString()

    private fun TestScope.viewModel(vararg choices: ReplyTarget = arrayOf(whatsApp)) = ThreadViewModel(
        services = ThreadServices(InboxRepository(api), ComposerRepository(api), WhatsAppRepository(whatsAppApi)),
        target = ThreadTarget(
            workspaceId = "ws-1",
            contactId = "c1",
            address = null,
            threadKey = "contact:c1",
            replyTarget = choices.first(),
            moreReplies = choices.drop(1),
        ),
        role = WorkspaceRole.CLIENT,
        attachmentReader = AttachmentReader { null },
        savedState = SavedStateHandle(),
    ).also { runCurrent() }

    private fun ThreadViewModel.window() = channels.state.value.whatsApp

    private val closedWithTemplates =
        WhatsAppWindowState.Closed("wa_sender_1", WhatsAppTemplatesState.Ready(listOf(reminder)))

    @Test
    fun `an open window whose expiresAt has passed is closed`() = runTest(dispatcher) {
        whatsAppApi.windowResult = window(open = true, expiresAt = "2026-08-16T14:25:00.000Z")
        val vm = viewModel()

        assertEquals(closedWithTemplates, vm.window())
    }

    @Test
    fun `an open window is read again when it passes, and closes if the server agrees`() = runTest(dispatcher) {
        whatsAppApi.windowResult = window(open = true, expiresAt = inMillis(HOUR))
        val vm = viewModel()
        assertEquals(WhatsAppWindowState.Open("wa_sender_1"), vm.window())

        advanceTimeBy(HOUR - MINUTE)
        assertEquals("nothing before it passes", 1, whatsAppApi.windowReads.size)
        whatsAppApi.windowResult = window(open = false)
        advanceTimeBy(2 * MINUTE)
        runCurrent()

        assertEquals(2, whatsAppApi.windowReads.size)
        assertEquals(closedWithTemplates, vm.window())
    }

    @Test
    fun `a re-read at expiry that names the same closing time is closed, and reads no more`() = runTest(dispatcher) {
        val closesAt = inMillis(MINUTE)
        whatsAppApi.windowResult = window(open = true, expiresAt = closesAt)
        val vm = viewModel()

        advanceTimeBy(2 * MINUTE)
        runCurrent()
        advanceTimeBy(HOUR)
        runCurrent()

        assertEquals(2, whatsAppApi.windowReads.size)
        assertEquals(closedWithTemplates, vm.window())
    }

    @Test
    fun `a window the customer re-opened is waited on again`() = runTest(dispatcher) {
        whatsAppApi.windowResult = window(open = true, expiresAt = inMillis(MINUTE))
        val vm = viewModel()
        whatsAppApi.windowResult = window(open = true, expiresAt = inMillis(HOUR))

        advanceTimeBy(2 * MINUTE)
        runCurrent()

        assertEquals(2, whatsAppApi.windowReads.size)
        assertEquals(WhatsAppWindowState.Open("wa_sender_1"), vm.window())
    }

    @Test
    fun `an unreadable expiresAt leaves the window open with nothing scheduled`() = runTest(dispatcher) {
        whatsAppApi.windowResult = window(open = true, expiresAt = "tomorrow")
        val vm = viewModel()

        advanceTimeBy(DAY)
        runCurrent()

        assertEquals(WhatsAppWindowState.Open("wa_sender_1"), vm.window())
        assertEquals(1, whatsAppApi.windowReads.size)
    }

    @Test
    fun `leaving WhatsApp cancels the re-read at expiry`() = runTest(dispatcher) {
        whatsAppApi.windowResult = window(open = true, expiresAt = inMillis(MINUTE))
        val vm = viewModel(whatsApp, sms)

        vm.channels.choose(sms)
        advanceTimeBy(HOUR)
        runCurrent()

        assertEquals(1, whatsAppApi.windowReads.size)
        assertEquals(WhatsAppWindowState.NotWhatsApp, vm.window())
    }

    @Test
    fun `a re-check finds a closed window opened, and the template sheet closes`() = runTest(dispatcher) {
        whatsAppApi.windowResult = window(open = false)
        val vm = viewModel()
        vm.channels.openTemplates()
        vm.channels.chooseTemplate(reminder)
        assertTrue(vm.channels.state.value.sheet.open)

        whatsAppApi.windowResult = window(open = true)
        vm.channels.recheckWindow()
        runCurrent()

        assertEquals(WhatsAppWindowState.Open("wa_sender_1"), vm.window())
        assertEquals(TemplateSheetState(), vm.channels.state.value.sheet)
    }

    @Test
    fun `a re-check of a window still closed keeps the sheet and reads the templates no more`() =
        runTest(dispatcher) {
            whatsAppApi.windowResult = window(open = false)
            val vm = viewModel()
            vm.channels.openTemplates()
            vm.channels.chooseTemplate(reminder)

            vm.channels.recheckWindow()
            runCurrent()

            assertEquals(2, whatsAppApi.windowReads.size)
            assertEquals(1, whatsAppApi.templateReads.size)
            assertEquals(closedWithTemplates, vm.window())
            assertEquals(reminder, vm.channels.state.value.sheet.chosen)
        }

    @Test
    fun `a re-check finds an open window closed`() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(WhatsAppWindowState.Open("wa_sender_1"), vm.window())

        whatsAppApi.windowResult = window(open = false)
        vm.channels.recheckWindow()
        runCurrent()

        assertEquals(closedWithTemplates, vm.window())
    }

    @Test
    fun `a re-check leaves a read already out, a template on its way, and another channel alone`() =
        runTest(dispatcher) {
            // A first read still out.
            val gate = CompletableDeferred<Unit>()
            whatsAppApi.windowGate = gate
            val checking = viewModel()
            checking.channels.recheckWindow()
            assertEquals(1, whatsAppApi.windowReads.size)
            gate.complete(Unit)
            runCurrent()
            whatsAppApi.windowGate = null

            // The closed window's templates still loading.
            val templatesGate = CompletableDeferred<Unit>()
            whatsAppApi.templatesGate = templatesGate
            whatsAppApi.windowResult = window(open = false)
            val loading = viewModel()
            loading.channels.recheckWindow()
            assertEquals(2, whatsAppApi.windowReads.size)
            templatesGate.complete(Unit)
            runCurrent()
            whatsAppApi.templatesGate = null

            // A template on its way (sending is set before the send is dispatched).
            loading.channels.chooseTemplate(reminder)
            loading.channels.setTemplateValue(0, "Ada")
            loading.channels.sendTemplate()
            assertTrue(loading.channels.state.value.sheet.sending)
            loading.channels.recheckWindow()
            runCurrent()
            assertEquals(2, whatsAppApi.windowReads.size)

            // Not WhatsApp at all.
            val onSms = viewModel(sms)
            onSms.channels.recheckWindow()
            runCurrent()
            assertEquals(2, whatsAppApi.windowReads.size)
            assertEquals(WhatsAppWindowState.NotWhatsApp, onSms.window())
        }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
