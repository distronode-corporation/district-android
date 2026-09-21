package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/calls/{callId}?workspaceId=`
 *
 * ⛔ THE PAYLOAD IS THE SAME [CallSummary] THE FEED RETURNS, NOT A RICHER "DETAIL" TYPE. The route
 * reuses the server's own `toCallSummaries` mapping deliberately, so one DTO decodes three
 * surfaces: the feed, the overview's recent activity, and this. The contract suite asserts this
 * response equals the matching feed row, which is what keeps the three from drifting.
 *
 * ⚠️ THE ENVELOPE DIFFERS FROM THE FEED'S EVEN THOUGH THE PAYLOAD DOES NOT. The feed is a bare
 * ARRAY; this is `{ success, call }`. A single resource needs a way to express "found nothing" that
 * a bare object cannot, so absence is a 404 with an envelope — which is also how every other
 * single-resource route here behaves.
 *
 * ⚠️ No transcript text rides on this payload, only [CallSummary.hasTranscript]; the text is
 * `GET .../transcript` ([CallTranscriptResponse]).
 */
@Serializable
data class CallDetailResponse(
    val success: Boolean = false,
    /**
     * Null only on a malformed response — a genuinely missing call is a 404, which the client maps
     * to [com.distronode.districtai.core.network.ApiResult.NotFound] rather than a success with a
     * null payload.
     *
     * ⚠️ A 404 here does NOT mean the id is invalid. The server reads by id and checks ownership
     * afterwards, on purpose, so another tenant's call id is indistinguishable from one that does
     * not exist. Do not word an error as "no such call" in a way that implies the id was wrong.
     */
    val call: CallSummary? = null,
)
