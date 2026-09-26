package com.distronode.districtai.ui.hq

import com.distronode.districtai.R
import com.distronode.districtai.core.data.HqRepository
import com.distronode.districtai.core.model.HqConfirmResponse
import com.distronode.districtai.core.model.HqPendingWrite
import com.distronode.districtai.core.model.HqPromptResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The console's state machine, and the two guarantees it exists to keep:
 * a failure never destroys the transcript, and a write is never applied without a deliberate tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HqViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: TestDistrictApi, role: WorkspaceRole? = WorkspaceRole.CLIENT) =
        HqViewModel(HqRepository(api), workspaceId = "ws-1", role = role)

    private fun pendingWrite(tool: String = "update_persona") = HqPendingWrite(
        tool = tool,
        args = JsonObject(mapOf("greeting" to JsonPrimitive("Good afternoon."))),
        summary = "Update the AI receptionist persona (greeting → \"Good afternoon.\").",
    )

    private fun apiProposing(pending: HqPendingWrite = pendingWrite()) = TestDistrictApi().apply {
        hqPromptResult = ApiResult.Success(
            HqPromptResponse(
                success = true,
                answer = "I've proposed a new greeting.",
                needsConfirmation = true,
                pendingWrite = pending,
            ),
        )
    }

    // ── Prompt turns ─────────────────────────────────────────────────────────

    @Test
    fun `a prompt turn appends the question and then the answer`() = runTest(dispatcher) {
        val api = TestDistrictApi()
        val vm = viewModel(api)

        vm.ask("How did we do this week?")
        advanceUntilIdle()

        assertEquals(
            listOf(HqRole.OPERATOR, HqRole.CONSOLE),
            vm.messages.value.map { it.role },
        )
        assertEquals("How did we do this week?", vm.messages.value[0].text.literalOrNull)
        assertEquals("You had 3 calls this week.", vm.messages.value[1].text.literalOrNull)
        assertEquals(HqUiState.Idle, vm.state.value)
    }

    @Test
    fun `history accumulates and is labelled in the SERVER's vocabulary`() = runTest(dispatcher) {
        // ⛔ THE ROUTE DROPS ANY TURN WHOSE ROLE IS NEITHER `user` NOR `model`, SILENTLY. The
        // obvious spelling — "assistant" — would lose half the conversation with no error anywhere,
        // and the only symptom would be answers that stop following the thread.
        val api = TestDistrictApi()
        val vm = viewModel(api)

        vm.ask("First question")
        advanceUntilIdle()
        vm.ask("Second question")
        advanceUntilIdle()

        // The FIRST turn carries no history; the second carries the whole exchange before it.
        assertEquals(emptyList<String>(), api.hqPrompts[0].history.map { it.role })
        assertEquals(listOf("user", "model"), api.hqPrompts[1].history.map { it.role })
        assertEquals("First question", api.hqPrompts[1].history[0].text)
        assertEquals("You had 3 calls this week.", api.hqPrompts[1].history[1].text)
        // ⛔ AND THE PROMPT IS NOT ALSO IN THE HISTORY. The route appends `prompt` to whatever
        // `history` holds, so a trailing copy would send the question twice in one request and the
        // model answers as though the operator had repeated themselves.
        assertEquals("Second question", api.hqPrompts[1].prompt)
        assertFalse(
            "the prompt must not be duplicated into its own history",
            api.hqPrompts[1].history.any { it.text == "Second question" },
        )
    }

    @Test
    fun `the whole transcript is sent, because the SERVER does the trimming`() = runTest(dispatcher) {
        val api = TestDistrictApi()
        val vm = viewModel(api)

        repeat(5) {
            vm.ask("Question $it")
            advanceUntilIdle()
        }

        // Five exchanges = ten turns before the sixth ask. The server keeps the last 6; trimming
        // here as well would be two places deciding the same thing and disagreeing after a change.
        assertEquals(8, api.hqPrompts[4].history.size)
    }

    @Test
    fun `a blank prompt is refused without a round trip`() = runTest(dispatcher) {
        val api = TestDistrictApi()
        val vm = viewModel(api)

        vm.ask("   ")
        advanceUntilIdle()

        assertTrue("nothing should have been sent", api.hqPrompts.isEmpty())
        assertTrue("and nothing appended", vm.messages.value.isEmpty())
    }

    @Test
    fun `a second ask while one is in flight is refused`() = runTest(dispatcher) {
        // ⛔ Two turns racing on one transcript interleave their appends and produce a conversation
        // that never happened — and each is a billable model run over the workspace's data.
        val api = TestDistrictApi()
        val vm = viewModel(api)

        vm.ask("First")
        vm.ask("Second")
        advanceUntilIdle()

        assertEquals(1, api.hqPrompts.size)
        assertEquals("First", api.hqPrompts[0].prompt)
    }

    // ── Failure ──────────────────────────────────────────────────────────────

    @Test
    fun `a failed turn keeps the transcript, including the unanswered question`() = runTest(dispatcher) {
        // ⛔ THE CENTRAL GUARANTEE. The server holds no conversation, so a screen that blanked on
        // failure would destroy the only copy of it.
        val api = TestDistrictApi()
        val vm = viewModel(api)

        vm.ask("A question that lands")
        advanceUntilIdle()
        api.hqPromptResult = ApiResult.HttpFailure(502, "Bad gateway")
        vm.ask("A question that fails")
        advanceUntilIdle()

        assertTrue(vm.state.value is HqUiState.Failed)
        assertEquals(
            listOf(HqRole.OPERATOR, HqRole.CONSOLE, HqRole.OPERATOR),
            vm.messages.value.map { it.role },
        )
        assertEquals("A question that fails", vm.messages.value.last().text.literalOrNull)
    }

    @Test
    fun `retrying re-sends the failed turn without duplicating the question`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            hqPromptResult = ApiResult.HttpFailure(502, "Bad gateway")
        }
        val vm = viewModel(api)

        vm.ask("Try me")
        advanceUntilIdle()
        api.hqPromptResult = ApiResult.Success(HqPromptResponse(success = true, answer = "Done."))
        vm.retry()
        advanceUntilIdle()

        assertEquals("the turn is re-sent", 2, api.hqPrompts.size)
        // ⚠️ The operator's message is already in the transcript; a retry that appended it again
        // would show the question twice and then replay it as its own history.
        assertEquals(
            listOf(HqRole.OPERATOR, HqRole.CONSOLE),
            vm.messages.value.map { it.role },
        )
        assertEquals(HqUiState.Idle, vm.state.value)
    }

    @Test
    fun `a rate limit surfaces the server's own wording and stays retryable`() = runTest(dispatcher) {
        // ⚠️ 30/min per ACCOUNT, shared by prompts and confirms. The session is intact and the
        // server's message is more specific than anything the client could infer.
        val api = TestDistrictApi().apply {
            hqPromptResult = ApiResult.RateLimited(
                "You're sending requests too quickly — give it a second.",
            )
        }
        val vm = viewModel(api)

        vm.ask("Too fast")
        advanceUntilIdle()

        val state = vm.state.value as HqUiState.Failed
        assertEquals(
            "You're sending requests too quickly — give it a second.",
            state.failure.message.literalOrNull,
        )
        assertTrue("a rate limit is worth retrying", state.failure.retryable)
    }

    @Test
    fun `a 200 that does not affirm success is contract drift, not an empty answer`() = runTest(dispatcher) {
        // ⛔ An empty body decodes cleanly into every-field-defaulted, which would read as the
        // console answering with silence. See rejectedEnvelope.
        val api = TestDistrictApi().apply {
            hqPromptResult = ApiResult.Success(HqPromptResponse())
        }
        val vm = viewModel(api)

        vm.ask("Anything")
        advanceUntilIdle()

        val state = vm.state.value as HqUiState.Failed
        assertEquals(R.string.failure_unexpected_response, state.failure.message.resourceIdOrNull)
    }

    @Test
    fun `a confirmation prompt with nothing to confirm is rejected`() = runTest(dispatcher) {
        // ⛔ The answer would say a change was proposed while the screen offered no way to apply it
        // and no sign anything was missing — which the operator reads as "done".
        val api = TestDistrictApi().apply {
            hqPromptResult = ApiResult.Success(
                HqPromptResponse(success = true, answer = "Proposed.", needsConfirmation = true),
            )
        }
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()

        val state = vm.state.value as HqUiState.Failed
        assertEquals(R.string.failure_unexpected_response, state.failure.message.resourceIdOrNull)
    }

    // ── Proposal and confirmation ────────────────────────────────────────────

    @Test
    fun `a proposal is surfaced and NOTHING is sent until it is confirmed`() = runTest(dispatcher) {
        val api = apiProposing()
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()

        val state = vm.state.value as HqUiState.Confirming
        assertEquals("update_persona", state.pending.tool)
        // ⛔ THE WHOLE TWO-STEP. A write the model emits is a proposal; untrusted text in a
        // transcript can steer the model into proposing one but must never cause one to execute.
        assertTrue("no write may have been sent", api.hqConfirms.isEmpty())
    }

    @Test
    fun `confirming echoes the proposal back VERBATIM`() = runTest(dispatcher) {
        // ⛔ The operator approved the sentence they read, so the applied action must be the one
        // that sentence described — the arguments are returned unchanged, never rebuilt.
        val proposal = pendingWrite()
        val api = apiProposing(proposal)
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.confirmPending()
        advanceUntilIdle()

        assertEquals(1, api.hqConfirms.size)
        assertEquals(proposal.tool, api.hqConfirms[0].confirm.tool)
        assertEquals(proposal.args, api.hqConfirms[0].confirm.args)
        assertEquals("ws-1", api.hqConfirms[0].workspaceId)

        assertEquals(HqUiState.Idle, vm.state.value)
        assertEquals(R.string.hq_applied, vm.messages.value.last().text.resourceIdOrNull)
    }

    @Test
    fun `a handled request that declined the write reads as NOT applied`() = runTest(dispatcher) {
        // ⛔ success:true with executed:false is not a failure and is not a success. Reporting it as
        // done is exactly the lie the pair of flags exists to prevent.
        val api = apiProposing().apply {
            hqConfirmResult = ApiResult.Success(
                HqConfirmResponse(success = true, executed = false, tool = "update_persona"),
            )
        }
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.confirmPending()
        advanceUntilIdle()

        assertEquals(R.string.hq_not_applied, vm.messages.value.last().text.resourceIdOrNull)
        assertEquals(HqUiState.Idle, vm.state.value)
    }

    @Test
    fun `a substituted action is rejected rather than reported as done`() = runTest(dispatcher) {
        // ⛔ "We applied something, but not what you approved" has no honest UI. Treating it as
        // success is how a delete gets attributed to a persona edit.
        val api = apiProposing().apply {
            hqConfirmResult = ApiResult.Success(
                HqConfirmResponse(success = true, executed = true, tool = "delete_contact"),
            )
        }
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.confirmPending()
        advanceUntilIdle()

        val state = vm.state.value as HqUiState.ConfirmFailed
        assertEquals(R.string.failure_unexpected_response, state.failure.message.resourceIdOrNull)
        assertTrue(
            "nothing may be reported as applied",
            vm.messages.value.none { it.text.resourceIdOrNull == R.string.hq_applied },
        )
    }

    @Test
    fun `a second confirm tap while one is applying is refused`() = runTest(dispatcher) {
        // ⛔ THE TAP THAT SPENDS MONEY. The server offers no idempotency key, so this guard is the
        // only thing between a double tap and a repeated write.
        val api = apiProposing()
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.confirmPending()
        vm.confirmPending()
        advanceUntilIdle()

        assertEquals("exactly one write", 1, api.hqConfirms.size)
    }

    @Test
    fun `a failed confirm keeps the card and is NEVER replayed by a session change`() = runTest(dispatcher) {
        // ⛔ THE ASYMMETRY IS THE POINT. A failed confirm may already have executed — the response
        // is what was lost, not necessarily the write — so re-issuing it on an event the operator
        // did not connect to it would delete a second contact or send a second email.
        val api = apiProposing().apply {
            hqConfirmResult = ApiResult.NetworkFailure(java.io.IOException("dropped"))
        }
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.confirmPending()
        advanceUntilIdle()

        val state = vm.state.value as HqUiState.ConfirmFailed
        assertEquals("update_persona", state.pending.tool)

        vm.retryOrNoop()
        advanceUntilIdle()

        assertEquals("the write must not be repeated", 1, api.hqConfirms.size)
        assertTrue("and the card stays", vm.state.value is HqUiState.ConfirmFailed)
    }

    @Test
    fun `a session change replays a failed PROMPT`() = runTest(dispatcher) {
        // ⛔ Without this a turn that died on Unauthorized stays failed forever: signing in
        // successfully would leave the operator looking at "your session has ended" behind a button
        // that has already done its job.
        val api = TestDistrictApi().apply {
            hqPromptResult = ApiResult.HttpFailure(502, "Bad gateway")
        }
        val vm = viewModel(api)

        vm.ask("Ask me again")
        advanceUntilIdle()
        api.hqPromptResult = ApiResult.Success(HqPromptResponse(success = true, answer = "Done."))
        vm.retryOrNoop()
        advanceUntilIdle()

        assertEquals(2, api.hqPrompts.size)
        assertEquals(HqUiState.Idle, vm.state.value)
    }

    @Test
    fun `a session change on a healthy console does nothing`() = runTest(dispatcher) {
        val api = TestDistrictApi()
        val vm = viewModel(api)

        vm.ask("All good")
        advanceUntilIdle()
        vm.retryOrNoop()
        advanceUntilIdle()

        assertEquals("no turn may be duplicated", 1, api.hqPrompts.size)
    }

    @Test
    fun `dismissing a proposal sends nothing`() = runTest(dispatcher) {
        // ⚠️ Purely local: the proposal was never applied, so there is no "cancel" endpoint and
        // calling one would imply the server was holding state it is not.
        val api = apiProposing()
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.dismissPending()

        assertEquals(HqUiState.Idle, vm.state.value)
        assertTrue(api.hqConfirms.isEmpty())
    }

    // ── Roles ────────────────────────────────────────────────────────────────

    @Test
    fun `a viewer still gets answers`() = runTest(dispatcher) {
        // ⚠️ Reads admit viewers. Removing the console from them would remove the half of the
        // feature they are entitled to.
        val api = TestDistrictApi()
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)

        vm.ask("How did we do this week?")
        advanceUntilIdle()

        assertEquals("You had 3 calls this week.", vm.messages.value.last().text.literalOrNull)
        assertFalse("but is offered no confirm control", vm.canConfirm)
    }

    @Test
    fun `a viewer cannot confirm even if a proposal somehow arrives`() = runTest(dispatcher) {
        // ⚠️ SHOULD BE UNREACHABLE: the server refuses a viewer's write inside the tool executor,
        // BEFORE the confirmation gate, so no proposal is ever surfaced to them. This pins the
        // behaviour for the day that ordering changes.
        val api = apiProposing()
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.confirmPending()
        advanceUntilIdle()

        assertTrue("no write may be attempted", api.hqConfirms.isEmpty())
    }

    @Test
    fun `an unparsed role confirms nothing`() = runTest(dispatcher) {
        // ⛔ fromWire fails CLOSED to null, and null must mean "no privileges" rather than the
        // server's default of client — here the role could not be established at all.
        val vm = viewModel(TestDistrictApi(), role = null)

        assertFalse(vm.canConfirm)
    }

    @Test
    fun `client-authored receipts are not replayed as conversation`() = runTest(dispatcher) {
        // ⚠️ "Applied." is OUR bookkeeping, not something either party said. Replaying it would put
        // words in the model's mouth on the next turn.
        val api = apiProposing()
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.confirmPending()
        advanceUntilIdle()
        api.hqPromptResult = ApiResult.Success(HqPromptResponse(success = true, answer = "Sure."))
        vm.ask("What else?")
        advanceUntilIdle()

        val history = api.hqPrompts.last().history
        assertEquals(listOf("user", "model"), history.map { it.role })
        assertNull(
            "the receipt has no wire text and must not be sent",
            history.firstOrNull { it.text.isBlank() },
        )
    }

    @Test
    fun `a question asked while a write is applying is refused`() = runTest(dispatcher) {
        // ⛔ Two turns racing on the same transcript can interleave their appends; an apply is a
        // turn in flight exactly as a prompt is.
        val api = apiProposing()
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.confirmPending()
        vm.ask("And another thing")
        advanceUntilIdle()

        assertEquals(1, api.hqPrompts.size)
        assertFalse(vm.messages.value.any { it.text.literalOrNull == "And another thing" })
    }

    @Test
    fun `a failed confirm can be dismissed, which sends nothing more`() = runTest(dispatcher) {
        val api = apiProposing().apply {
            hqConfirmResult = ApiResult.NetworkFailure(java.io.IOException("dropped"))
        }
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.confirmPending()
        advanceUntilIdle()
        vm.dismissPending()

        assertEquals(HqUiState.Idle, vm.state.value)
        assertEquals(1, api.hqConfirms.size)
    }

    @Test
    fun `dismissing while a write is applying does not pretend to cancel it`() = runTest(dispatcher) {
        // ⚠️ The write is already on the wire. Dropping the card now would hide what is being done.
        val api = apiProposing()
        val vm = viewModel(api)

        vm.ask("Change the greeting")
        advanceUntilIdle()
        vm.confirmPending()
        vm.dismissPending()

        assertTrue(vm.state.value is HqUiState.Applying)
        advanceUntilIdle()
        assertEquals(1, api.hqConfirms.size)
    }

    @Test
    fun `a failed turn holds the prompt a retry will send`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply { hqPromptResult = ApiResult.HttpFailure(502, "Bad gateway") }
        val vm = viewModel(api)

        vm.ask("  Who called?  ")
        advanceUntilIdle()

        assertEquals("Who called?", (vm.state.value as HqUiState.Failed).prompt)
    }
}
