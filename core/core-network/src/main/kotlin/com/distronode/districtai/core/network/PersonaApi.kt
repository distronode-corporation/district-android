package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.core.model.PersonaPreviewTokenResponse
import com.distronode.districtai.core.model.VoiceStudioResponse

/**
 * The persona form's vocabularies, and the credential for auditioning one.
 *
 * ⛔ THESE TWO ARE WHY THE PERSONA FORM CAN BE EDITED FROM A PHONE AT ALL. Every field on
 * `PATCH workspace/persona` beyond the three free-text ones is drawn from a server-side registry
 * and COERCES rather than rejects: an unrecognised `modelId` becomes `deepgram-pipeline`, an
 * unrecognised `voice` is stored verbatim and then quietly replaced by the agent's own fallback at
 * synthesis time. Both answer 200 and nothing anywhere reports the substitution. So the choice was
 * never "validate client-side or not" — it was "offer free text and produce a persona nobody
 * chose, or read the catalogue the web derives its own pickers from". This is the second.
 *
 * ⛔ AND THE PREVIEW IS A REAL, BILLED CALL. `persona/preview-token` mints a LiveKit credential for
 * an end-to-end encrypted `preview_<workspaceId>_<uuid>` room that the voice agent joins and
 * answers in, on the workspace's own pipeline and the workspace's own media node. Nothing about it
 * is a dry run.
 *
 * ⛔ ITS OWN INTERFACE RATHER THAN TWO MORE METHODS ON [DistrictApi]. See [ExtraPaths] for why.
 */
interface PersonaApi {

    /**
     * The engines, languages, voices, voice styles, answer lengths and defaults this workspace's
     * persona form may offer.
     *
     * ⛔ REGION-KEYED, AND THE REGION IS THE WORKSPACE'S RATHER THAN THE SERVING ORIGIN'S. Each
     * engine is labelled with where its audio is actually processed, which is a public claim about
     * residency; keying on whichever origin answered would tell an EU customer their audio stays
     * in the EU because a Frankfurt node happened to take the request. A client must therefore
     * never cache one workspace's answer and render it for another.
     *
     * ⛔ `inRegion: false` IS SELECTABLE-LOOKING AND MUST NOT BE OFFERED AS ONE.
     *
     * ⚠️ A READ, AND STILL RATE LIMITED — 60/min per WORKSPACE. Generous on purpose (a client may
     * legitimately refetch when the engine changes) but not unbounded, so it belongs on a screen's
     * load rather than on a keystroke.
     *
     * ⚠️ IT EXCLUDES `viewer` SERVER-SIDE, the same bar the save route and `workspace/config` set.
     */
    suspend fun personaOptions(workspaceId: String): ApiResult<PersonaOptionsResponse>

    /**
     * Everything the Voice Studio draws: recipes, the resolved signal chain, the time-to-first-word
     * meter, the per-leg catalogue, voices and every tuning key, with every label already in the
     * reader's PORTAL locale.
     *
     * ⛔ THE SAVE IS NOT HERE. The Studio writes through the existing persona PATCH, which refuses
     * an invalid chain with 400 `invalid_engine_mix` and silently ignores a few wrong types with a
     * 200; this read is how a client finds out which: re-read after every save and compare
     * `current.fields` with what was sent.
     *
     * ⚠️ RATE LIMITED, 60/min per WORKSPACE, and excludes `viewer`, like [personaOptions].
     */
    suspend fun personaVoiceStudio(workspaceId: String): ApiResult<VoiceStudioResponse>

    /**
     * Mint a credential for one persona preview session.
     *
     * ⛔ BILLABLE, NOT IDEMPOTENT, AND NOTHING IN THIS CLIENT MAY RETRY IT. Every token is an
     * invitation for the voice agent to join a room and start burning STT/LLM/TTS minutes; the
     * route is capped at **10/min per workspace** and that ceiling is the only thing bounding a
     * retry loop, because the spend lands downstream in the agent rather than in the handler.
     *
     * ⛔ IT CARRIES THE **UNSAVED** FORM, WHICH IS THE WHOLE POINT OF THE ROUTE. The agent reads
     * this blob out of the token metadata for any `preview_*` room, so the audition is of what is
     * on screen rather than of what is stored. A client that sent the SAVED persona instead would
     * answer a different question convincingly.
     *
     * ⛔ THE SERVER SANITISES AND [PersonaPreviewForm] MIRRORS THE SANITISER EXACTLY. Unknown keys
     * are DROPPED rather than passed through; sending a key the sanitiser does not read is inert
     * today, which is exactly how a form field becomes an agent input later without anyone
     * deciding.
     */
    suspend fun personaPreviewToken(
        workspaceId: String,
        form: PersonaPreviewForm,
    ): ApiResult<PersonaPreviewTokenResponse>
}
