package com.distronode.districtai.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.StatusDot
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.MediaParticipant
import com.distronode.districtai.core.media.VideoTile
import com.distronode.districtai.core.model.MeetRoomName

/**
 * A live multi-party room.
 *
 * ⛔ `Reconnecting` IS A BANNER OVER THE CALL, NEVER A FAILURE SCREEN. A phone walking out of wifi
 * onto its radio disconnects and resumes within seconds, and the SDK does that recovery itself — a
 * UI that swapped the grid for an error would tear down a meeting that was about to come back, and
 * would do it during exactly the ordinary event it was meant to survive. The tiles stay on screen.
 *
 * ⛔ THE COMPANION IS A CHIP AND NOT A TILE, AND BOTH HALVES OF THAT ARE DELIBERATE. It publishes no
 * media, so a tile would be a blank rectangle in the middle of the grid; it is also transcribing
 * the conversation, so hiding it entirely would mean people are recorded without the screen ever
 * saying so. It gets one line that says what it is doing.
 *
 * ⛔ A DENIED PERMISSION DEGRADES THE ROOM, IT DOES NOT REFUSE IT. No camera is audio-only
 * attendance; no microphone is listen-only attendance, which is precisely the seat a `viewer` is
 * given by the server anyway. Both are stated on screen so nobody wonders why their control is
 * disabled — an inert button with no explanation is the worst of the three outcomes.
 */
@Composable
fun ActiveRoomScreen(
    state: ActiveRoomUiState,
    roomName: String,
    controls: RoomControls,
    onLeave: () -> Unit,
    onShareInvite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = ACTIVE_ROOM_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                // ⚠️ The human half of the name. `meet_<uuid>_standup` is not a title.
                title = MeetRoomName.displayName(roomName),
                onBack = onLeave,
            )
        },
    ) { inset ->
        ContentContainer(modifier = inset.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DistrictTheme.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
            ) {
                ConnectionBanner(state.connection)
                state.joinFailure?.let { JoinFailure(it) }
                if (state.companionPresent) CompanionChip()
                PermissionNotices(state)

                Box(modifier = Modifier.weight(1f)) {
                    ParticipantGrid(state.participants)
                }

                RoomControlsRow(state, controls)
                LeaveRow(state, onLeave, onShareInvite)
            }
        }
    }
}

/**
 * ⛔ A PARTICIPANT WITH NO VIDEO STILL GETS A TILE. Camera-off is the normal way to be in a meeting,
 * and dropping those people from the grid would make the room look emptier than it is — the
 * dangerous direction, since it is what someone checks before saying something private.
 *
 * ⚠️ THE LOCAL USER IS NOT IN THIS LIST, by [com.distronode.districtai.core.media.CallEngine]'s
 * contract. A self-tile drawn from the same source double-counts on reconnect.
 */
@Composable
private fun ParticipantGrid(participants: List<MediaParticipant>) {
    if (participants.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.room_alone_title),
            body = stringResource(R.string.room_alone_body),
            modifier = Modifier.semantics { contentDescription = ROOM_ALONE_DESCRIPTION },
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = ROOM_GRID_DESCRIPTION },
    ) {
        // ⚠️ Keyed on the server-derived identity, which is the EVICTION key — the same human
        // rejoining replaces themselves rather than producing a second tile with a new key.
        items(participants, key = { it.identity }) { participant ->
            ParticipantTile(participant)
        }
    }
}

@Composable
private fun ParticipantTile(participant: MediaParticipant) {
    DistrictCard(
        modifier = Modifier.semantics {
            contentDescription = participantTileDescription(participant.identity)
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(TILE_ASPECT),
            contentAlignment = Alignment.Center,
        ) {
            val track = participant.videoTrack
            if (track != null) {
                // ⛔ THE ONLY CALL SITE THAT TOUCHES A VIDEO TRACK, AND IT NEVER OPENS IT. `VideoTile`
                // lives in core-media because rendering means holding the SDK's track; this module
                // hands over an opaque handle and learns nothing about what is inside.
                VideoTile(handle = track, modifier = Modifier.fillMaxSize())
            } else {
                // ⚠️ An audio-only tile, not an omission. See the ⛔ on the grid.
                Text(
                    text = participant.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.room_unnamed_participant),
                    style = MaterialTheme.typography.titleSmall,
                    color = DistrictTheme.colors.mutedForeground,
                    modifier = Modifier.semantics {
                        contentDescription = audioTileDescription(participant.identity)
                    },
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠️ A muted participant is marked, because "why can I not hear them" is the most
            // common question in a room and the answer is usually this.
            StatusDot(tone = if (participant.isMicrophoneEnabled) Tone.Success else Tone.Neutral)
            Text(
                text = participant.name?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.room_unnamed_participant),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.foreground,
                modifier = Modifier.padding(start = DistrictTheme.spacing.hairline),
            )
        }
    }
}

/**
 * The media controls.
 *
 * ⛔ DISABLED RATHER THAN HIDDEN WHEN A PERMISSION OR THE ROLE FORBIDS THEM. A missing control makes
 * the user look for it; a disabled one beside a notice that says why is the pair that actually
 * explains the state. The notices are rendered above.
 */
@Composable
private fun RoomControlsRow(state: ActiveRoomUiState, controls: RoomControls) {
    Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        DistrictButton(
            text = stringResource(
                if (state.micEnabled) R.string.room_mic_on else R.string.room_mic_off,
            ),
            onClick = controls.onToggleMicrophone,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Md,
            enabled = state.canPublish && state.permissions.microphoneGranted,
            modifier = Modifier.semantics { contentDescription = ROOM_MIC_DESCRIPTION },
        )
        DistrictButton(
            text = stringResource(
                if (state.cameraEnabled) R.string.room_camera_on else R.string.room_camera_off,
            ),
            onClick = controls.onToggleCamera,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Md,
            enabled = state.canPublish && state.permissions.cameraGranted,
            modifier = Modifier.semantics { contentDescription = ROOM_CAMERA_DESCRIPTION },
        )
        DistrictButton(
            text = stringResource(R.string.room_flip),
            onClick = controls.onFlipCamera,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Md,
            // ⚠️ Only while the camera is publishing — flipping an off camera does nothing visible,
            // so an enabled control would report success for an action with no effect.
            enabled = state.cameraEnabled,
            modifier = Modifier.semantics { contentDescription = ROOM_FLIP_DESCRIPTION },
        )
        DistrictButton(
            text = stringResource(
                if (state.speakerOn) R.string.room_speaker_on else R.string.room_speaker_off,
            ),
            onClick = controls.onToggleSpeaker,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Md,
            modifier = Modifier.semantics { contentDescription = ROOM_SPEAKER_DESCRIPTION },
        )
    }
}

@Composable
private fun LeaveRow(
    state: ActiveRoomUiState,
    onLeave: () -> Unit,
    onShareInvite: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        DistrictButton(
            text = stringResource(R.string.room_leave),
            onClick = onLeave,
            variant = ButtonVariant.Danger,
            modifier = Modifier.semantics { contentDescription = ROOM_LEAVE_DESCRIPTION },
        )
        // ⛔ ABSENT, NOT DISABLED, WHEN THE SERVER MINTED NO INVITE. A disabled share control would
        // suggest a viewer could invite someone if only something else were true; the server's
        // refusal is categorical, and a link built locally would be one `/api/meet/token` will not
        // honour. See ActiveRoomUiState.guestLink.
        state.guestLink?.let { link ->
            DistrictButton(
                text = stringResource(R.string.room_share_invite),
                onClick = { onShareInvite(link) },
                variant = ButtonVariant.Secondary,
                modifier = Modifier.semantics { contentDescription = ROOM_SHARE_DESCRIPTION },
            )
        }
    }
}

/**
 * The four media controls, bundled.
 *
 * ⚠️ A HOLDER RATHER THAN FOUR PARAMETERS, for the reason `ComposerHandlers` exists on the thread
 * screen: detekt caps a function's parameter list, and four callbacks that always travel together
 * are one concept. It also stops a call site silently swapping two identically-typed lambdas.
 */
data class RoomControls(
    val onToggleMicrophone: () -> Unit,
    val onToggleCamera: () -> Unit,
    val onFlipCamera: () -> Unit,
    val onToggleSpeaker: () -> Unit,
)

private const val GRID_COLUMNS = 2
private const val TILE_ASPECT = 4f / 3f

/** ⚠️ Keyed on the participant identity, which is stable across a rejoin. */
internal fun participantTileDescription(identity: String): String = "district-room-tile-$identity"

internal fun audioTileDescription(identity: String): String = "district-room-audio-$identity"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val ACTIVE_ROOM_ROOT_DESCRIPTION: String = "district-room-root"
const val ROOM_GRID_DESCRIPTION: String = "district-room-grid"
const val ROOM_ALONE_DESCRIPTION: String = "district-room-alone"
const val ROOM_COMPANION_DESCRIPTION: String = "district-room-companion"
const val ROOM_BANNER_IDLE_DESCRIPTION: String = "district-room-banner-idle"
const val ROOM_BANNER_CONNECTING_DESCRIPTION: String = "district-room-banner-connecting"
const val ROOM_BANNER_RECONNECTING_DESCRIPTION: String = "district-room-banner-reconnecting"
const val ROOM_BANNER_LEFT_DESCRIPTION: String = "district-room-banner-left"
const val ROOM_BANNER_FAILED_DESCRIPTION: String = "district-room-banner-failed"
const val ROOM_JOIN_FAILURE_DESCRIPTION: String = "district-room-join-failure"
const val ROOM_VIEWER_NOTICE_DESCRIPTION: String = "district-room-viewer-notice"
const val ROOM_NO_MIC_DESCRIPTION: String = "district-room-no-mic"
const val ROOM_NO_CAMERA_DESCRIPTION: String = "district-room-no-camera"
const val ROOM_MIC_DESCRIPTION: String = "district-room-mic"
const val ROOM_CAMERA_DESCRIPTION: String = "district-room-camera"
const val ROOM_FLIP_DESCRIPTION: String = "district-room-flip"
const val ROOM_SPEAKER_DESCRIPTION: String = "district-room-speaker"
const val ROOM_LEAVE_DESCRIPTION: String = "district-room-leave"
const val ROOM_SHARE_DESCRIPTION: String = "district-room-share"

@Preview(showBackground = true)
@Composable
private fun ActiveRoomScreenPreview() {
    DistrictTheme {
        ActiveRoomScreen(
            state = ActiveRoomUiState(
                connection = CallConnectionState.Connected,
                participants = listOf(
                    MediaParticipant(identity = "user-4f2a", name = "Ada"),
                    MediaParticipant(
                        identity = "user-9c11",
                        name = "Grace",
                        isMicrophoneEnabled = false,
                    ),
                ),
                companionPresent = true,
                permissions = RoomPermissions(
                    requested = true,
                    microphoneGranted = true,
                    cameraGranted = false,
                ),
                guestLink = "https://www.distronode.com/meet/meet_ws_standup?e=1&s=x",
            ),
            roomName = "meet_ws_standup",
            controls = RoomControls({}, {}, {}, {}),
            onLeave = {},
            onShareInvite = {},
        )
    }
}
