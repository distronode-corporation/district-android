package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.model.ConversationLastMessage
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.ConversationsResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.DraftListResponse
import com.distronode.districtai.core.model.MessageDraft
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.TestInboxExtrasApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ⛔ THE ASSERTION THAT MATTERS MOST HERE IS `a viewer never fires a mark-read`. `messages/mark-read`
 * excludes viewer server-side, so firing it would guarantee a 403 on a request the user did not ask
 * for — every time they open a thread. The role gate is an affordance, not a security control, but a
 * client that knowingly fires a doomed request is just generating noise in someone's error budget.
 *
 * ⚠️ The optimistic-update assertions are the other half: the badge must fall the moment a thread is
 * opened, because waiting on the write would make opening a conversation feel slower to serve
 * bookkeeping nobody is watching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // ⚠️ The ViewModel loads in `init`, so the main dispatcher has to be replaced BEFORE one is
        // constructed — otherwise viewModelScope posts to the real Android main looper, which does not
        // exist here.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun summary(
        threadKey: String = "contact:c1",
        contactId: String? = "c1",
        counterpart: String = "+14165550142",
        unread: Int = 0,
    ) = ConversationSummary(
        threadKey = threadKey,
        counterpart = counterpart,
        contactId = contactId,
        contactName = "Ada",
        unreadCount = unread,
        lastMessage = ConversationLastMessage(body = "hi", direction = "inbound"),
    )

    /** ⚠️ Per test, so a search assertion can set a result and then read what was requested. */
    private val searchApi = TestInboxExtrasApi()

    private fun viewModel(
        api: TestDistrictApi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = InboxViewModel(
        InboxRepository(api),
        ComposerRepository(api),
        MessageSearchRepository(searchApi),
        "ws-1",
        role,
        // ⚠️ NO DEBOUNCE IN TESTS. The delay is a real coroutine delay; leaving it at 300ms would
        // make every search assertion depend on advancing a clock that has nothing to do with the
        // behaviour under test.
        searchDebounceMillis = 0L,
    )

    private fun apiWith(vararg threads: ConversationSummary) = TestDistrictApi().apply {
        conversationsResult = ApiResult.Success(
            ConversationsResponse(
                success = true,
                conversations = threads.toList(),
                scanned = 10,
                scanLimit = 500,
            ),
        )
    }

    @Test
    fun `the unread badge is the sum of the threads, not a second request`() = runTest(dispatcher) {
        // ⚠️ Derived from the list already in hand. A second round trip would cost a request to learn
        // something present in the response — and could disagree with the list the user is looking at.
        val vm = viewModel(apiWith(summary(unread = 2), summary(threadKey = "addr:x", contactId = null, unread = 3)))
        advanceUntilIdle()

        assertEquals(5, vm.unread.value)
    }

    @Test
    fun `opening a thread clears its badge immediately`() = runTest(dispatcher) {
        val vm = viewModel(apiWith(summary(unread = 4)))
        advanceUntilIdle()
        assertEquals(4, vm.unread.value)

        vm.markThreadRead("c1", "+14165550142")

        // ⛔ BEFORE the write completes. The state is updated synchronously; advancing the dispatcher
        // is only needed for the request itself.
        assertEquals(0, vm.unread.value)
        val state = vm.state.value as InboxUiState.Content
        assertEquals(0, state.conversations.single().unreadCount)
    }

    @Test
    fun `a thread with no contact row is matched on its address`() = runTest(dispatcher) {
        // ⚠️ A thread whose counterpart never resolved has only an address, so matching on contactId
        // alone would silently fail to clear it.
        val vm = viewModel(apiWith(summary(threadKey = "addr:+1416", contactId = null, unread = 2)))
        advanceUntilIdle()

        vm.markThreadRead(null, "+14165550142")

        assertEquals(0, vm.unread.value)
    }

    @Test
    fun `marking one thread read leaves the others alone`() = runTest(dispatcher) {
        val vm = viewModel(
            apiWith(
                summary(threadKey = "contact:c1", contactId = "c1", unread = 2),
                summary(threadKey = "contact:c2", contactId = "c2", unread = 5),
            ),
        )
        advanceUntilIdle()

        vm.markThreadRead("c1", "+14165550142")

        assertEquals(5, vm.unread.value)
    }

    @Test
    fun `a viewer never fires a mark-read`() = runTest(dispatcher) {
        // ⛔ See the class doc: mark-read excludes viewer server-side.
        val api = apiWith(summary(unread = 3))
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(vm.canReply)
        vm.markThreadRead("c1", "+14165550142")
        advanceUntilIdle()

        // The badge is untouched too — nothing was claimed to have happened.
        assertEquals(3, vm.unread.value)
    }

    @Test
    fun `a partial list reaches the state rather than being hidden`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            conversationsResult = ApiResult.Success(
                ConversationsResponse(
                    success = true,
                    conversations = listOf(summary()),
                    scanned = 500,
                    scanLimit = 500,
                ),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue((vm.state.value as InboxUiState.Content).partial)
    }

    @Test
    fun `a refresh keeps the current list on screen`() = runTest(dispatcher) {
        // ⚠️ Replacing a populated list with a spinner on pull-to-refresh throws away what the user is
        // looking at to show them less.
        val vm = viewModel(apiWith(summary()))
        advanceUntilIdle()

        vm.load(refreshing = true)

        val state = vm.state.value
        assertTrue(state is InboxUiState.Content)
        assertTrue((state as InboxUiState.Content).refreshing)
        assertEquals(1, state.conversations.size)
    }

    @Test
    fun `a failure becomes a failed state, not an empty inbox`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            conversationsResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value is InboxUiState.Failed)
    }

    // ── The drafts badge (Task A1) ──────────────────────────────────────────

    /**
     * ⚠️ ONE REQUEST FOR THE WHOLE LIST, not one per row. The route answers every draft this author
     * has open in a single indexed query; asking per thread would be a request per visible
     * conversation for a decorative chip.
     */
    @Test
    fun `the badge read maps drafts onto their threads`() = runTest(dispatcher) {
        val api = apiWith(summary(threadKey = "contact:c1"), summary(threadKey = "contact:c2")).apply {
            draftsResult = ApiResult.Success(
                DraftListResponse(
                    success = true,
                    drafts = listOf(MessageDraft(threadKey = "contact:c1", body = "half a sentence")),
                ),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val content = vm.state.value as InboxUiState.Content
        assertEquals(setOf("contact:c1"), content.draftThreadKeys)
    }

    /**
     * ⛔ FAIL-SOFT. An Inbox that refused to render because the drafts endpoint was down would be
     * strictly worse than an Inbox with no chips — the conversations are the screen, the badge is
     * decoration.
     */
    @Test
    fun `a failed badge read leaves the conversations on screen`() = runTest(dispatcher) {
        val api = apiWith(summary()).apply {
            draftsResult = ApiResult.HttpFailure(status = 500, message = "boom")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val content = vm.state.value as InboxUiState.Content
        assertEquals(1, content.conversations.size)
        assertTrue(content.draftThreadKeys.isEmpty())
    }

    /**
     * ⚠️ A VIEWER IS NOT ASKED. The drafts route excludes `viewer` server-side, so calling it for
     * one would be a known 403 — the same rule that hides their reply box.
     */
    @Test
    fun `a viewer never asks for drafts`() = runTest(dispatcher) {
        val api = apiWith(summary())
        viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertTrue(api.draftReads.isEmpty())
    }
}
