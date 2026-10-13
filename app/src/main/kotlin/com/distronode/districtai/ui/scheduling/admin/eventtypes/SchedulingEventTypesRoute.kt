package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingPendingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection

/** The event_types section. */
@Composable
fun SchedulingEventTypesRoute(deps: SchedulingAdminDeps) {
    SchedulingPendingSection(stringResource(SchedulingSection.EVENT_TYPES.title), deps.onBack)
}

/** One event type. */
@Composable
fun SchedulingEventTypeRoute(deps: SchedulingAdminDeps, slug: String) {
    SchedulingPendingSection(slug, deps.onBack)
}
