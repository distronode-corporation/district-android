package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingPendingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection

/** The hours section. */
@Composable
fun SchedulingHoursRoute(deps: SchedulingAdminDeps) {
    SchedulingPendingSection(stringResource(SchedulingSection.HOURS.title), deps.onBack)
}
