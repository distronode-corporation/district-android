package com.distronode.districtai.ui.desk

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
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Raising a ticket on a customer's behalf.
 *
 * ⛔ THE ASSERTION THAT MATTERS MOST IS THAT ALL THREE REQUESTER BOXES MAY BE BLANK AND THE TICKET
 * IS STILL SUBMITTABLE. The route needs only a subject and a message, and a form that demanded a
 * contact detail would refuse a ticket the desk accepts.
 *
 * ⚠️ AND ONE THAT GUARDS A DIFFERENT KIND OF MISTAKE: each box reports through its OWN callback.
 * Five of these are strings and three of them are the customer's name, email and phone, which is
 * exactly the shape where an email ends up in the phone column and then reaches the customer as the
 * address a notification is sent to.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class DeskComposeSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** ⚠️ A recorder rather than a parameter bundle; the sheet takes seven callbacks. */
    private class Recorder {
        val subjects = mutableListOf<String>()
        val messages = mutableListOf<String>()
        val names = mutableListOf<String>()
        val emails = mutableListOf<String>()
        val phones = mutableListOf<String>()
        var submits = 0
        var dismissals = 0
    }

    /** ⚠️ Past `DeskBounds.SUBJECT_MIN`, so the shortness of a subject is never the reason. */
    private val filled = DeskComposeState(subject = "Leaking tap", message = "The tap drips.")

    private fun render(
        state: DeskComposeState = DeskComposeState(),
        recorder: Recorder = Recorder(),
    ) {
        composeRule.setContent {
            DistrictTheme {
                DeskComposeSheet(
                    state = state,
                    onSubject = { recorder.subjects += it },
                    onMessage = { recorder.messages += it },
                    onRequesterName = { recorder.names += it },
                    onRequesterEmail = { recorder.emails += it },
                    onRequesterPhone = { recorder.phones += it },
                    onSubmit = { recorder.submits++ },
                    onDismiss = { recorder.dismissals++ },
                )
            }
        }
    }

    private fun submit() = composeRule.onNodeWithContentDescription(DESK_COMPOSE_SUBMIT_DESCRIPTION)

    private fun cancel() = composeRule.onNodeWithContentDescription(DESK_COMPOSE_CANCEL_DESCRIPTION)

    @Test
    fun `the sheet offers all five boxes`() {
        render()

        composeRule.onNodeWithContentDescription(DESK_COMPOSE_ROOT_DESCRIPTION).assertIsDisplayed()
        listOf(
            DESK_COMPOSE_SUBJECT_DESCRIPTION,
            DESK_COMPOSE_MESSAGE_DESCRIPTION,
            DESK_COMPOSE_NAME_DESCRIPTION,
            DESK_COMPOSE_EMAIL_DESCRIPTION,
            DESK_COMPOSE_PHONE_DESCRIPTION,
        ).forEach { composeRule.onNodeWithContentDescription(it).assertIsDisplayed() }
    }

    @Test
    fun `it says whose words the message becomes`() {
        // ⛔ A PRODUCT FACT RATHER THAN DECORATION. The server records the CUSTOMER as the author
        // even when an operator types it, and an operator who thought they were writing as the team
        // would word the message the other way round.
        render()

        composeRule.onNodeWithText(
            "For something a customer brought another way. The message is recorded as your " +
                "customer's own words.",
        ).assertIsDisplayed()
    }

    @Test
    fun `an empty sheet cannot be submitted`() {
        render()

        submit().assertIsNotEnabled()
    }

    @Test
    fun `a subject and a message alone submit, with all three requester boxes blank`() {
        // ⛔ THE ROUTE NEEDS ONLY THE TWO. A ticket raised during a call may carry no contact
        // detail at all, and demanding one here would refuse input the desk accepts.
        val recorder = Recorder()
        render(filled, recorder)

        submit().assertIsEnabled().performClick()

        assertEquals(1, recorder.submits)
    }

    @Test
    fun `a subject shorter than the route allows is refused here rather than sent`() {
        // ⚠️ The same bound the route enforces, checked so the button can be disabled rather than
        // spending a request to be refused. The server re-checks; this is a shortcut, never the
        // boundary.
        render(filled.copy(subject = "ab"))

        submit().assertIsNotEnabled()
    }

    @Test
    fun `whitespace is not a subject and not a message`() {
        render(DeskComposeState(subject = "   ", message = "   "))

        submit().assertIsNotEnabled()
    }

    @Test
    fun `a message on its own is not enough`() {
        render(DeskComposeState(message = "The tap drips."))

        submit().assertIsNotEnabled()
    }

    @Test
    fun `each box reports through its own callback`() {
        // ⚠️ THE SWAP THIS CATCHES IS NOT HYPOTHETICAL: five interchangeable strings, three of them
        // the customer's own contact details, is where an email ends up in the phone column.
        val recorder = Recorder()
        render(recorder = recorder)

        composeRule.onNodeWithContentDescription(DESK_COMPOSE_SUBJECT_DESCRIPTION)
            .performTextInput("Leaking tap")
        composeRule.onNodeWithContentDescription(DESK_COMPOSE_MESSAGE_DESCRIPTION)
            .performTextInput("The tap drips.")
        composeRule.onNodeWithContentDescription(DESK_COMPOSE_NAME_DESCRIPTION)
            .performTextInput("Ada Lovelace")
        composeRule.onNodeWithContentDescription(DESK_COMPOSE_EMAIL_DESCRIPTION)
            .performTextInput("ada@example.com")
        composeRule.onNodeWithContentDescription(DESK_COMPOSE_PHONE_DESCRIPTION)
            .performTextInput("+14165550142")

        // ⚠️ THE FIRST REPORT, NOT THE WHOLE LIST. The sheet is stateless and this test holds
        // `state` fixed, so when focus leaves a box the field re-syncs to the unchanged `""` and
        // reports that too. It is an artefact of driving a stateless form from a constant.
        assertEquals("Leaking tap", recorder.subjects.firstOrNull())
        assertEquals("The tap drips.", recorder.messages.firstOrNull())
        assertEquals("Ada Lovelace", recorder.names.firstOrNull())
        assertEquals("ada@example.com", recorder.emails.firstOrNull())
        assertEquals("+14165550142", recorder.phones.firstOrNull())
    }

    @Test
    fun `submitting relabels the button and disables it, so a double tap is invisible`() {
        // ⚠️ The idempotency key makes a second submit harmless server-side; this makes it
        // impossible to make.
        render(filled.copy(submitting = true))

        composeRule.onNodeWithText("Raising…").assertIsDisplayed()
        composeRule.onNodeWithText("Raise the ticket").assertDoesNotExist()
        submit().assertIsNotEnabled()
    }

    @Test
    fun `submitting disables every box and the cancel, because the request is already in flight`() {
        val recorder = Recorder()
        render(filled.copy(submitting = true), recorder)

        listOf(
            DESK_COMPOSE_SUBJECT_DESCRIPTION,
            DESK_COMPOSE_MESSAGE_DESCRIPTION,
            DESK_COMPOSE_NAME_DESCRIPTION,
            DESK_COMPOSE_EMAIL_DESCRIPTION,
            DESK_COMPOSE_PHONE_DESCRIPTION,
        ).forEach { composeRule.onNodeWithContentDescription(it).assertIsNotEnabled() }
        cancel().assertIsNotEnabled()

        assertEquals(0, recorder.dismissals)
    }

    @Test
    fun `cancel reports the dismissal when nothing is in flight`() {
        val recorder = Recorder()
        render(recorder = recorder)

        cancel().performClick()

        assertEquals(1, recorder.dismissals)
    }

    @Test
    fun `a failure is shown in the sheet rather than closing it`() {
        val recorder = Recorder()
        render(
            filled.copy(
                failure = FailureText(
                    message = UiText.Literal("A subject and a description are required."),
                ),
            ),
            recorder,
        )

        composeRule.onNodeWithContentDescription(DESK_COMPOSE_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("A subject and a description are required.").assertIsDisplayed()
        // ⚠️ The operator's words survive it, and one tap retries them.
        submit().assertIsEnabled()

        assertEquals(0, recorder.dismissals)
    }
}
