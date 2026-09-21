package com.distronode.districtai.ui.workflows

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.CampaignStatus
import com.distronode.districtai.core.model.WorkflowListItem
import com.distronode.districtai.core.model.WorkflowRun
import com.distronode.districtai.ui.FailureText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The automation monitor's state.
 *
 * ⛔ FOUR INDEPENDENT FAILURES, NEVER ONE. The SDR campaign card, the workflow list, each
 * workflow's run history and the toggle all fail separately — the same rule
 * [com.distronode.districtai.ui.marketplace.MarketplaceUiState] states for its two reads, and it
 * matters more here because the screen answers "is my automation working". A campaign read that
 * 500'd must not blank a workflow list that answered, and a toggle that was refused must not blank
 * either. The list on screen is a correct answer already in hand.
 *
 * ⛔ THE RUN HISTORY IS CACHED PER WORKFLOW AND THE LIST IS NOT. Expanding a workflow costs a
 * request; collapsing and re-expanding the same one must not, because the natural way to compare
 * two workflows is to open one, close it, open the other, and go back. [reload] is the only thing
 * that clears the cache, which is what makes a session change or a retry honest.
 */
data class WorkflowsUiState(
    val campaign: CampaignState = CampaignState.Loading,
    val workflows: WorkflowListState = WorkflowListState.Loading,
    /**
     * Which workflow's history is open, or null.
     *
     * ⚠️ ONE AT A TIME, deliberately. Run rows are tall (a status, two timestamps, a line per
     * action and possibly an error paragraph), and several expanded at once turns the list into a
     * wall in which the workflow NAMES — the thing being scanned for — are the smallest text on
     * screen.
     */
    val expanded: String? = null,
    /** Per-workflow history, keyed by workflow id. See the ⛔ on the class for why it is kept. */
    val runs: Map<String, RunHistory> = emptyMap(),
    /**
     * ⚠️ A SET, NOT A BOOLEAN, unlike [com.distronode.districtai.ui.devices.DevicesUiState.busy].
     * Devices uses one flag because both of its writes end sessions and race one refresh; these
     * writes touch DIFFERENT rows and nothing re-reads afterwards, so two operators' worth of
     * impatience on two different switches is not a race. What must be prevented is a second tap
     * on the SAME switch while the first is in flight, which would leave the optimistic value and
     * the server disagreeing about which way it ended up.
     */
    val pendingToggles: Set<String> = emptySet(),
    /** ⚠️ Shown ALONGSIDE the list, never instead of it. Cleared on the next toggle attempt. */
    val toggleFailure: FailureText? = null,
    /**
     * The pause/resume awaiting confirmation, or null.
     *
     * ⛔ A CONFIRMATION EXISTS HERE AND NOT ON THE PER-WORKFLOW SWITCH, WHICH IS A DELIBERATE
     * ASYMMETRY RATHER THAN AN OVERSIGHT. Flipping one workflow changes what happens the NEXT time
     * its trigger fires; pausing the campaign stops an engine that is working through a contact
     * list right now, and resuming one starts spending call and message credit again. The second
     * is worth a sentence in front of it and the first is not — a confirmation on every switch
     * would train the operator to dismiss the one that matters.
     */
    val campaignConfirm: CampaignConfirm? = null,
    /**
     * ⚠️ A SINGLE FLAG, unlike [pendingToggles]. There is exactly one campaign per workspace, so
     * "which one is in flight" is not a question — and a second submit while the first is running
     * could land in either order and leave the card showing the loser.
     */
    val campaignPending: Boolean = false,
    /** ⚠️ Shown ON the campaign card, beside a status that is still the last one the server sent. */
    val campaignFailure: FailureText? = null,
)

/**
 * A pause or resume the operator has asked for but not yet confirmed.
 *
 * @param enable the value that would be WRITTEN, not the current one. Carrying the target rather
 *   than "the user tapped the button" is what makes the confirmation copy and the request agree —
 *   and it survives the card re-rendering underneath the dialog after a background reload.
 */
data class CampaignConfirm(val enable: Boolean)

sealed interface CampaignState {

    data object Loading : CampaignState

    /**
     * @param status ⚠️ ALL-EMPTY IS A REAL STATE, NOT A FAILURE. A workspace that never opened the
     *   campaigns tab reads as `{false, null, null}`, and the route normalises "absent" and "off"
     *   into that one shape on purpose. It renders as "Paused" with an explanatory caption.
     */
    data class Ready(val status: CampaignStatus) : CampaignState

    data class Failed(val failure: FailureText) : CampaignState
}

sealed interface WorkflowListState {

    data object Loading : WorkflowListState

    /**
     * @param workflows ⚠️ EMPTY IS A LEGITIMATE ANSWER — most workspaces have never created one —
     *   and it renders as an explanatory empty state pointing at the web dashboard, never as a
     *   failure. The repository's envelope guard is what separates this from "we could not look".
     */
    data class Ready(val workflows: List<WorkflowListItem>) : WorkflowListState

    data class Failed(val failure: FailureText) : WorkflowListState
}

/**
 * One workflow's run history, as far as it has been read.
 *
 * ⛔ `hasMore` IS THE SERVER'S FLAG AND MUST NOT BE RE-DERIVED FROM `runs.size`. It is computed
 * from a real `total`, so it stays correct when a page comes back short — which happens whenever
 * a run is written or deleted between two requests. A client-side `size < limit` would then end
 * the list early and hide history that exists.
 *
 * @param loading a "load more" is in flight. ⚠️ Separate from the list being empty: the FIRST
 *   fetch also sets it, and the screen shows a skeleton for that case and a spinner under the
 *   existing rows for the second.
 * @param failure ⚠️ CARRIED BESIDE THE ROWS ALREADY FETCHED rather than replacing them. A "load
 *   more" that failed on page three must not discard pages one and two.
 */
data class RunHistory(
    val runs: List<WorkflowRun> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val failure: FailureText? = null,
)

/**
 * How many runs one page holds.
 *
 * ⚠️ MATCHES THE SERVER'S OWN DEFAULT (10) RATHER THAN ITS CEILING (50), deliberately. A run row
 * is tall and this is a phone; asking for the maximum would make the first expand slow and the
 * "load more" control decorative. The server clamps to 1..50 and ECHOES what it applied, so this
 * number being wrong would be visible rather than silent.
 */
const val RUNS_PAGE_SIZE: Int = 10

/**
 * The label for a trigger, or null when this build has never heard of it.
 *
 * ⛔ NULL IS THE POINT OF THIS FUNCTION AND THE CALLER MUST RENDER THE RAW STRING FOR IT. The
 * column is a bare `String` server-side, validated on write against a list that has already grown
 * once (`call_ended_answered` and `call_ended_unanswered` were added alongside `call_ended`, which
 * still fires for every terminal call). An installed build must keep drawing a workflow whose
 * trigger it does not recognise — a `when` with no else, or a label that read "Unknown", would
 * turn a server-side vocabulary addition into a screen that lies about existing automation.
 *
 * ⚠️ THE THREE CALL-END TRIGGERS ARE NOT SYNONYMS AND THEIR LABELS SAY SO. `call_ended` fires for
 * EVERY terminal call; the answered/unanswered pair fires alongside it, exactly one per call. A
 * workflow on all three runs twice.
 */
@StringRes
fun triggerLabelRes(trigger: String): Int? = when (trigger) {
    TRIGGER_CALL_ENDED -> R.string.workflow_trigger_call_ended
    TRIGGER_CALL_ENDED_ANSWERED -> R.string.workflow_trigger_call_ended_answered
    TRIGGER_CALL_ENDED_UNANSWERED -> R.string.workflow_trigger_call_ended_unanswered
    TRIGGER_NEGATIVE_SENTIMENT -> R.string.workflow_trigger_negative_sentiment
    TRIGGER_POSITIVE_SENTIMENT -> R.string.workflow_trigger_positive_sentiment
    TRIGGER_NEUTRAL_SENTIMENT -> R.string.workflow_trigger_neutral_sentiment
    TRIGGER_INTENT_DETECTED -> R.string.workflow_trigger_intent_detected
    TRIGGER_SMS_RECEIVED -> R.string.workflow_trigger_sms_received
    TRIGGER_CONTACT_CREATED -> R.string.workflow_trigger_contact_created
    TRIGGER_DNC_REGISTERED -> R.string.workflow_trigger_dnc_registered
    else -> null
}

/**
 * The badge tone for a run's status.
 *
 * ⛔ `partial` IS A WARNING, NOT A SUCCESS, AND THAT IS THE ONE THAT MATTERS. It means some actions
 * ran and some did not — a follow-up email that went while the CRM tag did not — which draws
 * exactly like a healthy run if it is toned green. ⚠️ `skipped` is NEUTRAL rather than a warning:
 * the engine skips a run whose conditions were not met, which is the workflow working.
 *
 * ⚠️ An unrecognised status is neutral rather than an error tone: the vocabulary is free text on
 * the wire, and colouring an unknown value red would report a fault that may not exist.
 */
fun runTone(status: String): Tone = when (status) {
    RUN_STATUS_SUCCESS -> Tone.Success
    RUN_STATUS_PARTIAL -> Tone.Warning
    RUN_STATUS_FAILED -> Tone.Danger
    else -> Tone.Neutral
}

/**
 * The badge tone for one action's outcome. Same reasoning as [runTone], one level down.
 *
 * ⚠️ `skipped` IS NEUTRAL AND CARRIES THE MOST USEFUL TEXT ON THE ROW. The engine attaches a
 * `reason` to a skip — no contact, no phone number, missing metadata — so a skip is the outcome an
 * operator can actually act on, and the screen shows the reason beside it.
 */
fun outcomeTone(outcome: String): Tone = when (outcome) {
    OUTCOME_OK -> Tone.Success
    OUTCOME_FAILED -> Tone.Danger
    else -> Tone.Neutral
}

/**
 * An ISO-8601 instant as a short local date and time.
 *
 * ⛔ FALLS BACK TO THE RAW STRING RATHER THAN THROWING OR BLANKING. These timestamps come straight
 * off `Date.toISOString()` today, but this is the one place in this screen where a server-side
 * format change would crash a list rather than degrade it — and an unparseable timestamp printed
 * verbatim is still more useful to an operator than an empty cell.
 *
 * ⚠️ ZONE AND LOCALE ARE PARAMETERS WITH DEFAULTS so a test can pin them. Without that, an
 * assertion on a rendered timestamp passes on the machine that wrote it and fails on a runner in
 * another timezone — the same trap `BillingFormat.formatUnixSeconds` documents.
 *
 * ⚠️ `java.time` on minSdk 26 rides core-library desugaring, already enabled by the base
 * convention plugin.
 */
fun formatRunTimestamp(
    iso: String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = try {
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.parse(iso).atZone(zone))
} catch (_: DateTimeParseException) {
    iso
}

/** The ten triggers this build knows. ⚠️ Not exhaustive of the server's — see [triggerLabelRes]. */
const val TRIGGER_CALL_ENDED: String = "call_ended"
const val TRIGGER_CALL_ENDED_ANSWERED: String = "call_ended_answered"
const val TRIGGER_CALL_ENDED_UNANSWERED: String = "call_ended_unanswered"
const val TRIGGER_NEGATIVE_SENTIMENT: String = "negative_sentiment"
const val TRIGGER_POSITIVE_SENTIMENT: String = "positive_sentiment"
const val TRIGGER_NEUTRAL_SENTIMENT: String = "neutral_sentiment"
const val TRIGGER_INTENT_DETECTED: String = "intent_detected"
const val TRIGGER_SMS_RECEIVED: String = "sms_received"
const val TRIGGER_CONTACT_CREATED: String = "contact_created"
const val TRIGGER_DNC_REGISTERED: String = "dnc_registered"

/** The four run statuses the engine writes today. ⚠️ Free text on the wire; see [runTone]. */
const val RUN_STATUS_SUCCESS: String = "success"
const val RUN_STATUS_PARTIAL: String = "partial"
const val RUN_STATUS_FAILED: String = "failed"
const val RUN_STATUS_SKIPPED: String = "skipped"

/** The three per-action outcomes. ⚠️ Free text on the wire; see [outcomeTone]. */
const val OUTCOME_OK: String = "ok"
const val OUTCOME_SKIPPED: String = "skipped"
const val OUTCOME_FAILED: String = "failed"
