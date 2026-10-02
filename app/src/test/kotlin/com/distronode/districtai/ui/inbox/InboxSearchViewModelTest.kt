package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.model.ConversationLastMessage
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.ConversationsResponse
import com.distronode.districtai.core.model.MessageSearchHit
import com.distronode.districtai.core.model.MessageSearchResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.TestInboxExtrasApi
import java.io.IOException
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Full-content search across every message in the workspace.
 *
 * ⛔ NOT THE CONVERSATION LIST FILTERED, AND THAT IS THE WHOLE REASON IT EXISTS. The list holds a
 * bounded window of recent messages grouped into threads; the route queries every `Message` row.
 * A client that filtered the loaded rows instead would silently answer "no matches" for messages
 * the workspace definitely has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val searchApi = TestInboxExtrasApi()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun hit(id: String, threadKey: String = "contact:c1") = MessageSearchHit(
        messageId = id,
        key = "phone:+14165550142",
        threadKey = threadKey,
        counterpart = "+14165550142",
        kind = "phone",
        contactId = "c1",
        contactName = "Ada",
        body = "Our refund policy is 30 days.",
        direction = "inbound",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private fun conversation(threadKey: String = "contact:c1") = ConversationSummary(
        threadKey = threadKey,
        counterpart = "+14165550142",
        contactId = "c1",
        contactName = "Ada",
        lastMessage = ConversationLastMessage(body = "hi", direction = "inbound"),
    )

    private fun api(vararg threads: ConversationSummary) = TestDistrictApi().apply {
        conversationsResult = ApiResult.Success(
            ConversationsResponse(
                success = true,
                conversations = threads.toList(),
                scanned = 1,
                scanLimit = 500,
            ),
        )
    }

    private fun viewModel(api: TestDistrictApi = api()) = InboxViewModel(
        InboxRepository(api),
        ComposerRepository(api),
        MessageSearchRepository(searchApi),
        "ws-1",
        WorkspaceRole.CLIENT,
        searchDebounceMillis = 0L,
    )

    @Test
    fun `a query below the floor is not a search and sends nothing`() = runTest {
        // ⛔ MEASURED ON THE TRIMMED VALUE, because the server trims before it measures. A client
        // that measured the untrimmed one would send requests the route answers empty and then
        // render "no matches" for them.
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSearchQueryChanged(" a ")
        advanceUntilIdle()

        assertFalse(vm.searchState.value.active)
        assertTrue(searchApi.searches.isEmpty())
    }

    @Test
    fun `a real query reaches the route and its hits reach the state`() = runTest {
        searchApi.searchResult = ApiResult.Success(
            MessageSearchResponse(success = true, results = listOf(hit("m1")), limit = 30),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSearchQueryChanged("refund")
        advanceUntilIdle()

        assertEquals(listOf("refund"), searchApi.searches)
        assertEquals(listOf("m1"), vm.searchState.value.hits.map { it.messageId })
        assertFalse(vm.searchState.value.running)
    }

    @Test
    fun `a full page is reported as truncated`() = runTest {
        // ⛔ A FULL PAGE MEANS OLDER MATCHES EXIST AND ARE NOT SHOWN. There is no offset to page on,
        // so drawing it as a complete answer is the same class of mistake as reporting a degraded
        // region's absence as "you have no workspaces".
        searchApi.searchResult = ApiResult.Success(
            MessageSearchResponse(success = true, results = listOf(hit("m1")), limit = 1),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSearchQueryChanged("refund")
        advanceUntilIdle()

        assertTrue(vm.searchState.value.truncated)
    }

    @Test
    fun `a failed search is reported rather than rendered as no matches`() = runTest {
        // ⛔ "No matches" AND "I could not look" ARE DIFFERENT ANSWERS, and the first is the one
        // somebody acts on.
        searchApi.searchResult = ApiResult.NetworkFailure(IOException("down"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSearchQueryChanged("refund")
        advanceUntilIdle()

        assertNotNull(vm.searchState.value.failure)
        assertTrue(vm.searchState.value.hits.isEmpty())
    }

    @Test
    fun `emptying the field drops the results`() = runTest {
        // ⚠️ The screen clears search by sending "" through the same callback as typing.
        searchApi.searchResult = ApiResult.Success(
            MessageSearchResponse(success = true, results = listOf(hit("m1")), limit = 30),
        )
        val vm = viewModel()
        advanceUntilIdle()
        vm.onSearchQueryChanged("refund")
        advanceUntilIdle()

        vm.onSearchQueryChanged("")
        advanceUntilIdle()

        assertEquals("", vm.searchState.value.query)
        assertTrue(vm.searchState.value.hits.isEmpty())
        assertFalse(vm.searchState.value.active)
    }

    @Test
    fun `a hit in a loaded thread resolves a reply target, and one outside it does not`() = runTest {
        // ⛔ THE REPLY TARGET COMES FROM THE CONVERSATION AND NEVER FROM THE HIT'S OWN `kind`. A
        // conversation carries the server's canSms/canEmail; deriving a channel from a matching
        // message's type would offer SMS to a customer who has only ever emailed, and the server
        // would then refuse the send.
        //
        // ⚠️ SO A HIT IN A THREAD OUTSIDE THE LOADED WINDOW OPENS READ-ONLY, which is the truthful
        // outcome rather than a gap: this app does not know whether that thread can be replied to,
        // and offering a box whose send would be refused is worse than not offering one.
        val vm = viewModel(api(conversation()))
        advanceUntilIdle()

        assertNotNull(vm.conversationFor("contact:c1"))
        assertNull(vm.conversationFor("addr:+14165550159"))
    }
}
