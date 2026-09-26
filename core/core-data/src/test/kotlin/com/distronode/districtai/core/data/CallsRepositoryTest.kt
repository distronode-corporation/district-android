package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallDetailResponse
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.model.CallTranscriptResponse
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One call's detail and its transcript.
 *
 * ⚠️ BOTH ENVELOPES DEFAULT `success` TO FALSE, so a `{}` body decodes; what this pins is that such a
 * body, or one that says `success:false` outright, never reaches a screen as a call or as an empty
 * transcript, and that a transport failure is passed through rather than reinterpreted.
 */
class CallsRepositoryTest {

    private val api = FakeDistrictApi()
    private val repository = CallsRepository(api)

    private val call = CallSummary(
        id = "call-1",
        type = "inbound",
        number = "+14165550100",
        status = "completed",
        duration = "1m 5s",
        time = "Aug 15, 02:30 PM",
        aiSummary = "",
        hasTranscript = true,
        callerName = "Ada",
        summary = "",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    @Test
    fun `a call's detail is the call the server affirmed`() = runTest {
        api.detailResult = ApiResult.Success(CallDetailResponse(success = true, call = call))

        assertEquals(ApiResult.Success(call), repository.detail("ws-1", "call-1"))
    }

    @Test
    fun `a detail that does not affirm success, or affirms it with no call, is drift`() = runTest {
        api.detailResult = ApiResult.Success(CallDetailResponse(success = false, call = call))
        assertTrue(repository.detail("ws-1", "call-1") is ApiResult.DecodeFailure)

        // ⛔ Absence is a 404. A 2xx with no call is a malformed answer, never an empty state.
        api.detailResult = ApiResult.Success(CallDetailResponse(success = true))
        assertTrue(repository.detail("ws-1", "call-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a transcript is the server's text, and an unaffirmed one is drift rather than empty`() = runTest {
        api.transcriptResult = ApiResult.Success(CallTranscriptResponse(success = true, transcript = "Hello"))
        assertEquals(ApiResult.Success("Hello"), repository.transcript("ws-1", "call-1"))

        api.transcriptResult = ApiResult.Success(CallTranscriptResponse(success = false, transcript = "Hello"))
        assertTrue(repository.transcript("ws-1", "call-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a failed detail or transcript read is passed through unchanged`() = runTest {
        val offline = ApiResult.NetworkFailure(IOException("offline"))
        api.detailResult = offline
        api.transcriptResult = offline

        assertEquals(offline, repository.detail("ws-1", "call-1"))
        assertEquals(offline, repository.transcript("ws-1", "call-1"))
    }
}
