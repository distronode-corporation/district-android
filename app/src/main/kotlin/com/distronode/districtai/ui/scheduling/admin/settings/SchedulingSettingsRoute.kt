package com.distronode.districtai.ui.scheduling.admin.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingPendingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection

/** The settings section. */
@Composable
fun SchedulingSettingsRoute(deps: SchedulingAdminDeps) {
    SchedulingPendingSection(stringResource(SchedulingSection.SETTINGS.title), deps.onBack)
}
