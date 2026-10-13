package com.distronode.districtai.ui.scheduling.admin

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminError
import com.distronode.districtai.core.data.SchedulingAdminFailureCode
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.model.SchedulingMe
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One read on a scheduling section: on its way, in hand, or failed with the sentence to show. */
sealed interface SectionState<out T> {
    data object Loading : SectionState<Nothing>
    data class Ready<out T>(val value: T) : SectionState<T>
    data class Failed(val failure: FailureText) : SectionState<Nothing>
}

/** The value of a [SectionState.Ready], else null. */
val <T> SectionState<T>.valueOrNull: T? get() = (this as? SectionState.Ready)?.value

/** A typed op's answer, as a section state. */
fun <T> SchedulingAdminResult<T>.toSectionState(): SectionState<T> = when (this) {
    is SchedulingAdminResult.Ok -> SectionState.Ready(value)
    is SchedulingAdminResult.Failed -> SectionState.Failed(error.toFailureText())
}

/**
 * Where a write is: not started, on its way, done (with the sentence that says so) or failed.
 *
 * ⚠️ DONE CARRIES ITS SENTENCE, as district-ios's `SchedulingWriteState.done` does: "Booking moved
 * to Oct 2, 1:00 p.m." is a confirmation of WHAT happened, not merely that something did.
 */
sealed interface WriteState {
    data object Idle : WriteState
    data object Working : WriteState
    data class Done(val message: UiText) : WriteState
    data class Failed(val failure: FailureText) : WriteState
}

/** True while a write is on its way. */
val WriteState.isWorking: Boolean get() = this == WriteState.Working

/**
 * The sentence for a scheduling admin failure, keyed on the recovery rather than on the server's
 * words, copied arm for arm from district-ios's `SchedulingFailureCopy`.
 *
 * ⚠️ RETRY IS OFFERED ONLY WHERE IT COULD WORK: a role refusal, a missing tenancy, a slot that went
 * and a shape this build cannot read give the identical answer on every attempt.
 */
fun SchedulingAdminError.toFailureText(): FailureText = when (this) {
    SchedulingAdminError.Transport -> failure(R.string.scheduling_admin_failure_offline, retryable = true)
    SchedulingAdminError.Decoding -> failure(R.string.scheduling_admin_failure_stale_build, retryable = false)
    SchedulingAdminError.InvalidParams -> failure(R.string.scheduling_admin_failure_unknown, retryable = false)
    else -> uiCode.toFailureText()
}

/** The sentence for one of the five recoveries. */
fun SchedulingAdminFailureCode.toFailureText(): FailureText = when (this) {
    SchedulingAdminFailureCode.UNAVAILABLE -> failure(R.string.scheduling_admin_failure_unavailable, retryable = true)
    SchedulingAdminFailureCode.SLOT_TAKEN -> failure(R.string.scheduling_admin_failure_slot_taken, retryable = false)
    SchedulingAdminFailureCode.FORBIDDEN -> failure(R.string.scheduling_admin_failure_forbidden, retryable = false)
    SchedulingAdminFailureCode.NOT_READY -> failure(R.string.scheduling_admin_failure_not_ready, retryable = false)
    SchedulingAdminFailureCode.UNKNOWN -> failure(R.string.scheduling_admin_failure_unknown, retryable = true)
}

private fun failure(id: Int, retryable: Boolean) = FailureText(UiText.Resource(id), retryable = retryable)

/** True when [result] failed because the slot was taken in the meantime. */
fun SchedulingAdminResult<*>.isSlotTaken(): Boolean =
    (this as? SchedulingAdminResult.Failed)?.error?.uiCode == SchedulingAdminFailureCode.SLOT_TAKEN

/**
 * What the status read says about the member and the tenancy, for a section's writes and links.
 *
 * ⛔ [canManage] IS THE SERVER'S ANSWER (`role` clears `client`), and a failed status read answers
 * false: a write control the server would refuse is worse than one that is missing until a refresh.
 * The scheduler still checks its own admin bit on top; this decides only whether to draw a control.
 */
data class SchedulingAccess(val canManage: Boolean = false, val publicHost: String? = null)

/** Read [SchedulingAccess] for [workspaceId]; a failure is the closed answer. */
suspend fun SchedulingRepository.access(workspaceId: String): SchedulingAccess =
    when (val result = status(workspaceId)) {
        is ApiResult.Success -> SchedulingAccess(
            canManage = result.value.canManage,
            publicHost = result.value.tenant?.publicHost?.takeIf { it.isNotBlank() },
        )
        is ApiResult.Failure -> SchedulingAccess()
    }

/** The member's own zone, which every time on the admin is shown in. ⚠️ Blank reads as UTC. */
val SchedulingMe.displayTimezone: String get() = timezone.ifBlank { UTC }

/** The zone a failed or absent profile read falls back to, as district-ios does. */
const val UTC: String = "UTC"

/**
 * A remove that asks first: the row waiting for an answer, the request in flight, and how it ended.
 *
 * @property removed the last row removed, so the screen can say which one went.
 */
data class RemovalState<out Row>(
    val pending: Row? = null,
    val busy: Boolean = false,
    val failure: FailureText? = null,
    val removed: Row? = null,
)

/**
 * The confirm-then-remove flow every scheduling list shares (an API key, a webhook, a calendar,
 * a rule, a question), ported from district-ios's `SchedulingConfirmedRemoval`.
 *
 * ⛔ [confirm] CLEARS [RemovalState.pending] BEFORE IT SENDS, so a second tap on the dialog's
 * button cannot send a second delete, and a press while one is [RemovalState.busy] is ignored.
 */
class SchedulingRemoval<Row>(
    private val scope: CoroutineScope,
    private val remove: suspend (Row) -> SchedulingAdminResult<*>,
    private val onRemoved: (Row) -> Unit,
) {
    private val _state = MutableStateFlow(RemovalState<Row>())
    val state: StateFlow<RemovalState<Row>> = _state.asStateFlow()

    /** Ask about [row]. Ignored while a remove is on its way. */
    fun ask(row: Row) {
        if (_state.value.busy) return
        _state.value = RemovalState(pending = row)
    }

    fun cancel() {
        _state.value = _state.value.copy(pending = null)
    }

    fun confirm() {
        // ⚠️ No separate busy check: [ask] never sets a pending row while one is busy, and this
        // clears it before sending, so a pending row already means nothing is in flight.
        val row = _state.value.pending ?: return
        _state.value = RemovalState(busy = true)
        scope.launch {
            when (val result = remove(row)) {
                is SchedulingAdminResult.Ok -> {
                    _state.value = RemovalState(removed = row)
                    onRemoved(row)
                }
                is SchedulingAdminResult.Failed ->
                    _state.value = RemovalState(failure = result.error.toFailureText())
            }
        }
    }

    fun dismissFailure() {
        _state.value = _state.value.copy(failure = null)
    }
}
