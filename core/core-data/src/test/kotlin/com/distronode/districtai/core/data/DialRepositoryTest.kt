package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CODE_OVERAGE_CAP_REACHED
import com.distronode.districtai.core.model.CODE_SUBSCRIPTION_INACTIVE
import com.distronode.districtai.core.model.DialResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one repository whose mistakes cost money.
 *
 * ⛔ THE FOUR THINGS UNDER TEST, AND NONE OF THEM IS "DOES IT CALL THE API".
 *  1. A 200 that is not a usable call must not be reported as one. Every field of [DialResponse]
 *     defaults, so a `{}` body decodes into a well-formed response with an empty token and an
 *     empty URL — which would surface at `engine.connect` as a media-plane error for what was
 *     really a server refusal, on a call that HAS been placed and IS being billed.
 *  2. A `call_` room must be refused rather than joined. It is one character from `direct_`, and
 *     the voice agent refuses only the latter by name — so a `call_` room means an AI is being
 *     dispatched into a human's conversation.
 *  3. The two coded refusals must stay distinct, because their remedies are opposite.
 *  4. The DNC refusal must arrive whole, because its SENTENCE is the only thing that carries it.
 */
class DialRepositoryTest {

    private val api = FakeDistrictApi()
    private val repository = DialRepository(api)

    private fun placed(
        callId: String = "CA0123456789abcdef0123456789abcdef",
        roomName: String = "direct_ws-1-CA0123456789abcdef0123456789abcdef",
        token: String = "jwt-dial",
        url: String = "wss://livekit.test",
    ) {
        api.dialResult = ApiResult.Success(
            DialResponse(
                success = true,
                callId = callId,
                roomName = roomName,
                token = token,
                url = url,
            ),
        )
    }

    @Test
    fun `a usable response is placed, and the number travels exactly as typed`() = runTest {
        placed()

        val outcome = repository.dial("ws-1", " (416) 555-0100 ")

        val session = (outcome as DialOutcome.Placed).session
        assertEquals("jwt-dial", session.token)
        assertEquals("wss://livekit.test", session.url)
        // ⛔ NOT NORMALISED HERE, AND THAT IS THE POINT. The server runs its own
        // `normalizePhoneNumber` and then checks DNC, resolves the Contact and dials against THAT
        // one form. A client-side canonicaliser that disagreed by a character would place a call
        // the compliance check never saw.
        assertEquals(" (416) 555-0100 ", api.dialRequests.single().to)
        assertEquals("ws-1", api.dialRequests.single().workspaceId)
    }

    @Test
    fun `an empty-object 200 is not reported as a placed call`() = runTest {
        // The literal `{}` body: `success=false` and every credential blank.
        api.dialResult = ApiResult.Success(DialResponse())

        val outcome = repository.dial("ws-1", "+14165550100")

        // ⚠️ DecodeFailure, not a generic failure: a 200 that does not affirm success is contract
        // drift, which is what that case means and what should be noisy in a debug build.
        val failure = (outcome as DialOutcome.NotPlaced).failure
        assertTrue(failure is ApiResult.DecodeFailure)
    }

    @Test
    fun `a success with no credential is refused rather than handed to the engine`() = runTest {
        // ⛔ THE DANGEROUS SHAPE: the envelope affirms success, so the usual guard passes, and the
        // call HAS been placed — but there is nothing to join with. Without this check the failure
        // appears as a LiveKit connection error, which reads as a media-plane fault rather than as
        // a malformed response, on a call the workspace is already paying for.
        placed(token = "", url = "")

        val outcome = repository.dial("ws-1", "+14165550100")

        val failure = (outcome as DialOutcome.NotPlaced).failure as ApiResult.DecodeFailure
        assertTrue(failure.bodyPreview.contains("token=false"))
        assertTrue(failure.bodyPreview.contains("url=false"))
    }

    @Test
    fun `a credential missing only one half is refused, and the preview names that half`() = runTest {
        placed(url = "")
        val noUrl = (repository.dial("ws-1", "+14165550100") as DialOutcome.NotPlaced).failure
        assertTrue((noUrl as ApiResult.DecodeFailure).bodyPreview.contains("token=true,url=false"))

        placed(token = "")
        val noToken = (repository.dial("ws-1", "+14165550100") as DialOutcome.NotPlaced).failure
        assertTrue((noToken as ApiResult.DecodeFailure).bodyPreview.contains("token=false,url=true"))
    }

    @Test
    fun `a call_ room is refused, because the AI joins those`() = runTest {
        // ⛔ ONE CHARACTER OF DIFFERENCE AND AN ENTIRELY DIFFERENT CALL. `request_fnc` in the voice
        // agent refuses `direct_` BY NAME; a `call_` room is one the agent joins and speaks in.
        // Nothing client-side can undo that — the room already exists — but joining it silently
        // would make the operator the last to know an AI is on their call.
        placed(roomName = "call_ws-1-CA0123456789abcdef0123456789abcdef")

        val outcome = repository.dial("ws-1", "+14165550100")

        val failure = (outcome as DialOutcome.NotPlaced).failure as ApiResult.DecodeFailure
        // ⚠️ The preview carries the PREFIX only. The rest of the name embeds the workspace id and
        // the call id, and a diagnostic string is not a place to put either.
        assertTrue(failure.bodyPreview.contains("call_…"))
        assertTrue("the preview must not carry the workspace", !failure.bodyPreview.contains("ws-1"))
    }

    @Test
    fun `the two coded refusals map to their own outcomes and never to each other`() = runTest {
        api.dialResult = ApiResult.HttpFailure(
            status = PAYMENT_REQUIRED,
            message = "This workspace's subscription is not active.",
            code = CODE_SUBSCRIPTION_INACTIVE,
        )
        assertEquals(DialOutcome.SubscriptionInactive, repository.dial("ws-1", "+14165550100"))

        api.dialResult = ApiResult.HttpFailure(
            status = CONFLICT,
            message = "This workspace has used all of its included voice minutes.",
            code = CODE_OVERAGE_CAP_REACHED,
        )
        assertEquals(DialOutcome.OverageCapReached, repository.dial("ws-1", "+14165550100"))
    }

    @Test
    fun `an unrecognised code falls through with the server's own sentence`() = runTest {
        // ⚠️ The fall-through is what keeps a NEW server code from becoming a blank screen: the
        // client shows the operator-facing sentence, which is more specific than anything it could
        // invent, until somebody adds the branch.
        val failure = ApiResult.HttpFailure(
            status = CONFLICT,
            message = "Something new the server started refusing.",
            code = "some_future_code",
        )
        api.dialResult = failure

        val outcome = repository.dial("ws-1", "+14165550100")

        assertSame(failure, (outcome as DialOutcome.NotPlaced).failure)
    }

    @Test
    fun `the DNC refusal arrives whole, because its sentence is all there is`() = runTest {
        // ⛔ THE SERVER SENDS NO `code` ON THIS ONE, so it is structurally identical to the ROLE
        // refusal the same route emits earlier. The client cannot tell them apart without matching
        // English, which is precisely what this repository refuses to do — so the outcome is the
        // generic one and the server's sentence is what reaches the operator. If a `code` is ever
        // added server-side, this test is where the branch becomes possible.
        val failure = ApiResult.Forbidden(
            "This number has opted out of calls from this workspace (DNC).",
        )
        api.dialResult = failure

        val outcome = repository.dial("ws-1", "+14165550100")

        val notPlaced = outcome as DialOutcome.NotPlaced
        assertSame(failure, notPlaced.failure)
        assertTrue((notPlaced.failure as ApiResult.Forbidden).message.contains("opted out"))
    }

    @Test
    fun `a transport failure is forwarded rather than reinterpreted`() = runTest {
        val failure = ApiResult.NetworkFailure(java.io.IOException("no route to host"))
        api.dialResult = failure

        val outcome = repository.dial("ws-1", "+14165550100")

        assertSame(failure, (outcome as DialOutcome.NotPlaced).failure)
        // ⚠️ ONE ATTEMPT. Nothing in this layer retries, and nothing may: the row is written and
        // the carrier instructed before the response exists, so a re-send is a second call.
        assertEquals(1, api.dialRequests.size)
    }

    private companion object {
        /** ⚠️ Named because detekt's MagicNumber counts a bare status code as one. */
        const val PAYMENT_REQUIRED = 402
        const val CONFLICT = 409
    }
}
