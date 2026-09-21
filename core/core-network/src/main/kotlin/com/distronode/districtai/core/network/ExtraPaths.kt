package com.distronode.districtai.core.network

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The paths and the body encoder for the endpoints that live OUTSIDE [DistrictApi].
 *
 * ⛔ WHY THIS FILE EXISTS AT ALL, BECAUSE IT IS A PROCESS DECISION AND NOT AN ARCHITECTURAL ONE.
 * `DistrictApi.kt` / `HttpDistrictApi.kt` are one 85-method interface and its delegating
 * implementation, and they are the guaranteed conflict when several people add endpoints at once.
 * The five small interfaces beside this file ([PersonaApi], [CallHandlingApi], [InboxExtrasApi],
 * [CallControlApi], [ContactBlockingApi]) are therefore separate rather than sections of that one. ⚠️ If this repo ever
 * stops having concurrent editors, folding them in is a mechanical move — each is already shaped
 * like a section of it.
 *
 * ⛔ THE PATH ROOTS ARE DERIVED FROM [DistrictPaths] RATHER THAN RESTATED. Its own `DISTRICT`,
 * `WORKSPACE` and `MESSAGES` roots are private, so the roots below are taken off sibling paths it
 * already publishes. A literal `listOf("api", "district", "workspace")` here would be a second
 * copy of a prefix that has already moved once — `/api/district/` postdates a flat `/api/` — with
 * nothing comparing the two, which is the same drifting-second-copy failure
 * `PersonaOptionsResponse` exists to retire on the persona vocabulary.
 */
internal object ExtraPaths {

    /** `api/district/workspace`, off [DistrictPaths.WORKSPACE_PERSONA]. */
    private val WORKSPACE: List<String> = DistrictPaths.WORKSPACE_PERSONA.dropLast(1)

    /** `api/district/messages`, off [DistrictPaths.MESSAGES_SEND]. */
    private val MESSAGES: List<String> = DistrictPaths.MESSAGES_SEND.dropLast(1)

    /**
     * ⛔ A CHILD OF `workspace/persona`, NOT A SIBLING. `workspace/persona-options` does not exist
     * and would 404.
     */
    val WORKSPACE_PERSONA_OPTIONS: List<String> = DistrictPaths.WORKSPACE_PERSONA + "options"

    /** ⛔ Its sibling, and the only route on this surface that starts a billed media session. */
    val WORKSPACE_PERSONA_PREVIEW_TOKEN: List<String> = DistrictPaths.WORKSPACE_PERSONA + "preview-token"

    /**
     * ⛔ ONE PATH, TWO VERBS, AND THE VERBS DISAGREE ON PURPOSE. GET normalises a stored value that
     * may predate the vocabulary; PATCH validates what arrives and answers 400 for anything
     * outside it.
     */
    val WORKSPACE_CALL_HANDLING: List<String> = WORKSPACE + "call-handling"

    /**
     * ⛔ A DIFFERENT SCOPE FROM ITS NEIGHBOUR ABOVE, despite the adjacent path. `call-handling` is a
     * WORKSPACE setting; this one reads and writes the CALLER'S OWN membership row and takes no
     * identity at all.
     */
    val WORKSPACE_AVAILABILITY: List<String> = WORKSPACE + "availability"

    /**
     * ⚠️ FULL-CONTENT SEARCH, AND IT IS NOT `conversations` FILTERED. See [MessageSearchResponse].
     */
    val MESSAGES_SEARCH: List<String> = MESSAGES + "search"

    /**
     * One message id exchanged for its thread.
     *
     * ⛔ A FUNCTION BECAUSE THE ID IS A PATH SEGMENT THAT ARRIVES FROM A PUSH, i.e. from the least
     * trustworthy input in the app. ⚠️ It also sits where the family's other members are literal
     * words, so a message whose id happened to be `send` would address the send route with a GET;
     * that answers 405 rather than sending anything, because ids here are cuids and the route
     * exports POST only.
     */
    fun messageThread(id: String): List<String> = MESSAGES + id

    /**
     * ⛔ ONE SEGMENT FROM `calls/{id}/answer` AND `calls/dial`, AND THE THREE DO OPPOSITE THINGS.
     * This one ENDS a call, `answer` takes one, and `dial` places one.
     */
    fun callHangUp(callId: String): List<String> = DistrictPaths.CALLS + callId + "hangup"

    /** The block WRITE. ⚠️ One letter from its read below, and the two take different verbs. */
    val CONTACTS_BLOCK: List<String> = DistrictPaths.CONTACTS + "block"

    /** The blocked-set READ. */
    val CONTACTS_BLOCKED: List<String> = DistrictPaths.CONTACTS + "blocked"
}

/**
 * Encode a request body the way this client's other writes are encoded.
 *
 * ⛔ `explicitNulls = false` IS THE LOAD-BEARING SETTING, NOT TIDINESS. Four of the five bodies in
 * this group depend on a null being ABSENT rather than explicit: `workspace/persona` merges
 * presence-wise so an explicit null would be a value, `call-handling` refuses a body carrying only
 * the workspace, the preview sanitiser reads each key it knows and leaves the agent on its own
 * fallback for the rest, and `contacts/block` is pinned to the absent form for the id or number it
 * was not given, so both native clients send the same body (its route would take a null too).
 *
 * ⚠️ A SECOND `Json` INSTANCE, AND IT IS A KNOWN COST. `HttpDistrictApi.kt` has a private one with
 * the same configuration and that file is deliberately untouched here (see [ExtraPaths]); the
 * mitigation is that both are pinned by MockWebServer tests that assert the ABSENCE of a key
 * rather than by inspection.
 */
internal fun <T> T.toExtraJson(serializer: SerializationStrategy<T>): JsonElement =
    EXTRA_BODY_JSON.encodeToJsonElement(serializer, this)

private val EXTRA_BODY_JSON = Json { explicitNulls = false }
