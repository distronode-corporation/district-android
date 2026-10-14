package com.distronode.districtai.ui.scheduling.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.AppContainer
import com.distronode.districtai.AppContainerSeams
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.SchedulingBookingPage
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.Routes
import com.distronode.districtai.ui.scheduling.SCHEDULING_ROOT_DESCRIPTION
import com.distronode.districtai.ui.scheduling.SCHEDULING_SECTIONS_DESCRIPTION
import com.distronode.districtai.ui.scheduling.SCHEDULING_SECTION_DESCRIPTION
import com.distronode.districtai.ui.scheduling.admin.bookings.SCHEDULING_BOOKINGS_ROOT_TAG
import com.distronode.districtai.ui.scheduling.admin.bookings.SchedulingBookingDetailRoute
import com.distronode.districtai.ui.scheduling.admin.bookings.SCHEDULING_BOOKING_CANCEL_DIALOG_TAG
import com.distronode.districtai.ui.scheduling.admin.bookings.SCHEDULING_BOOKING_DETAIL_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.bookings.SCHEDULING_BOOKINGS_ROW_TAG
import com.distronode.districtai.ui.scheduling.admin.bookings.SCHEDULING_BOOKING_DETAIL_ROOT_TAG
import com.distronode.districtai.ui.scheduling.admin.overview.SCHEDULING_OVERVIEW_ROOT_TAG
import com.distronode.districtai.ui.scheduling.schedulingDestination
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The booking-page screen's Manage list into each native section and back, through the real
 * navigation graph and the app's container.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingAdminNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val rig = SchedulingAdminTestRig()

    private lateinit var navController: NavHostController

    private fun route(section: SchedulingSection) = SchedulingAdminRoutes.section("ws-1", section)

    private fun show() {
        rig.admin.value(SchedulingAdminOp.BOOKINGS_LIST, SchedulingBookingPage(items = listOf(schedulingBooking())))
        val container = AppContainer(
            ApplicationProvider.getApplicationContext(),
            seams = AppContainerSeams(
                pushTokenSource = { null },
                districtApi = rig.district,
                schedulingAdminApi = rig.admin,
            ),
        )
        composeRule.setContent {
            navController = rememberNavController()
            val context = LocalContext.current
            DistrictTheme {
                NavHost(navController, startDestination = Routes.scheduling("ws-1")) {
                    schedulingDestination(container, navController, sessionEpoch = 0, context = context)
                }
            }
        }
    }

    @Test
    fun `a live tenancy lists every section and each one opens and goes back`() {
        show()
        composeRule.waitUntil {
            composeRule.onAllNodes(hasTestTag(SCHEDULING_SECTIONS_DESCRIPTION)).fetchSemanticsNodes().isNotEmpty()
        }
        SchedulingSection.entries.forEach { section ->
            composeRule.onNodeWithTag("$SCHEDULING_SECTION_DESCRIPTION-${section.pathSegment}").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).performClick()
            composeRule.onNodeWithTag(SCHEDULING_ROOT_DESCRIPTION).assertExists()
        }
    }

    @Test
    fun `bookings open one booking, and an unknown section or an event type route draws`() {
        show()
        composeRule.runOnIdle { navController.navigate(route(SchedulingSection.BOOKINGS)) }
        composeRule.onNodeWithTag(SCHEDULING_BOOKINGS_ROOT_TAG).assertExists()
        composeRule.waitUntil {
            composeRule.onAllNodes(hasTestTag("$SCHEDULING_BOOKINGS_ROW_TAG-b1")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_ROW_TAG-b1").performClick()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_DETAIL_ROOT_TAG).assertExists()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_ANSWERS).size)

        composeRule.runOnIdle { navController.navigate(route(SchedulingSection.OVERVIEW)) }
        composeRule.onNodeWithTag(SCHEDULING_OVERVIEW_ROOT_TAG).assertExists()

        composeRule.runOnIdle { navController.navigate(SchedulingAdminRoutes.eventType("ws-1", "intro")) }
        composeRule.onNodeWithTag(SCHEDULING_PENDING_SECTION_TAG).assertExists()

        composeRule.runOnIdle { navController.navigate("workspace/ws-1/scheduling/section/nope") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SCHEDULING_PENDING_SECTION_TAG).assertExists()
    }

    @Test
    fun `a new session re-reads the overview, the bookings and one booking`() {
        rig.admin.value(SchedulingAdminOp.BOOKINGS_LIST, SchedulingBookingPage(items = emptyList()))
        var epoch by mutableIntStateOf(0)
        composeRule.setContent {
            val deps = SchedulingAdminDeps(rig.adminRepository, rig.scheduling, "ws-1", epoch, {}, {}, { true })
            DistrictTheme {
                Column {
                    SchedulingSectionRoute(SchedulingSection.OVERVIEW, deps)
                    SchedulingSectionRoute(SchedulingSection.BOOKINGS, deps)
                    SchedulingBookingDetailRoute(deps, "b1")
                }
            }
        }
        composeRule.waitForIdle()
        val before = listOf(SchedulingAdminOp.EVENT_TYPES_LIST, SchedulingAdminOp.BOOKINGS_ANSWERS).map {
            rig.admin.callsTo(it).size
        }
        epoch++
        composeRule.waitForIdle()
        val after = listOf(SchedulingAdminOp.EVENT_TYPES_LIST, SchedulingAdminOp.BOOKINGS_ANSWERS).map {
            rig.admin.callsTo(it).size
        }
        // Overview and bookings each read event types again; the booking reads its answers again.
        assertEquals(listOf(before[0] + 2, before[1] + 1), after)
        // The booking's own Cancel opens its dialog, which Back-equivalent Keep closes again.
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_DETAIL_CANCEL_TAG).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_CANCEL_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_CANCEL_DIALOG_TAG).assertDoesNotExist()
    }
}
