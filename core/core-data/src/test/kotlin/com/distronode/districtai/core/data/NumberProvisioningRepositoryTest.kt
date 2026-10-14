package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.A2PRegistrationResponse
import com.distronode.districtai.core.model.NumberConfigureResponse
import com.distronode.districtai.core.model.NumberLookupResponse
import com.distronode.districtai.core.model.NumberRegistrationCreatedResponse
import com.distronode.districtai.core.model.NumberRegistrationSubmitResponse
import com.distronode.districtai.core.model.NumberRegistrationsResponse
import com.distronode.districtai.core.model.NumberReleaseResponse
import com.distronode.districtai.core.model.NumberRequirementsResponse
import com.distronode.districtai.core.model.ProviderStatusResponse
import com.distronode.districtai.core.model.RegistrationDocumentRemovalResponse
import com.distronode.districtai.core.model.SipTrunkCreatedResponse
import com.distronode.districtai.core.model.SipTrunksResponse
import com.distronode.districtai.core.model.TollFreeVerificationResponse
import com.distronode.districtai.core.model.VerifyServiceResponse
import com.distronode.districtai.core.network.A2PRegistrationRequest
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.TollFreeVerificationRequest
import com.distronode.districtai.core.network.testing.FakeCarrierAccountApi
import com.distronode.districtai.core.network.testing.FakeNumberProvisioningApi
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberProvisioningRepositoryTest {

    private val numbers = FakeNumberProvisioningApi()
    private val carrier = FakeCarrierAccountApi()
    private val repository = NumberProvisioningRepository(numbers, carrier)
    private val a2p = A2PRegistrationRequest("Acme", "CUSTOMER_CARE", "d", "1", "2")
    private val tollFree = TollFreeVerificationRequest("+18005550100", "Acme", "Marketing", listOf("u"))

    @Test
    fun `every call reaches its route with its arguments`() = runTest {
        repository.providerStatus("ws")
        repository.requirements("ws", "DE", "mobile")
        repository.registrations("ws")
        repository.createRegistration("ws", "DE", "local", "Berlin")
        repository.removeDocument("ws", "BU1", "D1")
        repository.submitRegistration("ws", "BU1", mapOf("a" to "b"))
        repository.configure("ws", "+1")
        repository.release("ws", "+1")
        assertEquals(
            listOf(
                "providerStatus:ws",
                "numberRequirements:ws:DE:mobile:null",
                "numberRegistrations:ws",
                "createNumberRegistration:ws:DE:local:null:Berlin",
                "deleteRegistrationDocument:ws:BU1:D1",
                "submitNumberRegistration:ws:BU1:{a=b}",
                "configureNumber:ws:+1",
                "releaseNumber:ws:+1",
            ),
            numbers.calls,
        )
        repository.sipTrunks("ws")
        repository.createSipTrunk("ws", "PBX", "acme", listOf("1.2.3.4"))
        repository.verifyService("ws")
        repository.setVerifyEnabled("ws", true)
        repository.submitA2P("ws", a2p)
        repository.submitTollFree("ws", tollFree)
        repository.lookup("ws", "+1")
        assertEquals(
            listOf(
                "sipTrunks:ws",
                "createSipTrunk:ws:PBX:acme:[1.2.3.4]",
                "verifyService:ws",
                "setVerifyServiceEnabled:ws:true",
                "submitA2PRegistration:ws",
                "submitTollFreeVerification:ws",
                "lookupNumber:ws:+1",
            ),
            carrier.calls,
        )
    }

    @Test
    fun `an answer that does not affirm success is drift on every enveloped route`() = runTest {
        numbers.requirementsResult = ApiResult.Success(NumberRequirementsResponse(success = false))
        numbers.registrationsResult = ApiResult.Success(NumberRegistrationsResponse(success = false))
        numbers.createResult = ApiResult.Success(NumberRegistrationCreatedResponse(success = false))
        numbers.removeResult = ApiResult.Success(RegistrationDocumentRemovalResponse(success = false))
        numbers.submitResult = ApiResult.Success(NumberRegistrationSubmitResponse(success = false))
        numbers.configureResult = ApiResult.Success(NumberConfigureResponse(success = false))
        numbers.releaseResult = ApiResult.Success(NumberReleaseResponse(success = false))
        carrier.sipResult = ApiResult.Success(SipTrunksResponse(success = false))
        carrier.createSipResult = ApiResult.Success(SipTrunkCreatedResponse(success = false))
        carrier.verifyResult = ApiResult.Success(VerifyServiceResponse(success = false))
        carrier.setVerifyResult = ApiResult.Success(VerifyServiceResponse(success = false))
        carrier.a2pResult = ApiResult.Success(A2PRegistrationResponse(success = false))
        carrier.tollFreeResult = ApiResult.Success(TollFreeVerificationResponse(success = false))
        carrier.lookupResult = ApiResult.Success(NumberLookupResponse(success = false))
        listOf(
            repository.requirements("ws", "DE"),
            repository.registrations("ws"),
            repository.createRegistration("ws", "DE", null, null),
            repository.removeDocument("ws", "B", "D"),
            repository.submitRegistration("ws", "B", emptyMap()),
            repository.configure("ws", "+1"),
            repository.release("ws", "+1"),
            repository.sipTrunks("ws"),
            repository.createSipTrunk("ws", "n", "d", emptyList()),
            repository.verifyService("ws"),
            repository.setVerifyEnabled("ws", false),
            repository.submitA2P("ws", a2p),
            repository.submitTollFree("ws", tollFree),
            repository.lookup("ws", "+1"),
        ).forEach { assertTrue(it.toString(), it is ApiResult.DecodeFailure) }
        // Provider status has no envelope to affirm.
        assertEquals(ApiResult.Success(ProviderStatusResponse()), repository.providerStatus("ws"))
    }

    @Test
    fun `a failure passes through untouched`() = runTest {
        numbers.releaseResult = ApiResult.Forbidden("viewer")
        assertEquals(ApiResult.Forbidden("viewer"), repository.release("ws", "+1"))
    }

    @Test
    fun `an idempotent write may always be repeated`() {
        assertTrue(NumberWriteResubmit.allowed(ApiResult.NetworkFailure(IOException()), NumberWriteRepeat.IDEMPOTENT))
    }

    @Test
    fun `a once write is offered again only after a refusal before any work`() {
        val once = NumberWriteRepeat.ONCE
        assertTrue(NumberWriteResubmit.allowed(ApiResult.HttpFailure(400, "bad"), once))
        assertTrue(NumberWriteResubmit.allowed(ApiResult.HttpFailure(499, "bad"), once))
        assertTrue(NumberWriteResubmit.allowed(ApiResult.Unauthorized(null), once))
        assertTrue(NumberWriteResubmit.allowed(ApiResult.Forbidden("no"), once))
        assertTrue(NumberWriteResubmit.allowed(ApiResult.NotFound("no"), once))
        assertTrue(NumberWriteResubmit.allowed(ApiResult.RateLimited("slow"), once))
        assertFalse(NumberWriteResubmit.allowed(ApiResult.HttpFailure(500, "thrown"), once))
        assertFalse(NumberWriteResubmit.allowed(ApiResult.HttpFailure(399, "odd"), once))
        assertFalse(NumberWriteResubmit.allowed(ApiResult.RegionsDegraded("x", listOf("eu")), once))
        assertFalse(NumberWriteResubmit.allowed(ApiResult.NetworkFailure(IOException()), once))
        assertFalse(NumberWriteResubmit.allowed(ApiResult.DecodeFailure(IllegalStateException(), ""), once))
    }
}
