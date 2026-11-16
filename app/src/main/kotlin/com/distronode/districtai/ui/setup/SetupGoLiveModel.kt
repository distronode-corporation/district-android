package com.distronode.districtai.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.distronode.districtai.core.model.ForwardingCheckMode
import com.distronode.districtai.core.model.ForwardingCheckResponse
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The test call's outcome on screen. */
sealed interface SetupCallStatus {
    data object Calling : SetupCallStatus

    data class Failed(val failure: FailureText) : SetupCallStatus
}

/** What the forwarding check says, as a sentence to pick. */
enum class SetupCheckSentence { PENDING, STILL_PENDING, PASSED, DIAL_FAILED, FAILED }

/** The greeting and the answering mode, read for the summary. */
data class SetupGoLiveSummary(val greeting: String, val mode: String, val ringSeconds: Int)

/**
 * Step 6, "Test and go live": the test call, the forwarding check, the summary, and Finish.
 *
 * ⛔ THE TEST CALL AND THE FORWARDING CHECK EACH PLACE A REAL CALL ON THE PLATFORM'S CARRIER ACCOUNT,
 * so each is its own single flight, set before the coroutine starts, and neither is ever retried by
 * this client. The service limits both per day and says so in its refusal.
 *
 * ⛔ THE CONSENT IS RECORDED BY THE SERVICE, in its own words for the owner's language, when the test
 * call is placed; this client sends only the phone. The step shows the same words beside the button.
 *
 * ⚠️ THE FORWARDING CHECK IS HIDDEN WHILE THE SERVICE SAYS `available: false` (no verifier in the
 * region), and polled every two seconds while it is pending, for at most two minutes. The poll stops
 * when the step leaves the screen ([close]).
 *
 * ⚠️ THE SUMMARY IS NOT A SUMMARY OF DEFAULTS. When the reads behind it fail it is left out, rather
 * than showing "no greeting" and "every call" for a workspace that has both.
 */
@Suppress("TooManyFunctions")
class SetupGoLiveModel(private val wizard: SetupWizardViewModel) : SetupStepModel {

    var mobile: String by mutableStateOf("")
    var businessLine: String by mutableStateOf("")
    var mode: String by mutableStateOf(ForwardingCheckMode.ALL)

    var callStatus: SetupCallStatus? by mutableStateOf(null)
        private set
    var mobileError: Boolean by mutableStateOf(false)
        private set
    var isCalling: Boolean by mutableStateOf(false)
        private set
    var forwarding: ForwardingCheckResponse? by mutableStateOf(null)
        private set
    var lineError: Boolean by mutableStateOf(false)
        private set
    var isStartingCheck: Boolean by mutableStateOf(false)
        private set

    /** True while this client is polling a pending check. */
    var isPolling: Boolean by mutableStateOf(false)
        private set

    /** The poll reached its limit with the check still pending on the service. */
    var pollGaveUp: Boolean by mutableStateOf(false)
        private set
    var checkError: FailureText? by mutableStateOf(null)
        private set
    var summary: SetupGoLiveSummary? by mutableStateOf(null)
        private set

    private var pollJob: Job? = null

    init {
        load()
    }

    val hasNumber: Boolean get() = (wizard.data?.numbersHeld ?: 0) > 0

    val checkIsPending: Boolean get() = forwarding?.forwardingCheck?.isPending == true

    private fun load() {
        wizard.scope.launch {
            val forwardingResult = wizard.deps.setup.forwardingCheck(wizard.workspaceId)
            val config = wizard.deps.config.load(wizard.workspaceId)
            val handling = wizard.deps.callHandling.callHandling(wizard.workspaceId)
            forwarding = (forwardingResult as? ApiResult.Success)?.value
            if (config is ApiResult.Success && handling is ApiResult.Success) {
                summary = SetupGoLiveSummary(
                    greeting = config.value.aiPersona?.greeting?.trim().orEmpty(),
                    mode = handling.value.callHandling,
                    ringSeconds = handling.value.appRingSeconds,
                )
            }
            if (checkIsPending) poll()
        }
    }

    // ── The test call ───────────────────────────────────────────────────────────

    fun callMe() {
        if (isCalling || !hasNumber) return
        val phone = SetupWire.e164(mobile, wizard.region)
        if (phone == null) {
            mobileError = true
            return
        }
        isCalling = true
        mobileError = false
        callStatus = null
        wizard.scope.launch {
            try {
                callStatus = when (val result = wizard.deps.setup.placeTestCall(wizard.workspaceId, phone)) {
                    is ApiResult.Success -> SetupCallStatus.Calling
                    is ApiResult.Failure -> SetupCallStatus.Failed(result.toFailureText())
                }
            } finally {
                isCalling = false
            }
        }
    }

    // ── The forwarding check ────────────────────────────────────────────────────

    /**
     * ⛔ A CHECK THE SERVICE STILL HOLDS AS PENDING IS READ AGAIN, NOT STARTED AGAIN: after the poll gave
     * up, the button polls once more rather than placing a second call and spending one of the day's
     * checks.
     */
    fun startCheck() {
        if (isStartingCheck || isPolling) return
        if (checkIsPending) {
            poll()
            return
        }
        val line = SetupWire.e164(businessLine, wizard.region)
        if (line == null) {
            lineError = true
            return
        }
        isStartingCheck = true
        lineError = false
        checkError = null
        wizard.scope.launch {
            val result = try {
                wizard.deps.setup.startForwardingCheck(wizard.workspaceId, line, mode)
            } finally {
                isStartingCheck = false
            }
            when (result) {
                is ApiResult.Success -> {
                    forwarding = result.value
                    poll()
                }
                is ApiResult.Failure -> checkError = result.toFailureText()
            }
        }
    }

    /** ⚠️ A FAILED POLL IS RETRIED ON THE NEXT TICK, as the web's is; the limit is what ends it. */
    private fun poll() {
        if (isPolling) return
        isPolling = true
        pollGaveUp = false
        pollJob = wizard.scope.launch {
            try {
                var ticks = 0
                while (checkIsPending && ticks < wizard.timing.pollLimit) {
                    ticks += 1
                    delay(wizard.timing.pollIntervalMillis)
                    val next = wizard.deps.setup.forwardingCheck(wizard.workspaceId)
                    if (next is ApiResult.Success) forwarding = next.value
                }
            } finally {
                isPolling = false
                pollGaveUp = checkIsPending
            }
        }
    }

    /** The sentence for the check on screen, or null before the first one. */
    val checkSentence: SetupCheckSentence?
        get() {
            val check = forwarding?.forwardingCheck ?: return null
            return when {
                check.isPending && pollGaveUp -> SetupCheckSentence.STILL_PENDING
                check.isPending -> SetupCheckSentence.PENDING
                check.hasPassed -> SetupCheckSentence.PASSED
                check.failure == "dial_failed" -> SetupCheckSentence.DIAL_FAILED
                else -> SetupCheckSentence.FAILED
            }
        }

    // ── Finish ──────────────────────────────────────────────────────────────────

    fun finish() {
        wizard.completeStep(SetupStep.GOLIVE, SETUP_STEP_DONE)
    }

    override fun close() {
        pollJob?.cancel()
    }
}
