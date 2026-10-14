package com.distronode.districtai.ui.scheduling.admin.developer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingFailureLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingRemoval
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionScaffold
import com.distronode.districtai.ui.scheduling.admin.SectionState

/** The developer section, wired to its ViewModel. */
@Composable
fun SchedulingDeveloperRoute(deps: SchedulingAdminDeps) {
    val viewModel: SchedulingDeveloperViewModel = viewModel(
        key = "scheduling-developer-${deps.workspaceId}",
        factory = SchedulingDeveloperViewModel.factory(deps.admin, deps.scheduling, deps.workspaceId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnSessionChanged(deps.sessionEpoch) { viewModel.load() }
    SchedulingDeveloperScreen(
        state = state,
        removals = viewModel.removals,
        writes = viewModel.writes,
        onBack = deps.onBack,
        onRefresh = @DontMemoize { viewModel.reload() },
        onSelectTab = @DontMemoize { tab -> viewModel.selectTab(tab) },
        onToggleDeliveries = @DontMemoize { id -> viewModel.toggleDeliveries(id) },
        onRetryDeliveries = @DontMemoize { id -> viewModel.loadDeliveries(id) },
    )
}

/**
 * API keys, connected apps and webhooks, ported from district-ios's `SchedulingDeveloperView`.
 *
 * ⛔ EVERY WRITE HERE IS `client`-LEVEL AND DRAWN ONLY WHEN [SchedulingDeveloperUiState.canManage]
 * SAYS SO; reading the lists and a webhook's deliveries is `viewer`-level and offered to everyone.
 */
@Composable
fun SchedulingDeveloperScreen(
    state: SchedulingDeveloperUiState,
    removals: SchedulingDeveloperRemovals,
    writes: SchedulingDeveloperWrites,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTab: (SchedulingDeveloperTab) -> Unit,
    onToggleDeliveries: (String) -> Unit,
    onRetryDeliveries: (String) -> Unit,
) {
    SchedulingSectionScaffold(
        title = stringResource(SchedulingSection.DEVELOPER.title),
        rootTag = SCHEDULING_DEVELOPER_ROOT_TAG,
        onBack = onBack,
        onRefresh = onRefresh,
    ) {
        Text(
            text = stringResource(R.string.scheduling_admin_common_times_in, state.timezone),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
        )
        SchedulingChoiceRow(
            options = SchedulingDeveloperTab.entries.map { it to stringResource(it.label) },
            selected = state.tab,
            onSelect = onSelectTab,
            tagPrefix = SCHEDULING_DEVELOPER_TAB_TAG,
        )
        when (state.tab) {
            SchedulingDeveloperTab.KEYS -> DeveloperKeysTab(state, removals.keyRevoke, writes, onRefresh)
            SchedulingDeveloperTab.APPS -> DeveloperAppsTab(state, removals.appRevoke, onRefresh)
            SchedulingDeveloperTab.WEBHOOKS -> DeveloperWebhooksTab(
                state = state,
                delete = removals.webhookDelete,
                writes = writes,
                onRetry = onRefresh,
                onToggleDeliveries = onToggleDeliveries,
                onRetryDeliveries = onRetryDeliveries,
            )
        }
    }
    SchedulingDeveloperDialogs(writes)
    SchedulingDeveloperRemovalDialogs(removals)
}

/** One tab's read: loading (also before the first read), failed with Retry, or its rows. */
@Composable
internal fun <T> DeveloperRead(read: SectionState<T>?, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (read) {
        is SectionState.Ready -> content(read.value)
        is SectionState.Failed -> SchedulingSectionFailure(read.failure, onRetry, SCHEDULING_DEVELOPER_FAILURE_TAG)
        // ⚠️ Not yet read (null) or on its way: both draw as loading.
        SectionState.Loading, null -> SchedulingSectionLoading(SCHEDULING_DEVELOPER_LOADING_TAG)
    }
}

/** A removal that failed, under the list it was made from, until it is dismissed. */
@Composable
internal fun <Row> DeveloperRemovalFailure(removal: SchedulingRemoval<Row>) {
    val state by removal.state.collectAsState()
    state.failure?.let { SchedulingFailureLine(it, onDismiss = @DontMemoize { removal.dismissFailure() }) }
}

/** A list that read fine and holds nothing. */
@Composable
internal fun DeveloperEmptyLine(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = Modifier.testTag(tag),
    )
}

/** A small action under a row. */
@Composable
internal fun DeveloperRowButton(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    DistrictButton(
        text = text,
        onClick = onClick,
        variant = ButtonVariant.Ghost,
        size = ButtonSize.Sm,
        enabled = enabled,
        modifier = Modifier.testTag(tag),
    )
}

const val SCHEDULING_DEVELOPER_ROOT_TAG: String = "district-scheduling-developer"
const val SCHEDULING_DEVELOPER_TAB_TAG: String = "district-scheduling-developer-tab"
const val SCHEDULING_DEVELOPER_LOADING_TAG: String = "district-scheduling-developer-loading"
const val SCHEDULING_DEVELOPER_FAILURE_TAG: String = "district-scheduling-developer-failure"
