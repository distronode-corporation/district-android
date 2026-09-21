package com.distronode.districtai.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.ui.resolve

/**
 * One call in full.
 *
 * ⚠️ NO EMBEDDED AUDIO PLAYER. Playback is handed to whatever app the device already has, via the
 * resolved URL. An in-app player means a media dependency, playback-state handling, and a
 * foreground service to keep audio alive when the screen is backgrounded — real work that belongs
 * with the telephony surfaces rather than bolted onto a detail screen. Handing off is honest and
 * works today.
 */
@Composable
fun CallDetailScreen(
    state: CallDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onShowTranscript: () -> Unit,
    onPlayRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = CALL_DETAIL_ROOT_DESCRIPTION },
        topBar = {
            // ⚠️ The back affordance lives IN the app bar. A text button at the bottom of the
            // FAILURE state only would leave a call that loaded successfully with no visible way
            // back at all, only the system gesture.
            DistrictTopBar(title = stringResource(R.string.call_detail_title), onBack = onBack)
        },
    ) { inset ->
        // ⛔ THE INSET WRAPS *EVERY* STATE, NOT JUST THE LOADED ONE. `DistrictScaffold` hands back the
        // app-bar padding as a modifier, and passing it only to the populated branch leaves the
        // loading, empty and failure states rendering UNDERNEATH the 56dp bar. Threading the inset
        // by hand into a single branch is easy to get wrong on every screen that does it; a Box
        // around the `when` makes it structural instead.
        Box(modifier = inset.fillMaxSize()) {
            when (state) {
                CallDetailUiState.Loading -> Centered {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = CALL_DETAIL_LOADING_DESCRIPTION
                        },
                    )
                }

                is CallDetailUiState.Failed -> Centered(
                    modifier = Modifier.semantics {
                        contentDescription = CALL_DETAIL_FAILURE_DESCRIPTION
                    },
                ) {
                    Text(
                        text = state.failure.message.resolve(),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (state.failure.retryable) {
                        DistrictButton(
                            text = stringResource(R.string.overview_retry),
                            onClick = onRetry,
                            modifier = Modifier.padding(top = DistrictTheme.spacing.gutter),
                        )
                    }
                }

                is CallDetailUiState.Content ->
                    Content(state, onShowTranscript, onPlayRecording)
            }
        }
    }
}

@Composable
private fun Content(
    state: CallDetailUiState.Content,
    onShowTranscript: () -> Unit,
    onPlayRecording: () -> Unit,
) {
    val call = state.call
    // ⛔ THE SAME MAPPER THE LOG USES. Deriving these inline is what let this screen and the log
    // disagree about the duration — see [CallDisplay].
    val display = call.toDisplay()

    ContentContainer(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentPadding = PaddingValues(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        Text(
            text = display.displayName ?: stringResource(R.string.overview_no_caller_id),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = buildString {
                append(
                    stringResource(
                        if (display.outbound) {
                            R.string.overview_direction_outbound
                        } else {
                            R.string.overview_direction_inbound
                        },
                    ),
                )
                append(" · ")
                // Pre-formatted in the OPERATOR's timezone server-side. Reformatting locally would
                // render it in the device's zone and disagree with the browser.
                append(display.time)
                // ⛔ GUARDED, NOT APPENDED UNCONDITIONALLY. The server always formats a duration
                // string, so a missed call (which never connected) would read "· 0s" here while the
                // call log correctly shows nothing.
                display.durationLabel?.let {
                    append(" · ")
                    append(it)
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            // ⚠️ Via the mapper, so a live call reads "Live" here exactly as it does in the log
            // rather than showing the raw wire status on one screen and the label on the other.
            text = if (display.live) {
                stringResource(R.string.overview_status_live)
            } else {
                display.status
            },
            style = MaterialTheme.typography.labelLarge,
        )

        LabelledCard(stringResource(R.string.call_detail_summary), call.aiSummary)

        call.sentiment?.let { LabelledCard(stringResource(R.string.call_detail_sentiment), it) }
        call.disposition?.let { LabelledCard(stringResource(R.string.call_detail_disposition), it) }
        call.transferStatus?.let {
            LabelledCard(
                stringResource(R.string.call_detail_transfer),
                listOfNotNull(it, call.transferReason).joinToString(" · "),
            )
        }
        // ⚠️ The wire key is `sms`, not `smsBody` — the column is followUpSMSBody and the handler
        // renames it. Present only when a follow-up was actually sent.
        call.followUp?.let { followUp ->
            LabelledCard(
                stringResource(R.string.call_detail_follow_up),
                listOfNotNull(followUp.email, followUp.sms).joinToString("\n").ifBlank { "—" },
            )
        }

        // Every analysis field is optional with a default because the column is unstructured JSON,
        // so each list is rendered only when it actually has content.
        call.analysis?.let { analysis ->
            ListCard(stringResource(R.string.call_detail_key_points), analysis.keyPoints)
            ListCard(stringResource(R.string.call_detail_objections), analysis.objections)
            ListCard(stringResource(R.string.call_detail_topics), analysis.topics)
            ListCard(stringResource(R.string.call_detail_action_items), analysis.actionItems)
        }

        RecordingSection(state, onPlayRecording)
        TranscriptSection(state, onShowTranscript)
    }
}

@Composable
private fun RecordingSection(state: CallDetailUiState.Content, onPlayRecording: () -> Unit) {
    when (val recording = state.recording) {
        RecordingState.Idle ->
            // ⚠️ Offered based on the row's recordingUrl, which is a hint rather than the whole
            // truth: an archived copy lives under a key this shape does not expose, so the server
            // can still produce a URL when this is absent. Offering is the friendlier mistake.
            if (state.mayHaveRecording) {
                Button(
                    onClick = onPlayRecording,
                    modifier = Modifier.semantics {
                        contentDescription = CALL_DETAIL_PLAY_DESCRIPTION
                    },
                ) {
                    Text(stringResource(R.string.call_detail_recording_play))
                }
            }

        RecordingState.Resolving -> CircularProgressIndicator(color = DistrictTheme.colors.district)

        // Ordinary for a missed call, so it reads as a fact rather than a failure.
        RecordingState.Absent -> Text(
            text = stringResource(R.string.call_detail_recording_absent),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics {
                contentDescription = CALL_DETAIL_NO_RECORDING_DESCRIPTION
            },
        )

        is RecordingState.Failed -> Text(
            text = recording.failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
        )
    }
}

@Composable
private fun TranscriptSection(state: CallDetailUiState.Content, onShowTranscript: () -> Unit) {
    when (val transcript = state.transcript) {
        // Not fetched until asked for — transcripts are large enough that the timeline stopped
        // embedding them.
        TranscriptState.Idle -> TextButton(
            onClick = onShowTranscript,
            modifier = Modifier.semantics {
                contentDescription = CALL_DETAIL_SHOW_TRANSCRIPT_DESCRIPTION
            },
        ) {
            Text(stringResource(R.string.call_detail_transcript_show))
        }

        TranscriptState.Loading -> CircularProgressIndicator(color = DistrictTheme.colors.district)

        is TranscriptState.Loaded -> LabelledCard(
            stringResource(R.string.call_detail_transcript),
            transcript.text,
            description = CALL_DETAIL_TRANSCRIPT_DESCRIPTION,
        )

        // ⚠️ The server sends "" for a call with no transcript, so this is the empty case rather
        // than an error.
        TranscriptState.Absent -> Text(
            text = stringResource(R.string.call_detail_transcript_absent),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics {
                contentDescription = CALL_DETAIL_NO_TRANSCRIPT_DESCRIPTION
            },
        )

        is TranscriptState.Failed -> Text(
            text = transcript.failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
        )
    }
}

@Composable
private fun LabelledCard(label: String, value: String, description: String? = null) {
    if (value.isBlank()) return
    DistrictCard {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .then(
                    description?.let { Modifier.semantics { contentDescription = it } } ?: Modifier,
                ),
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
            )
        }
    }
}

@Composable
private fun ListCard(label: String, values: List<String>) {
    if (values.isEmpty()) return
    LabelledCard(label, values.joinToString("\n") { "• $it" })
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(DistrictTheme.spacing.section),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

/** Stable handles for tests. */
const val CALL_DETAIL_ROOT_DESCRIPTION: String = "district-call-detail-root"
const val CALL_DETAIL_LOADING_DESCRIPTION: String = "district-call-detail-loading"
const val CALL_DETAIL_FAILURE_DESCRIPTION: String = "district-call-detail-failure"
const val CALL_DETAIL_SHOW_TRANSCRIPT_DESCRIPTION: String = "district-call-detail-show-transcript"
const val CALL_DETAIL_TRANSCRIPT_DESCRIPTION: String = "district-call-detail-transcript"
const val CALL_DETAIL_NO_TRANSCRIPT_DESCRIPTION: String = "district-call-detail-no-transcript"
const val CALL_DETAIL_PLAY_DESCRIPTION: String = "district-call-detail-play-recording"
const val CALL_DETAIL_NO_RECORDING_DESCRIPTION: String = "district-call-detail-no-recording"
