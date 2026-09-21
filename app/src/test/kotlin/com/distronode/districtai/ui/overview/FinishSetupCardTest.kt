package com.distronode.districtai.ui.overview

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
 * The card itself: present only when the state says so, and its button calls back.
 *
 * ⚠️ The URL it opens is asserted by [setupWebUrl]'s own test below rather than by launching a
 * browser here; the launch path is `openInBrowser`, shared with the marketplace hand-off.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class FinishSetupCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val workspace = WorkspaceEntry(id = "ws-a", name = "Alpha", region = "ca", role = "client")

    private fun content(showFinishSetup: Boolean) = OverviewUiState.Content(
        overview = Overview(
            workspaceId = "ws-a",
            role = WorkspaceRole.CLIENT,
            metrics = OverviewMetrics(),
            avgDurationLabel = "0s",
            recentCalls = emptyList(),
        ),
        workspaces = listOf(workspace),
        active = workspace,
        showFinishSetup = showFinishSetup,
    )

    @Test
    fun `the card shows for an owner mid-setup and opens the web`() {
        var opened = 0
        composeRule.setContent {
            DistrictTheme {
                OverviewScreen(
                    state = content(showFinishSetup = true),
                    onRetry = {},
                    onSignIn = {},
                    onSelectWorkspace = {},
                    onFinishSetup = { opened++ },
                )
            }
        }

        composeRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription(OVERVIEW_FINISH_SETUP_ACTION_DESCRIPTION))
        composeRule.onNodeWithContentDescription(OVERVIEW_FINISH_SETUP_ACTION_DESCRIPTION).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `no card when the state does not ask for one`() {
        composeRule.setContent {
            DistrictTheme {
                OverviewScreen(
                    state = content(showFinishSetup = false),
                    onRetry = {},
                    onSignIn = {},
                    onSelectWorkspace = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(OVERVIEW_FINISH_SETUP_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the web link is the dashboard on the app's own host`() {
        assertEquals(
            "https://www.distronode.com/dashboard/district",
            setupWebUrl("https://www.distronode.com/"),
        )
    }
}
