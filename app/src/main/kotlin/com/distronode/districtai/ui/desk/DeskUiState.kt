package com.distronode.districtai.ui.desk

import com.distronode.districtai.core.model.DeskBounds
import com.distronode.districtai.core.model.DeskMessage
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.core.model.DeskTicketDetail
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.DeskTicketSummary
import com.distronode.districtai.ui.FailureText

/**
 * The queue.
 *
 * ⛔ FOUR STATES, NOT THREE, AND [Disabled] IS THE ONE THAT IS EASY TO LOSE. `enabled: false` means
 * the desk is off and the queue is empty BY CONSTRUCTION — nothing is being recorded. An empty
 * queue on an ENABLED desk means no customer has written in. A failed read means we could not ask.
 * Collapsing the first two shows an operator "no customer has ever contacted you" when the truth is
 * that we were never listening, and collapsing either into [Failed] offers a retry for something no
 * retry fixes.
 */
sealed interface DeskUiState {

    data object Loading : DeskUiState

    /** ⚠️ Carries the settings so the screen can offer to turn the desk ON from here. */
    data class Disabled(val settings: DeskSettings) : DeskUiState

    data class Content(
        /**
         * ⛔ THE WHOLE QUEUE, UNFILTERED. The chips carry counts, so filtering server-side would
         * mean one request per chip and the counts could disagree between responses. [visible] is
         * the filtered view.
         */
        val tickets: List<DeskTicketSummary>,
        val settings: DeskSettings,
        val filter: DeskTicketStatus? = null,
        val refreshing: Boolean = false,
    ) : DeskUiState {

        val visible: List<DeskTicketSummary>
            get() = filter?.let { wanted -> tickets.filter { it.knownStatus == wanted } } ?: tickets

        /** ⚠️ Counted over the WHOLE queue, so a chip's number does not change when it is picked. */
        fun countOf(status: DeskTicketStatus): Int = tickets.count { it.knownStatus == status }

        /**
         * ⚠️ TRUE ONLY WHEN THE QUEUE ITSELF IS EMPTY, never when a filter merely matched nothing.
         * "No customer has written in" and "nothing is waiting" are different sentences.
         */
        val queueEmpty: Boolean get() = tickets.isEmpty()
    }

    data class Failed(val failure: FailureText) : DeskUiState
}

/**
 * One ticket and its thread.
 *
 * ⚠️ [statusChanging] AND [sending] ARE SEPARATE FLAGS because the two controls are separate and an
 * operator may reasonably use one while the other is in flight. One shared "busy" would disable a
 * reply box because someone tapped Resolve.
 */
sealed interface DeskTicketUiState {

    data object Loading : DeskTicketUiState

    data class Content(
        val ticket: DeskTicketDetail,
        val sending: Boolean = false,
        val sendFailure: FailureText? = null,
        val statusChanging: Boolean = false,
        val statusFailure: FailureText? = null,
        /**
         * ⚠️ NULL MEANS "WE DO NOT KNOW", NOT "NO". The reply route omits `notified` entirely on a
         * degraded replay, so a screen that defaulted it to false would tell an operator the
         * customer was not emailed when the truth is that we cannot say.
         */
        val lastNotified: Boolean? = null,
    ) : DeskTicketUiState {

        val messages: List<DeskMessage> get() = ticket.messages
    }

    data class Failed(val failure: FailureText) : DeskTicketUiState
}

/**
 * The desk's settings form.
 *
 * ⛔ THE FORM HOLDS ONLY WHAT THE OPERATOR TOUCHED, WHICH IS WHY [brandNameEdited] EXISTS. The
 * settings route is a PATCH that merges per field, so sending the whole form state would make this
 * screen the writer of values it may have read before another tab changed them. A save therefore
 * sends `enabled` only if the switch moved, and the brand name only if the box was edited.
 */
sealed interface DeskSettingsUiState {

    data object Loading : DeskSettingsUiState

    data class Content(
        val stored: DeskSettings,
        val enabled: Boolean,
        val notifyCustomersByEmail: Boolean,
        val brandName: String,
        /**
         * ⛔ WITHOUT THIS, CLEARING THE BRAND NAME IS INDISTINGUISHABLE FROM NEVER TOUCHING IT.
         * A stored name of null and an untouched empty box look identical, and the route
         * distinguishes absent ("leave it alone") from an explicit null ("clear it").
         */
        val brandNameEdited: Boolean = false,
        val saving: Boolean = false,
        val saveFailure: FailureText? = null,
        val logoBusy: Boolean = false,
        val logoFailure: FailureText? = null,
        /** ⚠️ Set only by a DELETE that reported the bytes were left behind. See below. */
        val logoObjectRetained: Boolean = false,
    ) : DeskSettingsUiState {

        val enabledChanged: Boolean get() = enabled != stored.enabled

        val notifyChanged: Boolean get() = notifyCustomersByEmail != stored.notifyCustomersByEmail

        /**
         * ⚠️ AN EDITED BOX THAT MATCHES WHAT IS STORED IS NOT A CHANGE. Typing a name and undoing it
         * should not send a write, and `""` matching a stored null is the same non-change.
         */
        val brandNameChanged: Boolean
            get() = brandNameEdited && brandName.trim() != stored.publicBrandName.orEmpty()

        val dirty: Boolean get() = enabledChanged || notifyChanged || brandNameChanged
    }

    data class Failed(val failure: FailureText) : DeskSettingsUiState
}

/**
 * What the compose sheet holds.
 *
 * ⛔ THE THREE REQUESTER BOXES MAY ALL BE BLANK AND THAT IS A VALID TICKET. The route requires only
 * a subject and a message; `DeskRepository` trims the rest to null so a blank box never reaches the
 * wire as `""`, which would fail `.email()` and take the whole create down.
 */
data class DeskComposeState(
    val subject: String = "",
    val message: String = "",
    val requesterName: String = "",
    val requesterEmail: String = "",
    val requesterPhone: String = "",
    val submitting: Boolean = false,
    val failure: FailureText? = null,
) {
    /**
     * ⚠️ THE SAME BOUNDS THE ROUTE ENFORCES, checked here only so the button can be disabled rather
     * than spending a request to be refused. The server re-checks; this is a shortcut, never the
     * boundary.
     */
    val submittable: Boolean
        get() = !submitting &&
            subject.trim().length >= DeskBounds.SUBJECT_MIN &&
            subject.trim().length <= DeskBounds.SUBJECT_MAX &&
            message.trim().isNotEmpty() &&
            message.trim().length <= DeskBounds.MESSAGE_MAX
}
