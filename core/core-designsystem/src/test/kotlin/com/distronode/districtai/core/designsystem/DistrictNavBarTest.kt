package com.distronode.districtai.core.designsystem

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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

        composeRule.onNodeWithContentDescription("nav-$CALLS").assertIsSelected()
        composeRule.onNodeWithContentDescription("nav-$OVERVIEW").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("nav-$CONTACTS").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("nav-$ACCOUNT").assertIsNotSelected()
    }

    @Test
    fun `tapping a destination reports it once`() {
        val taps = mutableListOf<String>()
        render(onSelect = { taps += it })

        composeRule.onNodeWithContentDescription("nav-$CONTACTS").performClick()

        assertEquals(listOf(CONTACTS), taps)
    }

    @Test
    fun `tapping the current destination still reports, so the host can decide`() {
        // ⚠️ The item does NOT swallow a tap on itself. The host uses `launchSingleTop`, which makes
        // re-tapping a no-op at the navigation layer — deciding that here would hard-code one
        // behaviour and rule out "scroll to top", which is what a re-tap usually means.
        val taps = mutableListOf<String>()
        render(selectedLabel = OVERVIEW, onSelect = { taps += it })

        composeRule.onNodeWithContentDescription("nav-$OVERVIEW").performClick()

        assertEquals(listOf(OVERVIEW), taps)
    }

    private companion object {
        const val OVERVIEW = "Overview"
        const val CALLS = "Calls"
        const val CONTACTS = "Contacts"
        const val ACCOUNT = "Account"
    }
}
