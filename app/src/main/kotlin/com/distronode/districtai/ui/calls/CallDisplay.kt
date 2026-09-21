package com.distronode.districtai.ui.calls

import com.distronode.districtai.core.model.CallSummary

/**
 * The display rules for one call, derived ONCE.
 *
 * ⛔ THIS EXISTS BECAUSE THE RULES HAD ALREADY DRIFTED, AND ONE OF THE DRIFTS HID A FAILURE.
 * `OverviewRepository.toActivity` in core-data derives exactly these fields for the overview's
 * Recent Activity, and both call screens ignored it and re-derived them inline — with the five
 * wire constants below copied into three files across two modules. Two divergences had already
 * shipped:
 *
 *   1. ⛔ A FAILED TRANSFER WAS INVISIBLE IN THE CALL LOG. The overview rendered both the
 *      transferred and the transfer-failed chip; the call log rendered only `success`. So the one
 *      list an operator scans specifically to find calls that did not reach a human was the one
 *      place that would not tell them.
 *   2. The detail screen appended the duration unconditionally where the log guarded on
 *      `durationRaw > 0`, so a missed call read "· 0s" — a call that never connected reported a
 *      length.
 *
 * ⚠️ THIS IS THE SMALLEST CORRECT FIX AND NOT THE RIGHT LONG-TERM HOME. The mapping belongs in
 * core-data beside `toActivity`, as one shared model both modules consume; that module is owned
 * elsewhere, so this is a single internal mapper with the constants defined once, used by both
 * call screens. Reported for the core owner: `RecentActivity` and this type should merge, and
 * `UNKNOWN_CALLER` / `DIRECTION_OUTBOUND` / `STATUS_IN_PROGRESS` / `STATUS_RINGING` /
 * `TRANSFER_SUCCESS` / `TRANSFER_FAILED` should be one exported set of wire constants.
 *
 * ⚠️ ONE DELIBERATE DIFFERENCE FROM `toActivity`, WHICH THE MERGE MUST PRESERVE. This reads the
 * caller from `CallSummary.number`, which the server has ALREADY resolved to a contact name where
 * one exists, falling back to the raw number and then the literal "Unknown". `toActivity` instead
 * reads `callerName` and falls back to `from`. Both end up at the same place for ordinary rows, but
 * `number` is the value the call screens have always shown, so it is kept rather than quietly
 * changing what the log displays as part of a de-duplication.
 */
internal data class CallDisplay(
    val id: String,
    /**
     * Null when there was no usable caller ID — render a placeholder.
     *
     * ⚠️ The API sends the literal "Unknown" for an unresolved caller. Printing that verbatim
     * reads as a name, which is why the null is produced here rather than at each render site.
     */
    val displayName: String?,
    val outbound: Boolean,
    /** The server's display status, already downgraded for a stale call. */
    val status: String,
    /**
     * ⚠️ DERIVED FROM THE DISPLAY STATUS, WHICH IS THE WHOLE POINT. The server has already
     * downgraded an in-progress call whose terminal webhook was lost to "no-answer", so only a
     * genuinely live call still carries these values. Recomputing liveness from a raw status
     * would resurrect the bug where a day-old stuck row renders a Live badge forever.
     */
    val live: Boolean,
    val transferred: Boolean,
    val transferFailed: Boolean,
    /** Pre-formatted in the OPERATOR's timezone by the server. ⛔ Not a parseable instant. */
    val time: String,
    /**
     * The human duration, or null when there is none worth showing.
     *
     * ⚠️ Null rather than "0s" for a call that never connected. See drift (2) in the class doc:
     * the server always formats a duration string, so the decision not to show it has to be made
     * from `durationRaw`.
     */
    val durationLabel: String?,
)

/** Map a wire row to its display rules. The only place these comparisons should appear in `app`. */
internal fun CallSummary.toDisplay(): CallDisplay = CallDisplay(
    id = id,
    displayName = number.takeUnless { it.isBlank() || it == UNKNOWN_CALLER },
    outbound = direction == DIRECTION_OUTBOUND,
    status = status,
    live = status == STATUS_IN_PROGRESS || status == STATUS_RINGING,
    transferred = transferStatus == TRANSFER_SUCCESS,
    transferFailed = transferStatus == TRANSFER_FAILED,
    time = time,
    // ⚠️ `durationRaw` is a property from another module, so Kotlin will not smart-cast it after
    // a null check — read it into a local first.
    durationLabel = durationRaw.let { seconds -> duration.takeIf { seconds != null && seconds > 0 } },
)

// ⚠️ DEFINED ONCE, HERE. These are wire values, not display copy, so they are not localizable and
// must never be duplicated — the copies in CallLogScreen and CallDetailScreen are what let the
// transfer-failed chip go missing from the log.
private const val UNKNOWN_CALLER = "Unknown"
private const val DIRECTION_OUTBOUND = "outbound"
private const val STATUS_IN_PROGRESS = "in-progress"
private const val STATUS_RINGING = "ringing"
private const val TRANSFER_SUCCESS = "success"
private const val TRANSFER_FAILED = "failed"
