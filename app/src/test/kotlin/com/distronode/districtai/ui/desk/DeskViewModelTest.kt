package com.distronode.districtai.ui.desk

import com.distronode.districtai.core.data.DeskRepository
import com.distronode.districtai.core.model.DeskBounds
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.core.model.DeskSettingsResponse
import com.distronode.districtai.core.model.DeskTicketCreateResponse
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.DeskTicketSummary
import com.distronode.districtai.core.model.DeskTicketsResponse
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The desk queue's state machine.
 *
 * ⛔ THE THREE STATES THAT LOOK ALIKE AND MEAN DIFFERENT THINGS, which is what most of this file is
 * about: a DISABLED desk (nothing is being recorded), an ENABLED but empty one (no customer has
 * written in), and a FAILED read (we could not ask). Collapsing the first two shows an operator "no
 * customer has ever contacted you" when the truth is that we were never listening; collapsing
 * either into the third offers a retry for something no retry fixes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeskViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val ticket = DeskTicketSummary(
        id = "tkt_1",
        displayReference = "T-1",
        subject = "Leaking tap",
        status = "open",
    )

    private fun api(
        enabled: Boolean = true,
        tickets: List<DeskTicketSummary> = listOf(ticket),
    ) = FakeDeskApiForUi().apply {
        settingsResult = ApiResult.Success(
            DeskSettingsResponse(success = true, settings = DeskSettings(enabled = enabled)),
        )
        ticketsResult = ApiResult.Success(DeskTicketsResponse(success = true, tickets = tickets))
    }

    private fun viewModel(
        api: FakeDeskApiForUi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = DeskViewModel(DeskRepository(api) { "key" }, workspaceId = "ws-1", role = role)

    // ── The four states ──────────────────────────────────────────────────────

    @Test
    fun `a disabled desk is its own state and no queue read is spent`() = runTest {
        val api = api(enabled = false)

        val model = viewModel(api)
        advanceUntilIdle()

        assertTrue(model.state.value is DeskUiState.Disabled)
        // ⚠️ A disabled desk has no queue worth fetching, and asking for one would spend a request
        // to be told something already known.
        assertEquals(0, api.ticketReads)
    }

    @Test
    fun `an enabled desk with no tickets is Content, NOT Disabled`() = runTest {
        val model = viewModel(api(enabled = true, tickets = emptyList()))
        advanceUntilIdle()

        val state = model.state.value as DeskUiState.Content
        assertTrue(state.queueEmpty)
    }

    @Test
    fun `a failed settings read is Failed, never Disabled`() = runTest {
        // ⛔ RENDERING A FAILED READ AS "the desk is off" SENDS AN OPERATOR TO TURN ON SOMETHING
        // ALREADY ON, and the Enable button they press writes a setting nobody asked to change.
        val api = api().apply { settingsResult = ApiResult.NetworkFailure(java.io.IOException()) }

        val model = viewModel(api)
        advanceUntilIdle()

        assertTrue(model.state.value is DeskUiState.Failed)
        assertEquals(0, api.ticketReads)
    }

    @Test
    fun `a failed queue read after a good settings read is still Failed`() = runTest {
        val api = api().apply { ticketsResult = ApiResult.NetworkFailure(java.io.IOException()) }

        val model = viewModel(api)
        advanceUntilIdle()

        assertTrue(model.state.value is DeskUiState.Failed)
    }

    @Test
    fun `a viewer is refused before any request is sent`() = runTest {
        // ⛔ EVERY DESK ROUTE EXCLUDES `viewer`, READS INCLUDED. There is no version of this screen
        // a viewer could be shown, so spending a request to be told so is pure cost.
        val api = api()

        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(model.canUse)
        assertEquals(0, api.settingsReads)
        assertEquals(0, api.ticketReads)
    }

    @Test
    fun `a null role fails closed the same way a viewer does`() = runTest {
        val api = api()

        val model = viewModel(api, role = null)
        advanceUntilIdle()

        assertFalse(model.canUse)
        assertEquals(0, api.settingsReads)
    }

    // ── The filter ───────────────────────────────────────────────────────────

    @Test
    fun `filtering is local and sends no request`() = runTest {
        val api = api(
            tickets = listOf(
                ticket,
                ticket.copy(id = "tkt_2", status = "resolved"),
                ticket.copy(id = "tkt_3", status = "resolved"),
            ),
        )
        val model = viewModel(api)
        advanceUntilIdle()
        val before = api.ticketReads

        model.filterBy(DeskTicketStatus.RESOLVED)

        val state = model.state.value as DeskUiState.Content
        assertEquals(listOf("tkt_2", "tkt_3"), state.visible.map { it.id })
        // ⚠️ THE COUNTS ARE OVER THE WHOLE QUEUE, so a chip's number does not change when it is
        // picked — which is the reason the filter is not sent server-side at all.
        assertEquals(1, state.countOf(DeskTicketStatus.OPEN))
        assertEquals(2, state.countOf(DeskTicketStatus.RESOLVED))
        assertEquals(before, api.ticketReads)
    }

    @Test
    fun `tapping the selected chip clears the filter`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        model.filterBy(DeskTicketStatus.OPEN)
        model.filterBy(DeskTicketStatus.OPEN)

        assertNull((model.state.value as DeskUiState.Content).filter)
    }

    @Test
    fun `a filter that matches nothing is not the same as an empty queue`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        model.filterBy(DeskTicketStatus.RESOLVED)

        val state = model.state.value as DeskUiState.Content
        assertTrue(state.visible.isEmpty())
        // ⛔ "No customer has written in" and "nothing is waiting" are different sentences, and only
        // this flag can tell the screen which to say.
        assertFalse(state.queueEmpty)
    }

    // ── Enabling from the empty state ────────────────────────────────────────

    @Test
    fun `enabling sends ONLY the enabled field`() = runTest {
        val api = api(enabled = false)
        val model = viewModel(api)
        advanceUntilIdle()

        api.settingsResult = ApiResult.Success(
            DeskSettingsResponse(success = true, settings = DeskSettings(enabled = true)),
        )
        model.enableDesk()
        advanceUntilIdle()

        val patch = api.patches.single()
        assertEquals(true, patch.enabled)
        // ⛔ THIS SCREEN NEVER READ THE OTHER TWO FIELDS, so including them would write values it
        // does not know — the `blank_form_overwrites_config` shape.
        assertNull(patch.notifyCustomersByEmail)
        assertNull(patch.publicBrandName)
        assertTrue(model.state.value is DeskUiState.Content)
    }

    @Test
    fun `a failed enable leaves the desk visibly off`() = runTest {
        val api = api(enabled = false)
        val model = viewModel(api)
        advanceUntilIdle()

        api.settingsResult = ApiResult.Forbidden("Forbidden")
        model.enableDesk()
        advanceUntilIdle()

        // ⚠️ The desk is still off, which is the more important fact than the failure; the failure
        // rides the compose channel rather than replacing the screen.
        assertTrue(model.state.value is DeskUiState.Disabled)
        assertTrue(model.compose.value.failure != null)
    }

    // ── The compose sheet ────────────────────────────────────────────────────

    @Test
    fun `a draft is not submittable until the subject clears the route's minimum`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        model.editMessage("It drips.")
        model.editSubject("ab")
        assertFalse("a two-character subject fails the route's min(3)", model.compose.value.submittable)

        model.editSubject("abc")
        assertTrue(model.compose.value.submittable)
    }

    @Test
    fun `a submit hands the boxes over raw and the repository blanks them`() = runTest {
        val api = api().apply {
            createResult = ApiResult.Success(
                DeskTicketCreateResponse(success = true, ticket = ticket),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editSubject("Leaking tap")
        model.editMessage("It drips.")
        model.editRequesterEmail("   ")
        model.submit()
        advanceUntilIdle()

        // ⛔ A BLANK MUST REACH THE WIRE AS ABSENT, NOT `""`. The repository is what enforces it, and
        // the ViewModel deliberately does not pre-check — so a form may hand it whatever is in its
        // boxes without every screen re-learning the rule.
        assertNull(api.createDrafts.single().requesterEmail)
        assertEquals("T-1", model.submitted.value)
        // ⚠️ The draft is cleared only on success.
        assertEquals("", model.compose.value.subject)
    }

    @Test
    fun `a deduplicated submit reports an EMPTY reference rather than a failure`() = runTest {
        // ⚠️ The ticket exists; there is simply no row to show. The screen words this differently,
        // which is why the reference is empty rather than the whole thing being null.
        val api = api().apply {
            createResult = ApiResult.Success(
                DeskTicketCreateResponse(success = true, deduplicated = true),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editSubject("Leaking tap")
        model.editMessage("It drips.")
        model.submit()
        advanceUntilIdle()

        assertEquals("", model.submitted.value)
    }

    @Test
    fun `a failed submit keeps the draft so nothing the operator typed is lost`() = runTest {
        val api = api().apply { createResult = ApiResult.RateLimited("Too many tickets.") }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editSubject("Leaking tap")
        model.editMessage("It drips.")
        model.submit()
        advanceUntilIdle()

        assertEquals("Leaking tap", model.compose.value.subject)
        assertEquals("It drips.", model.compose.value.message)
        assertFalse(model.compose.value.submitting)
        assertTrue(model.compose.value.failure != null)
        assertNull(model.submitted.value)
    }

    @Test
    fun `a viewer's submit sends nothing`() = runTest {
        val api = api()
        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        model.editSubject("Leaking tap")
        model.editMessage("It drips.")
        model.submit()
        advanceUntilIdle()

        assertTrue(api.createDrafts.isEmpty())
    }

    @Test
    fun `retryOrNoop replays a failed load and leaves a good one alone`() = runTest {
        val api = api().apply { settingsResult = ApiResult.NetworkFailure(java.io.IOException()) }
        val model = viewModel(api)
        advanceUntilIdle()
        val afterFailure = api.settingsReads

        model.retryOrNoop()
        advanceUntilIdle()
        assertTrue("a failed load is replayed", api.settingsReads > afterFailure)

        api.settingsResult = ApiResult.Success(
            DeskSettingsResponse(success = true, settings = DeskSettings(enabled = true)),
        )
        model.load()
        advanceUntilIdle()
        val afterSuccess = api.settingsReads

        model.retryOrNoop()
        advanceUntilIdle()
        assertEquals("a loaded queue is not re-read", afterSuccess, api.settingsReads)
    }

    // ── Guards: nothing is sent from a state that cannot use it ─────────────

    @Test
    fun `the requester's name and phone reach the create exactly as typed`() = runTest {
        val api = api().apply {
            createResult = ApiResult.Success(DeskTicketCreateResponse(success = true, ticket = ticket))
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editSubject("Leaking tap")
        model.editMessage("It drips.")
        model.editRequesterName("Ada Lovelace")
        model.editRequesterPhone("+14165550142")
        model.submit()
        advanceUntilIdle()

        val draft = api.createDrafts.single()
        assertEquals("Ada Lovelace", draft.requesterName)
        assertEquals("+14165550142", draft.requesterPhone)
    }

    @Test
    fun `a second submit while the first is in flight sends nothing more`() = runTest {
        val api = api().apply {
            createResult = ApiResult.Success(DeskTicketCreateResponse(success = true, ticket = ticket))
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editSubject("Leaking tap")
        model.editMessage("It drips.")
        model.submit()
        model.submit()
        advanceUntilIdle()

        assertEquals(1, api.createDrafts.size)
    }

    @Test
    fun `a subject or message over the route's maximum is not submittable`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        model.editMessage("It drips.")
        model.editSubject("a".repeat(DeskBounds.SUBJECT_MAX + 1))
        assertFalse(model.compose.value.submittable)

        model.editSubject("Leaking tap")
        model.editMessage("a".repeat(DeskBounds.MESSAGE_MAX + 1))
        assertFalse(model.compose.value.submittable)
    }

    @Test
    fun `discarding the draft empties every box, and acknowledging clears the confirmation`() = runTest {
        val api = api().apply {
            createResult = ApiResult.Success(DeskTicketCreateResponse(success = true, ticket = ticket))
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editSubject("Leaking tap")
        model.editMessage("It drips.")
        model.submit()
        advanceUntilIdle()
        assertEquals("T-1", model.submitted.value)
        model.acknowledge()
        assertNull(model.submitted.value)

        model.editSubject("Second")
        model.discardDraft()
        assertEquals(DeskComposeState(), model.compose.value)
    }

    @Test
    fun `filtering before the queue has loaded changes nothing`() = runTest {
        val model = viewModel(api())

        model.filterBy(DeskTicketStatus.OPEN)

        assertEquals(DeskUiState.Loading, model.state.value)
    }

    @Test
    fun `enabling from anything but the disabled screen sends nothing`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.enableDesk()
        advanceUntilIdle()

        assertTrue(api.patches.isEmpty())
        assertTrue(model.state.value is DeskUiState.Content)
    }

    @Test
    fun `a viewer cannot enable the desk or load it`() = runTest {
        val api = api(enabled = false)
        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        model.enableDesk()
        model.load(refreshing = true)
        advanceUntilIdle()

        assertTrue(api.patches.isEmpty())
        assertEquals(0, api.settingsReads)
    }

    @Test
    fun `a refresh from a failed load re-reads without flashing a spinner in between`() = runTest {
        // ⚠️ A refresh keeps whatever is on screen. From Failed there is no content to mark as
        // refreshing, so the failure stays up until the answer lands rather than turning into a
        // skeleton the operator did not ask for.
        val api = api().apply { settingsResult = ApiResult.NetworkFailure(java.io.IOException()) }
        val model = viewModel(api)
        advanceUntilIdle()
        assertTrue(model.state.value is DeskUiState.Failed)

        api.settingsResult = ApiResult.Success(
            DeskSettingsResponse(success = true, settings = DeskSettings(enabled = true)),
        )
        model.load(refreshing = true)
        assertTrue(model.state.value is DeskUiState.Failed)
        advanceUntilIdle()

        assertTrue(model.state.value is DeskUiState.Content)
    }

    @Test
    fun `the factory builds a model for the workspace it was given`() = runTest {
        val api = api()
        val model = DeskViewModel.factory(DeskRepository(api) { "key" }, "ws-1", WorkspaceRole.CLIENT)
            .create(DeskViewModel::class.java)
        advanceUntilIdle()

        assertTrue(model.canUse)
        assertEquals(1, api.settingsReads)
    }
}
