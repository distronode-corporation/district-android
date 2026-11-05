package com.distronode.districtai.ui.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.ActiveCall
import com.distronode.districtai.core.model.WhisperAppliedData
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.live.LiveSupervisor.Phase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the Live calls screen offers on each call, and what each control asks for.
 *
 * ⛔ THE ABSENCES ARE THE ASSERTIONS THAT MATTER: no note box and no take over on a team member's
 * own call or for a viewer, and none on a call nobody is listening to.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w411dp-h3000dp")
class LiveCallsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ai = FakeSuperviseApi.AI_CALL
    private val team = FakeSuperviseApi.TEAM_CALL
    private val asked = mutableListOf<String>()

    private val actions = LiveCallsActions(
        onBack = { asked += "back" },
        onRefresh = { asked += "refresh" },
        onSignIn = { asked += "sign-in" },
        onListen = { asked += "listen:$it" },
        onLeave = { asked += "leave" },
        onDraft = { id, text -> asked += "draft:$id:$text" },
        onSend = { asked += "send:$it" },
        onAskTakeOver = { asked += "ask:$it" },
        onCancelTakeOver = { asked += "cancel" },
        onConfirmTakeOver = { asked += "confirm" },
    )

    private fun view(
        calls: List<ActiveCall> = listOf(ai, team),
        supervision: LiveSupervisor.State = LiveSupervisor.State(),
        whispers: Map<String, WhisperBox> = emptyMap(),
        prompt: LiveCallsPrompt = LiveCallsPrompt(),
        canWrite: Boolean = true,
    ) = LiveCallsView(LiveCallList.Loaded(calls), supervision, whispers, prompt, canWrite)

    private fun render(view: LiveCallsView) {
        composeRule.setContent { DistrictTheme { LiveCallsScreen(view, actions) } }
        composeRule.waitForIdle()
    }

    private fun listening(callId: String = ai.id, takenOver: Boolean = false) =
        LiveSupervisor.State(callId = callId, takenOver = takenOver, phase = Phase.CONNECTED)

    private fun assertAbsent(tag: String) = composeRule.onNodeWithTag(tag).assertDoesNotExist()

    @Test
    fun `loading, then a failure that offers a retry`() {
        var state by mutableStateOf<LiveCallList>(LiveCallList.Loading)
        composeRule.setContent {
            DistrictTheme {
                LiveCallsScreen(
                    LiveCallsView(state, LiveSupervisor.State(), emptyMap(), LiveCallsPrompt(), true),
                    actions
                )
            }
        }
        composeRule.onNodeWithTag(LIVE_CALLS_LOADING_TAG).assertExists()

        state = LiveCallList.Failed(FailureText(UiText.Resource(R.string.failure_offline)))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(LIVE_CALLS_FAILURE_TAG).assertExists()
        composeRule.onNodeWithText(str(R.string.failure_offline)).assertExists()
    }

    @Test
    fun `no calls says so, under the subtitle`() {
        render(view(calls = emptyList()))

        composeRule.onNodeWithTag(LIVE_CALLS_EMPTY_TAG).assertExists()
        composeRule.onNodeWithText(str(R.string.live_calls_subtitle)).assertExists()
    }

    @Test
    fun `the bar goes back and refreshes`() {
        render(view())

        composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(str(R.string.live_calls_refresh)).performClick()

        assertEquals(listOf("back", "refresh"), asked)
    }

    @Test
    fun `every call offers listen, and nothing else until it is listened to`() {
        render(view())

        composeRule.onNodeWithTag(liveCallTag(ai.id)).assertExists()
        composeRule.onNodeWithText("Dana Smith").assertExists()
        composeRule.onNodeWithText(ai.from!!).assertExists()
        // A team member's outbound call names the callee.
        composeRule.onNodeWithText(team.to!!).assertExists()
        composeRule.onNodeWithTag(LIVE_CALLS_TEAM_BADGE_TAG).assertExists()
        assertAbsent(LIVE_CALLS_WHISPER_FIELD_TAG)
        assertAbsent(LIVE_CALLS_TAKE_OVER_TAG)
        assertAbsent(LIVE_CALLS_STATE_TAG)
    }

    @Test
    fun `a call with no number reads as an unknown caller`() {
        render(view(calls = listOf(ActiveCall(id = "cmnobody"), ActiveCall(id = "cmblank", callerName = " "))))

        composeRule.onNodeWithTag(liveCallTag("cmnobody")).assertExists()
        assertEquals(
            2,
            composeRule.onAllNodesWithText(str(R.string.live_calls_unknown_caller)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `listen asks for its own call`() {
        render(view(calls = listOf(ai)))

        composeRule.onNodeWithTag(LIVE_CALLS_LISTEN_TAG).performClick()

        assertEquals(listOf("listen:${ai.id}"), asked)
    }

    @Test
    fun `listen waits while a request is in flight`() {
        render(view(calls = listOf(ai), supervision = LiveSupervisor.State(requestingCallId = team.id)))

        composeRule.onNodeWithTag(LIVE_CALLS_LISTEN_TAG).assertIsNotEnabled()
    }

    @Test
    fun `the call being listened to opens the note box and take over`() {
        render(view(calls = listOf(ai), supervision = listening()))

        composeRule.onNodeWithText(str(R.string.live_calls_listening)).assertExists()
        composeRule.onNodeWithTag(LIVE_CALLS_WHISPER_FIELD_TAG).assertExists()
        composeRule.onNodeWithTag(LIVE_CALLS_WHISPER_SEND_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(LIVE_CALLS_TAKE_OVER_TAG).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(LIVE_CALLS_LEAVE_TAG).performClick()
        composeRule.onNodeWithTag(LIVE_CALLS_WHISPER_FIELD_TAG).performTextInput("Offer Thursday.")

        assertEquals(listOf("ask:${ai.id}", "leave", "draft:${ai.id}:Offer Thursday."), asked)
    }

    @Test
    fun `a team member's call offers listen only, and says why`() {
        render(view(calls = listOf(team), supervision = listening(team.id)))

        composeRule.onNodeWithTag(LIVE_CALLS_TEAM_NOTE_TAG).assertExists()
        assertAbsent(LIVE_CALLS_WHISPER_FIELD_TAG)
        assertAbsent(LIVE_CALLS_TAKE_OVER_TAG)
        composeRule.onNodeWithTag(LIVE_CALLS_LEAVE_TAG).assertExists()
    }

    @Test
    fun `a viewer listens and is offered nothing else`() {
        render(view(calls = listOf(ai), supervision = listening(), canWrite = false))

        composeRule.onNodeWithTag(LIVE_CALLS_STATE_TAG).assertExists()
        assertAbsent(LIVE_CALLS_WHISPER_FIELD_TAG)
        assertAbsent(LIVE_CALLS_TAKE_OVER_TAG)
        assertAbsent(LIVE_CALLS_TEAM_NOTE_TAG)
    }

    @Test
    fun `a typed note can be sent`() {
        render(
            view(
                calls = listOf(ai),
                supervision = listening(),
                whispers = mapOf(ai.id to WhisperBox("Offer Thursday."))
            )
        )

        composeRule.onNodeWithTag(LIVE_CALLS_WHISPER_SEND_TAG).assertIsEnabled().performClick()

        assertEquals(listOf("send:${ai.id}"), asked)
    }

    @Test
    fun `a note being sent cannot be sent again, and a refusal shows under it`() {
        val box =
            WhisperBox("Offer Thursday.", sending = true, error = UiText.Resource(R.string.live_calls_whisper_failed))
        render(view(calls = listOf(ai), supervision = listening(), whispers = mapOf(ai.id to box)))

        composeRule.onNodeWithTag(LIVE_CALLS_WHISPER_SEND_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(LIVE_CALLS_WHISPER_ERROR_TAG).assertExists()
        composeRule.onNodeWithText(str(R.string.live_calls_whisper_failed)).assertExists()
    }

    @Test
    fun `each receipt reads as a sentence, never as wire vocabulary`() {
        val receipts = listOf(
            WhisperAppliedData(applied = true, firstWords = "Thursday at 3 works.") to
                str(R.string.live_calls_receipt_applied_words, "Thursday at 3 works."),
            WhisperAppliedData(applied = true) to str(R.string.live_calls_receipt_applied),
            WhisperAppliedData(applied = true, firstWords = " ") to str(R.string.live_calls_receipt_applied),
            WhisperAppliedData(applied = false, reason = "taken_over") to str(R.string.live_calls_receipt_taken_over),
            WhisperAppliedData(applied = false, reason = "transfer") to str(R.string.live_calls_receipt_transfer),
        )
        var box by mutableStateOf(WhisperBox())
        composeRule.setContent {
            DistrictTheme {
                LiveCallsScreen(
                    view(calls = listOf(ai), supervision = listening(), whispers = mapOf(ai.id to box)),
                    actions
                )
            }
        }
        receipts.forEach { (receipt, sentence) ->
            box = WhisperBox(receipt = receipt)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(LIVE_CALLS_RECEIPT_TAG).assertExists()
            composeRule.onNodeWithText(sentence).assertExists()
        }
    }

    @Test
    fun `the take-over confirmation says what happens and answers both ways`() {
        render(
            view(calls = listOf(ai), supervision = listening(), prompt = LiveCallsPrompt(confirmingTakeover = ai.id))
        )

        composeRule.onNodeWithText(str(R.string.live_calls_take_over_confirm_body)).assertExists()
        assertAbsent(LIVE_CALLS_TAKE_OVER_TAG)
        composeRule.onNodeWithTag(LIVE_CALLS_CONFIRM_YES_TAG).performClick()
        composeRule.onNodeWithText(str(R.string.live_calls_take_over_cancel)).performClick()

        assertEquals(listOf("confirm", "cancel"), asked)
    }

    @Test
    fun `a confirmation for another call is not shown here, and take over waits while busy`() {
        val busy = listening().copy(requestingCallId = ai.id)
        render(view(calls = listOf(ai), supervision = busy, prompt = LiveCallsPrompt(confirmingTakeover = team.id)))

        assertAbsent(LIVE_CALLS_CONFIRM_TAG)
        composeRule.onNodeWithTag(LIVE_CALLS_TAKE_OVER_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a taken-over call offers only the hand back`() {
        render(view(calls = listOf(ai), supervision = listening(takenOver = true)))

        composeRule.onNodeWithText(str(R.string.live_calls_taken_over_title)).assertExists()
        assertAbsent(LIVE_CALLS_LEAVE_TAG)
        assertAbsent(LIVE_CALLS_WHISPER_FIELD_TAG)
        assertAbsent(LIVE_CALLS_TAKE_OVER_TAG)
        composeRule.onNodeWithTag(LIVE_CALLS_HAND_BACK_TAG).performClick()

        assertEquals(listOf("leave"), asked)
    }

    @Test
    fun `a failure shows at its own call only`() {
        val failed = LiveSupervisor.State(
            failedCallId = ai.id,
            failure = UiText.Server("This call has ended.", "call_ended")
        )
        render(view(supervision = failed))

        composeRule.onNodeWithText("This call has ended.").assertExists()
        composeRule.onNodeWithTag(LIVE_CALLS_FAILURE_LINE_TAG).assertExists()
    }

    @Test
    fun `a failed call with no sentence shows nothing`() {
        render(view(supervision = LiveSupervisor.State(failedCallId = ai.id)))

        assertAbsent(LIVE_CALLS_FAILURE_LINE_TAG)
    }
}
