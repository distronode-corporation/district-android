package com.distronode.districtai.ui.desk

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.DeskMessageAuthor
import com.distronode.districtai.core.model.DeskTicketStatus

/**
 * How a desk ticket's status and a message's author are presented.
 *
 * ⛔ ITS OWN FILE BECAUSE `DeskScreen.kt` AND `DeskTicketScreen.kt` WERE BOTH AT detekt's
 * `TooManyFunctions` CEILING FOR A FILE (11, and the rule fires AT the threshold, not above it).
 * That is the same constraint `SchedulingDestination.kt` records, and the same answer: move a
 * function out along a real seam rather than raise a threshold. The seam here is genuine — both
 * screens render the same three statuses and the same three author types, and a queue row and a
 * thread bubble disagreeing about which colour `waiting` is would be a visible bug that nothing
 * type-checks.
 *
 * ⚠️ ALL THREE TAKE A NULLABLE AND ANSWER FOR IT, deliberately. The `status` and `authorType`
 * columns are plain `TEXT` server-side — chosen so adding a state never needs a migration on four
 * databases — so an installed build must be able to render a value it has never heard of rather
 * than failing to draw the row at all.
 */
@StringRes
internal fun statusLabel(status: DeskTicketStatus): Int = when (status) {
    DeskTicketStatus.OPEN -> R.string.desk_status_open
    DeskTicketStatus.WAITING -> R.string.desk_status_waiting
    DeskTicketStatus.RESOLVED -> R.string.desk_status_resolved
}

/**
 * ⚠️ `District` IS THE ACCENT TONE AND NOT A SUCCESS TONE — the design system's own note says a
 * workspace being SELECTED and an operation having SUCCEEDED are different facts that happen to
 * both be positive. So `resolved` takes Success and `open` takes the accent.
 *
 * ⛔ AN UNRECOGNISED STATUS IS NEUTRAL RATHER THAN GUESSED. Colouring an unknown state as resolved
 * would assert something about a customer's ticket that this build has no basis for.
 */
internal fun toneFor(status: DeskTicketStatus?): Tone = when (status) {
    DeskTicketStatus.OPEN -> Tone.District
    DeskTicketStatus.WAITING -> Tone.Warning
    DeskTicketStatus.RESOLVED -> Tone.Success
    null -> Tone.Neutral
}

/**
 * ⛔ THE AUTHOR TYPE IS FIXED SERVER-SIDE AND THIS ONLY RENDERS IT. An operator's reply is
 * attributed to the team, and a ticket an operator raised on a customer's behalf is attributed to
 * the CUSTOMER — because it is the customer's problem, and attributing it to the team would make
 * the thread read as us talking to ourselves. Nothing here may relabel either.
 *
 * ⚠️ An unrecognised author falls to "Customer", which is the conservative reading: it is the only
 * one of the three that does not claim the message came from us.
 */
@StringRes
internal fun authorLabel(author: DeskMessageAuthor?): Int = when (author) {
    DeskMessageAuthor.TEAM -> R.string.desk_author_team
    DeskMessageAuthor.ASSISTANT -> R.string.desk_author_assistant
    DeskMessageAuthor.CUSTOMER, null -> R.string.desk_author_customer
}
