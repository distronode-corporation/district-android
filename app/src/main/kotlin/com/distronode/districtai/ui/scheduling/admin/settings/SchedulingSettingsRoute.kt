package com.distronode.districtai.ui.scheduling.admin.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.inbox.AttachmentReader
import com.distronode.districtai.ui.inbox.ContentResolverAttachmentReader
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionScaffold
import kotlinx.coroutines.launch

/** The settings section, wired to its ViewModel and the system photo picker. */
@Composable
fun SchedulingSettingsRoute(deps: SchedulingAdminDeps) {
    val context = LocalContext.current
    val reader = remember(context) { ContentResolverAttachmentReader(context.contentResolver) }
    SchedulingSettingsHost(deps, reader)
}

/**
 * The route's body, with the attachment reader as a seam a test can replace.
 *
 * ⚠️ THE PICKER LIVES HERE, NOT IN THE VIEWMODEL: `PickVisualMedia` hands back a URI with a one-shot
 * read grant, [AttachmentReader] turns it into bytes, a name and a type at once (the same reader
 * the inbox composer uses), and [SchedulingImageFlow.upload] decides from those. No permission is
 * requested or needed.
 */
@Composable
internal fun SchedulingSettingsHost(deps: SchedulingAdminDeps, reader: AttachmentReader) {
    val viewModel: SchedulingSettingsViewModel = viewModel(
        key = "scheduling-settings-${deps.workspaceId}",
        factory = SchedulingSettingsViewModel.factory(deps.admin, deps.scheduling, deps.workspaceId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnSessionChanged(deps.sessionEpoch) { viewModel.load() }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) @DontMemoize { uri ->
        // ⚠️ No URI is the member closing the picker: nothing to say and nothing to send.
        if (uri != null) scope.launch { viewModel.writes.images.upload(reader.read(uri.toString())) }
    }
    SchedulingSettingsScreen(
        state = state,
        writes = viewModel.writes,
        onBack = deps.onBack,
        onRefresh = @DontMemoize { viewModel.reload() },
        onSelectTab = @DontMemoize { tab -> viewModel.selectTab(tab) },
        onPickImage = @DontMemoize { target ->
            viewModel.writes.images.choose(target)
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
    )
}

/**
 * The booking page, the booking assistant, the profile and the notifications, ported from
 * district-ios's `SchedulingSettingsView`.
 *
 * ⛔ [SchedulingSettingsUiState.canManage] GATES TWO OF THE FOUR TABS' EDITORS AND NEVER THE OTHER
 * TWO: a viewer who cannot set their own timezone is offered every booking window in the wrong
 * hours.
 */
@Composable
fun SchedulingSettingsScreen(
    state: SchedulingSettingsUiState,
    writes: SchedulingSettingsWrites,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectTab: (SchedulingSettingsTab) -> Unit,
    onPickImage: (SchedulingAdminUploadTarget) -> Unit,
) {
    SchedulingSectionScaffold(
        title = stringResource(SchedulingSection.SETTINGS.title),
        rootTag = SCHEDULING_SETTINGS_ROOT_TAG,
        onBack = onBack,
        onRefresh = onRefresh,
    ) {
        SchedulingChoiceRow(
            options = SchedulingSettingsTab.entries.map { it to stringResource(it.label) },
            selected = state.tab,
            onSelect = onSelectTab,
            tagPrefix = SCHEDULING_SETTINGS_TAB_TAG,
        )
        when (state.tab) {
            SchedulingSettingsTab.BOOKING_PAGE -> SettingsBrandingTab(state, writes, onRefresh)
            SchedulingSettingsTab.ASSISTANT -> SettingsAssistantTab(state, writes, onRefresh)
            SchedulingSettingsTab.PROFILE -> SettingsProfileTab(state, writes, onRefresh)
            else -> SettingsNotificationsTab(state, writes, onRefresh)
        }
    }
    SchedulingSettingsDialogs(writes, onPickImage)
}

const val SCHEDULING_SETTINGS_ROOT_TAG: String = "district-scheduling-settings"
const val SCHEDULING_SETTINGS_TAB_TAG: String = "district-scheduling-settings-tab"
