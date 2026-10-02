package com.distronode.districtai.ui.desk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.DeskTicketSummary
import com.distronode.districtai.ui.FailureState

/**
 * The customer desk queue.
 *
 * ⛔ FOUR DESTINATIONS FOR FOUR STATES, AND THE ONE THAT MATTERS IS [DeskUiState.Disabled]. A
 * disabled desk is not an empty one: nothing is being recorded, so "no customer has ever contacted
 * you" would be a claim with no basis. It gets its own screen with its own action.
 *
 * ⚠️ THE FILTER CHIPS CARRY COUNTS OVER THE WHOLE QUEUE AND FILTER LOCALLY. Filtering server-side
 * would mean one request per chip, and the counts could then disagree with each other between
 * responses.
 */
@Composable
fun DeskScreen(
    state: DeskUiState,
    canUse: Boolean,
    onOpenTicket: (DeskTicketSummary) -> Unit,
    onFilter: (DeskTicketStatus?) -> Unit,
    onCompose: () -> Unit,
    onEnable: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    // ⚠️ NO `modifier` PARAMETER: the one caller (the nav graph) never sized or placed this screen.
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = DESK_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.desk_title),
                onBack = onBack,
                actions = {
                    // ⚠️ Offered only to a role the settings routes admit, which is the same role
                    // the queue admits — so in practice it is present whenever this screen is.
                    if (canUse) {
                        DistrictButton(
                            text = stringResource(R.string.desk_settings_open),
                            onClick = onSettings,
                            variant = ButtonVariant.Ghost,
                        )
                    }
                },
            )
        },
    ) { inset ->
        Box(modifier = inset.fillMaxSize()) {
            // ⛔ NO REQUEST IS SPENT FOR A VIEWER. Every route behind this screen refuses them,
            // reads included, so there is no version of it they could be shown.
            if (!canUse) {
                DeskRefused()
            } else {
                // ⚠️ A `when` on the state itself rather than a chain of `is` tests, so the
                // compiler rather than a trailing test proves every state is drawn.
                when (state) {
                    DeskUiState.Loading -> DeskLoading()
                    is DeskUiState.Disabled -> DeskDisabled(onEnable)
                    is DeskUiState.Failed -> FailureState(
                        failure = state.failure,
                        onRetry = onRetry,
                        onSignIn = null,
                        description = DESK_FAILURE_DESCRIPTION,
                    )
                    is DeskUiState.Content -> DeskLoaded(state, onOpenTicket, onFilter, onCompose)
                }
            }
        }
    }
}

@Composable
private fun DeskLoaded(
    state: DeskUiState.Content,
    onOpenTicket: (DeskTicketSummary) -> Unit,
    onFilter: (DeskTicketStatus?) -> Unit,
    onCompose: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.refreshing) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = DistrictTheme.colors.district,
                    trackColor = DistrictTheme.colors.muted,
                )
            }
        }

        item {
            ContentContainer {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DistrictTheme.spacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DeskTicketStatus.entries.forEach { status ->
                        FilterChip(
                            status = status,
                            count = state.countOf(status),
                            selected = state.filter == status,
                            onClick = { onFilter(status) },
                        )
                    }
                }
                DistrictButton(
                    text = stringResource(R.string.desk_compose_title),
                    onClick = onCompose,
                    modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
                )
            }
        }

        if (state.queueEmpty) {
            item { DeskEmpty() }
        } else if (state.visible.isEmpty()) {
            item {
                ContentContainer {
                    Text(
                        text = stringResource(R.string.desk_filter_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.mutedForeground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(DistrictTheme.spacing.section)
                            .semantics { contentDescription = DESK_FILTER_EMPTY_DESCRIPTION },
                    )
                }
            }
        }

        // ⚠️ KEYED ON THE TICKET'S UUID, never on `displayReference` — the reference is per
        // workspace and is a string the server formats, and a duplicate key in a LazyColumn is a
        // crash rather than a cosmetic repeat.
        items(state.visible, key = { it.id }) { ticket ->
            ContentContainer {
                TicketRow(ticket = ticket, onClick = { onOpenTicket(ticket) })
                DistrictRowDivider()
            }
        }

        // ⚠️ STATED RATHER THAN IMPLIED. The route caps at 100 with no cursor, so a busy desk
        // silently loses its oldest tickets from this list.
        if (state.tickets.size >= QUEUE_CAP) {
            item {
                ContentContainer {
                    Text(
                        text = stringResource(R.string.desk_capped),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.mutedForeground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(DistrictTheme.spacing.section)
                            .semantics { contentDescription = DESK_CAPPED_DESCRIPTION },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    status: DeskTicketStatus,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DistrictButton(
        text = "${stringResource(statusLabel(status))} $count",
        onClick = onClick,
        variant = if (selected) ButtonVariant.Primary else ButtonVariant.Ghost,
        modifier = Modifier.semantics {
            contentDescription = "$DESK_FILTER_DESCRIPTION-${status.wire}"
        },
    )
}

@Composable
private fun TicketRow(ticket: DeskTicketSummary, onClick: () -> Unit) {
    DistrictListRow(
        title = ticket.subject,
        // ⚠️ The requester, or an explicit "no contact details" — a ticket raised during a call may
        // genuinely have none, and a blank line reads as a rendering fault.
        subtitle = ticket.requesterName
            ?: ticket.requesterEmail
            ?: ticket.requesterPhone
            ?: stringResource(R.string.desk_no_requester),
        onClick = onClick,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (ticket.fromCall) {
                    DistrictBadge(
                        text = stringResource(R.string.desk_from_call),
                        tone = Tone.Info,
                    )
                }
                DistrictBadge(
                    text = ticket.knownStatus?.let { stringResource(statusLabel(it)) }
                        // ⚠️ An unrecognised status renders as ITSELF rather than being hidden or
                        // guessed. The column is plain TEXT and a new state must not make a ticket
                        // unreadable on an installed build.
                        ?: ticket.status,
                    tone = toneFor(ticket.knownStatus),
                    modifier = Modifier.semantics {
                        contentDescription = DESK_STATUS_DESCRIPTION
                    },
                )
            }
        },
    )
}

@Composable
private fun DeskDisabled(onEnable: () -> Unit) {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DistrictTheme.spacing.section)
                .semantics { contentDescription = DESK_DISABLED_DESCRIPTION },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(
                title = stringResource(R.string.desk_disabled_title),
                body = stringResource(R.string.desk_disabled_body),
                action = {
                    DistrictButton(
                        text = stringResource(R.string.desk_enable),
                        onClick = onEnable,
                    )
                },
            )
        }
    }
}

@Composable
private fun DeskEmpty() {
    ContentContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DistrictTheme.spacing.section)
                .semantics { contentDescription = DESK_EMPTY_DESCRIPTION },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(
                title = stringResource(R.string.desk_empty_title),
                body = stringResource(R.string.desk_empty),
            )
        }
    }
}

/**
 * ⛔ SAYS WHY, NOT JUST NO. Every desk route refuses a viewer including the reads, which is unusual
 * enough that an operator would otherwise assume a fault.
 */
@Composable
private fun DeskRefused() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DistrictTheme.spacing.section)
                .semantics { contentDescription = DESK_REFUSED_DESCRIPTION },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(
                title = stringResource(R.string.desk_viewer_title),
                body = stringResource(R.string.desk_viewer_body),
            )
        }
    }
}

@Composable
private fun DeskLoading() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = DESK_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_ROWS) { SkeletonBlock(height = SKELETON_ROW_HEIGHT) }
        }
    }
}

private const val SKELETON_ROWS = 8
private val SKELETON_ROW_HEIGHT = 56.dp

/** ⚠️ The route's own cap. Reaching it is what the "showing 100" note is keyed on. */
private const val QUEUE_CAP = 100

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val DESK_ROOT_DESCRIPTION: String = "district-desk-root"
const val DESK_LOADING_DESCRIPTION: String = "district-desk-loading"
const val DESK_EMPTY_DESCRIPTION: String = "district-desk-empty"
const val DESK_DISABLED_DESCRIPTION: String = "district-desk-disabled"
const val DESK_REFUSED_DESCRIPTION: String = "district-desk-refused"
const val DESK_FAILURE_DESCRIPTION: String = "district-desk-failure"
const val DESK_FILTER_DESCRIPTION: String = "district-desk-filter"
const val DESK_FILTER_EMPTY_DESCRIPTION: String = "district-desk-filter-empty"
const val DESK_CAPPED_DESCRIPTION: String = "district-desk-capped"
const val DESK_STATUS_DESCRIPTION: String = "district-desk-status"
