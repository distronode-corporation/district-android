package com.distronode.districtai.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.ui.resolve

/**
 * The overlay that shows one meeting's full record.
 *
 * ⛔ A SEPARATE FILE FROM [RoomsLobbyScreen] BECAUSE detekt CAPS A FILE AT 11 FUNCTIONS AND THE
 * LOBBY CROSSED IT. The split follows a real seam rather than an arbitrary one: this is the only
 * place in the app that displays a meeting TRANSCRIPT, and keeping it in its own file makes that
 * surface one file to review rather than a section of a longer one.
 */

/**
 * The full record of one meeting.
 *
 * ⛔ THE TRANSCRIPT IS BEHIND AN EXPLICIT SECTION LABEL AND IS NOT THE FIRST THING SHOWN. It is
 * every word everybody said, unredacted; the summary is what somebody opening this actually wants,
 * and putting the raw conversation first would mean the sensitive thing is what appears on screen
 * before anyone has decided to read it.
 *
 * ⚠️ SCROLLS INTERNALLY. A transcript is arbitrarily long and a dialog that grew with it would push
 * its own dismiss control off the screen.
 */
@Composable
internal fun MeetingRecordDialog(detail: MeetingDetailState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = ROOMS_RECORD_CLOSE_DESCRIPTION },
            ) {
                Text(stringResource(R.string.rooms_record_close))
            }
        },
        title = { Text(stringResource(R.string.rooms_record_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .semantics { contentDescription = ROOMS_RECORD_DESCRIPTION },
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                when (detail) {
                    MeetingDetailState.Loading -> SkeletonBlock(height = DistrictTheme.spacing.header)
                    is MeetingDetailState.Ready -> MeetingRecordBody(detail)
                    is MeetingDetailState.Failed -> Text(
                        text = detail.failure.message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.destructive,
                        modifier = Modifier.semantics {
                            contentDescription = ROOMS_RECORD_FAILURE_DESCRIPTION
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun MeetingRecordBody(detail: MeetingDetailState.Ready) {
    Eyebrow(stringResource(R.string.rooms_record_minutes))
    Text(
        // ⚠️ An in-progress meeting genuinely has no minutes yet, and that gets its own sentence
        // rather than an empty block — an empty block reads as a fault.
        text = detail.meeting.summary?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.rooms_no_minutes_yet),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.foreground,
    )
    detail.meeting.transcript?.takeIf { it.isNotBlank() }?.let { transcript ->
        Eyebrow(
            text = stringResource(R.string.rooms_record_transcript),
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        Text(
            text = transcript,
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.semantics {
                contentDescription = ROOMS_RECORD_TRANSCRIPT_DESCRIPTION
            },
        )
    }
}

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val ROOMS_RECORD_DESCRIPTION: String = "district-rooms-record"
const val ROOMS_RECORD_CLOSE_DESCRIPTION: String = "district-rooms-record-close"
const val ROOMS_RECORD_TRANSCRIPT_DESCRIPTION: String = "district-rooms-record-transcript"
const val ROOMS_RECORD_FAILURE_DESCRIPTION: String = "district-rooms-record-failure"
