package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.ConnectionsResponse
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.ForwardingCheckResponse
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.model.SetupTestCallResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SetupWizardApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

/** One `PATCH /api/district/setup` as sent. */
data class FakeSetupPatch(val workspaceId: String, val steps: Map<String, String>, val businessFacts: JsonObject?)

/**
 * The setup wizard's routes, canned, with every request recorded.
 *
 * ⚠️ A QUEUE PER READ, THEN THE LAST VALUE: a test of "read again after the page closed" queues the
 * second answer, and a test that does not care sets one value for every read.
 *
 * ⚠️ [gate] HOLDS EVERY CALL UNTIL IT IS COMPLETED, so a test can tap twice while the first write is
 * still in flight and see the second one refused.
 */
class FakeSetupWizardApi : SetupWizardApi {

    var setupResult: ApiResult<DistrictSetupResponse> = ApiResult.Success(DistrictSetupResponse())
    val setupQueue: ArrayDeque<ApiResult<DistrictSetupResponse>> = ArrayDeque()
    var patchResult: ApiResult<DistrictSetupResponse> = ApiResult.Success(DistrictSetupResponse())
    val patchQueue: ArrayDeque<ApiResult<DistrictSetupResponse>> = ArrayDeque()
    var lookupResult: ApiResult<SetupLookupResponse> = ApiResult.Success(SetupLookupResponse())
    var testCallResult: ApiResult<SetupTestCallResponse> =
        ApiResult.Success(SetupTestCallResponse(success = true, callId = "CA1"))
    var forwardingResult: ApiResult<ForwardingCheckResponse> = ApiResult.Success(ForwardingCheckResponse())
    val forwardingQueue: ArrayDeque<ApiResult<ForwardingCheckResponse>> = ArrayDeque()
    var forwardingStartResult: ApiResult<ForwardingCheckResponse> = ApiResult.Success(ForwardingCheckResponse())
    var connectionsResult: ApiResult<ConnectionsResponse> = ApiResult.Success(ConnectionsResponse())
    val connectionsQueue: ArrayDeque<ApiResult<ConnectionsResponse>> = ArrayDeque()

    var gate: CompletableDeferred<Unit>? = null

    val reads = mutableListOf<String>()
    val patches = mutableListOf<FakeSetupPatch>()
    val lookups = mutableListOf<Pair<String, String>>()
    val testCalls = mutableListOf<String>()
    val forwardingReads = mutableListOf<String>()
    val forwardingStarts = mutableListOf<Pair<String, String>>()
    val connectionReads = mutableListOf<String>()

    private suspend fun held() {
        gate?.await()
    }

    override suspend fun districtSetup(workspaceId: String): ApiResult<DistrictSetupResponse> {
        reads += workspaceId
        held()
        return setupQueue.removeFirstOrNull() ?: setupResult
    }

    override suspend fun districtSetupPatch(
        workspaceId: String,
        steps: Map<String, String>,
        businessFacts: JsonObject?,
    ): ApiResult<DistrictSetupResponse> {
        patches += FakeSetupPatch(workspaceId, steps, businessFacts)
        held()
        return patchQueue.removeFirstOrNull() ?: patchResult
    }

    override suspend fun setupLookup(
        workspaceId: String,
        websiteUrl: String,
        locale: String,
    ): ApiResult<SetupLookupResponse> {
        lookups += websiteUrl to locale
        held()
        return lookupResult
    }

    override suspend fun setupTestCall(workspaceId: String, phone: String): ApiResult<SetupTestCallResponse> {
        testCalls += phone
        held()
        return testCallResult
    }

    override suspend fun forwardingCheck(workspaceId: String): ApiResult<ForwardingCheckResponse> {
        forwardingReads += workspaceId
        held()
        return forwardingQueue.removeFirstOrNull() ?: forwardingResult
    }

    override suspend fun forwardingCheckStart(
        workspaceId: String,
        businessLine: String,
        mode: String,
    ): ApiResult<ForwardingCheckResponse> {
        forwardingStarts += businessLine to mode
        held()
        return forwardingStartResult
    }

    override suspend fun workspaceConnections(workspaceId: String): ApiResult<ConnectionsResponse> {
        connectionReads += workspaceId
        held()
        return connectionsQueue.removeFirstOrNull() ?: connectionsResult
    }
}
