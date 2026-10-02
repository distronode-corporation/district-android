package com.distronode.districtai.ui

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.ConversationsResponse
import com.distronode.districtai.core.model.MessageSearchHit
import com.distronode.districtai.core.model.MessageSearchResponse
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.TimelineResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.inbox.INBOX_SEARCH_FIELD_DESCRIPTION
import com.distronode.districtai.ui.inbox.InboxViewModel
import com.distronode.districtai.ui.inbox.PickerProvider
import com.distronode.districtai.ui.inbox.THREAD_ATTACH_DESCRIPTION
import com.distronode.districtai.ui.inbox.THREAD_MEDIA_DESCRIPTION
import com.distronode.districtai.ui.inbox.THREAD_REPLY_FIELD_DESCRIPTION
import com.distronode.districtai.ui.inbox.THREAD_ROOT_DESCRIPTION
import com.distronode.districtai.ui.inbox.THREAD_SEND_DESCRIPTION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The Inbox and a thread, with conversations to open and a composer to drive.
 *
 * ⛔ THE REPLY TARGET IS RESOLVED IN THE GRAPH, NOT IN EITHER SCREEN. The list is the only place that
 * knows a thread's sendable address; the thread is the only place that sends. What travels between
 * them is the route's query arguments, and every assertion here is about what arrives.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class NavHostInboxTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    @After
    fun tearDown() = harness.close()

    /** Replyable by SMS: the server allows it and there is a number on the contact. */
    private val ada = ConversationSummary(
        key = "k-ada",
        threadKey = "contact:ct-4",
        counterpart = "+14165550100",
        kind = "sms",
        contactId = "ct-4",
        contactName = "Ada Lovelace",
        contactPhone = "+14165550100",
        canSms = true,
    )

    /** Nothing to reply on: the server marked the thread unsendable. */
    private val grace = ConversationSummary(
        key = "k-grace",
        threadKey = "addr:grace@example.com",
        counterpart = "grace@example.com",
        kind = "email",
        contactName = "Grace Hopper",
    )

    private fun openInbox(role: WorkspaceRole = WorkspaceRole.AGENCY) {
        harness.api.conversationsResult = ApiResult.Success(
            ConversationsResponse(success = true, conversations = listOf(ada, grace)),
        )
        harness.render()
        harness.navigate(Routes.inbox("ws-1", role))
        harness.awaitText("Ada Lovelace")
    }

    // ── Opening a thread from the list ─────────────────────────────────────────────────────

    @Test
    fun `a replyable conversation opens with its address, channel and name, and is marked read`() {
        openInbox()

        harness.tapText("Ada Lovelace")

        assertEquals(Routes.THREAD, harness.route())
        assertEquals("contact:ct-4", harness.argument(ARG_THREAD_KEY))
        assertEquals("+14165550100", harness.argument(ARG_REPLY_TO))
        assertEquals("sms", harness.argument(ARG_REPLY_CHANNEL))
        assertEquals("Ada Lovelace", harness.argument(ARG_THREAD_TITLE))
        assertEquals(listOf("ct-4"), harness.api.markReads.map { it.contactId })
    }

    @Test
    fun `an unsendable conversation opens with no reply target at all`() {
        openInbox()

        harness.tapText("Grace Hopper")

        assertEquals(Routes.THREAD, harness.route())
        assertNull(harness.argument(ARG_REPLY_TO))
        assertNull(harness.argument(ARG_REPLY_CHANNEL))
        assertEquals("Grace Hopper", harness.argument(ARG_THREAD_TITLE))
    }

    // ── Opening a thread from search ───────────────────────────────────────────────────────

    private fun search(query: String, hit: MessageSearchHit) {
        harness.search.searchResult = ApiResult.Success(MessageSearchResponse(success = true, results = listOf(hit)))
        composeRule.onNodeWithContentDescription(INBOX_SEARCH_FIELD_DESCRIPTION).performTextInput(query)
        // The search is debounced on the main looper; advance exactly past it.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(InboxViewModel.SEARCH_DEBOUNCE_MILLIS))
        composeRule.waitForIdle()
    }

    @Test
    fun `a search hit in a loaded thread replies on that conversation's target and records the read`() {
        openInbox()
        search(
            "Thursday",
            MessageSearchHit(
                messageId = "m1",
                threadKey = "contact:ct-4",
                counterpart = "+14165550100",
                contactId = "ct-4",
                contactName = "Ada in search",
                body = "Are you open Thursday?",
            ),
        )

        harness.tapText("Ada in search")

        assertEquals(listOf("Thursday"), harness.search.searches)
        assertEquals(Routes.THREAD, harness.route())
        assertEquals("+14165550100", harness.argument(ARG_REPLY_TO))
        assertEquals("sms", harness.argument(ARG_REPLY_CHANNEL))
        assertEquals("Ada in search", harness.argument(ARG_THREAD_TITLE))
        assertEquals(listOf("ct-4"), harness.api.markReads.map { it.contactId })
    }

    @Test
    fun `a search hit outside the loaded window opens read-only rather than guessing a channel`() {
        openInbox()
        search(
            "invoice",
            MessageSearchHit(
                messageId = "m2",
                threadKey = "addr:+14165550999",
                counterpart = "+14165550999",
                kind = "sms",
                contactName = "Someone older",
                body = "About the invoice",
            ),
        )

        harness.tapText("Someone older")

        assertEquals(Routes.THREAD, harness.route())
        assertEquals("addr:+14165550999", harness.argument(ARG_THREAD_KEY))
        assertNull(harness.argument(ARG_REPLY_TO))
        assertNull(harness.argument(ARG_REPLY_CHANNEL))
    }

    // ── The thread destination ─────────────────────────────────────────────────────────────

    @Test
    fun `a target recorded without a channel replies by SMS`() {
        harness.render()
        harness.navigate(
            Routes.thread("ws-1", WorkspaceRole.AGENCY, threadKey = "contact:ct-4", replyTo = "+14165550100"),
        )

        composeRule.onNodeWithContentDescription(THREAD_REPLY_FIELD_DESCRIPTION).performTextInput("See you then")
        harness.tap(THREAD_SEND_DESCRIPTION)

        val sent = harness.api.sends.single()
        assertEquals("+14165550100", sent.to)
        assertEquals("sms", sent.channel)
    }

    @Test
    fun `a contact thread opened with no title and no address is titled by the section`() {
        harness.render()
        harness.navigate(Routes.thread("ws-1", WorkspaceRole.CLIENT, threadKey = "contact:ct-4"))

        composeRule.onNodeWithContentDescription(THREAD_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Inbox").assertIsDisplayed()
    }

    @Test
    fun `blank query arguments count as absent, so the address titles the thread and nothing is sendable`() {
        // ⚠️ The builder never writes a blank value, but a route is a string anything can hand the
        // controller. A blank reply address must not become a recipient.
        harness.render()
        harness.navigate("workspace/ws-1/inbox/agency/addr:%2B14165550199?replyTo=%20&replyChannel=%20&title=%20")

        assertEquals(Routes.THREAD, harness.route())
        composeRule.onNodeWithText("+14165550199").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(THREAD_REPLY_FIELD_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the attach control opens the image-only picker and a picked image is uploaded`() {
        Robolectric.setupContentProvider(PickerProvider::class.java, PICKER_AUTHORITY)
        PickerProvider.reset(byteArrayOf(1, 2, 3))
        harness.registry.pickedUri = Uri.parse("content://$PICKER_AUTHORITY/photo")
        harness.render()
        harness.navigate(
            Routes.thread("ws-1", WorkspaceRole.AGENCY, "contact:ct-4", replyTo = "+14165550100", replyChannel = "sms"),
        )

        harness.tap(THREAD_ATTACH_DESCRIPTION)

        val request = harness.registry.launched.filterIsInstance<PickVisualMediaRequest>().single()
        assertEquals(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
            request.mediaType,
        )
        harness.awaitMain { harness.api.uploads.isNotEmpty() }
        assertEquals("image/jpeg", harness.api.uploads.single().second)
    }

    @Test
    fun `backing out of the picker attaches nothing`() {
        harness.registry.pickedUri = null
        harness.render()
        harness.navigate(
            Routes.thread("ws-1", WorkspaceRole.AGENCY, "contact:ct-4", replyTo = "+14165550100", replyChannel = "sms"),
        )

        harness.tap(THREAD_ATTACH_DESCRIPTION)

        assertEquals(1, harness.registry.launched.filterIsInstance<PickVisualMediaRequest>().size)
        assertTrue(harness.api.uploads.isEmpty())
    }

    @Test
    fun `an inbound image opens in the browser as a new task`() {
        harness.api.timelineResult = ApiResult.Success(
            TimelineResponse(
                success = true,
                timeline = listOf(
                    TimelineEvent(
                        id = "e1",
                        type = "sms",
                        timestamp = "Aug 15, 07:06 PM",
                        direction = "inbound",
                        body = "Here it is",
                        status = "received",
                        mediaUrls = listOf(MEDIA_URL),
                    ),
                ),
            ),
        )
        harness.render()
        harness.navigate(Routes.thread("ws-1", WorkspaceRole.AGENCY, "contact:ct-4"))

        harness.tap(THREAD_MEDIA_DESCRIPTION)

        val intent = harness.started.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MEDIA_URL, intent.dataString)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `an image nothing can open is dropped quietly rather than crashing the thread`() {
        harness.api.timelineResult = ApiResult.Success(
            TimelineResponse(
                success = true,
                timeline = listOf(
                    TimelineEvent(id = "e1", type = "sms", direction = "inbound", mediaUrls = listOf(MEDIA_URL)),
                ),
            ),
        )
        harness.startFailure = android.content.ActivityNotFoundException("no browser")
        harness.render()
        harness.navigate(Routes.thread("ws-1", WorkspaceRole.AGENCY, "contact:ct-4"))

        harness.tap(THREAD_MEDIA_DESCRIPTION)

        assertTrue(harness.started.isEmpty())
        composeRule.onNodeWithContentDescription(THREAD_ROOT_DESCRIPTION).assertIsDisplayed()
    }

    private companion object {
        const val PICKER_AUTHORITY = "navhost.picker"

        /**
         * ⚠️ HTTPS, BECAUSE `openAttachment` OPENS NOTHING ELSE, and port 0, which OkHttp's URL
         * parser rejects, so the real thumbnail loader still fails without opening a socket.
         */
        const val MEDIA_URL = "https://thumb-1.invalid:0/media"
    }
}
