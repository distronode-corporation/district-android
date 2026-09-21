package com.distronode.districtai.ui.support

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SupportRequestKind
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Raising a request with Distronode.
 *
 * ⛔ THE KIND IS PICKED, NEVER TYPED, AND THE FORM CARRIES NOTHING ELSE. The payload is exactly
 * `kind`, `subject`, `message` and the idempotency key: this is backed by a real Atlassian service
 * desk where an unknown field is a hard 400 rather than an ignored key, and where a kind outside the
 * closed vocabulary would file into a request type whose portal form we do not populate. A test that
 * found a fourth box or a free-text kind here would be reporting a bug, not a layout change.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SupportComposeDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Recorder {
        val kinds = mutableListOf<SupportRequestKind>()
        val subjects = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var submits = 0
        var dismissals = 0
    }

    /**
     * ⚠️ THE KEY BELONGS TO THE DRAFT, NOT TO THE ATTEMPT, so it is a fixture here rather than
     * something the dialog mints. A retry carrying the same key collapses onto the first request;
     * a retry that minted a fresh one would put a second ticket in a human's queue.
     */
    private val empty = SupportComposeState(idempotencyKey = "idem_1")

    private val filled = empty.copy(subject = "Calls drop", message = "Every call drops at 30s.")

    private fun render(
        state: SupportComposeState = empty,
        recorder: Recorder = Recorder(),
    ) {
        composeRule.setContent {
            DistrictTheme {
                SupportComposeDialog(
                    state = state,
                    onKind = { recorder.kinds += it },
                    onSubject = { recorder.subjects += it },
                    onMessage = { recorder.messages += it },
                    onSubmit = { recorder.submits++ },
                    onDismiss = { recorder.dismissals++ },
                )
            }
        }
    }

    private fun kind(kind: SupportRequestKind) =
        composeRule.onNodeWithContentDescription("$SUPPORT_COMPOSE_KIND_DESCRIPTION-${kind.wire}")

    private fun submit() = composeRule.onNodeWithContentDescription(SUPPORT_COMPOSE_SUBMIT_DESCRIPTION)

    private fun cancel() = composeRule.onNodeWithContentDescription(SUPPORT_COMPOSE_CANCEL_DESCRIPTION)

    @Test
    fun `all three kinds are offered, and only those three`() {
        render()

        composeRule.onNodeWithContentDescription(SUPPORT_COMPOSE_ROOT_DESCRIPTION).assertIsDisplayed()
        kind(SupportRequestKind.PROBLEM).assertIsDisplayed()
        kind(SupportRequestKind.QUESTION).assertIsDisplayed()
        kind(SupportRequestKind.SUGGESTION).assertIsDisplayed()
    }

    @Test
    fun `the kinds are labelled in the words a customer would use`() {
        render()

        composeRule.onNodeWithText("What is this about?").assertIsDisplayed()
        composeRule.onNodeWithText("Something is broken").assertIsDisplayed()
        composeRule.onNodeWithText("A question").assertIsDisplayed()
        composeRule.onNodeWithText("A suggestion").assertIsDisplayed()
    }

    @Test
    fun `picking a kind reports that kind`() {
        val recorder = Recorder()
        render(empty.copy(kind = SupportRequestKind.SUGGESTION), recorder)

        kind(SupportRequestKind.PROBLEM).performClick()
        kind(SupportRequestKind.QUESTION).performClick()

        assertEquals(
            listOf(SupportRequestKind.PROBLEM, SupportRequestKind.QUESTION),
            recorder.kinds,
        )
    }

    @Test
    fun `an empty dialog cannot be submitted`() {
        render()

        submit().assertIsNotEnabled()
    }

    @Test
    fun `a subject and a message submit, and the tap reports it once`() {
        val recorder = Recorder()
        render(filled, recorder)

        submit().assertIsEnabled().performClick()

        assertEquals(1, recorder.submits)
    }

    @Test
    fun `a subject shorter than the route allows is refused here rather than sent`() {
        // ⚠️ The route's own bound, so the form stops where the server does. The server re-checks;
        // this only avoids spending a request to be told no.
        render(filled.copy(subject = "ab"))

        submit().assertIsNotEnabled()
    }

    @Test
    fun `whitespace is not a subject and not a message`() {
        render(empty.copy(subject = "   ", message = "   "))

        submit().assertIsNotEnabled()
    }

    @Test
    fun `the subject and the message report through their own callbacks`() {
        val recorder = Recorder()
        render(recorder = recorder)

        composeRule.onNodeWithContentDescription(SUPPORT_COMPOSE_SUBJECT_DESCRIPTION)
            .performTextInput("Calls drop")
        composeRule.onNodeWithContentDescription(SUPPORT_COMPOSE_MESSAGE_DESCRIPTION)
            .performTextInput("Every call drops at 30s.")

        // ⚠️ THE FIRST REPORT, NOT THE WHOLE LIST. The dialog is stateless and this test holds
        // `state` fixed, so when focus leaves the subject the field re-syncs to the unchanged `""`
        // and reports that too. It is an artefact of driving a stateless form from a constant.
        assertEquals("Calls drop", recorder.subjects.firstOrNull())
        assertEquals("Every call drops at 30s.", recorder.messages.firstOrNull())
    }

    @Test
    fun `submitting relabels the button and disables it`() {
        render(filled.copy(submitting = true))

        composeRule.onNodeWithText("Sending…").assertIsDisplayed()
        composeRule.onNodeWithText("Send to Distronode").assertDoesNotExist()
        submit().assertIsNotEnabled()
    }

    @Test
    fun `submitting disables the kinds, the boxes and the cancel`() {
        // ⚠️ Including the KINDS: the draft that is in flight already chose one, and letting it
        // change under the request would leave the dialog describing a request we did not send.
        render(filled.copy(submitting = true))

        kind(SupportRequestKind.PROBLEM).assertIsNotEnabled()
        kind(SupportRequestKind.QUESTION).assertIsNotEnabled()
        kind(SupportRequestKind.SUGGESTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(SUPPORT_COMPOSE_SUBJECT_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(SUPPORT_COMPOSE_MESSAGE_DESCRIPTION).assertIsNotEnabled()
        cancel().assertIsNotEnabled()
    }

    @Test
    fun `cancel reports the dismissal when nothing is in flight`() {
        val recorder = Recorder()
        render(recorder = recorder)

        cancel().performClick()

        assertEquals(1, recorder.dismissals)
    }

    @Test
    fun `the server's own sentence is shown verbatim rather than replaced`() {
        // ⛔ A 429 NAMES THE REMEDY and a 503 names the public form as a PATH rather than a host,
        // because Canada's canonical host is distronode.ca. A generic "something went wrong" would
        // drop the one useful thing in the response.
        val sentence = "You have raised several requests recently. Reply on an existing one instead."
        render(filled.copy(failure = FailureText(message = UiText.Literal(sentence))))

        composeRule.onNodeWithContentDescription(SUPPORT_COMPOSE_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(sentence).assertIsDisplayed()
        // ⚠️ The draft survives it, so the operator can act on what it says.
        submit().assertIsEnabled()
    }
}
