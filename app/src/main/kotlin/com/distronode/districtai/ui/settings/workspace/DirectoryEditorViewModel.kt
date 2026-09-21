package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.SaveOutcome
import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.model.DirectoryEntry
import com.distronode.districtai.core.model.DirectoryField
import com.distronode.districtai.core.model.directoryPatch
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The transfer directory's state machine.
 *
 * ⛔ EVERY MUTATOR HERE OPENS WITH THE SAME GUARD, AND IT IS NOT DEFENSIVE PROGRAMMING. The array
 * this builds REPLACES the stored one, so an edit accumulated against no baseline is a deletion
 * waiting for a save. [DirectoryEditorUiState.editable] is false both when the read failed and when
 * the stored value is a shape this client cannot carry losslessly, and both cases refuse an edit.
 *
 * ⛔ EDITS START FROM THE LOADED ROWS, NEVER FROM A REBUILT MODEL. `edit` calls
 * `DirectoryEntry.with`, which overwrites one key of the stored JSON object and leaves every other
 * key — including ones this app has never heard of — exactly where it was. The route's schema is
 * `.passthrough()` for that reason, and the fixture round trip proves the result is byte-identical
 * when nothing was touched.
 *
 * ⚠️ NO AUTO-SAVE, DELIBERATELY DIVERGING FROM THE WEB. `CallDirectorySettings` posts the whole
 * array on every add and every remove, so a mis-tap there is already saved. On a phone that would
 * mean a fat-finger delete of a transfer target with no confirmation, so edits are local until an
 * explicit, count-confirmed save.
 */
class DirectoryEditorViewModel(
    private val repository: WorkspaceConfigRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DirectoryEditorUiState())
    val state: StateFlow<DirectoryEditorUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** ⛔ The only route to an editable state, and it CLEARS the draft. */
    fun load() {
        _state.value = _state.value.copy(load = ConfigState.Loading)
        viewModelScope.launch {
            _state.value = when (val result = repository.load(workspaceId)) {
                is ApiResult.Success -> DirectoryEditorUiState(load = ConfigState.Ready(result.value))
                is ApiResult.Failure -> _state.value.copy(
                    load = ConfigState.LoadFailed(result.toFailureText()),
                )
            }
        }
    }

    /** ⚠️ Typing clears a previous rejection, so the warning belongs to the attempt that earned it. */
    fun editNewEntry(field: DirectoryField, value: String) {
        val current = _state.value
        _state.value = when (field) {
            DirectoryField.NAME -> current.copy(newName = value, addRejected = false)
            DirectoryField.PHONE_NUMBER -> current.copy(newPhoneNumber = value, addRejected = false)
        }
    }

    /**
     * Append the pending row.
     *
     * ⛔ REFUSES A BLANK NAME OR NUMBER, WHICH IS EXACTLY WHAT THE WEB FORM DOES AND NO MORE. No
     * phone-format check: the server stores what it is given and real rows carry extensions and
     * national formats, so a client-side E.164 rule would refuse data the product already accepts.
     */
    fun addEntry() {
        val current = _state.value
        if (!current.editable || current.save.busy) return
        if (!current.canAdd) {
            _state.value = current.copy(addRejected = true)
            return
        }
        val entry = DirectoryEntry.newEntry(
            name = current.newName.trim(),
            phoneNumber = current.newPhoneNumber.trim(),
        )
        _state.value = current.copy(
            draft = current.entries + entry,
            newName = "",
            newPhoneNumber = "",
            addRejected = false,
            save = SaveState.Idle,
        )
    }

    /**
     * Change one field of one existing row.
     *
     * ⛔ THROUGH `DirectoryEntry.with`, SO THE ROW'S OTHER KEYS SURVIVE. Rebuilding the entry from
     * name and number would drop anything a newer client had stored on it — silently, through a
     * route that writes back exactly what it receives.
     */
    fun edit(index: Int, field: DirectoryField, value: String) {
        val current = _state.value
        if (!current.editable || current.save.busy) return
        val entries = current.entries
        val entry = entries.getOrNull(index) ?: return
        _state.value = current.copy(
            draft = entries.toMutableList().also { it[index] = entry.with(field, value) },
            save = SaveState.Idle,
        )
    }

    /** ⚠️ Local only. The deletion reaches the server on the confirmed save, not on this tap. */
    fun remove(index: Int) {
        val current = _state.value
        if (!current.editable || current.save.busy) return
        val entries = current.entries
        if (index !in entries.indices) return
        _state.value = current.copy(
            draft = entries.filterIndexed { i, _ -> i != index },
            save = SaveState.Idle,
        )
    }

    /**
     * Replace the stored directory with what is on screen.
     *
     * ⛔ THE CONFIRMATION IS THE SCREEN'S JOB AND IT HAS ALREADY HAPPENED BY HERE. This is the
     * point of no return: whatever [DirectoryEditorUiState.entries] holds becomes the workspace's
     * complete transfer directory, and an empty list removes every target with a 200.
     */
    fun save() {
        val current = _state.value
        if (!current.canSave) return

        _state.value = current.copy(save = SaveState.Saving)
        viewModelScope.launch {
            val outcome = repository.saveDirectory(directoryPatch(workspaceId, current.entries))
            _state.value = applyOutcome(_state.value, outcome)
        }
    }

    /** ⚠️ Retires the banner without a re-read. */
    fun dismissSaveNotice() {
        val current = _state.value
        if (current.save.busy) return
        _state.value = current.copy(save = SaveState.Idle)
    }

    /**
     * ⛔ ON SUCCESS THE DRAFT IS DROPPED AND THE FRESH CONFIG BECOMES THE BASELINE. Keeping it would
     * leave the screen reading "changed" against a server that now agrees, and the next save would
     * replace the array from a baseline that had moved.
     *
     * ⛔ ON [SaveOutcome.SavedButStale] THE DRAFT IS DROPPED TOO — the write LANDED — but the
     * baseline on screen is the pre-save one, which is what the banner says. The remedy is a
     * re-read; a second save from here would replace the stored array from state the client can no
     * longer vouch for.
     */
    private fun applyOutcome(
        current: DirectoryEditorUiState,
        outcome: SaveOutcome,
    ): DirectoryEditorUiState = when (outcome) {
        is SaveOutcome.Saved -> current.copy(
            load = ConfigState.Ready(outcome.config),
            draft = null,
            save = SaveState.Saved,
        )
        is SaveOutcome.SavedButStale -> current.copy(
            draft = null,
            save = SaveState.SavedButStale(outcome.failure.toFailureText()),
        )
        // ⛔ THE DRAFT SURVIVES. Nothing was written and the operator's list is still theirs.
        is SaveOutcome.NotSaved -> current.copy(
            save = SaveState.Failed(outcome.failure.toFailureText()),
        )
    }

    companion object {
        fun factory(
            repository: WorkspaceConfigRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DirectoryEditorViewModel(repository, workspaceId) as T
        }
    }
}
