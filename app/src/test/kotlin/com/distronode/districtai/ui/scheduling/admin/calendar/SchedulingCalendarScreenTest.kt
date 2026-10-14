package com.distronode.districtai.ui.scheduling.admin.calendar

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.SchedulingCaldavConnection
import com.distronode.districtai.core.model.SchedulingCalendarConnection
import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.core.model.SchedulingCalendarSelections
import com.distronode.districtai.core.model.SchedulingCalendarStatus
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingZoomStatus
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.RemovalState
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_ADMIN_REFRESH_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_DISMISS_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_REJECTION_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_RETRY_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The calendar screen and its three dialogs, as a member sees them. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingCalendarScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingCalendarActions()

    private val google = calendarConnection("c1", "google", "ana@example.com", isDestination = true)
    private val caldav = calendarConnection("c2", "caldav", "ana@dav.example", checkConflicts = false)
    private val microsoft = calendarConnection("c3", "microsoft", "ana@corp.example")
    private val apple = calendarConnection("c4", "apple", "ana@icloud.example", checkConflicts = false)
    private val work = SchedulingCalendarSelection("w", "Work", checkConflicts = true)
    private val home = SchedulingCalendarSelection("h", "Home", checkConflicts = true, isDestination = true)

    private fun ready(
        status: SchedulingCalendarStatus,
        transform: (SchedulingCalendarUiState) -> SchedulingCalendarUiState = { it },
    ) = transform(SchedulingCalendarUiState(status = SectionState.Ready(status)))

    @Test
    fun `each account says what is checked and what receives bookings, with its own controls`() {
        val state = ready(calendarStatus(listOf(google, caldav, microsoft))) {
            it.copy(
                zoom = SchedulingZoomStatus(configured = true, connected = true),
                calendars = mapOf("c2" to emptyList(), "c3" to listOf(work, home)),
                connectFailure = FailureText(UiText.Literal("No browser")),
            )
        }
        val removal = RemovalState(failure = FailureText(UiText.Literal("Disconnect failed")), removed = google)
        composeRule.setContent { DistrictTheme { SchedulingCalendarScreen(state, removal, actions) } }

        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_conflicts_checked)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_destination_this_account)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_conflicts_none)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_destination_none)).assertExists()
        composeRule.onNodeWithText("Work, Home").assertExists()
        composeRule.onNodeWithText("Home").assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_connect_another)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_connected)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_disconnected_last_destination))
            .assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_connect_provider, "Microsoft 365"))
            .assertExists()

        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_CONNECT_TAG-google").performClick()
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_CALDAV_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_CHOOSE_TAG-c1").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_DISCONNECT_TAG-c1").performClick()
        composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        val dismiss = composeRule.onAllNodes(hasTestTag(SCHEDULING_WRITE_DISMISS_TAG))
        dismiss[0].performClick()
        dismiss[1].performClick()
        assertEquals(
            listOf(
                "connect:google",
                "caldav",
                "choose:c1",
                "disconnect:c1",
                "back",
                "refresh",
                "dismissConnect",
                "dismissDisconnect",
            ),
            actions.calls,
        )
    }

    @Test
    fun `an unconfigured scheduler says so once, offers CalDAV only, and a busy remove disables Disconnect`() {
        composeRule.setContent {
            DistrictTheme {
                SchedulingCalendarScreen(
                    ready(calendarStatus(listOf(apple), configured = false)) {
                        it.copy(zoom = SchedulingZoomStatus(configured = false, connected = false))
                    },
                    RemovalState(busy = true, removed = caldav),
                    actions,
                )
            }
        }
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_not_configured)).assertExists()
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_CONNECT_TAG-google").assertDoesNotExist()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_provider_zoom)).assertDoesNotExist()
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_CALDAV_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_DISCONNECT_TAG-c4").assertIsNotEnabled()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_conflicts_not_checked)).assertExists()
        // A removed account that was not the destination leaves nothing to warn about.
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_disconnected_last_destination))
            .assertDoesNotExist()
    }

    @Test
    fun `no providers configured, nothing connected, a Connect in flight, loading and a failed read`() {
        composeRule.setContent {
            DistrictTheme {
                SchedulingCalendarScreen(
                    ready(calendarStatus(unconfigured = listOf("google", "microsoft"))) {
                        it.copy(zoom = SchedulingZoomStatus(configured = true, connected = false))
                    },
                    RemovalState(),
                    actions,
                )
                SchedulingCalendarScreen(
                    ready(calendarStatus()) { it.copy(connecting = true) },
                    RemovalState(),
                    actions,
                )
                SchedulingCalendarScreen(SchedulingCalendarUiState(), RemovalState(), actions)
                SchedulingCalendarScreen(
                    SchedulingCalendarUiState(status = SectionState.Failed(FailureText(UiText.Literal("Down")))),
                    RemovalState(),
                    actions,
                )
            }
        }
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_not_configured)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_not_connected)).assertExists()
        val noneConnected = str(R.string.scheduling_admin_calendar_none_connected)
        composeRule.onAllNodes(hasText(noneConnected)).assertCountEquals(2)
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_CONNECT_TAG-microsoft").assertIsNotEnabled()
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_LOADING_TAG).assertExists()
        composeRule.onNodeWithText("Down").assertExists()
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()
        assertEquals(listOf("refresh"), actions.calls)
    }

    @Test
    fun `the CalDAV dialog switches to a server address, refuses a bad one, and confirms the account`() {
        val rig = SchedulingAdminTestRig()
        rig.admin.value(
            SchedulingAdminOp.CALENDAR_CALDAV_CONNECT,
            SchedulingCaldavConnection(true, "ana@dav.example"),
        )
        val scope = TestScope(StandardTestDispatcher())
        val writes = SchedulingCalendarWrites(scope, rig.adminRepository, "ws") {}
        writes.caldav.open()
        composeRule.setContent {
            val dialog by writes.dialog.collectAsState()
            DistrictTheme { SchedulingCalendarDialogs(dialog, writes) }
        }
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_SERVER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_CONFIRM_TAG).performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_caldav_username_missing))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$SCHEDULING_CALDAV_PRESET_TAG-CUSTOM").performClick()
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_SERVER_TAG).performTextInput("http://dav.example")
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_USERNAME_TAG).performTextInput("ana")
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_PASSWORD_TAG).performTextInput("pw")
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_CONFIRM_TAG).performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_caldav_server_url_not_https))
            .assertIsDisplayed()
        writes.caldav.edit { it.copy(serverUrl = "https://dav.example") }
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_USERNAME_TAG).assertIsNotEnabled()
        scope.advanceUntilIdle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_caldav_connected_to, "ana@dav.example"))
            .assertIsDisplayed()

        writes.caldav.open()
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_USERNAME_TAG).performTextInput("ana")
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_REJECTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_DIALOG_TAG).assertDoesNotExist()

        // Offline is worth retrying, and Try again resends the form as typed.
        rig.admin.clear(SchedulingAdminOp.CALENDAR_CALDAV_CONNECT)
        rig.admin.fail(SchedulingAdminOp.CALENDAR_CALDAV_CONNECT, ApiResult.NetworkFailure(IOException("x")))
        writes.caldav.open()
        writes.caldav.edit { it.copy(username = "ana", appPassword = "pw") }
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_CONFIRM_TAG).performClick()
        scope.advanceUntilIdle()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).performClick()
        scope.advanceUntilIdle()
        assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CALDAV_CONNECT).size)
    }

    @Test
    fun `the picker toggles conflicts, moves the destination and saves the whole list`() {
        val rig = SchedulingAdminTestRig()
        rig.admin.value(
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET,
            SchedulingCalendarSelections(listOf(work, home)),
        )
        rig.admin.value(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT, SchedulingNoContent(true))
        rig.admin.value(SchedulingAdminOp.CALENDAR_CONNECTIONS_DESTINATION, SchedulingNoContent(true))
        val scope = TestScope(StandardTestDispatcher())
        val writes = SchedulingCalendarWrites(scope, rig.adminRepository, "ws") {}
        writes.picker.open(google)
        composeRule.setContent {
            val dialog by writes.dialog.collectAsState()
            DistrictTheme { SchedulingCalendarDialogs(dialog, writes) }
        }
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_PICKER_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_PICKER_CONFIRM_TAG).assertIsNotEnabled()
        scope.advanceUntilIdle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_calendars_title, "ana@example.com"))
            .assertExists()
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_PICKER_CONFLICT_TAG-w").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_PICKER_DESTINATION_TAG-w").performClick()
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_PICKER_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_PICKER_CONFLICT_TAG-w").assertIsNotEnabled()
        scope.advanceUntilIdle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_calendars_saved)).assertIsDisplayed()
        val rows = (writes.dialog.value as CalendarDialog.Picker).rows as SectionState.Ready
        assertEquals(false, rows.value.first().checkConflicts)

        rig.admin.clear(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET)
        writes.picker.open(google)
        scope.advanceUntilIdle()
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_PICKER_FAILURE_TAG).assertExists()
        val justWork = SchedulingCalendarSelections(listOf(work))
        rig.admin.value(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET, justWork)
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()
        scope.advanceUntilIdle()
        rig.admin.clear(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT)
        rig.admin.fail(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT, ApiResult.NetworkFailure(IOException("x")))
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_PICKER_CONFIRM_TAG).performClick()
        scope.advanceUntilIdle()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).performClick()
        scope.advanceUntilIdle()
        assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT).size)
    }

    @Test
    fun `the disconnect question names the account and keeps it on a mis-tap`() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            DistrictTheme { SchedulingDisconnectDialog(google, { calls += "confirm" }, { calls += "keep" }) }
        }
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_disconnect_prompt, "ana@example.com"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_calendar_disconnect_keep)).performClick()
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_DISCONNECT_CONFIRM_TAG).performClick()
        assertEquals(listOf("keep", "confirm"), calls)
    }
}

/** Records what the calendar screen asked for. */
class RecordingCalendarActions : SchedulingCalendarActions {
    val calls = mutableListOf<String>()
    override fun back() { calls += "back" }
    override fun refresh() { calls += "refresh" }
    override fun connect(provider: String) { calls += "connect:$provider" }
    override fun dismissConnectFailure() { calls += "dismissConnect" }
    override fun caldav() { calls += "caldav" }
    override fun choose(connection: SchedulingCalendarConnection) { calls += "choose:${connection.id}" }
    override fun disconnect(connection: SchedulingCalendarConnection) { calls += "disconnect:${connection.id}" }
    override fun dismissDisconnectFailure() { calls += "dismissDisconnect" }
}
