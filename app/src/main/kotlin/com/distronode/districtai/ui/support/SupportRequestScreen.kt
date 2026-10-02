package com.distronode.districtai.ui.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.distronode.districtai.core.model.SupportMessage
import com.distronode.districtai.core.model.SupportMessageRole
import com.distronode.districtai.ui.CenteredState
import com.distronode.districtai.ui.FailureState
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve

/**
 * One request with Distronode, its conversation, and the two writes on it.
 *
 * ⛔ THE CLOSE CONTROL IS OFFERED ONLY WHEN THE SERVER SAID IT COULD BE. The desk's workflow either
 * offers no resolving transition or offers several, and in the second case picking one would decide
 * on the customer's behalf whether their request was "done" or "won't do" — so the server declines
 * and says so on the request. Offering it anyway earns a 409 that could have been avoided, and a
 * repeat leaves a second "Closed at the requester's request by …" in the thread, because the audit
 * comment is posted BEFORE the transition is applied.
 *
 * ⚠️ THE STATUS IS SHOWN AS THE DESK SPELLS IT. The live workflow is localised; substituting
 * "Closed" would print English over a status Atlassian spells in another language.
 */
@Composable
fun SupportRequestScreen(
    state: SupportRequestUiState,
    draft: String,
    canUse: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = SUPPORT_REQUEST_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                // ⚠️ The key when there is one; an unfiled request genuinely has none yet.
                title = (state as? SupportRequestUiState.Content)?.request?.issueKey
                    ?: stringResource(R.string.support_request_title),
                onBack = onBack,
            )
        },
    ) { inset ->
        Box(modifier = inset.fillMaxSize()) {
            // ⚠️ The states are matched exhaustively, so a new one cannot fall through to a blank screen.
            if (!canUse) {
                RequestRefused()
            } else {
                when (state) {
                    SupportRequestUiState.Loading -> RequestLoading()
                    // ⛔ SHOWN AS THE SERVER SENT IT. A 404 here is deliberately indistinguishable from
                    // "not yours" and "erased", so this client must not narrate a reason it does not have.
                    is SupportRequestUiState.Failed -> FailureState(
                        failure = state.failure,
                        onRetry = onRetry,
                        onSignIn = onSignIn,
                        description = SUPPORT_REQUEST_FAILURE_DESCRIPTION,
                    )
                    is SupportRequestUiState.Content ->
                        RequestLoaded(state, draft, onDraftChange, onSend, onClose)
                }
            }
        }
    }
}

@Composable
private fun RequestLoaded(
    state: SupportRequestUiState.Content,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            item { RequestHeader(state, onClose) }

            items(state.request.messages, key = { it.id }) { message ->
                ContentContainer { MessageBubble(message) }
            }

            state.closeFailure?.let { failure ->
                item {
                    ContentContainer {
                        InlineError(failure, SUPPORT_REQUEST_CLOSE_FAILURE_DESCRIPTION)
                    }
                }
            }
        }

        ReplyBox(state, draft, onDraftChange, onSend)
    }
}

@Composable
private fun RequestHeader(state: SupportRequestUiState.Content, onClose: () -> Unit) {
    val request = state.request
    ContentContainer {
        DistrictCard(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
            Eyebrow(text = request.issueKey ?: stringResource(R.string.support_unfiled))
            Text(
                text = request.subject,
                style = MaterialTheme.typography.titleMedium,
                color = DistrictTheme.colors.foreground,
            )
            DistrictBadge(
                text = request.statusName,
                tone = if (request.isResolved) Tone.Success else Tone.District,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.hairline)
                    .semantics { contentDescription = SUPPORT_REQUEST_STATUS_DESCRIPTION },
            )

            when {
                state.canClose -> DistrictButton(
                    text = stringResource(
                        if (state.closing) R.string.support_closing else R.string.support_close,
                    ),
                    onClick = onClose,
                    variant = ButtonVariant.Secondary,
                    enabled = !state.closing,
                    modifier = Modifier
                        .padding(top = DistrictTheme.spacing.tight)
                        .semantics { contentDescription = SUPPORT_REQUEST_CLOSE_DESCRIPTION },
                )
                // ⛔ SAYS WHY THE BUTTON IS ABSENT, for a request that is still open. Silence would
                // read as a missing feature rather than as the desk's own answer.
                !request.isResolved -> Text(
                    text = stringResource(R.string.support_not_closeable),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                    modifier = Modifier
                        .padding(top = DistrictTheme.spacing.tight)
                        .semantics {
                            contentDescription = SUPPORT_REQUEST_NOT_CLOSEABLE_DESCRIPTION
                        },
                )
                else -> Unit
            }
        }
    }
}

/**
 * ⛔ THE AUTHOR LABEL COMES FROM `role`, AND `author` IS A LABEL THE ROUTE SYNTHESISES RATHER THAN A
 * PERSON. The desk's own `authorName` is deliberately dropped server-side and never reaches this
 * client, so rendering [SupportMessage.author] as a name would invent an attribution the payload
 * does not carry.
 */
@Composable
private fun MessageBubble(message: SupportMessage) {
    DistrictCard(
        modifier = Modifier.padding(
            horizontal = DistrictTheme.spacing.gutter,
            vertical = DistrictTheme.spacing.hairline,
        ),
    ) {
        DistrictBadge(
            text = stringResource(
                if (message.knownRole == SupportMessageRole.AGENT) {
                    R.string.support_author_agent
                } else {
                    R.string.support_author_you
                },
            ),
            tone = if (message.knownRole == SupportMessageRole.AGENT) {
                Tone.Info
            } else {
                Tone.Neutral
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

/**
 * ⚠️ THE REPLY BOX STAYS AVAILABLE ON A RESOLVED REQUEST, AND THAT IS DELIBERATE. There is no
 * reopen endpoint — the desk's workflow exposes no single unambiguous transition back out of
 * `done`, so the server does not offer one — and replying on a closed request is the supported path.
 */
@Composable
private fun ReplyBox(
    state: SupportRequestUiState.Content,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    ContentContainer {
        Column(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
            state.sendFailure?.let { InlineError(it, SUPPORT_REQUEST_SEND_FAILURE_DESCRIPTION) }

            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = { Text(stringResource(R.string.support_reply_label)) },
                enabled = !state.sending,
                colors = districtFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = SUPPORT_REQUEST_REPLY_DESCRIPTION },
            )
            DistrictButton(
                text = stringResource(
                    if (state.sending) {
                        R.string.support_reply_sending
                    } else {
                        R.string.support_reply_send
                    },
                ),
                onClick = onSend,
                enabled = !state.sending && draft.isNotBlank(),
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = SUPPORT_REQUEST_SEND_DESCRIPTION },
            )
        }
    }
}

@Composable
private fun InlineError(failure: FailureText, description: String) {
    Text(
        text = failure.message.resolve(),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.destructive,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DistrictTheme.spacing.hairline)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun RequestLoading() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = SUPPORT_REQUEST_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_ROWS) { SkeletonBlock(height = SKELETON_HEIGHT) }
        }
    }
}

@Composable
private fun RequestRefused() {
    CenteredState(SUPPORT_REQUEST_REFUSED_DESCRIPTION) {
        Text(
            text = stringResource(R.string.support_viewer_body),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            textAlign = TextAlign.Center,
        )
    }
}

private const val SKELETON_ROWS = 6
private val SKELETON_HEIGHT = 64.dp

const val SUPPORT_REQUEST_ROOT_DESCRIPTION: String = "district-support-request-root"
const val SUPPORT_REQUEST_LOADING_DESCRIPTION: String = "district-support-request-loading"
const val SUPPORT_REQUEST_FAILURE_DESCRIPTION: String = "district-support-request-failure"
const val SUPPORT_REQUEST_REFUSED_DESCRIPTION: String = "district-support-request-refused"
const val SUPPORT_REQUEST_STATUS_DESCRIPTION: String = "district-support-request-status"
const val SUPPORT_REQUEST_CLOSE_DESCRIPTION: String = "district-support-request-close"
const val SUPPORT_REQUEST_NOT_CLOSEABLE_DESCRIPTION: String =
    "district-support-request-not-closeable"
const val SUPPORT_REQUEST_CLOSE_FAILURE_DESCRIPTION: String =
    "district-support-request-close-failure"
const val SUPPORT_REQUEST_REPLY_DESCRIPTION: String = "district-support-request-reply"
const val SUPPORT_REQUEST_SEND_DESCRIPTION: String = "district-support-request-send"
const val SUPPORT_REQUEST_SEND_FAILURE_DESCRIPTION: String = "district-support-request-send-failure"
