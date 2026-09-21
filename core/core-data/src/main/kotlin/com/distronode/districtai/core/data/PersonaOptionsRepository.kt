package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.PREVIEW_ROOM_PREFIX
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.core.model.PersonaPreviewTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.PersonaApi

/**
 * The persona vocabularies, and the credential for auditioning one.
 *
 * ⛔ SEPARATE FROM [WorkspaceConfigRepository] EVEN THOUGH ONE SCREEN USES BOTH, and the split is a
 * safety property rather than a filing preference. That repository's whole contract is
 * "load-then-save, because every write there replaces a stored array"; neither method here reads or
 * writes stored configuration at all — `options` is a catalogue and `previewToken` persists
 * nothing. Folding them in would put two different obligations behind one type name.
 */
class PersonaOptionsRepository(private val api: PersonaApi) {

    /**
     * The lists this workspace's persona form may offer.
     *
     * ⛔ A FAILURE HERE MUST LEAVE THE FORM READ-ONLY AND MUST NEVER FALL BACK TO A BUILT-IN
     * CATALOGUE. `PATCH workspace/persona` COERCES rather than rejects, so a hardcoded Kotlin list
     * does not fail when it drifts — every value it offers is still accepted, stored, and then
     * silently substituted by the agent, with a 200 and nothing anywhere reporting it. That is the
     * exact failure this route exists to retire, and a fallback list reintroduces it.
     *
     * ⚠️ THE ANSWER IS PER WORKSPACE, NOT PER PROCESS, so nothing here caches. Each engine's label
     * states where its audio is processed — a public residency claim keyed on the WORKSPACE's
     * region — so a cache shared across workspaces would tell an EU tenant their audio stays in the
     * EU because a US workspace was read first.
     */
    suspend fun options(workspaceId: String): ApiResult<PersonaOptionsResponse> =
        when (val result = api.personaOptions(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(OPTIONS_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }

    /**
     * Mint a credential for one persona audition.
     *
     * ⛔ BILLABLE, NOT IDEMPOTENT, AND NEVER RETRIED HERE OR ABOVE. Every token invites the voice
     * agent into a room and starts burning STT/LLM/TTS minutes; the ceiling is 10/min per
     * WORKSPACE and it is the only thing bounding a loop, because the spend lands downstream in the
     * agent rather than in the handler. A caller treats a failure as final and offers a button.
     *
     * ⛔ IT CARRIES THE UNSAVED FORM ON PURPOSE. The agent reads this blob out of the token
     * metadata for any `preview_*` room, so the audition is of what is on screen. Sending the SAVED
     * persona instead would answer a different question convincingly.
     *
     * ⛔ THE THREE REFUSALS BELOW ARE DECODE FAILURES RATHER THAN SUCCESSES WITH A CAVEAT, because
     * each of them produces a session that CONNECTS and is silently useless. A room name without
     * the `preview_` prefix is answered by the stored persona; a missing or blank passphrase is a
     * room this device would be the only unencrypted participant in, publishing and hearing noise
     * while every connection succeeded. None of those surfaces as an error later.
     */
    suspend fun previewToken(
        workspaceId: String,
        form: PersonaPreviewForm,
    ): ApiResult<PersonaPreviewTokenResponse> =
        when (val result = api.personaPreviewToken(workspaceId, form)) {
            is ApiResult.Success -> verified(result.value)
            is ApiResult.Failure -> result
        }

    private fun verified(body: PersonaPreviewTokenResponse): ApiResult<PersonaPreviewTokenResponse> {
        rejectedEnvelope(PREVIEW_ENVELOPE, body.success)?.let { return it }
        if (body.token.isBlank() || body.url.isBlank()) {
            return decodeFailure(
                "$PREVIEW_ENVELOPE carried no join credential",
                "$PREVIEW_ENVELOPE{token=${body.token.isNotBlank()},url=${body.url.isNotBlank()}}",
            )
        }
        if (!body.roomName.startsWith(PREVIEW_ROOM_PREFIX)) {
            return decodeFailure(
                "$PREVIEW_ENVELOPE room is not a $PREVIEW_ROOM_PREFIX room",
                "$PREVIEW_ENVELOPE{roomName=${body.roomName.substringBefore('_')}_…}",
            )
        }
        // ⛔ A BLANK KEY IS NOT "no encryption" AND MUST NOT BE FORWARDED: it would derive a real
        // AES key nobody else in the room derives, so the join would succeed and every track would
        // be noise. ⚠️ AND AN ABSENT KEY HERE IS NOT THE SAME AS ON `calls/token`: a `preview_*`
        // room is always encrypted, so null means the server failed to derive one rather than
        // "join in the clear".
        if (body.e2ee?.key.isNullOrBlank()) {
            return decodeFailure(
                "$PREVIEW_ENVELOPE carried no encryption key for an encrypted room",
                "$PREVIEW_ENVELOPE{e2ee=null}",
            )
        }
        return ApiResult.Success(body)
    }

    private fun decodeFailure(message: String, preview: String): ApiResult.DecodeFailure =
        ApiResult.DecodeFailure(cause = IllegalStateException(message), bodyPreview = preview)

    private companion object {
        const val OPTIONS_ENVELOPE = "PersonaOptionsResponse"
        const val PREVIEW_ENVELOPE = "PersonaPreviewTokenResponse"
    }
}
