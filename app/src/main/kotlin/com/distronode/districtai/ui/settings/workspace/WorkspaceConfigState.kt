package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SaveOutcome
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText

/**
 * Whether a workspace-settings screen has the configuration it needs in order to be allowed to
 * save.
 *
 * ⛔ THERE IS NO FOURTH CASE, AND ADDING ONE IS THE BUG THIS TYPE EXISTS TO PREVENT. Specifically
 * there is no "we could not load, here is an empty form anyway". Three of this surface's save
 * routes REPLACE their stored value wholesale rather than merging it, so a form rendered from
 * nothing and then saved does not save nothing — it DELETES the transfer directory the voice
 * agent routes live callers through, or the agent's tool allowlist. [LoadFailed] therefore
 * carries no config and no draft: a screen in that state may offer a retry and nothing else, and
 * the screen tests assert that no editable field exists in it.
 *
 * ⚠️ [Ready] holds the WHOLE [WorkspaceConfig] rather than the one section a given screen edits,
 * because the section is not the unit of safety. `allowedTools` has to be carried back complete,
 * including entries this client's catalog does not know about.
 */
sealed interface ConfigState {

    data object Loading : ConfigState

    /** The hydrated configuration. ⛔ The ONLY state in which a save may be built. */
    data class Ready(val config: WorkspaceConfig) : ConfigState

    /**
     * ⛔ RETRY ONLY. No config, therefore no editable field, therefore no save. See the ⛔ on the
     * interface — this is the case that decides whether the app can destroy a workspace's
     * configuration.
     */
    data class LoadFailed(val failure: FailureText) : ConfigState
}

/**
 * What happened to one section's save.
 *
 * ⛔ [SavedButStale] IS NOT A FAILURE AND MUST NOT BE DRAWN AS ONE. Neither save route echoes the
 * config it wrote, so every save is followed by a re-read — which means there is a real outcome
 * where the WRITE LANDED and the READ DID NOT. Telling the operator their change did not save is
 * the dangerous direction: they would change the form back and save again, through a route that
 * replaces the stored array wholesale, from state that is now stale.
 *
 * ⚠️ [Failed] KEEPS THE OPERATOR'S EDITS. Nothing here clears the draft — a failed save that also
 * discarded what someone typed would be two losses for one fault.
 */
sealed interface SaveState {

    data object Idle : SaveState

    /** ⚠️ Inputs are disabled while this is showing, so a second submit cannot race the first. */
    data object Saving : SaveState

    data object Saved : SaveState

    /** ⛔ The write LANDED; only the re-read failed. Offer a re-read, never a re-save. */
    data class SavedButStale(val failure: FailureText) : SaveState

    /** Nothing was written. The edits are still on screen and still the operator's. */
    data class Failed(val failure: FailureText) : SaveState
}

/** ⚠️ True only while a write is genuinely in flight — what disables every input on the screen. */
val SaveState.busy: Boolean get() = this == SaveState.Saving

/**
 * The banner a finished save is drawn as.
 *
 * ⛔ ONE MAPPING FOR EVERY SECTION'S SAVE, BECAUSE THE RULES ON [SaveState] ARE EASY TO RE-DERIVE
 * WRONGLY. Five ViewModels used to hand-copy it, and a copy that drew [SaveOutcome.SavedButStale] as
 * [SaveState.Failed] would invite the operator to re-save a wholesale-replace array from a stale
 * baseline.
 */
val SaveOutcome.saveState: SaveState
    get() = when (this) {
        is SaveOutcome.Saved -> SaveState.Saved
        is SaveOutcome.SavedButStale -> SaveState.SavedButStale(failure.toFailureText())
        is SaveOutcome.NotSaved -> SaveState.Failed(failure.toFailureText())
    }

/**
 * The fresh configuration to adopt as the new baseline, or null when there is none.
 *
 * ⚠️ NULL FOR [SaveOutcome.SavedButStale] AS WELL AS FOR [SaveOutcome.NotSaved]. The stale case
 * wrote, but the re-read failed, so the only config in hand is the pre-save one already on screen.
 */
val SaveOutcome.savedConfig: WorkspaceConfig? get() = (this as? SaveOutcome.Saved)?.config

/**
 * The thirteen capabilities this client knows how to name, in the order the web console lists them.
 *
 * ⛔ THIS IS A DISPLAY CATALOG, NOT THE SOURCE OF TRUTH FOR WHAT IS ENABLED. The stored
 * `allowedTools` array is, and it can legitimately contain ids that are not here:
 * `transfer_to_creator` is retired, and workspaces that still store it are migrated
 * on load by the voice agent, so it is live data rather than a hypothetical. Because
 * `PATCH workspace/tools` REPLACES the array wholesale, a toggle list built only from this
 * catalog would silently drop such an id on the next save. [capabilityRows] therefore renders the
 * union, and [applyToggles] never invents a list from this constant alone.
 *
 * ⛔ IT IS ALSO NOT THE DEFAULT; see [DEFAULT_ALLOWED_TOOL_IDS]. Not every capability defaults
 * on, so the catalog and the defaults are different lists.
 *
 * ⚠️ ANY ID THE WEB CATALOG GAINS HAS TO ARRIVE HERE IN THE SAME CYCLE. A missing id is not
 * cosmetic: a list built from this catalog and saved through a wholesale-replace route would
 * switch off, from a phone, a capability the web console has on.
 */
internal val CAPABILITY_CATALOG: List<String> = listOf(
    "transfer_to_person",
    "transfer_to_agent",
    "dispatch_email",
    "check_availability",
    "book_appointment",
    "create_or_update_contact",
    "leave_message",
    "search_knowledge_base",
    "send_sms",
    // District AI Scheduling: the platform's own booking tenancy, NOT the connected
    // Google/Microsoft calendar that check_availability and book_appointment address.
    // All four are off by default — see DEFAULT_ALLOWED_TOOL_IDS.
    "list_event_types",
    "list_appointments",
    "cancel_appointment",
    "reschedule_appointment",
)

/**
 * The capabilities that are OFF for a workspace that has never been asked.
 *
 * Kept as its own constant rather than a slice of [CAPABILITY_CATALOG] so that adding a future
 * default-off capability is one edit in one place, and so the test that pins web/Android
 * agreement has something to name.
 *
 * ⚠️ Declared BEFORE [DEFAULT_ALLOWED_TOOL_IDS], which subtracts it. Kotlin initialises top-level
 * properties in declaration order, so the other way round it would subtract an empty set and the
 * default would silently become all thirteen — the exact bug this pair exists to prevent.
 */
internal val SCHEDULING_TOOL_IDS: Set<String> = setOf(
    "list_event_types",
    "list_appointments",
    "cancel_appointment",
    "reschedule_appointment",
)

/**
 * What a workspace with NO stored `allowedTools` is shown as allowing.
 *
 * ⛔ NOT THE WHOLE CATALOG, AND THE DIFFERENCE IS THE WHOLE POINT. This mirrors the web form's
 * `DEFAULT_ALLOWED_TOOL_IDS`, which filters `AVAILABLE_TOOLS` on `defaultOn !== false`: the nine
 * historical capabilities keep their old behaviour byte for byte, and the four scheduling tools
 * stay off until someone ticks them deliberately.
 *
 * ⛔ THE TWO CLIENTS MUST AGREE HERE OR THE PHONE WRITES A DIFFERENT ANSWER THAN THE BROWSER.
 * `PATCH workspace/tools` replaces the array wholesale, so if this list said "all thirteen", an
 * operator opening the screen on an untouched workspace and pressing save would switch a
 * caller-facing cancel and reschedule ON — by inference, from a default nobody chose, on a
 * workspace that may not even have a scheduling tenancy.
 */
internal val DEFAULT_ALLOWED_TOOL_IDS: List<String> = CAPABILITY_CATALOG - SCHEDULING_TOOL_IDS

/**
 * One row in the capability list.
 *
 * @param labelRes null for an id this client's catalog does not know. The row still renders (by
 *   [id]) and still saves, because dropping it would be data loss through a wholesale-replace
 *   route — see the ⛔ on [CAPABILITY_CATALOG].
 */
// ⚠️ PUBLIC, unlike its neighbours in this file, only because `CapabilitiesUiState.rows` is a
// public property that returns them — Kotlin refuses to expose an internal type argument there.
data class CapabilityRow(
    val id: String,
    val labelRes: Int?,
    val enabled: Boolean,
)

/**
 * id -> string resource.
 *
 * ⚠️ A MAP RATHER THAN A `when`, and detekt is why. Each catalog entry is one more branch, so
 * the `when` this replaced hit CyclomaticComplexMethod (15, against a threshold of 15) on the
 * commit that added the last five ids — a rule that fires on the arm count alone and would fire
 * again on the next capability. A lookup is complexity 1 and grows for free.
 */
private val CAPABILITY_LABELS: Map<String, Int> = mapOf(
    "transfer_to_person" to R.string.capabilities_transfer_to_person,
    "transfer_to_agent" to R.string.capabilities_transfer_to_agent,
    "dispatch_email" to R.string.capabilities_dispatch_email,
    "check_availability" to R.string.capabilities_check_availability,
    "book_appointment" to R.string.capabilities_book_appointment,
    "create_or_update_contact" to R.string.capabilities_create_or_update_contact,
    "leave_message" to R.string.capabilities_leave_message,
    "search_knowledge_base" to R.string.capabilities_search_knowledge_base,
    "send_sms" to R.string.capabilities_send_sms,
    "list_event_types" to R.string.capabilities_list_event_types,
    "list_appointments" to R.string.capabilities_list_appointments,
    "cancel_appointment" to R.string.capabilities_cancel_appointment,
    "reschedule_appointment" to R.string.capabilities_reschedule_appointment,
)

/** The label for a known capability, or null for one that arrived from the server unrecognised. */
internal fun capabilityLabel(id: String): Int? = CAPABILITY_LABELS[id]

/**
 * What the stored allowlist means before anyone has touched a toggle.
 *
 * ⛔ AN ABSENT LIST MEANS THE DEFAULTS ARE ON, NOT NONE, and not everything either. It is the
 * shape a workspace has on the day it is created (`toolConfig` is null until something writes
 * it). A client that read absent as an empty allowlist and then saved would
 * switch every capability OFF for an operator who opened the screen only to look at it, through
 * a route that replaces the array wholesale.
 *
 * ⛔ IT DOES NOT RETURN THE WHOLE CATALOG, because the catalog and the defaults are not the same
 * list: the web form defaults the four scheduling tools OFF, so returning the catalog here would
 * have the phone quietly switch a caller-facing
 * cancel and reschedule ON for any untouched workspace whose operator pressed save. It reads
 * [DEFAULT_ALLOWED_TOOL_IDS] instead, which is the same nine ids the browser shows.
 *
 * ⚠️ AN EMPTY LIST IS A DIFFERENT ANSWER AND IS RETURNED AS ONE: the operator turned everything
 * off deliberately, and that has to survive a round trip.
 */
internal fun baselineTools(config: WorkspaceConfig): List<String> =
    config.toolConfig?.allowedTools ?: DEFAULT_ALLOWED_TOOL_IDS

/**
 * The rows to draw: every catalog capability, plus anything stored that the catalog does not
 * know, appended in the order the server sent it.
 *
 * ⚠️ CATALOG ORDER FIRST so the list reads the same way the web console does, and unknown ids
 * last so they are visibly the exception rather than interleaved with the familiar names.
 */
internal fun capabilityRows(
    config: WorkspaceConfig,
    toggles: Map<String, Boolean>,
): List<CapabilityRow> {
    val baseline = baselineTools(config)
    val unknown = baseline.filterNot { it in CAPABILITY_CATALOG }
    return (CAPABILITY_CATALOG + unknown).map { id ->
        CapabilityRow(
            id = id,
            labelRes = capabilityLabel(id),
            enabled = toggles[id] ?: (id in baseline),
        )
    }
}

/**
 * The complete list to send, built from what was LOADED with the operator's toggles applied.
 *
 * ⛔ THE BASELINE IS THE STARTING POINT, NOT THE CATALOG, AND THE ORDER OF WHAT WAS ALREADY THERE
 * IS PRESERVED. `PATCH workspace/tools` replaces the stored array with exactly this value, so
 * rebuilding it from the catalog would both drop unrecognised ids and silently reorder a list the
 * server is storing verbatim. Turning nothing off and nothing on must produce the identical array
 * that was loaded — that property is what makes an accidental save harmless, and it is asserted.
 *
 * ⚠️ Newly enabled ids are appended in CATALOG order rather than in map-iteration order, so the
 * result is deterministic regardless of the sequence the operator tapped things in.
 */
internal fun applyToggles(baseline: List<String>, toggles: Map<String, Boolean>): List<String> {
    val kept = baseline.filter { toggles[it] != false }
    val added = CAPABILITY_CATALOG.filter { toggles[it] == true && it !in baseline }
    return kept + added
}
