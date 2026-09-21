package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/district/setup`: the new-customer setup wizard's state (setup wizard plan 2.5).
 *
 * ⛔ THE APP READS ONE FACT FROM THIS AND DOES NOT RUN THE WIZARD. The wizard lives on the web; the
 * app only decides whether to show "Finish setting up on the web", via [needsWebSetup]. Everything
 * else is modelled because the contract gate decodes with unknown keys refused, not because a
 * screen reads it.
 *
 * ⛔ EVERY FIELD HAS A DEFAULT. Removing a key server-side must not break an installed build (a
 * property with no default throws on a MISSING key whatever the unknown-key setting), and the
 * server already omits `completedAt`, `paidAt` and friends until they are true.
 *
 * ⚠️ OWNER ONLY. A member, or a caller acting through support access, gets 403
 * `workspace_owner_only` and never a body, so there is no "not the owner" field to read here.
 */
@Serializable
data class DistrictSetupResponse(
    /**
     * ⛔ NULL MEANS "THIS WORKSPACE NEVER SEES THE WIZARD", not "not started". Every workspace that
     * existed before the wizard is null and stays null; only a real paid checkout writes one.
     */
    val setupProgress: SetupProgress? = null,
    /**
     * ⚠️ OPAQUE ON PURPOSE. The business step's answers (hours, services, and so on) are free-form
     * and edited only on the web, and nothing in the app reads them. Typed as an object so a
     * reshaped facts payload cannot fail this decode, which is the one read the card depends on.
     */
    val businessFacts: JsonObject? = null,
    /** The workspace's data region, e.g. "ca". */
    val region: String? = null,
    /** The stored subscription tier, mixed case, e.g. "VoicePro". */
    val tier: String? = null,
    /** Numbers the tier includes, or null when no ceiling applies. */
    val includedNumbers: Int? = null,
    /** Numbers the workspace currently holds. */
    val numbersHeld: Int = 0,
) {
    /**
     * Whether to show "Finish setting up on the web": a workspace that IS in the wizard (non-null
     * progress) and has not finished it. A null progress (pre-wizard workspace) and a completed one
     * both answer false.
     */
    val needsWebSetup: Boolean
        get() = setupProgress != null && setupProgress.completedAt == null
}

/** The wizard's stored progress. Server-owned timestamps are absent until they happen. */
@Serializable
data class SetupProgress(
    val steps: SetupSteps = SetupSteps(),
    val paidAt: String? = null,
    val firstRealCallAt: String? = null,
    /** ⛔ THE CARD'S SHOW CONDITION: absent while the owner is still setting up. */
    val completedAt: String? = null,
    /** The owner's consent to a test call, verbatim wording included. Not read by the app. */
    val testCallConsent: JsonObject? = null,
    /** The forwarding check's state. Not read by the app. */
    val forwardingCheck: JsonObject? = null,
)

/**
 * The six wizard steps, each [SETUP_STEP_TODO], [SETUP_STEP_DONE] or [SETUP_STEP_SKIPPED].
 *
 * ⚠️ STRINGS, NOT AN ENUM. A seventh state added server-side must not make the whole response
 * undecodable, which an enum would.
 */
@Serializable
data class SetupSteps(
    val business: String = SETUP_STEP_TODO,
    val number: String = SETUP_STEP_TODO,
    val receptionist: String = SETUP_STEP_TODO,
    val callers: String = SETUP_STEP_TODO,
    val calls: String = SETUP_STEP_TODO,
    val golive: String = SETUP_STEP_TODO,
)

const val SETUP_STEP_TODO: String = "todo"
const val SETUP_STEP_DONE: String = "done"
const val SETUP_STEP_SKIPPED: String = "skipped"
