package com.distronode.districtai.ui.dialer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.ui.resolve

/**
 * The keypad, the call-back list, and — when a call is live — the in-call screen in its place.
 *
 * ⛔ ONE SCREEN FOR BOTH, BECAUSE THEY ARE ONE DESTINATION. See the ⛔ on [DialerUiState]: an
 * in-call destination reached by navigation is restored after process death and its start effect
 * runs again, which would place a second billable call with no user action. Swapping the content
 * of one destination has no such restore path — a killed process comes back to an idle keypad,
 * which is the truth, because the socket died with it.
 *
 * ⛔ A VIEWER SEES THE SCREEN AND NOT THE CONTROLS. The dial route excludes `viewer` server-side,
 * so this is presence rather than wording — but the entry into the screen is already hidden for
 * that role, and a viewer arriving here came from a restored back stack or a deep link. Stating
 * why is better than a dial button that 403s.
 */
@Composable
fun DialerScreen(
    state: DialerUiState,
    handlers: DialerHandlers,
) {
    val call = state.call
    if (call != null) {
        InCallScreen(call = call, handlers = handlers)
        return
    }
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = DIALER_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.dialer_title), onBack = handlers.onBack)
        },
    ) { inset ->
        ContentContainer(modifier = inset.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
            ) {
                item { EntryCard(state, handlers) }
                item { CallbacksHeading() }
                callbacks(state.callbacks, handlers)
            }
        }
    }
}

/**
 * The number field and the call button.
 *
 * ⛔ A TEXT FIELD RATHER THAN A TWELVE-KEY GRID, AND THE REASON IS THE NUMBERS THIS PRODUCT DIALS.
 * A grid is the right shape for a consumer phone dialling local numbers from memory; this dials
 * E.164 numbers with country codes, usually pasted or called back from the log, and a grid makes
 * `+` awkward and pasting impossible. The field takes a phone keyboard, so the same digits are one
 * tap away, and the formatted preview below it does the job the grid's display would.
 */
@Composable
private fun EntryCard(state: DialerUiState, handlers: DialerHandlers) {
    DistrictCard(modifier = Modifier.semantics { contentDescription = DIALER_ENTRY_CARD_DESCRIPTION }) {
        Eyebrow(stringResource(R.string.dialer_number_label))
        OutlinedTextField(
            value = state.entry,
            onValueChange = handlers.onEntryChange,
            enabled = state.canDial,
            singleLine = true,
            // ⚠️ `Phone`, so `+`, `*` and `#` are reachable. `Number` offers digits only, which
            // makes an international number untypeable without switching keyboards.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = districtFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DistrictTheme.spacing.tight)
                .semantics { contentDescription = DIALER_FIELD_DESCRIPTION },
        )
        // ⛔ BESIDE THE FIELD, NEVER IN IT. See `formatDialEntry`: rewriting the text as someone
        // types moves their cursor, and the value that travels must stay exactly what they typed
        // because the server normalises its own copy and dials against that.
        Text(
            text = if (state.entry.isBlank()) {
                stringResource(R.string.dialer_entry_hint)
            } else {
                formatDialEntry(state.entry)
            },
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier
                .padding(top = DistrictTheme.spacing.hairline)
                .semantics { contentDescription = DIALER_PREVIEW_DESCRIPTION },
        )
        if (state.canDial) {
            DistrictButton(
                text = stringResource(R.string.dialer_call),
                onClick = handlers.onDial,
                enabled = state.canPlaceCall,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.row)
                    .semantics { contentDescription = DIALER_CALL_DESCRIPTION },
            )
            // ⚠️ SAID BEFORE THE PERMISSION DIALOG, so the dialog has a visible reason. A request
            // with no explanation is the one users deny permanently.
            Notice(R.string.dialer_mic_notice, DIALER_MIC_NOTICE_DESCRIPTION)
        } else {
            Notice(R.string.dialer_viewer_notice, DIALER_VIEWER_NOTICE_DESCRIPTION)
        }
        // ⚠️ THE REFUSAL LIVES HERE, ON THE KEYPAD, because a refused dial produced no call —
        // there is no call screen to show it over.
        state.refusal?.let { failure ->
            Text(
                text = failure.message.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = DIALER_REFUSAL_DESCRIPTION },
            )
        }
    }
}

@Composable
private fun Notice(textId: Int, description: String) {
    Text(
        text = stringResource(textId),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = Modifier
            .padding(top = DistrictTheme.spacing.tight)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun CallbacksHeading() {
    Row(modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter)) {
        Eyebrow(stringResource(R.string.dialer_callbacks_label))
    }
}

/**
 * The recent inbound callers.
 *
 * ⛔ "CALL BACK", NOT "REDIAL", AND THE LIST IS FILTERED TO MATCH. `CallSummary.from` on an
 * OUTBOUND row is the workspace's own number, so a redial built from it would dial the workspace's
 * own line — see `CallsRepository.recentCallbacks`, which is where the filter lives. Labelling
 * this redial would promise something the call log cannot support.
 *
 * ⚠️ A TAP FILLS THE FIELD; IT DOES NOT DIAL. One tap must never place a call, least of all from a
 * scrolling list where a mis-scroll lands on a row.
 */
private fun LazyListScope.callbacks(
    state: CallbacksState,
    handlers: DialerHandlers,
) {
    when (state) {
        CallbacksState.Loading -> item {
            ContentContainer {
                Text(
                    text = stringResource(R.string.dialer_callbacks_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                    modifier = Modifier
                        .padding(DistrictTheme.spacing.gutter)
                        .semantics { contentDescription = DIALER_CALLBACKS_LOADING_DESCRIPTION },
                )
            }
        }

        is CallbacksState.Failed -> item {
            ContentContainer {
                EmptyState(
                    title = stringResource(R.string.dialer_callbacks_failed),
                    body = state.failure.message.resolve(),
                    modifier = Modifier.semantics {
                        contentDescription = DIALER_CALLBACKS_FAILED_DESCRIPTION
                    },
                )
            }
        }

        is CallbacksState.Ready -> if (state.calls.isEmpty()) {
            item {
                ContentContainer {
                    EmptyState(
                        title = stringResource(R.string.dialer_callbacks_empty_title),
                        body = stringResource(R.string.dialer_callbacks_empty),
                        modifier = Modifier.semantics {
                            contentDescription = DIALER_CALLBACKS_EMPTY_DESCRIPTION
                        },
                    )
                }
            }
        } else {
            items(state.calls, key = { it.id }) { call ->
                ContentContainer {
                    CallbackRow(call, handlers)
                    DistrictRowDivider()
                }
            }
        }
    }
}

@Composable
private fun CallbackRow(call: CallSummary, handlers: DialerHandlers) {
    // ⚠️ `from` IS NON-NULL BY CONSTRUCTION HERE — the repository drops rows without one, because
    // a row with no number is not something to call back. The elvis is for the type, not for a
    // case that can reach the screen.
    val number = call.from.orEmpty()
    DistrictListRow(
        title = call.number,
        subtitle = "${formatDialEntry(number)} · ${call.time}",
        onClick = { handlers.onCallBack(number) },
        modifier = Modifier.semantics { contentDescription = callbackRowDescription(call.id) },
        trailing = {
            DistrictButton(
                text = stringResource(R.string.dialer_call),
                onClick = { handlers.onCallBack(number) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
            )
        },
    )
}

/**
 * Every callback the dialler and the in-call screen need.
 *
 * ⚠️ A HOLDER RATHER THAN SEVEN PARAMETERS, the same call `RoomControls` and `ComposerHandlers`
 * make: detekt caps a parameter list, and seven identically-typed lambdas at a call site is an
 * invitation to swap two of them silently.
 */
data class DialerHandlers(
    val onEntryChange: (String) -> Unit,
    val onCallBack: (String) -> Unit,
    val onDial: () -> Unit,
    val onToggleMicrophone: () -> Unit,
    val onToggleSpeaker: () -> Unit,
    val onHangUp: () -> Unit,
    val onDismissEndedCall: () -> Unit,
    val onBack: () -> Unit,
)

/** ⚠️ Keyed on the call id, which is stable across a reload. */
internal fun callbackRowDescription(id: String): String = "district-dialer-callback-$id"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val DIALER_ROOT_DESCRIPTION: String = "district-dialer-root"
const val DIALER_ENTRY_CARD_DESCRIPTION: String = "district-dialer-entry"
const val DIALER_FIELD_DESCRIPTION: String = "district-dialer-field"
const val DIALER_PREVIEW_DESCRIPTION: String = "district-dialer-preview"
const val DIALER_CALL_DESCRIPTION: String = "district-dialer-call"
const val DIALER_MIC_NOTICE_DESCRIPTION: String = "district-dialer-mic-notice"
const val DIALER_VIEWER_NOTICE_DESCRIPTION: String = "district-dialer-viewer-notice"
const val DIALER_REFUSAL_DESCRIPTION: String = "district-dialer-refusal"
const val DIALER_CALLBACKS_LOADING_DESCRIPTION: String = "district-dialer-callbacks-loading"
const val DIALER_CALLBACKS_FAILED_DESCRIPTION: String = "district-dialer-callbacks-failed"
const val DIALER_CALLBACKS_EMPTY_DESCRIPTION: String = "district-dialer-callbacks-empty"

// ⚠️ INTERNAL RATHER THAN PRIVATE so `DialerScreenTest` can render it: a preview that stopped
// composing would break Android Studio's renderer without failing anything else.
@Preview(showBackground = true)
@Composable
internal fun DialerScreenPreview() {
    DistrictTheme {
        DialerScreen(
            state = DialerUiState(
                canDial = true,
                entry = "+14165550100",
                callbacks = CallbacksState.Ready(emptyList()),
            ),
            handlers = DialerHandlers({}, {}, {}, {}, {}, {}, {}, {}),
        )
    }
}
