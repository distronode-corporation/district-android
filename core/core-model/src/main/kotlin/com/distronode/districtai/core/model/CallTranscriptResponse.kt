package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/calls/{callId}/transcript?workspaceId=`
 *
 * ⚠️ FETCHED LAZILY AND SEPARATELY, BY DESIGN. Embedded transcripts dominate any payload that
 * carries them, so every surface that wants one asks for it when the user actually opens it.
 * Neither the contact timeline nor the calls feed carries the text (the feed has only
 * [CallSummary.hasTranscript]), so this route is the ONLY source of it.
 *
 * ⚠️ An absent transcript is the EMPTY STRING, not null — the handler does `call.transcript || ""`.
 * So `transcript.isEmpty()` is the "nothing to show" test; a null check would never fire.
 * A missing CALL is a different thing entirely and arrives as 404 "Call not found".
 */
@Serializable
data class CallTranscriptResponse(
    val success: Boolean = false,
    val transcript: String = "",
) {
    /** True when there is something worth rendering. See the note about "" versus null. */
    val hasTranscript: Boolean get() = transcript.isNotBlank()
}
