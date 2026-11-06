package com.distronode.districtai.ui.inbox

import androidx.lifecycle.SavedStateHandle
import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.WhatsAppRepository
import com.distronode.districtai.core.model.CHANNEL_INSTAGRAM
import com.distronode.districtai.core.model.CHANNEL_MESSENGER
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.MetaWindow
import com.distronode.districtai.core.model.MetaWindowResponse
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeWhatsAppApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Messenger's and Instagram's reply window in the composer, as the web reads it (`useMetaWindow`):
 * open, open for a human agent's reply only (the AI draft is off), closed (nothing may go), or
 * unknown (nothing is blocked). No countdown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadMetaWindowTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val messenger = ReplyTarget("fb:5550101", CHANNEL_MESSENGER)
    private val instagram = ReplyTarget("ig:5550102", CHANNEL_INSTAGRAM)
    private val sms = ReplyTarget("+14165550142", CHANNEL_SMS)

    private val api = FakeDistrictApi()
    private val whatsAppApi = FakeWhatsAppApi()

    private fun window(state: String) =
        ApiResult.Success(MetaWindowResponse(success = true, window = MetaWindow(state = state)))

    private fun TestScope.viewModel(vararg choices: ReplyTarget = arrayOf(messenger)) = ThreadViewModel(
        services = ThreadServices(InboxRepository(api), ComposerRepository(api), WhatsAppRepository(whatsAppApi)),
        target = ThreadTarget(
            workspaceId = "ws-1",
            contactId = null,
            address = choices.first().to,
            threadKey = "addr:${choices.first().to}",
            replyTarget = choices.first(),
            moreReplies = choices.drop(1),
        ),
        role = WorkspaceRole.CLIENT,
        attachmentReader = AttachmentReader { null },
        savedState = SavedStateHandle(),
    ).also { advanceUntilIdle() }

    private fun ThreadViewModel.meta() = channels.state.value.meta

    @Test
    fun `a Messenger thread reads its window for that customer, and an open one blocks nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()

            assertEquals(listOf(Triple("ws-1", CHANNEL_MESSENGER, "fb:5550101")), whatsAppApi.metaWindowReads)
            assertEquals(MetaWindowState.OPEN, vm.meta())
            assertTrue(vm.channels.state.value.allowsFreeText)
            assertTrue(vm.channels.state.value.allowsDraft)
        }

    @Test
    fun `the human agent window lets a person reply, but never an AI draft`() = runTest(dispatcher) {
        whatsAppApi.metaWindowResult = window("human_agent")
        val vm = viewModel()

        assertEquals(MetaWindowState.HUMAN_AGENT, vm.meta())
        assertTrue(vm.channels.state.value.allowsFreeText)
        assertFalse(vm.channels.state.value.allowsDraft)
        vm.generateDraft()
        advanceUntilIdle()
        assertTrue("no AI draft is spent", api.generations.isEmpty())
        vm.send("Thursday at 10 works.")
        advanceUntilIdle()
        assertEquals(CHANNEL_MESSENGER, api.sends.single().channel)
    }

    @Test
    fun `a closed window sends nothing and drafts nothing`() = runTest(dispatcher) {
        whatsAppApi.metaWindowResult = window("closed")
        val vm = viewModel()

        assertEquals(MetaWindowState.CLOSED, vm.meta())
        assertFalse(vm.channels.state.value.allowsFreeText)
        vm.send("Hello?")
        vm.generateDraft()
        advanceUntilIdle()
        assertTrue(api.sends.isEmpty())
        assertTrue(api.generations.isEmpty())
    }

    @Test
    fun `a read that failed, a false envelope, or a state this build does not know blocks nothing`() =
        runTest(dispatcher) {
            listOf(
                ApiResult.NetworkFailure(IOException("offline")),
                ApiResult.Success(MetaWindowResponse(success = false)),
                window("a_state_from_a_newer_server"),
            ).forEach { answer ->
                whatsAppApi.metaWindowResult = answer
                val vm = viewModel()

                assertEquals("$answer", MetaWindowState.UNKNOWN, vm.meta())
                assertTrue(vm.channels.state.value.allowsFreeText)
                assertTrue(vm.channels.state.value.allowsDraft)
            }
        }

    @Test
    fun `a send refused for a closed window closes it`() = runTest(dispatcher) {
        val vm = viewModel()
        api.sendResult = ApiResult.HttpFailure(status = 409, message = "closed", code = "messenger_window_closed")

        vm.send("Are you still there?")
        advanceUntilIdle()

        assertEquals(MetaWindowState.CLOSED, vm.meta())
        assertEquals(1, whatsAppApi.metaWindowReads.size)
    }

    @Test
    fun `another refusal leaves the window as it was`() = runTest(dispatcher) {
        val vm = viewModel()
        api.sendResult = ApiResult.HttpFailure(status = 400, message = "bad", code = "invalid_request")

        vm.send("Hi")
        advanceUntilIdle()

        assertEquals(MetaWindowState.OPEN, vm.meta())
    }

    @Test
    fun `the window is read again after a reply goes`() = runTest(dispatcher) {
        val vm = viewModel()
        whatsAppApi.metaWindowResult = window("human_agent")

        vm.send("Booked for Thursday.")
        advanceUntilIdle()

        assertEquals(2, whatsAppApi.metaWindowReads.size)
        assertEquals(MetaWindowState.HUMAN_AGENT, vm.meta())
    }

    @Test
    fun `a re-check keeps what is shown until the answer, and leaves a read already out alone`() =
        runTest(dispatcher) {
            val vm = viewModel()
            val gate = CompletableDeferred<Unit>()
            whatsAppApi.metaWindowGate = gate
            whatsAppApi.metaWindowResult = window("closed")

            vm.channels.recheckWindow()
            runCurrent()
            assertEquals("no flash of checking", MetaWindowState.OPEN, vm.meta())
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(MetaWindowState.CLOSED, vm.meta())

            // A first read still out: the re-check sends nothing more.
            val second = CompletableDeferred<Unit>()
            whatsAppApi.metaWindowGate = second
            val checking = viewModel(instagram)
            val reads = whatsAppApi.metaWindowReads.size
            assertEquals(MetaWindowState.CHECKING, checking.meta())
            checking.channels.recheckWindow()
            runCurrent()
            assertEquals(reads, whatsAppApi.metaWindowReads.size)
            second.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `a window answer for another customer never paints the one now chosen`() = runTest(dispatcher) {
        // Messenger's read is slow; the operator switches to Instagram before it answers.
        val gate = CompletableDeferred<Unit>()
        whatsAppApi.metaWindowGate = gate
        whatsAppApi.metaWindowResult = window("closed")
        val vm = viewModel(messenger, instagram)
        assertEquals(MetaWindowState.CHECKING, vm.meta())

        vm.channels.choose(instagram)
        whatsAppApi.metaWindowGate = null
        whatsAppApi.metaWindowResult = window("open")
        advanceUntilIdle()
        whatsAppApi.metaWindowResult = window("closed")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(instagram, vm.channels.state.value.selected)
        assertEquals("Instagram's own answer, not Messenger's", MetaWindowState.OPEN, vm.meta())
        assertTrue(vm.channels.state.value.allowsFreeText)
    }

    @Test
    fun `a channel other than Messenger or Instagram reads no Meta window`() = runTest(dispatcher) {
        val vm = viewModel(sms, messenger)

        assertEquals(MetaWindowState.NOT_META, vm.meta())
        assertTrue(whatsAppApi.metaWindowReads.isEmpty())
        vm.channels.choose(messenger)
        advanceUntilIdle()
        assertEquals(MetaWindowState.OPEN, vm.meta())
        vm.channels.choose(sms)
        assertEquals(MetaWindowState.NOT_META, vm.meta())
    }
}
