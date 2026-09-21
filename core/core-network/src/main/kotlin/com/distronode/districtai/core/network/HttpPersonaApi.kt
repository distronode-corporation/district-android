package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.core.model.PersonaPreviewTokenRequest
import com.distronode.districtai.core.model.PersonaPreviewTokenResponse

/**
 * [PersonaApi] over [DistrictApiClient].
 *
 * ⛔ THE PREVIEW GOES THROUGH [DistrictApiClient.send], WHICH RE-SENDS ONCE ON A 401, AND IT IS NOT
 * IDEMPOTENT. Same class of endpoint as `messages/send` and HQ's confirm, and the same bounded
 * trade: the re-send can only fire on a request the server refused BEFORE running the handler, so
 * it cannot start two billed sessions. Anything above this layer adding its own retry can.
 */
class HttpPersonaApi(private val client: DistrictApiClient) : PersonaApi {

    override suspend fun personaOptions(workspaceId: String): ApiResult<PersonaOptionsResponse> =
        client.get(
            segments = ExtraPaths.WORKSPACE_PERSONA_OPTIONS,
            serializer = PersonaOptionsResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    /**
     * ⚠️ THE BODY NESTS UNDER `formData`, unlike every other write on this surface. The route reads
     * `{ workspaceId, formData }` and answers **400 "Missing workspaceId or formData"** for a
     * flattened body — which reads as a broken client rather than as a shape mismatch.
     */
    override suspend fun personaPreviewToken(
        workspaceId: String,
        form: PersonaPreviewForm,
    ): ApiResult<PersonaPreviewTokenResponse> =
        client.send(
            method = "POST",
            segments = ExtraPaths.WORKSPACE_PERSONA_PREVIEW_TOKEN,
            serializer = PersonaPreviewTokenResponse.serializer(),
            body = PersonaPreviewTokenRequest(workspaceId = workspaceId, formData = form)
                .toExtraJson(PersonaPreviewTokenRequest.serializer()),
        )
}
