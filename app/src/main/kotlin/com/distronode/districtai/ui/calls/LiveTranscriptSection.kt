package com.distronode.districtai.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.TranscriptSegment
import com.distronode.districtai.core.model.TranscriptSpeaker
import com.distronode.districtai.core.network.live.FinalTranscriptState
import com.distronode.districtai.core.network.live.LiveTranscriptPhase
import com.distronode.districtai.ui.resolve
import com.distronode.districtai.ui.toFailureText

/**
 * The live transcript on a call's screen while the call is in progress, and the full transcript in
 * the same place once the call has ended (iOS `LiveTranscriptSection`).
 *
 * ⚠️ AN INTERIM LINE IS DRAWN AS PROVISIONAL (muted, italic): the server will replace it.
 */
@Composable
internal fun LiveTranscriptSection(state: LiveTranscriptController.State) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CALL_DETAIL_LIVE_TRANSCRIPT_TAG),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
            Text(
                text = stringResource(R.string.live_transcript_heading),
                style = MaterialTheme.typography.labelSmall,
                color = DistrictTheme.colors.mutedForeground,
            )
            Text(
                text = stringResource(liveStatus(state)),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
            )
        }
        if (!state.complete) {
            Note(
                text = stringResource(R.string.live_transcript_incomplete),
                modifier = Modifier.testTag(CALL_DETAIL_LIVE_INCOMPLETE_TAG),
            )
        }
        if (state.lines.isEmpty() && state.phase == LiveTranscriptPhase.Live) {
            Note(stringResource(R.string.live_transcript_waiting))
        }
        state.lines.forEach { Line(it) }
        Final(state.finalTranscript)
    }
}

/** The line under the heading. */
internal fun liveStatus(state: LiveTranscriptController.State): Int = when {
    state.phase is LiveTranscriptPhase.Ended -> R.string.live_transcript_ended
    state.phase == LiveTranscriptPhase.Reconnecting -> R.string.live_transcript_reconnecting
    state.phase == LiveTranscriptPhase.Live && state.connection == LiveTranscriptController.Connection.OPEN ->
        R.string.live_transcript_live
    else -> R.string.live_transcript_connecting
}

@Composable
private fun Line(segment: TranscriptSegment) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
    ) {
        Text(text = speakerLabel(segment), style = MaterialTheme.typography.labelLarge)
        Text(
            text = segment.text,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = if (segment.isFinal) FontStyle.Normal else FontStyle.Italic,
            color = if (segment.isFinal) DistrictTheme.colors.foreground else DistrictTheme.colors.mutedForeground,
        )
        if (segment.interrupted) Note(stringResource(R.string.live_transcript_interrupted))
    }
}

/**
 * The speaker's label. ⚠️ An assistant line names its persona when the server sends one; a role
 * this app does not know yet is a third party rather than a guess.
 */
@Composable
private fun speakerLabel(segment: TranscriptSegment): String = when (segment.speakerRole) {
    TranscriptSpeaker.CALLER -> stringResource(R.string.live_transcript_speaker_caller)
    TranscriptSpeaker.AGENT ->
        segment.speakerName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.live_transcript_speaker_assistant)
    TranscriptSpeaker.OTHER -> stringResource(R.string.live_transcript_speaker_other)
}

@Composable
private fun Final(final: FinalTranscriptState) {
    when (final) {
        FinalTranscriptState.NotRequested -> Unit
        is FinalTranscriptState.Fetching -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(PROGRESS_SIZE), color = DistrictTheme.colors.district)
            Note(stringResource(R.string.live_transcript_loading_full))
        }
        is FinalTranscriptState.Loaded -> DistrictCard {
            Column(
                modifier = Modifier
                    .padding(DistrictTheme.spacing.gutter)
                    .testTag(CALL_DETAIL_TRANSCRIPT_DESCRIPTION),
            ) {
                Text(
                    text = stringResource(R.string.live_transcript_full_transcript),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = final.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
                )
            }
        }
        FinalTranscriptState.Empty -> Note(stringResource(R.string.live_transcript_no_transcript))
        is FinalTranscriptState.Failed -> Text(
            text = final.failure.toFailureText().message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
        )
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

private val PROGRESS_SIZE = 16.dp

/** Stable handles for tests. */
const val CALL_DETAIL_LIVE_TRANSCRIPT_TAG: String = "district-call-detail-live-transcript"
const val CALL_DETAIL_LIVE_INCOMPLETE_TAG: String = "district-call-detail-live-incomplete"
