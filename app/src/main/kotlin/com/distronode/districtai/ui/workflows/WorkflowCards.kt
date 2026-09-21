package com.distronode.districtai.ui.workflows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.CampaignStatus
import com.distronode.districtai.core.model.WorkflowListItem
import com.distronode.districtai.core.model.WorkflowRun
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve

/**
 * The always-on SDR campaign: its state, and the one control that may change it.
 *
 * ⛔ EXACTLY ONE FIELD IS WRITABLE HERE, AND THE CAPTION SAYS WHERE THE OTHER TWO ARE CHANGED.
 * This card used to carry no control at all, because the only candidate route
 * (`PATCH workspace/campaign-settings`) rebuilds all three SDR fields from its body and would wipe
 * the goal text on a partial one. `PATCH workspace/campaign-status` merges instead, so pausing is
 * now safe — the batch size and the goal are still web-only, and saying so is what stops the
 * single button reading as a half-built settings form.
 *
 * ⛔ A VIEWER SEES THE STATE AND NO BUTTON. The GET admits `viewer` and the PATCH does not, so the
 * state is exactly what they came for and the control is the only thing withheld — presence rather
 * than absence, the same call the per-workflow switch makes.
 *
 * ⚠️ "Paused" RATHER THAN "Off", AND "No goal configured yet." RATHER THAN A BLANK. A workspace
 * that has never opened the campaigns tab and one that deliberately switched the campaign off are
 * the SAME state on the wire — the route normalises both to `false` — so the copy has to be true
 * of both. "Not set up" would be wrong for the second and "Off" understates the first.
 *
 * @param pending a pause or resume is in flight. The button is disabled rather than hidden: the
 *   card must not appear to lose its control for the length of a round trip.
 * @param failure ⚠️ SHOWN BESIDE A STATUS THAT IS STILL THE LAST ONE THE SERVER SENT, never
 *   instead of it. A refused pause leaves the campaign exactly as it was, and that is the answer
 *   the operator needs most at that moment.
 */
@Composable
internal fun SdrCampaignCard(
    status: CampaignStatus,
    canToggle: Boolean,
    pending: Boolean,
    failure: FailureText?,
    onRequestChange: (Boolean) -> Unit,
) {
    DistrictCard(
        modifier = Modifier.semantics { contentDescription = WORKFLOWS_CAMPAIGN_DESCRIPTION },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(
                text = stringResource(R.string.workflows_campaign_label),
                modifier = Modifier.weight(1f),
            )
            DistrictBadge(
                text = stringResource(
                    if (status.infiniteSdrEnabled) {
                        R.string.workflows_campaign_active
                    } else {
                        R.string.workflows_campaign_paused
                    },
                ),
                tone = if (status.infiniteSdrEnabled) Tone.Success else Tone.Neutral,
                modifier = Modifier.semantics {
                    contentDescription = WORKFLOWS_CAMPAIGN_BADGE_DESCRIPTION
                },
            )
        }
        Text(
            // ⛔ NULL IS NOT ZERO. The route sends null rather than 0 because the settings PATCH
            // floors the value to at least 1, so a 0 could never have been stored and printing one
            // would be a batch size nobody configured.
            text = status.sdrBatchSize
                ?.let { stringResource(R.string.workflows_campaign_batch, it) }
                ?: stringResource(R.string.workflows_campaign_batch_unset),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        Text(
            text = status.sdrCampaignGoal ?: stringResource(R.string.workflows_campaign_no_goal),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier
                .padding(top = DistrictTheme.spacing.hairline)
                .semantics { contentDescription = WORKFLOWS_CAMPAIGN_GOAL_DESCRIPTION },
        )
        Text(
            text = stringResource(
                if (canToggle) {
                    R.string.workflows_campaign_read_only
                } else {
                    R.string.workflows_campaign_read_only_viewer
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier
                .padding(top = DistrictTheme.spacing.tight)
                .semantics { contentDescription = WORKFLOWS_CAMPAIGN_CAPTION_DESCRIPTION },
        )

        // ⛔ THE ONLY WRITE ON THIS CARD, AND IT IS DRAWN ONLY FOR A ROLE THE ROUTE ADMITS. A
        // disabled button here (the switch's treatment) would be a second, weaker way of saying
        // what the viewer caption above already says in words.
        if (canToggle) {
            DistrictButton(
                text = stringResource(
                    when {
                        pending -> R.string.workflows_campaign_working
                        status.infiniteSdrEnabled -> R.string.workflows_campaign_pause
                        else -> R.string.workflows_campaign_resume
                    },
                ),
                // ⚠️ THE TARGET VALUE, NOT A TOGGLE SIGNAL. The label and the request are derived
                // from the same expression, so a card rendered from a stale status cannot ask for
                // one thing while saying another.
                onClick = { onRequestChange(!status.infiniteSdrEnabled) },
                variant = if (status.infiniteSdrEnabled) ButtonVariant.Secondary else ButtonVariant.Primary,
                enabled = !pending,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = WORKFLOWS_CAMPAIGN_ACTION_DESCRIPTION },
            )
        }

        failure?.let {
            Text(
                text = it.message.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = WORKFLOWS_CAMPAIGN_CHANGE_FAILURE_DESCRIPTION },
            )
        }
    }
}

/**
 * One workflow: what fires it, whether it is on, and how its last run went.
 *
 * ⛔ THE SWITCH IS DISABLED FOR A VIEWER RATHER THAN HIDDEN, WHICH IS THE OPPOSITE CALL FROM THE
 * WORKSPACE-SETTINGS ENTRY ON THE OVERVIEW. There, the whole destination is hidden because every
 * route behind it 403s a viewer and the screen would be empty. Here the STATE is the content — a
 * viewer opened this to find out whether the follow-up automation is running — so removing the
 * switch would remove the answer along with the control.
 *
 * ⚠️ THE ROW ITSELF EXPANDS AND THE SWITCH DOES NOT. Two actions on one row need two targets; a
 * switch that also expanded would make every attempt to read the history flip a live workflow.
 *
 * ⚠️ AN UNRECOGNISED TRIGGER IS SHOWN BY ITS RAW WIRE VALUE. The server's vocabulary has already
 * grown once and an installed build has to keep drawing a workflow it does not fully understand —
 * see [triggerLabelRes].
 */
@Composable
internal fun WorkflowRow(
    workflow: WorkflowListItem,
    expanded: Boolean,
    canToggle: Boolean,
    pending: Boolean,
    onToggleExpanded: () -> Unit,
    onSetActive: (Boolean) -> Unit,
) {
    DistrictListRow(
        title = workflow.name,
        subtitle = triggerLabelRes(workflow.trigger)
            ?.let { stringResource(it) }
            ?: workflow.trigger,
        onClick = onToggleExpanded,
        modifier = Modifier.semantics {
            contentDescription = workflowRowDescription(workflow.id)
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                LatestRunBadge(workflow)
                Switch(
                    checked = workflow.active,
                    onCheckedChange = onSetActive,
                    // ⚠️ Disabled while its own write is in flight, not while ANY is: two switches
                    // touch two different rows and nothing re-reads, so they do not race.
                    enabled = canToggle && !pending,
                    modifier = Modifier.semantics {
                        contentDescription = workflowToggleDescription(workflow.id)
                    },
                )
            }
        },
    )
    if (expanded) {
        Eyebrow(
            text = stringResource(R.string.workflows_runs_heading),
            modifier = Modifier.padding(
                start = DistrictTheme.spacing.gutter,
                top = DistrictTheme.spacing.tight,
            ),
        )
    }
}

/**
 * The last run's outcome, or nothing at all.
 *
 * ⛔ "Never run" IS A REAL AND COMMON STATE, and it is shown rather than left blank. `latestRun` is
 * an explicit null for a workflow nobody has triggered yet, which is every workflow for as long as
 * it takes its trigger to fire — a blank there would read as a missing value rather than as an
 * answer.
 */
@Composable
private fun LatestRunBadge(workflow: WorkflowListItem) {
    val run = workflow.latestRun
    if (run == null) {
        DistrictBadge(
            text = stringResource(R.string.workflows_never_run),
            tone = Tone.Neutral,
            modifier = Modifier.semantics {
                contentDescription = workflowLatestRunDescription(workflow.id)
            },
        )
        return
    }
    Column(horizontalAlignment = Alignment.End) {
        DistrictBadge(
            text = run.status,
            tone = runTone(run.status),
            modifier = Modifier.semantics {
                contentDescription = workflowLatestRunDescription(workflow.id)
            },
        )
        Text(
            text = formatRunTimestamp(run.startedAt),
            style = MaterialTheme.typography.labelSmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
        )
    }
}

/**
 * One workflow's run history, fetched on expand.
 *
 * ⛔ A FAILED PAGE KEEPS THE PAGES BEFORE IT. The rows already on screen are a correct answer; a
 * "load more" that failed replaces the button with a message and leaves them alone.
 *
 * ⚠️ `hasMore` DECIDES WHETHER THE BUTTON EXISTS, and it is the SERVER's flag rather than
 * `runs.size < limit` — see [RunHistory]. Deriving it here would end the list early whenever a run
 * was written between two requests.
 */
@Composable
internal fun RunHistoryPanel(history: RunHistory?, onLoadMore: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(
                start = DistrictTheme.spacing.gutter,
                end = DistrictTheme.spacing.gutter,
                bottom = DistrictTheme.spacing.tight,
            )
            .semantics { contentDescription = WORKFLOWS_RUNS_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        if (history == null || (history.loading && history.runs.isEmpty())) {
            SkeletonBlock(height = DistrictTheme.spacing.header)
            return@Column
        }

        // ⚠️ Reachable only once a read SUCCEEDED, so it means this workflow has never fired —
        // never "we could not look", which is the failure branch below.
        if (history.runs.isEmpty() && history.failure == null) {
            Text(
                text = stringResource(R.string.workflows_runs_empty),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
            )
        }

        history.runs.forEach { run -> RunCard(run) }

        history.failure?.let { failure ->
            Text(
                text = failure.message.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.semantics {
                    contentDescription = WORKFLOWS_RUNS_FAILURE_DESCRIPTION
                },
            )
        }

        if (history.hasMore) {
            DistrictButton(
                text = stringResource(R.string.workflows_runs_more),
                onClick = onLoadMore,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                enabled = !history.loading,
                modifier = Modifier.semantics {
                    contentDescription = WORKFLOWS_RUNS_MORE_DESCRIPTION
                },
            )
        }
    }
}

/**
 * One run.
 *
 * ⛔ THE WHOLE-RUN `error` AND THE PER-ACTION OUTCOMES ARE BOTH RENDERED, because a run can carry
 * either without the other. The engine can throw before any action executes, which produces a
 * `failed` run with an EMPTY `actionResults` — a card that showed only the action rows would draw
 * that as a failure with no explanation whatsoever.
 *
 * ⚠️ `finishedAt` IS NULL FOR A RUN THAT DID NOT FINISH and the card says so rather than leaving a
 * gap where a time should be.
 */
@Composable
private fun RunCard(run: WorkflowRun) {
    DistrictCard(modifier = Modifier.semantics { contentDescription = runCardDescription(run.id) }) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DistrictBadge(text = run.status, tone = runTone(run.status))
            Text(
                text = formatRunTimestamp(run.startedAt),
                style = MaterialTheme.typography.labelSmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = DistrictTheme.spacing.tight),
            )
            Text(
                text = run.finishedAt
                    ?.let { formatRunTimestamp(it) }
                    ?: stringResource(R.string.workflows_run_unfinished),
                style = MaterialTheme.typography.labelSmall,
                color = DistrictTheme.colors.mutedForeground,
            )
        }

        run.actionResults.forEach { action ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DistrictTheme.spacing.tight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                DistrictBadge(text = action.outcome, tone = outcomeTone(action.outcome))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = action.type,
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.foreground,
                    )
                    // ⛔ THE MOST USEFUL LINE ON THE CARD. The engine attaches a reason when it
                    // SKIPS an action — no contact, no phone number, missing metadata — which is
                    // exactly the case where the outcome alone tells an operator nothing they can
                    // act on.
                    action.reason?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = DistrictTheme.colors.mutedForeground,
                        )
                    }
                }
            }
        }

        run.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = runErrorDescription(run.id) },
            )
        }
    }
}

/**
 * Stable per-row test handles, derived in one place so a row and its test cannot drift apart.
 *
 * ⚠️ Functions rather than literals for the same reason `MarketplaceScreen.typeDescription` is one:
 * a description built by string concatenation in both the screen and the test is two copies of a
 * format, and the test that stops matching passes by finding nothing.
 */
internal fun workflowRowDescription(id: String): String = "district-workflow-row-$id"
internal fun workflowToggleDescription(id: String): String = "district-workflow-toggle-$id"
internal fun workflowLatestRunDescription(id: String): String = "district-workflow-latest-$id"
internal fun runCardDescription(id: String): String = "district-workflow-run-$id"
internal fun runErrorDescription(id: String): String = "district-workflow-run-error-$id"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val WORKFLOWS_CAMPAIGN_DESCRIPTION: String = "district-workflows-campaign"
const val WORKFLOWS_CAMPAIGN_BADGE_DESCRIPTION: String = "district-workflows-campaign-badge"
const val WORKFLOWS_CAMPAIGN_GOAL_DESCRIPTION: String = "district-workflows-campaign-goal"
const val WORKFLOWS_CAMPAIGN_CAPTION_DESCRIPTION: String = "district-workflows-campaign-caption"
const val WORKFLOWS_CAMPAIGN_ACTION_DESCRIPTION: String = "district-workflows-campaign-action"
const val WORKFLOWS_CAMPAIGN_CHANGE_FAILURE_DESCRIPTION: String =
    "district-workflows-campaign-change-failure"
const val WORKFLOWS_RUNS_DESCRIPTION: String = "district-workflows-runs"
const val WORKFLOWS_RUNS_MORE_DESCRIPTION: String = "district-workflows-runs-more"
const val WORKFLOWS_RUNS_FAILURE_DESCRIPTION: String = "district-workflows-runs-failure"
