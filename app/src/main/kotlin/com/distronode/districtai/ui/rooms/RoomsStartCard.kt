package com.distronode.districtai.ui.rooms

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow

/**
 * The form that starts or joins a room.
 *
 * ⛔ A SEPARATE FILE FROM [RoomsLobbyScreen] BECAUSE detekt CAPS A FILE AT 11 FUNCTIONS AND THE
 * LOBBY CROSSED IT — and the cut is along the seam the lobby's own doc already describes: the join
 * form and the meetings history are unrelated server surfaces, and the form must stay usable when
 * the history read fails. Two files make that independence structural rather than a comment.
 */

@Composable
internal fun StartRoomCard(
    state: RoomsLobbyUiState,
    onRoomNameChange: (String) -> Unit,
    onJoin: () -> Unit,
) {
    DistrictCard(modifier = Modifier.semantics { contentDescription = ROOMS_START_DESCRIPTION }) {
        Eyebrow(stringResource(R.string.rooms_start_label))
        OutlinedTextField(
            value = state.roomName,
            onValueChange = onRoomNameChange,
            label = { Eyebrow(stringResource(R.string.rooms_name_label)) },
            colors = districtFieldColors(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DistrictTheme.spacing.tight)
                .semantics { contentDescription = ROOMS_NAME_FIELD_DESCRIPTION },
        )
        // ⛔ SHOWN WHILE TYPING, NOT APPLIED TO THE FIELD. The name is lower-cased and hyphenated
        // before it reaches the server, and a field that rewrote itself would move the cursor and
        // eat spaces mid-word. Two people who typed "Weekly Review" and "weekly review" are in the
        // SAME room, and this is the only place that is visible before they find out the hard way.
        Text(
            text = if (state.canJoin) {
                stringResource(R.string.rooms_name_preview, state.normalizedName)
            } else {
                stringResource(R.string.rooms_name_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier
                .padding(top = DistrictTheme.spacing.hairline)
                .semantics { contentDescription = ROOMS_NAME_PREVIEW_DESCRIPTION },
        )
        DistrictButton(
            text = stringResource(R.string.rooms_join),
            onClick = onJoin,
            enabled = state.canJoin,
            modifier = Modifier
                .padding(top = DistrictTheme.spacing.row)
                .semantics { contentDescription = ROOMS_JOIN_DESCRIPTION },
        )
        // ⚠️ SAYS THE COMPANION WILL BE THERE, BEFORE ANYONE JOINS. The transcription agent is
        // dispatched into every meet_ room automatically; being told about it after the fact is
        // being told too late.
        Text(
            text = stringResource(R.string.rooms_companion_notice),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier
                .padding(top = DistrictTheme.spacing.tight)
                .semantics { contentDescription = ROOMS_COMPANION_NOTICE_DESCRIPTION },
        )
    }
}

/**
 * ⚠️ A LOCAL COPY OF THE FIELD COLOURS, matching `CreateContactDialog`'s. `OutlinedTextField` draws
 * its own Material greys for the container and placeholder, which are not this theme's
 * `--muted`/`--muted-foreground`.
 */
@Composable
private fun districtFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = DistrictTheme.colors.muted,
    unfocusedContainerColor = DistrictTheme.colors.muted,
    disabledContainerColor = DistrictTheme.colors.muted,
    focusedBorderColor = DistrictTheme.colors.district,
    unfocusedBorderColor = DistrictTheme.colors.border,
    focusedTextColor = DistrictTheme.colors.foreground,
    unfocusedTextColor = DistrictTheme.colors.foreground,
    cursorColor = DistrictTheme.colors.district,
)

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val ROOMS_START_DESCRIPTION: String = "district-rooms-start"
const val ROOMS_NAME_FIELD_DESCRIPTION: String = "district-rooms-name"
const val ROOMS_NAME_PREVIEW_DESCRIPTION: String = "district-rooms-name-preview"
const val ROOMS_JOIN_DESCRIPTION: String = "district-rooms-join"
const val ROOMS_COMPANION_NOTICE_DESCRIPTION: String = "district-rooms-companion-notice"
