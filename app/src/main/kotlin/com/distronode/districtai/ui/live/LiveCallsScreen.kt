package com.distronode.districtai.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictIcons
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.DistrictTopBarAction
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.ActiveCall
import com.distronode.districtai.core.model.WHISPER_NOT_APPLIED_TAKEN_OVER
import com.distronode.districtai.core.model.WhisperAppliedData
import com.distronode.districtai.ui.FailureState
import com.distronode.districtai.ui.PagedListLoading
import com.distronode.districtai.ui.resolve

/** Everything the Live calls screen can ask for. */
data class LiveCallsActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSignIn: () -> Unit,
    val onListen: (String) -> Unit,
    val onLeave: () -> Unit,
    val onDraft: (String, String) -> Unit,
    val onSend: (String) -> Unit,
    val onAskTakeOver: (String) -> Unit,
    val onCancelTakeOver: () -> Unit,
    val onConfirmTakeOver: () -> Unit,
)

/** What the Live calls screen draws, from the ViewModel's four flows. */
data class LiveCallsView(
    val list: LiveCallList,
    val supervision: LiveSupervisor.State,
    val whispers: Map<String, WhisperBox>,
    val prompt: LiveCallsPrompt,
    /** Not a viewer: may whisper and take over. */
    val canWrite: Boolean,
)

/**
 * The calls in progress, each with Listen; the one being listened to opens its note box and its
 * take-over control (the web Live page, as one column).
 *
 * ⛔ A TEAM MEMBER'S OWN CALL OFFERS LISTEN AND NOTHING ELSE, and says why. No receptionist is on a
 * `direct_` call, so a note reaches nobody and there is nobody to step back.
 */
@Composable
fun LiveCallsScreen(view: LiveCallsView, actions: LiveCallsActions) {
    DistrictScaffold(
        modifier = Modifier.testTag(LIVE_CALLS_ROOT_TAG),
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.live_calls_title),
                onBack = actions.onBack,
                actions = {
                    DistrictTopBarAction(
                        icon = DistrictIcons.Refresh,
                        label = stringResource(R.string.live_calls_refresh),
                        onClick = actions.onRefresh,
                    )
                },
            )
        },
    ) { inset ->
        Box(modifier = inset.fillMaxSize()) {
            when (val list = view.list) {
                LiveCallList.Loading -> PagedListLoading(LIVE_CALLS_LOADING_TAG)
                is LiveCallList.Failed -> FailureState(
                    failure = list.failure,
                    onRetry = actions.onRefresh,
                    onSignIn = actions.onSignIn,
                    description = LIVE_CALLS_FAILURE_TAG,
                )
                is LiveCallList.Loaded -> LoadedCalls(list.calls, view, actions)
            }
        }
    }
}

@Composable
private fun LoadedCalls(calls: List<ActiveCall>, view: LiveCallsView, actions: LiveCallsActions) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(LIVE_CALLS_LIST_TAG)) {
        item(key = "header") {
            ContentContainer {
                Text(
                    text = stringResource(R.string.live_calls_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DistrictTheme.colors.mutedForeground,
                )
            }
        }
        if (calls.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.live_calls_empty_title),
                    body = stringResource(R.string.live_calls_empty_body),
                    modifier = Modifier.testTag(LIVE_CALLS_EMPTY_TAG),
                )
            }
        }
        items(calls, key = { it.id }) { call ->
            ContentContainer {
                LiveCallCard(call, view, actions)
            }
        }
    }
}

@Composable
private fun LiveCallCard(call: ActiveCall, view: LiveCallsView, actions: LiveCallsActions) {
    val supervision = view.supervision
    val here = supervision.callId == call.id
    DistrictCard(modifier = Modifier.testTag(liveCallTag(call.id))) {
        Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row)) {
            CallHeading(call)
            if (here) {
                SupervisedState(supervision)
            }
            ListenControl(call.id, here, supervision.busy, actions)
            if (here && call.isTeamMemberCall) {
                Note(stringResource(R.string.live_calls_team_call_note), Modifier.testTag(LIVE_CALLS_TEAM_NOTE_TAG))
            }
            if (here && view.canWrite && !call.isTeamMemberCall) {
                WriterControls(call.id, view, actions)
            }
            if (supervision.failedCallId == call.id) {
                supervision.failure?.let { Failure(it.resolve(), Modifier.testTag(LIVE_CALLS_FAILURE_LINE_TAG)) }
            }
        }
    }
}

@Composable
private fun CallHeading(call: ActiveCall) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
        val party = call.party.ifBlank { stringResource(R.string.live_calls_unknown_caller) }
        Text(
            text = call.callerName?.takeIf { it.isNotBlank() } ?: party,
            style = MaterialTheme.typography.titleSmall,
        )
        if (!call.callerName.isNullOrBlank()) {
            Note(party)
        }
        if (call.isTeamMemberCall) {
            Text(
                text = stringResource(R.string.live_calls_team_call),
                style = MaterialTheme.typography.labelSmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.testTag(LIVE_CALLS_TEAM_BADGE_TAG),
            )
        }
    }
}

@Composable
private fun SupervisedState(supervision: LiveSupervisor.State) {
    val (title, body) = if (supervision.takenOver) {
        R.string.live_calls_taken_over_title to R.string.live_calls_taken_over_body
    } else {
        R.string.live_calls_listening to R.string.live_calls_listening_body
    }
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag(LIVE_CALLS_STATE_TAG),
        )
        Note(stringResource(body))
    }
}

@Composable
private fun ListenControl(callId: String, here: Boolean, busy: Boolean, actions: LiveCallsActions) {
    if (here) {
        DistrictButton(
            text = stringResource(R.string.live_calls_stop_listening),
            onClick = actions.onLeave,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Sm,
            modifier = Modifier.testTag(LIVE_CALLS_LEAVE_TAG),
        )
    } else {
        DistrictButton(
            text = stringResource(R.string.live_calls_listen),
            onClick = @DontMemoize { actions.onListen(callId) },
            size = ButtonSize.Sm,
            enabled = !busy,
            modifier = Modifier.testTag(LIVE_CALLS_LISTEN_TAG),
        )
    }
}

/** The note box and the take-over control: a writer, on a call the receptionist is on. */
@Composable
private fun WriterControls(callId: String, view: LiveCallsView, actions: LiveCallsActions) {
    val takenOver = view.supervision.takenOver
    if (takenOver) {
        DistrictButton(
            text = stringResource(R.string.live_calls_hand_back),
            onClick = actions.onLeave,
            modifier = Modifier.testTag(LIVE_CALLS_HAND_BACK_TAG),
        )
        return
    }
    WhisperControl(callId, view.whispers[callId] ?: WhisperBox(), actions)
    if (view.prompt.confirmingTakeover == callId) {
        TakeOverConfirmation(actions)
    } else {
        DistrictButton(
            text = stringResource(R.string.live_calls_take_over),
            onClick = @DontMemoize { actions.onAskTakeOver(callId) },
            variant = ButtonVariant.Secondary,
            enabled = !view.supervision.busy,
            modifier = Modifier.testTag(LIVE_CALLS_TAKE_OVER_TAG),
        )
    }
}

@Composable
private fun WhisperControl(callId: String, box: WhisperBox, actions: LiveCallsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        OutlinedTextField(
            value = box.draft,
            onValueChange = @DontMemoize { actions.onDraft(callId, it) },
            label = { Text(stringResource(R.string.live_calls_whisper_label)) },
            placeholder = { Text(stringResource(R.string.live_calls_whisper_placeholder)) },
            colors = districtFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag(LIVE_CALLS_WHISPER_FIELD_TAG),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictButton(
                text = stringResource(R.string.live_calls_whisper_send),
                onClick = @DontMemoize { actions.onSend(callId) },
                size = ButtonSize.Sm,
                enabled = !box.sending && box.draft.isNotBlank(),
                modifier = Modifier.testTag(LIVE_CALLS_WHISPER_SEND_TAG),
            )
        }
        box.error?.let { Failure(it.resolve(), Modifier.testTag(LIVE_CALLS_WHISPER_ERROR_TAG)) }
        box.receipt?.let { Note(receiptText(it), Modifier.testTag(LIVE_CALLS_RECEIPT_TAG)) }
    }
}

/** ⚠️ `reason` is the agent's wire vocabulary, mapped to a sentence and never shown raw. */
@Composable
private fun receiptText(receipt: WhisperAppliedData): String = when {
    receipt.applied && !receipt.firstWords.isNullOrBlank() ->
        stringResource(R.string.live_calls_receipt_applied_words, receipt.firstWords.orEmpty())
    receipt.applied -> stringResource(R.string.live_calls_receipt_applied)
    receipt.reason == WHISPER_NOT_APPLIED_TAKEN_OVER -> stringResource(R.string.live_calls_receipt_taken_over)
    else -> stringResource(R.string.live_calls_receipt_transfer)
}

/** ⛔ In the page, never a system dialog, and it says what the caller will hear. */
@Composable
private fun TakeOverConfirmation(actions: LiveCallsActions) {
    Column(
        modifier = Modifier.testTag(LIVE_CALLS_CONFIRM_TAG),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Text(text = stringResource(R.string.live_calls_take_over_confirm_title), style = MaterialTheme.typography.labelLarge)
        Note(stringResource(R.string.live_calls_take_over_confirm_body))
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictButton(
                text = stringResource(R.string.live_calls_take_over_confirm),
                onClick = actions.onConfirmTakeOver,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag(LIVE_CALLS_CONFIRM_YES_TAG),
            )
            DistrictButton(
                text = stringResource(R.string.live_calls_take_over_cancel),
                onClick = actions.onCancelTakeOver,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
            )
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = modifier,
    )
}

@Composable
private fun Failure(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.destructive,
        modifier = modifier.fillMaxWidth(),
    )
}

internal fun liveCallTag(callId: String): String = "live-call-$callId"

internal const val LIVE_CALLS_ROOT_TAG = "live-calls"
internal const val LIVE_CALLS_LOADING_TAG = "live-calls-loading"
internal const val LIVE_CALLS_FAILURE_TAG = "live-calls-failure"
internal const val LIVE_CALLS_LIST_TAG = "live-calls-list"
internal const val LIVE_CALLS_EMPTY_TAG = "live-calls-empty"
internal const val LIVE_CALLS_LISTEN_TAG = "live-calls-listen"
internal const val LIVE_CALLS_LEAVE_TAG = "live-calls-leave"
internal const val LIVE_CALLS_STATE_TAG = "live-calls-state"
internal const val LIVE_CALLS_TEAM_BADGE_TAG = "live-calls-team-badge"
internal const val LIVE_CALLS_TEAM_NOTE_TAG = "live-calls-team-note"
internal const val LIVE_CALLS_WHISPER_FIELD_TAG = "live-calls-whisper-field"
internal const val LIVE_CALLS_WHISPER_SEND_TAG = "live-calls-whisper-send"
internal const val LIVE_CALLS_WHISPER_ERROR_TAG = "live-calls-whisper-error"
internal const val LIVE_CALLS_RECEIPT_TAG = "live-calls-receipt"
internal const val LIVE_CALLS_TAKE_OVER_TAG = "live-calls-take-over"
internal const val LIVE_CALLS_CONFIRM_TAG = "live-calls-confirm"
internal const val LIVE_CALLS_CONFIRM_YES_TAG = "live-calls-confirm-yes"
internal const val LIVE_CALLS_HAND_BACK_TAG = "live-calls-hand-back"
internal const val LIVE_CALLS_FAILURE_LINE_TAG = "live-calls-failure-line"
