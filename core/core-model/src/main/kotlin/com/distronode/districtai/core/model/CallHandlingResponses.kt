package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * Who answers a call, and whether the person asking can be rung at all.
 *
 * ⛔ TWO ROUTES THAT LOOK LIKE ONE FEATURE AND ARE NOT ONE SCOPE, WHICH IS THE ONLY THING IN THIS
 * FILE THAT IS EASY TO GET WRONG. `workspace/call-handling` is a WORKSPACE setting: every member
 * sees the same value and a mutator changes it for all of them. `workspace/availability` is a fact
 * about the CALLER'S OWN membership row — the PATCH takes no email and no user id, so there is no
 * way to express "set someone else's availability" and no screen may imply otherwise.
 *
 * ⛔ NEITHER OF THESE IS A WHOLESALE REPLACE, WHICH MAKES THEM THE FIRST WRITES ON THIS SURFACE
 * THAT DO NOT NEED A LOAD FIRST. `saveTools`, `saveRoutingRules` and `saveDirectory` all overwrite
 * a stored array, so a form that opened empty and saved would delete something; these two write
 * scalars and the PATCH accepts either field alone. A screen still reads first, because it has to
 * draw the current value, but a failed read is a blank screen rather than a live delete.
 *
 * ⚠️ BOTH PATCHES ECHO THE NEW VALUES, unlike every other workspace-settings write on this surface
 * (which answer a bare `{success:true}` and force a re-read). So a save here adopts its own
 * response and needs no second request.
 */
@Serializable
data class CallHandlingResponse(
    val success: Boolean = false,
    /**
     * ⚠️ NORMALISED SERVER-SIDE, WHICH IS WHY THE CLIENT NEVER SEES A RAW STORED VALUE. An
     * unrecognised mode reads back as [CallHandling.DEFAULT] and an out-of-range ring is clamped,
     * so this read cannot hand a picker a selection it has no row for. ⛔ Do not re-normalise on
     * top of that: a client that clamped again would be a second opinion about a value the server
     * has already settled.
     */
    val callHandling: String = CallHandling.DEFAULT,
    val appRingSeconds: Int = CallHandling.DEFAULT_RING_SECONDS,
)

/**
 * The PATCH body.
 *
 * ⛔ AT LEAST ONE OF THE TWO FIELDS IS REQUIRED AND AN EMPTY BODY IS A 400, not a no-op. That is
 * why both are nullable here and why a caller must never send both as null: the request encoder's
 * `explicitNulls = false` would produce a body carrying only the workspace, which the route
 * refuses with "Nothing to update".
 *
 * ⛔ AN UNKNOWN MODE OR AN OUT-OF-RANGE RING IS A **400**, NOT A COERCED VALUE. The read normalises
 * what is STORED; the write validates what ARRIVES, and those are deliberately not the same rule.
 * Send a [CallHandling.MODES] value and [CallHandling.clampRing].
 *
 * ⚠️ [appRingSeconds] IS AN `Int` BECAUSE THE ROUTE'S ZOD SCHEMA IS `.int()`. A `Double` that
 * encoded as `20.0` would be refused as fractional.
 */
@Serializable
data class CallHandlingPatchRequest(
    val workspaceId: String,
    val callHandling: String? = null,
    val appRingSeconds: Int? = null,
)

/**
 * The three inbound behaviours and the ring window, named once.
 *
 * ⚠️ THE VALUES ARE THE SERVER'S WIRE STRINGS and are never localised. A display string sent in
 * their place is a 400 from the PATCH.
 */
object CallHandling {
    const val AI_FIRST: String = "ai_first"
    const val AI_THEN_APP: String = "ai_then_app"
    const val APP_FIRST: String = "app_first"

    val MODES: List<String> = listOf(AI_FIRST, AI_THEN_APP, APP_FIRST)

    /** ⚠️ What an unrecognised STORED mode reads back as. See [CallHandlingResponse.callHandling]. */
    const val DEFAULT: String = AI_FIRST

    const val MINIMUM_RING_SECONDS: Int = 5
    const val MAXIMUM_RING_SECONDS: Int = 30
    const val DEFAULT_RING_SECONDS: Int = 20

    fun clampRing(seconds: Int): Int =
        seconds.coerceIn(MINIMUM_RING_SECONDS, MAXIMUM_RING_SECONDS)

    fun isKnown(mode: String): Boolean = mode in MODES
}

/**
 * Whether the caller can be rung for this workspace's calls.
 *
 * ⛔ [reason] IS ALWAYS PRESENT ON THE WIRE AND IS `null` WHEN THERE IS NOTHING TO EXPLAIN. That is
 * the route's own decision and it is the right one for this client: the decoder is strict about
 * unknown keys, so a key that appeared only in the interesting cases would be exactly the shape
 * that fails to decode the answer worth reading.
 *
 * ⚠️ A VIEWER GETS `false` WITH `reason: "role"` AND NO DATABASE READ. It is a real answer rather
 * than a refusal, so this route never 403s for a role — which means a screen must render the
 * reason instead of assuming a 200 carries a toggleable value.
 */
@Serializable
data class AvailabilityResponse(
    val success: Boolean = false,
    val availableForCalls: Boolean = false,
    val reason: String? = null,
)

/**
 * The PATCH body.
 *
 * ⛔ IT WRITES THE CALLER'S OWN MEMBERSHIP ROW AND TAKES NO IDENTITY. There is no `email` and no
 * `userId` field here and there must never be one: an identity that arrives as an argument is an
 * identity the caller chose. A roster screen offering to toggle a colleague cannot be built on
 * this and must not be faked with one.
 */
@Serializable
data class AvailabilityPatchRequest(
    val workspaceId: String,
    val availableForCalls: Boolean,
)

/**
 * The two refusals the read can carry, which are different facts.
 *
 * ⚠️ [ROLE] is structural and permanent for a viewer; [NO_MEMBER_ROW] means the caller holds their
 * role through the owner fallback and has no row to write. The PATCH answers **409** for the
 * second rather than creating one, because creating a member row would also enrol that person in
 * the MESSAGE notification fan-out.
 */
object AvailabilityReason {
    const val ROLE: String = "role"
    const val NO_MEMBER_ROW: String = "no_member_row"
}
