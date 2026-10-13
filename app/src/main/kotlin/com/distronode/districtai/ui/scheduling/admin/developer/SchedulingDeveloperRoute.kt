package com.distronode.districtai.ui.scheduling.admin.developer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingPendingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection

/** The developer section. */
@Composable
fun SchedulingDeveloperRoute(deps: SchedulingAdminDeps) {
    SchedulingPendingSection(stringResource(SchedulingSection.DEVELOPER.title), deps.onBack)
}
