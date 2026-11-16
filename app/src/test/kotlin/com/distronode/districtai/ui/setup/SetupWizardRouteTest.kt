package com.distronode.districtai.ui.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.scheduling.admin.calendar.TestLifecycleOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The wizard wired to the browser and the lifecycle: which URL each hand-off opens, a missing browser
 * said rather than swallowed, and coming back reading the state again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w600dp-h4000dp")
class SetupWizardRouteTest {

    @get:Rule(order = 0)
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val harness = SetupHarness()
    private val owner = TestLifecycleOwner()
    private val launched = mutableListOf<String>()
    private val messages = mutableListOf<String>()
    private var browser = true
    private var closed = 0
    private var epoch by mutableIntStateOf(0)

    private fun show(base: String = "https://www.example.com/") {
        owner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                DistrictTheme {
                    SetupWizardRoute(
                        deps = harness.deps,
                        workspaceId = SetupHarness.WORKSPACE,
                        workspaceName = "Bloom",
                        sessionEpoch = epoch,
                        apiBaseUrl = base,
                        launch = {
                            launched += it
                            browser
                        },
                        onShowMessage = { messages += it },
                        onClose = { closed++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun tap(tag: String) {
        val node = composeRule.onNodeWithTag(tag)
        // ⚠️ The top bar's actions sit outside the scrolling column.
        runCatching { node.performScrollTo() }
        node.performClick()
        composeRule.waitForIdle()
    }

    private fun comeBack() {
        composeRule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `buying a number opens the web's setup page, and coming back moves on when it was bought`() {
        harness.reads(setupState(business = SETUP_STEP_DONE, numbersHeld = 0))
        show()
        comeBack()
        assertEquals("an ordinary resume reads nothing", 1, harness.setup.reads.size)

        tap(NumberTags.OPEN_PAGE)
        assertEquals(listOf("https://www.example.com/dashboard/district"), launched)
        harness.reads(setupState(business = SETUP_STEP_DONE, number = SETUP_STEP_DONE))
        comeBack()
        assertEquals(2, harness.setup.reads.size)
        composeRule.onNodeWithTag(SetupTags.step(SetupStep.RECEPTIONIST)).assertExists()
    }

    @Test
    fun `the Studio pages open by their own paths, and a missing browser is said`() {
        harness.reads(setupState())
        show()
        browser = false
        tap(BusinessTags.OPEN_SKILLS)
        assertEquals(listOf("https://www.example.com/dashboard/district/studio/skills"), launched)
        assertEquals(listOf(str(R.string.settings_browser_missing)), messages)
        comeBack()
        assertEquals("nothing opened, nothing to read again", 1, harness.setup.reads.size)
        assertEquals(
            "https://www.example.com/dashboard/district/studio/integrations",
            SetupHandOff.INTEGRATIONS.destination("https://www.example.com")
        )
    }

    @Test
    fun `off HTTPS nothing can be opened`() {
        harness.reads(setupState())
        show(base = "http://10.0.2.2:3000")
        composeRule.onNodeWithTag(BusinessTags.OPEN_SKILLS).performScrollTo().assertIsNotEnabled()
        assertNull(SetupHandOff.SETUP.destination("http://10.0.2.2:3000"))
        SetupHandOff.entries.forEach { page -> assertNull(page.destination("ftp://x")) }
    }

    @Test
    fun `a new session reads again, and Finish later closes`() {
        harness.reads(setupState())
        show()
        epoch = 1
        composeRule.waitForIdle()
        assertEquals(2, harness.setup.reads.size)
        tap(SetupTags.FINISH_LATER)
        assertEquals(1, closed)
    }
}
