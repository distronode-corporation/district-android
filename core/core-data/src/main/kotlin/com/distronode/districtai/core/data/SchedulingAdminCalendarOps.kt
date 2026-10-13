package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingCaldavConnection
import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.core.model.SchedulingCalendarSelections
import com.distronode.districtai.core.model.SchedulingCalendarStatus
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingZoomStatus
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

// The caller's own calendars. Viewer-level throughout, writes included: a member who cannot
// connect their own calendar cannot be booked at all.
//
// ⚠️ THE ACCOUNT KEY IS `account` ON THE READ AND THE DELETE AND `account_email` ON THE TWO WRITES.
// That is the catalog's spelling, copied from district-core-swift; "fixing" either is a 400.

suspend fun SchedulingAdminRepository.calendarStatus(
    workspaceId: String,
): SchedulingAdminResult<SchedulingCalendarStatus> = perform(
    workspaceId,
    SchedulingAdminOp.CALENDAR_STATUS,
    schedulingParams(),
    SchedulingCalendarStatus.serializer(),
)

/**
 * Connect a CalDAV account.
 *
 * ⛔ [appPassword] IS A CREDENTIAL: it is sent once and kept nowhere, and nothing logs it.
 */
suspend fun SchedulingAdminRepository.connectCaldav(
    workspaceId: String,
    username: String,
    appPassword: String,
    preset: String? = null,
    serverUrl: String? = null,
): SchedulingAdminResult<SchedulingCaldavConnection> = perform(
    workspaceId,
    SchedulingAdminOp.CALENDAR_CALDAV_CONNECT,
    schedulingParams(
        "username" to JsonPrimitive(username),
        "app_password" to JsonPrimitive(appPassword),
        "preset" to preset.jsonOrNull(),
        "server_url" to serverUrl.jsonOrNull(),
    ),
    SchedulingCaldavConnection.serializer(),
)

suspend fun SchedulingAdminRepository.connectionCalendars(
    workspaceId: String,
    connectionId: String,
    provider: String,
    accountEmail: String? = null,
): SchedulingAdminResult<List<SchedulingCalendarSelection>> = perform(
    workspaceId,
    SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET,
    schedulingParams(
        "id" to JsonPrimitive(connectionId),
        "provider" to JsonPrimitive(provider),
        "account" to accountEmail.jsonOrNull(),
    ),
    SchedulingCalendarSelections.serializer(),
).map { it.calendars }

suspend fun SchedulingAdminRepository.setConnectionCalendars(
    workspaceId: String,
    connectionId: String,
    provider: String,
    calendars: List<SchedulingCalendarSelection>,
    accountEmail: String? = null,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT,
    schedulingParams(
        "id" to JsonPrimitive(connectionId),
        "provider" to JsonPrimitive(provider),
        "account_email" to accountEmail.jsonOrNull(),
        "calendars" to JsonArray(
            calendars.map { calendar ->
                schedulingParams(
                    "id" to JsonPrimitive(calendar.id),
                    "name" to JsonPrimitive(calendar.name),
                    "primary" to calendar.primary.jsonOrNull(),
                    "writable" to calendar.writable.jsonOrNull(),
                    "check_conflicts" to calendar.checkConflicts.jsonOrNull(),
                    "is_destination" to calendar.isDestination.jsonOrNull(),
                )
            },
        ),
    ),
    SchedulingNoContent.serializer(),
)

/** Make this connection the one new bookings are written to. */
suspend fun SchedulingAdminRepository.setDestinationConnection(
    workspaceId: String,
    connectionId: String,
    provider: String,
    accountEmail: String? = null,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.CALENDAR_CONNECTIONS_DESTINATION,
    schedulingParams(
        "id" to JsonPrimitive(connectionId),
        "provider" to JsonPrimitive(provider),
        "account_email" to accountEmail.jsonOrNull(),
    ),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.deleteCalendarConnection(
    workspaceId: String,
    connectionId: String,
    provider: String,
    accountEmail: String? = null,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.CALENDAR_CONNECTIONS_DELETE,
    schedulingParams(
        "id" to JsonPrimitive(connectionId),
        "provider" to JsonPrimitive(provider),
        "account" to accountEmail.jsonOrNull(),
    ),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.zoomStatus(
    workspaceId: String,
): SchedulingAdminResult<SchedulingZoomStatus> = perform(
    workspaceId,
    SchedulingAdminOp.ZOOM_STATUS,
    schedulingParams(),
    SchedulingZoomStatus.serializer(),
)
