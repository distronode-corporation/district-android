package com.distronode.districtai.ui.overview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.data.Overview
import com.distronode.districtai.core.data.RecentActivity
import com.distronode.districtai.core.model.OverviewMetrics
import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.SignedOutCause
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.core.designsystem.DistrictTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ MOSTLY ABOUT THE EMPTY STATES, NOT THE HAPPY PATH. Four different situations produce a screen
 * with no dashboard on it, and they must not look alike: no workspaces, unpaid workspaces, an
 * incomplete read, and no session. The dangerous one is the third — rendering "we could not finish
 * looking" as "you have nothing" is indistinguishable from account loss to the person holding the
 * phone, and it has already shipped once on the web.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class OverviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val workspace = WorkspaceEntry(id = "ws-a", name = "Alpha Agency", region = "ca", role = "client")

    private fun overview(
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
        calls: List<RecentActivity> = emptyList(),
    ) = Overview(
        workspaceId = "ws-a",
        role = role,
        metrics = OverviewMetrics(totalCalls = 412, callsThisWeek = 19, totalContacts = 87, avgDuration = 45),
        // ⚠️ "45s", not "0m 45s". Two duration formats ship and disagree; this is the tile's.
        avgDurationLabel = "45s",
        recentCalls = calls,
    )

    private fun render(
        state: OverviewUiState,
        onRetry: () -> Unit = {},
        onSignIn: () -> Unit = {},
        onSelectWorkspace: (String) -> Unit = {},
        onOpenAnalytics: (String) -> Unit = {},
        onOpenMarketplace: (String, WorkspaceRole?) -> Unit = { _, _ -> },
        onOpenRooms: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            DistrictTheme {
                OverviewScreen(
                    state = state,
                    onRetry = onRetry,
                    onSignIn = onSignIn,
                    onSelectWorkspace = onSelectWorkspace,
                    onOpenAnalytics = onOpenAnalytics,
                    onOpenMarketplace = onOpenMarketplace,
                    onOpenRooms = onOpenRooms,
                )
            }
        }
    }

    /**
     * A second host for the dial tile only.
     *
     * ⚠️ ITS OWN HELPER RATHER THAN AN EIGHTH PARAMETER ON [render], because that function had
     * already reached detekt's parameter ceiling — a ceiling worth respecting rather than raising,
     * since every screen callback added to a shared test host makes every other test's call site
     * longer. `OverviewScreen` defaults every callback it does not need.
     */
    private fun renderForDialer(
        state: OverviewUiState,
        onOpenDialer: (String, WorkspaceRole?) -> Unit,
    ) {
        composeRule.setContent {
            DistrictTheme {
                OverviewScreen(
                    state = state,
                    onRetry = {},
                    onSignIn = {},
                    onSelectWorkspace = {},
                    onOpenDialer = onOpenDialer,
                )
            }
        }
    }

    /**
     * Scroll the content list until [text] is composed, then return the node.
     *
     * ⚠️ REQUIRED FOR ANYTHING BELOW THE FOLD. The content state is a LazyColumn, which only
     * composes visible items, so `onNodeWithText(...).assertIsDisplayed()` fails for a row further
     * down with "is not displayed" — which reads like a rendering bug rather than "it was never
     * composed". Scrolling is also the more honest assertion: it proves the row is reachable, not
     * merely present in a tree.
     */
    private fun scrollToText(text: String) = composeRule
        .onNode(hasScrollToNodeAction())
        .performScrollToNode(hasText(text, substring = true))
        .let { composeRule.onNodeWithText(text, substring = true) }

    private fun content(
        overview: Overview = overview(),
        workspaces: List<WorkspaceEntry> = listOf(workspace),
        degradedRegions: List<String> = emptyList(),
    ) = OverviewUiState.Content(
        overview = overview,
        workspaces = workspaces,
        active = workspace,
        degradedRegions = degradedRegions,
    )

    // ── Content ──────────────────────────────────────────────────────────────

    @Test
    fun `renders the four KPI tiles with the server's duration label`() {
        render(content())

        composeRule.onNodeWithContentDescription(OVERVIEW_ROOT_DESCRIPTION).assertIsDisplayed()

        // ⚠️ ASSERTED THROUGH THE PAIRED CONTENT DESCRIPTION, NOT THE RENDERED LABEL, AND THAT IS
        // A DELIBERATE CHANGE. The label is now an `Eyebrow`, which UPPERCASES its input the way
        // the web's `text-transform: uppercase` does — so the rendered node reads "TOTAL CALLS
        // ROUTED" and an assertion on the sentence-case string matches nothing.
        //
        // Asserting on "$label, $value" is better than asserting on the uppercased text: it pins
        // the label-value PAIRING that a screen reader announces, which is the behaviour that
        // actually matters here, and it survives a future change to the visual casing.
        composeRule.onNodeWithContentDescription("Total Calls Routed, 412").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Weekly Call Volume, 19").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Customer Contacts, 87").assertIsDisplayed()
        // ⛔ The server's label. A locally-formatted value would read "0m 45s" and disagree with
        // the browser on every sub-minute average.
        composeRule.onNodeWithContentDescription("Avg Call Duration, 45s").assertIsDisplayed()
        composeRule.onNodeWithText("412").assertIsDisplayed()
        composeRule.onNodeWithText("45s").assertIsDisplayed()
    }

    @Test
    fun `names the workspace and its data-residency region`() {
        render(content())

        composeRule.onNodeWithText("Alpha Agency").assertIsDisplayed()
        composeRule.onNodeWithText("Region: CA").assertIsDisplayed()
    }

    @Test
    fun `states read-only access up front for a viewer`() {
        // ⚠️ Better than letting the user discover it by tapping something that 403s.
        render(content(overview = overview(role = WorkspaceRole.VIEWER)))

        composeRule.onNodeWithContentDescription(OVERVIEW_READ_ONLY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `offers no switcher for a single workspace`() {
        render(content())

        composeRule.onNodeWithContentDescription(OVERVIEW_SWITCHER_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `offers a switcher for more than one workspace`() {
        render(
            content(
                workspaces = listOf(
                    workspace,
                    WorkspaceEntry(id = "ws-b", name = "Bravo", region = "us", role = "client"),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(OVERVIEW_SWITCHER_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the analytics tile is reachable and reports the ACTIVE workspace`() {
        // ⚠️ REACHABLE, NOT MERELY PRESENT. The destination buttons sit below the recent-activity
        // list in a LazyColumn, so an off-screen item is not composed at all — scrolling to it is
        // what proves an operator can actually get there.
        //
        // ⚠️ AND IT CARRIES NO ROLE, unlike the Contacts and HQ tiles beside it: both routes behind
        // the analytics screen admit agency, client and viewer alike, so there is nothing on it to
        // gate.
        var opened: String? = null
        render(content(), onOpenAnalytics = { opened = it })

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription(OVERVIEW_OPEN_ANALYTICS_DESCRIPTION))
        composeRule
            .onNodeWithContentDescription(OVERVIEW_OPEN_ANALYTICS_DESCRIPTION)
            .performClick()

        assertEquals("ws-a", opened)
    }

    @Test
    fun `the phone-numbers tile is reachable and carries the EFFECTIVE role`() {
        // ⚠️ CARRIES A ROLE, unlike the analytics tile immediately above — and the marketplace is
        // ALSO read-only for every role, so the difference is worth being explicit about. Nothing
        // on that screen is gated; the role decides what its read-only caption SAYS, because
        // telling a viewer to make the change on the web dashboard sends them somewhere that will
        // refuse them too.
        //
        // ⚠️ And it is the OVERVIEW's role, which is the effective one for this caller: support
        // access can grant "agency" with no membership row, which the workspace list would
        // understate.
        var openedWorkspace: String? = null
        var openedRole: WorkspaceRole? = null
        render(
            content(),
            onOpenMarketplace = { id, role ->
                openedWorkspace = id
                openedRole = role
            },
        )

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription(OVERVIEW_OPEN_MARKETPLACE_DESCRIPTION))
        composeRule
            .onNodeWithContentDescription(OVERVIEW_OPEN_MARKETPLACE_DESCRIPTION)
            .performClick()

        assertEquals("ws-a", openedWorkspace)
        assertEquals(WorkspaceRole.CLIENT, openedRole)
    }

    @Test
    fun `the rooms tile is offered to every role and carries the effective one`() {
        // ⛔ UNCONDITIONAL, UNLIKE THE WORKSPACE-SETTINGS TILE. Every route behind the rooms lobby
        // admits `viewer` — the meetings list, the detail and the join token all do — so there is
        // nothing here to hide. What a viewer gets is a room it cannot publish into, which the
        // room itself states rather than the tile withholding the destination.
        //
        // ⚠️ AND IT PASSES THE OVERVIEW'S ROLE, the effective one for this caller: support access
        // can grant "agency" with no membership row, which the workspace list would understate.
        // The lobby does not gate on it; it hands it to the room, where a viewer's token carries
        // `canPublish:false`.
        var openedWorkspace: String? = null
        var openedRole: WorkspaceRole? = null
        render(
            content(),
            onOpenRooms = { id, role ->
                openedWorkspace = id
                openedRole = role
            },
        )

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription(OVERVIEW_OPEN_ROOMS_DESCRIPTION))
        composeRule.onNodeWithContentDescription(OVERVIEW_OPEN_ROOMS_DESCRIPTION).performClick()

        assertEquals("ws-a", openedWorkspace)
        assertEquals(WorkspaceRole.CLIENT, openedRole)
    }

    @Test
    fun `the dial tile is gated on the role, unlike the rooms tile beside it`() {
        // ⛔ PRESENCE, NOT WORDING, AND THE CONTRAST WITH THE ROOMS TILE ABOVE IS THE POINT. Every
        // route behind the rooms lobby admits `viewer`; `POST /api/district/calls/dial` does not,
        // so offering this to a viewer would be a button that 403s on the destination's only
        // action. Same shape of gate as workspace settings.
        var openedWorkspace: String? = null
        var openedRole: WorkspaceRole? = null
        renderForDialer(content()) { id, role ->
            openedWorkspace = id
            openedRole = role
        }

        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription(OVERVIEW_OPEN_DIALER_DESCRIPTION))
        composeRule.onNodeWithContentDescription(OVERVIEW_OPEN_DIALER_DESCRIPTION).performClick()

        // ⚠️ THE OVERVIEW'S ROLE, the effective one for this caller: support access can grant
        // "agency" with no membership row, which the workspace list would understate.
        assertEquals("ws-a", openedWorkspace)
        assertEquals(WorkspaceRole.CLIENT, openedRole)
    }

    @Test
    fun `a viewer is offered no dial tile at all`() {
        render(content(overview = overview(role = WorkspaceRole.VIEWER)))

        composeRule
            .onNodeWithContentDescription(OVERVIEW_OPEN_DIALER_DESCRIPTION)
            .assertDoesNotExist()
        // ⚠️ The rooms tile IS still there for the same viewer, which is what makes this a gate
        // rather than a layout accident.
        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasContentDescription(OVERVIEW_OPEN_ROOMS_DESCRIPTION))
        composeRule.onNodeWithContentDescription(OVERVIEW_OPEN_ROOMS_DESCRIPTION).assertExists()
    }

    @Test
    fun `shows an empty recent-activity message rather than nothing`() {
        render(content())

        // ⚠️ NOW A TITLE PLUS A BODY, WHICH IS WHY THIS STRING CHANGED. The empty list used to be
        // one centred sentence, indistinguishable from a failed load — the whole reason
        // `EmptyState` exists. The body no longer repeats "No calls yet" because the title says it.
        scrollToText("Nothing here yet").assertIsDisplayed()
        scrollToText("Inbound and outbound calls will appear here as they happen.")
            .assertIsDisplayed()
    }

    @Test
    fun `renders a call with no caller id as a placeholder, not the literal Unknown`() {
        render(
            content(
                overview = overview(
                    calls = listOf(
                        RecentActivity(
                            id = "c1",
                            displayName = null,
                            outbound = false,
                            status = "completed",
                            live = false,
                            transferred = false,
                            transferFailed = false,
                            time = "Aug 15, 02:30 PM",
                        ),
                    ),
                ),
            ),
        )

        scrollToText("No caller ID").assertIsDisplayed()
        composeRule.onNodeWithText("Unknown").assertDoesNotExist()
    }

    @Test
    fun `flags a live call as Live and a stale one by its status`() {
        render(
            content(
                overview = overview(
                    calls = listOf(
                        RecentActivity(
                            id = "c1",
                            displayName = "Ada",
                            outbound = false,
                            status = "in-progress",
                            live = true,
                            transferred = false,
                            transferFailed = false,
                            time = "now",
                        ),
                        RecentActivity(
                            id = "c2",
                            displayName = "Bob",
                            outbound = true,
                            status = "no-answer",
                            live = false,
                            transferred = false,
                            transferFailed = false,
                            time = "earlier",
                        ),
                    ),
                ),
            ),
        )

        scrollToText("Live").assertIsDisplayed()
        scrollToText("no-answer").assertIsDisplayed()
    }

    @Test
    fun `warns when the workspace list was incomplete`() {
        // ⛔ The switcher is short because a region did not answer. Saying so is not optional:
        // implying the user has fewer workspaces than they do is the same class of error as
        // showing none at all.
        render(content(degradedRegions = listOf("eu", "apac")))

        composeRule.onNodeWithContentDescription(OVERVIEW_PARTIAL_DESCRIPTION).assertIsDisplayed()
    }

    // ── The states with nothing to show ──────────────────────────────────────

    @Test
    fun `distinguishes an empty account from a lapsed one`() {
        render(OverviewUiState.NoWorkspaces)
        composeRule.onNodeWithContentDescription(OVERVIEW_NO_WORKSPACES_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(OVERVIEW_BILLING_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a lapsed account gets a billing message and no purchase path`() {
        render(OverviewUiState.BillingBlocked(inactiveCount = 2))

        composeRule.onNodeWithContentDescription(OVERVIEW_BILLING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Subscription inactive").assertIsDisplayed()
        // ⚠️ Read-only billing, per Play's Payments policy: no button, no purchase affordance.
        // Only the ONE action this state offers may exist, and it is not "pay".
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `an incomplete read is retryable and names the regions`() {
        // ⛔ THE MOST IMPORTANT ASSERTION HERE. This state means "we could not finish looking",
        // never "there is nothing", so it must always offer another attempt AND must not be the
        // no-workspaces screen.
        var retries = 0
        render(
            OverviewUiState.Unavailable(
                message = UiText.Resource(R.string.overview_workspaces_degraded),
                degradedRegions = listOf("eu"),
            ),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithContentDescription(OVERVIEW_UNAVAILABLE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(OVERVIEW_NO_WORKSPACES_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithText("Affected regions: eu").assertIsDisplayed()

        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a routine sign-out is worded as routine`() {
        // Process death mid-refresh. ⚠️ Ends the session but is NOT a security event, and an
        // alarming message would be both wrong and unactionable.
        render(OverviewUiState.SignedOut(SignedOutCause.ROUTINE))

        composeRule.onNodeWithContentDescription(OVERVIEW_SIGNED_OUT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Your session ended. Signing in again will restore it.")
            .assertIsDisplayed()
    }

    @Test
    fun `an invalid session offers a way back in`() {
        var signIns = 0
        render(OverviewUiState.SignedOut(SignedOutCause.SESSION_INVALID), onSignIn = { signIns += 1 })

        composeRule.onNodeWithText("Sign in").performClick()
        assertEquals(1, signIns)
    }

    @Test
    fun `loading is announced rather than being a bare spinner`() {
        render(OverviewUiState.Loading)

        composeRule.onNodeWithContentDescription(OVERVIEW_LOADING_DESCRIPTION).assertIsDisplayed()
    }
}
