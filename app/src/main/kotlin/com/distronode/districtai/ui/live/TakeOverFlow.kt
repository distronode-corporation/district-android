package com.distronode.districtai.ui.live

import com.distronode.districtai.R
import com.distronode.districtai.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The take-over confirmation and the microphone request.
 *
 * @property microphoneRequest ⚠️ A COUNTER, like the dialer's: a permission launcher cannot be
 *   called from a ViewModel, and a flag would not fire again after a denial. ⛔ CONSUMED ONCE
 *   LAUNCHED ([TakeOverFlow.onMicrophoneRequested]): the screen's effect re-runs whenever it
 *   re-enters composition (a rotation, a return from a screen pushed on top), and a count left
 *   above zero would ask for the microphone again with nothing to take over.
 */
data class LiveCallsPrompt(
    val confirmingTakeover: String? = null,
    val microphoneRequest: Int = 0,
)

/**
 * From "Take over the call" to a takeover: an in-page confirmation, then the microphone, then the
 * request.
 *
 * ⛔ NEVER A TAKEOVER ON ONE TAP. The confirmation says what the caller will hear and that the
 * microphone goes live; only its confirm asks for the microphone, and only a grant requests the
 * takeover.
 *
 * @param writable whether [ask] may open the confirmation for a call (a writer, on a call the
 *   receptionist is on).
 */
class TakeOverFlow(
    private val supervisor: LiveSupervisor,
    private val writable: (String) -> Boolean,
) {
    private val _prompt = MutableStateFlow(LiveCallsPrompt())
    val prompt: StateFlow<LiveCallsPrompt> = _prompt.asStateFlow()

    private var pending: String? = null

    fun ask(callId: String) {
        if (writable(callId)) _prompt.value = _prompt.value.copy(confirmingTakeover = callId)
    }

    fun cancel() {
        _prompt.value = _prompt.value.copy(confirmingTakeover = null)
    }

    /** Confirmed: ask for the microphone, and take over once it is granted. */
    fun confirm() {
        val callId = _prompt.value.confirmingTakeover ?: return
        pending = callId
        _prompt.value = LiveCallsPrompt(microphoneRequest = _prompt.value.microphoneRequest + 1)
    }

    /**
     * The screen launched the microphone request: back to zero, so the effect's next run (on any
     * re-entry) launches nothing. The pending call is kept for the answer, which the launcher
     * still delivers after a recreation.
     */
    fun onMicrophoneRequested() {
        _prompt.value = _prompt.value.copy(microphoneRequest = 0)
    }

    /** ⚠️ A result with nothing pending (the screen was left meanwhile) is dropped. */
    fun onMicrophonePermissionResult(granted: Boolean) {
        val callId = pending ?: return
        pending = null
        if (granted) {
            supervisor.takeOver(callId)
        } else {
            supervisor.fail(callId, UiText.Resource(R.string.live_calls_microphone_denied))
        }
    }

    /** The screen stopped: no confirmation stays open and no answer may take anything over. */
    fun reset() {
        pending = null
        cancel()
    }
}
