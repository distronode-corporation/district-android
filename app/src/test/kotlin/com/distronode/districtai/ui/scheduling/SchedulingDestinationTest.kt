package com.distronode.districtai.ui.scheduling

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.NavHostHarness
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.Routes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The booking-page destination as the graph wires it: which URL each hand-off gives the browser,
 * and what the operator is told when there is no browser to give it to.
 *
 * ⚠️ THE URL IS MINTED BY THE ViewModel AND SPENT HERE, so only the graph can show that the two
 * hand-offs reach a browser at all; the screen test sees only that a button was tapped.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingDestinationTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    private val browserMissing: String =
        ApplicationProvider.getApplicationContext<Context>().getString(R.string.settings_browser_missing)

    @After
    fun tearDown() = harness.close()

    private fun openLiveBookingPage() {
        harness.api.schedulingApi.schedulingStatusResult = ApiResult.Success(
            SchedulingStatusResponse(
                eligible = true,
                canManage = true,
                tenant = SchedulingTenant(
                    status = "ready",
                    publicHost = "acme-book.distronode.com",
                    region = "us",
                    hasCredentials = true,
                    bookingUrl = "https://acme-book.distronode.com/book/phone-consultation",
                ),
            ),
        )
        harness.render()
        harness.navigate(Routes.scheduling("ws-1"))
    }

    @Test
    fun `the dashboard hand-off gives the browser the URL the server minted`() {
        harness.installBrowser()
        openLiveBookingPage()

        harness.tap(SCHEDULING_DASHBOARD_DESCRIPTION)

        assertEquals(
            listOf("https://www.distronode.com/dashboard/handoff?code=stub&next=%2Fdashboard"),
            harness.started.map { it.dataString },
        )
        assertTrue(harness.messages.isEmpty())
    }

    @Test
    fun `the scheduler hand-off with no browser at all says so rather than doing nothing`() {
        openLiveBookingPage()
        harness.startFailure = ActivityNotFoundException("no browser")

        harness.tap(SCHEDULING_OPEN_DESCRIPTION)

        // ⛔ ON THE SCREEN, NOT THROUGH `onShowMessage`. The launcher outlives a rotation, so it may
        // not hold the Activity's snackbar; see `MainActivitySchedulingHandOffTest`.
        composeRule.onNodeWithContentDescription(SCHEDULING_NOTICE_DESCRIPTION).assertExists()
        composeRule.onNodeWithText(browserMissing).assertExists()
        assertTrue(harness.messages.isEmpty())
        assertTrue(harness.started.isEmpty())
    }
}
