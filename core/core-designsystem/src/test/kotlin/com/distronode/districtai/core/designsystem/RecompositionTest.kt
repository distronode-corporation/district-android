package com.distronode.districtai.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The primitives across a recomposition: what a screen sees when the state behind them changes, and
 * when it does not.
 *
 * WHY THIS IS NOT [PrimitivesTest] AGAIN. That class renders each component once. A real screen
 * renders them hundreds of times, with inputs that mostly stay the same and occasionally change, and
 * the two cases take different paths through a composable: an unchanged input lets it skip, a
 * changed one must redraw with the new value, and a slot that goes from absent to present (or back)
 * must add or remove what it draws. Each case here drives one host through all three, bumping
 * [pass] to force a recomposition in which nothing else changed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class RecompositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var pass by mutableIntStateOf(0)

    /** Reads [pass] where it is called, so that scope recomposes on [recomposeUnchanged]. */
    @Composable
    private fun Pass() {
        Spacer(Modifier.testTag("pass-$pass"))
    }

    private fun recomposeUnchanged() {
        pass += 1
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pass-$pass").assertExists()
    }

    private fun SemanticsNodeInteraction.textColor(): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.color
    }

    @Test
    fun `a theme switched at runtime repaints its content in the new palette`() {
        var dark by mutableStateOf(true)
        composeRule.setContent {
            Pass()
            DistrictTheme(darkTheme = dark) { Eyebrow("Calls") }
        }

        assertEquals(DistrictColors.Dark.mutedForeground, composeRule.onNodeWithText("CALLS").textColor())
        recomposeUnchanged()
        assertEquals(DistrictColors.Dark.mutedForeground, composeRule.onNodeWithText("CALLS").textColor())

        dark = false
        composeRule.waitForIdle()
        assertEquals(DistrictColors.Light.mutedForeground, composeRule.onNodeWithText("CALLS").textColor())
    }

    @Test
    fun `a theme left to the system follows the system's dark mode when it changes`() {
        var night by mutableStateOf(false)
        composeRule.setContent {
            val base = LocalConfiguration.current
            val configuration = remember(base, night) {
                Configuration(base).apply {
                    val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
                }
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                DistrictTheme { Eyebrow("Calls") }
            }
        }

        assertEquals(DistrictColors.Light.mutedForeground, composeRule.onNodeWithText("CALLS").textColor())

        night = true
        composeRule.waitForIdle()
        assertEquals(DistrictColors.Dark.mutedForeground, composeRule.onNodeWithText("CALLS").textColor())
    }

    @Test
    fun `an eyebrow follows its text and its ink, and defaults to the muted ink`() {
        var label by mutableStateOf("Total calls")
        var ink by mutableStateOf(DistrictColors.Dark.district)
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Column {
                    Pass()
                    Eyebrow(label)
                    Eyebrow("Pinned", Modifier.testTag("pinned"), DistrictColors.Dark.foreground)
                    Eyebrow(label.reversed(), modifier = Modifier.testTag("inked"), color = ink)
                }
            }
        }

        assertEquals(DistrictColors.Dark.mutedForeground, composeRule.onNodeWithText("TOTAL CALLS").textColor())
        assertEquals(DistrictColors.Dark.foreground, composeRule.onNodeWithTag("pinned").textColor())
        assertEquals(DistrictColors.Dark.district, composeRule.onNodeWithTag("inked").textColor())
        recomposeUnchanged()

        label = "Missed"
        ink = DistrictColors.Dark.success
        composeRule.waitForIdle()
        composeRule.onNodeWithText("MISSED").assertIsDisplayed()
        composeRule.onNodeWithText("TOTAL CALLS").assertDoesNotExist()
        assertEquals(DistrictColors.Dark.success, composeRule.onNodeWithTag("inked").textColor())
    }

    @Test
    fun `a card becomes clickable when given a click, and stops when it is taken away`() {
        var onClick: (() -> Unit)? by mutableStateOf(null)
        var taps = 0
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Column {
                    Pass()
                    DistrictCard(modifier = Modifier.testTag("card"), onClick = onClick) { Text("Body") }
                    DistrictCard { Text("Plain") }
                }
            }
        }

        composeRule.onNodeWithTag("card").assertHasNoClickAction()
        recomposeUnchanged()

        onClick = { taps += 1 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("card").assertHasClickAction().performClick()
        assertEquals(1, taps)

        onClick = null
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("card").assertHasNoClickAction()
        composeRule.onNodeWithText("Plain").assertIsDisplayed()
    }

    @Test
    fun `a metric card shows a caption only while it has one`() {
        var caption: String? by mutableStateOf(null)
        var value by mutableStateOf("258")
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Column {
                    Pass()
                    MetricCard(label = "Total calls", value = value, caption = caption)
                    MetricCard("Answered", "12", Modifier.testTag("answered"))
                }
            }
        }

        composeRule.onNodeWithText("Cumulative").assertDoesNotExist()
        recomposeUnchanged()

        caption = "Cumulative"
        value = "259"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cumulative").assertIsDisplayed()
        composeRule.onNodeWithText("259").assertIsDisplayed()

        caption = null
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cumulative").assertDoesNotExist()
        composeRule.onNodeWithText("12").assertIsDisplayed()
    }

    @Test
    fun `a page header adds and drops its accent, subtitle and actions as they come and go`() {
        var accent: String? by mutableStateOf(null)
        var subtitle: String? by mutableStateOf(null)
        var withAction by mutableStateOf(false)
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Column {
                    Pass()
                    PageHeader(
                        title = "Distronode",
                        modifier = Modifier.testTag("header"),
                        accent = accent,
                        subtitle = subtitle,
                        actions = if (withAction) {
                            { Text("Export") }
                        } else {
                            null
                        },
                    )
                    PageHeader("Contacts")
                }
            }
        }

        composeRule.onNodeWithText("Distronode").assertIsDisplayed()
        composeRule.onNodeWithText("Export").assertDoesNotExist()
        recomposeUnchanged()

        accent = "Corporation"
        subtitle = "Region: US"
        withAction = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Distronode Corporation").assertIsDisplayed()
        composeRule.onNodeWithText("Region: US").assertIsDisplayed()
        composeRule.onNodeWithText("Export").assertIsDisplayed()

        accent = null
        subtitle = null
        withAction = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Distronode").assertIsDisplayed()
        composeRule.onNodeWithText("Region: US").assertDoesNotExist()
        composeRule.onNodeWithText("Export").assertDoesNotExist()
        composeRule.onNodeWithText("Contacts").assertIsDisplayed()
    }

    @Test
    fun `the top bar gains and loses its back action with the handler`() {
        var onBack: (() -> Unit)? by mutableStateOf(null)
        var title by mutableStateOf("Call log")
        var backs = 0
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Column {
                    Pass()
                    DistrictTopBar(title = title, modifier = Modifier.testTag("bar"), onBack = onBack) {
                        Text("Filter")
                    }
                    DistrictTopBar("Settings")
                }
            }
        }

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).assertDoesNotExist()
        recomposeUnchanged()

        onBack = { backs += 1 }
        title = "Contacts"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Contacts").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        assertEquals(1, backs)
        composeRule.onNodeWithText("Filter").assertIsDisplayed()
    }

    @Test
    fun `a scaffold shows its top bar only while it has one`() {
        var withBar by mutableStateOf(false)
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Pass()
                DistrictScaffold(
                    modifier = Modifier.testTag("scaffold"),
                    topBar = if (withBar) {
                        { DistrictTopBar(title = "Overview") }
                    } else {
                        null
                    },
                ) { padding -> Text("Content", modifier = padding) }
            }
        }

        composeRule.onNodeWithText("Content").assertIsDisplayed()
        composeRule.onNodeWithText("Overview").assertDoesNotExist()
        recomposeUnchanged()

        withBar = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Overview").assertIsDisplayed()
    }

    @Test
    fun `a bare scaffold with no bar still lays out its content`() {
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Pass()
                DistrictScaffold { padding -> Text("Only content", modifier = padding) }
            }
        }

        composeRule.onNodeWithText("Only content").assertIsDisplayed()
        recomposeUnchanged()
        composeRule.onNodeWithText("Only content").assertIsDisplayed()
    }

    @Test
    fun `a nav item moves its selection and its description with the state behind it`() {
        var selected by mutableStateOf(OVERVIEW)
        var described by mutableStateOf(false)
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Column {
                    Pass()
                    DistrictNavBar(modifier = Modifier.testTag("bar")) {
                        DistrictNavItem(
                            label = OVERVIEW,
                            selected = selected == OVERVIEW,
                            onClick = { selected = OVERVIEW },
                            modifier = Modifier.testTag(OVERVIEW),
                            description = if (described) "nav-$OVERVIEW" else null,
                        )
                        DistrictNavItem(CALLS, selected == CALLS, { selected = CALLS })
                    }
                    DistrictNavBar { DistrictNavItem(label = "Alone", selected = false, onClick = {}) }
                }
            }
        }

        composeRule.onNodeWithTag(OVERVIEW).assertIsSelected()
        composeRule.onNodeWithContentDescription("nav-$OVERVIEW").assertDoesNotExist()
        recomposeUnchanged()

        composeRule.onNodeWithText(CALLS).performClick()
        described = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("nav-$OVERVIEW").assertIsNotSelected()
        composeRule.onNodeWithText("Alone").assertIsNotSelected()
    }

    @Test
    fun `a badge and a status dot follow their tone, and a badge its text`() {
        var tone by mutableStateOf(Tone.Neutral)
        var text by mutableStateOf("queued")
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Column {
                    Pass()
                    DistrictBadge(text = text, tone = tone)
                    DistrictBadge("fixed", Modifier.testTag("fixed"), Tone.Danger)
                    DistrictBadge("plain")
                    StatusDot(modifier = Modifier.testTag("dot"), tone = tone)
                    StatusDot()
                }
            }
        }

        assertEquals(DistrictColors.Dark.mutedForeground, composeRule.onNodeWithText("queued").textColor())
        assertEquals(DistrictColors.Dark.destructive, composeRule.onNodeWithTag("fixed").textColor())
        recomposeUnchanged()

        tone = Tone.Success
        text = "delivered"
        composeRule.waitForIdle()
        assertEquals(DistrictColors.Dark.success, composeRule.onNodeWithText("delivered").textColor())
        composeRule.onNodeWithTag("dot").assertExists()
    }

    @Test
    fun `every tone inks its badge with its own token`() {
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Column { Tone.entries.forEach { DistrictBadge(text = it.name, tone = it) } }
            }
        }

        val expected = mapOf(
            Tone.Neutral to DistrictColors.Dark.mutedForeground,
            Tone.District to DistrictColors.Dark.district,
            Tone.Success to DistrictColors.Dark.success,
            Tone.Warning to DistrictColors.Dark.warning,
            Tone.Danger to DistrictColors.Dark.destructive,
            Tone.Info to DistrictColors.Dark.info,
        )
        expected.forEach { (tone, ink) -> assertEquals(ink, composeRule.onNodeWithText(tone.name).textColor()) }
    }

    @Test
    fun `a row divider draws with and without a caller's modifier across recompositions`() {
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                Column {
                    Pass()
                    DistrictRowDivider()
                    DistrictRowDivider(Modifier.testTag("divider"))
                }
            }
        }

        composeRule.onNodeWithTag("divider").assertExists()
        recomposeUnchanged()
        composeRule.onNodeWithTag("divider").assertExists()
    }

    private companion object {
        const val OVERVIEW = "Overview"
        const val CALLS = "Calls"
    }
}
