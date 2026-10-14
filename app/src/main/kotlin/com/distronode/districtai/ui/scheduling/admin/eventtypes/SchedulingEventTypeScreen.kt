package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.core.model.SchedulingHost
import com.distronode.districtai.core.model.SchedulingQuestion
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingCard
import com.distronode.districtai.ui.scheduling.admin.SchedulingFailureLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionScaffold
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingMinutes
import com.distronode.districtai.ui.scheduling.admin.valueOrNull

/** One event type, wired to its ViewModel. ⚠️ A delete that landed goes back, see the ViewModel. */
@Composable
fun SchedulingEventTypeRoute(deps: SchedulingAdminDeps, slug: String) {
    val viewModel: SchedulingEventTypeViewModel = viewModel(
        key = "scheduling-event-type-${deps.workspaceId}-$slug",
        factory = SchedulingEventTypeViewModel.factory(deps.admin, deps.scheduling, deps.workspaceId, slug),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.writes.dialog.collectAsStateWithLifecycle()
    OnSessionChanged(deps.sessionEpoch) { viewModel.load() }
    LaunchedEffect(state.deleted) { if (state.deleted) deps.onBack() }
    val writes = viewModel.writes
    SchedulingEventTypeScreen(
        state = state,
        slug = slug,
        onBack = deps.onBack,
        onRefresh = @DontMemoize { viewModel.load() },
        bar = SchedulingEventTypeBar(
            onEdit = @DontMemoize { writes.editor.openEdit(it) },
            onManage = @DontMemoize { writes.actions.open(it) },
            onHosts = @DontMemoize { writes.hosts.open(it) },
            onQuestions = @DontMemoize { writes.questions.open(it.slug) },
        ),
    )
    SchedulingEventTypeDialogs(dialog, writes)
}

/**
 * The event type's details, its hosts and its questions, ported from district-ios's
 * `SchedulingEventTypeView`. ⚠️ The title is the event type's name once it loads and the slug the
 * route carried before that.
 *
 * ⛔ THE WRITE BAR IS DRAWN ONLY FOR A MEMBER WHO MAY MANAGE AND ONLY FROM THE LOADED ROW: the hosts
 * save replaces the list wholesale and the edit sends every field, so neither may open over a read
 * that is on its way or failed.
 */
@Composable
fun SchedulingEventTypeScreen(
    state: SchedulingEventTypeUiState,
    slug: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    bar: SchedulingEventTypeBar,
) {
    val item = state.eventType.valueOrNull
    SchedulingSectionScaffold(
        title = item?.name ?: slug,
        rootTag = SCHEDULING_EVENT_TYPE_ROOT_TAG,
        onBack = onBack,
        onRefresh = onRefresh,
    ) {
        Details(state.eventType, onRefresh)
        if (state.canManage && item != null) WriteBar(item, bar)
        Hosts(state.hosts)
        Questions(state.questions)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WriteBar(item: SchedulingEventType, bar: SchedulingEventTypeBar) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_common_manage)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_event_types_edit),
                onClick = @DontMemoize { bar.onEdit(item) },
                modifier = Modifier.testTag(SCHEDULING_EVENT_TYPE_EDIT_TAG),
            )
            BarButton(R.string.scheduling_admin_common_manage, SCHEDULING_EVENT_TYPE_MANAGE_TAG) @DontMemoize {
                bar.onManage(item)
            }
            BarButton(R.string.scheduling_admin_event_types_hosts_title, SCHEDULING_EVENT_TYPE_HOSTS_TAG) {
                bar.onHosts(item)
            }
            BarButton(R.string.scheduling_admin_event_types_questions_title, SCHEDULING_EVENT_TYPE_QUESTIONS_TAG) {
                bar.onQuestions(item)
            }
        }
    }
}

@Composable
private fun BarButton(@StringRes label: Int, tag: String, onClick: () -> Unit) {
    DistrictButton(
        text = stringResource(label),
        onClick = onClick,
        variant = ButtonVariant.Secondary,
        modifier = Modifier.testTag(tag),
    )
}

/** ⚠️ An absent fact is dropped rather than shown as "Not set" beside real ones, as the reference does. */
@Composable
private fun Details(row: SectionState<SchedulingEventType>, onRetry: () -> Unit) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_event_types_details_eyebrow)) {
        when (row) {
            is SectionState.Failed -> SchedulingSectionFailure(row.failure, onRetry, SCHEDULING_EVENT_TYPE_FAILURE_TAG)
            is SectionState.Ready -> {
                row.value.description?.takeIf { it.isNotEmpty() }?.let { SchedulingHint(it) }
                eventTypeFacts(row.value).forEach { (label, value) -> SchedulingReadOnlyRow(label, value) }
            }
            else -> SchedulingSectionLoading(SCHEDULING_EVENT_TYPE_LOADING_TAG, rows = 1)
        }
    }
}

@Composable
private fun eventTypeFacts(item: SchedulingEventType): List<Pair<String, String>> = buildList {
    add(stringResource(R.string.scheduling_admin_event_types_fact_duration) to schedulingMinutes(item.durationMinutes))
    item.slotIntervalMinutes?.let {
        add(stringResource(R.string.scheduling_admin_event_types_fact_interval) to schedulingMinutes(it))
    }
    val location = schedulingLocationLabel(item.locationType)
    add(stringResource(R.string.scheduling_admin_event_types_fact_location) to location)
    item.locationValue?.takeIf { it.isNotEmpty() }?.let {
        add(stringResource(R.string.scheduling_admin_event_types_fact_location_value) to it)
    }
    item.minNoticeMinutes?.let {
        add(stringResource(R.string.scheduling_admin_event_types_fact_notice) to schedulingMinutes(it))
    }
    item.maxFutureDays?.let {
        val days = pluralStringResource(R.plurals.scheduling_admin_event_types_days_ahead, it, it)
        add(stringResource(R.string.scheduling_admin_event_types_fact_horizon) to days)
    }
    item.bufferBeforeMinutes?.takeIf { it > 0 }?.let {
        add(stringResource(R.string.scheduling_admin_event_types_fact_buffer_before) to schedulingMinutes(it))
    }
    item.bufferAfterMinutes?.takeIf { it > 0 }?.let {
        add(stringResource(R.string.scheduling_admin_event_types_fact_buffer_after) to schedulingMinutes(it))
    }
    add(stringResource(R.string.scheduling_admin_event_types_fact_state) to schedulingEventTypeState(item).label)
}

/**
 * ⚠️ AN ARCHIVED HOST IS STILL LISTED, with the word: the fork keeps the link, and hiding the row would
 * make a rota look complete when it is not.
 */
@Composable
private fun Hosts(hosts: SectionState<List<SchedulingHost>>) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_event_types_hosts_eyebrow)) {
        ChildRows(
            hosts,
            R.string.scheduling_admin_event_types_no_hosts,
            SCHEDULING_EVENT_TYPE_HOSTS_LOADING_TAG,
        ) { host ->
            val role = schedulingWireLabel(host.role, SCHEDULING_HOST_ROLES)
            SchedulingReadOnlyRow(
                label = host.name.ifEmpty { host.email },
                value = if (host.archived) {
                    stringResource(R.string.scheduling_admin_event_types_host_archived, role)
                } else {
                    role
                },
            )
        }
    }
}

/** ⚠️ Only "required" is worth the word: optional is the default and says nothing. */
@Composable
private fun Questions(questions: SectionState<List<SchedulingQuestion>>) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_event_types_questions_eyebrow)) {
        ChildRows(
            questions,
            R.string.scheduling_admin_event_types_no_questions,
            SCHEDULING_EVENT_TYPE_QUESTIONS_LOADING_TAG,
        ) { question ->
            val type = schedulingWireLabel(question.type, SCHEDULING_QUESTION_TYPES)
            SchedulingReadOnlyRow(
                label = question.label,
                value = if (question.required) {
                    stringResource(R.string.scheduling_admin_event_types_question_required, type)
                } else {
                    type
                },
            )
        }
    }
}

/** A child read's rows, its empty line, or its own failure (which does not hide the rest). */
@Composable
private fun <T> ChildRows(
    rows: SectionState<List<T>>,
    @StringRes empty: Int,
    loadingTag: String,
    row: @Composable (T) -> Unit,
) {
    when (rows) {
        is SectionState.Failed -> SchedulingFailureLine(rows.failure)
        is SectionState.Ready -> if (rows.value.isEmpty()) {
            SchedulingHint(stringResource(empty))
        } else {
            rows.value.forEach { row(it) }
        }
        else -> SchedulingSectionLoading(loadingTag, rows = 1)
    }
}

const val SCHEDULING_EVENT_TYPE_ROOT_TAG: String = "district-scheduling-event-type"
const val SCHEDULING_EVENT_TYPE_LOADING_TAG: String = "district-scheduling-event-type-loading"
const val SCHEDULING_EVENT_TYPE_FAILURE_TAG: String = "district-scheduling-event-type-failure"
const val SCHEDULING_EVENT_TYPE_HOSTS_LOADING_TAG: String = "district-scheduling-event-type-hosts-loading"
const val SCHEDULING_EVENT_TYPE_QUESTIONS_LOADING_TAG: String = "district-scheduling-event-type-questions-loading"
const val SCHEDULING_EVENT_TYPE_EDIT_TAG: String = "district-scheduling-event-type-edit"
const val SCHEDULING_EVENT_TYPE_MANAGE_TAG: String = "district-scheduling-event-type-manage"
const val SCHEDULING_EVENT_TYPE_HOSTS_TAG: String = "district-scheduling-event-type-hosts"
const val SCHEDULING_EVENT_TYPE_QUESTIONS_TAG: String = "district-scheduling-event-type-questions"
