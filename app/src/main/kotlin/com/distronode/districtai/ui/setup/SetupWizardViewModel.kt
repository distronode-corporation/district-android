package com.distronode.districtai.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.CallHandlingRepository
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.data.PersonaOptionsRepository
import com.distronode.districtai.core.data.SetupWizardRepository
import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.SETUP_STEP_SKIPPED
import com.distronode.districtai.core.model.SETUP_STEP_TODO
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Everything the wizard reads and writes through. */
class SetupWizardDeps(
    val setup: SetupWizardRepository,
    val config: WorkspaceConfigRepository,
    val personaOptions: PersonaOptionsRepository,
    val callHandling: CallHandlingRepository,
    val members: MembersRepository,
    val store: SetupDraftStore,
)

/** The forwarding check's poll: every two seconds, for at most two minutes. A test shortens it. */
data class SetupTiming(val pollIntervalMillis: Long = 2_000, val pollLimit: Int = 60)

sealed interface SetupPhase {
    data object Loading : SetupPhase

    data class Refused(val refusal: SetupRefusal) : SetupPhase

    data class Failed(val failure: FailureText) : SetupPhase

    data object Ready : SetupPhase

    /** Every step is done or skipped and the service stamped `completedAt`. */
    data object Finished : SetupPhase
}

/** A step's own model. [close] stops what it runs in the background when the step leaves the screen. */
interface SetupStepModel {
    fun close() {}
}

/**
 * The setup wizard's shell: the state the service holds, the step on screen, and the one write every
 * step ends in, its own state.
 *
 * ⛔ MOST OF IT RUNS HERE, BUYING RUNS ON THE WEB. The business, the receptionist, what callers should
 * know, how calls are answered, the test call and the forwarding check are this client's; buying a
 * number, a calendar sign-in, the email identity and the transfer number open the web's page in a
 * Custom Tab, and the wizard reads its state again when the person comes back ([resumed]).
 *
 * ⛔ ONE STEP WRITE AT A TIME. [complete] is the shell's single flight: Skip, a step's Continue and
 * Finish all go through it, and a second tap while one is in flight is a no-op rather than a second
 * PATCH. The flag is set on the main thread BEFORE the coroutine starts, so two taps dispatched before
 * a recomposition cannot both pass the guard; every step's own write follows the same rule.
 *
 * ⛔ NO MOVE WHILE A STEP IS WRITING. A step's own save awaits its write and then calls [complete], so
 * Back or Skip tapped in between would be overtaken by that late completion. Each step write holds
 * [beginStepWrite] to its end, and [isMoving] refuses the shell's moves until then.
 *
 * ⚠️ COMPOSE STATE, NOT A StateFlow OF ONE UiState, unlike the settings screens. Six forms of up to
 * forty fields each bind straight to these properties, and every model here is a line-for-line port
 * of the iOS wizard's observable models, which keeps the two clients' rules reviewable side by side.
 * Every write happens on the main thread.
 */
@Suppress("TooManyFunctions")
class SetupWizardViewModel(
    val deps: SetupWizardDeps,
    val workspaceId: String,
    workspaceName: String,
    val appLanguage: AppLanguage,
    val timing: SetupTiming = SetupTiming(),
) : ViewModel() {

    var phase: SetupPhase by mutableStateOf(SetupPhase.Loading)
        private set

    /** The service's last answer: progress, facts, region, plan, numbers. */
    var data: DistrictSetupResponse? by mutableStateOf(null)
        private set

    var active: SetupStep by mutableStateOf(SetupStep.BUSINESS)
        private set

    /** The model of the step on screen; null for step 2, which has none of its own. */
    var stepModel: SetupStepModel? by mutableStateOf(null)
        private set

    /** The last step write's refusal, shown on the step it was for. */
    var stepError: FailureText? by mutableStateOf(null)
        private set

    var isCompleting: Boolean by mutableStateOf(false)
        private set

    private var stepWrites by mutableIntStateOf(0)

    /** What step 1 learned; see [SetupBusinessDraft]. */
    var draft: SetupBusinessDraft by mutableStateOf(
        deps.store.load(workspaceId) ?: SetupBusinessDraft.initial(null, null, workspaceName, appDefault),
    )
        private set

    /**
     * The workspace's name as the service holds it: the name it opened with, then each rename's echo,
     * so step 1 renames whenever the typed name differs from the stored one.
     */
    var workspaceName: String by mutableStateOf(workspaceName)
        private set

    /** Whether step 1 renamed the workspace, so the overview reads its list again on close. */
    var didRename: Boolean by mutableStateOf(false)
        private set

    /** A page opened in a Custom Tab and the person has not come back to the app yet. */
    private var awaitingReturn = false

    private var loadJob: Job? = null

    val scope: CoroutineScope get() = viewModelScope

    val appDefault: SetupLanguage get() = SetupLanguage.of(appLanguage)

    val region: String? get() = data?.region

    /** A step write or a move in flight: Back, Skip and Finish later wait for it. */
    val isMoving: Boolean get() = isCompleting || stepWrites > 0

    init {
        load()
    }

    fun state(step: SetupStep): String? = data?.setupProgress?.steps?.state(step)

    // ── Reading ─────────────────────────────────────────────────────────────────

    /** Opens the wizard where the person left off: the first step still `todo`. */
    fun load() {
        loadJob?.cancel()
        phase = SetupPhase.Loading
        loadJob = viewModelScope.launch {
            when (val result = deps.setup.load(workspaceId)) {
                is ApiResult.Success -> adoptFirstRead(result.value)
                is ApiResult.Failure -> phase = SetupRefusal.from(result)?.let { SetupPhase.Refused(it) }
                    ?: SetupPhase.Failed(result.toFailureText())
            }
        }
    }

    private suspend fun adoptFirstRead(response: DistrictSetupResponse) {
        val progress = response.setupProgress
        if (progress == null) {
            phase = SetupPhase.Refused(SetupRefusal.NOT_AVAILABLE)
            return
        }
        data = response
        // ⛔ STEP 1'S LANGUAGE OPENS ON THE PERSONA'S, as the web's `initialBusinessDraft` does: opening
        // it on the app's language would let step 3 replace a French receptionist's `fr-CA` with
        // `en-US` for an owner who never touched the choice.
        if (deps.store.load(workspaceId) == null) {
            draft = SetupBusinessDraft.initial(response, personaLanguage(), workspaceName, appDefault)
        }
        if (progress.completedAt != null) {
            finish()
            return
        }
        open(SetupFlow.firstOpen(progress))
        phase = SetupPhase.Ready
    }

    /** The persona's stored `language`, null when it cannot be read. */
    private suspend fun personaLanguage(): String? =
        (deps.config.load(workspaceId) as? ApiResult.Success)?.value?.aiPersona?.language

    /** A page was opened in a Custom Tab; [resumed] reads the state again when the person is back. */
    fun handOffOpened() {
        awaitingReturn = true
    }

    /** The screen is in front again. Re-reads only when a hand-off sent the person away. */
    fun resumed() {
        if (!awaitingReturn) return
        awaitingReturn = false
        refreshAfterHandOff()
    }

    /**
     * After a page opened in a Custom Tab: read again, and move on if the web finished the step on
     * screen (a number bought there marks `number` done).
     *
     * ⚠️ A FAILED RE-READ KEEPS THE STEP ON SCREEN. Nothing was written here, so the last answer is
     * still the best one this client has; the step's own controls retry.
     *
     * ⛔ ONLY A STEP THE PAGE FINISHED MOVES ON. A step that was already done or skipped (reached by
     * Back) stays on screen with whatever the person typed into it; only a `todo` that came back not
     * `todo` advances.
     */
    private fun refreshAfterHandOff() {
        if (phase != SetupPhase.Ready) return
        val step = active
        val before = state(step)
        viewModelScope.launch {
            val result = deps.setup.load(workspaceId)
            // ⚠️ AN ANSWER WITH NO PROGRESS CHANGES NOTHING, as a failed read does: the first read refuses
            // one (setup not available), and adopting it here would send the wizard back to step 1.
            val progress = (result as? ApiResult.Success)?.value?.setupProgress
            if (progress != null) {
                data = (result as ApiResult.Success).value
                if (progress.completedAt != null) {
                    finish()
                } else if (before == SETUP_STEP_TODO && state(step) != SETUP_STEP_TODO && active == step) {
                    open(SetupFlow.firstOpen(progress))
                }
            }
            // ⚠️ THE CALLS STEP READS ITS OWN TWO HAND-OFF FACTS AGAIN EITHER WAY: the transfer number
            // and the calendar are changed on those pages and are not in the setup state.
            (stepModel as? SetupCallsModel)?.refreshAfterHandOff()
        }
    }

    /** A step saved something through the setup route, which answers the whole state. */
    fun adopt(response: DistrictSetupResponse) {
        data = response
    }

    fun renamed(to: String) {
        workspaceName = to
        didRename = true
    }

    fun updateDraft(next: SetupBusinessDraft) {
        draft = next
        deps.store.save(next, workspaceId)
    }

    // ── Moving ──────────────────────────────────────────────────────────────────

    /** A step's write starts; pair it with [endStepWrite] in a `finally`. */
    fun beginStepWrite() {
        stepWrites += 1
    }

    fun endStepWrite() {
        stepWrites -= 1
    }

    /** [complete] from a tap: Keep this number, Finish. */
    fun completeStep(step: SetupStep, state: String) {
        if (isCompleting) return
        isCompleting = true
        viewModelScope.launch {
            try {
                write(step, state)
            } finally {
                isCompleting = false
            }
        }
    }

    /**
     * Marks [step] and moves to the next one; true when the write landed.
     *
     * ⛔ THE ONLY WAY A STEP'S STATE CHANGES. A step that saved its own data calls this with `done`;
     * Skip calls it with `skipped`. When the service answers with `completedAt`, the wizard is
     * finished and the draft kept on this device is dropped.
     */
    suspend fun complete(step: SetupStep, state: String): Boolean {
        if (isCompleting) return false
        isCompleting = true
        return try {
            write(step, state)
        } finally {
            isCompleting = false
        }
    }

    private suspend fun write(step: SetupStep, state: String): Boolean {
        stepError = null
        return when (val result = deps.setup.save(workspaceId, steps = mapOf(step.wire to state))) {
            is ApiResult.Success -> {
                data = result.value
                val progress = result.value.setupProgress
                if (progress?.completedAt != null) {
                    finish()
                } else {
                    // ⚠️ PAST THE LAST STEP WITH ONE STILL OPEN (reached by Back), the open one.
                    open(SetupFlow.next(step) ?: SetupFlow.firstOpen(progress))
                }
                true
            }
            is ApiResult.Failure -> {
                stepError = result.toFailureText()
                false
            }
        }
    }

    fun skip() {
        if (isMoving) return
        completeStep(active, SETUP_STEP_SKIPPED)
    }

    fun back() {
        if (isMoving) return
        val previous = SetupFlow.previous(active) ?: return
        stepError = null
        open(previous)
    }

    fun dismissStepError() {
        stepError = null
    }

    private fun open(step: SetupStep) {
        stepModel?.close()
        active = step
        stepModel = when (step) {
            SetupStep.BUSINESS -> SetupBusinessModel(this)
            SetupStep.NUMBER -> null
            SetupStep.RECEPTIONIST -> SetupReceptionistModel(this)
            SetupStep.CALLERS -> SetupCallersModel(this)
            SetupStep.CALLS -> SetupCallsModel(this)
            SetupStep.GOLIVE -> SetupGoLiveModel(this)
        }
    }

    /** ⚠️ EVERY WAY SETUP ENDS FORGETS STEP 1'S DRAFT: a write, the first read, or a hand-off's re-read. */
    private fun finish() {
        deps.store.clear(workspaceId)
        stepModel?.close()
        stepModel = null
        phase = SetupPhase.Finished
    }

    override fun onCleared() {
        stepModel?.close()
    }

    companion object {
        fun factory(
            deps: SetupWizardDeps,
            workspaceId: String,
            workspaceName: String,
            appLanguage: AppLanguage,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SetupWizardViewModel(deps, workspaceId, workspaceName, appLanguage) }
        }
    }
}
