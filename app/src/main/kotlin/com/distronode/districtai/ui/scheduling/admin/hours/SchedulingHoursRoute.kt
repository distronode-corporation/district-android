package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.RemovalState
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingCard
import com.distronode.districtai.ui.scheduling.admin.SchedulingFailureLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingNoticeLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionScaffold
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.overview.workingHoursSummary
import com.distronode.districtai.ui.scheduling.admin.schedulingWeekday

/** The working-hours section, wired to its ViewModel. */
@Composable
fun SchedulingHoursRoute(deps: SchedulingAdminDeps) {
    val viewModel: SchedulingHoursViewModel = viewModel(
        key = "scheduling-hours-${deps.workspaceId}",
        factory = SchedulingHoursViewModel.factory(deps.admin, deps.scheduling, deps.workspaceId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor by viewModel.rulesEditor.state.collectAsStateWithLifecycle()
    val adding by viewModel.overrideWrites.add.collectAsStateWithLifecycle()
    val removal by viewModel.overrideWrites.removal.state.collectAsStateWithLifecycle()
    OnSessionChanged(deps.sessionEpoch, @DontMemoize { viewModel.load() })
    SchedulingHoursScreen(
        state = state,
        removal = removal,
        actions = SchedulingHoursActions(
            onBack = deps.onBack,
            onRefresh = @DontMemoize { viewModel.load() },
            onEditHours = @DontMemoize { viewModel.rulesEditor.open() },
            onAddOverride = @DontMemoize { viewModel.overrideWrites.open() },
            onDeleteOverride = @DontMemoize { viewModel.overrideWrites.removal.ask(it) },
            onDismissRemoval = @DontMemoize { viewModel.overrideWrites.removal.dismissFailure() },
        ),
    )
    editor?.let { SchedulingHoursRulesDialog(it, viewModel.rulesEditor) }
    adding?.let { SchedulingOverrideAddDialog(it, viewModel.overrideWrites, state.today) }
    SchedulingOverrideDeleteDialog(removal, viewModel.overrideWrites.removal)
}

/** Everything the working-hours screen can ask for. */
class SchedulingHoursActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onEditHours: () -> Unit,
    val onAddOverride: () -> Unit,
    val onDeleteOverride: (SchedulingOverrideRow) -> Unit,
    val onDismissRemoval: () -> Unit,
)

/**
 * The weekly hours the member is bookable and the dates they are not, ported from district-ios's
 * `SchedulingHoursView`.
 *
 * ⛔ ALL SEVEN DAYS ARE DRAWN, MONDAY FIRST, INCLUDING THE EMPTY ONES: a grid that hid an unbookable
 * day would make "not bookable on Wednesday" look like "Wednesday did not load".
 *
 * ⛔ THE EDITORS ARE DRAWN ONLY FOR A MEMBER WHO MAY MANAGE (every `availability.*` write is
 * `client`-level), and whatever the two reads came to: the week editor reads for itself.
 */
@Composable
fun SchedulingHoursScreen(
    state: SchedulingHoursUiState,
    removal: RemovalState<SchedulingOverrideRow>,
    actions: SchedulingHoursActions,
) {
    SchedulingSectionScaffold(
        title = stringResource(SchedulingSection.HOURS.title),
        rootTag = SCHEDULING_HOURS_ROOT_TAG,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
    ) {
        if (state.canManage) WriteBar(actions)
        WeekCard(state, actions.onRefresh)
        OverridesCard(state, removal, actions)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WriteBar(actions: SchedulingHoursActions) {
    // ⚠️ A FLOW ROW, SO A LABEL THAT DOES NOT FIT MOVES TO THE NEXT LINE INSTEAD OF BEING SQUEEZED
    // UNTIL IT SPLITS MID-WORD (W4 French QA: en-XA, and French ~30% longer than English).
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        DistrictButton(
            text = stringResource(R.string.scheduling_admin_hours_edit_title),
            onClick = actions.onEditHours,
            modifier = Modifier.testTag(SCHEDULING_HOURS_EDIT_TAG),
        )
        DistrictButton(
            text = stringResource(R.string.scheduling_admin_hours_override_add),
            onClick = actions.onAddOverride,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.testTag(SCHEDULING_HOURS_ADD_OVERRIDE_TAG),
        )
    }
}

@Composable
private fun WeekCard(state: SchedulingHoursUiState, onRetry: () -> Unit) {
    SchedulingCard(eyebrow = stringResource(SchedulingSection.HOURS.title)) {
        val rules = state.rules
        when (rules) {
            is SectionState.Failed ->
                SchedulingSectionFailure(rules.failure, onRetry, SCHEDULING_HOURS_FAILURE_TAG)
            SectionState.Loading -> SchedulingSectionLoading(SCHEDULING_HOURS_LOADING_TAG)
            is SectionState.Ready -> {
                Text(
                    text = workingHoursSummary(rules.value)
                        ?: stringResource(R.string.scheduling_admin_hours_none_at_all),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DistrictTheme.colors.foreground,
                    modifier = Modifier.testTag(SCHEDULING_HOURS_SUMMARY_TAG),
                )
                SchedulingHint(stringResource(R.string.scheduling_admin_common_times_in, state.timezone))
                schedulingWeekFromRules(rules.value).forEachIndexed { index, day ->
                    SchedulingReadOnlyRow(
                        label = schedulingWeekday(index),
                        value = schedulingDayHours(day),
                        modifier = Modifier.testTag("$SCHEDULING_HOURS_DAY_TAG-$index"),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverridesCard(
    state: SchedulingHoursUiState,
    removal: RemovalState<SchedulingOverrideRow>,
    actions: SchedulingHoursActions,
) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_hours_overrides_eyebrow)) {
        SchedulingHint(stringResource(R.string.scheduling_admin_hours_overrides_subtitle))
        val overrides = state.overrides
        when (overrides) {
            is SectionState.Failed -> SchedulingFailureLine(overrides.failure, onRetry = actions.onRefresh)
            SectionState.Loading ->
                SchedulingSectionLoading(SCHEDULING_HOURS_OVERRIDES_LOADING_TAG, rows = 1)
            is SectionState.Ready -> if (overrides.value.isEmpty()) {
                val empty = if (state.canManage) {
                    R.string.scheduling_admin_hours_overrides_empty_manage
                } else {
                    R.string.scheduling_admin_hours_overrides_empty
                }
                SchedulingHint(stringResource(empty), Modifier.testTag(SCHEDULING_HOURS_OVERRIDES_EMPTY_TAG))
            } else {
                overrides.value.forEach { OverrideRow(it, state.canManage, actions) }
            }
        }
        if (removal.removed != null) {
            SchedulingNoticeLine(UiText.Resource(R.string.scheduling_admin_hours_override_deleted))
        }
        removal.failure?.let { SchedulingFailureLine(it, onDismiss = actions.onDismissRemoval) }
    }
}

@Composable
private fun OverrideRow(row: SchedulingOverrideRow, canManage: Boolean, actions: SchedulingHoursActions) {
    Row(
        modifier = Modifier.testTag("$SCHEDULING_HOURS_OVERRIDE_TAG-${row.key}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SchedulingReadOnlyRow(
            label = schedulingOverrideDates(row.start, row.end),
            value = schedulingOverrideHours(row),
            modifier = Modifier.weight(1f),
        )
        if (canManage) {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_hours_override_delete),
                onClick = @DontMemoize { actions.onDeleteOverride(row) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$SCHEDULING_HOURS_OVERRIDE_DELETE_TAG-${row.key}"),
            )
        }
    }
}

const val SCHEDULING_HOURS_ROOT_TAG: String = "district-scheduling-hours"
const val SCHEDULING_HOURS_EDIT_TAG: String = "district-scheduling-hours-edit"
const val SCHEDULING_HOURS_ADD_OVERRIDE_TAG: String = "district-scheduling-hours-add-override"
const val SCHEDULING_HOURS_LOADING_TAG: String = "district-scheduling-hours-loading"
const val SCHEDULING_HOURS_FAILURE_TAG: String = "district-scheduling-hours-failure"
const val SCHEDULING_HOURS_SUMMARY_TAG: String = "district-scheduling-hours-summary"
const val SCHEDULING_HOURS_DAY_TAG: String = "district-scheduling-hours-day"
const val SCHEDULING_HOURS_OVERRIDES_LOADING_TAG: String = "district-scheduling-hours-overrides-loading"
const val SCHEDULING_HOURS_OVERRIDES_EMPTY_TAG: String = "district-scheduling-hours-overrides-empty"
const val SCHEDULING_HOURS_OVERRIDE_TAG: String = "district-scheduling-hours-override"
const val SCHEDULING_HOURS_OVERRIDE_DELETE_TAG: String = "district-scheduling-hours-override-delete"
