// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.hq.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictIcons
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.HqAgentState
import com.distronode.districtai.l10n.WireCategory
import com.distronode.districtai.l10n.wireLabel
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.resolve

/**
 * District HQ by voice, above the text console's composer: Talk, the consent notice, the session's
 * state, its transcript, HQ's proposals and the microphone. Ported from iOS (`HQVoiceBar.swift`).
 *
 * ⚠️ THE TEXT CONSOLE STAYS USABLE IN EVERY STATE, a refused or failed session included.
 *
 * @param workspaceRegion the workspace's region, for the notice's region line; null when unknown.
 */
@Composable
fun HqVoiceBar(
    state: HqVoiceUiState,
    canConfirm: Boolean,
    workspaceRegion: String?,
    actions: HqVoiceActions,
) {
    ContentContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DistrictTheme.spacing.gutter, vertical = DistrictTheme.spacing.tight)
                .testTag(HQ_VOICE_BAR_DESCRIPTION),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            when (val phase = state.phase) {
                HqVoicePhase.Idle -> Idle(UiText.Resource(R.string.hq_voice_mic_explainer), false, actions)
                is HqVoicePhase.Ended -> Idle(phase.reason.sentence(), phase.reason.isFailure, actions)
                // ⛔ STOP WHILE MINTING AND JOINING TOO: the session gives back a token still in
                // flight and disconnects a join that completes after it.
                HqVoicePhase.Minting, is HqVoicePhase.Notice, is HqVoicePhase.Permission, HqVoicePhase.Connecting ->
                    Connecting(actions.onStop)
                HqVoicePhase.Live -> Live(state, canConfirm, actions)
            }
        }
    }
    (state.phase as? HqVoicePhase.Notice)?.let { notice ->
        val region = workspaceRegion?.takeIf { it != notice.credential.processingRegion }
        HqVoiceNotice(
            regionLine = region?.let {
                stringResource(
                    R.string.hq_voice_notice_region,
                    wireLabel(WireCategory.REGION, it)
                )
            },
            onStart = actions.onNoticeStart,
            onCancel = actions.onNoticeCancel,
        )
    }
}

@Composable
private fun Idle(note: UiText, isFailure: Boolean, actions: HqVoiceActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DistrictButton(
            text = stringResource(R.string.hq_voice_start),
            onClick = actions.onStart,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.testTag(HQ_VOICE_START_DESCRIPTION),
        )
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = actions.onOpenSettings, modifier = Modifier.testTag(HQ_VOICE_SETTINGS_DESCRIPTION)) {
                Icon(
                    imageVector = DistrictIcons.Settings,
                    contentDescription = stringResource(R.string.hq_voice_settings),
                    tint = DistrictTheme.colors.mutedForeground,
                )
            }
        }
    }
    Text(
        text = note.resolve(),
        style = MaterialTheme.typography.bodySmall,
        color = if (isFailure) DistrictTheme.colors.destructive else DistrictTheme.colors.mutedForeground,
        modifier = Modifier.testTag(HQ_VOICE_NOTE_DESCRIPTION),
    )
}

@Composable
private fun Connecting(onStop: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        modifier = Modifier.testTag(HQ_VOICE_CONNECTING_DESCRIPTION),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(PROGRESS_SIZE),
            color = DistrictTheme.colors.district,
            strokeWidth = PROGRESS_STROKE,
        )
        Text(
            text = stringResource(R.string.hq_voice_connecting),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.weight(1f),
        )
        StopButton(onStop)
    }
}

@Composable
private fun Live(state: HqVoiceUiState, canConfirm: Boolean, actions: HqVoiceActions) {
    Text(
        text = stringResource(state.agentState.label()),
        style = MaterialTheme.typography.titleSmall,
        color = DistrictTheme.colors.foreground,
        // ⚠️ A POLITE LIVE REGION: TalkBack reads a change of state once, without a second
        // announcement of the same words.
        modifier = Modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(HQ_VOICE_STATE_DESCRIPTION),
    )
    LiveControls(state, actions)
    Transcript(state.segments)
    state.proposals.forEach { card -> ProposalCard(card, canConfirm, actions) }
}

@Composable
private fun StopButton(onStop: () -> Unit) {
    DistrictButton(
        text = stringResource(R.string.hq_voice_stop),
        onClick = onStop,
        variant = ButtonVariant.Ghost,
        size = ButtonSize.Sm,
        modifier = Modifier.testTag(HQ_VOICE_STOP_DESCRIPTION),
    )
}

/** The last four final segments; the member's own lines sit right and are marked "You". */
@Composable
private fun Transcript(segments: List<HqVoiceSegment>) {
    if (segments.isEmpty()) return
    Column(
        modifier = Modifier.testTag(HQ_VOICE_TRANSCRIPT_DESCRIPTION),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
    ) {
        segments.takeLast(TRANSCRIPT_LINES).forEach { segment ->
            val alignment = if (segment.fromMember) Alignment.End else Alignment.Start
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
                Text(
                    text = stringResource(if (segment.fromMember) R.string.hq_voice_you else R.string.hq_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = DistrictTheme.colors.mutedForeground,
                )
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.foreground,
                    textAlign = if (segment.fromMember) TextAlign.End else TextAlign.Start,
                )
            }
        }
    }
}

private fun HqAgentState?.label(): Int = when (this) {
    HqAgentState.THINKING -> R.string.hq_voice_thinking
    HqAgentState.SPEAKING -> R.string.hq_voice_speaking
    HqAgentState.LISTENING, null -> R.string.hq_voice_listening
}

/** The sentence under Talk once a session has ended. */
internal fun HqVoiceEndReason.sentence(): UiText = when (this) {
    HqVoiceEndReason.ByMember, HqVoiceEndReason.Disconnected, HqVoiceEndReason.AgentLeft ->
        UiText.Resource(R.string.hq_voice_ended)
    HqVoiceEndReason.Interrupted -> UiText.Resource(R.string.hq_voice_ended_interrupted)
    HqVoiceEndReason.TimedOut -> UiText.Resource(R.string.hq_voice_ended_timeout)
    HqVoiceEndReason.Busy -> UiText.Resource(R.string.hq_voice_busy)
    HqVoiceEndReason.NoAgent -> UiText.Resource(R.string.hq_voice_no_agent)
    HqVoiceEndReason.MicrophoneDenied -> UiText.Resource(R.string.hq_voice_mic_denied)
    HqVoiceEndReason.JoinFailed -> UiText.Resource(R.string.hq_voice_join_failed)
    is HqVoiceEndReason.Refused -> failure.message
    is HqVoiceEndReason.Failed -> failure.message
}

private const val TRANSCRIPT_LINES = 4
private val PROGRESS_SIZE = 16.dp
private val PROGRESS_STROKE = 2.dp

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val HQ_VOICE_BAR_DESCRIPTION: String = "district-hq-voice-bar"
const val HQ_VOICE_START_DESCRIPTION: String = "district-hq-voice-start"
const val HQ_VOICE_SETTINGS_DESCRIPTION: String = "district-hq-voice-settings"
const val HQ_VOICE_NOTE_DESCRIPTION: String = "district-hq-voice-ended"
const val HQ_VOICE_CONNECTING_DESCRIPTION: String = "district-hq-voice-connecting"
const val HQ_VOICE_STATE_DESCRIPTION: String = "district-hq-voice-state"
const val HQ_VOICE_STOP_DESCRIPTION: String = "district-hq-voice-stop"
const val HQ_VOICE_MUTE_DESCRIPTION: String = "district-hq-voice-mute"
const val HQ_VOICE_HOLD_DESCRIPTION: String = "district-hq-voice-hold-to-talk"
const val HQ_VOICE_TRANSCRIPT_DESCRIPTION: String = "district-hq-voice-transcript"
const val HQ_VOICE_NOTICE_DESCRIPTION: String = "district-hq-voice-notice"
const val HQ_VOICE_NOTICE_REGION_DESCRIPTION: String = "district-hq-voice-notice-region"
const val HQ_VOICE_NOTICE_START_DESCRIPTION: String = "district-hq-voice-notice-start"
const val HQ_VOICE_NOTICE_CANCEL_DESCRIPTION: String = "district-hq-voice-notice-cancel"

fun hqVoiceProposalTag(id: String): String = "district-hq-voice-proposal-$id"
