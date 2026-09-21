package com.distronode.districtai.ui.support

import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.core.model.SupportRequestCreateResponse
import com.distronode.districtai.core.model.SupportRequestKind
import com.distronode.districtai.core.model.SupportRequestListResponse
import com.distronode.districtai.core.model.SupportRequestSummary
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The support list and its composer.
 *
 * ⛔ THE TWO THINGS THIS FILE EXISTS FOR:
 * 1. **An empty list and a failed read stay different states.** The web collapsed them and told a
 *    customer with three open tickets that they had none; they stopped chasing and nobody here ever
 *    saw the request.
 * 2. **A retry reuses the draft's idempotency key.** The server claims the key before it calls
 *    Atlassian, so the same key collapses onto the first request while a fresh one puts a SECOND
 *    ticket in a human's queue. This is the exact inverse of `DeskViewModel`, which must mint one
 *    per submit — see the ⛔ on `SupportRepository.createRequest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val open = SupportRequestSummary(
        issueKey = "DA-42",
        id = "row_1",
        subject = "Calls drop",
        statusName = "In Progress",
        statusCategory = "INDETERMINATE",
    )

    private val resolved = SupportRequestSummary(
        issueKey = "DA-40",
        id = "row_0",
        subject = "Old one",
        statusName = "Terminé",
        statusCategory = "DONE",
    )

    private fun api(requests: List<SupportRequestSummary> = listOf(open, resolved)) =
        FakeSupportApiForUi().apply {
            listResult = ApiResult.Success(
                SupportRequestListResponse(success = true, requests = requests),
            )
        }

    private fun viewModel(
        api: FakeSupportApiForUi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
        keys: () -> String = { "key-fixed" },
    ) = SupportViewModel(SupportRepository(api), workspaceId = "ws-1", role = role, keys = keys)

    // ── The list ─────────────────────────────────────────────────────────────

    @Test
    fun `an empty list is Content, never Failed`() = runTest {
        val model = viewModel(api(requests = emptyList()))
        advanceUntilIdle()

        val state = model.state.value as SupportUiState.Content
        assertTrue(state.requests.isEmpty())
    }

    @Test
    fun `a failed read is Failed, never an empty list`() = runTest {
        // ⛔ THE FAILURE THIS WHOLE SURFACE IS BUILT AROUND. Telling a customer with three open
        // tickets that they have none is how a request stops being chased.
        val api = api().apply { listResult = ApiResult.NetworkFailure(java.io.IOException()) }

        val model = viewModel(api)
        advanceUntilIdle()

        assertTrue(model.state.value is SupportUiState.Failed)
    }

    @Test
    fun `a structurally empty 200 is drift rather than an empty list`() = runTest {
        val api = api().apply {
            listResult = ApiResult.Success(SupportRequestListResponse())
        }

        val model = viewModel(api)
        advanceUntilIdle()

        assertTrue(model.state.value is SupportUiState.Failed)
    }

    @Test
    fun `open and resolved split on the CATEGORY, never on the localised status name`() = runTest {
        // ⚠️ `Terminé` IS RESOLVED. Comparing the name to "Closed" would file a French desk's
        // resolved request under Open.
        val model = viewModel(api())
        advanceUntilIdle()

        val state = model.state.value as SupportUiState.Content
        assertEquals(listOf("row_1"), state.open.map { it.id })
        assertEquals(listOf("row_0"), state.resolved.map { it.id })
    }

    @Test
    fun `a viewer sends no read at all`() = runTest {
        // ⛔ ALL FIVE ROUTES EXCLUDE `viewer`, READS INCLUDED: support correspondence is not
        // operational status, and a read-only seat exists to watch operations.
        val api = api()

        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(model.canUse)
        assertEquals(0, api.listReads)
    }

    // ── The idempotency key, which is this surface's whole retry story ───────

    @Test
    fun `a failed submit KEEPS the key so a retry is a repeat, not a second ticket`() = runTest {
        // ⛔ THE ASSERTION THIS FILE EXISTS FOR. A retry that minted a fresh key would put a second
        // ticket in a human's queue; the same key is answered `deduplicated:true` and collapses.
        var minted = 0
        val api = api().apply { createResult = ApiResult.RateLimited("Too many requests.") }
        val model = viewModel(api) { "key-${minted++}" }
        advanceUntilIdle()

        model.editSubject("Calls drop after 30 seconds")
        model.editMessage("Every inbound call ends abruptly.")
        model.submit()
        advanceUntilIdle()
        model.submit()
        advanceUntilIdle()

        assertEquals(listOf("key-0", "key-0"), api.createKeys)
    }

    @Test
    fun `a successful submit mints a FRESH key, because the old one is spent`() = runTest {
        var minted = 0
        val api = api()
        val model = viewModel(api) { "key-${minted++}" }
        advanceUntilIdle()

        val first = model.compose.value.idempotencyKey
        model.editSubject("Calls drop after 30 seconds")
        model.editMessage("It happens on every call.")
        model.submit()
        advanceUntilIdle()

        assertNotEquals(first, model.compose.value.idempotencyKey)
    }

    @Test
    fun `discarding a draft mints a fresh key, because the next one is genuinely different`() =
        runTest {
            var minted = 0
            val model = viewModel(api()) { "key-${minted++}" }
            advanceUntilIdle()

            val first = model.compose.value.idempotencyKey
            model.discardDraft()

            assertNotEquals(first, model.compose.value.idempotencyKey)
        }

    // ── The three outcomes ───────────────────────────────────────────────────

    @Test
    fun `a filed request reports its key`() = runTest {
        val api = api().apply {
            createResult = ApiResult.Success(
                SupportRequestCreateResponse(success = true, issueKey = "DA-43"),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editSubject("Calls drop after 30 seconds")
        model.editMessage("Every call.")
        model.submit()
        advanceUntilIdle()

        assertEquals(SupportSubmitOutcome.FILED, model.submitted.value!!.outcome)
        assertEquals("DA-43", model.submitted.value!!.issueKey)
    }

    @Test
    fun `a pending filing is a SUCCESS, not an error`() = runTest {
        // ⛔ WE HOLD THE CLAIM ROW AND A HUMAN WILL SEE IT; only the reference is missing. Rendering
        // this as an error is what makes an operator send the same request twice, which is the one
        // thing the idempotency key exists to prevent.
        val api = api().apply {
            createResult = ApiResult.Success(
                SupportRequestCreateResponse(success = true, pending = true),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editSubject("Calls drop after 30 seconds")
        model.editMessage("Every call.")
        model.submit()
        advanceUntilIdle()

        assertEquals(SupportSubmitOutcome.PENDING, model.submitted.value!!.outcome)
        assertNull(model.submitted.value!!.issueKey)
        assertNull("a success must leave no failure behind", model.compose.value.failure)
    }

    @Test
    fun `a deduplicated filing is a success too`() = runTest {
        val api = api().apply {
            createResult = ApiResult.Success(
                SupportRequestCreateResponse(success = true, deduplicated = true),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editSubject("Calls drop after 30 seconds")
        model.editMessage("Every call.")
        model.submit()
        advanceUntilIdle()

        assertEquals(SupportSubmitOutcome.DEDUPLICATED, model.submitted.value!!.outcome)
    }

    @Test
    fun `a failed submit keeps everything the operator typed`() = runTest {
        val api = api().apply {
            createResult = ApiResult.HttpFailure(
                503,
                "Support requests are temporarily unavailable here.",
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.setKind(SupportRequestKind.SUGGESTION)
        model.editSubject("An idea about voicemail")
        model.editMessage("It could transcribe.")
        model.submit()
        advanceUntilIdle()

        val draft = model.compose.value
        assertEquals(SupportRequestKind.SUGGESTION, draft.kind)
        assertEquals("An idea about voicemail", draft.subject)
        assertFalse(draft.submitting)
        assertTrue("the server's own sentence must reach the screen", draft.failure != null)
        assertNull(model.submitted.value)
    }

    @Test
    fun `a short subject is not submittable, matching the route's minimum`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        model.editMessage("Every call.")
        model.editSubject("ab")
        assertFalse(model.compose.value.submittable)

        model.editSubject("abc")
        assertTrue(model.compose.value.submittable)
    }

    @Test
    fun `a viewer's submit sends nothing`() = runTest {
        val api = api()
        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        model.editSubject("Calls drop after 30 seconds")
        model.editMessage("Every call.")
        model.submit()
        advanceUntilIdle()

        assertTrue(api.createDrafts.isEmpty())
    }

    @Test
    fun `acknowledge clears the confirmation so it cannot be shown twice`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        model.editSubject("Calls drop after 30 seconds")
        model.editMessage("Every call.")
        model.submit()
        advanceUntilIdle()

        model.acknowledge()

        assertNull(model.submitted.value)
    }

    @Test
    fun `retryOrNoop replays a failed load and leaves a good one alone`() = runTest {
        val api = api().apply { listResult = ApiResult.NetworkFailure(java.io.IOException()) }
        val model = viewModel(api)
        advanceUntilIdle()
        val afterFailure = api.listReads

        model.retryOrNoop()
        advanceUntilIdle()
        assertTrue(api.listReads > afterFailure)

        api.listResult = ApiResult.Success(SupportRequestListResponse(success = true))
        model.load()
        advanceUntilIdle()
        val afterSuccess = api.listReads

        model.retryOrNoop()
        advanceUntilIdle()
        assertEquals(afterSuccess, api.listReads)
    }
}
