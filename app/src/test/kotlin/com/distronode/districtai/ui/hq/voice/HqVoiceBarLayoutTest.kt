// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.hq.voice

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.HqMicMode
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * How the live bar is laid out: its three controls are one weight, Hold to talk's label sits in
 * its middle, and the transcript's speaker labels are never cut, in French, on a narrow phone at a
 * large font scale.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w320dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HqVoiceBarLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(micMode: HqMicMode, fontScale: Float = 1f) {
        val state = HqVoiceUiState(
            phase = HqVoicePhase.Live,
            micMode = micMode,
            isMicrophoneOn = false,
            segments = listOf(
                HqVoiceSegment("1", "Change the greeting to good afternoon, please.", fromMember = true),
                HqVoiceSegment("2", "I can do that. Please confirm the new greeting.", fromMember = false),
            ),
        )
        composeRule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(base.density, fontScale),
            ) {
                DistrictTheme { HqVoiceBar(state, true, null, HqVoiceActions()) }
            }
        }
    }

    private fun height(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height

    @Test
    fun `Hold to talk, Mute and End voice are buttons of one height`() {
        render(HqMicMode.PUSH)
        val hold = height(HQ_VOICE_HOLD_DESCRIPTION)
        val stop = height(HQ_VOICE_STOP_DESCRIPTION)
        assertEquals("Hold to talk and End voice are the same height", stop, hold, 0.5f)
    }

    @Test
    fun `at twice the font size, a label that wraps grows both controls together`() {
        render(HqMicMode.PUSH, fontScale = 2f)
        assertEquals(height(HQ_VOICE_STOP_DESCRIPTION), height(HQ_VOICE_HOLD_DESCRIPTION), 0.5f)
    }

    @Test
    fun `Mute is the same height as End voice`() {
        render(HqMicMode.OPEN)

        assertEquals(height(HQ_VOICE_STOP_DESCRIPTION), height(HQ_VOICE_MUTE_DESCRIPTION), 0.5f)
    }

    @Test
    fun `Hold to talk's label sits in the middle of its pill`() {
        render(HqMicMode.PUSH)
        val pill = composeRule.onNodeWithTag(HQ_VOICE_HOLD_DESCRIPTION).fetchSemanticsNode().boundsInRoot
        val label = composeRule.onNodeWithText(str(R.string.hq_voice_hold_to_talk), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val tolerance = with(Density(composeRule.density.density)) { 1.dp.toPx() }

        assertTrue("vertically centred: $label in $pill", abs(label.center.y - pill.center.y) <= tolerance)
        assertTrue("horizontally centred: $label in $pill", abs(label.center.x - pill.center.x) <= tolerance)
    }

    /**
     * Whether any line of [text] is drawn outside its box: wider than the box, or below it.
     *
     * ⚠️ NOT `hasVisualOverflow`: a wrap-content label is laid out at the full available width and
     * then shrunk to its glyphs, so that flag reads true for a one-word label that fits with room to
     * spare ("Vous" is 53 px in a 288 px paragraph). The glyphs' own extents are the honest test.
     */
    private fun cut(text: String): List<Boolean> =
        composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val layout = layouts.single()
            val last = layout.lineCount - 1
            val widest = (0..last).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
            widest > layout.size.width + 1f || layout.getLineBottom(last) > layout.size.height + 1f ||
                layout.didOverflowHeight
        }

    @Test
    @Config(qualifiers = "fr-rCA-w320dp-h720dp")
    fun `in French at twice the font size, the speaker labels and lines are never cut`() {
        render(HqMicMode.PUSH, fontScale = 2f)

        val you = cut(str(R.string.hq_voice_you))
        val hq = cut(str(R.string.hq_title))
        assertTrue(you.isNotEmpty() && hq.isNotEmpty())
        assertFalse("Vous is cut", you.any { it })
        assertFalse("District HQ is cut", hq.any { it })
        assertFalse(cut("I can do that. Please confirm the new greeting.").any { it })
        assertFalse(
            "the controls are not squeezed to nothing",
            composeRule.onNodeWithTag(HQ_VOICE_STATE_DESCRIPTION).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text).isNullOrEmpty(),
        )
    }
}
