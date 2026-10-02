package com.distronode.districtai.ui.hq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.R
import com.distronode.districtai.core.data.HqRepository
import com.distronode.districtai.core.model.HqPendingWrite
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * District HQ: the console's transcript, and the confirm gate in front of every write.
 *
 * ⛔ THE TRANSCRIPT AND THE IN-FLIGHT STATE ARE TWO SEPARATE FLOWS, DELIBERATELY. Folding the
 * messages into [HqUiState] would mean every state carried a copy of the list, and the failure
 * branch is exactly where one gets forgotten — a failed turn would render as an EMPTY console,
 * throwing away a conversation the operator is mid-way through and cannot recover, because the
 * server holds none of it. Splitting them makes "a failure never blanks the transcript" structural
 * rather than something each branch has to remember.
 *
 * ⛔ THE CLIENT IS THE SOLE OWNER OF THE CONVERSATION. The route is stateless: it keeps no session
 * and replays only what [history] sends it. So nothing here may quietly drop a turn.
 */
class HqViewModel(
    private val repository: HqRepository,
    private val workspaceId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    /**
     * Whether to OFFER the confirm control.
     *
     * ⚠️ A BELT-AND-BRACES GATE THAT SHOULD NEVER FIRE. The server refuses a viewer's write inside
     * the tool executor, BEFORE the confirmation gate, so a viewer's turn comes back as an ordinary
     * answer with no proposal attached and there is nothing to confirm. This exists for the case
     * where that ordering ever changes: a control that can only 403 is worse than an absent one.
     * It is an affordance, never the authority — see [WorkspaceRole].
     */
    val canConfirm: Boolean = role.allowsMutation()

    private val _messages = MutableStateFlow<List<HqMessage>>(emptyList())
    val messages: StateFlow<List<HqMessage>> = _messages.asStateFlow()

    private val _state = MutableStateFlow<HqUiState>(HqUiState.Idle)
    val state: StateFlow<HqUiState> = _state.asStateFlow()

    /**
     * Ask a question, or ask for a change.
     *
     * ⛔ REFUSED WHILE A TURN IS IN FLIGHT. Every prompt runs a Gemini function-calling loop against
     * the workspace's data, so a double tap is a duplicated model run — and worse, two turns racing
     * on the same transcript can interleave their appends and produce a conversation that never
     * happened.
     *
     * ⚠️ A blank prompt is refused locally. The server answers 400, and spending a round trip to be
     * told what the client can already see would surface as a fault.
     */
    fun ask(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || inFlight()) return

        _messages.value += HqMessage(HqRole.OPERATOR, UiText.Literal(text))
        sendPrompt(text)
    }

    /**
     * Re-send the turn that failed.
     *
     * ⚠️ Only meaningful from [HqUiState.Failed]; anything else is a no-op so a stray tap cannot
     * duplicate a turn that already succeeded.
     */
    fun retry() {
        val failed = _state.value as? HqUiState.Failed ?: return
        sendPrompt(failed.prompt)
    }

    /**
     * The session-changed hook. See `DistrictNavHost`.
     *
     * ⛔ REPLAYS A FAILED PROMPT AND NEVER A FAILED CONFIRM, AND THAT ASYMMETRY IS THE WHOLE POINT.
     * A turn that died on `Unauthorized` stays failed until something re-issues it, so a successful
     * sign-in has to reach the screen or the operator is left looking at "your session has ended"
     * behind a button that has already done its job. But a CONFIRM is a write — a deletion, an
     * email to a customer, a routing replacement — and a failed one may well have executed before
     * the response was lost. Re-issuing that automatically, on an event the operator did not
     * connect to it, would apply it twice with nobody having asked. A failed confirm therefore
     * keeps its card and waits for a deliberate tap.
     */
    fun retryOrNoop() {
        retry()
    }

    private fun inFlight(): Boolean =
        _state.value is HqUiState.Thinking || _state.value is HqUiState.Applying

    private fun sendPrompt(text: String) {
        _state.value = HqUiState.Thinking
        viewModelScope.launch {
            when (val result = repository.ask(workspaceId, text, history())) {
                is ApiResult.Success -> {
                    _messages.value += HqMessage(HqRole.CONSOLE, UiText.Literal(result.value.answer))
                    _state.value = result.value.pendingWrite
                        ?.let { HqUiState.Confirming(it) }
                        ?: HqUiState.Idle
                }
                // ⛔ The transcript is untouched, including the operator's unanswered message. See
                // the ⛔ on the class.
                is ApiResult.Failure -> _state.value = HqUiState.Failed(result.toFailureText(), text)
            }
        }
    }

    /**
     * The turns to replay, oldest first.
     *
     * ⛔ EXCLUDES THE TRAILING OPERATOR MESSAGE, because that message IS the prompt being sent. The
     * route appends `prompt` to whatever `history` contains, so leaving it in would send the
     * question twice in one request — once as context and once as the ask — and the model answers
     * the duplicate as though the operator had repeated themselves.
     *
     * ⚠️ The last message is ALWAYS that prompt, so it is dropped unconditionally: [ask] appends it
     * just before sending, and a failed turn leaves it last for [retry] (nothing else can append
     * while the state is `Failed`).
     *
     * ⚠️ Sent whole otherwise. The server keeps the last 6 turns; trimming here as well would be two
     * places deciding the same thing.
     */
    private fun history(): List<HqTurn> {
        return _messages.value.dropLast(1).mapNotNull { message ->
            // Client-authored notes (the "applied" receipt) are UiText.Resource and have no wire
            // text. They are OUR bookkeeping, not something either party said, so they are not
            // replayed as conversation.
            message.text.literalOrNull?.let { HqTurn(role = message.role.wireRole, text = it) }
        }
    }

    /**
     * Apply the proposed write.
     *
     * ⛔ SENDS THE PROPOSAL BACK UNCHANGED — see [HqRepository.confirm]. The operator approved the
     * sentence they read, so the action applied has to be the one that sentence described.
     *
     * ⛔ AND IS GUARDED AGAINST A SECOND TAP. This is the tap that spends money, deletes a contact
     * or replaces the routing rules; the server offers no idempotency key, so this guard is the only
     * thing standing between a double tap and a repeated write. [HqUiState.Applying] is refused, so
     * the guard holds for a retry as well.
     *
     * ⚠️ ACCEPTED FROM [HqUiState.ConfirmFailed] TOO: THAT IS THE OPERATOR'S MANUAL RETRY. The card
     * keeps an enabled Confirm button after a failure, and the decision to try again is theirs
     * (see the failure arm below); refusing here made that button a silent no-op. Only an event the
     * operator did not connect to the write (a session change) is barred from replaying it.
     */
    fun confirmPending() {
        val pending = when (val current = _state.value) {
            is HqUiState.Confirming -> current.pending
            is HqUiState.ConfirmFailed -> current.pending
            else -> return
        }
        if (!canConfirm) return

        _state.value = HqUiState.Applying(pending)
        viewModelScope.launch {
            when (val result = repository.confirm(workspaceId, pending)) {
                is ApiResult.Success -> {
                    // ⚠️ `executed` decides the wording, not `success`. A handled request that
                    // declined the write must not read as applied.
                    _messages.value += HqMessage(
                        HqRole.CONSOLE,
                        UiText.Resource(
                            if (result.value.executed) R.string.hq_applied else R.string.hq_not_applied,
                        ),
                    )
                    _state.value = HqUiState.Idle
                }
                // ⛔ The card SURVIVES the failure, deliberately. The operator has to be able to see
                // what was attempted; and because a failed confirm may have executed, the decision
                // to try again is theirs. Nothing here retries it.
                is ApiResult.Failure -> {
                    _state.value = HqUiState.ConfirmFailed(pending, result.toFailureText())
                }
            }
        }
    }

    /**
     * Decline the proposed write.
     *
     * ⚠️ NOTHING IS SENT. The proposal was never applied, so declining it is purely local — there is
     * no "cancel" endpoint and calling one would imply the server was holding state it is not.
     */
    fun dismissPending() {
        val current = _state.value
        if (current is HqUiState.Confirming || current is HqUiState.ConfirmFailed) {
            _state.value = HqUiState.Idle
        }
    }

    companion object {
        fun factory(
            repository: HqRepository,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HqViewModel(repository, workspaceId, role) as T
        }
    }
}

/**
 * Who said it.
 *
 * ⛔ [wireRole] IS NOT THE ENUM NAME, AND THE MAPPING IS LOAD-BEARING. The server replays history
 * straight into Gemini, whose vocabulary is `user`/`model`, and it silently DROPS any turn whose
 * role is neither — so a mismatch here loses half the conversation with no error anywhere. The enum
 * is named for the product ("the operator", "the console") while the wire keeps the model's words;
 * conflating the two is how the mapping would get dropped as redundant.
 */
enum class HqRole(val wireRole: String) {
    OPERATOR("user"),
    CONSOLE("model"),
}

/**
 * One line of the transcript.
 *
 * ⚠️ A [UiText] rather than a String because two different kinds of text share this list: the
 * server's answers, which are [UiText.Literal] and are replayed as history, and the client's own
 * receipts ("Applied."), which are [UiText.Resource] so they can be translated — and which must NOT
 * be replayed, because neither party said them. [HqViewModel.history] uses exactly that distinction.
 */
data class HqMessage(
    val role: HqRole,
    val text: UiText,
)

/**
 * What the console is doing, independently of what it has said.
 *
 * ⛔ CARRIES NO MESSAGES. See the ⛔ on [HqViewModel].
 */
sealed interface HqUiState {

    /** Nothing in flight; the composer is open. */
    data object Idle : HqUiState

    /** A prompt turn is running server-side. */
    data object Thinking : HqUiState

    /**
     * A write has been PROPOSED and nothing has been applied.
     *
     * ⚠️ The operator must be able to decline as easily as accept, so this state is never a
     * blocking modal — the transcript stays readable behind it.
     */
    data class Confirming(val pending: HqPendingWrite) : HqUiState

    /** The approved write is executing. */
    data class Applying(val pending: HqPendingWrite) : HqUiState

    /**
     * A prompt turn failed. The transcript is intact; [HqViewModel.retry] re-sends it.
     *
     * @param prompt the unanswered prompt. ⚠️ Held HERE rather than beside the state so a failed turn
     *   can never be without one: a retry re-sends it WITHOUT re-appending the operator's message,
     *   which is already in the transcript (appending it again would show the question twice and
     *   then send it as its own history).
     */
    data class Failed(val failure: FailureText, val prompt: String) : HqUiState

    /**
     * A confirm failed.
     *
     * ⛔ KEEPS [pending] SO THE CARD STAYS ON SCREEN, and deliberately does not retry itself — the
     * write may already have taken effect. See [HqViewModel.retryOrNoop].
     */
    data class ConfirmFailed(
        val pending: HqPendingWrite,
        val failure: FailureText,
    ) : HqUiState
}
