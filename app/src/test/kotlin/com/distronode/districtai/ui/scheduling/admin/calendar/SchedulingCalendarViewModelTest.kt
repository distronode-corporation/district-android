package com.distronode.districtai.ui.scheduling.admin.calendar

import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingCalendarConnection
import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.core.model.SchedulingCalendarSelections
import com.distronode.districtai.core.model.SchedulingCalendarStatus
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingZoomStatus
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.FakeSchedulingAnswer
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The calendar section's reads, the Connect hand-off and the disconnect. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingCalendarViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val google = calendarConnection("c1", "google", "ana@example.com", isDestination = true)
    private val caldav = calendarConnection("c2", "caldav", "ana@fastmail.example")

    private fun rig(vararg connections: SchedulingCalendarConnection) = SchedulingAdminTestRig().apply {
        admin.value(SchedulingAdminOp.CALENDAR_STATUS, calendarStatus(connections.toList()))
    }

    private fun model(rig: SchedulingAdminTestRig) =
        SchedulingCalendarViewModel(rig.adminRepository, rig.scheduling, "ws")

    @Test
    fun `the status and Zoom are read together, then each connection's calendars, each failing alone`() =
        runTest(dispatcher) {
            val rig = rig(google, caldav)
            val zoom = SchedulingZoomStatus(configured = true, connected = false)
            rig.admin.value(SchedulingAdminOp.ZOOM_STATUS, zoom)
            val rows = listOf(SchedulingCalendarSelection("primary", "Ana", checkConflicts = true))
            rig.admin.answer(
                SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET,
                FakeSchedulingAnswer.Value(SchedulingCalendarSelections(rows)),
                FakeSchedulingAnswer.Failed(ApiResult.NetworkFailure(IOException("x"))),
            )
            val viewModel = model(rig)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SchedulingZoomStatus(configured = true, connected = false), state.zoom)
            assertEquals(2, (state.status as SectionState.Ready).value.connections.size)
            // ⛔ Only the read that worked is recorded: the failed one is ABSENT, not empty.
            assertEquals(mapOf("c1" to rows), state.calendars)
            val first = rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET).first().params
            assertEquals(JsonPrimitive("c1"), first["id"])
            assertEquals(JsonPrimitive("google"), first["provider"])
            assertEquals(JsonPrimitive("ana@example.com"), first["account"])
        }

    @Test
    fun `a failed status is a failure, and a failed Zoom read is silent`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig()
        val viewModel = model(rig)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.status is SectionState.Failed)
        assertNull(viewModel.state.value.zoom)

        rig.admin.value(SchedulingAdminOp.CALENDAR_STATUS, calendarStatus(emptyList()))
        viewModel.load()
        assertEquals(SectionState.Loading, viewModel.state.value.status)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.status is SectionState.Ready)
        assertNull(viewModel.state.value.zoom)
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET).isEmpty())
    }

    @Test
    fun `Connect mints one hand-off per press, opens it, and re-reads only on the way back`() = runTest(dispatcher) {
        val rig = rig()
        val opened = mutableListOf<String>()
        val viewModel = model(rig)
        advanceUntilIdle()
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.CALENDAR_STATUS).size)

        val launch: (String) -> Boolean = {
            opened += it
            true
        }
        viewModel.connect("google", launch)
        viewModel.connect("google", launch)
        assertTrue(viewModel.state.value.connecting)
        advanceUntilIdle()
        assertEquals(listOf("https://acme-book.distronode.com/v1/auth/sso?token=stub"), opened)
        val next = rig.district.schedulingApi.schedulingSsoRequests.single()
        assertTrue(next.startsWith("ws|/v1/calendar/connect?provider=google&return_to=https%3A%2F%2F"))
        assertFalse(viewModel.state.value.connecting)
        assertNull(viewModel.state.value.connectFailure)

        viewModel.onResume()
        advanceUntilIdle()
        viewModel.onResume()
        advanceUntilIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.CALENDAR_STATUS).size)
    }

    @Test
    fun `no browser, a tenancy still being set up, and any other refusal each say so`() = runTest(dispatcher) {
        val rig = rig()
        val viewModel = model(rig)
        advanceUntilIdle()

        viewModel.connect("microsoft") { false }
        advanceUntilIdle()
        val missing = viewModel.state.value.connectFailure?.message?.resourceIdOrNull
        assertEquals(R.string.settings_browser_missing, missing)
        viewModel.dismissConnectFailure()
        assertNull(viewModel.state.value.connectFailure)

        rig.district.schedulingApi.schedulingSsoResult = ApiResult.HttpFailure(409, "not ready")
        viewModel.connect("microsoft") { true }
        advanceUntilIdle()
        assertEquals(
            R.string.scheduling_admin_calendar_connect_not_ready_yet,
            viewModel.state.value.connectFailure?.message?.resourceIdOrNull,
        )

        rig.district.schedulingApi.schedulingSsoResult = ApiResult.NetworkFailure(IOException("x"))
        viewModel.connect("microsoft") { true }
        advanceUntilIdle()
        assertEquals(R.string.failure_offline, viewModel.state.value.connectFailure?.message?.resourceIdOrNull)
        assertEquals(
            R.string.failure_server,
            SchedulingCalendarViewModel.handOffFailure(ApiResult.HttpFailure(500, "x")).message.resourceIdOrNull,
        )
    }

    @Test
    fun `Disconnect sends the provider and the account with the id, then re-reads`() = runTest(dispatcher) {
        val rig = rig(google)
        rig.admin.value(SchedulingAdminOp.CALENDAR_CONNECTIONS_DELETE, SchedulingNoContent(ok = true))
        val viewModel = model(rig)
        advanceUntilIdle()

        viewModel.disconnect.ask(google)
        viewModel.disconnect.confirm()
        advanceUntilIdle()

        val params = rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CONNECTIONS_DELETE).single().params
        assertEquals(JsonPrimitive("c1"), params["id"])
        assertEquals(JsonPrimitive("google"), params["provider"])
        assertEquals(JsonPrimitive("ana@example.com"), params["account"])
        assertEquals(google, viewModel.disconnect.state.value.removed)
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.CALENDAR_STATUS).size)
    }

    @Test
    fun `Connect is offered only for configured OAuth providers`() {
        val unconfigured = calendarStatus(configured = false)
        assertEquals(emptyList<String>(), SchedulingCalendarViewModel.offeredProviders(unconfigured))
        assertEquals(listOf("google", "microsoft"), SchedulingCalendarViewModel.offeredProviders(calendarStatus()))
        assertEquals(
            listOf("google"),
            SchedulingCalendarViewModel.offeredProviders(calendarStatus(unconfigured = listOf("microsoft"))),
        )
    }
}

/** A calendar connection shaped like the contract fixture's. */
fun calendarConnection(
    id: String,
    provider: String,
    account: String,
    isDestination: Boolean = false,
    checkConflicts: Boolean = true,
) = SchedulingCalendarConnection(
    id = id,
    provider = provider,
    accountEmail = account,
    isDestination = isDestination,
    checkConflicts = checkConflicts,
)

/** A `calendar.status` answer. */
fun calendarStatus(
    connections: List<SchedulingCalendarConnection> = emptyList(),
    configured: Boolean = true,
    unconfigured: List<String>? = null,
) = SchedulingCalendarStatus(
    connected = connections.isNotEmpty(),
    configured = configured,
    connections = connections,
    unconfiguredProviders = unconfigured,
)
