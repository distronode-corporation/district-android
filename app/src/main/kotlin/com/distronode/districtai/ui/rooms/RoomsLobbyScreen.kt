package com.distronode.districtai.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.MeetRoomName
import com.distronode.districtai.core.model.MeetingSummary
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve

/**
 * The rooms lobby: start a meeting, or read the minutes of one that already happened.
 *
 * ⛔ THE MINUTES ARE THE REASON THIS SCREEN EXISTS, NOT A SECONDARY LIST. The Companion joins every
 * `meet_` room and writes them up; without somewhere to read them the feature is invisible on a
 * phone. So a completed meeting shows its preview inline rather than hiding it behind a tap.
 *
 * ⛔ AND THE PREVIEW IS LABELLED AS ONE. The server truncates to 220 characters, so presenting it
 * as "the minutes" would be quietly delivering two sentences where a page was written. The row says
 * it is a preview and offers the full record.
 *
 * ⚠️ THE JOIN FORM STAYS USABLE WHEN THE HISTORY READ FAILS. They are unrelated server surfaces,
 * and gating the field on the list would turn an outage of the archive into an inability to hold a
 * meeting — see [RoomsLobbyUiState].
 */
@Composable
fun RoomsLobbyScreen(
    state: RoomsLobbyUiState,
    onRoomNameChange: (String) -> Unit,
    onJoin: () -> Unit,
    onRejoin: (String) -> Unit,
    onOpenMeeting: (String) -> Unit,
    onCloseMeeting: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = ROOMS_ROOT_DESCRIPTION },
        topBar = { DistrictTopBar(title = stringResource(R.string.rooms_title), onBack = onBack) },
    ) { inset ->
        ContentContainer(modifier = inset.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.padding(DistrictTheme.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.section),
            ) {
                StartRoomCard(state, onRoomNameChange, onJoin)

                Eyebrow(stringResource(R.string.rooms_history_label))
                when (val list = state.meetings) {
                    MeetingsListState.Loading -> LoadingRows()
                    is MeetingsListState.Ready -> MeetingList(list.meetings, onRejoin, onOpenMeeting)
                    is MeetingsListState.Failed -> HistoryFailure(list.failure, onRetry)
                }
            }
        }
    }

    state.openMeeting?.let { detail ->
        MeetingRecordDialog(detail = detail, onDismiss = onCloseMeeting)
    }
}

@Composable
private fun LoadingRows() {
    Column(
        modifier = Modifier.semantics { contentDescription = ROOMS_LOADING_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        repeat(SKELETON_ROWS) { SkeletonBlock(height = DistrictTheme.spacing.header) }
    }
}

@Composable
private fun MeetingList(
    meetings: List<MeetingSummary>,
    onRejoin: (String) -> Unit,
    onOpenMeeting: (String) -> Unit,
) {
    if (meetings.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.rooms_empty_title),
            body = stringResource(R.string.rooms_empty_body),
            modifier = Modifier.semantics { contentDescription = ROOMS_EMPTY_DESCRIPTION },
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row)) {
        meetings.forEach { meeting -> MeetingCard(meeting, onRejoin, onOpenMeeting) }
    }
}

@Composable
private fun MeetingCard(
    meeting: MeetingSummary,
    onRejoin: (String) -> Unit,
    onOpenMeeting: (String) -> Unit,
) {
    val live = meeting.status == STATUS_IN_PROGRESS
    DistrictCard(
        modifier = Modifier.semantics { contentDescription = meetingRowDescription(meeting.id) },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                // ⚠️ The human half of the room name when nobody titled the meeting — the full
                // `meet_<uuid>_standup` is not something to show a person, and an empty line would
                // leave the row unidentifiable.
                text = meeting.title?.takeIf { it.isNotBlank() }
                    ?: MeetRoomName.displayName(meeting.roomName),
                style = MaterialTheme.typography.titleSmall,
                color = DistrictTheme.colors.foreground,
                modifier = Modifier.weight(1f),
            )
            DistrictBadge(
                text = meeting.status,
                tone = if (live) Tone.District else Tone.Neutral,
            )
        }
        Text(
            // ⛔ NAMED AS A PREVIEW, and the "no minutes yet" case gets its own sentence rather
            // than an empty line — an in-progress meeting has no summary and that is normal, not
            // a fault. See MeetingSummary.summaryPreview.
            text = meeting.summaryPreview?.let { stringResource(R.string.rooms_preview, it) }
                ?: stringResource(
                    if (live) R.string.rooms_no_minutes_yet else R.string.rooms_no_minutes,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        Row(
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            DistrictButton(
                text = stringResource(R.string.rooms_open_meeting),
                onClick = { onOpenMeeting(meeting.id) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.semantics {
                    contentDescription = meetingOpenDescription(meeting.id)
                },
            )
            // ⛔ REJOIN IS OFFERED ONLY FOR A MEETING STILL RUNNING. Joining the room of a finished
            // meeting is not an error server-side — the name is still valid — so nothing would
            // stop it; it would just silently start a SECOND meeting under the name whose minutes
            // the user was reading, and the Companion would write those up too.
            if (live) {
                DistrictButton(
                    text = stringResource(R.string.rooms_rejoin),
                    onClick = { onRejoin(meeting.roomName) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Sm,
                    modifier = Modifier.semantics {
                        contentDescription = meetingRejoinDescription(meeting.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun HistoryFailure(failure: FailureText, onRetry: () -> Unit) {
    DistrictCard(
        modifier = Modifier.semantics { contentDescription = ROOMS_HISTORY_FAILURE_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.rooms_history_failed))
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        // ⚠️ Only when retrying could work — a role refusal or a contract mismatch repeats
        // identically, and a button that cannot help is worse than none.
        if (failure.retryable) {
            DistrictButton(
                text = stringResource(R.string.overview_retry),
                onClick = onRetry,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
            )
        }
    }
}

private const val SKELETON_ROWS = 3

/** ⚠️ The server's own string for a running meeting; the column carries no enum. */
internal const val STATUS_IN_PROGRESS: String = "in-progress"

/**
 * Per-row handles, derived in one place so a row and its test cannot drift apart.
 *
 * ⚠️ Keyed on the MEETING ID rather than the list index: the list is newest-first and a meeting
 * that ends re-sorts nothing but a new one shifts every index below it.
 */
internal fun meetingRowDescription(id: String): String = "district-rooms-meeting-$id"

internal fun meetingOpenDescription(id: String): String = "district-rooms-open-$id"

internal fun meetingRejoinDescription(id: String): String = "district-rooms-rejoin-$id"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val ROOMS_ROOT_DESCRIPTION: String = "district-rooms-root"
const val ROOMS_LOADING_DESCRIPTION: String = "district-rooms-loading"
const val ROOMS_EMPTY_DESCRIPTION: String = "district-rooms-empty"
const val ROOMS_HISTORY_FAILURE_DESCRIPTION: String = "district-rooms-history-failure"

@Preview(showBackground = true)
@Composable
internal fun RoomsLobbyScreenPreview() {
    DistrictTheme {
        RoomsLobbyScreen(
            state = RoomsLobbyUiState(
                meetings = MeetingsListState.Ready(
                    listOf(
                        MeetingSummary(
                            id = "m1",
                            roomName = "meet_ws_standup",
                            status = STATUS_IN_PROGRESS,
                            createdAt = "2026-08-18T09:00:00.000Z",
                        ),
                        MeetingSummary(
                            id = "m2",
                            roomName = "meet_ws_weekly-review",
                            title = "Weekly review",
                            status = "completed",
                            durationSec = 2520,
                            summaryPreview = "The team reviewed Thursday's inbound volume…",
                            participantCount = 2,
                            createdAt = "2026-08-14T15:00:00.000Z",
                        ),
                    ),
                ),
                roomName = "Weekly Review",
                normalizedName = "weekly-review",
            ),
            onRoomNameChange = {},
            onJoin = {},
            onRejoin = {},
            onOpenMeeting = {},
            onCloseMeeting = {},
            onRetry = {},
            onBack = {},
        )
    }
}
