package com.distronode.districtai.ui.report

import com.distronode.districtai.R
import com.distronode.districtai.ui.parseThreadKey

/**
 * What was reported (Windows `district-ffi/src/report.rs`, the W2 reference for Report).
 *
 * ⛔ AN ID, NEVER THE CONTENT. The message names what was reported by kind and id and quotes none
 * of it: a support request is a ticket at a vendor, and the content stays in the workspace, where an
 * agent can read it under the workspace's own rules.
 */
sealed interface ReportTarget {
    /** One call. */
    data class Call(val callId: String) : ReportTarget

    /**
     * An inbox conversation, by the server's `threadKey`, with the name the list resolved for the
     * dialog's heading. ⚠️ The name never reaches the desk.
     */
    data class Conversation(val threadKey: String, val name: String?) : ReportTarget

    /** One District HQ answer. The service keeps no id for one. */
    data object HqAnswer : ReportTarget
}

/**
 * The English the support desk receives, resolved from the catalog's `fixed` entries.
 *
 * ⚠️ PASSED IN RATHER THAN READ HERE, so the message is a pure function a test pins without a
 * `Context`. The dialog resolves them with `stringResource`; they are `translatable="false"`, so the
 * desk reads English whatever the app's language.
 */
data class ReportWords(
    val subject: String,
    val preamble: String,
    val noNote: String,
    val reference: (ReportReference) -> String,
)

/** One reference line, before it is worded. */
sealed interface ReportReference {
    data class Call(val callId: String) : ReportReference

    data class Contact(val contactId: String) : ReportReference

    data object Conversation : ReportReference

    data object HqAnswer : ReportReference
}

/** The report's subject and message, and the dialog's heading. */
object ContentReport {
    /**
     * The longest note a report carries, in characters after trimming. The iOS and Windows limit:
     * well inside the route's 10,000, which the rest of the message shares.
     */
    const val NOTE_LIMIT: Int = 2000

    /**
     * What the desk receives about [target].
     *
     * ⛔ NEVER THE THREAD KEY ITSELF: a thread with no contact is keyed by the other party's phone
     * number or email address, which must not reach the support desk. A contact's thread names the
     * contact by its id; an address's thread names nothing but the kind.
     */
    fun references(target: ReportTarget): List<ReportReference> = when (target) {
        is ReportTarget.Call -> listOf(ReportReference.Call(target.callId))
        is ReportTarget.Conversation -> listOfNotNull(
            ReportReference.Conversation,
            parseThreadKey(target.threadKey).contactId?.let(ReportReference::Contact),
        )
        ReportTarget.HqAnswer -> listOf(ReportReference.HqAnswer)
    }

    /**
     * The message: where it came from, what it is about, and the note, trimmed and cut to
     * [NOTE_LIMIT]. ⚠️ A blank note says so, so an absent note does not read as a lost one.
     */
    fun message(target: ReportTarget, note: String, words: ReportWords): String {
        val trimmed = note.trim().take(NOTE_LIMIT).trimEnd()
        val tail = trimmed.ifEmpty { words.noNote }
        val references = references(target).joinToString("\n", transform = words.reference)
        return "${words.preamble}\n$references\n\n$tail"
    }

    /** The dialog's heading resource for [target]; the conversation one takes the name. */
    fun headline(target: ReportTarget): Int = when (target) {
        is ReportTarget.Call -> R.string.report_headline_call
        is ReportTarget.Conversation ->
            if (target.name.isNullOrBlank()) {
                R.string.report_headline_conversation
            } else {
                R.string.report_headline_conversation_with
            }
        ReportTarget.HqAnswer -> R.string.report_headline_hq
    }
}
