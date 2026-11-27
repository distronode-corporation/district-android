// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.HqProposalCancelResponse
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.model.HqVoice
import com.distronode.districtai.core.model.HqVoiceContextResponse
import com.distronode.districtai.core.model.HqVoiceDiscardResponse
import com.distronode.districtai.core.model.HqVoiceEndResponse
import com.distronode.districtai.core.model.HqVoiceSettings
import com.distronode.districtai.core.model.HqVoiceSettingsResponse
import com.distronode.districtai.core.model.HqVoiceTokenResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * [HqVoiceApi] over [DistrictApiClient].
 *
 * ⚠️ BODIES BUILT AS `JsonObject`s, key for key the bodies iOS sends (district-core-swift
 * `DistrictEndpoints+HQVoice.swift`). `HqVoiceRequestTest` asserts each one against those keys.
 * Every route takes `workspaceId` in the body except the settings read, which takes it in the query.
 */
class HttpHqVoiceApi(private val client: DistrictApiClient) : HqVoiceApi {

    override suspend fun hqVoiceToken(
        workspaceId: String,
        locale: String,
        history: List<HqTurn>,
    ): ApiResult<HqVoiceTokenResponse> {
        val turns = HqVoice.boundedHistory(history)
        return client.send(
            method = "POST",
            segments = HqVoicePaths.TOKEN,
            serializer = HqVoiceTokenResponse.serializer(),
            body = jsonObjectOf(
                "workspaceId" to JsonPrimitive(workspaceId),
                "locale" to JsonPrimitive(if (locale == "fr") "fr" else "en"),
                // ⚠️ Left out when empty, as iOS does.
                "history" to turns.takeIf { it.isNotEmpty() }?.let { bounded ->
                    JsonArray(
                        bounded.map { turn ->
                            jsonObjectOf("role" to JsonPrimitive(turn.role), "text" to JsonPrimitive(turn.text))
                        },
                    )
                },
            ),
        )
    }

    override suspend fun hqVoiceContext(
        workspaceId: String,
        roomName: String,
        version: Int,
        page: String,
    ): ApiResult<HqVoiceContextResponse> =
        client.send(
            method = "POST",
            segments = HqVoicePaths.CONTEXT,
            serializer = HqVoiceContextResponse.serializer(),
            body = jsonObjectOf(
                "workspaceId" to JsonPrimitive(workspaceId),
                "roomName" to JsonPrimitive(roomName),
                // ⚠️ A page of the service's page table and nothing else: no record is named.
                "snapshot" to jsonObjectOf(
                    "version" to JsonPrimitive(version),
                    "page" to JsonPrimitive(page),
                ),
            ),
        )

    override suspend fun hqVoiceSettings(workspaceId: String): ApiResult<HqVoiceSettingsResponse> =
        client.get(
            segments = HqVoicePaths.SETTINGS,
            serializer = HqVoiceSettingsResponse.serializer(),
            query = workspaceQuery(workspaceId),
        )

    override suspend fun saveHqVoiceSettings(
        workspaceId: String,
        settings: HqVoiceSettings,
    ): ApiResult<HqVoiceSettingsResponse> =
        client.send(
            method = "PUT",
            segments = HqVoicePaths.SETTINGS,
            serializer = HqVoiceSettingsResponse.serializer(),
            body = jsonObjectOf(
                "workspaceId" to JsonPrimitive(workspaceId),
                "settings" to jsonObjectOf(
                    "voice" to JsonPrimitive(settings.voice),
                    "length" to JsonPrimitive(settings.length),
                    "pace" to JsonPrimitive(settings.pace),
                    "language" to JsonPrimitive(settings.language),
                    "micMode" to JsonPrimitive(settings.micMode),
                ),
            ),
        )

    override suspend fun hqProposalCancel(
        workspaceId: String,
        proposalId: String,
    ): ApiResult<HqProposalCancelResponse> =
        client.send(
            method = "POST",
            segments = HqVoicePaths.PROPOSAL_CANCEL,
            serializer = HqProposalCancelResponse.serializer(),
            body = jsonObjectOf(
                "workspaceId" to JsonPrimitive(workspaceId),
                "proposalId" to JsonPrimitive(proposalId),
            ),
        )

    override suspend fun hqVoiceEnd(workspaceId: String, sessionId: String): ApiResult<HqVoiceEndResponse> =
        client.send(
            method = "POST",
            segments = HqVoicePaths.END,
            serializer = HqVoiceEndResponse.serializer(),
            body = sessionBody(workspaceId, sessionId),
        )

    override suspend fun hqVoiceDiscard(
        workspaceId: String,
        sessionId: String,
    ): ApiResult<HqVoiceDiscardResponse> =
        client.send(
            method = "POST",
            segments = HqVoicePaths.DISCARD,
            serializer = HqVoiceDiscardResponse.serializer(),
            body = sessionBody(workspaceId, sessionId),
        )

    private fun sessionBody(workspaceId: String, sessionId: String) = jsonObjectOf(
        "workspaceId" to JsonPrimitive(workspaceId),
        "sessionId" to JsonPrimitive(sessionId),
    )
}

private object HqVoicePaths {
    private val HQ = ApiRoots.DISTRICT + "hq"

    val TOKEN = HQ + "voice-token"
    val CONTEXT = HQ + "voice-context"
    val SETTINGS = HQ + "voice-settings"
    val END = HQ + "voice-end"
    val DISCARD = HQ + "voice-discard"
    val PROPOSAL_CANCEL = HQ + "proposals" + "cancel"
}
