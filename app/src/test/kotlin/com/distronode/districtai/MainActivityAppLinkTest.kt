package com.distronode.districtai

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.applinks.DistrictSection
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.SIGN_IN_ROOT_DESCRIPTION
import com.distronode.districtai.ui.marketplace.MARKETPLACE_BUY_WEB_DESCRIPTION
import com.distronode.districtai.ui.scrolledIntoView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A verified App Link reaching the activity, and what the signed-in graph does with it.
 *
 * ⛔ THREE OUTCOMES AND THE THIRD IS NOT THE SECOND. A dashboard section is recorded for the graph, a
 * URL on our hosts that the app has no screen for goes to a browser, and anything else (including
 * the login callback, which is also an `ACTION_VIEW`) is left alone. Collapsing "leave it" into
 * "open it in a browser" would send the sign-in callback to a Custom Tab.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], application = ShellTestApplication::class)
class MainActivityAppLinkTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(compose)

    private val harness = ShellHarness(compose)

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a dashboard link on a cold start is recorded for the graph and consumed`() {
        harness.signedOut()

        harness.launch(harness.viewIntent("https://www.distronode.com/dashboard/district/inbox"))

        assertEquals(DistrictSection.INBOX, harness.container.appLinkDeepLinks.pending.value)
        assertNull("consumed, so a rotation cannot re-navigate", harness.activity.intent.data)
    }

    @Test
    fun `a recreation does not re-deliver a link the graph already acted on`() {
        harness.signedOut()
        harness.launch(harness.viewIntent("https://distronode.com/dashboard/district/calls"))
        harness.container.appLinkDeepLinks.clear()

        harness.recreate()

        assertNull(harness.container.appLinkDeepLinks.pending.value)
    }

    @Test
    fun `a link on our host that the app has no screen for opens in the pinned browser`() {
        harness.installBrowser()
        harness.signedOut()
        harness.launch()
        val url = "https://www.distronode.com/dashboard/districtai-pricing"

        harness.deliver(harness.viewIntent(url))

        val opened = checkNotNull(harness.nextStarted()) { "the page was not handed to a browser" }
        // ⛔ Pinned, so this app's own App Link filter cannot route it straight back here.
        assertEquals(ShellHarness.BROWSER_PACKAGE, opened.`package`)
        assertEquals(url, opened.data.toString())
        assertNull(harness.activity.intent.data)
        assertNull(harness.container.appLinkDeepLinks.pending.value)
    }

    @Test
    fun `with no browser, a link the app cannot show is dropped rather than looped back in`() {
        harness.signedOut()
        harness.launch()

        harness.deliver(harness.viewIntent("https://distronode.com/pricing"))

        assertNull("an implicit start would resolve back into this activity", harness.nextStarted())
        assertNull(harness.activity.intent.data)
    }

    @Test
    fun `a URL on a host we do not claim is left untouched`() {
        harness.signedOut()
        val foreign = "https://example.com/dashboard/district/inbox"

        harness.launch(harness.viewIntent(foreign))

        assertNull(harness.container.appLinkDeepLinks.pending.value)
        assertNull(harness.nextStarted())
        assertEquals(foreign, harness.activity.intent.data.toString())
    }

    @Test
    fun `a signed-in cold start shows the graph, not the sign-in screen`() {
        harness.signedIn()

        harness.launch()

        harness.awaitText(ShellHarness.WORKSPACE.name)
        assertTrue(compose.onAllNodes(hasContentDescription(SIGN_IN_ROOT_DESCRIPTION)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a marketplace link lands on the marketplace, and a missing browser is reported in a snackbar`() {
        // ⛔ THE ACTIVITY'S MESSAGE CHANNEL, END TO END. The graph reports "no browser" through
        // `onShowMessage`, and the activity is what turns that into something the user can read.
        harness.signedIn()

        harness.launch(harness.viewIntent("https://www.distronode.com/dashboard/district/marketplace"))
        harness.awaitDescription(MARKETPLACE_BUY_WEB_DESCRIPTION)
        compose.onNodeWithContentDescription(MARKETPLACE_BUY_WEB_DESCRIPTION).scrolledIntoView().performClick()

        val missing = harness.string(R.string.settings_browser_missing)
        harness.awaitText(missing)

        // ⚠️ And it goes away on its own: a snackbar that never timed out would sit over the graph.
        compose.mainClock.advanceTimeBy(SNACKBAR_TIMEOUT_MILLIS)
        harness.await("the snackbar to time out") {
            compose.onAllNodes(hasText(missing)).fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        /** Longer than Material's longest non-indefinite snackbar, which is ten seconds. */
        const val SNACKBAR_TIMEOUT_MILLIS = 11_000L
    }
}
