package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * District HQ — the agentic command console.
 *
 * ⛔ ONE ROUTE, TWO REQUEST SHAPES, TWO RESPONSE SHAPES. `POST /api/district/hq` branches on the
 * BODY: a body carrying `confirm` executes a previously proposed write and answers
 * [HqConfirmResponse]; anything else runs the model over `prompt` + `history` and answers
 * [HqPromptResponse]. They are modelled as separate types rather than as one union with everything
 * optional, because a union would let a confirm response decode as a prompt response with an empty
 * answer — i.e. a write silently reported as a reply.
 *
 * ⛔ THE ROUTE IS NON-STREAMING AND STATELESS. There is no SSE, no session id and no server-side
 * conversation: the client holds the transcript and sends it back as [HqPromptRequest.history] on
 * every turn. The server keeps only the last 6 turns, so sending more is not an error — it is how
 * the client stays the single owner of the transcript across process death.
 */

/**
 * One prior turn of the conversation, replayed to the server.
 *
 * ⛔ [role] IS `user` OR `model`, NOT `assistant`. The server feeds these straight into Gemini's
 * `Content` list, whose vocabulary is `model`, and it DROPS any turn whose role is neither — with no
 * error. So a client that sent the more usual `assistant` would lose exactly half the conversation
 * and be told nothing; the answer would just quietly stop following the thread. The app's own enum
 * is mapped on the way out for this reason (see `HqMessage`).
 */
@Serializable
data class HqTurn(
    val role: String,
    val text: String,
)

/**
 * A prompt turn.
 *
 * ⚠️ `workspaceId` is a HINT, not an authority. The server resolves the caller's workspace and role
 * itself through `requireWorkspaceRole` and uses THAT id for every tool call, so a wrong id here is
 * a 403/404 rather than a cross-tenant read.
 */
@Serializable
data class HqPromptRequest(
    val workspaceId: String,
    val prompt: String,
    /**
     * Prior turns, oldest first.
     *
     * ⛔ THE DEFAULT MEANS AN EMPTY HISTORY IS OMITTED FROM THE WIRE, NOT SENT AS `[]`.
     * kotlinx.serialization does not encode a property still holding its declared default unless
     * `encodeDefaults` is on, which this client leaves off. That exact mechanism was a real bug on
     * `SendMessageRequest.channel`, whose default never reached the server and let the SERVER's own
     * (different) default apply silently.
     *
     * ⚠️ It is SAFE here, and for a reason specific to this route rather than a general reprieve:
     * the handler gates the replay on `Array.isArray(body.history)`, so absent and `[]` take the
     * same branch and mean the same thing. `channel` was dangerous because the server had another
     * value to fall back on. Before giving any future field on this request a default, establish
     * which of those two cases it is. Pinned by `HqRequestBodyTest`.
     */
    val history: List<HqTurn> = emptyList(),
)

/**
 * A confirm turn: execute the write the operator was shown and approved.
 *
 * ⛔ [HqConfirmAction.args] MUST BE ECHOED BACK VERBATIM from the [HqPendingWrite] the server
 * proposed. The whole point of the two-step is that the confirmed action is byte-identical to the
 * one the operator read in [HqPendingWrite.summary]; re-deriving, re-ordering or "cleaning up" the
 * arguments here would apply a different change from the one that was described.
 */
@Serializable
data class HqConfirmRequest(
    val workspaceId: String,
    val confirm: HqConfirmAction,
)

@Serializable
data class HqConfirmAction(
    val tool: String,
    /** ⛔ Opaque. See the ⛔ on [HqConfirmRequest] and on [HqPendingWrite.args]. */
    val args: JsonObject = JsonObject(emptyMap()),
)

/**
 * The answer to a prompt turn.
 *
 * ⚠️ [answer] IS ALWAYS POPULATED, even when the model produced nothing usable — the route
 * substitutes its own "I couldn't finish that one" text rather than returning an empty string, so an
 * empty answer means contract drift rather than a quiet model.
 */
@Serializable
data class HqPromptResponse(
    val success: Boolean = false,
    val answer: String = "",
    /**
     * ⛔ TRUE MEANS NOTHING HAS BEEN WRITTEN YET. The model can only PROPOSE a change; the server
     * gates every write behind an explicit second call. Rendering the answer without the confirm
     * affordance would leave the operator believing a change they asked for had been applied.
     */
    val needsConfirmation: Boolean = false,
    val pendingWrite: HqPendingWrite? = null,
)

/**
 * A proposed, NOT-APPLIED change.
 *
 * ⚠️ Only the FIRST proposal of a turn is surfaced by the server, even if the model emitted several.
 */
@Serializable
data class HqPendingWrite(
    /** The tool name, echoed back on confirm so the applied action can be checked against it. */
    val tool: String = "",
    /**
     * ⛔ MODELLED AS OPAQUE JSON ON PURPOSE, AND IT MUST STAY THAT WAY. These are the arguments the
     * MODEL chose, so their shape depends on which of the ten write tools it picked — a persona
     * update carries `greeting`/`personality`/`voice`, a routing replacement carries a `rules`
     * array, a campaign carries a `goal`. Typing them would mean a data class per tool and a
     * decode failure on the phone the day a tool gains a field. The client never reads them: it
     * shows [summary] and returns [args] verbatim.
     */
    val args: JsonObject = JsonObject(emptyMap()),
    /**
     * The operator-facing sentence describing exactly what would happen.
     *
     * ⛔ THE ONLY THING THE CONFIRM PROMPT MAY BE BUILT FROM. It is composed server-side from the
     * real arguments; a client that rendered the tool name instead would ask the operator to
     * approve "update_call_routing" rather than "REPLACE the workspace call-routing rules".
     */
    val summary: String = "",
)

/**
 * The answer to a confirm turn.
 *
 * ⛔ [success] AND [executed] ARE DIFFERENT QUESTIONS. `success` means the request was handled;
 * `executed` means the write actually took effect. A viewer's confirm, or a tool that refused
 * internally, answers `success: true, executed: false` — reporting that as done is the failure mode
 * this pair exists to prevent.
 */
@Serializable
data class HqConfirmResponse(
    val success: Boolean = false,
    val executed: Boolean = false,
    /** ⛔ Echoed by the server so the client can prove the APPLIED action is the PROPOSED one. */
    val tool: String = "",
    val args: JsonObject = JsonObject(emptyMap()),
    /**
     * The tool's own return value.
     *
     * ⚠️ [JsonElement], not [JsonObject]: this is whatever the executed tool returned, and nothing
     * in the route constrains it to an object. It is carried for diagnostics only — the client
     * decides "applied" from [executed], never by inspecting this.
     */
    val result: JsonElement? = null,
)
