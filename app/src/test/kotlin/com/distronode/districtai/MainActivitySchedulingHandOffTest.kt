package com.distronode.districtai

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.scheduling.SCHEDULING_DASHBOARD_DESCRIPTION
import com.distronode.districtai.ui.scrolledIntoView
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The scheduling hand-off across a configuration change, through the real activity.
 *
 * ⛔ THE MINT OUTLIVES THE ACTIVITY THAT ASKED FOR IT. The ViewModel is scoped to the nav entry and
 * survives a rotation; the Activity does not. Whatever the ViewModel holds while the request is in
 * flight must therefore reach the Activity that exists when the answer lands, never the one that
 * pressed the button. These tests rotate mid-mint and check the answer reaches the new one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], application = ShellTestApplication::class)
class MainActivitySchedulingHandOffTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(compose)

    private val harness = ShellHarness(compose)

    @After
    fun tearDown() = harness.close()

    @Test
    fun `no browser after a rotation mid-mint is still reported, on the new activity`() {
        // ⛔ THE OLD CALLBACK CAPTURED THE PRESSING ACTIVITY through `onShowMessage`, whose snackbar
        // is launched on that Activity's `lifecycleScope`. Rotated mid-mint, the answer landed on a
        // destroyed Activity, the cancelled scope dropped the message, and the user was told
        // nothing: the button simply did nothing. The Activity was also held for the whole mint.
        harness.signedIn()
        harness.app.api.schedulingApi.schedulingStatusResult = ApiResult.Success(LIVE)
        val gate = CompletableDeferred<Unit>()
        harness.app.api.schedulingApi.schedulingHandOffGate = gate
        harness.launch(harness.viewIntent("https://www.distronode.com/dashboard/district/scheduling"))
        harness.awaitDescription(SCHEDULING_DASHBOARD_DESCRIPTION)
        // Every start throws from here on, as it does on a device with no browser. ⚠️ Not before
        // the launch, which starts activities of its own that have no handler under Robolectric.
        shadowOf(harness.app).checkActivities(true)
        compose.onNodeWithContentDescription(SCHEDULING_DASHBOARD_DESCRIPTION).scrolledIntoView().performClick()

        harness.recreate()
        gate.complete(Unit)

        harness.awaitText(harness.string(R.string.settings_browser_missing))
    }

    private companion object {
        val LIVE = SchedulingStatusResponse(
            eligible = true,
            canManage = true,
            tenant = SchedulingTenant(
                status = "ready",
                publicHost = "acme-book.distronode.com",
                region = "us",
                hasCredentials = true,
                bookingUrl = "https://acme-book.distronode.com/book/phone-consultation",
            ),
        )
    }
}
