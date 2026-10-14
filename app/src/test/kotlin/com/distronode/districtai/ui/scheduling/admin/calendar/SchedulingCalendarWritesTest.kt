package com.distronode.districtai.ui.scheduling.admin.calendar

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminError
import com.distronode.districtai.core.data.SchedulingAdminFailureCode
import com.distronode.districtai.core.model.SchedulingCaldavConnection
import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.core.model.SchedulingCalendarSelections
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CalDAV and the calendar picker: what each sends, what each refuses before sending, and the
 * one-request rule, as district-ios's `SchedulingCaldavConnectModel` and
 * `SchedulingCalendarPickerModel` do them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingCalendarWritesTest {

    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope(StandardTestDispatcher())
    private var changed = 0
    private val writes = SchedulingCalendarWrites(scope, rig.adminRepository, "ws") { changed++ }

    private val caldavDialog get() = writes.dialog.value as CalendarDialog.Caldav
    private val pickerDialog get() = writes.dialog.value as CalendarDialog.Picker

    private val connection = calendarConnection("c1", "google", "ana@example.com")
    private val primary = SchedulingCalendarSelection("p", "Ana", primary = true, writable = true)
    private val holidays = SchedulingCalendarSelection("h", "Holidays", writable = false)
    private val team = SchedulingCalendarSelection("t", "Team")

    @Test
    fun `nothing open ignores every action`() {
        writes.caldav.edit { it.copy(username = "x") }
        writes.caldav.submit()
        writes.picker.load()
        writes.picker.setCheckConflicts("p", true)
        writes.picker.save()
        writes.close()
        assertNull(writes.dialog.value)
    }

    @Test
    fun `CalDAV refuses in the order the form is filled, then sends the preset and never a server`() {
        rig.admin.value(
            SchedulingAdminOp.CALENDAR_CALDAV_CONNECT,
            SchedulingCaldavConnection(true, "ana@icloud.example"),
        )
        writes.caldav.open()
        writes.caldav.submit()
        assertEquals(CaldavField.USERNAME, caldavDialog.form.erroredField)
        assertEquals(R.string.scheduling_admin_calendar_caldav_username_missing, caldavDialog.form.fieldMessage)
        writes.caldav.edit { it.copy(username = "  ana  ") }
        assertNull(caldavDialog.form.erroredField)
        writes.caldav.submit()
        assertEquals(CaldavField.APP_PASSWORD, caldavDialog.form.erroredField)
        writes.caldav.edit { it.copy(appPassword = " secret ") }
        writes.caldav.submit()
        writes.caldav.submit()
        assertEquals(WriteState.Working, caldavDialog.state)
        writes.caldav.edit { it.copy(username = "ignored while working") }
        writes.close()
        scope.advanceUntilIdle()

        val params = rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CALDAV_CONNECT).single().params
        assertEquals(JsonPrimitive("ana"), params["username"])
        // ⛔ The app password is sent exactly as typed, spaces included.
        assertEquals(JsonPrimitive(" secret "), params["app_password"])
        assertEquals(JsonPrimitive("icloud"), params["preset"])
        assertNull(params["server_url"])
        val done = caldavDialog.state as WriteState.Done
        val connected = UiText.Resource(R.string.scheduling_admin_calendar_caldav_connected_to, "ana@icloud.example")
        assertEquals(connected, done.message)
        // ⛔ Every box is emptied once connected, the password included.
        assertEquals(CaldavForm(), caldavDialog.form)
        assertEquals(1, changed)
    }

    @Test
    fun `a server address is required for Nextcloud and other servers, checked, and sent instead of a preset`() {
        rig.admin.value(SchedulingAdminOp.CALENDAR_CALDAV_CONNECT, SchedulingCaldavConnection(true, "ana@dav.example"))
        writes.caldav.open()
        writes.caldav.edit { it.copy(preset = CaldavPreset.NEXTCLOUD, username = "ana", appPassword = "pw") }
        writes.caldav.submit()
        assertEquals(R.string.scheduling_admin_calendar_caldav_server_url_missing, caldavDialog.form.fieldMessage)
        writes.caldav.edit { it.copy(serverUrl = "http://dav.example") }
        writes.caldav.submit()
        assertEquals(CaldavField.SERVER_URL, caldavDialog.form.erroredField)
        assertEquals(R.string.scheduling_admin_calendar_caldav_server_url_not_https, caldavDialog.form.fieldMessage)
        writes.caldav.edit { it.copy(serverUrl = " https://dav.example/remote.php/dav ") }
        writes.caldav.submit()
        scope.advanceUntilIdle()

        val params = rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CALDAV_CONNECT).single().params
        assertEquals(JsonPrimitive("https://dav.example/remote.php/dav"), params["server_url"])
        assertNull(params["preset"])
        assertEquals(CaldavPreset.NEXTCLOUD, caldavDialog.form.preset)
    }

    @Test
    fun `a refused credential names the three things to check, and editing clears the failure`() {
        rig.admin.refuse(SchedulingAdminOp.CALENDAR_CALDAV_CONNECT, "rejected")
        writes.caldav.open()
        writes.caldav.edit { it.copy(username = "ana", appPassword = "pw") }
        writes.caldav.submit()
        scope.advanceUntilIdle()
        val failed = caldavDialog.state as WriteState.Failed
        assertEquals(R.string.scheduling_admin_calendar_caldav_refused, failed.failure.message.resourceIdOrNull)
        assertFalse(failed.failure.retryable)
        // The password is kept after a refusal, so Try again resends what was typed.
        assertEquals("pw", caldavDialog.form.appPassword)
        assertEquals(0, changed)
        writes.caldav.edit { it.copy(appPassword = "pw2") }
        assertEquals(WriteState.Idle, caldavDialog.state)
        writes.caldav.edit { it.copy(appPassword = "pw3") }
        assertEquals(WriteState.Idle, caldavDialog.state)
    }

    @Test
    fun `only an unnamed refusal gets the CalDAV sentence`() {
        fun id(error: SchedulingAdminError) = CaldavConnectFlow.connectFailure(error).message.resourceIdOrNull
        assertEquals(R.string.scheduling_admin_calendar_caldav_refused, id(SchedulingAdminError.Unknown))
        assertEquals(R.string.scheduling_admin_calendar_caldav_refused, id(SchedulingAdminError.InvalidParams))
        assertEquals(R.string.scheduling_admin_failure_stale_build, id(SchedulingAdminError.Decoding))
        assertEquals(R.string.scheduling_admin_failure_forbidden, id(SchedulingAdminError.Forbidden))
        assertEquals(
            R.string.scheduling_admin_failure_unavailable,
            id(SchedulingAdminError.Refused(SchedulingAdminFailureCode.UNAVAILABLE)),
        )
    }

    @Test
    fun `a late answer lands only in the dialog that asked`() {
        rig.admin.value(SchedulingAdminOp.CALENDAR_CALDAV_CONNECT, SchedulingCaldavConnection(true, "a@b.example"))
        writes.caldav.open()
        writes.caldav.edit { it.copy(username = "ana", appPassword = "pw") }
        writes.caldav.submit()
        // A new opening (a different key) replaces it while the first is in flight.
        writes.caldav.open()
        scope.advanceUntilIdle()
        assertEquals(WriteState.Idle, caldavDialog.state)

        writes.caldav.edit { it.copy(username = "ana", appPassword = "pw") }
        writes.caldav.submit()
        writes.picker.open(connection)
        scope.advanceUntilIdle()
        assertTrue(writes.dialog.value is CalendarDialog.Picker)
        writes.close()
        assertNull(writes.dialog.value)
    }

    @Test
    fun `server addresses are refused by name, and a machine address is not a name`() {
        val issue = CaldavConnectFlow::serverUrlIssue
        assertEquals(R.string.scheduling_admin_calendar_caldav_server_url_not_a_url, issue("dav example"))
        assertEquals(R.string.scheduling_admin_calendar_caldav_server_url_not_a_url, issue("dav.example"))
        assertEquals(R.string.scheduling_admin_calendar_caldav_server_url_not_a_url, issue("https:dav"))
        assertEquals(R.string.scheduling_admin_calendar_caldav_server_url_not_https, issue("ftp://dav.example"))
        assertEquals(R.string.scheduling_admin_calendar_caldav_server_url_is_ip, issue("https://2130706433/"))
        assertNull(issue("HTTPS://dav.example"))
        listOf("[::1]", "localhost", "cal.localhost", "10.0.0.1", "0x7f000001").forEach {
            assertTrue(it, CaldavConnectFlow.isIpLiteralHost(it))
        }
        listOf("dav.example", "0x", "0xzz", "0x1.example").forEach {
            assertFalse(it, CaldavConnectFlow.isIpLiteralHost(it))
        }
    }

    @Test
    fun `the form never prints the password`() {
        val form = CaldavForm(username = "ana", appPassword = "hunter2")
        assertFalse(form.toString().contains("hunter2"))
        assertTrue(form.toString().contains("ana"))
        assertEquals(listOf("icloud", "fastmail", "nextcloud", "custom"), CaldavPreset.entries.map { it.wire })
    }

    @Test
    fun `the picker reads the whole list and sends the whole list back, then moves the destination`() {
        rig.admin.value(
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET,
            SchedulingCalendarSelections(listOf(primary, holidays, team)),
        )
        rig.admin.value(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT, SchedulingNoContent(true))
        rig.admin.value(SchedulingAdminOp.CALENDAR_CONNECTIONS_DESTINATION, SchedulingNoContent(true))
        writes.picker.open(connection)
        writes.picker.save()
        writes.picker.setCheckConflicts("p", true)
        assertEquals(SectionState.Loading, pickerDialog.rows)
        scope.advanceUntilIdle()

        assertEquals(listOf(primary, team), pickerDialog.destinationChoices)
        assertNull(pickerDialog.destinationId)
        writes.picker.setCheckConflicts("p", true)
        writes.picker.chooseDestination("t")
        assertEquals("t", pickerDialog.destinationId)
        writes.picker.save()
        writes.picker.save()
        writes.picker.load()
        writes.picker.chooseDestination("p")
        scope.advanceUntilIdle()

        val put = rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT).single().params
        assertEquals(JsonPrimitive("ana@example.com"), put["account_email"])
        val sent = (put["calendars"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("p", "h", "t"), sent.map { (it["id"] as JsonPrimitive).content })
        assertEquals(JsonPrimitive(true), sent[0]["check_conflicts"])
        // ⛔ A row nobody touched keeps its absent flags; the destination is set on every row.
        assertNull(sent[2]["check_conflicts"])
        val destinations = sent.map { (it["is_destination"] as JsonPrimitive).content.toBoolean() }
        assertEquals(listOf(false, false, true), destinations)
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CONNECTIONS_DESTINATION).size)
        assertEquals(
            UiText.Resource(R.string.scheduling_admin_calendar_calendars_saved),
            (pickerDialog.state as WriteState.Done).message,
        )
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET).size)
        assertEquals(1, changed)
    }

    @Test
    fun `no destination chosen sends only the list, and each write's failure is shown`() {
        rig.admin.value(
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET,
            SchedulingCalendarSelections(listOf(team)),
        )
        rig.admin.value(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT, SchedulingNoContent(true))
        writes.picker.open(connection)
        scope.advanceUntilIdle()
        writes.picker.save()
        scope.advanceUntilIdle()
        assertTrue(pickerDialog.state is WriteState.Done)
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CONNECTIONS_DESTINATION).isEmpty())

        writes.picker.chooseDestination("t")
        assertTrue(pickerDialog.state is WriteState.Done)
        writes.picker.save()
        scope.advanceUntilIdle()
        assertEquals(
            R.string.scheduling_admin_failure_offline,
            (pickerDialog.state as WriteState.Failed).failure.message.resourceIdOrNull,
        )
        writes.picker.setCheckConflicts("t", false)
        assertEquals(WriteState.Idle, pickerDialog.state)

        rig.admin.clear(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT)
        rig.admin.refuse(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT, "unavailable")
        writes.picker.save()
        scope.advanceUntilIdle()
        assertEquals(
            R.string.scheduling_admin_failure_unavailable,
            (pickerDialog.state as WriteState.Failed).failure.message.resourceIdOrNull,
        )
        assertEquals(1, changed)
    }

    @Test
    fun `a failed calendar read is a failure, never an empty account, and editing waits for a read`() {
        val offline = ApiResult.NetworkFailure(IOException("x"))
        rig.admin.fail(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET, offline)
        writes.picker.open(connection)
        scope.advanceUntilIdle()
        assertTrue(pickerDialog.rows is SectionState.Failed)
        assertEquals(emptyList<SchedulingCalendarSelection>(), pickerDialog.destinationChoices)
        writes.picker.chooseDestination("t")
        writes.picker.save()
        assertTrue(pickerDialog.rows is SectionState.Failed)
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT).isEmpty())
    }
}
