package com.distronode.districtai.ui.scheduling.admin.calendar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_ADMIN_REFRESH_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_DISMISS_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The calendar route against its real ViewModel: every control reaches the right write, and coming
 * back from the browser re-reads.
 *
 * ⚠️ AN UNCONFINED MAIN DISPATCHER, so every fake answer lands before the next line rather than on
 * a coroutine a compose wait has to chase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingCalendarRouteTest {

    @get:Rule(order = 0)
    val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val rig = SchedulingAdminTestRig()
    private val owner = TestLifecycleOwner()
    private val calls = mutableListOf<String>()
    private val connection = calendarConnection("c1", "google", "a@b.example")
    private var browserTakesIt = false

    private var epoch by mutableIntStateOf(0)

    private fun deps(sessionEpoch: Int) = SchedulingAdminDeps(
        admin = rig.adminRepository,
        scheduling = rig.scheduling,
        workspaceId = "ws",
        sessionEpoch = sessionEpoch,
        onBack = { calls += "back" },
        navigate = { calls += "navigate:$it" },
        openUrl = {
            calls += "open"
            browserTakesIt
        },
        members = MembersRepository(rig.district),
    )

    private fun show() {
        owner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                DistrictTheme { SchedulingCalendarRoute(deps(epoch)) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun statusReads() = rig.admin.callsTo(SchedulingAdminOp.CALENDAR_STATUS).size

    @Test
    fun `Connect opens the browser, and coming back re-reads`() {
        rig.admin.value(SchedulingAdminOp.CALENDAR_STATUS, calendarStatus(listOf(connection)))
        show()
        assertEquals(1, statusReads())

        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_CONNECT_TAG-google").performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_DISMISS_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_DISMISS_TAG).assertDoesNotExist()

        browserTakesIt = true
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_CONNECT_TAG-google").performClick()
        composeRule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()
        assertEquals(2, statusReads())
        assertEquals(listOf("open", "open"), calls)

        composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        assertEquals(3, statusReads())
        assertEquals("back", calls.last())
        // A new session re-reads.
        epoch = 1
        composeRule.waitForIdle()
        assertEquals(4, statusReads())
    }

    @Test
    fun `CalDAV, the picker and Disconnect each open their own dialog`() {
        rig.admin.value(SchedulingAdminOp.CALENDAR_STATUS, calendarStatus(listOf(connection)))
        rig.admin.refuse(SchedulingAdminOp.CALENDAR_CONNECTIONS_DELETE, "unavailable")
        show()

        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_CALDAV_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_CALDAV_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_CHOOSE_TAG-c1").performClick()
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_PICKER_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()

        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_DISCONNECT_TAG-c1").performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_DISCONNECT_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_CALENDAR_DISCONNECT_TAG-c1").performClick()
        composeRule.onNodeWithTag(SCHEDULING_CALENDAR_DISCONNECT_CONFIRM_TAG).performClick()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.CALENDAR_CONNECTIONS_DELETE).size)
        composeRule.onNodeWithTag(SCHEDULING_WRITE_DISMISS_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_DISMISS_TAG).assertDoesNotExist()
    }
}

/** A lifecycle a test moves by hand. */
class TestLifecycleOwner : LifecycleOwner {
    val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
}
