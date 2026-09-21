package com.distronode.districtai.ui.rooms

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.StatusDot
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve

/**
 * Everything the room draws AROUND the participant grid: the connection banner, the Companion
 * chip, the permission notices and the join failure.
 *
 * ⛔ A SEPARATE FILE FROM [ActiveRoomScreen] BECAUSE detekt CAPS A FILE AT 11 FUNCTIONS AND THE
 * SCREEN CROSSED IT. That ceiling is worth respecting rather than suppressing here: the split is
 * along a real seam — nothing in this file touches a participant, a track or a control, so the
 * grid and the chrome genuinely have no shared state. If either ever needs the other's data,
 * that is the signal to reconsider, not to widen the suppression.
 */

/**
 * ⛔ RENDERED FOR EVERY STATE EXCEPT `Connected`, INCLUDING `Reconnecting` — and `Reconnecting` is
 * toned as INFORMATION, not as danger. Colouring a recoverable radio handover red is how a user
 * learns to hang up on something that was about to fix itself.
 */
@Composable
internal fun ConnectionBanner(connection: CallConnectionState) {
    val banner = when (connection) {
        CallConnectionState.Idle ->
            Banner(R.string.room_state_idle, Tone.Neutral, ROOM_BANNER_IDLE_DESCRIPTION)
        CallConnectionState.Connecting ->
            Banner(R.string.room_state_connecting, Tone.Neutral, ROOM_BANNER_CONNECTING_DESCRIPTION)
        // ⛔ Warning, NOT Danger. Colouring a recoverable radio handover red is how a user learns
        // to hang up on something that was about to fix itself.
        CallConnectionState.Reconnecting ->
            Banner(R.string.room_state_reconnecting, Tone.Warning, ROOM_BANNER_RECONNECTING_DESCRIPTION)
        is CallConnectionState.Disconnected ->
            Banner(R.string.room_state_left, Tone.Neutral, ROOM_BANNER_LEFT_DESCRIPTION)
        is CallConnectionState.Failed ->
            Banner(R.string.room_state_failed, Tone.Danger, ROOM_BANNER_FAILED_DESCRIPTION)
        CallConnectionState.Connected -> return
    }
    DistrictCard(modifier = Modifier.semantics { contentDescription = banner.handle }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(tone = banner.tone)
            Text(
                text = stringResource(banner.text),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.foreground,
                modifier = Modifier.padding(start = DistrictTheme.spacing.tight),
            )
        }
    }
}

/**
 * ⛔ SAYS WHAT IT IS DOING, NOT MERELY THAT IT IS HERE. "Companion" alone means nothing to somebody
 * who did not build this; "taking notes" is the fact that matters to a person deciding what to say
 * in the room.
 */
@Composable
internal fun CompanionChip() {
    DistrictCard(modifier = Modifier.semantics { contentDescription = ROOM_COMPANION_DESCRIPTION }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(tone = Tone.District)
            Text(
                text = stringResource(R.string.room_companion_notes),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.padding(start = DistrictTheme.spacing.tight),
            )
        }
    }
}

/**
 * ⚠️ NOTHING IS SAID UNTIL THE PERMISSIONS HAVE ACTUALLY BEEN REQUESTED. Before that both flags are
 * false and mean nothing, and a "microphone blocked" warning in that window would be telling the
 * user something untrue about their own device. See [RoomPermissions.requested].
 *
 * ⚠️ AND A VIEWER IS TOLD WHY ITS CONTROLS ARE OFF, which is a different reason from a denied
 * permission — one is the workspace's role and the other is the phone's settings, and the remedy
 * differs.
 */
@Composable
internal fun PermissionNotices(state: ActiveRoomUiState) {
    if (!state.canPublish) {
        Notice(R.string.room_viewer_notice, ROOM_VIEWER_NOTICE_DESCRIPTION)
        return
    }
    if (!state.permissions.requested) return
    if (!state.permissions.microphoneGranted) {
        Notice(R.string.room_no_microphone, ROOM_NO_MIC_DESCRIPTION)
    }
    if (!state.permissions.cameraGranted) {
        Notice(R.string.room_no_camera, ROOM_NO_CAMERA_DESCRIPTION)
    }
}

@Composable
private fun Notice(text: Int, handle: String) {
    DistrictCard(modifier = Modifier.semantics { contentDescription = handle }) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
        )
    }
}

@Composable
internal fun JoinFailure(failure: FailureText) {
    DistrictCard(modifier = Modifier.semantics { contentDescription = ROOM_JOIN_FAILURE_DESCRIPTION }) {
        Eyebrow(stringResource(R.string.room_join_failed))
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
    }
}

/**
 * One banner's three properties, bundled.
 *
 * ⚠️ A NAMED TYPE RATHER THAN A `Triple`, because a triple of (Int, Tone, String) reads as three
 * unrelated values at the destructuring site and would let two of them be swapped silently — and
 * one of the three is what decides whether a recoverable reconnect is drawn as an error.
 */
private data class Banner(val text: Int, val tone: Tone, val handle: String)
