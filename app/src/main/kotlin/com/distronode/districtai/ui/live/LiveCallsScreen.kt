package com.distronode.districtai.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.ui.calls.toDisplay
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
 * ⛔ A CALL THE RECEPTIONIST IS NOT ON (`receptionistOnCall` false: a team member's own call, or
 * one with no room) OFFERS LISTEN AND NOTHING ELSE, and says why: a note reaches nobody and there
 * is nobody to step back.
 */
@Composable
fun LiveCallsScreen(view: LiveCallsView, actions: LiveCallsActions) {
    KeepScreenOn(active = view.supervision.callId != null)
    ProximityScreenOff(active = view.supervision.takenOver)
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
                is LiveCallList.Loaded -> LoadedCalls(list, view, actions)
            }
        }
    }
}

@Composable
private fun LoadedCalls(list: LiveCallList.Loaded, view: LiveCallsView, actions: LiveCallsActions) {
    val calls = list.calls
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(LIVE_CALLS_LIST_TAG),
        contentPadding = PaddingValues(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) @DontMemoize {
        item(key = "header") {
            ContentContainer(contentPadding = PaddingValues(horizontal = DistrictTheme.spacing.gutter)) {
                Text(
                    text = stringResource(R.string.live_calls_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DistrictTheme.colors.mutedForeground,
                )
            }
        }
        // ⚠️ A failed refresh over a loaded list is a notice, never the failure screen: the cards
        // (and the controls that end a session) stay. The shared failure sentence says why.
        list.refreshFailure?.let { failure ->
            item(key = "refresh-failure") {
                ContentContainer(contentPadding = PaddingValues(horizontal = DistrictTheme.spacing.gutter)) {
                    Failure(failure.message.resolve(), Modifier.testTag(LIVE_CALLS_REFRESH_FAILURE_TAG))
                }
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
            ContentContainer(contentPadding = PaddingValues(horizontal = DistrictTheme.spacing.gutter)) {
                LiveCallCard(call, view, actions)
            }
        }
    }
}

@Composable
private fun LiveCallCard(call: CallSummary, view: LiveCallsView, actions: LiveCallsActions) {
    val supervision = view.supervision
    val here = supervision.callId == call.id
    DistrictCard(modifier = Modifier.testTag(liveCallTag(call.id))) {
        Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row)) {
            CallHeading(call)
            if (here) {
                SupervisedState(supervision)
            }
            // ⚠️ A taken-over call offers only the hand back: "Stop listening" would name the wrong thing.
            if (!(here && supervision.takenOver)) {
                ListenControl(call.id, here, supervision.busy, actions)
            }
            if (here && !call.receptionistOnCall) {
                Note(
                    stringResource(R.string.live_calls_no_receptionist_note),
                    Modifier.testTag(LIVE_CALLS_NO_RECEPTIONIST_NOTE_TAG),
                )
            }
            if (here && view.canWrite && call.receptionistOnCall) {
                WriterControls(call.id, view, actions)
            }
            if (supervision.failedCallId == call.id) {
                supervision.failure?.let { Failure(it.resolve(), Modifier.testTag(LIVE_CALLS_FAILURE_LINE_TAG)) }
            }
        }
    }
}

@Composable
private fun CallHeading(call: CallSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
        // ⚠️ The call log's own reading of the row: the server's "Unknown" is no name.
        Text(
            text = call.toDisplay().displayName ?: stringResource(R.string.live_calls_unknown_caller),
            style = MaterialTheme.typography.titleSmall,
        )
        if (!call.receptionistOnCall) {
            Text(
                text = stringResource(R.string.live_calls_no_receptionist),
                style = MaterialTheme.typography.labelSmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.testTag(LIVE_CALLS_NO_RECEPTIONIST_TAG),
            )
        }
    }
}

/**
 * ⛔ "YOU ARE LISTENING" AND "YOU ARE ON THE CALL" ONLY ONCE TRUE ([LiveSupervisor.State.live]).
 * Before that the room is still joining (or, on a takeover, the microphone is not yet live) and
 * the card says connecting; a joined room that drops says reconnecting. The web gates the same
 * states on `connectionState === "connected"`.
 */
@Composable
private fun SupervisedState(supervision: LiveSupervisor.State) {
    val (title, body) = when {
        supervision.joined && supervision.phase == LiveSupervisor.Phase.RECONNECTING ->
            R.string.live_calls_reconnecting to null
        !supervision.live -> R.string.live_calls_connecting to null
        supervision.takenOver -> R.string.live_calls_taken_over_title to R.string.live_calls_taken_over_body
        else -> R.string.live_calls_listening to R.string.live_calls_listening_body
    }
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.testTag(LIVE_CALLS_STATE_TAG),
        )
        body?.let { Note(stringResource(it)) }
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

internal fun liveCallTag(callId: String): String = "live-call-$callId"

internal const val LIVE_CALLS_ROOT_TAG = "live-calls"
internal const val LIVE_CALLS_LOADING_TAG = "live-calls-loading"
internal const val LIVE_CALLS_FAILURE_TAG = "live-calls-failure"
internal const val LIVE_CALLS_REFRESH_FAILURE_TAG = "live-calls-refresh-failure"
internal const val LIVE_CALLS_LIST_TAG = "live-calls-list"
internal const val LIVE_CALLS_EMPTY_TAG = "live-calls-empty"
internal const val LIVE_CALLS_LISTEN_TAG = "live-calls-listen"
internal const val LIVE_CALLS_LEAVE_TAG = "live-calls-leave"
internal const val LIVE_CALLS_STATE_TAG = "live-calls-state"
internal const val LIVE_CALLS_NO_RECEPTIONIST_TAG = "live-calls-no-receptionist"
internal const val LIVE_CALLS_NO_RECEPTIONIST_NOTE_TAG = "live-calls-no-receptionist-note"
internal const val LIVE_CALLS_WHISPER_FIELD_TAG = "live-calls-whisper-field"
internal const val LIVE_CALLS_WHISPER_SEND_TAG = "live-calls-whisper-send"
internal const val LIVE_CALLS_WHISPER_ERROR_TAG = "live-calls-whisper-error"
internal const val LIVE_CALLS_RECEIPT_TAG = "live-calls-receipt"
internal const val LIVE_CALLS_TAKE_OVER_TAG = "live-calls-take-over"
internal const val LIVE_CALLS_CONFIRM_TAG = "live-calls-confirm"
internal const val LIVE_CALLS_CONFIRM_YES_TAG = "live-calls-confirm-yes"
internal const val LIVE_CALLS_HAND_BACK_TAG = "live-calls-hand-back"
internal const val LIVE_CALLS_FAILURE_LINE_TAG = "live-calls-failure-line"
