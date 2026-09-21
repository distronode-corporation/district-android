package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.DistrictSetupResponse

class HttpSetupApi(private val client: DistrictApiClient) : SetupApi {

    override suspend fun districtSetup(workspaceId: String): ApiResult<DistrictSetupResponse> =
        client.get(
            segments = SETUP_PATH,
            serializer = DistrictSetupResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )
}

private val SETUP_PATH = listOf("api", "district", "setup")
