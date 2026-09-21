package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `POST /api/district/workspace/persona/preview-token` — the credential for one persona audition.
 *
 * ⛔ A REAL, BILLED CALL AND NOT A DRY RUN. The token invites the voice agent into a
 * `preview_<workspaceId>_<uuid>` room on the workspace's own pipeline and its own media node,
 * where it answers with STT, an LLM and TTS exactly as it would on a telephone call. The route is
 * capped at **10/min per workspace** and is not idempotent; nothing in this client may retry it.
 *
 * ⛔ [url] IS THE SERVER'S CHOICE OF MEDIA NODE AND IS USED VERBATIM, for the reason
 * [RoomTokenResponse] states at length: the room exists only on the deployment that created it,
 * and a URL derived from the workspace's region — or from a constant — joins a bus that has never
 * heard of the room.
 *
 * ⛔ THE TOKEN LASTS 30 MINUTES AND THAT IS NOT A SESSION LIMIT. It authorises the JOIN; an
 * established connection is not re-checked against it. A rejoin after it expires needs a fresh
 * one, which is a fresh rate-limit slot and a fresh billed session.
 */
@Serializable
data class PersonaPreviewTokenResponse(
    val success: Boolean = false,
    /** ⚠️ 30 minutes. See the class doc. */
    val token: String = "",
    /** ⛔ Used verbatim. See the class doc. */
    val url: String = "",
    /**
     * ⛔ `preview_<workspaceId>_<uuid>`, MINTED SERVER-SIDE AND NEVER REBUILT. The agent branches
     * on the `preview_` prefix to read the persona out of the token metadata instead of the stored
     * row, so a name a client invented would be answered by the SAVED persona — the opposite of
     * what the screen promises.
     */
    val roomName: String = "",
    /**
     * The room's shared encryption passphrase.
     *
     * ⛔ HANDED TO THE MEDIA SDK VERBATIM AND NEVER BASE64-DECODED. See [E2eeInfo], which carries
     * the argument in full and records the 2.28.0 AAR verification.
     *
     * ⛔ NULLABLE IN KOTLIN AND PRESENT ON EVERY PREVIEW IN PRACTICE, and the nullability is a
     * refusal to crash rather than a documented second shape: a `preview_*` room is always
     * encrypted (phone-to-agent, no SIP leg, no avatar). ⚠️ So an ABSENT key here is not "join
     * unencrypted" the way it is on `calls/token` — it is the server failing to derive one, and a
     * client that joined anyway would be the only unencrypted participant in a room everyone else
     * encrypted, hearing and publishing noise.
     */
    val e2ee: E2eeInfo? = null,
)

/**
 * ⛔ THE PREFIX THE VOICE AGENT BRANCHES ON. A room whose name does not start with this is answered
 * by the SAVED persona rather than by the form on screen, so a repository checks it before handing
 * the credential to a media engine.
 */
const val PREVIEW_ROOM_PREFIX: String = "preview_"

/**
 * The body of a preview-token request.
 *
 * ⚠️ THE FORM NESTS UNDER `formData`, unlike every other write on this surface. The route reads
 * `{ workspaceId, formData }` and answers **400 "Missing workspaceId or formData"** for a
 * flattened body — which reads as a broken client rather than as a shape mismatch.
 */
@Serializable
data class PersonaPreviewTokenRequest(
    val workspaceId: String,
    val formData: PersonaPreviewForm,
)

/**
 * The persona a preview session auditions, as the route's sanitiser reads it.
 *
 * ⛔ THE FIELD LIST IS THE SERVER'S `PREVIEW_STRING_FIELDS` PLUS THE THREE TYPED ONES, AND IT IS
 * NOT THE SAVE ROUTE'S LIST. The preview sanitiser reads `name`, `voice`, `greeting`,
 * `personality`, `voiceStyle`, `language`, `responseLength`, `modelId`, `temperature` and
 * `preemptiveTts` — and `responseLength` here is a bare STRING for the engine being auditioned
 * rather than the per-engine map the stored row holds. `dgiEnabled` is absent because the preview
 * does not enrich.
 *
 * ⚠️ `connectors` AND `agentMedia` ARE ALSO READ BY THE SANITISER AND ARE DELIBERATELY NOT
 * MODELLED. Both are arrays this client has no editor for, and a field sent empty is not the same
 * as one left alone.
 *
 * ⚠️ EVERY FIELD IS NULLABLE AND A null IS DROPPED FROM THE WIRE by the request encoder's
 * `explicitNulls = false`. There is no stored row to merge against: an absent key leaves the agent
 * on its own per-field fallback for this session only, and nothing is persisted either way.
 */
@Serializable
data class PersonaPreviewForm(
    val name: String? = null,
    val greeting: String? = null,
    val personality: String? = null,
    /** ⚠️ A pipeline-registry voice id, not a display name. */
    val voice: String? = null,
    val language: String? = null,
    /**
     * ⛔ COERCED TO `deepgram-pipeline` BY THE SERVER when it is not a known pipeline id. The
     * preview then runs on an engine nobody chose, with a 200 — which is why this value must come
     * from [PersonaOptionsResponse.engines].
     */
    val modelId: String? = null,
    /** ⚠️ ONE LEVEL FOR THE ENGINE BEING AUDITIONED, not the stored per-engine map. */
    val responseLength: String? = null,
    val temperature: Double? = null,
    /** ⚠️ Gemini Live only. Ignored by the other engines, which have no TTS stage to posture. */
    val voiceStyle: String? = null,
    /**
     * ⛔ PAID SPECULATIVE SYNTHESIS. The agent reads it as
     * `bool(persona_data.get("preemptiveTts"))`, so a truthy STRING would switch it on — which is
     * why this is a `Boolean?` and why the sanitiser refuses anything else.
     */
    val preemptiveTts: Boolean? = null,
)
