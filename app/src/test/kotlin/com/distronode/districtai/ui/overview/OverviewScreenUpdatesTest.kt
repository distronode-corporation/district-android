package com.distronode.districtai.ui.overview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.Overview
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.OverviewMetrics
import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The overview while it is ALIVE: a refresh arriving, figures changing under the operator, and the
 * workspace switcher actually switching. [OverviewScreenTest] renders each state once; these hold
 * one screen and change what it is showing, which is what a pull-to-refresh or a switch does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class OverviewScreenUpdatesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val alpha = WorkspaceEntry(id = "ws-a", name = "Alpha Agency", region = "ca", role = "client")
    private val bravo = WorkspaceEntry(id = "ws-b", name = "Bravo", region = "us", role = "client")

    private fun content(totalCalls: Int = 412, refreshing: Boolean = false) = OverviewUiState.Content(
        overview = Overview(
            workspaceId = "ws-a",
            role = WorkspaceRole.CLIENT,
            metrics = OverviewMetrics(totalCalls = totalCalls, callsThisWeek = 19, totalContacts = 87),
            avgDurationLabel = "45s",
            recentCalls = emptyList(),
        ),
        workspaces = listOf(alpha, bravo),
        active = alpha,
        refreshing = refreshing,
    )

    private val progressBar = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    @Test
    fun `a refresh keeps the figures on screen under a progress bar, which goes when it lands`() {
        var state by mutableStateOf<OverviewUiState>(content(refreshing = true))
        composeRule.setContent {
            DistrictTheme {
                OverviewScreen(state = state, onRetry = {}, onSignIn = {}, onSelectWorkspace = {})
            }
        }

        composeRule.onNode(progressBar).assertExists()
        composeRule.onNodeWithContentDescription("Total Calls Routed, 412").assertIsDisplayed()

        // A figure moving while the bar is still up: the tile re-announces its new value.
        state = content(totalCalls = 450, refreshing = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Total Calls Routed, 450").assertIsDisplayed()

        state = content(totalCalls = 500)
        composeRule.waitForIdle()

        composeRule.onNode(progressBar).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Total Calls Routed, 500").assertIsDisplayed()
        // The tiles whose figures did not move still read the same.
        composeRule.onNodeWithContentDescription("Weekly Call Volume, 19").assertIsDisplayed()
        composeRule.onNodeWithText("Alpha Agency").assertIsDisplayed()
    }

    @Test
    fun `the switcher lists every workspace and hands back the one picked`() {
        val picked = mutableListOf<String>()
        composeRule.setContent {
            DistrictTheme {
                OverviewScreen(
                    state = content(),
                    onRetry = {},
                    onSignIn = {},
                    onSelectWorkspace = { picked += it },
                )
            }
        }

        composeRule.onNodeWithText("Bravo").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(OVERVIEW_SWITCHER_DESCRIPTION).performClick()
        composeRule.onNodeWithText("Bravo").performClick()

        assertEquals(listOf("ws-b"), picked)
        // The menu closes on a pick, so the list does not sit over the screen being reloaded.
        composeRule.onNodeWithText("Bravo").assertDoesNotExist()
    }
}
