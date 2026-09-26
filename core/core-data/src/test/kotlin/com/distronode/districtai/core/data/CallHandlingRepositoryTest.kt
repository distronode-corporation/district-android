package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.AvailabilityReason
import com.distronode.districtai.core.model.AvailabilityResponse
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.CallHandlingResponse
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two routes, two scopes, and the refusals this layer makes before spending a request.
 */
class CallHandlingRepositoryTest {

    private fun repository(api: FakeCallHandlingApi) = CallHandlingRepository(api)

    @Test
    fun `the read is passed through without re-normalising what the server settled`() = runTest {
        // ⛔ THE SERVER ALREADY NORMALISED IT. An unrecognised stored mode reads back as `ai_first`
        // and an out-of-range window is clamped, so a second opinion here could only disagree.
        val api = FakeCallHandlingApi().apply {
            handlingResult = ApiResult.Success(
                CallHandlingResponse(success = true, callHandling = "app_first", appRingSeconds = 12),
            )
        }

        val result = repository(api).callHandling("ws-1")

        assertTrue(result is ApiResult.Success)
        assertEquals("app_first", (result as ApiResult.Success).value.callHandling)
        assertEquals(12, result.value.appRingSeconds)
    }

    @Test
    fun `a save with nothing to change never reaches the network`() = runTest {
        // ⛔ THE ROUTE ANSWERS "Nothing to update" WITH A 400. Spending a request and a rate-limit
        // token to be told that is a worse answer than declining to make it.
        val api = FakeCallHandlingApi()

        val result = repository(api).saveCallHandling("ws-1")

        assertTrue(result is ApiResult.DecodeFailure)
        assertTrue("no request may be sent", api.handlingWrites.isEmpty())
    }

    @Test
    fun `an unknown mode is refused locally rather than sent to be rejected`() = runTest {
        // ⛔ AN UNKNOWN MODE CAN ONLY HAVE COME FROM THIS APP'S OWN CODE. The route answers 400 for
        // it, which is an error the operator cannot act on.
        val api = FakeCallHandlingApi()

        val result = repository(api).saveCallHandling("ws-1", callHandling = "ai_maybe")

        assertTrue(result is ApiResult.DecodeFailure)
        assertTrue(api.handlingWrites.isEmpty())
    }

    @Test
    fun `an out-of-range ring window IS sent, deliberately`() = runTest {
        // ⛔ NOT CLAMPED HERE, AND THAT IS THE ASYMMETRY WORTH PINNING. An unknown MODE is this
        // app's bug; a ring outside the bounds is a control that should have constrained it, and
        // silently clamping would save a number the operator did not choose. The screen clamps its
        // slider; this layer reports what the server says.
        val api = FakeCallHandlingApi()

        repository(api).saveCallHandling("ws-1", appRingSeconds = 90)

        assertEquals(listOf(null to 90), api.handlingWrites)
    }

    @Test
    fun `only the field that changed is forwarded`() = runTest {
        val api = FakeCallHandlingApi()

        repository(api).saveCallHandling("ws-1", callHandling = CallHandling.APP_FIRST)

        assertEquals(listOf(CallHandling.APP_FIRST to null), api.handlingWrites)
    }

    @Test
    fun `a viewer's availability answer is a success carrying its reason`() = runTest {
        // ⚠️ A 200 WITH A REASON IS A REAL ANSWER, NOT A REFUSAL. The route never 403s for a role,
        // so a screen must render the reason instead of assuming a 200 carries a toggleable value.
        val api = FakeCallHandlingApi().apply {
            availabilityResult = ApiResult.Success(
                AvailabilityResponse(
                    success = true,
                    availableForCalls = false,
                    reason = AvailabilityReason.ROLE,
                ),
            )
        }

        val result = repository(api).availability("ws-1")

        assertTrue(result is ApiResult.Success)
        assertEquals(AvailabilityReason.ROLE, (result as ApiResult.Success).value.reason)
    }

    @Test
    fun `a 409 for a missing member row stays a failure`() = runTest {
        // ⛔ THE TOGGLE DID NOT TAKE EFFECT. Reporting it as success because the reason is
        // understandable would leave a switch showing a state the ring fan-out does not share.
        val api = FakeCallHandlingApi().apply {
            availabilityResult = ApiResult.HttpFailure(
                status = 409,
                message = "You have no membership row in this workspace to set availability on.",
            )
        }

        val result = repository(api).saveAvailability("ws-1", availableForCalls = true)

        assertTrue(result is ApiResult.HttpFailure)
        assertEquals(409, (result as ApiResult.HttpFailure).status)
    }

    @Test
    fun `a 200 on either route that does not affirm success is drift, read or write`() = runTest {
        val api = FakeCallHandlingApi().apply {
            handlingResult = ApiResult.Success(CallHandlingResponse(success = false, callHandling = "ai_first"))
            availabilityResult = ApiResult.Success(AvailabilityResponse(success = false, availableForCalls = true))
        }
        val repository = repository(api)

        assertTrue(repository.callHandling("ws-1") is ApiResult.DecodeFailure)
        assertTrue(repository.saveCallHandling("ws-1", appRingSeconds = 20) is ApiResult.DecodeFailure)
        assertTrue(repository.availability("ws-1") is ApiResult.DecodeFailure)
        assertTrue(repository.saveAvailability("ws-1", availableForCalls = true) is ApiResult.DecodeFailure)
    }

    @Test
    fun `a transport failure on either route is passed through unchanged`() = runTest {
        val offline = ApiResult.NetworkFailure(IOException("offline"))
        val api = FakeCallHandlingApi().apply {
            handlingResult = offline
            availabilityResult = offline
        }
        val repository = repository(api)

        assertEquals(offline, repository.callHandling("ws-1"))
        assertEquals(offline, repository.saveCallHandling("ws-1", callHandling = CallHandling.AI_FIRST))
        assertEquals(offline, repository.availability("ws-1"))
        assertEquals(offline, repository.saveAvailability("ws-1", availableForCalls = false))
    }

    @Test
    fun `a false availability reaches the api as false`() = runTest {
        val api = FakeCallHandlingApi()

        repository(api).saveAvailability("ws-1", availableForCalls = false)

        assertEquals(listOf(false), api.availabilityWrites)
    }
}
