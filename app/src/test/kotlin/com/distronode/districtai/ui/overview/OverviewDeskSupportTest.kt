package com.distronode.districtai.ui.overview

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.data.Overview
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.OverviewMetrics
import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Overview's two ticket entries: the customer desk and Distronode support.
 *
 * ⛔ WITHOUT THESE ENTRIES BOTH SCREENS ARE REACHABLE ONLY THROUGH AN APP LINK, so an operator on the
 * phone has no way in. These tests pin that the way
 * in exists for a role the server admits, that it is ABSENT for one it does not (every desk and
 * support route excludes `viewer`, reads included), and that the two are not transposed: they are
 * mirror images with the same callback signature, and swapping them sends an operator to answer the
 * wrong people.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class OverviewDeskSupportTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val workspace = WorkspaceEntry(id = "ws-a", name = "Alpha Agency", region = "ca", role = "client")

    private fun content(role: WorkspaceRole?) = OverviewUiState.Content(
        overview = Overview(
            workspaceId = "ws-a",
            role = role,
            metrics = OverviewMetrics(totalCalls = 1, callsThisWeek = 1, totalContacts = 1, avgDuration = 1),
            avgDurationLabel = "1s",
            recentCalls = emptyList(),
        ),
        workspaces = listOf(workspace),
        active = workspace,
        degradedRegions = emptyList(),
    )

    /** Records the ROUTE each callback would navigate to, built the way DistrictNavHost builds it. */
    private val navigated = mutableListOf<String>()

    private fun render(role: WorkspaceRole?) {
        composeRule.setContent {
            DistrictTheme {
                OverviewScreen(
                    state = content(role),
                    onRetry = {},
                    onSignIn = {},
                    onSelectWorkspace = {},
                    onOpenDesk = { id, r -> navigated += Routes.desk(id, r) },
                    onOpenSupport = { id, r -> navigated += Routes.support(id, r) },
                )
            }
        }
    }

    private fun scrollTo(description: String) {
        // ⚠️ REQUIRED: the content is a LazyColumn and these rows sit below the fold.
        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription(description))
    }

    @Test
    fun `a client is offered the desk, labelled as the customer desk, and it opens the desk route`() {
        render(WorkspaceRole.CLIENT)

        scrollTo(OVERVIEW_OPEN_DESK_DESCRIPTION)
        composeRule.onNodeWithContentDescription(OVERVIEW_OPEN_DESK_DESCRIPTION)
            .assertTextEquals(context.getString(R.string.desk_open))
            .performClick()

        // ⚠️ THE OVERVIEW'S ROLE travels in the path, because the desk gates the whole destination on it.
        assertEquals(listOf("workspace/ws-a/desk/client"), navigated)
    }

    @Test
    fun `an agency member is offered support, labelled as Distronode support, and it opens the support route`() {
        render(WorkspaceRole.AGENCY)

        scrollTo(OVERVIEW_OPEN_SUPPORT_DESCRIPTION)
        composeRule.onNodeWithContentDescription(OVERVIEW_OPEN_SUPPORT_DESCRIPTION)
            .assertTextEquals(context.getString(R.string.support_open))
            .performClick()

        assertEquals(listOf("workspace/ws-a/support/agency"), navigated)
    }

    @Test
    fun `a viewer is offered neither, because every route behind both refuses a viewer`() {
        render(WorkspaceRole.VIEWER)

        // ⚠️ Scrolled to the LAST entry first, so an absence below is a gate and not a row that was
        // simply never composed by the LazyColumn.
        scrollTo(OVERVIEW_OPEN_WORKSPACE_SETTINGS_DESCRIPTION)
        composeRule.onNodeWithContentDescription(OVERVIEW_OPEN_DESK_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(OVERVIEW_OPEN_SUPPORT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an unrecognised role is offered neither`() {
        // ⛔ `WorkspaceRole.fromWire` fails closed to null, and null admits nothing.
        render(null)

        scrollTo(OVERVIEW_OPEN_WORKSPACE_SETTINGS_DESCRIPTION)
        composeRule.onNodeWithContentDescription(OVERVIEW_OPEN_DESK_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(OVERVIEW_OPEN_SUPPORT_DESCRIPTION).assertDoesNotExist()
    }
}
