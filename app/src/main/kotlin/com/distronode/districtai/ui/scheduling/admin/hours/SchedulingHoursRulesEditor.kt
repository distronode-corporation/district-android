package com.distronode.districtai.ui.scheduling.admin.hours

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.availabilityRules
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.DAYS_IN_WEEK
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The open week editor.
 *
 * @property load the editor's own read of the rules; nothing can be saved until it is in hand.
 * @property week seven days, Monday first, as edited.
 * @property stored the rules the read found: the baseline the save is a diff against.
 */
data class SchedulingRulesEditorState(
    val load: SectionState<Unit> = SectionState.Loading,
    val week: List<List<SchedulingHoursDraft>> = List(DAYS_IN_WEEK) { emptyList() },
    val stored: List<SchedulingAvailabilityRule> = emptyList(),
    val state: WriteState = WriteState.Idle,
) {
    /** Each day's per-window refusals. */
    val errors: List<List<SchedulingRangeError?>> get() = week.map(::schedulingRangeErrors)

    /**
     * ⚠️ SAVE IS DISABLED WHILE ANY WINDOW IS REFUSED, as in district-ios: each refusal is already
     * shown on its window, so a live button would only say it again.
     */
    val canSave: Boolean get() = load is SectionState.Ready && !state.isWorking && isValidSchedulingWeek(week)
}

/**
 * The weekly working-hours editor: read the rules, edit a week, save the difference. Ported from
 * district-ios's `SchedulingAvailabilityRulesModel`.
 *
 * ⛔ THE SAVE IS A DIFF, NOT A REPLACE: `availability.rules.*` is create, patch and delete per row,
 * and a delete-everything-then-recreate would leave the member with no working hours for as long as
 * the middle of that sequence takes. ⛔ THE DIFF IS TAKEN AGAINST THIS EDITOR'S OWN READ, never the
 * screen's, and the form opens only on a read in hand: a diff against a week it never saw would
 * delete every rule it did not see.
 *
 * ⛔ AFTER A SAVE, FAILED OR NOT, THE RULES ARE READ AGAIN AND THE DRAFT IS DISCARDED. The phases
 * stop at the first refusal, so a failure means some of them landed; keeping the draft would build
 * the next diff on a wrong baseline and re-send what already happened.
 *
 * @param onChanged told after every save, so the screen behind re-reads.
 */
class SchedulingHoursRulesEditor(
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onChanged: () -> Unit,
) {
    private val _state = MutableStateFlow<SchedulingRulesEditorState?>(null)

    /** The open editor, or null when it is closed. */
    val state: StateFlow<SchedulingRulesEditorState?> = _state.asStateFlow()

    private var nextKey = 0L

    fun open() {
        _state.value = SchedulingRulesEditorState()
        load()
    }

    /** Close. Ignored while a save is on its way. */
    fun close() {
        if (_state.value?.state?.isWorking == true) return
        _state.value = null
    }

    /** Read the rules this editor diffs against. */
    fun load() {
        val editor = _state.value ?: return
        _state.value = editor.copy(load = SectionState.Loading)
        scope.launch { read() }
    }

    /** Add a 09:00 to 17:00 window to the Monday-first [day]. */
    fun addRange(day: Int) = change { editor ->
        val added = SchedulingHoursDraft(nextKey++, null, SCHEDULING_DEFAULT_START, SCHEDULING_DEFAULT_END)
        editor.copy(week = editor.week.withDay(day) { it + added })
    }

    fun removeRange(day: Int, key: Long) = change { editor ->
        editor.copy(week = editor.week.withDay(day) { ranges -> ranges.filterNot { it.key == key } })
    }

    /** Set one window's two times, as typed. */
    fun setTimes(day: Int, key: Long, start: String, end: String) = change { editor ->
        editor.copy(
            week = editor.week.withDay(day) { ranges ->
                ranges.map { if (it.key == key) it.copy(start = start, end = end) else it }
            },
        )
    }

    /** Copy the Monday-first [day]'s windows onto Monday to Friday. */
    fun copyToWeekdays(day: Int) = change { editor ->
        editor.copy(week = copySchedulingWeekdays(day, editor.week) { nextKey++ })
    }

    /**
     * Send the difference. ⚠️ An empty difference is not sent: a no-op still spends a write from the
     * workspace's hourly budget, so the honest answer is a sentence.
     */
    fun save() {
        val editor = _state.value ?: return
        if (!editor.canSave) return
        val diff = schedulingHoursDiff(editor.week, editor.stored)
        if (diff.isEmpty) {
            val nothing = UiText.Resource(R.string.scheduling_admin_hours_nothing_to_save)
            _state.value = editor.copy(state = WriteState.Done(nothing))
            return
        }
        _state.value = editor.copy(state = WriteState.Working)
        scope.launch {
            val outcome = when (val result = admin.applyHoursDiff(workspaceId, diff)) {
                is SchedulingAdminResult.Ok -> WriteState.Done(UiText.Resource(R.string.scheduling_admin_hours_saved))
                is SchedulingAdminResult.Failed -> WriteState.Failed(result.error.toFailureText())
            }
            // ⚠️ The captured editor is current: nothing edits or closes it while a save is on its way.
            _state.value = editor.copy(state = outcome)
            read()
            onChanged()
        }
    }

    private suspend fun read() {
        val result = admin.availabilityRules(workspaceId)
        val current = _state.value ?: return
        _state.value = when (result) {
            is SchedulingAdminResult.Ok -> current.copy(
                load = SectionState.Ready(Unit),
                stored = result.value,
                week = schedulingWeekFromRules(result.value).map { day ->
                    day.map { SchedulingHoursDraft(nextKey++, it.id, it.startTime, it.endTime) }
                },
            )
            is SchedulingAdminResult.Failed -> current.copy(load = SectionState.Failed(result.error.toFailureText()))
        }
    }

    /**
     * Apply an edit to the open editor. ⚠️ An edit clears the last outcome, so the next Save is a fresh
     * attempt; nothing is edited while a save is on its way or before the read is in hand.
     */
    private fun change(edit: (SchedulingRulesEditorState) -> SchedulingRulesEditorState) {
        val editor = _state.value ?: return
        if (editor.state.isWorking || editor.load !is SectionState.Ready) return
        _state.value = edit(editor).copy(state = WriteState.Idle)
    }
}

private fun <T> List<List<T>>.withDay(day: Int, edit: (List<T>) -> List<T>): List<List<T>> =
    mapIndexed { index, ranges -> if (index == day) edit(ranges) else ranges }
