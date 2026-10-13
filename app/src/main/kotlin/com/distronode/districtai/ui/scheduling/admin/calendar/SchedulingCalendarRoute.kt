package com.distronode.districtai.ui.scheduling.admin.calendar

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingPendingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection

/** The calendar section. */
@Composable
fun SchedulingCalendarRoute(deps: SchedulingAdminDeps) {
    SchedulingPendingSection(stringResource(SchedulingSection.CALENDAR.title), deps.onBack)
}
