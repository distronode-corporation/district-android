package com.distronode.districtai.ui.settings.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the audition sheet says, and when it offers to spend money.
 *
 * ⛔ THE COST IS STATED BEFORE THE FIRST TAP. A control reading only "Preview" would describe a dry
 * run that does not exist: the token invites the voice agent into a room on the workspace's own
 * pipeline, where it answers with speech recognition, a model and speech synthesis exactly as it
 * would on a telephone call.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class PersonaPreviewDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: PersonaPreviewUiState,
        onStart: () -> Unit = {},
        onStop: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                PersonaPreviewDialog(
                    state = state,
                    onStart = onStart,
                    onStop = onStop,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Test
    fun `it says this is a real call before offering to start one`() {
        render(PersonaPreviewUiState())

        composeRule.onNodeWithText(
            "This places a real call to your agent using the settings on this screen, including " +
                "the ones you have not saved. It uses call minutes.",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_START_DESCRIPTION).assertIsEnabled()
    }

    @Test
    fun `waiting for the agent is worded differently from live`() {
        // ⛔ TELLING SOMEBODY TO SPEAK BEFORE THE AGENT HAS JOINED has them talk into a room that
        // nothing is listening to, and then conclude the persona is broken.
        render(PersonaPreviewUiState(phase = PersonaPreviewPhase.Waiting))
        composeRule.onNodeWithText("Connected. Waiting for the agent to join…").assertIsDisplayed()
        composeRule.onNodeWithText("The agent is on the line. Say something.").assertDoesNotExist()
    }

    @Test
    fun `a running session offers Stop rather than Start`() {
        var stopped = 0
        render(PersonaPreviewUiState(phase = PersonaPreviewPhase.Live), onStop = { stopped++ })

        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_START_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_STOP_DESCRIPTION).performClick()

        assertEquals(1, stopped)
    }

    @Test
    fun `the cooldown disables Start rather than hiding it`() {
        // ⛔ THE ROUTE'S 10-A-MINUTE CEILING IS THE ONLY THING BOUNDING A LOOP OF BILLED SESSIONS,
        // and a button that re-armed instantly would invite somebody to spend the rest of the
        // minute's slots finding out it is still refused.
        render(PersonaPreviewUiState(phase = PersonaPreviewPhase.Idle, cooling = true))

        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_START_DESCRIPTION)
            .assertIsNotEnabled()
    }

    @Test
    fun `a session the server dropped is not described as one the operator stopped`() {
        render(
            PersonaPreviewUiState(
                phase = PersonaPreviewPhase.Ended(PersonaPreviewEnding.DroppedRemotely(null)),
            ),
        )

        composeRule.onNodeWithText("The call ended.").assertIsDisplayed()
        composeRule.onNodeWithText("You ended the call.").assertDoesNotExist()
    }

    @Test
    fun `a denied microphone is said out loud rather than left as silence`() {
        // ⚠️ THE SESSION STILL RUNS — the agent greets and can be heard — so the screen explains
        // why nothing is being heard back instead of refusing to start.
        render(PersonaPreviewUiState(phase = PersonaPreviewPhase.Live, microphoneDenied = true))

        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_NO_MIC_DESCRIPTION)
            .assertIsDisplayed()
    }

    @Test
    fun `a mint failure shows the server's own sentence`() {
        render(
            PersonaPreviewUiState(
                phase = PersonaPreviewPhase.Failed,
                failure = FailureText(UiText.Literal("Too many previews. Try again shortly.")),
            ),
        )

        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Too many previews. Try again shortly.").assertIsDisplayed()
    }

    @Test
    fun `closing reports a dismissal, which the caller turns into a teardown`() {
        // ⛔ A SHEET THAT MERELY DISAPPEARED would leave a room publishing this phone's microphone
        // with nothing on screen able to reach it.
        var dismissed = 0
        render(PersonaPreviewUiState(phase = PersonaPreviewPhase.Live), onDismiss = { dismissed++ })

        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_CLOSE_DESCRIPTION).performClick()

        assertEquals(1, dismissed)
    }
}
