package com.distronode.districtai.ui.settings.workspace.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.Tone

/** The signal chain: one block per leg (one for a realtime engine); tapping a block opens its editor. */
@Composable
internal fun ChainSection(state: VoiceStudioUiState.Ready, actions: VoiceStudioActions) {
    Eyebrow(state.studio.labels.chainLabel)
    StudioReadout.blocks(state.held.engine, state.studio).forEach { block ->
        val notMeasured = state.studio.labels.notMeasured
        ChainBlock(block, selected = block.leg == state.leg, notMeasured = notMeasured) @DontMemoize {
            actions.selectLeg(block.leg)
        }
    }
}

/** One block of the signal chain. Tapping it opens that leg's editor. */
@Composable
private fun ChainBlock(block: BlockView, selected: Boolean, notMeasured: String, onClick: () -> Unit) {
    DistrictCard(
        onClick = onClick,
        modifier = Modifier.semantics(
            properties = @DontMemoize { contentDescription = studioHandle(HANDLE_BLOCK, block.leg, selected) },
        ),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            Eyebrow(
                block.title,
                color = if (selected) DistrictTheme.colors.district else DistrictTheme.colors.mutedForeground
            )
            DistrictBadge(block.channelLabel, tone = channelTone(block.channel))
        }
        Text(block.model, style = MaterialTheme.typography.titleSmall, color = DistrictTheme.colors.foreground)
        Caption(block.role)
        Text(block.where, style = MaterialTheme.typography.bodySmall, color = residencyColor(block.inRegion))
        Caption(latencyText(block.latency, notMeasured))
        block.note?.let { Caption(it) }
    }
}

/** Time to first word: the headline, each stage, and where the numbers come from. */
@Composable
internal fun MeterSection(state: VoiceStudioUiState.Ready) {
    val labels = state.studio.labels
    val meter = StudioReadout.meter(state.held.engine, state.studio)
    Eyebrow(labels.meterHeading)
    Caption(labels.meterDescription)
    val headline = meter.headline
    Text(
        // ⚠️ An `if` chain rather than an exhaustive `when`, whose synthetic last arm no test reaches.
        text = if (headline is MeterHeadline.Server) {
            headline.text
        } else if (headline is MeterHeadline.Local) {
            stringResource(
                if (headline.atLeast) R.string.voice_studio_meter_at_least else R.string.voice_studio_meter_about,
                formatMillis(headline.ms),
            )
        } else {
            labels.notMeasured
        },
        style = MaterialTheme.typography.titleMedium,
        color = DistrictTheme.colors.foreground,
        modifier = Modifier.semantics { contentDescription = VOICE_STUDIO_METER_DESCRIPTION },
    )
    meter.stages.forEach { stage -> Caption("${stage.label}: ${latencyText(stage.value, labels.notMeasured)}") }
    meter.note?.let { Caption(it) }
    Caption(state.studio.latency.sourceText)
}

/** Where the call is processed: the whole call, then each leg that leaves the region. */
@Composable
internal fun ResidencySection(state: VoiceStudioUiState.Ready) {
    val residency = StudioReadout.residency(state.held.engine, state.studio)
    Eyebrow(state.studio.labels.residencyHeading)
    Text(
        text = residency.text,
        style = MaterialTheme.typography.bodyMedium,
        color = residencyColor(residency.inRegion),
        modifier = Modifier.semantics { contentDescription = VOICE_STUDIO_RESIDENCY_DESCRIPTION },
    )
    residency.legsOut.forEach { Caption(it) }
}

@Composable
internal fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = DistrictTheme.colors.mutedForeground)
}

@Composable
private fun latencyText(latency: LatencyText, notMeasured: String): String =
    if (latency is LatencyText.Server) {
        latency.text
    } else if (latency is LatencyText.Millis) {
        stringResource(R.string.voice_studio_ms, formatMillis(latency.ms))
    } else {
        notMeasured
    }

/** ⚠️ Whole milliseconds in the device's own digits; the server's sentences carry their own. */
private fun formatMillis(ms: Double): String = java.text.NumberFormat.getIntegerInstance().format(ms)

/** ⛔ The badge TEXT carries the meaning; the tone only repeats it, as on the web. */
internal fun channelTone(channel: String): Tone = when (channel) {
    StudioRecipes.STABLE -> Tone.Success
    StudioRecipes.LATEST -> Tone.Info
    else -> Tone.Neutral
}

/** In region, out of region, or (the turn detector) not a place at all. */
@Composable
internal fun residencyColor(inRegion: Boolean?) =
    if (inRegion == true) {
        DistrictTheme.colors.success
    } else if (inRegion == false) {
        DistrictTheme.colors.warning
    } else {
        DistrictTheme.colors.mutedForeground
    }
