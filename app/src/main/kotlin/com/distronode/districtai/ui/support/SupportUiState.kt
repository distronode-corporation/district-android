package com.distronode.districtai.ui.support

import com.distronode.districtai.core.model.SupportBounds
import com.distronode.districtai.core.model.SupportRequestDetail
import com.distronode.districtai.core.model.SupportRequestKind
import com.distronode.districtai.core.model.SupportRequestSummary
import com.distronode.districtai.ui.FailureText

/**
 * The workspace's own requests with Distronode.
 *
 * ⛔ AN EMPTY LIST AND A FAILED READ ARE DIFFERENT STATES AND MUST STAY THAT WAY. The web collapsed
 * every list failure into `[]` and told a customer with three open tickets that they had none; they
 * stopped chasing and nobody here ever saw the request. That is the single most expensive mistake
 * this screen can make, and it is prevented by [Failed] existing at all rather than by anything
 * clever.
 */
sealed interface SupportUiState {

    data object Loading : SupportUiState

    data class Content(
        val requests: List<SupportRequestSummary>,
        val refreshing: Boolean = false,
    ) : SupportUiState {

        /** ⚠️ Derived from `statusCategory`, never from the localised `statusName`. */
        val open: List<SupportRequestSummary> get() = requests.filterNot { it.isResolved }

        val resolved: List<SupportRequestSummary> get() = requests.filter { it.isResolved }
    }

    data class Failed(val failure: FailureText) : SupportUiState
}

/**
 * One request and its conversation.
 *
 * ⚠️ [closeable] TRAVELS ON THE REQUEST AND IS THE SERVER'S ANSWER. The desk's workflow either
 * offers no resolving transition or offers several, and in the second case picking one would decide
 * on the customer's behalf whether their request was "done" or "won't do" — so the server declines
 * and says so. A screen that offered Close anyway would earn a 409 it could have avoided.
 */
sealed interface SupportRequestUiState {

    data object Loading : SupportRequestUiState

    data class Content(
        val request: SupportRequestDetail,
        val sending: Boolean = false,
        val sendFailure: FailureText? = null,
        val closing: Boolean = false,
        val closeFailure: FailureText? = null,
    ) : SupportRequestUiState {

        /**
         * ⛔ THE CLOSE CONTROL IS OFFERED ONLY WHEN THE SERVER SAYS IT CAN BE, and never for a
         * request that is already resolved — a repeat leaves a SECOND "Closed at the requester's
         * request by …" in the customer's own thread, because the audit comment is posted before
         * the transition is applied.
         *
         * ⚠️ IT DOES NOT READ [closing]. A close in flight keeps the control on screen, disabled
         * and labelled as closing; excluding it here sent the header to the "cannot be closed from
         * here" sentence for the length of the request. [SupportRequestViewModel.close] refuses a
         * second close while one is in flight.
         */
        val canClose: Boolean get() = request.closeable && !request.isResolved
    }

    data class Failed(val failure: FailureText) : SupportRequestUiState
}

/**
 * A request being composed.
 *
 * ⛔ THE IDEMPOTENCY KEY BELONGS TO THE DRAFT, NOT TO THE ATTEMPT, AND IT IS THE OPPOSITE OF THE
 * DESK'S RULE. The server claims the key before it calls Atlassian and answers a re-used one with
 * `deduplicated:true`, so a retry carrying the SAME key collapses onto the first request while a
 * retry that mints a fresh one puts a second ticket in a human's queue. Minting it per submit —
 * which is right for the desk — would defeat it exactly when it is needed. It is minted when the
 * sheet opens and discarded when the draft is cleared.
 */
data class SupportComposeState(
    val kind: SupportRequestKind = SupportRequestKind.PROBLEM,
    val subject: String = "",
    val message: String = "",
    val idempotencyKey: String,
    val submitting: Boolean = false,
    val failure: FailureText? = null,
) {
    val submittable: Boolean
        get() = !submitting &&
            subject.trim().length >= SupportBounds.SUBJECT_MIN &&
            subject.trim().length <= SupportBounds.SUBJECT_MAX &&
            message.trim().isNotEmpty() &&
            message.trim().length <= SupportBounds.MESSAGE_MAX
}

/**
 * What a submitted request turned into.
 *
 * ⚠️ THREE OUTCOMES AND ALL THREE ARE SUCCESSES. [PENDING] in particular is not a failure: the
 * claim row is held and a human will see it, and the only thing missing is a key to quote. Showing
 * it as an error would make an operator submit again, which is the one thing the idempotency key
 * exists to prevent.
 */
enum class SupportSubmitOutcome { FILED, DEDUPLICATED, PENDING }

/** ⚠️ [issueKey] is present only for [SupportSubmitOutcome.FILED]. */
data class SupportSubmitted(
    val outcome: SupportSubmitOutcome,
    val issueKey: String? = null,
)
