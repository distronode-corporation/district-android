package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ⛔ THE NAVIGATION BAR IS THE ONE CONTROL THAT MUST NEVER BE THE THING THAT BREAKS. Before it
 * existed this app had no menu at all — seven screens reachable only through inline links on the
 * overview, with Contacts below the fold — so a user could not find two of the three main surfaces.
 * Now that it is the primary way around, a regression in it strands the whole app.
 *
 * ⚠️ SELECTION IS ASSERTED THROUGH SEMANTICS, NOT COLOUR. `assertIsSelected` reads the semantics
 * property, which is what a screen reader announces; a test that checked the tint would pass for a
 * bar that looked right and told assistive technology nothing. The tint is a second cue on purpose,
 * because colour alone is also the cue a colour-blind user may not receive.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DistrictNavBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        selectedLabel: String = OVERVIEW,
        onSelect: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                DistrictNavBar {
                    listOf(OVERVIEW, CALLS, CONTACTS, ACCOUNT).forEach { label ->
                        DistrictNavItem(
                            label = label,
                            icon = DistrictIcons.Home,
                            selected = label == selectedLabel,
                            description = "nav-$label",
                            onClick = { onSelect(label) },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `every destination is reachable from every destination`() {
        // ⛔ THE POINT OF A PERSISTENT BAR. Previously, getting from the call log to contacts meant
        // going back to the overview first and scrolling to find a link.
        render()

        listOf(OVERVIEW, CALLS, CONTACTS, ACCOUNT).forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun `exactly one destination reports itself selected`() {
        render(selectedLabel = CALLS)

        composeRule.onNodeWithTag("nav-$CALLS").assertIsSelected()
        composeRule.onNodeWithTag("nav-$OVERVIEW").assertIsNotSelected()
        composeRule.onNodeWithTag("nav-$CONTACTS").assertIsNotSelected()
        composeRule.onNodeWithTag("nav-$ACCOUNT").assertIsNotSelected()
    }

    @Test
    fun `tapping a destination reports it once`() {
        val taps = mutableListOf<String>()
        render(onSelect = { taps += it })

        composeRule.onNodeWithTag("nav-$CONTACTS").performClick()

        assertEquals(listOf(CONTACTS), taps)
    }

    @Test
    fun `tapping the current destination still reports, so the host can decide`() {
        // ⚠️ The item does NOT swallow a tap on itself. The host uses `launchSingleTop`, which makes
        // re-tapping a no-op at the navigation layer — deciding that here would hard-code one
        // behaviour and rule out "scroll to top", which is what a re-tap usually means.
        val taps = mutableListOf<String>()
        render(selectedLabel = OVERVIEW, onSelect = { taps += it })

        composeRule.onNodeWithTag("nav-$OVERVIEW").performClick()

        assertEquals(listOf(OVERVIEW), taps)
    }

    /**
     * ⛔ A LABEL IS NEVER SPLIT MID-WORD. Five items share a phone's width: on a 411dp phone each label
     * has about 68dp. The W4 French QA found "Overview" drawn as "Overvie / w" in English, and "Vue
     * d'ensemble" and "Boîte de réception" split twice each in French; the label now steps down until
     * its longest word fits.
     *
     * ⚠️ READ FROM `onTextLayout`, because the text's semantics report the layout at the style's own
     * size, not the size the auto-size chose.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `French labels in a phone-width item step down until every word is whole`() {
        val laid = layouts(68.dp, "Vue d'ensemble", "Boîte de réception", "Contacts", "Overview")

        laid.forEach { (label, layout) -> assertFalse(label, layout.splitsAWord()) }
        assertTrue(laid.getValue("Vue d'ensemble").layoutInput.style.fontSize < 14.sp)
        // An English label that already fits keeps the full size.
        assertEquals(14.sp, laid.getValue("Overview").layoutInput.style.fontSize)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a word too wide at every size stops at the floor rather than shrinking out of sight`() {
        val word = "Supercalifragilisticexpialidocious"
        assertEquals(11.sp, layouts(40.dp, word).getValue(word).layoutInput.style.fontSize)
    }

    @Test
    fun `two auto-sizes with the same bounds are the same auto-size, so the text is not relaid out`() {
        assertEquals(WholeWordAutoSize(14.sp, 11.sp), WholeWordAutoSize(14.sp, 11.sp))
        assertEquals(WholeWordAutoSize(14.sp, 11.sp).hashCode(), WholeWordAutoSize(14.sp, 11.sp).hashCode())
        assertNotEquals(WholeWordAutoSize(14.sp, 11.sp), WholeWordAutoSize(14.sp, 10.sp))
        assertNotEquals(WholeWordAutoSize(14.sp, 11.sp), WholeWordAutoSize(12.sp, 11.sp))
        assertNotEquals(WholeWordAutoSize(14.sp, 11.sp), 14.sp)
    }

    /** The layout [WholeWordAutoSize] settles on for each label in a box [width] wide, from 14sp to 11sp. */
    private fun layouts(width: Dp, vararg labels: String): Map<String, TextLayoutResult> {
        val out = mutableMapOf<String, TextLayoutResult>()
        composeRule.setContent {
            Column {
                labels.forEach { label ->
                    Box(Modifier.width(width)) {
                        Text(
                            text = label,
                            style = TextStyle(fontSize = 14.sp),
                            autoSize = WholeWordAutoSize(max = 14.sp, floor = 11.sp),
                            onTextLayout = { out[label] = it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return out
    }

    private companion object {
        const val OVERVIEW = "Overview"
        const val CALLS = "Calls"
        const val CONTACTS = "Contacts"
        const val ACCOUNT = "Account"
    }
}
