package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallAnswerResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answering a ringing call, and the four ways it does not happen.
 *
 * ⛔ THE ASSERTION THAT MATTERS MOST IS THE ONE ABOUT **404 AND 409 BEING THE SAME ANSWER**. Both
 * mean the call ended while the phone was ringing — which is the ordinary outcome of a caller
 * hanging up, not a fault — and a screen that said "could not answer" there would blame the operator
 * for somebody else's decision. On a ringing screen that is the difference between "you missed it"
 * and "the app is broken".
 */
class InboundCallRepositoryTest {

    private fun api(result: ApiResult<CallAnswerResponse>) = FakeDistrictApi().apply {
        pushApi.answerResult = result
    }

    private fun joinable() = ApiResult.Success(
        CallAnswerResponse(
            success = true,
            url = "wss://livekit-wss.distronode.com",
            token = "join-jwt",
            roomName = "call_ws-1_CA1",
        ),
    )

    @Test
    fun `an answered call carries the credential through verbatim`() = runTest {
        // ⛔ VERBATIM IS THE WHOLE CONTRACT. The room lives on the deployment that created it — for
        // an inbound call, the US SIP bridge, regardless of the workspace's own region — and the
        // server resolved that BY ROOM precisely because an EU tenant has rooms on both buses. A
        // client that rewrote either field would join an empty room of the same name on the wrong
        // bus and sit in it alone while the caller waited.
        val fake = api(joinable())

        val outcome = InboundCallRepository(fake).answer("ws-1", "CA1")

        assertTrue(outcome is AnswerOutcome.Joinable)
        val session = (outcome as AnswerOutcome.Joinable).session
        assertEquals("wss://livekit-wss.distronode.com", session.url)
        assertEquals("join-jwt", session.token)
        assertEquals(listOf("CA1/ws-1"), fake.pushApi.answerRequests)
    }

    @Test
    fun `a 404 is the call having ended, not an error`() = runTest {
        // ⚠️ THE SERVER ANSWERS 404 FOR A CALL ID THIS WORKSPACE CANNOT SEE, deliberately, so that
        // another tenant's id is indistinguishable from one that does not exist. That makes "wrong
        // id" and "gone" one answer by design, and the only honest rendering of it is the ordinary
        // one: the call ended.
        val outcome = InboundCallRepository(api(ApiResult.NotFound("Call not found")))
            .answer("ws-1", "CA1")

        assertEquals(AnswerOutcome.AlreadyEnded, outcome)
    }

    @Test
    fun `a 409 is also the call having ended`() = runTest {
        // ⚠️ 409 is a status that is no longer answerable, or a call that never had a room. Same
        // sentence on screen as the 404; matching it in a ViewModel instead would put the same
        // decision in two places.
        val outcome = InboundCallRepository(
            api(ApiResult.HttpFailure(status = 409, message = "Call is not answerable")),
        ).answer("ws-1", "CA1")

        assertEquals(AnswerOutcome.AlreadyEnded, outcome)
    }

    @Test
    fun `a viewer's 403 is a real refusal and keeps the server's own wording`() = runTest {
        // ⛔ A VIEWER'S PHONE GENUINELY RINGS. The server fans a push out to every registered device
        // in the workspace without consulting roles, so this is reachable by an ordinary user rather
        // than by a broken client — and it is NOT "the call ended", because the call is still live
        // and somebody else can take it.
        val failure = ApiResult.Forbidden("Forbidden")

        val outcome = InboundCallRepository(api(failure)).answer("ws-1", "CA1")

        assertEquals(AnswerOutcome.NotAnswered(failure), outcome)
    }

    @Test
    fun `a 200 with no credential is refused rather than joined`() = runTest {
        // ⛔ EVERY FIELD OF THE DTO DEFAULTS, so a `{}` body decodes into a well-formed response
        // holding an empty token and an empty URL. Joining on it would surface at `engine.connect`
        // as a media-plane error — on a screen already showing a connected call — for what was
        // really a server refusal.
        val outcome = InboundCallRepository(
            api(ApiResult.Success(CallAnswerResponse(success = true, url = "", token = ""))),
        ).answer("ws-1", "CA1")

        assertTrue(outcome is AnswerOutcome.NotAnswered)
        assertTrue(
            (outcome as AnswerOutcome.NotAnswered).failure is ApiResult.DecodeFailure,
        )
    }

    @Test
    fun `a 5xx is a real failure rather than the call having ended`() = runTest {
        // ⚠️ Only 404 and 409 mean "over". A server fault says nothing about the call, which may
        // still be ringing for somebody else.
        val failure = ApiResult.HttpFailure(status = 503, message = "Calling service is not configured.")

        val outcome = InboundCallRepository(api(failure)).answer("ws-1", "CA1")

        assertEquals(AnswerOutcome.NotAnswered(failure), outcome)
    }

    @Test
    fun `a 200 missing only one half of the credential is refused, and says which half`() = runTest {
        val noUrl = InboundCallRepository(
            api(ApiResult.Success(CallAnswerResponse(success = true, url = "", token = "join-jwt"))),
        ).answer("ws-1", "CA1")
        val noToken = InboundCallRepository(
            api(ApiResult.Success(CallAnswerResponse(success = true, url = "wss://x", token = ""))),
        ).answer("ws-1", "CA1")

        val urlFailure = (noUrl as AnswerOutcome.NotAnswered).failure as ApiResult.DecodeFailure
        val tokenFailure = (noToken as AnswerOutcome.NotAnswered).failure as ApiResult.DecodeFailure
        assertTrue(urlFailure.bodyPreview.contains("token=true,url=false"))
        assertTrue(tokenFailure.bodyPreview.contains("token=false,url=true"))
    }

    @Test
    fun `a 200 that does not affirm success is refused`() = runTest {
        val outcome = InboundCallRepository(
            api(
                ApiResult.Success(
                    CallAnswerResponse(success = false, url = "wss://x", token = "t"),
                ),
            ),
        ).answer("ws-1", "CA1")

        assertTrue(outcome is AnswerOutcome.NotAnswered)
    }

    @Test
    fun `a call_ room is accepted, unlike the dial path's direct_ requirement`() = runTest {
        // ⛔ THE ABSENCE OF A PREFIX CHECK IS DELIBERATE AND IS PINNED HERE SO NOBODY "RESTORES" IT.
        // `DialRepository` refuses anything that is not `direct_` because that prefix is what keeps
        // the AI off a room this app created. An ANSWERED call joins the room the SIP bridge already
        // made, which the agent is in and is supposed to be in — the handoff metadata inside the
        // token is what tells it to step back. A prefix check here would refuse every real answer.
        val outcome = InboundCallRepository(api(joinable())).answer("ws-1", "CA1")

        assertTrue(outcome is AnswerOutcome.Joinable)
        assertTrue(
            (outcome as AnswerOutcome.Joinable).session.roomName.startsWith("call_"),
        )
    }

    @Test
    fun `nothing is sent until answer is called, and then exactly once`() = runTest {
        // ⛔ THIS CALL WRITES THE SERVER'S RENDEZVOUS, which is what releases the agent's transfer.
        // Calling it to pre-warm a credential would tell the agent a human took the call while the
        // phone was still ringing in a pocket, and the caller would be handed to nobody.
        val fake = api(joinable())
        val repository = InboundCallRepository(fake)

        assertEquals(emptyList<String>(), fake.pushApi.answerRequests)
        repository.answer("ws-1", "CA1")

        assertEquals(1, fake.pushApi.answerRequests.size)
    }
}
