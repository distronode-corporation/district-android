package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallMonitorTokenResponse
import com.distronode.districtai.core.model.CallTakeoverResponse
import com.distronode.districtai.core.model.CallWhisperResponse
import com.distronode.districtai.core.model.E2eeInfo
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client guards on live supervise, and the proof that a failure is handed back once.
 *
 * ⛔ EVERY REFUSAL TEST ALSO COUNTS THE REQUESTS. A guard that refused after sending would still
 * start a `video_` avatar session; a repository that retried would still return the failure.
 */
class SuperviseRepositoryTest {

    private val api = FakeSuperviseApi()
    private val repository = SuperviseRepository(api)

    @Test
    fun `a listen grant is the route's url and token, verbatim`() = runTest {
        val outcome = repository.monitor("cmaicall1")

        assertEquals(SuperviseOutcome.Done(LiveCallGrant(FakeSuperviseApi.ROOM_URL, "monitor-jwt")), outcome)
        assertEquals(listOf("cmaicall1"), api.monitors)
    }

    @Test
    fun `a takeover grant is the route's url and token, verbatim`() = runTest {
        val outcome = repository.takeOver("ws-1", "cmaicall1")

        assertEquals(SuperviseOutcome.Done(LiveCallGrant(FakeSuperviseApi.ROOM_URL, "takeover-jwt")), outcome)
        assertEquals(listOf("ws-1/cmaicall1"), api.takeovers)
    }

    @Test
    fun `a whisper that the server affirms is done`() = runTest {
        assertEquals(SuperviseOutcome.Done(Unit), repository.whisper("ws-1", "cmaicall1", "Offer Thursday."))
        assertEquals(listOf("ws-1/cmaicall1/Offer Thursday."), api.whispers)
    }

    @Test
    fun `an id with an underscore is refused locally and nothing is sent`() = runTest {
        // ⛔ meet_ and video_ are ROOMS to calls/token, and a video_ join starts a billed avatar.
        listOf("video_ws-1_lobby", "meet_ws-1_standup", "direct_ws-1-CA01", "call_x", " ", "").forEach { id ->
            assertEquals(id, SuperviseOutcome.NotACall, repository.monitor(id))
            assertEquals(id, SuperviseOutcome.NotACall, repository.takeOver("ws-1", id))
            assertEquals(id, SuperviseOutcome.NotACall, repository.whisper("ws-1", id, "Offer Thursday."))
        }
        assertTrue(api.monitors.isEmpty())
        assertTrue(api.takeovers.isEmpty())
        assertTrue(api.whispers.isEmpty())
    }

    @Test
    fun `a supervisable id is a non-blank one without an underscore`() {
        assertTrue(SuperviseRepository.isSupervisableId("cmaicall1"))
        assertTrue(SuperviseRepository.isSupervisableId("CA-01"))
        assertFalse(SuperviseRepository.isSupervisableId("a_b"))
        assertFalse(SuperviseRepository.isSupervisableId(""))
    }

    @Test
    fun `a 200 that does not affirm success is a failure, never a grant`() = runTest {
        api.monitorResult = ApiResult.Success(CallMonitorTokenResponse(success = false, token = "t", url = "wss://x"))
        api.takeoverResult = ApiResult.Success(CallTakeoverResponse(success = false, token = "t", url = "wss://x"))
        api.whisperResult = ApiResult.Success(CallWhisperResponse(success = false))

        listOf(
            repository.monitor("cmaicall1"),
            repository.takeOver("ws-1", "cmaicall1"),
            repository.whisper("ws-1", "cmaicall1", "Offer Thursday."),
        ).forEach { outcome ->
            val failure = (outcome as SuperviseOutcome.Refused).failure
            assertTrue("drift, not a refusal sentence: $failure", failure is ApiResult.DecodeFailure)
        }
    }

    @Test
    fun `a grant with a blank token or url is a failure`() = runTest {
        val blanks = listOf(
            CallMonitorTokenResponse(success = true, token = "", url = "wss://x"),
            CallMonitorTokenResponse(success = true, token = "jwt-secret", url = " "),
            CallMonitorTokenResponse(success = true),
        )
        blanks.forEach { body ->
            api.monitorResult = ApiResult.Success(body)
            val failure = (repository.monitor("cmaicall1") as SuperviseOutcome.Refused).failure
            assertTrue(failure is ApiResult.DecodeFailure)
            assertFalse(
                "the preview never carries the token",
                (failure as ApiResult.DecodeFailure).bodyPreview.contains("jwt-secret")
            )
        }
        api.takeoverResult = ApiResult.Success(CallTakeoverResponse(success = true, token = "t", url = ""))
        assertTrue(
            (repository.takeOver("ws-1", "cmaicall1") as SuperviseOutcome.Refused).failure is ApiResult.DecodeFailure
        )
    }

    @Test
    fun `a refusal is handed back once, with its code, from one request`() = runTest {
        val ended = ApiResult.HttpFailure(409, "This call has ended.", "call_ended")
        val noReceptionist = ApiResult.HttpFailure(409, "A team member's own call.", "call_has_no_receptionist")
        api.monitorResult = ended
        api.takeoverResult = noReceptionist
        api.whisperResult = ApiResult.HttpFailure(500, "boom", "server_error")

        assertEquals(SuperviseOutcome.Refused(ended), repository.monitor("cmaicall1"))
        assertEquals(SuperviseOutcome.Refused(noReceptionist), repository.takeOver("ws-1", "cmteamcall1"))
        assertEquals(
            SuperviseOutcome.Refused(ApiResult.HttpFailure(500, "boom", "server_error")),
            repository.whisper("ws-1", "cmaicall1", "Offer Thursday."),
        )
        // ⛔ NEVER RETRIED: one request each.
        assertEquals(1, api.monitors.size)
        assertEquals(1, api.takeovers.size)
        assertEquals(1, api.whispers.size)
    }

    @Test
    fun `a grant never prints its token or its key`() {
        assertEquals(
            "LiveCallGrant(url=wss://x, token=<redacted>, e2eeKey=none)",
            LiveCallGrant("wss://x", "secret").toString(),
        )
        assertEquals(
            "LiveCallGrant(url=wss://x, token=<redacted>, e2eeKey=<redacted>)",
            LiveCallGrant("wss://x", "secret", "key-secret").toString(),
        )
    }

    @Test
    fun `a listen grant carries the room's key when the route sends one, and a blank key is none`() = runTest {
        api.monitorResult = ApiResult.Success(
            CallMonitorTokenResponse(success = true, token = "jwt", url = "wss://x", e2ee = E2eeInfo("room-key")),
        )
        val keyed = LiveCallGrant("wss://x", "jwt", "room-key")
        assertEquals(SuperviseOutcome.Done(keyed), repository.monitor("cmaicall1"))

        api.monitorResult = ApiResult.Success(
            CallMonitorTokenResponse(success = true, token = "jwt", url = "wss://x", e2ee = E2eeInfo(" ")),
        )
        assertEquals(SuperviseOutcome.Done(LiveCallGrant("wss://x", "jwt")), repository.monitor("cmaicall1"))
    }
}
