package com.distronode.districtai.ui.inbox

import androidx.lifecycle.SavedStateHandle
import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.data.WhatsAppRepository
import com.distronode.districtai.core.model.CHANNEL_WHATSAPP
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.ConversationsResponse
import com.distronode.districtai.core.model.ReplyOption
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.TimelineResponse
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.WhatsAppTemplatesResponse
import com.distronode.districtai.core.model.WhatsAppWindow
import com.distronode.districtai.core.model.WhatsAppWindowResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeInboxExtrasApi
import com.distronode.districtai.core.network.testing.FakeWhatsAppApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * One rule across the Inbox, the thread and the composer: A FAILED RE-READ KEEPS WHAT IS SHOWN, and
 * any limit it implies. Only a successful answer, or the server refusing the session or the access,
 * changes it. (The Meta window, the thread owner, the template list and the Live calls list hold
 * the same rule in their own tests.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedRereadTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val offline = ApiResult.NetworkFailure(IOException("offline"))

    // ── The Inbox list: owners and reply options ─────────────────────────────

    private val ada = ConversationSummary(
        threadKey = "contact:c1",
        counterpart = "+14165550142",
        contactId = "c1",
        contactName = "Ada",
        assigneeEmail = "teammate@example.com",
        replyOptions = listOf(ReplyOption("whatsapp", "+14165550142")),
    )

    private val inboxApi = FakeDistrictApi().apply {
        conversationsResult = ApiResult.Success(ConversationsResponse(success = true, conversations = listOf(ada)))
    }

    private fun TestScope.inbox() = InboxViewModel(
        InboxRepository(inboxApi),
        ComposerRepository(inboxApi),
        MessageSearchRepository(FakeInboxExtrasApi()),
        "ws-1",
        WorkspaceRole.CLIENT,
        searchDebounceMillis = 0L,
    ).also { advanceUntilIdle() }

    @Test
    fun `an Inbox refresh that fails keeps the list, its owners and reply options, with a notice`() =
        runTest(dispatcher) {
            val vm = inbox()
            inboxApi.conversationsResult = offline

            vm.load(refreshing = true)
            advanceUntilIdle()

            val kept = vm.state.value as InboxUiState.Content
            assertEquals(listOf(ada), kept.conversations)
            assertFalse(kept.refreshing)
            assertNotNull(kept.refreshFailure)

            inboxApi.conversationsResult = ApiResult.Success(
                ConversationsResponse(success = true, conversations = listOf(ada)),
            )
            vm.load(refreshing = true)
            advanceUntilIdle()
            assertNull("the next good read clears it", (vm.state.value as InboxUiState.Content).refreshFailure)
        }

    @Test
    fun `an Inbox refresh the server refuses takes the list away`() = runTest(dispatcher) {
        val refusals = listOf(ApiResult.Unauthorized(null), ApiResult.Forbidden("Not a member of this workspace."))
        refusals.forEach {
            inboxApi.conversationsResult = ApiResult.Success(
                ConversationsResponse(success = true, conversations = listOf(ada)),
            )
            val vm = inbox()
            inboxApi.conversationsResult = it

            vm.load(refreshing = true)
            advanceUntilIdle()

            assertTrue("$it", vm.state.value is InboxUiState.Failed)
        }
    }

    // ── The thread's timeline ────────────────────────────────────────────────

    private val whatsApp = ReplyTarget("+14165550142", CHANNEL_WHATSAPP)
    private val event = TimelineEvent(id = "m1", type = "sms", timestamp = "2026-08-15T09:00:00.000Z", body = "hi")
    private val threadApi = FakeDistrictApi().apply {
        timelineResult = ApiResult.Success(TimelineResponse(success = true, timeline = listOf(event)))
    }
    private val whatsAppApi = FakeWhatsAppApi()

    private fun TestScope.thread(target: ReplyTarget = whatsApp) = ThreadViewModel(
        services = ThreadServices(
            InboxRepository(threadApi),
            ComposerRepository(threadApi),
            WhatsAppRepository(whatsAppApi),
        ),
        target = ThreadTarget(
            workspaceId = "ws-1",
            contactId = "c1",
            address = null,
            threadKey = "contact:c1",
            replyTarget = target,
            moreReplies = emptyList(),
        ),
        role = WorkspaceRole.CLIENT,
        attachmentReader = AttachmentReader { null },
        savedState = SavedStateHandle(),
    ).also { runCurrent() }

    @Test
    fun `a thread re-read after a reply keeps the conversation, never a spinner, and Send is free again`() =
        runTest(dispatcher) {
            val vm = thread()
            threadApi.timelineResult = offline

            vm.send("Thursday works.")
            runCurrent()
            assertTrue("no spinner while it re-reads", vm.state.value is ThreadUiState.Content)
            advanceUntilIdle()

            val kept = vm.state.value as ThreadUiState.Content
            assertEquals(listOf(event), kept.events)
            assertNotNull(kept.refreshFailure)
            assertFalse("the reply went: Send is not stuck", kept.sending)
        }

    @Test
    fun `a thread re-read the server refuses takes the conversation away`() = runTest(dispatcher) {
        val vm = thread()
        threadApi.timelineResult = ApiResult.Forbidden("No access.")

        vm.load()
        advanceUntilIdle()

        assertTrue(vm.state.value is ThreadUiState.Failed)
    }

    // ── WhatsApp: the sender, and the window at its expiry ───────────────────

    @Test
    fun `a WhatsApp re-check that fails keeps the sender the open window named`() = runTest(dispatcher) {
        val vm = thread()
        assertEquals(WhatsAppWindowState.Open("wa_sender_1"), vm.channels.state.value.whatsApp)
        whatsAppApi.windowResult = offline

        vm.channels.recheckWindow()
        advanceUntilIdle()
        vm.send("Thursday works.")
        advanceUntilIdle()

        assertEquals(WhatsAppWindowState.Open("wa_sender_1"), vm.channels.state.value.whatsApp)
        val sent = threadApi.sends.single()
        assertEquals("the sender it named, not the route's default", "wa_sender_1", sent.whatsappSenderId)
    }

    @Test
    fun `a read at the window's own expiry that fails reads as closed, with that sender's templates`() =
        runTest(dispatcher) {
            val reminder = WhatsAppTemplate(id = "t1", status = "approved", body = "Hi {{1}}", variableCount = 1)
            whatsAppApi.templatesResult = ApiResult.Success(
                WhatsAppTemplatesResponse(success = true, templates = listOf(reminder)),
            )
            whatsAppApi.windowResult = ApiResult.Success(
                WhatsAppWindowResponse(
                    success = true,
                    window = WhatsAppWindow(
                        open = true,
                        expiresAt = Instant.ofEpochMilli(System.currentTimeMillis() + MINUTE).toString(),
                    ),
                    senderId = "wa_sender_1",
                ),
            )
            val vm = thread()
            assertEquals(WhatsAppWindowState.Open("wa_sender_1"), vm.channels.state.value.whatsApp)
            whatsAppApi.windowResult = offline

            advanceTimeBy(2 * MINUTE)
            runCurrent()

            assertEquals(
                WhatsAppWindowState.Closed("wa_sender_1", WhatsAppTemplatesState.Ready(listOf(reminder))),
                vm.channels.state.value.whatsApp,
            )
        }

    private companion object {
        const val MINUTE = 60_000L
    }
}
