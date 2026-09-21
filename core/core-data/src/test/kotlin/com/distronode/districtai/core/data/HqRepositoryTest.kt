package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.HqConfirmResponse
import com.distronode.districtai.core.model.HqPendingWrite
import com.distronode.districtai.core.model.HqPromptResponse
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two guarantees this layer adds on top of the wire: a proposal that cannot be confirmed is
 * rejected rather than shown, and a confirmation describing a DIFFERENT action than the one
 * approved is rejected rather than reported as done.
 */
class HqRepositoryTest {

    private val proposal = HqPendingWrite(
        tool = "delete_contact",
        args = JsonObject(mapOf("contact" to JsonPrimitive("Ada"))),
        summary = "Permanently DELETE the CRM contact \"Ada\".",
    )

    private fun repository(api: FakeDistrictApi) = HqRepository(api)

    // ── Prompt turns ─────────────────────────────────────────────────────────

    @Test
    fun `an answer with no proposal carries none`() = runTest {
        val api = FakeDistrictApi()
        val result = repository(api).ask("ws-1", "How did we do?", emptyList())

        val answer = (result as ApiResult.Success).value
        assertEquals("You had 3 calls this week.", answer.answer)
        assertNull("nothing was proposed", answer.pendingWrite)
    }

    @Test
    fun `the history is sent WHOLE, because the server does the trimming`() = runTest {
        // ⚠️ The route keeps the last 6 turns. Trimming here as well would be two places deciding
        // the same thing, which is how they end up disagreeing after a server change.
        val api = FakeDistrictApi()
        val history = (1..10).map { HqTurn(role = "user", text = "turn $it") }

        repository(api).ask("ws-1", "Next", history)

        assertEquals(10, api.hqPrompts[0].history.size)
        assertEquals("ws-1", api.hqPrompts[0].workspaceId)
        assertEquals("Next", api.hqPrompts[0].prompt)
    }

    @Test
    fun `a 200 that does not affirm success is rejected as contract drift`() = runTest {
        // ⛔ An empty body decodes cleanly into every-field-defaulted, which reads as the console
        // answering with silence. See rejectedEnvelope.
        val api = FakeDistrictApi().apply {
            hqPromptResult = ApiResult.Success(HqPromptResponse())
        }

        val result = repository(api).ask("ws-1", "Anything", emptyList())

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a proposal is carried through intact`() = runTest {
        val api = FakeDistrictApi().apply {
            hqPromptResult = ApiResult.Success(
                HqPromptResponse(
                    success = true,
                    answer = "Proposed.",
                    needsConfirmation = true,
                    pendingWrite = proposal,
                ),
            )
        }

        val answer = (repository(api).ask("ws-1", "Delete Ada", emptyList()) as ApiResult.Success).value

        assertEquals(proposal, answer.pendingWrite)
    }

    @Test
    fun `needsConfirmation with nothing to confirm is a malformed response`() = runTest {
        // ⛔ THE FALLBACK IS THE WORST OPTION AVAILABLE. The model has just told the operator in
        // prose that it proposed a change; showing the answer and dropping the flag would offer no
        // way to apply it and no sign anything was missing, which reads as "done".
        val api = FakeDistrictApi().apply {
            hqPromptResult = ApiResult.Success(
                HqPromptResponse(success = true, answer = "Proposed.", needsConfirmation = true),
            )
        }

        val result = repository(api).ask("ws-1", "Delete Ada", emptyList())

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a pendingWrite without the flag is still treated as a proposal`() = runTest {
        // ⚠️ THE MIRROR CASE FAILS THE OTHER WAY DELIBERATELY. The payload is the substantive half
        // and the flag is a summary of it, so trusting the payload fails toward ASKING rather than
        // toward acting.
        val api = FakeDistrictApi().apply {
            hqPromptResult = ApiResult.Success(
                HqPromptResponse(success = true, answer = "Proposed.", pendingWrite = proposal),
            )
        }

        val answer = (repository(api).ask("ws-1", "Delete Ada", emptyList()) as ApiResult.Success).value

        assertEquals(proposal, answer.pendingWrite)
    }

    @Test
    fun `a transport failure is passed through untouched`() = runTest {
        val api = FakeDistrictApi().apply {
            hqPromptResult = ApiResult.RateLimited("Too quickly.")
        }

        val result = repository(api).ask("ws-1", "Anything", emptyList())

        assertEquals("Too quickly.", (result as ApiResult.RateLimited).message)
    }

    // ── Confirmation ─────────────────────────────────────────────────────────

    @Test
    fun `confirming sends the proposal back VERBATIM`() = runTest {
        // ⛔ The operator approved the sentence they read, so the applied action has to be the one
        // that sentence described — the arguments are echoed, never rebuilt.
        val api = FakeDistrictApi().apply {
            hqConfirmResult = ApiResult.Success(
                HqConfirmResponse(success = true, executed = true, tool = "delete_contact"),
            )
        }

        val result = repository(api).confirm("ws-1", proposal)

        assertEquals(1, api.hqConfirms.size)
        assertEquals("delete_contact", api.hqConfirms[0].confirm.tool)
        assertEquals(proposal.args, api.hqConfirms[0].confirm.args)
        assertTrue((result as ApiResult.Success).value.executed)
    }

    @Test
    fun `a declined write is reported as not executed rather than as a failure`() = runTest {
        // ⛔ success:true with executed:false means the request was handled and the WRITE was
        // declined — a view-only role, or a tool that refused internally. Neither "failed" nor
        // "done" is the truth.
        val api = FakeDistrictApi().apply {
            hqConfirmResult = ApiResult.Success(
                HqConfirmResponse(success = true, executed = false, tool = "delete_contact"),
            )
        }

        val confirmation = (repository(api).confirm("ws-1", proposal) as ApiResult.Success).value

        assertEquals(false, confirmation.executed)
        assertEquals("delete_contact", confirmation.tool)
    }

    @Test
    fun `a confirmation echoing a DIFFERENT tool is rejected`() = runTest {
        // ⛔ "We applied something, but not what you approved" has no honest UI, and treating it as
        // success is how a deletion gets attributed to a persona edit.
        val api = FakeDistrictApi().apply {
            hqConfirmResult = ApiResult.Success(
                HqConfirmResponse(success = true, executed = true, tool = "update_persona"),
            )
        }

        val result = repository(api).confirm("ws-1", proposal)

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a confirm envelope that does not affirm success is rejected`() = runTest {
        val api = FakeDistrictApi().apply {
            hqConfirmResult = ApiResult.Success(HqConfirmResponse())
        }

        val result = repository(api).confirm("ws-1", proposal)

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a 400 refusal is passed through rather than retried`() = runTest {
        // ⚠️ The server answers 400 for a tool that is not confirmable at all. Nothing here retries
        // it — a confirm is a write, and repeating one is the operator's decision.
        val api = FakeDistrictApi().apply {
            hqConfirmResult = ApiResult.HttpFailure(400, "That action can't be confirmed.")
        }

        val result = repository(api).confirm("ws-1", proposal)

        assertEquals(400, (result as ApiResult.HttpFailure).status)
        assertEquals(1, api.hqConfirms.size)
    }
}
