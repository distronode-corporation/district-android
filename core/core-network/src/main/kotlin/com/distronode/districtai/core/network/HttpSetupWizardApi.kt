package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.ConnectionsResponse
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.ForwardingCheckResponse
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.model.SetupTestCallResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [SetupWizardApi] over [DistrictApiClient].
 *
 * ⛔ THE BODIES ARE BUILT AS JSON, NOT FROM A DTO, because the facts PATCH needs both an ABSENT
 * section (left alone) and an explicit `null` one (cleared), and the client's encoder drops nulls
 * from a class (`explicitNulls = false`). A [JsonObject] keeps every `JsonNull` it is given.
 */
class HttpSetupWizardApi(private val client: DistrictApiClient) : SetupWizardApi {

    private val read = HttpSetupApi(client)

    override suspend fun districtSetup(workspaceId: String): ApiResult<DistrictSetupResponse> =
        read.districtSetup(workspaceId)

    override suspend fun districtSetupPatch(
        workspaceId: String,
        steps: Map<String, String>,
        businessFacts: JsonObject?,
    ): ApiResult<DistrictSetupResponse> =
        client.send(
            method = "PATCH",
            segments = SETUP_PATH,
            serializer = DistrictSetupResponse.serializer(),
            body = buildJsonObject {
                put("workspaceId", workspaceId)
                if (steps.isNotEmpty()) {
                    put(
                        "setupProgress",
                        buildJsonObject {
                            put("steps", buildJsonObject { steps.forEach { (step, state) -> put(step, state) } })
                        },
                    )
                }
                if (businessFacts != null) put("businessFacts", businessFacts)
            },
        )

    override suspend fun setupLookup(
        workspaceId: String,
        websiteUrl: String,
        locale: String,
    ): ApiResult<SetupLookupResponse> =
        client.send(
            method = "POST",
            segments = SETUP_PATH + "lookup",
            serializer = SetupLookupResponse.serializer(),
            body = buildJsonObject {
                put("workspaceId", workspaceId)
                // ⚠️ THE ROUTE KNOWS TWO: anything that is not French drafts in English.
                put("locale", if (locale == LOOKUP_FRENCH) LOOKUP_FRENCH else LOOKUP_ENGLISH)
                put("websiteUrl", websiteUrl)
            },
        )

    override suspend fun setupTestCall(workspaceId: String, phone: String): ApiResult<SetupTestCallResponse> =
        client.send(
            method = "POST",
            segments = SETUP_PATH + "test-call",
            serializer = SetupTestCallResponse.serializer(),
            body = buildJsonObject {
                put("workspaceId", workspaceId)
                put("phone", phone)
            },
        )

    override suspend fun forwardingCheck(workspaceId: String): ApiResult<ForwardingCheckResponse> =
        client.get(
            segments = FORWARDING_CHECK_PATH,
            serializer = ForwardingCheckResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun forwardingCheckStart(
        workspaceId: String,
        businessLine: String,
        mode: String,
    ): ApiResult<ForwardingCheckResponse> =
        client.send(
            method = "POST",
            segments = FORWARDING_CHECK_PATH,
            serializer = ForwardingCheckResponse.serializer(),
            body = buildJsonObject {
                put("workspaceId", workspaceId)
                put("businessLine", businessLine)
                put("mode", mode)
            },
        )

    override suspend fun workspaceConnections(workspaceId: String): ApiResult<ConnectionsResponse> =
        client.get(
            segments = ApiRoots.DISTRICT + "connections",
            serializer = ConnectionsResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    private companion object {
        val SETUP_PATH: List<String> = ApiRoots.DISTRICT + "setup"
        val FORWARDING_CHECK_PATH: List<String> = SETUP_PATH + "forwarding-check"
        const val LOOKUP_FRENCH = "fr"
        const val LOOKUP_ENGLISH = "en"
    }
}
