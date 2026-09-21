package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.DirectoryPatchRequest
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.RoutingRulesRequest
import com.distronode.districtai.core.model.ToolsPatchRequest
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi

/**
 * Workspace settings: the read every mutation form must hydrate from, and the two writes that
 * are safe to make from a phone.
 *
 * ⛔ THE CENTRAL RULE, AND EVERY SIGNATURE HERE ENFORCES IT: A SAVE MAY ONLY BE BUILT ON A
 * SUCCESSFUL [load]. Three of this surface's save routes replace their stored value WHOLESALE
 * rather than merging it — `workspace/directory`, `workspace/routing-rules` and the
 * `allowedTools` half of `workspace/tools`. A form that opened empty and saved would not save
 * nothing; it would DELETE the transfer directory the voice agent routes live callers through,
 * or the agent's tool allowlist. The web never had to solve this because its settings page is a
 * server component that hydrates each form from the row during render. This repository is that
 * prop, fetched — and [saveTools] takes the complete list rather than a delta precisely so a
 * caller cannot express a save it did not load.
 *
 * ⛔ NO WRITE ECHOES THE UPDATED CONFIG. All four routes answer a bare `{success:true}`, so
 * there is nothing to adopt and every save RE-READS. That re-read is not bookkeeping: without it
 * the screen would keep rendering its own optimistic edit as though the server had confirmed it,
 * and the next save would be built on a client-side belief rather than on stored state. If either
 * route ever grows a `config` key, `district-persona-patch.json` is where that shows up and this
 * is where the extra round trip can then be dropped.
 *
 * ⛔ AND THE RE-READ IS ALLOWED TO FAIL WITHOUT THE SAVE HAVING FAILED, WHICH IS WHY [SaveOutcome]
 * HAS THREE CASES RATHER THAN TWO. Collapsing "written, could not re-read" into a failure is the
 * dangerous direction: the operator would be told the save did not land, would change the form
 * back, and would save again — through a wholesale-replace route, from state that is now stale.
 *
 * ⚠️ NO CACHING. A settings screen is opened BECAUSE something looks wrong, and this is also the
 * value a save is rebuilt from; a process-scoped copy would let a phone write back a configuration
 * that was replaced on the web in between.
 *
 * ⚠️ EVERY CALL HERE EXCLUDES `viewer` SERVER-SIDE — including [load], which is unusual on this
 * surface and deliberate (the payload is staff phone numbers and the operator's prompt). The UI
 * must hide the entry point rather than let a viewer arrive at a 403.
 */
class WorkspaceConfigRepository(private val api: DistrictApi) {

    /**
     * Read the workspace settings row.
     *
     * ⛔ ENVELOPE FIRST, THEN THE NULL CHECK, AND BOTH MATTER MORE HERE THAN ANYWHERE ELSE IN
     * THIS MODULE. Every field of [com.distronode.districtai.core.model.WorkspaceConfigResponse]
     * has a default, so a `{}` body decodes into a perfectly well-formed "this workspace has no
     * persona, no tools, no directory" — which is exactly the shape a form would then save back
     * through a wholesale-replace route. A structurally wrong 200 must reach the caller as a
     * FAILURE so the form renders retry, never as an empty config.
     */
    suspend fun load(workspaceId: String): ApiResult<WorkspaceConfig> =
        when (val result = api.workspaceConfig(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(CONFIG_ENVELOPE, result.value.success)
                    ?: result.value.config?.let { ApiResult.Success(it) }
                    // The route always emits `config`, so its absence is contract drift. Reported
                    // as drift rather than defaulted to an empty config — see the ⛔ above.
                    ?: ApiResult.DecodeFailure(
                        cause = IllegalStateException("$CONFIG_ENVELOPE affirmed success with no config"),
                        bodyPreview = "$CONFIG_ENVELOPE{config=null}",
                    )
            is ApiResult.Failure -> result
        }

    /**
     * Save persona fields, then re-read.
     *
     * ⛔ THE CALLER MUST HAVE ALREADY DROPPED EVERY FIELD THE OPERATOR DID NOT CHANGE. The server
     * merges per field, so an omitted key is preserved and a present one is written — which makes
     * [PersonaPatchRequest]'s nulls the mechanism, not a nicety. This layer does not re-derive
     * dirtiness: it cannot, because it does not hold the baseline the form was hydrated with.
     *
     * ⚠️ AN EMPTY STRING IS A DELIBERATE CLEAR AND IS SENT AS ONE, matching the web form exactly.
     * Null is "leave it alone". Anything that "helpfully" mapped blank to null here would make a
     * cleared greeting silently un-clearable.
     */
    suspend fun savePersona(request: PersonaPatchRequest): SaveOutcome =
        commit(request.workspaceId) { api.savePersona(request) }

    /**
     * Replace the agent's capability allowlist, then re-read.
     *
     * ⛔ `allowedTools` IS WRITTEN WHOLESALE. Whatever list arrives becomes the stored one, so the
     * caller's obligation is to send what it LOADED with the operator's toggles applied —
     * including any id this client's catalog does not recognise. That is not hypothetical:
     * `transfer_to_creator` was retired in 2026 and workspaces still store it, and a list rebuilt
     * from a hardcoded catalog would drop it on the next save.
     *
     * ⚠️ NOTHING ELSE ON `toolConfig` IS SENT. The other five keys are merged per field AND are
     * written when present, so `""` would CLEAR the calendar id or the sender identity. Omitting
     * them is the only way to leave them alone, and this client has no form for any of them.
     */
    suspend fun saveTools(request: ToolsPatchRequest): SaveOutcome =
        commit(request.workspaceId) { api.saveTools(request) }

    /**
     * Replace the call transfer directory, then re-read.
     *
     * ⛔ THE MOST DESTRUCTIVE WRITE THIS CLIENT MAKES, AND ITS FAILURE MODE IS A SUCCESS. The route
     * writes `callDirectory: (callDirectory || [])`, so an empty array is not "nothing to save" —
     * it removes every human the voice agent can transfer a live caller to, and answers
     * `{success:true}`. The caller's obligation is therefore two-fold: build the array from a
     * successful [load], and confirm the count with the operator before calling this.
     *
     * ⚠️ AN EMPTY ARRAY IS STILL A LEGITIMATE SAVE and is not refused here. An operator who
     * genuinely wants no transfer targets must be able to say so; the guard is the confirmation in
     * the UI, not a repository that second-guesses a request it was given.
     */
    suspend fun saveDirectory(request: DirectoryPatchRequest): SaveOutcome =
        commit(request.workspaceId) { api.saveDirectory(request) }

    /**
     * Replace the routing rules, then re-read.
     *
     * ⛔ WHOLESALE, LIKE THE DIRECTORY, and with the same "an empty array wipes it" property. The
     * one difference is that an OMITTED array is a 400 rather than a wipe — which the request type
     * makes unreachable anyway, since its array is non-nullable.
     *
     * ⚠️ A 400 HERE IS OFTEN A REAL, SPECIFIC REFUSAL rather than a client fault: a workspace that
     * restricts voices or models rejects a rule naming one outside its allow-list, by name. That
     * arrives as [ApiResult.HttpFailure] and its message is worth showing verbatim.
     */
    suspend fun saveRoutingRules(request: RoutingRulesRequest): SaveOutcome =
        commit(request.workspaceId) { api.saveRoutingRules(request) }

    /**
     * Run a write, assert its envelope, then re-read the config it changed.
     *
     * ⛔ THE ENVELOPE IS CHECKED BEFORE THE RE-READ, so a `{}` body cannot be reported as a
     * successful save. Both routes answer `{success:true}` and nothing else, which means the flag
     * is the ONLY thing distinguishing a real 200 from a structurally empty one.
     */
    private suspend fun commit(
        workspaceId: String,
        write: suspend () -> ApiResult<com.distronode.districtai.core.model.WorkspaceConfigSaveResponse>,
    ): SaveOutcome {
        val written = when (val result = write()) {
            is ApiResult.Success ->
                rejectedEnvelope(SAVE_ENVELOPE, result.value.success)
            is ApiResult.Failure -> result
        }
        if (written != null) return SaveOutcome.NotSaved(written)

        return when (val reread = load(workspaceId)) {
            is ApiResult.Success -> SaveOutcome.Saved(reread.value)
            // ⛔ NOT `NotSaved`. The write already returned 200; only the read back failed.
            is ApiResult.Failure -> SaveOutcome.SavedButStale(reread)
        }
    }

    private companion object {
        const val CONFIG_ENVELOPE = "WorkspaceConfigResponse"
        const val SAVE_ENVELOPE = "WorkspaceConfigSaveResponse"
    }
}

/**
 * What happened to a workspace-settings write.
 *
 * ⛔ THREE CASES, NOT TWO, AND THE MIDDLE ONE IS THE REASON THIS TYPE EXISTS. Every save on this
 * surface is followed by a re-read, because neither route echoes the config it wrote — so there
 * is a real outcome in which the WRITE LANDED and the READ DID NOT. Reporting that as a failure
 * is the dangerous direction: an operator told their change did not save will change the form
 * back and save again, through a route that replaces the stored array wholesale, from state that
 * is now stale. Reporting it as a success is the other wrong answer, because the screen would
 * then be showing pre-save values as though they were confirmed.
 */
sealed interface SaveOutcome {

    /** The write landed and the fresh config is in hand. */
    data class Saved(val config: WorkspaceConfig) : SaveOutcome

    /**
     * ⛔ THE WRITE LANDED. Only the read back failed, so what is on screen is stale rather than
     * wrong — say so, and offer a re-read rather than a re-save.
     */
    data class SavedButStale(val failure: ApiResult.Failure) : SaveOutcome

    /** Nothing was written. The operator's edits are still theirs to keep and retry. */
    data class NotSaved(val failure: ApiResult.Failure) : SaveOutcome
}
