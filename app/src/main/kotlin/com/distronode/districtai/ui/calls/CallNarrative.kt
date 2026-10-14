package com.distronode.districtai.ui.calls

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.l10n.WIRE_PLACEHOLDER_ALIASES

/**
 * What a call's `aiSummary` is actually carrying.
 *
 * ⛔ THE FIELD IS NEVER EMPTY, AND OFTEN NOT A SUMMARY. The feed maps it as
 * `c.summary || "No summary available."`, and `Call.summary` doubles as a marker and lifecycle
 * channel: the dial route writes `"direct:softphone"` on every outbound softphone call, and the
 * LiveKit webhook writes its own progress sentences. Printed as sent, all of them appeared under the
 * "AI summary" heading, in English whatever the app's language. The same rule as district-core-swift's
 * `CallNarrative`, which the iPhone, iPad and Mac apps use.
 *
 * ⛔ THREE OUTCOMES, NOT TWO, BECAUSE A FAILED SUMMARY IS NOT AN ABSENT ONE. Folding the
 * summariser's failure sentences into "no summary" would report a failure as an absence.
 */
internal sealed interface CallNarrative {
    /** A real summary, trimmed. */
    data class Summary(val text: String) : CallNarrative

    /** Nothing was written, or what was written is a stand-in, a marker or a lifecycle status. */
    data object Absent : CallNarrative

    /** A summary was attempted and the attempt failed. */
    data object Failed : CallNarrative
}

/**
 * Decide what [raw] is.
 *
 * ⚠️ THE MATCH IS EXACT, AND A STORED ROW KEEPS THE SPELLING IT WAS WRITTEN WITH. The no-answer
 * sentence had a dash and now has a comma; rows from before the change still hold the dash. Every
 * earlier spelling comes from the wire vocabulary's `aliases` ([WIRE_PLACEHOLDER_ALIASES]), never
 * from a second literal here, so the next respelling arrives with the contract.
 */
internal fun callNarrative(raw: String): CallNarrative {
    val trimmed = raw.trim()
    return when {
        trimmed.isEmpty() || trimmed in SUMMARY_PLACEHOLDERS -> CallNarrative.Absent
        trimmed in SUMMARY_FAILURES -> CallNarrative.Failed
        else -> CallNarrative.Summary(trimmed)
    }
}

/**
 * The AI summary card, or the note that says why there is none.
 *
 * ⚠️ THE MUTED NOTE, NOT THE ERROR COLOUR, FOR A FAILED SUMMARY. It is a fact about the call rather
 * than a failure of this request, and nothing on this screen can re-run the summariser.
 */
@Composable
internal fun SummarySection(raw: String) {
    when (val narrative = callNarrative(raw)) {
        is CallNarrative.Summary -> LabelledCard(stringResource(R.string.call_detail_summary), narrative.text)
        CallNarrative.Absent -> SummaryNote(R.string.call_detail_summary_absent)
        CallNarrative.Failed -> SummaryNote(R.string.call_detail_summary_failed)
    }
}

@Composable
private fun SummaryNote(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(CALL_DETAIL_SUMMARY_NOTE_DESCRIPTION),
    )
}

/**
 * The `placeholders` of contracts/i18n/wire-vocabulary.json that the service puts in a call's summary,
 * with every earlier spelling, plus the dial route's marker.
 *
 * ⚠️ `"direct:softphone"` IS NOT IN THE VOCABULARY: it is a marker the dial route stores, never meant
 * for display, so it is named here as district-core-swift names it.
 */
internal val SUMMARY_PLACEHOLDERS: Set<String> = setOf(
    "No summary available.",
    "AI Voice session active...",
    "AI Outbound Voice session active...",
    "Voice session completed.",
    "No answer, no conversation took place.",
).flatMapTo(mutableSetOf("direct:softphone")) { listOf(it) + WIRE_PLACEHOLDER_ALIASES[it].orEmpty() }

/**
 * The summariser's catch-branch sentences. ⚠️ The website no longer writes either (checked against
 * its source at 46e1740a6), but they were persisted onto rows, so a call stored earlier may still carry one.
 */
private val SUMMARY_FAILURES: Set<String> = setOf(
    "Error processing audio with AI.",
    "AI analysis failed to execute.",
)

/** Stable handle for tests: the note shown in place of the summary card. */
const val CALL_DETAIL_SUMMARY_NOTE_DESCRIPTION: String = "district-call-detail-summary-note"
