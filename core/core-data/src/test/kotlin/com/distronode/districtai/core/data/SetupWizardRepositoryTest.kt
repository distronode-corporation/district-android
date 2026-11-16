package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.ConnectionsResponse
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.ForwardingCheckResponse
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.model.SetupProgress
import com.distronode.districtai.core.model.SetupTestCallResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeSetupPatch
import com.distronode.districtai.core.network.testing.FakeSetupWizardApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's repository: every call reaches its route unchanged, and an owner-only refusal keeps
 * its status rather than turning into "nothing to show" as the overview's card does.
 */
class SetupWizardRepositoryTest {

    private val api = FakeSetupWizardApi()
    private val repository = SetupWizardRepository(api)

    @Test
    fun `a member's refusal reaches the wizard as Forbidden`() = runTest {
        api.setupResult = ApiResult.Forbidden("Owner only", code = "workspace_owner_only")

        assertEquals(ApiResult.Forbidden("Owner only", code = "workspace_owner_only"), repository.load("ws-1"))
        assertEquals(listOf("ws-1"), api.reads)
    }

    @Test
    fun `a save passes its steps and facts through and answers the new state`() = runTest {
        val after = DistrictSetupResponse(setupProgress = SetupProgress())
        api.patchResult = ApiResult.Success(after)
        val facts = buildJsonObject { put("timezone", "America/Toronto") }

        assertEquals(ApiResult.Success(after), repository.save("ws-1", mapOf("number" to "done"), facts))
        assertEquals(ApiResult.Success(after), repository.save("ws-1"))

        assertEquals(
            listOf(
                FakeSetupPatch("ws-1", mapOf("number" to "done"), facts),
                FakeSetupPatch("ws-1", emptyMap(), null),
            ),
            api.patches,
        )
    }

    @Test
    fun `the lookup, the forwarding reads and the connections pass through`() = runTest {
        val lookup = SetupLookupResponse()
        val forwarding = ForwardingCheckResponse(available = true, attemptsLeft = 3)
        val connections = ConnectionsResponse()
        api.lookupResult = ApiResult.Success(lookup)
        api.forwardingResult = ApiResult.Success(forwarding)
        api.forwardingStartResult = ApiResult.Success(forwarding)
        api.connectionsResult = ApiResult.Success(connections)

        assertEquals(ApiResult.Success(lookup), repository.lookUp("ws-1", "example.com", "fr"))
        assertEquals(ApiResult.Success(forwarding), repository.forwardingCheck("ws-1"))
        assertEquals(ApiResult.Success(forwarding), repository.startForwardingCheck("ws-1", "+14165550123", "all"))
        assertEquals(ApiResult.Success(connections), repository.connections("ws-1"))

        assertEquals(listOf("example.com" to "fr"), api.lookups)
        assertEquals(listOf("+14165550123" to "all"), api.forwardingStarts)
        assertEquals(listOf("ws-1"), api.connectionReads)
    }

    @Test
    fun `the test call answers the call's id`() = runTest {
        api.testCallResult = ApiResult.Success(SetupTestCallResponse(success = true, callId = "CA9"))

        assertEquals(ApiResult.Success("CA9"), repository.placeTestCall("ws-1", "+14165550123"))
        assertEquals(listOf("+14165550123"), api.testCalls)
    }

    @Test
    fun `a test call the body does not affirm is drift, not a call`() = runTest {
        api.testCallResult = ApiResult.Success(SetupTestCallResponse(success = false, callId = "CA9"))
        assertTrue(repository.placeTestCall("ws-1", "+1") is ApiResult.DecodeFailure)

        api.testCallResult = ApiResult.Success(SetupTestCallResponse(success = true, callId = ""))
        assertTrue(repository.placeTestCall("ws-1", "+1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a refused test call keeps the service's answer`() = runTest {
        api.testCallResult = ApiResult.RateLimited("Too many test calls today.")

        assertTrue(repository.placeTestCall("ws-1", "+1") is ApiResult.RateLimited)
    }
}
