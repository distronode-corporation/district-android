package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.CallHandlingRepository
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Calls section: who answers, how long the app rings, and whether this person is on call.
 *
 * ⛔ TWO READS AND TWO WRITES ACROSS TWO SCOPES, AND THEY ARE NEVER COMBINED. The handling mode
 * belongs to the WORKSPACE; the availability switch belongs to the CALLER'S OWN membership row and
 * the route takes no identity at all. A single save button over both would be one tap changing two
 * different people's worth of state.
 *
 * ⛔ BOTH SAVES ADOPT THEIR OWN RESPONSE INSTEAD OF RE-READING, unlike every other form in this
 * package. Each PATCH echoes what it wrote through the same normaliser the read uses, so a second
 * request could only confirm what is already in hand — see [CallHandlingRepository].
 */
class CallHandlingViewModel(
    private val repository: CallHandlingRepository,
    private val workspaceId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    private val _state = MutableStateFlow(CallHandlingUiState(canMutate = role.allowsMutation()))
    val state: StateFlow<CallHandlingUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * ⚠️ BOTH READS, ALWAYS, AND EITHER MAY FAIL ALONE. They are sequential rather than concurrent
     * because this screen is two short reads on a settings page, and a `viewModelScope.launch` per
     * read would make the two failures arrive in a nondeterministic order for a test to assert.
     */
    fun load() {
        _state.value = _state.value.copy(
            load = CallHandlingLoad.Loading,
            availability = AvailabilityLoad.Loading,
            modeDraft = null,
            ringDraft = null,
            save = SaveState.Idle,
            availabilitySave = SaveState.Idle,
        )
        viewModelScope.launch {
            val handling = when (val result = repository.callHandling(workspaceId)) {
                is ApiResult.Success -> CallHandlingLoad.Ready(result.value)
                is ApiResult.Failure -> CallHandlingLoad.LoadFailed(result.toFailureText())
            }
            val availability = when (val result = repository.availability(workspaceId)) {
                is ApiResult.Success -> AvailabilityLoad.Ready(result.value)
                is ApiResult.Failure -> AvailabilityLoad.LoadFailed(result.toFailureText())
            }
            _state.value = _state.value.copy(load = handling, availability = availability)
        }
    }

    fun selectMode(mode: String) {
        if (!_state.value.canMutate || _state.value.load !is CallHandlingLoad.Ready) return
        // ⛔ REFUSED RATHER THAN PASSED THROUGH. An unknown mode can only come from this app's own
        // code, and the route answers 400 for it — a picker that offered one would produce an error
        // the operator cannot act on.
        if (!CallHandling.isKnown(mode)) return
        _state.value = _state.value.copy(modeDraft = mode, save = SaveState.Idle)
    }

    /**
     * ⛔ CLAMPED HERE AND NOT IN THE REPOSITORY, WHICH IS THE ONE PLACE CLAMPING IS RIGHT. This is a
     * slider: the bound is a property of the control, and letting a value outside it reach the
     * request would be a 400 for a position the control should never have offered. The repository
     * deliberately does NOT clamp, so a value arriving from anywhere else is still reported.
     */
    fun selectRingSeconds(seconds: Int) {
        if (!_state.value.canMutate || _state.value.load !is CallHandlingLoad.Ready) return
        _state.value = _state.value.copy(
            ringDraft = CallHandling.clampRing(seconds),
            save = SaveState.Idle,
        )
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return

        _state.value = current.copy(save = SaveState.Saving)
        viewModelScope.launch {
            val result = repository.saveCallHandling(
                workspaceId = workspaceId,
                callHandling = current.pendingMode,
                appRingSeconds = current.pendingRingSeconds,
            )
            _state.value = when (result) {
                // ⛔ THE RESPONSE IS THE NEW BASELINE. The drafts are cleared against it rather than
                // kept, so a screen that saved one field and left the other dirty stays dirty on
                // the field it did not send.
                is ApiResult.Success -> _state.value.copy(
                    load = CallHandlingLoad.Ready(result.value),
                    modeDraft = null,
                    ringDraft = null,
                    save = SaveState.Saved,
                )
                is ApiResult.Failure -> _state.value.copy(
                    save = SaveState.Failed(result.toFailureText()),
                )
            }
        }
    }

    /**
     * ⛔ THE SWITCH SENDS IMMEDIATELY AND HAS NO SAVE BUTTON, unlike the mode above it. "I am on
     * call" is a statement about right now — a draft sitting unsent while the phone does not ring
     * is the failure this control exists to prevent — and the write is a single scalar on the
     * caller's own row, so there is nothing a mistaken tap can destroy.
     *
     * ⚠️ A 409 IS LEFT AS A FAILURE. It means the caller has no membership row, so the ring fan-out
     * cannot reach them; showing the switch as moved would claim otherwise.
     */
    fun setAvailability(available: Boolean) {
        val current = _state.value
        if (!current.canToggleAvailability || available == current.availableForCalls) return

        _state.value = current.copy(availabilitySave = SaveState.Saving)
        viewModelScope.launch {
            val result = repository.saveAvailability(workspaceId, available)
            _state.value = when (result) {
                is ApiResult.Success -> _state.value.copy(
                    availability = AvailabilityLoad.Ready(result.value),
                    availabilitySave = SaveState.Saved,
                )
                is ApiResult.Failure -> _state.value.copy(
                    availabilitySave = SaveState.Failed(result.toFailureText()),
                )
            }
        }
    }

    companion object {
        fun factory(
            repository: CallHandlingRepository,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CallHandlingViewModel(repository, workspaceId, role) }
        }
    }
}
