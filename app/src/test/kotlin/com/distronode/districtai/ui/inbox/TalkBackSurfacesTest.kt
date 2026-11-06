package com.distronode.districtai.ui.inbox

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.CHANNEL_INSTAGRAM
import com.distronode.districtai.core.model.CHANNEL_MESSENGER
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.CHANNEL_WHATSAPP
import com.distronode.districtai.core.model.ConversationLastMessage
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.WhisperAppliedData
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import com.distronode.districtai.plural
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.dialer.DIALER_COUNTRY_NO_MATCH_DESCRIPTION
import com.distronode.districtai.ui.dialer.DIALER_COUNTRY_SEARCH_DESCRIPTION
import com.distronode.districtai.ui.dialer.DialerCountryPicker
import com.distronode.districtai.ui.dialer.countryRowDescription
import com.distronode.districtai.ui.live.LIVE_CALLS_CONFIRM_TAG
import com.distronode.districtai.ui.live.LIVE_CALLS_FAILURE_LINE_TAG
import com.distronode.districtai.ui.live.LIVE_CALLS_LISTEN_TAG
import com.distronode.districtai.ui.live.LIVE_CALLS_RECEIPT_TAG
import com.distronode.districtai.ui.live.LIVE_CALLS_STATE_TAG
import com.distronode.districtai.ui.live.LiveCallList
import com.distronode.districtai.ui.live.LiveCallsActions
import com.distronode.districtai.ui.live.LiveCallsPrompt
import com.distronode.districtai.ui.live.LiveCallsScreen
import com.distronode.districtai.ui.live.LiveCallsView
import com.distronode.districtai.ui.live.LiveSupervisor
import com.distronode.districtai.ui.live.WhisperBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What TalkBack is given on the 3.3.0 surfaces: the inbox rows, the assign picker, the reply channel
 * and WhatsApp template choices, the Meta reply window, live supervise and the dialler's country list.
 *
 * ⚠️ SEMANTICS, NOT A SCREEN READER: these pin the labels, roles, states, headings and live regions
 * TalkBack reads; what it then says aloud is TalkBack's. Rendered natively, so sizes are real.
 * ⚠️ 320dp wide: at 411dp the template sheet's text fields never let Compose idle under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w320dp-h3000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TalkBackSurfacesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { DistrictTheme { content() } }
        composeRule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.config() = fetchSemanticsNode().config

    private fun SemanticsNodeInteraction.assertLive() =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

    private fun SemanticsNodeInteraction.assertHeading() =
        assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

    private fun selected(value: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.Selected, value)

    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    // ── Inbox rows ──────────────────────────────────────────────────────────────────────────────

    private fun inbox() = show {
        InboxScreen(
            state = InboxUiState.Content(
                listOf(
                    ConversationSummary(
                        threadKey = "contact:c1",
                        counterpart = "c1",
                        contactName = "Ada Lovelace",
                        channels = listOf("whatsapp", "sms"),
                        assigneeEmail = "teammate@example.com",
                        unreadCount = 2,
                        lastMessage = ConversationLastMessage(body = "Hi, do you do flat roofs?"),
                    ),
                ),
                partial = false,
            ),
            onOpenThread = {},
            onRetry = {},
            onSignIn = {},
            onBack = {},
            searchState = InboxSearchState(),
            onSearchQueryChanged = {},
            onOpenHit = {},
        )
    }

    @Test
    fun `an inbox row reads the unread count as messages, and never the avatar's initials`() {
        inbox()
        val spoken = plural(R.plurals.inbox_unread_count, 2)
        assertEquals("2 unread messages", spoken)

        val row = composeRule.onNodeWithText("Ada Lovelace").config()
        val texts = row[SemanticsProperties.Text].map { it.text }
        assertTrue(texts.toString(), spoken in texts)
        assertFalse("the bare number", "2" in texts)
        assertFalse("the initials", "AL" in texts)
        // ⛔ No contentDescription in the merged row: TalkBack would read it in place of every line.
        assertNull(row.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(str(R.string.inbox_assigned_to, "teammate@example.com") in texts)
    }

    // ── The thread: assign picker, channels, templates, Meta window ─────────────────────────────

    private val reminder = WhatsAppTemplate(
        id = "wa_tpl_reminder",
        name = "appointment_reminder",
        language = "en_US",
        status = "approved",
        body = "Hi {{1}}, this is a reminder of your appointment on {{2}}.",
        variableCount = 2,
    )
    private val messenger = ReplyTarget("fb:5550101", CHANNEL_MESSENGER)
    private val instagram = ReplyTarget("ig:5550102", CHANNEL_INSTAGRAM)
    private val whatsApp = ReplyTarget("+14165550100", CHANNEL_WHATSAPP)
    private val sms = ReplyTarget("+14165550100", CHANNEL_SMS)
    private val members = listOf("agent@example.com", "teammate@example.com")

    private fun thread(
        channels: ReplyChannelState = ReplyChannelState(listOf(sms, whatsApp), sms),
        owner: ThreadOwnerState = ThreadOwnerState(
            loaded = true,
            owner = "teammate@example.com",
            canAssign = true,
            members = members,
        ),
    ) = show {
        ThreadScreen(
            title = "Ada Lovelace",
            state = ThreadUiState.Content(
                events = listOf(
                    TimelineEvent(
                        id = "m1",
                        type = "sms",
                        timestamp = "2026-11-04T14:00:00.000Z",
                        direction = "inbound",
                        body = "Hello",
                    ),
                ),
            ),
            canReply = true,
            onBack = {},
            onSend = {},
            onRetry = {},
            onSignIn = {},
            onDismissSendFailure = {},
            onLoadOlder = {},
            composer = ComposerHandlers(
                text = "",
                onTextChange = {},
                canAttach = false,
                onAttach = {},
                onRemoveAttachment = {},
                onGenerateDraft = {},
                imageLoader = MediaImageLoader { null },
                onOpenMedia = {},
            ),
            channels = ReplyChannelHandlers(channels, {}, {}, {}, {}, { _, _ -> }, {}),
            owner = ThreadOwnerHandlers(owner, {}, {}, {}),
        )
    }

    private val eyebrow = str(R.string.thread_assign_label).uppercase()

    @Test
    fun `the assign picker says who it is assigned to and what a tap does, once`() {
        thread()
        val picker = composeRule.onNodeWithTag(THREAD_OWNER_PICKER_DESCRIPTION).config()
        assertEquals(
            listOf(str(R.string.inbox_assigned_to, "teammate@example.com")),
            picker[SemanticsProperties.ContentDescription],
        )
        assertEquals(str(R.string.thread_assign_change), picker[SemanticsActions.OnClick].label)
        // The eyebrow is in the button's reading, so it is not read a second time.
        composeRule.onAllNodesWithText(eyebrow, useUnmergedTree = true).assertCountEquals(0)

        composeRule.onNodeWithTag(THREAD_OWNER_PICKER_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(THREAD_OWNER_NOBODY_DESCRIPTION).assert(selected(false))
        composeRule.onNodeWithTag(THREAD_OWNER_OPTION_DESCRIPTION + 0).assert(selected(false))
        composeRule.onNodeWithTag(THREAD_OWNER_OPTION_DESCRIPTION + 1).assert(selected(true))
    }

    @Test
    fun `the assign picker on an unassigned thread reads Unassigned, and that option is the selected one`() {
        thread(owner = ThreadOwnerState(loaded = true, owner = null, canAssign = true, members = members))
        val picker = composeRule.onNodeWithTag(THREAD_OWNER_PICKER_DESCRIPTION).config()
        assertEquals(listOf(str(R.string.thread_unassigned)), picker[SemanticsProperties.ContentDescription])

        composeRule.onNodeWithTag(THREAD_OWNER_PICKER_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(THREAD_OWNER_NOBODY_DESCRIPTION).assert(selected(true))
    }

    @Test
    fun `without a picker the owner line is one reading, and a failure to assign is announced`() {
        thread(
            owner = ThreadOwnerState(
                loaded = true,
                owner = "teammate@example.com",
                failure = FailureText(UiText.Resource(R.string.failure_offline)),
            ),
        )
        val merged = composeRule.onNodeWithText("teammate@example.com")
            .config()[SemanticsProperties.Text]
            .map { it.text }
        assertEquals(listOf(eyebrow, "teammate@example.com"), merged)
        composeRule.onNodeWithTag(THREAD_OWNER_FAILURE_DESCRIPTION).assertLive()
    }

    @Test
    fun `reply channels are one choice of several, radio buttons in a group`() {
        thread()
        composeRule.onNodeWithTag(THREAD_CHANNEL_CHIP_DESCRIPTION + 0)
            .assert(radio)
            .assert(selected(true))
        composeRule.onNodeWithTag(THREAD_CHANNEL_CHIP_DESCRIPTION + 1)
            .assert(radio)
            .assert(selected(false))
        composeRule.onNodeWithTag(THREAD_CHANNEL_CHIP_DESCRIPTION + 0, useUnmergedTree = true)
            .assert(
                SemanticsMatcher("inside a selectable group") { node ->
                    generateSequence(node.parent) { it.parent }.any { SemanticsProperties.SelectableGroup in it.config }
                },
            )
    }

    @Test
    fun `the template sheet's choices are radio buttons, and its failure is announced`() {
        thread(
            channels = ReplyChannelState(
                listOf(whatsApp, sms),
                whatsApp,
                WhatsAppWindowState.Closed("s1", WhatsAppTemplatesState.Ready(listOf(reminder))),
                TemplateSheetState(
                    open = true,
                    chosen = reminder,
                    values = listOf("Ada", ""),
                    failure = FailureText(UiText.Resource(R.string.failure_offline)),
                ),
            ),
        )
        composeRule.onNodeWithTag(THREAD_TEMPLATE_OPTION_DESCRIPTION + 0)
            .assert(radio)
            .assert(selected(true))
        composeRule.onNodeWithTag(THREAD_TEMPLATE_FAILURE_DESCRIPTION).assertLive()
    }

    @Test
    fun `the template list's status line is announced`() {
        thread(
            channels = ReplyChannelState(
                listOf(whatsApp, sms),
                whatsApp,
                WhatsAppWindowState.Closed("s1", WhatsAppTemplatesState.Failed),
                TemplateSheetState(open = true),
            ),
        )
        composeRule.onNodeWithTag(THREAD_TEMPLATE_STATUS_DESCRIPTION).assertLive()
        composeRule.onNodeWithText(str(R.string.thread_whatsapp_window_closed)).assertLive()
    }

    @Test
    fun `the Meta reply window, closed or open for a human agent, is announced when it arrives`() {
        thread(channels = ReplyChannelState(listOf(messenger, instagram), messenger, meta = MetaWindowState.CLOSED))
        composeRule.onNodeWithTag(THREAD_META_WINDOW_CLOSED_DESCRIPTION).assertLive()
    }

    @Test
    fun `the human agent window badge is announced`() {
        thread(
            channels = ReplyChannelState(listOf(messenger, instagram), instagram, meta = MetaWindowState.HUMAN_AGENT),
        )
        composeRule.onNodeWithTag(THREAD_META_HUMAN_AGENT_DESCRIPTION).assertLive()
    }

    // ── Live supervise ──────────────────────────────────────────────────────────────────────────

    private val ai = FakeSuperviseApi.AI_CALL
    private val team = FakeSuperviseApi.TEAM_CALL
    private val actions = LiveCallsActions({}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    private val listening = LiveSupervisor.State(callId = ai.id, phase = LiveSupervisor.Phase.CONNECTED, joined = true)

    private fun live(
        state: LiveSupervisor.State,
        whispers: Map<String, WhisperBox> = emptyMap(),
        prompt: LiveCallsPrompt = LiveCallsPrompt(),
    ) = show {
        LiveCallsScreen(
            LiveCallsView(LiveCallList.Loaded(listOf(ai, team)), state, whispers, prompt, canWrite = true),
            actions,
        )
    }

    @Test
    fun `each call is a heading, and its Listen says whom`() {
        live(LiveSupervisor.State())
        composeRule.onNodeWithText("Dana Smith", useUnmergedTree = true).assertHeading()
        val listens = composeRule.onAllNodesWithText(str(R.string.live_calls_listen))
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.ContentDescription].single() }
        assertEquals(2, listens.size)
        assertEquals(str(R.string.live_calls_listen_to, "Dana Smith"), listens.first())
        assertTrue("the visible word comes first", listens.all { it.startsWith(str(R.string.live_calls_listen)) })
        composeRule.onAllNodesWithText(str(R.string.live_calls_listen)).fetchSemanticsNodes().forEach { node ->
            assertEquals(LIVE_CALLS_LISTEN_TAG, node.config[SemanticsProperties.TestTag])
        }
    }

    @Test
    fun `the supervised state, a note's receipt and a failure are announced as they change`() {
        live(
            listening,
            mapOf(ai.id to WhisperBox(receipt = WhisperAppliedData(applied = true, firstWords = "We can do Thursday"))),
        )
        composeRule.onNodeWithTag(LIVE_CALLS_STATE_TAG).assertLive()
        composeRule.onNodeWithTag(LIVE_CALLS_RECEIPT_TAG).assertLive()
    }

    @Test
    fun `a failure at the call is announced`() {
        live(LiveSupervisor.State(failedCallId = ai.id, failure = UiText.Resource(R.string.api_error_call_ended)))
        composeRule.onNodeWithTag(LIVE_CALLS_FAILURE_LINE_TAG).assertLive()
    }

    @Test
    fun `the take-over confirmation, which replaces its button in place, is a heading and is announced`() {
        live(listening, prompt = LiveCallsPrompt(confirmingTakeover = ai.id))
        composeRule.onNodeWithTag(LIVE_CALLS_CONFIRM_TAG).assertExists()
        composeRule.onNodeWithText(str(R.string.live_calls_take_over_confirm_title), useUnmergedTree = true)
            .assertHeading()
            .assertLive()
    }

    // ── The dialler's country list ──────────────────────────────────────────────────────────────

    /** The first row for [iso]: a suggested country is listed twice, and the first is the suggestion. */
    private fun countryRow(iso: String) =
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, countryRowDescription(iso)))
            .fetchSemanticsNodes()
            .first()

    private fun countries() = show { DialerCountryPicker(home = "CA", current = "FR", onPick = {}, onDismiss = {}) }

    @Test
    fun `a country row is at least 48dp, reads no flag, and the current country is selected`() {
        countries()
        val france = countryRow("FR")
        val floor = with(composeRule.density) { 48.dp.roundToPx() }
        assertTrue("height ${france.size.height}", france.size.height >= floor)
        assertEquals(true, france.config[SemanticsProperties.Selected])
        assertEquals(listOf("France", "+33"), france.config[SemanticsProperties.Text].map { it.text })

        val canada = countryRow("CA")
        assertEquals(false, canada.config[SemanticsProperties.Selected])
    }

    @Test
    fun `the picker's title and groups are headings, and a search that matches nothing is announced`() {
        countries()
        composeRule.onNodeWithText(str(R.string.dialer_country)).assertHeading()
        composeRule.onNodeWithText(str(R.string.dialer_countries_suggested).uppercase()).assertHeading()

        composeRule.onNodeWithTag(DIALER_COUNTRY_SEARCH_DESCRIPTION).performTextInput("zzzzzz")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DIALER_COUNTRY_NO_MATCH_DESCRIPTION).assertLive()
    }
}
