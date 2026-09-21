package com.distronode.districtai.ui.workflows

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.CampaignStatus
import com.distronode.districtai.core.model.WorkflowActionResult
import com.distronode.districtai.core.model.WorkflowLatestRun
import com.distronode.districtai.core.model.WorkflowListItem
import com.distronode.districtai.core.model.WorkflowRun
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the automation monitor draws, and the four things it must never draw: a campaign pause
 * control, an empty workflow list as a failure, a working switch for a viewer, and a `partial` run
 * as a healthy one.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT, for the reason the marketplace and analytics screens need one: this is a
// LazyColumn and `assertIsDisplayed` checks visible BOUNDS. On a phone-sized Robolectric display
// the read-only caption and the expanded run panel sit below the fold, and the assertion fails with
// "is not displayed" against a node that is perfectly present — which reads as a rendering bug
// rather than as a short viewport.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class WorkflowsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val active = WorkflowListItem(
        id = "wf-active",
        name = "Missed-call follow-up",
        active = true,
        trigger = "call_ended_unanswered",
        createdAt = "2026-08-18T10:00:00.000Z",
        latestRun = WorkflowLatestRun(status = "partial", startedAt = "2026-08-18T11:30:00.000Z"),
    )

    private val paused = WorkflowListItem(
        id = "wf-paused",
        name = "New contact welcome",
        active = false,
        trigger = "contact_created",
        createdAt = "2026-08-01T09:00:00.000Z",
    )

    private val runningCampaign = CampaignStatus(
        infiniteSdrEnabled = true,
        sdrBatchSize = 25,
        sdrCampaignGoal = "Book demos with lapsed trials",
    )

    @Suppress("LongParameterList")
    private fun ready(
        workflows: List<WorkflowListItem> = listOf(active, paused),
        campaign: CampaignStatus = runningCampaign,
        expanded: String? = null,
        runs: Map<String, RunHistory> = emptyMap(),
        pendingToggles: Set<String> = emptySet(),
        toggleFailure: FailureText? = null,
        campaignConfirm: CampaignConfirm? = null,
        campaignPending: Boolean = false,
        campaignFailure: FailureText? = null,
    ) = WorkflowsUiState(
        campaign = CampaignState.Ready(campaign),
        workflows = WorkflowListState.Ready(workflows),
        expanded = expanded,
        runs = runs,
        pendingToggles = pendingToggles,
        toggleFailure = toggleFailure,
        campaignConfirm = campaignConfirm,
        campaignPending = campaignPending,
        campaignFailure = campaignFailure,
    )

    /**
     * ⚠️ The campaign callbacks are recorded through a mutable holder rather than added to this
     * helper's parameter list: [render] is not `@Composable`, so detekt's `LongParameterList`
     * exemption does not cover it — the same reason `AnalyticsScreenTest` bundles its three.
     */
    private class CampaignRecorder {
        val requested: MutableList<Boolean> = mutableListOf()
        var dismissals: Int = 0
        var confirms: Int = 0

        fun callbacks(): CampaignCallbacks = CampaignCallbacks(
            onRequestChange = { requested += it },
            onDismissConfirm = { dismissals++ },
            onConfirm = { confirms++ },
        )
    }

    @Suppress("LongParameterList")
    private fun render(
        state: WorkflowsUiState,
        canToggle: Boolean = true,
        onRetry: () -> Unit = {},
        onToggleExpanded: (String) -> Unit = {},
        onLoadMoreRuns: (String) -> Unit = {},
        onSetActive: (String, Boolean) -> Unit = { _, _ -> },
        onDismissToggleFailure: () -> Unit = {},
        campaign: CampaignRecorder = CampaignRecorder(),
    ) {
        composeRule.setContent {
            DistrictTheme {
                WorkflowsScreen(
                    state = state,
                    canToggle = canToggle,
                    onRetry = onRetry,
                    onToggleExpanded = onToggleExpanded,
                    onLoadMoreRuns = onLoadMoreRuns,
                    onSetActive = onSetActive,
                    onDismissToggleFailure = onDismissToggleFailure,
                    campaign = campaign.callbacks(),
                    onBack = {},
                )
            }
        }
    }

    // ── The SDR campaign card ────────────────────────────────────────────────

    @Test
    fun `a running campaign shows its batch size and goal`() {
        render(ready())

        composeRule.onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Active").assertIsDisplayed()
        composeRule.onNodeWithText("25 contacts per batch").assertIsDisplayed()
        composeRule.onNodeWithText("Book demos with lapsed trials").assertIsDisplayed()
    }

    @Test
    fun `an all-empty campaign reads as paused with no goal, not as unconfigured`() {
        // ⛔ A WORKSPACE THAT NEVER OPENED THE CAMPAIGNS TAB AND ONE THAT SWITCHED THE CAMPAIGN OFF
        // ARE THE SAME VALUE ON THE WIRE. The copy has to be true of both, so "Paused" rather than
        // "Off" or "Not set up", and an explicit sentence rather than a blank where a goal would be.
        render(ready(campaign = CampaignStatus()))

        composeRule.onNodeWithText("Paused").assertIsDisplayed()
        composeRule.onNodeWithText("No goal configured yet.").assertIsDisplayed()
        // ⛔ NULL IS NOT ZERO. The stored batch size is floored to at least 1, so "0" on screen
        // would be a number nobody configured.
        composeRule.onNodeWithText("No batch size configured yet.").assertIsDisplayed()
    }

    @Test
    fun `the caption names what is still web-only, beside the one control that is not`() {
        // ⛔ THE CAPTION IS NOT DECORATION, AND ITS JOB CHANGED RATHER THAN ENDING. It used to
        // explain why there was nothing to tap; now it explains why there is only ONE thing to
        // tap. Pausing merges a single key through `campaign-status`; the goal and the batch size
        // are written by `campaign-settings`, which rebuilds all three fields from its body — so
        // editing either from a phone would mean sending all of them.
        render(ready())

        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_CAPTION_DESCRIPTION)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("The campaign goal and batch size cannot be changed in this app.")
            .assertIsDisplayed()
    }

    // ── Pausing and resuming ─────────────────────────────────────────────────

    @Test
    fun `a running campaign offers a pause and asks for the value to be WRITTEN`() {
        val campaign = CampaignRecorder()
        render(ready(), campaign = campaign)

        composeRule.onNodeWithText("Pause campaign").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_ACTION_DESCRIPTION)
            .performClick()

        // ⛔ THE TARGET VALUE, NOT A TOGGLE SIGNAL. A screen that emitted "the user tapped" would
        // leave the ViewModel to re-derive the direction from a status that may have been
        // replaced underneath the button by a background reload.
        assertEquals(listOf(false), campaign.requested)
    }

    @Test
    fun `a paused campaign offers a resume`() {
        val campaign = CampaignRecorder()
        render(ready(campaign = CampaignStatus(infiniteSdrEnabled = false)), campaign = campaign)

        composeRule.onNodeWithText("Resume campaign").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_ACTION_DESCRIPTION)
            .performClick()

        assertEquals(listOf(true), campaign.requested)
    }

    @Test
    fun `a viewer sees the campaign state and NO control at all`() {
        // ⛔ THE OPPOSITE TREATMENT FROM THE PER-WORKFLOW SWITCH, DELIBERATELY. The switch is drawn
        // DISABLED for a viewer because its position is the answer they came for; the campaign
        // button carries no state — the badge above it does — so a disabled one would be a second,
        // weaker way of saying what the viewer caption already says in words.
        render(ready(), canToggle = false)

        composeRule.onNodeWithText("Active").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_ACTION_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithText("Pause campaign").assertDoesNotExist()
        composeRule
            .onNodeWithText(
                "You are in this workspace as a viewer. Ask an agency or client member to change the campaign.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `a write in flight disables the control rather than removing it`() {
        // ⚠️ Hiding it would make the card appear to lose its only control for the length of a
        // round trip, which reads as the tap having broken something.
        render(ready(campaignPending = true))

        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_ACTION_DESCRIPTION)
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Saving…").assertIsDisplayed()
    }

    @Test
    fun `the pause confirmation names the consequence and commits only on submit`() {
        // ⛔ NOT "ARE YOU SURE". A pause stops an engine that is working through a contact list
        // right now, and the sentence says so.
        val campaign = CampaignRecorder()
        render(ready(campaignConfirm = CampaignConfirm(enable = false)), campaign = campaign)

        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_CONFIRM_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Pause the outbound campaign?").assertIsDisplayed()

        assertEquals("nothing is committed by opening the dialog", 0, campaign.confirms)
        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_CONFIRM_SUBMIT_DESCRIPTION)
            .performClick()
        assertEquals(1, campaign.confirms)
    }

    @Test
    fun `the resume confirmation says it starts spending, and backing out writes nothing`() {
        val campaign = CampaignRecorder()
        render(
            ready(
                campaign = CampaignStatus(infiniteSdrEnabled = false),
                campaignConfirm = CampaignConfirm(enable = true),
            ),
            campaign = campaign,
        )

        composeRule.onNodeWithText("Resume the outbound campaign?").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "The SDR engine starts calling and messaging your contacts again, which spends " +
                    "call and message credit.",
            )
            .assertIsDisplayed()

        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_CANCEL_DESCRIPTION)
            .performClick()

        assertEquals(1, campaign.dismissals)
        assertEquals("backing out must not commit", 0, campaign.confirms)
    }

    @Test
    fun `a refused pause is shown BESIDE a status that is still correct`() {
        // ⛔ THE CARD KEEPS THE LAST STATE THE SERVER SENT. A refused pause left the campaign
        // running, and "is my campaign running" is exactly the question the operator has at that
        // moment — replacing the card with the failure would take the answer away with it.
        render(
            ready(
                campaignFailure = FailureText(
                    UiText.Literal("You do not have access to this workspace."),
                    retryable = false,
                ),
            ),
        )

        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_CHANGE_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("You do not have access to this workspace.").assertIsDisplayed()
        composeRule.onNodeWithText("Active").assertIsDisplayed()
        composeRule.onNodeWithText("25 contacts per batch").assertIsDisplayed()
    }

    @Test
    fun `a viewer is told who can change the campaign rather than sent to the web`() {
        // ⚠️ Sending a viewer to the web dashboard points them at something that will also refuse
        // them — the same reasoning the marketplace's read-only caption carries.
        render(ready(), canToggle = false)

        composeRule
            .onNodeWithText(
                "You are in this workspace as a viewer. Ask an agency or client member to " +
                    "change the campaign.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `a campaign failure does not hide the workflow list`() {
        render(
            WorkflowsUiState(
                campaign = CampaignState.Failed(FailureText(UiText.Literal("Server error"))),
                workflows = WorkflowListState.Ready(listOf(active)),
            ),
        )

        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Missed-call follow-up").assertIsDisplayed()
    }

    // ── The workflow list ────────────────────────────────────────────────────

    @Test
    fun `each workflow shows its name, its trigger label and its latest run`() {
        render(ready())

        composeRule.onNodeWithText("Missed-call follow-up").assertIsDisplayed()
        composeRule.onNodeWithText("After a missed call").assertIsDisplayed()
        composeRule.onNodeWithText("New contact welcome").assertIsDisplayed()
        composeRule.onNodeWithText("New contact").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(workflowLatestRunDescription("wf-active"))
            .assertIsDisplayed()
    }

    @Test
    fun `a workflow that has never run says so rather than showing nothing`() {
        // ⛔ EVERY WORKFLOW IS IN THIS STATE UNTIL ITS TRIGGER FIRES. A blank there reads as a
        // missing value rather than as the answer.
        render(ready())

        composeRule.onNodeWithText("Never run").assertIsDisplayed()
    }

    @Test
    fun `an unknown trigger falls back to its raw wire value`() {
        // ⛔ THE SERVER'S TRIGGER VOCABULARY HAS ALREADY GROWN ONCE. An installed build has to keep
        // drawing a workflow whose trigger it has never heard of — a `when` with no else, or a
        // label reading "Unknown", would turn a server-side addition into a screen that lies about
        // existing automation.
        render(ready(workflows = listOf(active.copy(trigger = "invoice_overdue"))))

        composeRule.onNodeWithText("invoice_overdue").assertIsDisplayed()
    }

    @Test
    fun `an empty list is an explanatory empty state, not a failure`() {
        render(ready(workflows = emptyList()))

        composeRule.onNodeWithContentDescription(WORKFLOWS_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("No workflows yet").assertIsDisplayed()
    }

    @Test
    fun `a list failure offers a retry when retrying could help`() {
        var retried = 0
        render(
            WorkflowsUiState(
                campaign = CampaignState.Ready(runningCampaign),
                workflows = WorkflowListState.Failed(FailureText(UiText.Literal("Server error"))),
            ),
            onRetry = { retried += 1 },
        )

        composeRule
            .onNodeWithContentDescription(WORKFLOWS_LIST_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `a non-retryable failure offers no retry`() {
        // ⚠️ A role refusal and a contract mismatch produce the identical failure on a second
        // attempt, so a button there is a control that cannot work.
        render(
            WorkflowsUiState(
                campaign = CampaignState.Ready(runningCampaign),
                workflows = WorkflowListState.Failed(
                    FailureText(UiText.Literal("Insufficient permissions"), retryable = false),
                ),
            ),
        )

        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    // ── The switch ───────────────────────────────────────────────────────────

    @Test
    fun `the switch reports the workflow's state and reports a change`() {
        var changed: Pair<String, Boolean>? = null
        render(ready(), onSetActive = { id, value -> changed = id to value })

        composeRule
            .onNodeWithContentDescription(workflowToggleDescription("wf-active"))
            .assertIsEnabled()
            .performClick()

        assertEquals("wf-active" to false, changed)
    }

    @Test
    fun `a viewer sees the state but cannot change it`() {
        // ⛔ DISABLED RATHER THAN HIDDEN, WHICH IS THE OPPOSITE CALL FROM THE WORKSPACE-SETTINGS
        // ENTRY. There, the destination is hidden because every route behind it 403s a viewer and
        // the screen would be empty. Here the STATE is the content — a viewer opened this to find
        // out whether the follow-up automation is running — so removing the switch would remove
        // the answer along with the control.
        render(ready(), canToggle = false)

        composeRule
            .onNodeWithContentDescription(workflowToggleDescription("wf-active"))
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Missed-call follow-up").assertIsDisplayed()
    }

    @Test
    fun `a switch with a write in flight is disabled`() {
        render(ready(pendingToggles = setOf("wf-active")))

        composeRule
            .onNodeWithContentDescription(workflowToggleDescription("wf-active"))
            .assertIsNotEnabled()
        // ⚠️ Only ITS OWN. Two switches touch different rows and nothing re-reads, so a write on
        // one must not freeze the other.
        composeRule
            .onNodeWithContentDescription(workflowToggleDescription("wf-paused"))
            .assertIsEnabled()
    }

    @Test
    fun `a refused toggle is shown beside the list rather than instead of it`() {
        var dismissed = 0
        render(
            ready(toggleFailure = FailureText(UiText.Literal("Insufficient permissions"))),
            onDismissToggleFailure = { dismissed += 1 },
        )

        composeRule
            .onNodeWithContentDescription(WORKFLOWS_TOGGLE_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Missed-call follow-up").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `tapping a row asks to expand it`() {
        var expanded: String? = null
        render(ready(), onToggleExpanded = { expanded = it })

        composeRule
            .onNodeWithContentDescription(workflowRowDescription("wf-paused"))
            .performClick()

        assertEquals("wf-paused", expanded)
    }

    // ── Run history ──────────────────────────────────────────────────────────

    @Test
    fun `an expanded run shows its outcomes and its skip reasons`() {
        // ⛔ THE REASON IS THE MOST USEFUL LINE ON THE CARD. The engine attaches it when it SKIPS an
        // action — no contact, no phone number, missing metadata — which is exactly the case where
        // the outcome alone tells an operator nothing they can act on.
        render(
            ready(
                expanded = "wf-active",
                runs = mapOf(
                    "wf-active" to RunHistory(
                        runs = listOf(
                            WorkflowRun(
                                id = "run-partial",
                                workflowId = "wf-active",
                                trigger = "call_ended_unanswered",
                                status = "partial",
                                startedAt = "2026-08-18T11:30:00.000Z",
                                finishedAt = "2026-08-18T11:30:01.000Z",
                                actionResults = listOf(
                                    WorkflowActionResult(type = "send_sms", outcome = "ok"),
                                    WorkflowActionResult(
                                        type = "send_email",
                                        outcome = "skipped",
                                        reason = "contact has no email address",
                                    ),
                                ),
                            ),
                        ),
                        total = 1,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(WORKFLOWS_RUNS_DESCRIPTION).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(runCardDescription("run-partial"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("send_email").assertIsDisplayed()
        composeRule.onNodeWithText("contact has no email address").assertIsDisplayed()
    }

    @Test
    fun `a whole-run failure with no actions still explains itself`() {
        // ⛔ THE ENGINE CAN THROW BEFORE ANY ACTION EXECUTES, which produces a `failed` run with an
        // EMPTY `actionResults`. A card showing only the per-action rows would draw that as a
        // failure with no explanation whatsoever.
        render(
            ready(
                expanded = "wf-active",
                runs = mapOf(
                    "wf-active" to RunHistory(
                        runs = listOf(
                            WorkflowRun(
                                id = "run-failed",
                                workflowId = "wf-active",
                                trigger = "call_ended_unanswered",
                                status = "failed",
                                startedAt = "2026-08-17T16:40:00.000Z",
                                finishedAt = null,
                                error = "notify_ops webhook returned 502",
                            ),
                        ),
                        total = 1,
                    ),
                ),
            ),
        )

        composeRule
            .onNodeWithContentDescription(runErrorDescription("run-failed"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("notify_ops webhook returned 502").assertIsDisplayed()
        // ⚠️ And a run with no finish time says so rather than leaving a gap where a time goes.
        composeRule.onNodeWithText("Did not finish").assertIsDisplayed()
    }

    @Test
    fun `load more appears only while the server says there is more`() {
        var loadedMore = 0
        render(
            ready(
                expanded = "wf-active",
                runs = mapOf(
                    "wf-active" to RunHistory(
                        runs = listOf(
                            WorkflowRun(id = "run-1", workflowId = "wf-active", status = "success"),
                        ),
                        total = 9,
                        hasMore = true,
                    ),
                ),
            ),
            onLoadMoreRuns = { loadedMore += 1 },
        )

        composeRule.onNodeWithContentDescription(WORKFLOWS_RUNS_MORE_DESCRIPTION).performClick()
        assertEquals(1, loadedMore)
    }

    @Test
    fun `no load more control once the history is complete`() {
        render(
            ready(
                expanded = "wf-active",
                runs = mapOf(
                    "wf-active" to RunHistory(
                        runs = listOf(
                            WorkflowRun(id = "run-1", workflowId = "wf-active", status = "success"),
                        ),
                        total = 1,
                        hasMore = false,
                    ),
                ),
            ),
        )

        composeRule
            .onNodeWithContentDescription(WORKFLOWS_RUNS_MORE_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `a workflow with no runs says so rather than looking broken`() {
        // ⚠️ Reachable only once a read SUCCEEDED, so it means this workflow has never fired —
        // never "we could not look", which is the failure branch below.
        render(ready(expanded = "wf-paused", runs = mapOf("wf-paused" to RunHistory(total = 0))))

        composeRule.onNodeWithText("This workflow has not run yet.").assertIsDisplayed()
    }

    @Test
    fun `a failed page keeps the rows already on screen`() {
        render(
            ready(
                expanded = "wf-active",
                runs = mapOf(
                    "wf-active" to RunHistory(
                        runs = listOf(
                            WorkflowRun(id = "run-1", workflowId = "wf-active", status = "success"),
                        ),
                        total = 9,
                        hasMore = true,
                        failure = FailureText(UiText.Literal("Server error")),
                    ),
                ),
            ),
        )

        composeRule
            .onNodeWithContentDescription(runCardDescription("run-1"))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(WORKFLOWS_RUNS_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    @Test
    fun `the loading state draws skeletons in the shape of the content`() {
        render(WorkflowsUiState())

        composeRule.onNodeWithContentDescription(WORKFLOWS_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKFLOWS_LOADING_DESCRIPTION).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(WORKFLOWS_CAMPAIGN_LOADING_DESCRIPTION)
            .assertIsDisplayed()
    }
}
