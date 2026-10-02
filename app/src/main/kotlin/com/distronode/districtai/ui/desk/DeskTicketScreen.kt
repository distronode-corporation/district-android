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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.DeskMessage
import com.distronode.districtai.core.model.DeskMessageAuthor
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.ui.FailureState
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve

/**
 * One ticket, its thread and the two writes on it.
 *
 * ⚠️ THE STATUS CONTROLS ARE NOT CONFIRM-GATED, which is a deliberate difference from HQ. Nothing
 * here is irreversible or billable: the three states are values an operator moves between freely,
 * and the server echoes the result immediately, so a mistaken tap is visible and undoable. A
 * confirmation on each would be friction charged for no risk.
 */
@Composable
fun DeskTicketScreen(
    state: DeskTicketUiState,
    draft: String,
    canUse: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSetStatus: (DeskTicketStatus) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    // ⚠️ NO `modifier` PARAMETER: the one caller (the nav graph) never sized or placed this screen.
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = DESK_TICKET_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                title = (state as? DeskTicketUiState.Content)?.ticket?.displayReference
                    ?: stringResource(R.string.desk_ticket_title),
                onBack = onBack,
            )
        },
    ) { inset ->
        Box(modifier = inset.fillMaxSize()) {
            // ⚠️ A `when` on the state itself rather than a chain of `is` tests, so the compiler
            // rather than a trailing test proves every state is drawn.
            if (!canUse) {
                TicketRefused()
            } else {
                when (state) {
                    DeskTicketUiState.Loading -> TicketLoading()
                    is DeskTicketUiState.Failed -> FailureState(
                        failure = state.failure,
                        onRetry = onRetry,
                        onSignIn = null,
                        description = DESK_TICKET_FAILURE_DESCRIPTION,
                    )
                    is DeskTicketUiState.Content ->
                        TicketLoaded(state, draft, onDraftChange, onSend, onSetStatus)
                }
            }
        }
    }
}

@Composable
private fun TicketLoaded(
    state: DeskTicketUiState.Content,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSetStatus: (DeskTicketStatus) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            item { TicketHeader(state, onSetStatus) }

            items(state.messages, key = { it.id }) { message ->
                ContentContainer { MessageBubble(message) }
            }

            state.statusFailure?.let { failure ->
                item {
                    ContentContainer {
                        InlineFailure(
                            failure,
                            Modifier.semantics { contentDescription = DESK_TICKET_STATUS_FAILURE_DESCRIPTION },
                        )
                    }
                }
            }
        }

        ReplyBox(state, draft, onDraftChange, onSend)
    }
}

@Composable
private fun TicketHeader(
    state: DeskTicketUiState.Content,
    onSetStatus: (DeskTicketStatus) -> Unit,
) {
    ContentContainer {
        DistrictCard(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
            Eyebrow(text = state.ticket.displayReference)
            Text(
                text = state.ticket.subject,
                style = MaterialTheme.typography.titleMedium,
                color = DistrictTheme.colors.foreground,
            )
            // ⚠️ Every contact detail the ticket carries, and nothing invented. A ticket raised
            // during a call may have none of the three.
            listOfNotNull(
                state.ticket.requesterName,
                state.ticket.requesterEmail,
                state.ticket.requesterPhone,
            ).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                )
            }

            Row(
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeskTicketStatus.entries.forEach { status ->
                    DistrictButton(
                        text = stringResource(statusLabel(status)),
                        onClick = { onSetStatus(status) },
                        variant = if (state.ticket.knownStatus == status) {
                            ButtonVariant.Primary
                        } else {
                            ButtonVariant.Ghost
                        },
                        // ⚠️ Disabled only while a status change is in flight — never while a
                        // REPLY is, which is a separate control with a separate flag.
                        enabled = !state.statusChanging,
                        modifier = Modifier.semantics {
                            contentDescription = "$DESK_TICKET_SET_STATUS_DESCRIPTION-${status.wire}"
                        },
                    )
                }
            }
        }
    }
}

/**
 * ⛔ THE AUTHOR IS RENDERED FROM `authorType`, WHICH THE SERVER FIXES. An operator's reply is
 * attributed to the team and a ticket they raised on a customer's behalf is attributed to the
 * CUSTOMER, because it is the customer's problem. Nothing here may relabel either.
 */
@Composable
private fun MessageBubble(message: DeskMessage) {
    val author = message.knownAuthor
    DistrictCard(
        modifier = Modifier.padding(
            horizontal = DistrictTheme.spacing.gutter,
            vertical = DistrictTheme.spacing.hairline,
        ),
    ) {
        DistrictBadge(
            text = stringResource(authorLabel(author)),
            tone = when (author) {
                DeskMessageAuthor.TEAM -> Tone.District
                DeskMessageAuthor.ASSISTANT -> Tone.Info
                // ⚠️ An unrecognised author type falls here rather than being hidden.
                DeskMessageAuthor.CUSTOMER, null -> Tone.Neutral
            },
        )
        Text(
            text = message.body,
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
        )
    }
}

@Composable
private fun ReplyBox(
    state: DeskTicketUiState.Content,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    ContentContainer {
        Column(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
            // ⛔ NULL IS "WE DO NOT KNOW", so nothing is said. The reply route omits `notified`
            // entirely on a degraded replay, and asserting "not emailed" there would be a claim
            // about the customer's inbox that we cannot support.
            when (state.lastNotified) {
                true -> NotifyNote(
                    R.string.desk_notified,
                    Modifier.semantics { contentDescription = DESK_TICKET_NOTIFIED_DESCRIPTION },
                )
                false -> NotifyNote(
                    R.string.desk_not_notified,
                    Modifier.semantics { contentDescription = DESK_TICKET_NOT_NOTIFIED_DESCRIPTION },
                )
                null -> Unit
            }

            state.sendFailure?.let {
                InlineFailure(it, Modifier.semantics { contentDescription = DESK_TICKET_SEND_FAILURE_DESCRIPTION })
            }

            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = { Text(stringResource(R.string.desk_reply_label)) },
                enabled = !state.sending,
                colors = districtFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = DESK_TICKET_REPLY_DESCRIPTION },
            )
            DistrictButton(
                text = stringResource(
                    if (state.sending) R.string.desk_reply_sending else R.string.desk_reply_send,
                ),
                onClick = onSend,
                enabled = !state.sending && draft.isNotBlank(),
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
            )
        }
    }
}

@Composable
private fun NotifyNote(textId: Int, modifier: Modifier) {
    Text(
        text = stringResource(textId),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = modifier.padding(bottom = DistrictTheme.spacing.hairline),
    )
}

@Composable
private fun InlineFailure(failure: FailureText, modifier: Modifier) {
    Text(
        text = failure.message.resolve(),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.destructive,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = DistrictTheme.spacing.hairline),
    )
}

@Composable
private fun TicketLoading() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = DESK_TICKET_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_ROWS) { SkeletonBlock(height = SKELETON_HEIGHT) }
        }
    }
}

@Composable
private fun TicketRefused() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DistrictTheme.spacing.section)
                .semantics { contentDescription = DESK_TICKET_REFUSED_DESCRIPTION },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.desk_viewer_body),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val SKELETON_ROWS = 6
private val SKELETON_HEIGHT = 64.dp

const val DESK_TICKET_ROOT_DESCRIPTION: String = "district-desk-ticket-root"
const val DESK_TICKET_LOADING_DESCRIPTION: String = "district-desk-ticket-loading"
const val DESK_TICKET_FAILURE_DESCRIPTION: String = "district-desk-ticket-failure"
const val DESK_TICKET_REFUSED_DESCRIPTION: String = "district-desk-ticket-refused"
const val DESK_TICKET_REPLY_DESCRIPTION: String = "district-desk-ticket-reply"
const val DESK_TICKET_SEND_FAILURE_DESCRIPTION: String = "district-desk-ticket-send-failure"
const val DESK_TICKET_STATUS_FAILURE_DESCRIPTION: String = "district-desk-ticket-status-failure"
const val DESK_TICKET_SET_STATUS_DESCRIPTION: String = "district-desk-ticket-set-status"
const val DESK_TICKET_NOTIFIED_DESCRIPTION: String = "district-desk-ticket-notified"
const val DESK_TICKET_NOT_NOTIFIED_DESCRIPTION: String = "district-desk-ticket-not-notified"
