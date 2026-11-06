package com.distronode.districtai.ui.inbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.CHANNEL_EMAIL
import com.distronode.districtai.core.model.CHANNEL_INSTAGRAM
import com.distronode.districtai.core.model.CHANNEL_MESSENGER
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.CHANNEL_WHATSAPP
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.UploadedMedia
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The channel chips, the composer notices, the template sheet and the owner row, on screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ThreadParityScreenTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val sms = ReplyTarget("+14165550142", CHANNEL_SMS)
    private val email = ReplyTarget("ada@example.com", CHANNEL_EMAIL)
    private val whatsApp = ReplyTarget("+14165550142", CHANNEL_WHATSAPP)
    private val instagram = ReplyTarget("ig:5550102", CHANNEL_INSTAGRAM)

    private val reminder = WhatsAppTemplate(
        id = "wa_tpl_reminder",
        name = "appointment_reminder",
        language = "en_US",
        status = "approved",
        body = "Hi {{1}}, see you {{2}}.",
        variableCount = 2,
    )

    private val content = ThreadUiState.Content(
        events = listOf(TimelineEvent(id = "m1", type = "sms", timestamp = "now", direction = "inbound", body = "hi")),
    )

    /** What the screen asked for, in order. */
    private val calls = mutableListOf<String>()

    private fun handlers(state: ReplyChannelState) = ReplyChannelHandlers(
        state = state,
        onChoose = { calls += "choose ${it.channel}" },
        onOpenTemplates = { calls += "open" },
        onDismissTemplates = { calls += "dismiss" },
        onChooseTemplate = { calls += "template ${it.id}" },
        onTemplateValue = { index, value -> calls += "value $index $value" },
        onSendTemplate = { calls += "send template" },
    )

    private fun render(
        channels: ReplyChannelState? = null,
        state: ThreadUiState.Content = content,
        owner: ThreadOwnerState? = null,
        startText: String = "",
    ) {
        composeRule.setContent {
            var text by remember { mutableStateOf(startText) }
            DistrictTheme {
                ThreadScreen(
                    title = "Ada",
                    state = state,
                    canReply = true,
                    onBack = {},
                    onSend = { calls += "send $it" },
                    onRetry = {},
                    onSignIn = {},
                    onDismissSendFailure = {},
                    onLoadOlder = {},
                    composer = ComposerHandlers(
                        text = text,
                        onTextChange = { text = it },
                        canAttach = channels?.attachmentsAllowed ?: true,
                        onAttach = {},
                        onRemoveAttachment = {},
                        onGenerateDraft = { calls += "draft" },
                        imageLoader = MediaImageLoader { null },
                        onOpenMedia = {},
                    ),
                    channels = channels?.let(::handlers),
                    owner = owner?.let {
                        ThreadOwnerHandlers(
                            state = it,
                            onAssign = { email -> calls += "assign $email" },
                            onDismissFailure = { calls += "dismiss owner" },
                            onRetry = { calls += "retry owner" },
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    // ── Channels ─────────────────────────────────────────────────────────────

    @Test
    fun `Messenger and Instagram events read as messages, not as calls`() {
        render(
            state = ThreadUiState.Content(
                events = listOf(
                    TimelineEvent(id = "m1", type = "messenger", direction = "inbound", body = "Open Saturday?"),
                    TimelineEvent(id = "m2", type = "instagram", direction = "outbound", body = "Yes, 9 to 5."),
                ),
            ),
        )

        composeRule.onNodeWithText("Open Saturday?").assertIsDisplayed()
        composeRule.onNodeWithText("Yes, 9 to 5.").assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_CALL_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `more than one choice draws a chip per channel, named, and a tap chooses`() {
        render(ReplyChannelState(choices = listOf(email, sms, instagram), selected = email))

        composeRule.onNodeWithText(str(R.string.thread_reply_channel).uppercase()).assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_CHANNEL_CHIP_DESCRIPTION + 0).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.inbox_channel_email)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.inbox_channel_instagram)).performClick()

        assertEquals(listOf("choose instagram"), calls)
    }

    @Test
    fun `one choice draws no picker`() {
        render(ReplyChannelState(choices = listOf(sms), selected = sms))
        composeRule.onNodeWithTag(THREAD_CHANNELS_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `attachments on a channel that cannot carry them hold Send back with a notice`() {
        render(
            ReplyChannelState(choices = listOf(sms, instagram), selected = instagram),
            state = content.copy(attachments = listOf(UploadedMedia(url = "https://m/1"))),
            startText = "Here is the photo",
        )

        composeRule.onNodeWithText(str(R.string.thread_attachments_sms_only)).assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(THREAD_ATTACH_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `attachments on SMS send as before, with no notice`() {
        render(
            ReplyChannelState(choices = listOf(sms, instagram), selected = sms),
            state = content.copy(attachments = listOf(UploadedMedia(url = "https://m/1"))),
            startText = "Here is the photo",
        )

        composeRule.onNodeWithTag(THREAD_ATTACHMENTS_SMS_ONLY_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).assertIsEnabled()
    }

    // ── The closed WhatsApp window ───────────────────────────────────────────

    private fun closed(templates: WhatsAppTemplatesState, sheet: TemplateSheetState = TemplateSheetState()) =
        ReplyChannelState(
            choices = listOf(whatsApp),
            selected = whatsApp,
            whatsApp = WhatsAppWindowState.Closed("wa_sender_1", templates),
            sheet = sheet,
        )

    @Test
    fun `a closed window turns off the text box, the AI draft and Send, and offers a template`() {
        render(closed(WhatsAppTemplatesState.Ready(listOf(reminder))), startText = "free text")

        composeRule.onNodeWithText(str(R.string.thread_whatsapp_window_closed)).assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_REPLY_FIELD_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(THREAD_AI_DRAFT_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(THREAD_TEMPLATE_OPEN_DESCRIPTION).performClick()

        assertEquals(listOf("open"), calls)
    }

    @Test
    fun `the sheet says while the templates load`() {
        render(closed(WhatsAppTemplatesState.Loading, TemplateSheetState(open = true)))
        composeRule.onNodeWithText(str(R.string.thread_whatsapp_templates_loading)).assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_TEMPLATE_SEND_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `the sheet says when the templates could not be read`() {
        render(closed(WhatsAppTemplatesState.Failed, TemplateSheetState(open = true)))
        composeRule.onNodeWithText(str(R.string.thread_whatsapp_templates_failed)).assertIsDisplayed()
    }

    @Test
    fun `the sheet says when this number has no approved template, and Cancel closes it`() {
        render(closed(WhatsAppTemplatesState.Ready(emptyList()), TemplateSheetState(open = true)))
        composeRule.onNodeWithText(str(R.string.thread_whatsapp_templates_none)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.inbox_compose_cancel)).performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun `a sheet left open on a window that is no longer closed offers nothing`() {
        render(
            ReplyChannelState(
                choices = listOf(whatsApp),
                selected = whatsApp,
                whatsApp = WhatsAppWindowState.Open("wa_sender_1"),
                sheet = TemplateSheetState(open = true),
            ),
        )
        composeRule.onNodeWithText(str(R.string.thread_whatsapp_templates_none)).assertIsDisplayed()
    }

    @Test
    fun `the sheet lists the templates by name and language, and a tap picks one`() {
        render(closed(WhatsAppTemplatesState.Ready(listOf(reminder)), TemplateSheetState(open = true)))

        val heading = str(R.string.thread_whatsapp_template_placeholder).uppercase()
        composeRule.onNodeWithText(heading).assertIsDisplayed()
        composeRule.onNodeWithText("appointment_reminder (en_US)").performClick()
        assertEquals(listOf("template wa_tpl_reminder"), calls)
    }

    @Test
    fun `a chosen template has a field per variable, the hint on an empty one, and a live preview`() {
        val sheet = TemplateSheetState(open = true, chosen = reminder, values = listOf("Ada", ""))
        render(closed(WhatsAppTemplatesState.Ready(listOf(reminder)), sheet))

        listOf("{{1}}", "{{2}}").forEach {
            val label = str(R.string.thread_whatsapp_template_variable, it).uppercase()
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeRule.onNodeWithText(str(R.string.thread_whatsapp_template_variable_required)).assertIsDisplayed()
        composeRule.onNodeWithText("Hi Ada, see you {{2}}.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_TEMPLATE_PREVIEW_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_TEMPLATE_SEND_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(THREAD_TEMPLATE_VARIABLE_DESCRIPTION + 1).performTextInput("Thursday")

        assertEquals(listOf("value 1 Thursday"), calls)
    }

    @Test
    fun `a variable of only a zero-width no-break space is empty, by the hint as by Send`() {
        val sheet = TemplateSheetState(open = true, chosen = reminder, values = listOf("Ada", "\uFEFF"))
        render(closed(WhatsAppTemplatesState.Ready(listOf(reminder)), sheet))

        composeRule.onNodeWithText(str(R.string.thread_whatsapp_template_variable_required)).assertExists()
        composeRule.onNodeWithTag(THREAD_TEMPLATE_SEND_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a complete template can be sent`() {
        val sheet = TemplateSheetState(open = true, chosen = reminder, values = listOf("Ada", "Thursday"))
        render(closed(WhatsAppTemplatesState.Ready(listOf(reminder)), sheet))

        composeRule.onNodeWithText(str(R.string.thread_whatsapp_template_send)).performClick()
        assertEquals(listOf("send template"), calls)
    }

    @Test
    fun `a template on its way says so and cannot be sent or cancelled, and a refusal shows`() {
        val sheet = TemplateSheetState(
            open = true,
            chosen = reminder,
            values = listOf("Ada", "Thursday"),
            sending = true,
            failure = FailureText(UiText.Server("That WhatsApp template cannot be sent.")),
        )
        render(closed(WhatsAppTemplatesState.Ready(listOf(reminder)), sheet))

        composeRule.onNodeWithText(str(R.string.thread_sending)).assertIsNotEnabled()
        composeRule.onNodeWithText(str(R.string.inbox_compose_cancel)).assertIsNotEnabled()
        // ⚠️ Exists rather than displayed: the sheet scrolls, and the failure sits under the fields.
        composeRule.onNodeWithTag(THREAD_TEMPLATE_FAILURE_DESCRIPTION).assertExists()
    }

    // ── Messenger and Instagram's reply window ───────────────────────────────

    private val messengerTarget = ReplyTarget("fb:5550101", CHANNEL_MESSENGER)

    private fun meta(window: MetaWindowState) =
        ReplyChannelState(choices = listOf(messengerTarget), selected = messengerTarget, meta = window)

    @Test
    fun `a closed Messenger window says why, and Send and the AI draft are off, with no template on offer`() {
        render(meta(MetaWindowState.CLOSED), startText = "Hello?")

        composeRule.onNodeWithTag(THREAD_META_WINDOW_CLOSED_DESCRIPTION).assertExists()
        composeRule.onNodeWithText(str(R.string.thread_meta_window_closed)).assertExists()
        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(THREAD_AI_DRAFT_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(THREAD_TEMPLATE_OPEN_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(THREAD_META_HUMAN_AGENT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the human agent window shows its badge and hides the AI draft, and a reply can go`() {
        render(meta(MetaWindowState.HUMAN_AGENT), startText = "Booked for Thursday.")

        composeRule.onNodeWithTag(THREAD_META_HUMAN_AGENT_DESCRIPTION).assertExists()
        composeRule.onNodeWithText(str(R.string.thread_meta_human_agent)).assertExists()
        composeRule.onNodeWithTag(THREAD_AI_DRAFT_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).assertIsEnabled()
        composeRule.onNodeWithTag(THREAD_META_WINDOW_CLOSED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an open Messenger window shows neither line and offers the AI draft`() {
        render(meta(MetaWindowState.OPEN))

        composeRule.onNodeWithTag(THREAD_META_WINDOW_CLOSED_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(THREAD_META_HUMAN_AGENT_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(THREAD_AI_DRAFT_DESCRIPTION).assertIsEnabled()
    }

    // ── The owner ────────────────────────────────────────────────────────────

    @Test
    fun `nothing is drawn until the owner is read`() {
        render(owner = ThreadOwnerState(loaded = false, canAssign = true))
        composeRule.onNodeWithTag(THREAD_OWNER_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(THREAD_OWNER_READ_FAILED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an owner that could not be read says so, never Unassigned, and Try again reads it again`() {
        render(owner = ThreadOwnerState(loaded = false, canAssign = true, readFailed = true))

        composeRule.onNodeWithText(str(R.string.thread_owner_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.thread_unassigned)).assertDoesNotExist()
        composeRule.onNodeWithTag(THREAD_OWNER_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(THREAD_OWNER_RETRY_DESCRIPTION).performClick()
        assertEquals(listOf("retry owner"), calls)
    }

    @Test
    fun `a viewer reads the owner and gets no picker`() {
        render(owner = ThreadOwnerState(loaded = true, owner = "ada@example.com"))

        composeRule.onNodeWithText(str(R.string.thread_assign_label).uppercase()).assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_OWNER_VALUE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("ada@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_OWNER_PICKER_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the picker offers Unassigned and each member, and assigns the one picked`() {
        render(
            owner = ThreadOwnerState(
                loaded = true,
                canAssign = true,
                members = listOf("agency@example.com", "client@example.com"),
            ),
        )

        // Unassigned is the picker's own value here, so tapping it opens the menu.
        composeRule.onNodeWithText(str(R.string.thread_unassigned)).performClick()
        composeRule.onNodeWithText("agency@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_OWNER_OPTION_DESCRIPTION + 1).performClick()
        composeRule.onNodeWithTag(THREAD_OWNER_PICKER_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(THREAD_OWNER_NOBODY_DESCRIPTION).performClick()

        assertEquals(listOf("assign client@example.com", "assign null"), calls)
    }

    @Test
    fun `an assignment on its way disables the picker, and a refusal can be dismissed`() {
        render(
            owner = ThreadOwnerState(
                loaded = true,
                owner = "ada@example.com",
                canAssign = true,
                assigning = true,
                failure = FailureText(UiText.Server("Viewers cannot be assigned conversations.")),
            ),
        )

        composeRule.onNodeWithTag(THREAD_OWNER_PICKER_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(THREAD_OWNER_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.thread_dismiss)).performClick()
        assertEquals(listOf("dismiss owner"), calls)
    }
}
