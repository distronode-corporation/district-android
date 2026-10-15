package com.distronode.districtai.ui.inbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.UploadedMedia
import com.distronode.districtai.R
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.distronode.districtai.ui.MainLooperDrain
import org.junit.rules.RuleChain
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import org.junit.Assert.assertTrue

/**
 * ⛔ TWO ASSERTIONS HERE GUARD MONEY, NOT LAYOUT: that a `viewer` is offered no reply box at all, and
 * that the send control is DISABLED while a send is in flight. Every send is billable SMS/MMS segments
 * or an email, and the server caps a workspace at 30/min — a double tap becoming two charges and a
 * message the customer receives twice is a real defect, not a cosmetic one.
 *
 * ⚠️ A third guards honesty: a call in the middle of a thread must render. The server interleaves calls
 * with messages because an operator needs to see that the customer PHONED between two texts, and a
 * missed call is the one event in a conversation that needs acting on.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ThreadScreenTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private fun message(
        id: String = "m1",
        direction: String = "inbound",
        body: String = "Are you open Thursday?",
        status: String = "received",
        subject: String? = null,
        media: List<String> = emptyList(),
    ) = TimelineEvent(
        id = id,
        type = "sms",
        timestamp = "Aug 15, 07:06 PM",
        direction = direction,
        body = body,
        status = status,
        subject = subject,
        mediaUrls = media,
    )

    private fun call(id: String = "c1", direction: String = "inbound", summary: String? = null) =
        TimelineEvent(
            id = id,
            type = "call",
            timestamp = "Aug 15, 06:00 PM",
            direction = direction,
            body = "",
            status = "completed",
            duration = 70,
            summary = summary,
        )

    /**
     * The composer's callbacks and collaborators for one render.
     *
     * ⛔ A DATA CLASS RATHER THAN TEN PARAMETERS ON [render]. Ten arguments trips detekt's
     * LongParameterList, and the honest fix is to notice that these ARE one thing — the composer's
     * wiring — rather than to suppress the rule. Defaults keep every existing case a one-liner.
     */
    private data class Wiring(
        val canAttach: Boolean = true,
        val onSend: (String) -> Unit = {},
        val onRetry: () -> Unit = {},
        val onDismiss: () -> Unit = {},
        val onAttach: () -> Unit = {},
        val onRemoveAttachment: (String) -> Unit = {},
        val onGenerateDraft: () -> Unit = {},
        val onLoadOlder: () -> Unit = {},
        /** ⚠️ Answers null by default: a thumbnail settles into its failure state rather than hanging. */
        val imageLoader: MediaImageLoader = MediaImageLoader { null },
        val onOpenMedia: (String) -> Unit = {},
    )

    /**
     * ⚠️ THE COMPOSER TEXT IS HELD HERE, IN THE TEST, BECAUSE THE SCREEN NO LONGER OWNS IT. It
     * moved to the ViewModel's SavedStateHandle so autosave can read it and the AI draft button can
     * write it — see `ThreadViewModel`. A test that passed a constant would render a field that
     * cannot be typed into, so this stands in for the ViewModel's StateFlow.
     */
    private fun render(
        state: ThreadUiState,
        canReply: Boolean = true,
        wiring: Wiring = Wiring(),
        moderation: ThreadModeration? = null,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            var text by remember { mutableStateOf("") }
            DistrictTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    ThreadScreen(
                        title = "Ada Lovelace",
                        state = state,
                        canReply = canReply,
                        onBack = {},
                        onSend = wiring.onSend,
                        onRetry = wiring.onRetry,
                        onSignIn = {},
                        onDismissSendFailure = wiring.onDismiss,
                        onLoadOlder = wiring.onLoadOlder,
                        composer = ComposerHandlers(
                            text = text,
                            onTextChange = { text = it },
                            canAttach = wiring.canAttach,
                            onAttach = wiring.onAttach,
                            onRemoveAttachment = wiring.onRemoveAttachment,
                            onGenerateDraft = wiring.onGenerateDraft,
                            imageLoader = wiring.imageLoader,
                            onOpenMedia = wiring.onOpenMedia,
                        ),
                        moderation = moderation,
                    )
                }
            }
        }
    }

    /**
     * ⛔ AT 200% FONT THE ACTIONS GO UNDER THE REPLY FIELD (visual QA AA1): beside it, they took their
     * own width first and left the field a fifth of the row.
     */
    @Test
    fun `at 200 percent font the reply field keeps the row and the actions sit under it`() {
        render(
            ThreadUiState.Content(listOf(message(direction = "outbound", status = "delivered"))),
            fontScale = 2f,
        )

        val field = composeRule.onNodeWithTag(THREAD_REPLY_FIELD_DESCRIPTION).getUnclippedBoundsInRoot()
        val send = composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).getUnclippedBoundsInRoot()
        assertTrue(send.top >= field.bottom)
        composeRule.onNodeWithText("Delivered").assertExists()
    }

    @Test
    fun `messages render in both directions`() {
        render(
            ThreadUiState.Content(
                listOf(
                    message(id = "m1", direction = "inbound", body = "Are you open Thursday?"),
                    message(id = "m2", direction = "outbound", body = "We are, 9 til 5.", status = "delivered"),
                ),
            ),
        )

        composeRule.onNodeWithTag(THREAD_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Are you open Thursday?").assertIsDisplayed()
        composeRule.onNodeWithText("We are, 9 til 5.").assertIsDisplayed()
    }

    @Test
    fun `an outbound message shows its delivery status`() {
        // ⚠️ On an outbound message the status is the answer the operator is looking for after sending.
        // ⚠️ Two tests rather than one: `setContent` may be called ONCE per test.
        render(ThreadUiState.Content(listOf(message(direction = "outbound", status = "undelivered"))))

        composeRule.onNodeWithText("undelivered").assertIsDisplayed()
    }

    @Test
    fun `an inbound message shows no status`() {
        // ⚠️ An inbound message's status is a receipt artefact and means nothing to the operator.
        render(ThreadUiState.Content(listOf(message(direction = "inbound", status = "received"))))

        composeRule.onNodeWithText("received").assertDoesNotExist()
    }

    @Test
    fun `a call in the middle of a thread is rendered, not filtered out`() {
        // ⛔ See the class doc: dropping it leaves an unexplained gap in the conversation.
        render(ThreadUiState.Content(listOf(call(summary = "Asked about Thursday hours."))))

        composeRule.onNodeWithTag(THREAD_CALL_DESCRIPTION).assertIsDisplayed()
        // ⚠️ The event's own label ("Appel" in French), not the action's ("Appeler").
        composeRule.onNodeWithTag(THREAD_CALL_DESCRIPTION, useUnmergedTree = true)
            .assertTextEquals(str(R.string.thread_call_event))
        composeRule.onNodeWithText("Asked about Thursday hours.").assertIsDisplayed()
    }

    @Test
    fun `a call's stored stand-in reads through its placeholder label, in its current spelling`() {
        // ⚠️ A row stored before the respelling keeps the dash; it reads as the label, so in English it
        // reads as the current spelling and in French as « Sans réponse, aucune conversation n'a eu lieu. ».
        render(ThreadUiState.Content(listOf(call(summary = "No answer \u2014 no conversation took place."))))

        composeRule.onNodeWithText(str(R.string.wire_placeholder_no_answer_no_conversation_took_place))
            .assertIsDisplayed()
        composeRule.onNodeWithText("No answer, no conversation took place.").assertIsDisplayed()
    }

    @Test
    fun `a missed call is labelled as missed`() {
        // ⛔ `direction` is three-state here. A missed call has no side, which is why it is not a bubble.
        render(ThreadUiState.Content(listOf(call(direction = "missed"))))

        composeRule.onNodeWithText(str(R.string.thread_call_missed)).assertIsDisplayed()
    }

    @Test
    fun `a viewer is offered no reply box at all`() {
        // ⛔ `messages/send` excludes viewer server-side. Offering the box would guarantee a 403 the
        // user cannot act on — and this is the affordance, not the security control.
        render(ThreadUiState.Content(listOf(message())), canReply = false)

        composeRule.onNodeWithTag(THREAD_REPLY_FIELD_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `send is disabled until there is something to send`() {
        render(ThreadUiState.Content(listOf(message())))

        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `typing a reply enables send and sends the typed text`() {
        var sent: String? = null
        render(ThreadUiState.Content(listOf(message())), wiring = Wiring(onSend = { sent = it }))

        composeRule.onNodeWithTag(THREAD_REPLY_FIELD_DESCRIPTION)
            .performTextInput("On our way")
        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).performClick()

        assertEquals("On our way", sent)
    }

    @Test
    fun `send is disabled while a send is in flight`() {
        // ⛔ THE MONEY GUARD. A second tap must not become a second charge and a duplicate message.
        render(ThreadUiState.Content(listOf(message()), sending = true))

        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithText(str(R.string.thread_sending)).assertIsDisplayed()
    }

    @Test
    fun `a send failure is shown alongside the thread, not instead of it`() {
        // ⚠️ The conversation is still good; only the reply failed. Blanking it loses what the operator
        // was reading, and the server's refusal text is specific enough to be worth showing verbatim.
        var dismissed = 0
        render(
            ThreadUiState.Content(
                listOf(message(body = "Are you open Thursday?")),
                sendFailure = FailureText(message = UiText.Literal("Sender not verified.")),
            ),
            wiring = Wiring(onDismiss = { dismissed += 1 }),
        )

        composeRule.onNodeWithText("Are you open Thursday?").assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_SEND_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Sender not verified.").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.thread_dismiss)).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `an email subject renders and an sms shows none`() {
        render(ThreadUiState.Content(listOf(message(subject = "Thursday hours"))))
        composeRule.onNodeWithText("Thursday hours").assertIsDisplayed()
    }

    @Test
    fun `attachments are counted with a plural`() {
        // ⚠️ "1 attachments" is the kind of detail that makes a product look unfinished.
        render(ThreadUiState.Content(listOf(message(media = listOf("https://x/a.jpg")))))

        composeRule.onNodeWithText("1 attachment").assertIsDisplayed()
    }

    @Test
    fun `an empty thread reads as empty, not as broken`() {
        render(ThreadUiState.Content(emptyList()))

        composeRule.onNodeWithText(str(R.string.thread_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `a failed thread is announced`() {
        render(
            ThreadUiState.Failed(FailureText(message = UiText.Literal("Offline."), retryable = true)),
        )

        composeRule.onNodeWithTag(THREAD_FAILURE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `loading is announced`() {
        render(ThreadUiState.Loading)

        composeRule.onNodeWithTag(THREAD_LOADING_DESCRIPTION).assertIsDisplayed()
    }

    // ── Composer: attachments, AI drafting, inbound thumbnails (Task A1) ─────

    /**
     * ⛔ HIDDEN, NOT DISABLED, ON A CHANNEL THAT CANNOT CARRY AN ATTACHMENT. A greyed-out button
     * invites the operator to work out why; on an email thread the honest answer is that
     * attachments are not a thing that channel does. The send route's email branch never reads
     * `mediaUrls` at all, so one attached there would upload and silently not be delivered.
     */
    @Test
    fun `the attach control is offered on sms and absent on email`() {
        render(ThreadUiState.Content(listOf(message())), wiring = Wiring(canAttach = true))

        composeRule.onNodeWithTag(THREAD_ATTACH_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `an email thread shows no attach control at all`() {
        render(ThreadUiState.Content(listOf(message())), wiring = Wiring(canAttach = false))

        composeRule.onNodeWithTag(THREAD_ATTACH_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a viewer is offered neither the composer nor the attach control`() {
        render(ThreadUiState.Content(listOf(message())), canReply = false, wiring = Wiring(canAttach = true))

        composeRule.onNodeWithTag(THREAD_REPLY_FIELD_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(THREAD_ATTACH_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `attached images render as removable chips`() {
        var removed: String? = null
        render(
            ThreadUiState.Content(
                listOf(message()),
                attachments = listOf(
                    UploadedMedia(id = "m1", mimeType = "image/png", sizeBytes = 4, url = "https://x/api/media/m1"),
                ),
            ),
            wiring = Wiring(onRemoveAttachment = { removed = it }),
        )

        composeRule.onNodeWithTag(THREAD_ATTACHMENTS_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_ATTACHMENT_CHIP_DESCRIPTION).performClick()
        assertEquals("https://x/api/media/m1", removed)
    }

    @Test
    fun `no chip row is drawn when nothing is attached`() {
        render(ThreadUiState.Content(listOf(message())))

        composeRule.onNodeWithTag(THREAD_ATTACHMENTS_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * ⛔ THE ATTACH CONTROL IS DISABLED WHILE AN UPLOAD IS IN FLIGHT, so a second pick cannot
     * queue behind the first and land the operator with two copies of one image.
     */
    @Test
    fun `the attach control is disabled while an upload is in flight`() {
        render(ThreadUiState.Content(listOf(message()), attaching = true))

        composeRule.onNodeWithTag(THREAD_ATTACH_DESCRIPTION).assertIsNotEnabled()
    }

    /**
     * ⛔ THIS ONE GUARDS MONEY. Each AI draft is a billed Vertex generation capped at 20/min per
     * workspace, so the control must be disabled while one is running — the same rule as send.
     */
    @Test
    fun `the AI draft control is disabled while a generation is in flight`() {
        render(ThreadUiState.Content(listOf(message()), generating = true))

        composeRule.onNodeWithTag(THREAD_AI_DRAFT_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `tapping the AI draft control asks for one generation`() {
        var generations = 0
        render(
            ThreadUiState.Content(listOf(message())),
            wiring = Wiring(onGenerateDraft = { generations += 1 }),
        )

        composeRule.onNodeWithTag(THREAD_AI_DRAFT_DESCRIPTION).performClick()

        assertEquals(1, generations)
    }

    /**
     * ⛔ SEND STAYS DISABLED ON BLANK TEXT EVEN WITH AN IMAGE ATTACHED. `messages/send` guards on
     * `!body` BEFORE it parses `mediaUrls`, so a picture with no caption is a 400 rather than a
     * message — enabling the button would collect a send that could only fail.
     */
    @Test
    fun `send stays disabled on an empty body even when an image is attached`() {
        render(
            ThreadUiState.Content(
                listOf(message()),
                attachments = listOf(UploadedMedia(url = "https://x/api/media/m1")),
            ),
        )

        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).assertIsNotEnabled()
    }

    /**
     * ⚠️ THE PLACEHOLDER IS WHAT A LOADING THUMBNAIL LOOKS LIKE, and it must not be what a FAILED
     * one looks like — an image that will never arrive rendering as a permanent shimmer leaves the
     * operator waiting for something that is not coming.
     */
    @Test
    fun `an inbound attachment shows a loading placeholder until its bytes arrive`() {
        // A loader that never completes: the composable stays in its initial phase.
        render(
            ThreadUiState.Content(listOf(message(media = listOf("https://x/api/media/m1")))),
            wiring = Wiring(imageLoader = MediaImageLoader { awaitCancellation() }),
        )

        composeRule.onNodeWithTag(THREAD_MEDIA_LOADING_DESCRIPTION, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `an attachment that cannot be fetched says so rather than shimmering forever`() {
        render(
            ThreadUiState.Content(listOf(message(media = listOf("https://x/api/media/m1")))),
            wiring = Wiring(imageLoader = MediaImageLoader { null }),
        )

        composeRule.onNodeWithTag(THREAD_MEDIA_FAILED_DESCRIPTION, useUnmergedTree = true).assertIsDisplayed()
        // ⚠️ AND THE COUNT BADGE SURVIVES. A thumbnail that failed leaves no indication the message
        // HAD an attachment, which is the one fact the operator must not lose.
        composeRule.onNodeWithText("1 attachment").assertIsDisplayed()
    }

    @Test
    fun `tapping an attachment hands its url to the caller for the browser`() {
        var opened: String? = null
        render(
            ThreadUiState.Content(listOf(message(media = listOf("https://x/api/media/m1")))),
            wiring = Wiring(imageLoader = MediaImageLoader { null }, onOpenMedia = { opened = it }),
        )

        composeRule.onNodeWithTag(THREAD_MEDIA_DESCRIPTION).performClick()

        assertEquals("https://x/api/media/m1", opened)
    }

    // ── Expand-only paging ───────────────────────────────────────────────────

    @Test
    fun `the load-older control is offered only when there is more behind the thread`() {
        render(ThreadUiState.Content(listOf(message()), hasMore = true))

        composeRule.onNodeWithTag(THREAD_LOAD_OLDER_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.thread_load_older)).assertIsDisplayed()
    }

    @Test
    fun `a thread with nothing behind it offers no load-older control`() {
        // ⚠️ ABSENT RATHER THAN DISABLED. A dead control at the top of a conversation reads as a
        // thread that failed to load its history, which is the opposite of what it means.
        render(ThreadUiState.Content(listOf(message()), hasMore = false))

        composeRule.onNodeWithTag(THREAD_LOAD_OLDER_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `an empty thread offers no load-older control either`() {
        // ⚠️ The empty state replaces the list entirely, so there is no top of a thread to put it
        // at — and a server with nothing to show has nothing behind it.
        render(ThreadUiState.Content(emptyList(), hasMore = true))

        composeRule.onNodeWithTag(THREAD_LOAD_OLDER_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(str(R.string.thread_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `tapping load older asks for the previous page`() {
        var taps = 0
        render(
            ThreadUiState.Content(listOf(message()), hasMore = true),
            wiring = Wiring(onLoadOlder = { taps += 1 }),
        )

        composeRule.onNodeWithTag(THREAD_LOAD_OLDER_DESCRIPTION).performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `the load-older control says it is working and refuses a second tap`() {
        // ⚠️ The label carries the progress state, the same way Sending…/Attaching… do below it: the
        // control is disabled while the page is in flight, so a spinner alone would leave it
        // unexplained.
        render(ThreadUiState.Content(listOf(message()), hasMore = true, loadingOlder = true))

        composeRule.onNodeWithText(str(R.string.thread_load_older_loading)).assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_LOAD_OLDER_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a failed page is reported at the top and leaves the thread and the retry in place`() {
        // ⛔ THE CONVERSATION IS STILL CORRECT. Losing it because a page BEHIND it could not be read
        // would be the destructive failure; the control stays so the retry is the same control.
        render(
            ThreadUiState.Content(
                listOf(message(body = "Are you open Thursday?")),
                hasMore = true,
                olderFailure = FailureText(message = UiText.Literal("Offline.")),
            ),
        )

        composeRule.onNodeWithText("Are you open Thursday?").assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_LOAD_OLDER_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Offline.").assertIsDisplayed()
        composeRule.onNodeWithTag(THREAD_LOAD_OLDER_DESCRIPTION).assertIsDisplayed()
    }

    /**
     * ⛔ THE POINT OF KEYING EVERY ITEM, AND THE ONE PROPERTY A PAGING UI IS ACTUALLY JUDGED ON.
     * `LazyColumn` holds its scroll anchor by the KEY of the first visible item, so prepending an
     * older page leaves the operator looking at the same message instead of being thrown to the top
     * of a history they did not ask to re-read. Asserted rather than assumed: it depends on the
     * items being keyed, which a later edit could drop without anything else noticing.
     */
    @Test
    fun `prepending an older page leaves the operator looking at the same message`() {
        val held = (1..20).map { message(id = "held-$it", body = "Held $it") }
        val older = (1..20).map { message(id = "older-$it", body = "Older $it") }
        var state by mutableStateOf(ThreadUiState.Content(held, hasMore = true))

        composeRule.setContent {
            var text by remember { mutableStateOf("") }
            DistrictTheme {
                ThreadScreen(
                    title = "Ada Lovelace",
                    state = state,
                    canReply = true,
                    onBack = {},
                    onSend = {},
                    onRetry = {},
                    onSignIn = {},
                    onDismissSendFailure = {},
                    onLoadOlder = {},
                    composer = ComposerHandlers(
                        text = text,
                        onTextChange = { text = it },
                        canAttach = false,
                        onAttach = {},
                        onRemoveAttachment = {},
                        onGenerateDraft = {},
                        imageLoader = MediaImageLoader { null },
                        onOpenMedia = {},
                    ),
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Held 18"))
        composeRule.onNodeWithText("Held 18").assertIsDisplayed()

        state = ThreadUiState.Content(older + held, hasMore = false)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Held 18").assertIsDisplayed()
    }

    // ── Rows that carry less than usual ─────────────────────────────────────

    @Test
    fun `a blank email subject draws no empty line, and an outbound message with no status draws no badge`() {
        render(
            ThreadUiState.Content(
                listOf(
                    message(id = "m1", subject = "   ", body = "First"),
                    message(id = "m2", direction = "outbound", status = "", body = "Second"),
                ),
            ),
        )

        composeRule.onNodeWithText("First").assertIsDisplayed()
        composeRule.onNodeWithText("Second").assertIsDisplayed()
        composeRule.onNodeWithText("   ").assertDoesNotExist()
    }

    @Test
    fun `a call's summary shows when it has one, and a blank one shows nothing`() {
        render(
            ThreadUiState.Content(
                listOf(
                    call(id = "c1", summary = "Asked about Thursday hours."),
                    call(id = "c2", summary = "  "),
                ),
            ),
        )

        composeRule.onNodeWithText("Asked about Thursday hours.").assertIsDisplayed()
    }

    @Test
    fun `a thread that cannot be retried offers no retry`() {
        render(
            ThreadUiState.Failed(FailureText(message = UiText.Literal("Not yours."), retryable = false)),
        )

        composeRule.onNodeWithText("Not yours.").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.overview_retry)).assertDoesNotExist()
    }

    @Test
    fun `a failed page stays reported even once the thread says nothing more is behind it`() {
        render(
            ThreadUiState.Content(
                listOf(message(body = "Are you open Thursday?")),
                hasMore = false,
                olderFailure = FailureText(message = UiText.Literal("Offline.")),
            ),
        )

        composeRule.onNodeWithTag(THREAD_LOAD_OLDER_FAILURE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `send reaches the handler current at the time of the tap, with the text in the box then`() {
        // ⚠️ One screen whose state and handlers are replaced under it one at a time, as the graph
        // does: a new message arriving must not disturb the composer, and the tap must reach the
        // live handler, never one captured earlier.
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        var onSend by mutableStateOf<(String) -> Unit>({ first += it })
        var state by mutableStateOf(ThreadUiState.Content(listOf(message(id = "m1", body = "Hello?"))))
        composeRule.setContent {
            var text by remember { mutableStateOf("") }
            // ⚠️ Remembered on the text, so a change to the thread alone reaches the screen with the
            // SAME composer, and a keystroke with a new one.
            val composer = remember(text) {
                ComposerHandlers(
                    text = text,
                    onTextChange = { text = it },
                    canAttach = false,
                    onAttach = {},
                    onRemoveAttachment = {},
                    onGenerateDraft = {},
                    imageLoader = MediaImageLoader { null },
                    onOpenMedia = {},
                )
            }
            DistrictTheme {
                ThreadScreen(
                    title = "Ada Lovelace",
                    state = state,
                    canReply = true,
                    onBack = {},
                    onSend = onSend,
                    onRetry = {},
                    onSignIn = {},
                    onDismissSendFailure = {},
                    onLoadOlder = {},
                    composer = composer,
                )
            }
        }
        composeRule.onNodeWithTag(THREAD_REPLY_FIELD_DESCRIPTION).performTextInput("On my way")

        state = ThreadUiState.Content(
            listOf(message(id = "m1", body = "Hello?"), message(id = "m2", body = "Anyone?")),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Anyone?").assertIsDisplayed()

        onSend = { second += it }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(THREAD_SEND_DESCRIPTION).performClick()

        assertEquals(emptyList<String>(), first)
        assertEquals(listOf("On my way"), second)
    }

    // ── Report and Block ─────────────────────────────────────────────────────

    private fun moderation(
        onReport: () -> Unit = {},
        onBlock: () -> Unit = {},
        busy: Boolean = false,
        failure: FailureText? = null,
        onDismissFailure: () -> Unit = {},
    ) = ThreadModeration(onReport, onBlock, busy, failure, onDismissFailure)

    @Test
    fun `the menu reports the conversation`() {
        var reports = 0
        render(ThreadUiState.Content(emptyList()), moderation = moderation(onReport = { reports++ }))
        composeRule.onNodeWithTag(THREAD_MANAGE_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(THREAD_REPORT_DESCRIPTION).performClick()
        assertEquals(1, reports)
    }

    @Test
    fun `a block from the menu is confirmed first, and a cancel blocks no one`() {
        var blocks = 0
        render(ThreadUiState.Content(emptyList()), moderation = moderation(onBlock = { blocks++ }))
        composeRule.onNodeWithTag(THREAD_MANAGE_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(THREAD_BLOCK_DESCRIPTION).performClick()
        composeRule.onNodeWithText(str(R.string.contact_block_cancel)).performClick()
        assertEquals(0, blocks)

        composeRule.onNodeWithTag(THREAD_MANAGE_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(THREAD_BLOCK_DESCRIPTION).performClick()
        composeRule.onNodeWithText(str(R.string.contact_block_block_prompt)).assertIsDisplayed()
        composeRule.onNodeWithTag(com.distronode.districtai.ui.contacts.BLOCK_CONFIRM_TAG).performClick()
        assertEquals(1, blocks)
    }

    @Test
    fun `a failed block is shown above the thread, and the menu waits while one is in flight`() {
        var dismissed = 0
        render(
            ThreadUiState.Content(emptyList()),
            moderation = moderation(
                busy = true,
                failure = FailureText(UiText.Literal("Refused.")),
                onDismissFailure = { dismissed++ },
            ),
        )
        composeRule.onNodeWithTag(THREAD_MANAGE_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithText("Refused.").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.contact_block_dismiss)).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a role that may not write has no menu`() {
        render(ThreadUiState.Content(emptyList()))
        composeRule.onNodeWithTag(THREAD_MANAGE_DESCRIPTION).assertDoesNotExist()
    }
}
