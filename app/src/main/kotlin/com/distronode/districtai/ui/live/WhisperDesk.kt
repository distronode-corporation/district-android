package com.distronode.districtai.ui.live

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SuperviseOutcome
import com.distronode.districtai.core.data.SuperviseRepository
import com.distronode.districtai.core.model.MAX_WHISPER_CHARS
import com.distronode.districtai.core.model.WhisperAppliedData
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The sentence for a refused supervise request.
 *
 * ⚠️ THE CODE IS KEPT, so `call_ended`, `call_has_no_receptionist` and `call_not_in_progress` read
 * in the error catalog's words (the server's own sentence in English), and [fallback] is the
 * screen's sentence for a code this build does not know, as the web's `describeApiErrorOr` does.
 */
internal fun superviseFailureText(failure: ApiResult.Failure, @StringRes fallback: Int): UiText =
    when (val text = failure.toFailureText().message) {
        is UiText.Server -> text.copy(fallback = fallback)
        else -> text
    }

/** One call's note box. */
data class WhisperBox(
    val draft: String = "",
    val sending: Boolean = false,
    val error: UiText? = null,
    /** The receptionist's receipt for the last note sent, cleared by the next. */
    val receipt: WhisperAppliedData? = null,
)

/**
 * The notes to the receptionist (the web's text whisper), one box per call.
 *
 * ⛔ ONE DRAFT PER CALL. A shared draft would show what was typed under one call in another call's
 * box, and Send there would speak it into the wrong call (the web learned this).
 *
 * ⛔ SINGLE-FLIGHT PER CALL, AND NEVER RETRIED. The check is synchronous, before the request, so two
 * taps before a re-render send one note; a failure keeps the text and says why, and the supervisor
 * decides whether to send again.
 *
 * ⛔ A REFUSAL IS NOT A SEND: the draft stays. Only the text that was sent is cleared, so what was
 * typed while it was in flight survives.
 *
 * @param scope single-threaded (the ViewModel's).
 */
class WhisperDesk(
    private val repository: SuperviseRepository,
    private val workspaceId: String,
    private val scope: CoroutineScope,
) {
    private val _boxes = MutableStateFlow<Map<String, WhisperBox>>(emptyMap())
    val boxes: StateFlow<Map<String, WhisperBox>> = _boxes.asStateFlow()

    /** ⚠️ Bounded to the route's own limit, which would otherwise answer 400. */
    fun setDraft(callId: String, text: String) = update(callId) { it.copy(draft = text.take(MAX_WHISPER_CHARS)) }

    fun send(callId: String) {
        val box = _boxes.value[callId] ?: WhisperBox()
        val text = box.draft.trim()
        if (text.isEmpty() || box.sending) return
        update(callId) { it.copy(sending = true, error = null, receipt = null) }
        scope.launch {
            val error = when (val outcome = repository.whisper(workspaceId, callId, text)) {
                is SuperviseOutcome.Done -> null
                SuperviseOutcome.NotACall -> UiText.Resource(R.string.live_calls_not_a_call)
                is SuperviseOutcome.Refused -> superviseFailureText(outcome.failure, R.string.live_calls_whisper_failed)
            }
            update(callId) {
                it.copy(
                    sending = false,
                    error = error,
                    draft = if (error == null && it.draft.trim() == text) "" else it.draft,
                )
            }
        }
    }

    /** The receptionist's receipt arrived. ⚠️ Kept for a call with no box too: the note may be HQ's. */
    fun receipt(callId: String, data: WhisperAppliedData) = update(callId) { it.copy(receipt = data) }

    private fun update(callId: String, change: (WhisperBox) -> WhisperBox) {
        val current = _boxes.value
        _boxes.value = current + (callId to change(current[callId] ?: WhisperBox()))
    }
}
