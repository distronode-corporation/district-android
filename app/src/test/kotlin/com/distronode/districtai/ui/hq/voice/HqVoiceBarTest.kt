package com.distronode.districtai.ui.hq.voice

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.model.HqAgentState
import com.distronode.districtai.core.model.HqMicMode
import com.distronode.districtai.core.model.HqResolutionStatus
import com.distronode.districtai.core.model.HqVoiceProposal
import com.distronode.districtai.core.network.testing.FakeHqVoiceApi
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.ThemeFlip
import com.distronode.districtai.ui.UiText
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the voice bar draws in each phase, and what each of its controls asks the session. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class HqVoiceBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val theme = ThemeFlip(composeRule)
    private val calls = mutableListOf<String>()
    private val actions = HqVoiceActions(
        onStart = { calls += "start" },
        onStop = { calls += "stop" },
        onOpenSettings = { calls += "settings" },
        onNoticeStart = { calls += "notice-start" },
        onNoticeCancel = { calls += "notice-cancel" },
        onHold = { calls += "hold:$it" },
        onToggleMute = { calls += "mute" },
        onConfirm = { calls += "confirm:$it" },
        onCancel = { calls += "cancel:$it" },
    )

    private fun render(state: HqVoiceUiState, canConfirm: Boolean = true, region: String? = null) {
        theme.setContent { HqVoiceBar(state, canConfirm, region, actions) }
    }

    private fun live(micMode: HqMicMode = HqMicMode.OPEN, on: Boolean = true, agent: HqAgentState? = null) =
        HqVoiceUiState(phase = HqVoicePhase.Live, micMode = micMode, isMicrophoneOn = on, agentState = agent)

    @Test
    fun `before a session, Talk and the settings are offered with the microphone explained`() {
        render(HqVoiceUiState())

        composeRule.onNodeWithTag(HQ_VOICE_NOTE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.hq_voice_mic_explainer)).assertIsDisplayed()
        composeRule.onNodeWithTag(HQ_VOICE_START_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(HQ_VOICE_SETTINGS_DESCRIPTION).performClick()
        theme.flip()

        assertEquals(listOf("start", "settings"), calls)
    }

    @Test
    fun `an ended session says why, and Talk starts another`() {
        var state by mutableStateOf(HqVoiceUiState(phase = HqVoicePhase.Ended(HqVoiceEndReason.Busy)))
        theme.setContent { HqVoiceBar(state, true, null, actions) }
        val failure = FailureText(UiText.Literal("All of this month's voice minutes are used."))
        val expected = mapOf(
            HqVoiceEndReason.Busy to str(R.string.hq_voice_busy),
            HqVoiceEndReason.ByMember to str(R.string.hq_voice_ended),
            HqVoiceEndReason.Interrupted to str(R.string.hq_voice_ended_interrupted),
            HqVoiceEndReason.TimedOut to str(R.string.hq_voice_ended_timeout),
            HqVoiceEndReason.NoAgent to str(R.string.hq_voice_no_agent),
            HqVoiceEndReason.MicrophoneDenied to str(R.string.hq_voice_mic_denied),
            HqVoiceEndReason.JoinFailed to str(R.string.hq_voice_join_failed),
            HqVoiceEndReason.Refused(failure) to "All of this month's voice minutes are used.",
            HqVoiceEndReason.Failed(FailureText(UiText.Literal("Offline."))) to "Offline.",
            HqVoiceEndReason.Disconnected to str(R.string.hq_voice_ended),
            HqVoiceEndReason.AgentLeft to str(R.string.hq_voice_ended),
        )

        expected.forEach { (reason, sentence) ->
            state = HqVoiceUiState(phase = HqVoicePhase.Ended(reason))
            composeRule.waitForIdle()
            composeRule.onNodeWithText(sentence).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(HQ_VOICE_START_DESCRIPTION).performClick()
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun `while it mints, waits on the notice or the microphone, or joins, Stop is offered`() {
        var phase by mutableStateOf<HqVoicePhase>(HqVoicePhase.Minting)
        theme.setContent { HqVoiceBar(HqVoiceUiState(phase = phase), true, null, actions) }
        listOf(
            HqVoicePhase.Minting,
            HqVoicePhase.Permission(FakeHqVoiceApi.CREDENTIAL),
            HqVoicePhase.Connecting,
        ).forEach {
            phase = it
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(HQ_VOICE_CONNECTING_DESCRIPTION).assertIsDisplayed()
            composeRule.onNodeWithText(str(R.string.hq_voice_connecting)).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(HQ_VOICE_STOP_DESCRIPTION).performClick()

        assertEquals(listOf("stop"), calls)
    }

    @Test
    fun `the notice says where the voice is processed, before anything is joined`() {
        render(HqVoiceUiState(phase = HqVoicePhase.Notice(FakeHqVoiceApi.CREDENTIAL)), region = "ca")

        composeRule.onNodeWithText(str(R.string.hq_voice_notice_title)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.hq_voice_notice_body)).assertExists()
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_REGION_DESCRIPTION, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(str(R.string.hq_voice_notice_region, str(R.string.wire_region_ca))).assertExists()
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_START_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_CANCEL_DESCRIPTION).performClick()

        assertEquals(listOf("notice-start", "notice-cancel"), calls)
    }

    @Test
    fun `no region line when the voice is processed in the workspace's own region, or it is not known`() {
        var region by mutableStateOf<String?>("us")
        theme.setContent {
            HqVoiceBar(HqVoiceUiState(phase = HqVoicePhase.Notice(FakeHqVoiceApi.CREDENTIAL)), true, region, actions)
        }
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_REGION_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()

        region = null
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_DESCRIPTION).assertExists()
        composeRule.onNodeWithTag(HQ_VOICE_NOTICE_REGION_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `live, the agent's state is shown and an open microphone mutes`() {
        var state by mutableStateOf(live(agent = null))
        theme.setContent { HqVoiceBar(state, true, null, actions) }
        composeRule.onNodeWithText(str(R.string.hq_voice_listening)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.hq_voice_mute)).performClick()

        state = live(on = false, agent = HqAgentState.THINKING)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.hq_voice_thinking)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.hq_voice_unmute)).performClick()

        state = live(agent = HqAgentState.SPEAKING)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.hq_voice_speaking)).assertIsDisplayed()
        state = live(agent = HqAgentState.LISTENING)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_STATE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithTag(HQ_VOICE_STOP_DESCRIPTION).performClick()
        theme.flip()

        assertEquals(listOf("mute", "mute", "stop"), calls)
    }

    @Test
    fun `Hold to talk is on while held and off when let go`() {
        render(live(micMode = HqMicMode.PUSH, on = false))

        composeRule.onNodeWithTag(HQ_VOICE_HOLD_DESCRIPTION).performTouchInput { down(center) }
        composeRule.onNodeWithTag(HQ_VOICE_HOLD_DESCRIPTION).performTouchInput { up() }
        composeRule.onNodeWithTag(HQ_VOICE_HOLD_DESCRIPTION).assertIsNotSelected()

        assertEquals(listOf("hold:true", "hold:false"), calls)
    }

    @Test
    fun `TalkBack's double tap turns Hold to talk on and off`() {
        var on by mutableStateOf(false)
        theme.setContent { HqVoiceBar(live(micMode = HqMicMode.PUSH, on = on), true, null, actions) }

        composeRule.onNodeWithTag(HQ_VOICE_HOLD_DESCRIPTION).performSemanticsAction(SemanticsActions.OnClick)
        on = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_HOLD_DESCRIPTION).assertIsSelected()
        composeRule.onNodeWithTag(HQ_VOICE_HOLD_DESCRIPTION).performSemanticsAction(SemanticsActions.OnClick)
        theme.flip()

        assertEquals(listOf("hold:true", "hold:false"), calls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Space or Enter hold it from a keyboard, once per press, and losing focus lets go`() {
        var on by mutableStateOf(false)
        var shown by mutableStateOf(true)
        val elsewhere = FocusRequester()
        theme.setContent {
            Column {
                if (shown) HqVoiceBar(live(micMode = HqMicMode.PUSH, on = on), true, null, actions)
                androidx.compose.foundation.layout.Box(
                    Modifier.size(1.dp).focusRequester(elsewhere).focusable().testTag("elsewhere"),
                )
            }
        }
        val hold = composeRule.onNodeWithTag(HQ_VOICE_HOLD_DESCRIPTION)

        hold.requestFocus()
        hold.performKeyInput { pressKey(Key.Spacebar) }
        hold.performKeyInput { pressKey(Key.A) }
        hold.performKeyInput { pressKey(Key.NumPadEnter) }
        on = true
        composeRule.waitForIdle()
        hold.performKeyInput { keyDown(Key.Enter) }
        hold.performKeyInput { keyUp(Key.Enter) }
        hold.requestFocus()
        composeRule.runOnIdle { elsewhere.requestFocus() }
        composeRule.waitForIdle()
        shown = false
        composeRule.waitForIdle()

        assertEquals(
            listOf("hold:true", "hold:false", "hold:true", "hold:false", "hold:false", "hold:false", "hold:false"),
            calls,
        )
    }

    @Test
    fun `the transcript shows the last four final segments, the member's marked You`() {
        val segments = (1..5).map { HqVoiceSegment("s$it", "Line $it", fromMember = it % 2 == 0) }
        render(live().copy(segments = segments))

        composeRule.onNodeWithTag(HQ_VOICE_TRANSCRIPT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Line 1").assertDoesNotExist()
        composeRule.onNodeWithText("Line 5").assertIsDisplayed()
        composeRule.onAllNodesWithText(str(R.string.hq_voice_you)).assertCountEquals(2)
    }

    private fun card(id: String, summary: String = "Change the greeting.", outcome: HqResolutionStatus? = null) =
        HqVoiceProposalCard(HqVoiceProposal(id, "update_persona", JsonObject(emptyMap()), summary), outcome)

    @Test
    fun `a proposal shows its summary and is confirmed or cancelled by a tap`() {
        render(live().copy(proposals = listOf(card("p1"))))

        composeRule.onNodeWithTag(hqVoiceProposalTag("p1")).assertIsDisplayed()
        composeRule.onNodeWithText("Change the greeting.").assertIsDisplayed()
        composeRule.onNodeWithTag(HQ_VOICE_CONFIRM_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(HQ_VOICE_CANCEL_DESCRIPTION).performClick()

        assertEquals(listOf("confirm:p1", "cancel:p1"), calls)
    }

    @Test
    fun `a viewer can only cancel, a proposal being resolved is disabled, and an outcome replaces the controls`() {
        var state by mutableStateOf(live().copy(proposals = listOf(card("p1", summary = ""))))
        var canConfirm by mutableStateOf(false)
        theme.setContent { HqVoiceBar(state, canConfirm, null, actions) }
        composeRule.onNodeWithText(str(R.string.hq_voice_proposal_fallback)).assertIsDisplayed()
        composeRule.onNodeWithTag(HQ_VOICE_CONFIRM_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(HQ_VOICE_CANCEL_DESCRIPTION).assertIsEnabled()

        canConfirm = true
        state = live().copy(proposals = listOf(card("p1").copy(isWorking = true)))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_CONFIRM_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(HQ_VOICE_CANCEL_DESCRIPTION).assertIsNotEnabled()

        mapOf(
            HqResolutionStatus.APPLIED to R.string.hq_voice_applied,
            HqResolutionStatus.FAILED to R.string.hq_voice_failed,
            HqResolutionStatus.CANCELLED to R.string.hq_voice_cancelled,
        ).forEach { (outcome, sentence) ->
            state = live().copy(proposals = listOf(card("p1", outcome = outcome)))
            composeRule.waitForIdle()
            composeRule.onNodeWithText(str(sentence)).assertIsDisplayed()
            composeRule.onNodeWithTag(HQ_VOICE_CONFIRM_DESCRIPTION).assertDoesNotExist()
        }
        theme.flip()
    }
}
