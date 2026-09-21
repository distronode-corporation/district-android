package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingCaldavConnection
import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.core.model.SchedulingCalendarSelections
import com.distronode.districtai.core.model.SchedulingCalendarStatus
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingZoomStatus
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.json.JsonObject

/*
 * The six `calendar.*` ops plus `zoom.status`, typed.
 *
 * ⚠️ THIS WHOLE NAMESPACE IS `viewer`, INCLUDING ITS FOUR WRITES, and that is not an oversight in
 * the catalog's role table: these are the CALLER'S OWN calendar connections, so a viewer
 * connecting their own Google account is not a tenancy change. A viewer who cannot connect a
 * calendar cannot be booked at all, which is the product breaking for exactly the role the narrow
 * rule was meant to protect.
 *
 * ⚠️ AND THEY ARE STILL WRITES FOR BUDGETING. They spend the 30-per-member-per-hour bucket rather
 * than the workspace's 120 — see `SchedulingAdminOp.isWrite`, which disagrees with `minRole` on
 * exactly these plus the two `me.*` writes.
 *
 * ⛔ `account` VERSUS `account_email` IS A REAL DIFFERENCE AND NOT A TYPO. The two GET-shaped ops
 * (`calendars.get`, `connections.delete`) name the param `account`; the two write-shaped ones
 * (`calendars.put`, `destination`) name it `account_email`. Copied from `admin-ops.ts` rather than
 * normalised — "tidying" either one is a 400 naming a required field.
 */

suspend fun SchedulingAdminRepository.calendarStatus(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingCalendarStatus> = perform(
    SchedulingAdminOp.CALENDAR_STATUS,
    workspaceId,
    schedulingParams(),
    SchedulingCalendarStatus.serializer(),
)

/**
 * ⛔ [appPassword] IS A LIVE CREDENTIAL AND MUST NOT BE LOGGED, CACHED OR RETAINED. It crosses the
 * wire once, inside `params`, and the answer carries only a flag and the address it resolved to.
 */
suspend fun SchedulingAdminRepository.connectCaldav(
    workspaceId: String,
    username: String,
    appPassword: String,
    preset: String? = null,
    serverUrl: String? = null,
): SchedulingAdminOutcome<SchedulingCaldavConnection> = perform(
    SchedulingAdminOp.CALENDAR_CALDAV_CONNECT,
    workspaceId,
    schedulingParams(
        "username" to textParam(username),
        "app_password" to textParam(appPassword),
        "preset" to textParam(preset),
        "server_url" to textParam(serverUrl),
    ),
    SchedulingCaldavConnection.serializer(),
)

suspend fun SchedulingAdminRepository.connectionCalendars(
    workspaceId: String,
    connectionId: String,
    provider: String,
    accountEmail: String? = null,
): SchedulingAdminOutcome<List<SchedulingCalendarSelection>> = perform(
    SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET,
    workspaceId,
    schedulingParams(
        "id" to textParam(connectionId),
        "provider" to textParam(provider),
        "account" to textParam(accountEmail),
    ),
    SchedulingCalendarSelections.serializer(),
).map { it.calendars }

/**
 * ⛔ A PUT, SO THE LIST IS THE WHOLE SELECTION. A calendar absent from [calendars] stops being
 * consulted; sending one entry is not "also watch this one".
 */
suspend fun SchedulingAdminRepository.setConnectionCalendars(
    workspaceId: String,
    connectionId: String,
    provider: String,
    calendars: List<SchedulingCalendarSelection>,
    accountEmail: String? = null,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT,
    workspaceId,
    schedulingParams(
        "id" to textParam(connectionId),
        "provider" to textParam(provider),
        "account_email" to textParam(accountEmail),
        "calendars" to objectsParam(calendars.map(::calendarSelectionParams)),
    ),
    SchedulingNoContent.serializer(),
)

/**
 * ⚠️ EXACTLY ONE CONNECTION MAY BE THE DESTINATION, so this op MOVES it rather than adding one.
 * The previous destination stops receiving new events with no second call.
 */
suspend fun SchedulingAdminRepository.setDestinationConnection(
    workspaceId: String,
    connectionId: String,
    provider: String,
    accountEmail: String? = null,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.CALENDAR_CONNECTIONS_DESTINATION,
    workspaceId,
    schedulingParams(
        "id" to textParam(connectionId),
        "provider" to textParam(provider),
        "account_email" to textParam(accountEmail),
    ),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.deleteCalendarConnection(
    workspaceId: String,
    connectionId: String,
    provider: String,
    accountEmail: String? = null,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.CALENDAR_CONNECTIONS_DELETE,
    workspaceId,
    schedulingParams(
        "id" to textParam(connectionId),
        "provider" to textParam(provider),
        "account" to textParam(accountEmail),
    ),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.zoomStatus(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingZoomStatus> = perform(
    SchedulingAdminOp.ZOOM_STATUS,
    workspaceId,
    schedulingParams(),
    SchedulingZoomStatus.serializer(),
)

/**
 * ⚠️ THE FOUR FLAGS ARE OMITTED WHEN NULL, AND `false` IS NOT NULL. "Do not check this calendar
 * for conflicts" and "say nothing about this calendar" are different instructions to the fork, and
 * a selection read back from [connectionCalendars] carries nulls for a calendar the caller has
 * never configured.
 */
private fun calendarSelectionParams(selection: SchedulingCalendarSelection): JsonObject =
    schedulingParams(
        "id" to textParam(selection.id),
        "name" to textParam(selection.name),
        "primary" to boolParam(selection.primary),
        "writable" to boolParam(selection.writable),
        "check_conflicts" to boolParam(selection.checkConflicts),
        "is_destination" to boolParam(selection.isDestination),
    )
