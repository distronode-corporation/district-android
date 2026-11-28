// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.hq.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.HqMicMode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.max

/**
 * The live bar's two controls: the microphone (Hold to talk, or Mute) and End voice.
 *
 * ⛔ ONE TREATMENT AND ONE HEIGHT (visual QA): equal halves of their own row, both drawn as the
 * secondary small button (the card fill, the border, the 8 dp corner, the 32 dp drawn height inside
 * Material's 48 dp touch target), instead of one pill beside bare text.
 *
 * ⛔ AND ONE HEIGHT AT ANY FONT SIZE: when one label wraps at half the row's width (French, a large
 * font scale), both labels take that many lines, so the two surfaces grow together. ⚠️ Not done by
 * stretching both to the row's height: that would draw the fill over the whole touch target on one
 * line, and these would be the only 48 dp small buttons in the app.
 */
@Composable
internal fun LiveControls(state: HqVoiceUiState, actions: HqVoiceActions) {
    val mic = stringResource(
        when {
            state.micMode == HqMicMode.PUSH -> R.string.hq_voice_hold_to_talk
            state.isMicrophoneOn -> R.string.hq_voice_mute
            else -> R.string.hq_voice_unmute
        },
    )
    val stop = stringResource(R.string.hq_voice_stop)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val lines = sharedLines(mic, stop, maxWidth)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            val half = Modifier.weight(1f)
            if (state.micMode == HqMicMode.PUSH) {
                HoldToTalk(state.isMicrophoneOn, actions.onHold, lines, half)
            } else {
                ControlButton(mic, lines, actions.onToggleMute, half.testTag(HQ_VOICE_MUTE_DESCRIPTION))
            }
            ControlButton(stop, lines, actions.onStop, half.testTag(HQ_VOICE_STOP_DESCRIPTION))
        }
    }
}

/** The more lines [first] or [second] takes in half of [rowWidth], inside a control's padding. */
@Composable
private fun sharedLines(first: String, second: String, rowWidth: Dp): Int {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val inner = (rowWidth - DistrictTheme.spacing.tight) / 2 - DistrictTheme.spacing.row * 2
    val width = with(LocalDensity.current) { inner.roundToPx() }
    fun lines(text: String) = measurer.measure(text, style, constraints = Constraints(maxWidth = width)).lineCount
    return max(lines(first), lines(second))
}

/** A control's label: centred, and given [lines] lines so the pair stays one height. */
@Composable
private fun ControlLabel(text: String, lines: Int) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = DistrictTheme.colors.foreground,
        textAlign = TextAlign.Center,
        minLines = lines,
    )
}

/** Mute, Unmute or End voice, drawn as the secondary small button. */
@Composable
private fun ControlButton(text: String, lines: Int, onClick: () -> Unit, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .controlSurface(held = false)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DistrictTheme.spacing.row, vertical = CONTROL_VERTICAL_PADDING),
    ) {
        ControlLabel(text, lines)
    }
}

/** The secondary small button's surface, with the accent fill while [held]. */
@Composable
private fun Modifier.controlSurface(held: Boolean): Modifier = this
    .minimumInteractiveComponentSize()
    .defaultMinSize(minHeight = CONTROL_MIN_HEIGHT)
    .clip(CONTROL_SHAPE)
    .background(if (held) DistrictTheme.colors.district.copy(alpha = HELD_ALPHA) else DistrictTheme.colors.card)
    .border(1.dp, DistrictTheme.colors.border, CONTROL_SHAPE)

/**
 * Hold to talk: the microphone is on while this is held down, with a finger or with Space or Enter
 * on a keyboard, and off when it is let go, when the press is cancelled (the finger slides off),
 * when the control loses focus and when it leaves the screen. One `true` per press, one `false`
 * after it, as the Windows app does.
 *
 * ⛔ TALKBACK'S DOUBLE TAP TOGGLES IT INSTEAD, as VoiceOver's does on iOS: a screen-reader user
 * cannot hold a control under a finger that is also exploring the screen.
 */
@Composable
private fun HoldToTalk(isOn: Boolean, onHold: (Boolean) -> Unit, lines: Int, modifier: Modifier) {
    val hold by rememberUpdatedState(onHold)
    val on by rememberUpdatedState(isOn)
    DisposableEffect(Unit) { onDispose { hold(false) } }
    // ⚠️ NOT A BUTTON: a button's own click would fire on release and turn the microphone back on.
    // Held, it takes the accent fill.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .controlSurface(held = isOn)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    hold(true)
                    waitForUpOrCancellation()
                    hold(false)
                }
            }
            .onPreviewKeyEvent { event ->
                val talkKey = event.key == Key.Spacebar || event.key == Key.Enter || event.key == Key.NumPadEnter
                when {
                    !talkKey -> false
                    event.type == KeyEventType.KeyDown -> {
                        // ⚠️ A HELD KEY REPEATS; the press opens the microphone once.
                        if (!on) hold(true)
                        true
                    }
                    event.type == KeyEventType.KeyUp -> {
                        hold(false)
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { if (!it.isFocused && on) hold(false) }
            .focusable()
            // ⚠️ MERGED, so the button's name is its "Hold to talk" text.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected = isOn
                onClick {
                    hold(!on)
                    true
                }
            }
            .padding(horizontal = DistrictTheme.spacing.row, vertical = CONTROL_VERTICAL_PADDING)
            .testTag(HQ_VOICE_HOLD_DESCRIPTION),
    ) {
        ControlLabel(stringResource(R.string.hq_voice_hold_to_talk), lines)
    }
}

private const val HELD_ALPHA = 0.24f
private val CONTROL_MIN_HEIGHT = 32.dp
private val CONTROL_VERTICAL_PADDING = 6.dp
private val CONTROL_SHAPE = RoundedCornerShape(8.dp)
