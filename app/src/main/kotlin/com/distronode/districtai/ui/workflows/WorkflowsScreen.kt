package com.distronode.districtai.ui.workflows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.ui.InlineFailure
import com.distronode.districtai.ui.resolve

/**
 * The automation monitor: is the SDR campaign running, which workflows exist, and what did they do.
 *
 * ⛔ AN OVERVIEW DESTINATION RATHER THAN A WORKSPACE-SETTINGS SECTION, AND THE DISTINCTION IS WHAT
 * IT IS FOR RATHER THAN WHERE THE DATA LIVES. Every screen under `Routes.WORKSPACE_SETTINGS` is a
 * FORM whose save replaces stored configuration, and the hub in front of them excludes `viewer`
 * from every route including the read. This is a MONITOR: three of its four routes admit viewers,
 * nothing on it is authored, and the question it answers ("did the follow-up actually go out")
 * belongs beside Analytics and HQ, not beside the persona editor. Filing it under settings would
 * also have hidden it from the role most likely to be asked to check.
 *
 * ⛔ AND THE SDR CAMPAIGN CARD IS A HEADER HERE RATHER THAN A SURFACE OF ITS OWN, because
 * "campaigns" has no other surface in this client and does not warrant one: it is three read-only
 * fields. The two things share a screen because they are one question asked twice — what is this
 * workspace's automation doing right now.
 *
 * ⛔ THE CAMPAIGN CARD WRITES EXACTLY ONE FIELD, AND THE CAPTION STILL SAYS WHERE THE OTHER TWO
 * ARE CHANGED. This paragraph used to state that no pause control could exist here at all, because
 * the only candidate route (`PATCH /api/district/workspace/campaign-settings`) rebuilds all three
 * SDR fields from its body and wipes the goal text on a partial one. `PATCH
 * /api/district/workspace/campaign-status` merges instead — see `CampaignStatusResponse` — so the
 * pause is safe and the batch size and goal remain web-only.
 *
 * ⛔ AND THE PAUSE IS CONFIRMED WHILE THE PER-WORKFLOW SWITCH IS NOT. Flipping one workflow
 * changes what happens the next time its trigger fires; pausing the campaign stops an engine
 * working through a contact list now, and resuming one starts spending credit again. A
 * confirmation on every switch would train the operator to dismiss the one that matters.
 *
 * ⚠️ TWO CONTROLS, BOTH ROLE-GATED, AND THEY ARE GATED DIFFERENTLY. Both PATCH routes exclude
 * `viewer` while every read admits them. The workflow switch is DISABLED for a viewer (the state
 * is the content they came for and must stay visible); the campaign button is ABSENT, because the
 * card's caption already says in words who can change it.
 *
 * ⚠️ THE CARDS LIVE IN `WorkflowCards.kt`. This file owns the shell and the section dispatch, the
 * same split `MarketplaceScreen.kt` and `AnalyticsScreen.kt` use, for the same detekt file ceiling.
 */
@Composable
fun WorkflowsScreen(
    state: WorkflowsUiState,
    /**
     * ⚠️ COMPUTED BY THE ViewModel FROM THE ROLE, not re-derived here. One answer to "may this
     * caller toggle" means the disabled switch and the refused call site cannot disagree.
     */
    canToggle: Boolean,
    onRetry: () -> Unit,
    onToggleExpanded: (String) -> Unit,
    onLoadMoreRuns: (String) -> Unit,
    onSetActive: (String, Boolean) -> Unit,
    onDismissToggleFailure: () -> Unit,
    /** ⚠️ The three campaign callbacks are one group: ask, back out, commit. */
    campaign: CampaignCallbacks,
    onBack: () -> Unit,
) {
    // ⛔ HOISTED ABOVE THE SCAFFOLD SO IT SURVIVES THE LIST RECOMPOSING UNDERNEATH IT. Inside a
    // LazyColumn item the dialog would be torn down whenever the campaign card scrolled out of
    // the composition, which on a phone is one flick away from a confirmation vanishing mid-read.
    state.campaignConfirm?.let { confirm ->
        CampaignConfirmDialog(
            confirm = confirm,
            onDismiss = campaign.onDismissConfirm,
            onSubmit = campaign.onConfirm,
        )
    }

    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = WORKFLOWS_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.workflows_title), onBack = onBack)
        },
    ) { inset ->
        LazyColumn(
            modifier = inset.fillMaxSize(),
            // ⚠️ Padding on the CONTENT rather than the modifier: a modifier padding clips the
            // scroll container, so an expanded run panel scrolling past the bottom edge would be
            // cut rather than sliding out.
            contentPadding = PaddingValues(bottom = DistrictTheme.spacing.header),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            item { ContentContainer { CampaignSection(state, canToggle, onRetry, campaign) } }

            // ⛔ ALONGSIDE THE LIST, NEVER INSTEAD OF IT. A refused toggle must not blank the rows
            // the operator was reading — and the row it was refused on has already been reverted,
            // so the list on screen is the truth and this explains why it did not move.
            state.toggleFailure?.let { failure ->
                item {
                    ContentContainer {
                        DistrictBadge(
                            text = failure.message.resolve(),
                            tone = Tone.Danger,
                            modifier = Modifier
                                .padding(horizontal = DistrictTheme.spacing.gutter)
                                .semantics {
                                    contentDescription = WORKFLOWS_TOGGLE_FAILURE_DESCRIPTION
                                },
                        )
                        DistrictButton(
                            text = stringResource(R.string.workflows_dismiss),
                            onClick = onDismissToggleFailure,
                            variant = ButtonVariant.Ghost,
                            modifier = Modifier.padding(
                                horizontal = DistrictTheme.spacing.gutter,
                            ),
                        )
                    }
                }
            }

            item {
                ContentContainer {
                    Eyebrow(
                        text = stringResource(R.string.workflows_section),
                        modifier = Modifier.padding(
                            horizontal = DistrictTheme.spacing.gutter,
                            vertical = DistrictTheme.spacing.tight,
                        ),
                    )
                }
            }

            workflowSection(
                state = state,
                canToggle = canToggle,
                onRetry = onRetry,
                onToggleExpanded = onToggleExpanded,
                onLoadMoreRuns = onLoadMoreRuns,
                onSetActive = onSetActive,
            )
        }
    }
}

/**
 * The workflow list's three states.
 *
 * ⚠️ A `LazyListScope` EXTENSION RATHER THAN A COMPOSABLE, so each workflow is its own list item
 * and an expanded history does not force the whole list to recompose. It is also what lets the
 * `items` key be the workflow id, which is what stops an expanded panel jumping rows when the
 * list reorders.
 */
private fun LazyListScope.workflowSection(
    state: WorkflowsUiState,
    canToggle: Boolean,
    onRetry: () -> Unit,
    onToggleExpanded: (String) -> Unit,
    onLoadMoreRuns: (String) -> Unit,
    onSetActive: (String, Boolean) -> Unit,
) {
    when (val list = state.workflows) {
        WorkflowListState.Loading -> item {
            ContentContainer {
                Column(
                    modifier = Modifier
                        .padding(DistrictTheme.spacing.gutter)
                        .semantics { contentDescription = WORKFLOWS_LOADING_DESCRIPTION },
                    verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
                ) {
                    repeat(SKELETON_ROWS) { SkeletonBlock(height = DistrictTheme.spacing.header) }
                }
            }
        }

        is WorkflowListState.Failed -> item {
            ContentContainer {
                InlineFailure(
                    title = stringResource(R.string.workflows_list_failed),
                    failure = list.failure,
                    onRetry = onRetry,
                    description = WORKFLOWS_LIST_FAILURE_DESCRIPTION,
                    modifier = Modifier.padding(vertical = DistrictTheme.spacing.tight),
                )
            }
        }

        // ⛔ AN EMPTY LIST IS NOT A FAILURE AND MUST NOT LOOK LIKE ONE. Most workspaces have never
        // created a workflow; the repository's envelope guard is what separates this from "we
        // could not look", and the copy points at where one is authored rather than implying
        // something was lost.
        is WorkflowListState.Ready -> if (list.workflows.isEmpty()) {
            item {
                ContentContainer {
                    Column(
                        modifier = Modifier.semantics {
                            contentDescription = WORKFLOWS_EMPTY_DESCRIPTION
                        },
                    ) {
                        EmptyState(
                            title = stringResource(R.string.workflows_empty_title),
                            body = stringResource(R.string.workflows_empty_body),
                        )
                    }
                }
            }
        } else {
            items(list.workflows, key = { it.id }) { workflow ->
                ContentContainer {
                    WorkflowRow(
                        workflow = workflow,
                        expanded = state.expanded == workflow.id,
                        canToggle = canToggle,
                        pending = workflow.id in state.pendingToggles,
                        onToggleExpanded = { onToggleExpanded(workflow.id) },
                        onSetActive = { active -> onSetActive(workflow.id, active) },
                    )
                    if (state.expanded == workflow.id) {
                        RunHistoryPanel(
                            history = state.runs[workflow.id],
                            onLoadMore = { onLoadMoreRuns(workflow.id) },
                        )
                    }
                    DistrictRowDivider()
                }
            }
        }
    }
}

/**
 * The SDR campaign header card, in its three states.
 *
 * ⚠️ A CAMPAIGN READ THAT FAILED DOES NOT BLOCK THE WORKFLOW LIST BELOW IT — the two are separate
 * requests against separate databases, and this renders its own failure inline rather than taking
 * over the screen.
 */
@Composable
private fun CampaignSection(
    state: WorkflowsUiState,
    canToggle: Boolean,
    onRetry: () -> Unit,
    campaign: CampaignCallbacks,
) {
    when (val card = state.campaign) {
        CampaignState.Loading -> Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = WORKFLOWS_CAMPAIGN_LOADING_DESCRIPTION },
        ) {
            SkeletonBlock(height = DistrictTheme.spacing.header)
        }

        is CampaignState.Ready -> Column(
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        ) {
            SdrCampaignCard(
                status = card.status,
                canToggle = canToggle,
                pending = state.campaignPending,
                // ⚠️ THE WRITE'S FAILURE, NOT THE READ'S. A refused pause is shown ON the card
                // beside a status that is still correct; a failed READ replaces the card entirely,
                // because then there is no status to stand beside.
                failure = state.campaignFailure,
                onRequestChange = campaign.onRequestChange,
            )
        }

        is CampaignState.Failed -> Column(
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        ) {
            InlineFailure(
                title = stringResource(R.string.workflows_campaign_failed),
                failure = card.failure,
                onRetry = onRetry,
                description = WORKFLOWS_CAMPAIGN_FAILURE_DESCRIPTION,
                modifier = Modifier.padding(vertical = DistrictTheme.spacing.tight),
            )
        }
    }
}

/**
 * The pause/resume confirmation.
 *
 * ⛔ THE COPY NAMES WHAT HAPPENS IN THE UNITS THE OPERATOR THINKS IN, NOT "ARE YOU SURE". A pause
 * stops an engine that is working through a contact list right now; a resume starts spending call
 * and message credit again. Those are different sentences and different consequences, so they are
 * two sets of strings rather than one with the verb swapped.
 *
 * ⚠️ THE ACTION READS FROM [CampaignConfirm.enable], the value that will be WRITTEN, so the
 * sentence on screen and the request cannot describe different changes if the card underneath is
 * replaced by a background reload while the dialog is open.
 */
@Composable
private fun CampaignConfirmDialog(
    confirm: CampaignConfirm,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // ⚠️ The card token, not Material's default dialog surface.
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = {
            Text(
                stringResource(
                    if (confirm.enable) {
                        R.string.workflows_campaign_resume_title
                    } else {
                        R.string.workflows_campaign_pause_title
                    },
                ),
            )
        },
        text = {
            Text(
                text = stringResource(
                    if (confirm.enable) {
                        R.string.workflows_campaign_resume_body
                    } else {
                        R.string.workflows_campaign_pause_body
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.semantics {
                    contentDescription = WORKFLOWS_CAMPAIGN_CONFIRM_DESCRIPTION
                },
            )
        },
        confirmButton = {
            DistrictButton(
                text = stringResource(
                    if (confirm.enable) {
                        R.string.workflows_campaign_resume
                    } else {
                        R.string.workflows_campaign_pause
                    },
                ),
                onClick = onSubmit,
                modifier = Modifier.semantics {
                    contentDescription = WORKFLOWS_CAMPAIGN_CONFIRM_SUBMIT_DESCRIPTION
                },
            )
        },
        dismissButton = {
            DistrictButton(
                text = stringResource(R.string.workflows_campaign_cancel),
                onClick = onDismiss,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.semantics {
                    contentDescription = WORKFLOWS_CAMPAIGN_CANCEL_DESCRIPTION
                },
            )
        },
    )
}

/**
 * The campaign card's three callbacks.
 *
 * ⚠️ BUNDLED RATHER THAN PASSED INDIVIDUALLY. `WorkflowsScreen` already takes eight parameters and
 * detekt's `LongParameterList` threshold is eight — `ignoreAnnotated: ['Composable']` exempts the
 * screen itself, but the grouping is also what keeps "ask, back out, commit" legible as one unit
 * at the call site rather than as three lambdas in a row of eleven.
 */
data class CampaignCallbacks(
    val onRequestChange: (Boolean) -> Unit,
    val onDismissConfirm: () -> Unit,
    val onConfirm: () -> Unit,
)

private const val SKELETON_ROWS = 3

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val WORKFLOWS_ROOT_DESCRIPTION: String = "district-workflows-root"
const val WORKFLOWS_LOADING_DESCRIPTION: String = "district-workflows-loading"
const val WORKFLOWS_EMPTY_DESCRIPTION: String = "district-workflows-empty"
const val WORKFLOWS_LIST_FAILURE_DESCRIPTION: String = "district-workflows-list-failure"
const val WORKFLOWS_TOGGLE_FAILURE_DESCRIPTION: String = "district-workflows-toggle-failure"
const val WORKFLOWS_CAMPAIGN_LOADING_DESCRIPTION: String = "district-workflows-campaign-loading"
const val WORKFLOWS_CAMPAIGN_FAILURE_DESCRIPTION: String = "district-workflows-campaign-failure"
const val WORKFLOWS_CAMPAIGN_CONFIRM_DESCRIPTION: String = "district-workflows-campaign-confirm"
const val WORKFLOWS_CAMPAIGN_CONFIRM_SUBMIT_DESCRIPTION: String =
    "district-workflows-campaign-confirm-submit"
const val WORKFLOWS_CAMPAIGN_CANCEL_DESCRIPTION: String = "district-workflows-campaign-cancel"
