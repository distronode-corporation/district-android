package com.distronode.districtai.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.calls.CALL_LOG_FAILURE_DESCRIPTION
import com.distronode.districtai.ui.contacts.CONTACTS_FAILURE_DESCRIPTION
import com.distronode.districtai.ui.inbox.INBOX_ROOT_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.SETTINGS_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_ROOT_DESCRIPTION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the graph does when the session changes under it, and when its own inputs change.
 *
 * ⛔ A COMPLETED LOGIN IS AN EPOCH ADVANCE, AND EVERY DESTINATION MUST ANSWER IT WITH A READ, NEVER
 * A WRITE. A screen that failed on an expired session stays failed until something re-reads; a
 * screen that replayed a write on the same signal would send a second message or place a second call
 * because a token was refreshed. So each case here pins the READ that comes back, and HQ pins that
 * nothing does when there is nothing failed to replay.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class NavHostSessionTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    @After
    fun tearDown() = harness.close()

    private val failure = ApiResult.HttpFailure(status = 500, message = "down")

    /** A destination, and the count that must rise when a login lands while it is on screen. */
    private class Case(val route: String, val reads: () -> Int, val settled: String? = null)

    @Test
    fun `a completed login re-reads every destination that shows server state`() {
        val api = harness.api
        // ⚠️ The paged lists and the retry-only screens reload only from a FAILED read, which is
        // exactly the state an expired session leaves them in.
        api.callsResult = failure
        api.contactsResult = failure
        harness.desk.settingsResult = failure
        harness.desk.ticketResult = failure
        harness.support.listResult = failure
        harness.support.detailResult = failure
        harness.render()

        val role = WorkspaceRole.AGENCY
        val cases = listOf(
            Case(Routes.DEVICES, { api.deviceListRequests.size }),
            Case(Routes.callLog("ws-1"), { api.callsReads }, CALL_LOG_FAILURE_DESCRIPTION),
            Case(Routes.inbox("ws-1", role), { api.conversationsReads }),
            Case(Routes.thread("ws-1", role, "contact:ct-4"), { api.timelineCursors.size }),
            Case(Routes.contacts("ws-1", role), { api.contactsReads }, CONTACTS_FAILURE_DESCRIPTION),
            Case(Routes.analytics("ws-1"), { api.analyticsRequests.size }),
            Case(Routes.marketplace("ws-1", role), { api.ownedRequests.size }),
            Case(Routes.workflows("ws-1", role), { api.workflowListRequests.size }),
            Case(Routes.billing("ws-1", role), { api.workspaceBillingRequests.size }),
            Case(Routes.rooms("ws-1", role), { api.meetingsRequests.size }),
            Case(Routes.dialer("ws-1", role), { api.callsReads }),
            Case(Routes.workspaceSettings("ws-1", role, Routes.SECTION_DIRECTORY), { api.configRequests.size }),
            Case(Routes.workspaceSettings("ws-1", role, Routes.SECTION_ROUTING), { api.configRequests.size }),
            Case(Routes.workspaceSettings("ws-1", role, Routes.SECTION_PERSONA), { api.configRequests.size }),
            Case(
                Routes.workspaceSettings("ws-1", role, Routes.SECTION_VOICE_STUDIO),
                { harness.persona.voiceStudioCalls.size },
            ),
            Case(Routes.workspaceSettings("ws-1", role, Routes.SECTION_CAPABILITIES), { api.configRequests.size }),
            Case(Routes.workspaceSettings("ws-1", role, Routes.SECTION_KNOWLEDGE), { api.knowledgeListRequests.size }),
            Case(
                Routes.workspaceSettings("ws-1", role, Routes.SECTION_MESSAGING),
                { api.messagingApi.messagingRequests.size },
            ),
            Case(Routes.workspaceSettings("ws-1", role, Routes.SECTION_MEMBERS), { api.memberListRequests.size }),
            Case(Routes.workspaceSettings("ws-1", role, Routes.SECTION_CALLS), { harness.callHandling.handlingReads }),
            Case(Routes.contactDetail("ws-1", "ct-4", role), { api.contactRequestCount }),
            Case(Routes.callDetail("ws-1", "c1"), { api.detailRequests.size }),
            Case(Routes.desk("ws-1", role), { harness.desk.settingsReads }),
            Case(Routes.deskTicket("ws-1", role, "ticket-uuid-1"), { harness.desk.ticketDetailReads }),
            Case(Routes.deskSettings("ws-1", role), { harness.desk.settingsReads }),
            Case(Routes.support("ws-1", role), { harness.support.listReads }),
            Case(Routes.supportRequest("ws-1", role, "DA-42"), { harness.support.detailReads }),
        )

        cases.forEach { case ->
            harness.navigate(case.route)
            case.settled?.let(harness::awaitDescription)
            val before = case.reads()

            harness.bumpEpoch()

            harness.awaitMain { case.reads() > before }
            case.settled?.let(harness::awaitDescription)
        }
    }

    @Test
    fun `HQ replays nothing on a login when nothing failed, because a confirm may already have landed`() {
        harness.render()
        harness.navigate(Routes.hq("ws-1", WorkspaceRole.AGENCY))

        harness.bumpEpoch()

        assertTrue(harness.api.hqPrompts.isEmpty())
        assertTrue(harness.api.hqConfirms.isEmpty())
    }

    @Test
    fun `screens with no server state of their own stay put across a login`() {
        harness.render()
        listOf(
            Routes.OVERVIEW to OVERVIEW_ROOT_DESCRIPTION,
            Routes.SETTINGS to SETTINGS_ROOT_DESCRIPTION,
            Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY) to WORKSPACE_SETTINGS_ROOT_DESCRIPTION,
        ).forEach { (route, root) ->
            harness.navigate(route)
            harness.bumpEpoch()
            composeRule.onNodeWithContentDescription(root).assertIsDisplayed()
        }
    }

    // ── The host's own inputs ──────────────────────────────────────────────────────────────

    @Test
    fun `the host builds its own controller when none is passed, as the activity does`() {
        harness.render(ownController = true)

        composeRule.onNodeWithContentDescription(OVERVIEW_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(NAV_INBOX_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(INBOX_ROOT_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the bar follows the overview's role as it changes, and goes when the workspace does`() {
        harness.render()

        harness.overviewState = NavHostHarness.content(role = WorkspaceRole.VIEWER)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(NAV_CONTACTS_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(Routes.CONTACTS, harness.route())
        assertEquals("viewer", harness.argument(ARG_ROLE))

        harness.overviewState = com.distronode.districtai.ui.overview.OverviewUiState.Loading
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(NAV_CONTACTS_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a new overview state handed down by a caller reaches the graph, and an unchanged one changes nothing`() {
        harness.render(forwarded = true)

        // Unchanged: the caller recomposes for the epoch alone, and the tabs still carry agency.
        harness.bumpEpoch()
        composeRule.onNodeWithContentDescription(NAV_CONTACTS_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals("agency", harness.argument(ARG_ROLE))

        // Changed: a new state with a narrower role, and the next tab carries it.
        harness.overviewState = NavHostHarness.content(role = WorkspaceRole.VIEWER)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(NAV_INBOX_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(Routes.INBOX, harness.route())
        assertEquals("viewer", harness.argument(ARG_ROLE))
    }
}
