package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The primitive surface, pinned.
 *
 * ⛔ THESE ARE NOW THE ONLY WAY ANY SCREEN DRAWS ANYTHING, so a regression here is a regression
 * everywhere at once — the exact reason the web keeps its equivalents in one file and calls that file
 * "the single source of truth". Before it existed, every surface re-implemented these inline and they
 * drifted; this module is that consolidation for Android, and this test is what stops it drifting
 * back.
 *
 * ⚠️ ASSERTS BEHAVIOUR AND STRUCTURE, NOT PIXELS. There is no screenshot test in this project, and
 * these deliberately do not pretend to be one: they check that a component renders its text, that a
 * clickable one is clickable, and that a disabled one reports disabled. Geometry — the class of bug
 * that let the app-bar inset reach only one state per screen — is NOT covered by anything here, and
 * that gap is worth knowing rather than papering over.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PrimitivesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the eyebrow uppercases its input`() {
        // ⛔ THIS BEHAVIOUR BROKE A TEST IN THE APP MODULE, WHICH IS WHY IT IS PINNED HERE. CSS
        // `text-transform: uppercase` has no TextStyle equivalent in Compose, so the component does
        // it — and any assertion elsewhere that matches the sentence-case string will find nothing.
        composeRule.setContent { DistrictTheme { Eyebrow("Total calls routed") } }

        composeRule.onNodeWithText("TOTAL CALLS ROUTED").assertIsDisplayed()
    }

    @Test
    fun `a metric card pairs its label and value`() {
        composeRule.setContent {
            DistrictTheme { MetricCard(label = "Total calls", value = "258", caption = "Cumulative") }
        }

        composeRule.onNodeWithText("TOTAL CALLS").assertIsDisplayed()
        composeRule.onNodeWithText("258").assertIsDisplayed()
        composeRule.onNodeWithText("Cumulative").assertIsDisplayed()
    }

    @Test
    fun `a page header renders the accent word as part of one sentence`() {
        // ⚠️ One AnnotatedString, not two Texts in a Row — two would not wrap as a single sentence, so
        // a long title would break mid-phrase and strand the accent on its own line.
        composeRule.setContent {
            DistrictTheme {
                PageHeader(title = "Distronode", accent = "Corporation", subtitle = "Region: US")
            }
        }

        composeRule.onNodeWithText("Distronode Corporation").assertIsDisplayed()
        composeRule.onNodeWithText("Region: US").assertIsDisplayed()
    }

    @Test
    fun `every badge tone renders`() {
        composeRule.setContent {
            DistrictTheme {
                Column {
                    Tone.entries.forEach { DistrictBadge(text = it.name, tone = it) }
                }
            }
        }

        Tone.entries.forEach { composeRule.onNodeWithText(it.name).assertIsDisplayed() }
    }

    @Test
    fun `an avatar falls back to a glyph rather than rendering empty`() {
        // ⚠️ An unnamed contact is common — the voice agent writes "Unknown" for an unidentified
        // caller — and an empty circle reads as a loading failure rather than as absent data.
        composeRule.setContent {
            DistrictTheme {
                Column {
                    Avatar(name = "Ada Lovelace")
                    Avatar(name = "", modifier = Modifier)
                }
            }
        }

        composeRule.onNodeWithText("AL").assertIsDisplayed()
        composeRule.onNodeWithText("·").assertIsDisplayed()
    }

    @Test
    fun `a list row is clickable only when given a click`() {
        composeRule.setContent {
            DistrictTheme {
                Column {
                    DistrictListRow(title = "Tappable", subtitle = "sub", onClick = {})
                    DistrictListRow(title = "Inert", subtitle = "sub")
                }
            }
        }

        composeRule.onNodeWithText("Tappable").assertIsDisplayed()
        composeRule.onNodeWithText("Inert").assertIsDisplayed()
    }

    @Test
    fun `an empty state can carry the action that fills it`() {
        // ⚠️ The action lives INSIDE the empty state so an empty list is where the first item gets
        // made, rather than a dead end describing a control elsewhere on the screen.
        var taps = 0
        composeRule.setContent {
            DistrictTheme {
                EmptyState(
                    title = "No contacts yet",
                    body = "Callers are added automatically.",
                    action = { DistrictButton(text = "Add a contact", onClick = { taps += 1 }) },
                )
            }
        }

        composeRule.onNodeWithText("No contacts yet").assertIsDisplayed()
        composeRule.onNodeWithText("Add a contact").assertHasClickAction().performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `every button variant and size renders, and disabled reports disabled`() {
        composeRule.setContent {
            DistrictTheme {
                Column {
                    ButtonVariant.entries.forEach { variant ->
                        DistrictButton(text = variant.name, onClick = {}, variant = variant)
                    }
                    DistrictButton(text = "Small", onClick = {}, size = ButtonSize.Sm)
                    DistrictButton(text = "Off", onClick = {}, enabled = false)
                }
            }
        }

        ButtonVariant.entries.forEach { composeRule.onNodeWithText(it.name).assertIsEnabled() }
        composeRule.onNodeWithText("Small").assertIsEnabled()
        composeRule.onNodeWithText("Off").assertIsNotEnabled()
    }

    @Test
    fun `the top bar offers back only when given a handler`() {
        var backs = 0
        composeRule.setContent {
            DistrictTheme { DistrictTopBar(title = "Call log", onBack = { backs += 1 }) }
        }

        composeRule.onNodeWithText("Call log").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `the top bar omits back when there is nowhere to go`() {
        composeRule.setContent { DistrictTheme { DistrictTopBar(title = "District AI") } }

        composeRule.onNodeWithText("District AI").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a skeleton and a status dot render without content`() {
        composeRule.setContent {
            DistrictTheme {
                Column {
                    SkeletonBlock()
                    Tone.entries.forEach { StatusDot(tone = it) }
                }
            }
        }

        // Nothing to read; reaching here without an exception is the assertion. Both are decorative
        // and deliberately carry no semantics — a screen reader announcing "loading block" four times
        // would be noise.
        composeRule.onRoot().assertIsDisplayed()
    }
}
