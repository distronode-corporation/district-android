package com.distronode.districtai.ui.desk

import com.distronode.districtai.core.data.DeskRepository
import com.distronode.districtai.core.model.DeskMessage
import com.distronode.districtai.core.model.DeskReplyResponse
import com.distronode.districtai.core.model.DeskTicketDetail
import com.distronode.districtai.core.model.DeskTicketResponse
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.DeskTicketStatusResponse
import com.distronode.districtai.core.model.DeskTicketSummary
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DeskApi
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One ticket's state machine.
 *
 * ⛔ WHAT THIS FILE PROTECTS IS THE ECHO. Both writes here change fields the client did not ask
 * about — a reply auto-sets `waiting` UNLESS the ticket is resolved, and a status change stamps or
 * clears `resolvedAt` — so a screen that adopted the value it SENT would be confidently wrong in
 * exactly the cases that matter, and every figure derived from `resolvedAt` would be plausible and
 * false.
 *
 * ⛔ AND THAT A FAILED WRITE NEVER DESTROYS THE THREAD. The correspondence already on screen is
 * still true and is the thing an operator needs in order to try again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeskTicketViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val detail = DeskTicketDetail(
        id = "tkt_1",
        displayReference = "T-1",
        subject = "Leaking tap",
        status = "open",
        messageCount = 1,
        messages = listOf(DeskMessage(id = "m1", authorType = "customer", body = "It drips.")),
    )

    private fun api() = FakeDeskApiForUi().apply {
        ticketResult = ApiResult.Success(DeskTicketResponse(success = true, ticket = detail))
    }

    private fun viewModel(
        api: FakeDeskApiForUi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = DeskTicketViewModel(
        DeskRepository(api) { "key" },
        workspaceId = "ws-1",
        ticketId = "tkt_1",
        role = role,
    )

    @Test
    fun `a viewer sends no read at all`() = runTest {
        val api = api()

        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(model.canUse)
        assertEquals(0, api.ticketDetailReads)
    }

    @Test
    fun `the thread loads`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        val state = model.state.value as DeskTicketUiState.Content
        assertEquals("T-1", state.ticket.displayReference)
        assertEquals(listOf("m1"), state.messages.map { it.id })
    }

    // ── The reply ────────────────────────────────────────────────────────────

    @Test
    fun `a reply adopts the echoed STATUS rather than assuming waiting`() = runTest {
        // ⛔ THE SERVER LEAVES A RESOLVED TICKET ALONE. A client that assumed `waiting` would be
        // wrong for exactly the ticket an operator is most likely answering as a courtesy.
        val api = api().apply {
            replyResult = ApiResult.Success(
                DeskReplyResponse(
                    success = true,
                    ticket = DeskTicketSummary(id = "tkt_1", status = "resolved"),
                    message = DeskMessage(id = "m2", authorType = "team", body = "Done."),
                    notified = true,
                ),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editDraft("Done.")
        model.send()
        advanceUntilIdle()

        val state = model.state.value as DeskTicketUiState.Content
        assertEquals(DeskTicketStatus.RESOLVED, state.ticket.knownStatus)
        assertEquals(listOf("m1", "m2"), state.messages.map { it.id })
        assertEquals(true, state.lastNotified)
        // ⚠️ The draft is cleared only on success.
        assertEquals("", model.draft.value)
    }

    @Test
    fun `a degraded replay re-reads rather than guessing a status`() = runTest {
        // ⛔ THE REPLY LANDED AND THE SERVER CANNOT SAY WHICH ONE. Re-reading is the only way to
        // show the truth; inventing `waiting` here is exactly the mistake this class exists to
        // prevent.
        val api = api().apply {
            replyResult = ApiResult.Success(DeskReplyResponse(success = true, deduplicated = true))
        }
        val model = viewModel(api)
        advanceUntilIdle()
        val before = api.ticketDetailReads

        model.editDraft("Done.")
        model.send()
        advanceUntilIdle()

        assertTrue("the thread must be re-read", api.ticketDetailReads > before)
    }

    @Test
    fun `a failed reply keeps the thread AND the draft`() = runTest {
        val api = api().apply {
            replyResult = ApiResult.RateLimited("Too many replies in the last hour.")
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editDraft("We will come Tuesday.")
        model.send()
        advanceUntilIdle()

        val state = model.state.value as DeskTicketUiState.Content
        // ⛔ THE CORRESPONDENCE ALREADY ON SCREEN IS STILL TRUE. Replacing it with a failure screen
        // would take away the thing the operator needs in order to try again.
        assertEquals(listOf("m1"), state.messages.map { it.id })
        assertEquals("We will come Tuesday.", model.draft.value)
        assertFalse(state.sending)
        assertTrue(state.sendFailure != null)
    }

    @Test
    fun `a blank draft sends nothing`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.editDraft("   ")
        model.send()
        advanceUntilIdle()

        assertTrue(api.replyBodies.isEmpty())
    }

    @Test
    fun `a viewer's reply sends nothing`() = runTest {
        val api = api()
        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        model.editDraft("Hello")
        model.send()
        advanceUntilIdle()

        assertTrue(api.replyBodies.isEmpty())
    }

    // ── The status change ────────────────────────────────────────────────────

    @Test
    fun `a status change adopts the echoed resolvedAt`() = runTest {
        // ⛔ RESOLVING STAMPS IT AND ANYTHING ELSE CLEARS IT, both server-side. A screen holding its
        // own copy would show a resolution time for a ticket that has since been reopened.
        val api = api().apply {
            statusResult = ApiResult.Success(
                DeskTicketStatusResponse(
                    success = true,
                    ticket = DeskTicketSummary(
                        id = "tkt_1",
                        status = "resolved",
                        resolvedAt = "2026-09-03T09:00:00.000Z",
                    ),
                ),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.setStatus(DeskTicketStatus.RESOLVED)
        advanceUntilIdle()

        val state = model.state.value as DeskTicketUiState.Content
        assertEquals("2026-09-03T09:00:00.000Z", state.ticket.resolvedAt)
        // ⚠️ The thread survives a status change untouched.
        assertEquals(listOf("m1"), state.messages.map { it.id })
    }

    @Test
    fun `setting the status a ticket already has sends nothing`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.setStatus(DeskTicketStatus.OPEN)
        advanceUntilIdle()

        assertTrue(api.statuses.isEmpty())
    }

    @Test
    fun `a failed status change keeps the thread and the old status`() = runTest {
        val api = api().apply { statusResult = ApiResult.Forbidden("Forbidden") }
        val model = viewModel(api)
        advanceUntilIdle()

        model.setStatus(DeskTicketStatus.RESOLVED)
        advanceUntilIdle()

        val state = model.state.value as DeskTicketUiState.Content
        assertEquals(DeskTicketStatus.OPEN, state.ticket.knownStatus)
        assertEquals(listOf("m1"), state.messages.map { it.id })
        assertTrue(state.statusFailure != null)
        assertFalse(state.statusChanging)
    }

    @Test
    fun `a reply in flight does not disable the status controls`() = runTest {
        // ⚠️ TWO FLAGS, NOT ONE. They are separate controls and an operator may reasonably use one
        // while the other is in flight; a shared "busy" would grey out the reply box because
        // someone tapped Resolve.
        val model = viewModel(api())
        advanceUntilIdle()

        val state = model.state.value as DeskTicketUiState.Content
        assertFalse(state.sending)
        assertFalse(state.statusChanging)
    }

    @Test
    fun `retryOrNoop replays the READ and never a write`() = runTest {
        val api = api().apply { ticketResult = ApiResult.NetworkFailure(java.io.IOException()) }
        val model = viewModel(api)
        advanceUntilIdle()
        val afterFailure = api.ticketDetailReads

        model.retryOrNoop()
        advanceUntilIdle()

        assertTrue(api.ticketDetailReads > afterFailure)
        // ⛔ A REPLY MAY ALREADY HAVE BEEN POSTED when the session expired. Replaying it would put a
        // second message in the customer's thread.
        assertTrue(api.replyBodies.isEmpty())
        assertTrue(api.statuses.isEmpty())
    }

    @Test
    fun `retryOrNoop on a loaded thread re-reads nothing`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        val reads = api.ticketDetailReads

        model.retryOrNoop()
        advanceUntilIdle()

        assertEquals(reads, api.ticketDetailReads)
    }

    @Test
    fun `a ticket that decodes to drift is a failure rather than an empty thread`() = runTest {
        val api = api().apply {
            ticketResult = ApiResult.Success(DeskTicketResponse(success = true))
        }
        val model = viewModel(api)
        advanceUntilIdle()

        assertTrue(model.state.value is DeskTicketUiState.Failed)
        assertNull((model.state.value as? DeskTicketUiState.Content)?.ticket)
    }

    // ── Guards: nothing is sent from a state that cannot use it ─────────────

    @Test
    fun `a reply or a status change before the thread has loaded sends nothing`() = runTest {
        val api = api().apply { ticketResult = ApiResult.NetworkFailure(java.io.IOException()) }
        val model = viewModel(api)
        advanceUntilIdle()
        assertTrue(model.state.value is DeskTicketUiState.Failed)

        model.editDraft("Hello")
        model.send()
        model.setStatus(DeskTicketStatus.RESOLVED)
        advanceUntilIdle()

        assertTrue(api.replyBodies.isEmpty())
        assertTrue(api.statuses.isEmpty())
    }

    @Test
    fun `a second send while the first is in flight sends nothing more`() = runTest {
        val api = api().apply {
            replyResult = ApiResult.Success(
                DeskReplyResponse(
                    success = true,
                    ticket = DeskTicketSummary(id = "tkt_1", status = "waiting"),
                    message = DeskMessage(id = "m2", authorType = "team", body = "Coming."),
                    notified = true,
                ),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editDraft("Coming.")
        model.send()
        model.send()
        advanceUntilIdle()

        assertEquals(listOf("Coming."), api.replyBodies)
    }

    @Test
    fun `a second status change while the first is in flight sends nothing more`() = runTest {
        val api = api().apply {
            statusResult = ApiResult.Success(
                DeskTicketStatusResponse(
                    success = true,
                    ticket = DeskTicketSummary(id = "tkt_1", status = "resolved"),
                ),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.setStatus(DeskTicketStatus.RESOLVED)
        model.setStatus(DeskTicketStatus.WAITING)
        advanceUntilIdle()

        assertEquals(listOf(DeskTicketStatus.RESOLVED), api.statuses)
    }

    @Test
    fun `a viewer's status change sends nothing`() = runTest {
        val api = api()
        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        model.setStatus(DeskTicketStatus.RESOLVED)
        advanceUntilIdle()

        assertTrue(api.statuses.isEmpty())
    }

    @Test
    fun `the factory builds a model for the ticket it was given`() = runTest {
        val api = api()
        val model = DeskTicketViewModel.factory(DeskRepository(api) { "key" }, "ws-1", "tkt_1", WorkspaceRole.CLIENT)
            .create(DeskTicketViewModel::class.java)
        advanceUntilIdle()

        assertEquals("tkt_1", model.ticketId)
        assertEquals(1, api.ticketDetailReads)
    }

    @Test
    fun `a status change during a send keeps the delivered reply and stops both spinners`() = runTest {
        // ⛔ THE CHIPS STAY TAPPABLE WHILE A REPLY SENDS. Before the fix the reply landed on its
        // tap-time snapshot and the status change then landed on ITS snapshot (taken with
        // sending = true), so Send spun forever and the delivered reply vanished from the thread.
        val api = api().apply {
            replyResult = ApiResult.Success(
                DeskReplyResponse(
                    success = true,
                    ticket = DeskTicketSummary(id = "tkt_1", status = "waiting"),
                    message = DeskMessage(id = "m2", authorType = "team", body = "On our way."),
                    notified = true,
                ),
            )
            statusResult = ApiResult.Success(
                DeskTicketStatusResponse(
                    success = true,
                    ticket = DeskTicketSummary(id = "tkt_1", status = "resolved"),
                ),
            )
        }
        val replyGate = CompletableDeferred<Unit>()
        val statusGate = CompletableDeferred<Unit>()
        val gated = object : DeskApi by api {
            override suspend fun replyToDeskTicket(
                workspaceId: String,
                ticketId: String,
                message: String,
                idempotencyKey: String?,
            ): ApiResult<DeskReplyResponse> {
                replyGate.await()
                return api.replyToDeskTicket(workspaceId, ticketId, message, idempotencyKey)
            }

            override suspend fun setDeskTicketStatus(
                workspaceId: String,
                ticketId: String,
                status: DeskTicketStatus,
            ): ApiResult<DeskTicketStatusResponse> {
                statusGate.await()
                return api.setDeskTicketStatus(workspaceId, ticketId, status)
            }
        }
        val model = DeskTicketViewModel(
            DeskRepository(gated) { "key" },
            workspaceId = "ws-1",
            ticketId = "tkt_1",
            role = WorkspaceRole.CLIENT,
        )
        advanceUntilIdle()

        model.editDraft("On our way.")
        model.send()
        model.setStatus(DeskTicketStatus.RESOLVED)
        advanceUntilIdle()
        replyGate.complete(Unit)
        advanceUntilIdle()
        statusGate.complete(Unit)
        advanceUntilIdle()

        val state = model.state.value as DeskTicketUiState.Content
        assertFalse(state.sending)
        assertFalse(state.statusChanging)
        assertEquals(listOf("m1", "m2"), state.messages.map { it.id })
        assertEquals("resolved", state.ticket.status)
        assertEquals(true, state.lastNotified)
    }
}
