package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.AvailabilityResponse
import com.distronode.districtai.core.model.CallHandlingResponse

/**
 * Who answers a call, and whether the person asking can be rung at all.
 *
 * ⛔ TWO ROUTES THAT LOOK LIKE ONE FEATURE AND ARE NOT ONE SCOPE. `workspace/call-handling` is a
 * WORKSPACE setting: every member sees the same value and a mutator changes it for all of them.
 * `workspace/availability` is a fact about the CALLER'S OWN membership row — the PATCH takes no
 * email and no user id, so there is no way to express "set someone else's availability" and no
 * screen may imply otherwise. They share an interface because they share a screen, not a scope.
 *
 * ⛔ NEITHER WRITE IS A WHOLESALE REPLACE, WHICH MAKES THEM THE FIRST ON THIS SURFACE THAT DO NOT
 * NEED A LOAD FIRST. `saveTools`, `saveRoutingRules` and `saveDirectory` all overwrite a stored
 * array, so a form that opened empty and saved would delete something; these two write scalars and
 * the PATCH accepts either field alone.
 *
 * ⚠️ BOTH PATCHES ECHO THE NEW VALUES, unlike every other workspace-settings write here (which
 * answer a bare `{success:true}` and force a re-read). So a save adopts its own response.
 */
interface CallHandlingApi {

    /**
     * How this workspace answers a call.
     *
     * ⚠️ ADMITS `viewer` WHILE THE PATCH DOES NOT — the opposite split from `workspace/config`,
     * whose read excludes them because it carries staff transfer numbers. Nothing here is a phone
     * number, so a screen shows a viewer the real setting read-only rather than hiding it.
     */
    suspend fun callHandling(workspaceId: String): ApiResult<CallHandlingResponse>

    /**
     * Change who answers, or how long the app rings.
     *
     * ⛔ AT LEAST ONE OF THE TWO FIELDS IS REQUIRED AND AN EMPTY BODY IS A 400, not a no-op. Both
     * parameters are nullable so a caller can send either alone; a caller must never send both as
     * null, because the encoder drops nulls and the route refuses a body carrying only the
     * workspace.
     *
     * ⛔ AN UNKNOWN MODE OR AN OUT-OF-RANGE RING IS A **400**, NOT A COERCED VALUE. The read
     * normalises what is STORED; the write validates what ARRIVES, and those are deliberately not
     * the same rule — a stored value predating the vocabulary must still be displayable, while a
     * client sending one must be told it is wrong.
     */
    suspend fun saveCallHandling(
        workspaceId: String,
        callHandling: String?,
        appRingSeconds: Int?,
    ): ApiResult<CallHandlingResponse>

    /**
     * Whether the caller can be rung for this workspace's calls.
     *
     * ⚠️ A VIEWER GETS `false` WITH `reason: "role"` AND NO DATABASE READ. It is a real answer
     * rather than a refusal, so this route never 403s for a role — which means a screen must render
     * the reason instead of assuming a 200 carries a toggleable value.
     */
    suspend fun availability(workspaceId: String): ApiResult<AvailabilityResponse>

    /**
     * Make the caller available, or not.
     *
     * ⛔ IT WRITES THE CALLER'S OWN MEMBERSHIP ROW AND TAKES NO IDENTITY. There is no `email` and
     * no `userId` parameter here and there must never be one: an identity that arrives as an
     * argument is an identity the caller chose, which is the same rule the voice tools document.
     *
     * ⛔ **409 WHEN THERE IS NO MEMBER ROW TO WRITE.** That is not a validation failure and not a
     * permission failure: the person holds their role through the owner fallback, so there is
     * genuinely nothing to set, and the ring fan-out reads `WorkspaceMember`. It is the write-side
     * twin of the read's `reason: "no_member_row"` and needs the same sentence rather than a
     * generic refusal.
     */
    suspend fun saveAvailability(
        workspaceId: String,
        availableForCalls: Boolean,
    ): ApiResult<AvailabilityResponse>
}
