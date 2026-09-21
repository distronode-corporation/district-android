package com.distronode.districtai.ui.hq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.HqPendingWrite
import com.distronode.districtai.ui.resolve

/**
 * District HQ — the agentic console.
 *
 * ⛔ THE TRANSCRIPT IS DRAWN FROM ITS OWN LIST, NOT FROM [state]. A failed turn must leave the
 * conversation on screen: the server keeps none of it, so a screen that blanked on failure would
 * destroy the only copy. See the ⛔ on [HqViewModel].
 *
 * ⛔ AND THE COMPOSER STAYS OPEN FOR EVERY ROLE, INCLUDING `viewer`. Reads admit viewers; only
 * writes do not, and the server declines those before a proposal is ever made — so a viewer simply
 * never sees a confirm card. Hiding the input from them would remove the half of the feature they
 * are entitled to.
 */
@Composable
fun HqScreen(
    state: HqUiState,
    messages: List<HqMessage>,
    canConfirm: Boolean,
    onSend: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = HQ_ROOT_DESCRIPTION },
        topBar = { DistrictTopBar(title = stringResource(R.string.hq_title), onBack = onBack) },
    ) { inset ->
        Column(modifier = inset.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    ContentContainer(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(DistrictTheme.spacing.section)
                                .semantics { contentDescription = HQ_EMPTY_DESCRIPTION },
                            verticalArrangement = Arrangement.Center,
                        ) {
                            EmptyState(
                                title = stringResource(R.string.hq_empty_title),
                                body = stringResource(R.string.hq_empty),
                            )
                        }
                    }
                } else {
                    Transcript(messages)
                }
            }

            if (state is HqUiState.Thinking) {
                ThinkingRow()
            }

            // ⚠️ The card is rendered for Applying and ConfirmFailed too, so the operator can always
            // see WHAT is being applied — including after it failed, where the summary is the only
            // record of what was attempted.
            state.pendingWrite()?.let { pending ->
                ConfirmCard(
                    pending = pending,
                    state = state,
                    canConfirm = canConfirm,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                )
            }

            if (state is HqUiState.Failed) {
                FailureRow(
                    message = state.failure.message.resolve(),
                    retryable = state.failure.retryable,
                    onRetry = onRetry,
                )
            }

            Composer(enabled = state !is HqUiState.Thinking && state !is HqUiState.Applying, onSend = onSend)
        }
    }
}

/**
 * The proposal this state is about, if any.
 *
 * ⚠️ Three states carry one and two do not, and the card's presence is the operator's only signal
 * that a change is pending — so it is derived in ONE place rather than re-branched at each use.
 */
private fun HqUiState.pendingWrite() = when (this) {
    is HqUiState.Confirming -> pending
    is HqUiState.Applying -> pending
    is HqUiState.ConfirmFailed -> pending
    HqUiState.Idle, HqUiState.Thinking, is HqUiState.Failed -> null
}

/**
 * ⚠️ KEYED BY INDEX, WHICH IS NORMALLY WRONG AND IS RIGHT HERE. Transcript lines have no server id,
 * and two identical answers are genuinely two lines; a content-derived key would collide and drop
 * one. The list is append-only — nothing is ever inserted or reordered — so the index is stable for
 * the lifetime of every row.
 */
@Composable
private fun Transcript(messages: List<HqMessage>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(messages) { index, message ->
            ContentContainer {
                MessageRow(message, isLast = index == messages.lastIndex)
            }
        }
    }
}

/**
 * ⚠️ ALIGNMENT CARRIES WHO SPOKE, the same way the Inbox thread does it: the operator sits right on
 * the accent, the console sits left on the muted fill. The console's answers are markdown, and they
 * are rendered as PLAIN TEXT deliberately — a half-implemented markdown renderer that dropped a
 * table or mangled a list would misreport the workspace's own data, which is the one thing this
 * screen exists to state accurately.
 */
@Composable
private fun MessageRow(message: HqMessage, isLast: Boolean) {
    val fromOperator = message.role == HqRole.OPERATOR
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = DistrictTheme.spacing.gutter,
                vertical = DistrictTheme.spacing.hairline,
            ),
        horizontalArrangement = if (fromOperator) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .background(
                    color = if (fromOperator) {
                        DistrictTheme.colors.district.copy(alpha = BUBBLE_ACCENT_ALPHA)
                    } else {
                        DistrictTheme.colors.muted
                    },
                    shape = RoundedCornerShape(BUBBLE_RADIUS),
                )
                .padding(DistrictTheme.spacing.row)
                .semantics {
                    // ⚠️ Only the newest line gets the stable handle: a test asserting "the answer"
                    // must not match six of them.
                    if (isLast) contentDescription = HQ_LATEST_MESSAGE_DESCRIPTION
                },
        ) {
            Text(
                text = message.text.resolve(),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
            )
        }
    }
}

@Composable
private fun ThinkingRow() {
    ContentContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = DistrictTheme.spacing.gutter,
                    vertical = DistrictTheme.spacing.tight,
                )
                .semantics { contentDescription = HQ_THINKING_DESCRIPTION },
        ) {
            Text(
                text = stringResource(R.string.hq_thinking),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
            )
        }
    }
}

/**
 * The confirm gate.
 *
 * ⛔ THE SUMMARY IS THE SUBJECT, NOT THE TOOL NAME. The server composes a sentence from the real
 * arguments ("Permanently DELETE the CRM contact ..."), and it is the only description the operator
 * gets — showing `delete_contact` instead would be asking them to approve an identifier.
 *
 * ⛔ AND THE "NOTHING HAS CHANGED YET" LINE IS NOT DECORATION. The answer above it says a change was
 * proposed; without this, a proposal reads as a completed action and the operator walks away
 * believing it was applied.
 */
@Composable
private fun ConfirmCard(
    pending: HqPendingWrite,
    state: HqUiState,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val applying = state is HqUiState.Applying
    ContentContainer {
        DistrictCard(
            modifier = Modifier
                .padding(
                    horizontal = DistrictTheme.spacing.gutter,
                    vertical = DistrictTheme.spacing.tight,
                )
                .semantics { contentDescription = HQ_CONFIRM_DESCRIPTION },
        ) {
            Eyebrow(text = stringResource(R.string.hq_confirm_title))
            Text(
                text = pending.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
            )
            Text(
                text = stringResource(R.string.hq_confirm_note),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
            )

            (state as? HqUiState.ConfirmFailed)?.let { failed ->
                Text(
                    text = failed.failure.message.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.destructive,
                    modifier = Modifier
                        .padding(top = DistrictTheme.spacing.tight)
                        .semantics { contentDescription = HQ_CONFIRM_FAILURE_DESCRIPTION },
                )
            }

            Row(
                modifier = Modifier.padding(top = DistrictTheme.spacing.row),
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canConfirm) {
                    // ⛔ DISABLED WHILE APPLYING. This is the tap that deletes a contact or sends a
                    // customer an email, and the server has no idempotency key — so this guard is
                    // the only thing between a double tap and a repeated write.
                    DistrictButton(
                        text = stringResource(
                            if (applying) R.string.hq_applying else R.string.hq_confirm_apply,
                        ),
                        onClick = onConfirm,
                        enabled = !applying,
                        modifier = Modifier.semantics {
                            contentDescription = HQ_CONFIRM_ACCEPT_DESCRIPTION
                        },
                    )
                }
                DistrictButton(
                    text = stringResource(R.string.hq_confirm_dismiss),
                    onClick = onDismiss,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    enabled = !applying,
                    modifier = Modifier.semantics {
                        contentDescription = HQ_CONFIRM_DISMISS_DESCRIPTION
                    },
                )
            }
        }
    }
}

@Composable
private fun FailureRow(message: String, retryable: Boolean, onRetry: () -> Unit) {
    ContentContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DistrictTheme.spacing.gutter)
                .semantics { contentDescription = HQ_FAILURE_DESCRIPTION },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            // ⚠️ Offered only when retrying could work. A contract mismatch or a role refusal
            // produces the identical failure every time.
            if (retryable) {
                DistrictButton(
                    text = stringResource(R.string.overview_retry),
                    onClick = onRetry,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                )
            }
        }
    }
}

/**
 * ⛔ `rememberSaveable`, NOT `remember`. This is user input and the activity is `singleTask` with no
 * `android:configChanges`, so a rotation or a font-size change destroys plain state. A half-typed
 * question is the same loss the Inbox reply box and the create-contact dialog already paid for.
 */
@Composable
private fun Composer(enabled: Boolean, onSend: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }

    ContentContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DistrictTheme.spacing.gutter),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Eyebrow(stringResource(R.string.hq_prompt_label)) },
                enabled = enabled,
                maxLines = PROMPT_MAX_LINES,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DistrictTheme.colors.muted,
                    unfocusedContainerColor = DistrictTheme.colors.muted,
                    disabledContainerColor = DistrictTheme.colors.muted,
                    focusedBorderColor = DistrictTheme.colors.district,
                    unfocusedBorderColor = DistrictTheme.colors.border,
                    focusedTextColor = DistrictTheme.colors.foreground,
                    unfocusedTextColor = DistrictTheme.colors.foreground,
                    cursorColor = DistrictTheme.colors.district,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = HQ_PROMPT_FIELD_DESCRIPTION },
            )
            DistrictButton(
                text = stringResource(R.string.hq_send),
                onClick = {
                    onSend(draft)
                    draft = ""
                },
                enabled = enabled && draft.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = HQ_SEND_DESCRIPTION },
            )
        }
    }
}

private const val PROMPT_MAX_LINES = 5
private val BUBBLE_MAX_WIDTH = 420.dp
private val BUBBLE_RADIUS = 12.dp
private const val BUBBLE_ACCENT_ALPHA = 0.12f

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val HQ_ROOT_DESCRIPTION: String = "district-hq-root"
const val HQ_EMPTY_DESCRIPTION: String = "district-hq-empty"
const val HQ_LATEST_MESSAGE_DESCRIPTION: String = "district-hq-latest-message"
const val HQ_THINKING_DESCRIPTION: String = "district-hq-thinking"
const val HQ_CONFIRM_DESCRIPTION: String = "district-hq-confirm"
const val HQ_CONFIRM_ACCEPT_DESCRIPTION: String = "district-hq-confirm-accept"
const val HQ_CONFIRM_DISMISS_DESCRIPTION: String = "district-hq-confirm-dismiss"
const val HQ_CONFIRM_FAILURE_DESCRIPTION: String = "district-hq-confirm-failure"
const val HQ_FAILURE_DESCRIPTION: String = "district-hq-failure"
const val HQ_PROMPT_FIELD_DESCRIPTION: String = "district-hq-prompt-field"
const val HQ_SEND_DESCRIPTION: String = "district-hq-send"
