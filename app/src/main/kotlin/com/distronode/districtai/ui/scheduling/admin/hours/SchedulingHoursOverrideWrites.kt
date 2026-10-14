package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.createAvailabilityOverride
import com.distronode.districtai.core.data.deleteAvailabilityOverride
import com.distronode.districtai.core.data.deleteAvailabilityOverrideGroup
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SchedulingRemoval
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The open add-an-override dialog.
 *
 * @property rejection why the form was refused before sending, or null.
 */
data class SchedulingOverrideAddState(
    val form: SchedulingOverrideForm = SchedulingOverrideForm(),
    @get:StringRes val rejection: Int? = null,
    val state: WriteState = WriteState.Idle,
)

/**
 * Add one override, or delete one row (a single day or a whole range) after asking. Ported from
 * district-ios's `SchedulingAvailabilityOverridesModel`.
 *
 * ⚠️ THE CREATE'S ANSWER IS DISCARDED: one date answers the row, a range answers a summary with no
 * row in it, and neither says where it sits among the others. The screen re-reads instead.
 *
 * ⛔ A 409 ("an override already exists on that date") READS AS THE GENERIC FAILURE, as it does in
 * district-ios: [com.distronode.districtai.core.data.SchedulingAdminError] keeps no HTTP status, and
 * guessing which refusal it was would be worse than saying less.
 *
 * @param onChanged told after every create or delete that worked, so the screen re-reads.
 */
class SchedulingHoursOverrideWrites(
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onChanged: () -> Unit,
) {
    private val _add = MutableStateFlow<SchedulingOverrideAddState?>(null)

    /** The open add dialog, or null when it is closed. */
    val add: StateFlow<SchedulingOverrideAddState?> = _add.asStateFlow()

    /**
     * Delete a row after asking. ⛔ [SchedulingOverrideRow.kind] decides the op: the row's own id for
     * a single day, the GROUP's for a range, so a fortnight away goes in one delete.
     */
    val removal = SchedulingRemoval<SchedulingOverrideRow>(
        scope = scope,
        remove = { row ->
            when (row.kind) {
                SchedulingOverrideKind.SINGLE -> admin.deleteAvailabilityOverride(workspaceId, row.target)
                SchedulingOverrideKind.GROUP -> admin.deleteAvailabilityOverrideGroup(workspaceId, row.target)
            }
        },
        onRemoved = { onChanged() },
    )

    fun open() {
        _add.value = SchedulingOverrideAddState()
    }

    /** Close. Ignored while the create is on its way. */
    fun close() {
        if (_add.value?.state?.isWorking == true) return
        _add.value = null
    }

    /** Change the form. ⚠️ An edit clears the last refusal and outcome, so the next press is fresh. */
    fun edit(change: (SchedulingOverrideForm) -> SchedulingOverrideForm) {
        val open = _add.value ?: return
        if (open.state.isWorking) return
        _add.value = SchedulingOverrideAddState(form = change(open.form))
    }

    /** Send the form once; a refused form says why and sends nothing. */
    fun submit() {
        val open = _add.value ?: return
        if (open.state.isWorking) return
        val draft = open.form.draft()
        if (draft == null) {
            _add.value = open.copy(rejection = open.form.error)
            return
        }
        _add.value = open.copy(rejection = null, state = WriteState.Working)
        scope.launch {
            val result = admin.createAvailabilityOverride(workspaceId, draft)
            // ⚠️ The captured dialog is current: nothing edits or closes it while the create is on its way.
            _add.value = open.copy(
                rejection = null,
                state = when (result) {
                    is SchedulingAdminResult.Ok -> WriteState.Done(UiText.Resource(ADDED))
                    is SchedulingAdminResult.Failed -> WriteState.Failed(result.error.toFailureText())
                },
            )
            if (result is SchedulingAdminResult.Ok) onChanged()
        }
    }
}

private val ADDED = R.string.scheduling_admin_hours_override_added
