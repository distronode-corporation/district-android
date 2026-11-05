package com.distronode.districtai.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.ConversationsResponse
import com.distronode.districtai.core.model.ReplyOption
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.inbox.THREAD_CHANNEL_CHIP_DESCRIPTION
import com.distronode.districtai.ui.inbox.encodeReplyChoices
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Reply channels between the Inbox and a thread (app parity 1 and 5), through the graph.
 *
 * ⛔ EVERY CHOICE TRAVELS, NOT JUST THE FIRST: the list is the only screen that has them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class NavHostInboxParityTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    @After
    fun tearDown() = harness.close()

    /** WhatsApp first, as the server orders a customer who last wrote there. */
    private val ada = ConversationSummary(
        threadKey = "contact:ct-4",
        counterpart = "+14165550100",
        contactId = "ct-4",
        contactName = "Ada Lovelace",
        canSms = true,
        replyOptions = listOf(
            ReplyOption("whatsapp", "+14165550100"),
            ReplyOption("email", "ada@example.com"),
            ReplyOption("sms", "+14165550100"),
        ),
    )

    /** The server's `[]`: nothing to reply on, whatever `canSms` says. */
    private val bea = ConversationSummary(
        threadKey = "addr:fb:5550101",
        counterpart = "fb:5550101",
        contactName = "Bea",
        canSms = true,
        replyOptions = emptyList(),
    )

    private fun openInbox() {
        harness.api.conversationsResult = ApiResult.Success(
            ConversationsResponse(success = true, conversations = listOf(ada, bea)),
        )
        harness.render()
        harness.navigate(Routes.inbox("ws-1", WorkspaceRole.AGENCY))
        harness.awaitText("Ada Lovelace")
    }

    @Test
    fun `a list tap carries every reply choice, and the thread offers them all`() {
        openInbox()

        harness.tapText("Ada Lovelace")

        assertEquals("+14165550100", harness.argument(ARG_REPLY_TO))
        assertEquals("whatsapp", harness.argument(ARG_REPLY_CHANNEL))
        assertEquals(
            """[{"channel":"email","to":"ada@example.com"},{"channel":"sms","to":"+14165550100"}]""",
            harness.argument(ARG_REPLY_MORE),
        )
        composeRule.onNodeWithTag(THREAD_CHANNEL_CHIP_DESCRIPTION + 2).assertIsDisplayed()
        assertEquals(
            "choosing WhatsApp first reads its window",
            listOf("ws-1" to "+14165550100"),
            harness.whatsApp.windowReads,
        )
    }

    @Test
    fun `a thread the server gave no reply option opens read-only`() {
        openInbox()

        harness.tapText("Bea")

        assertNull(harness.argument(ARG_REPLY_TO))
        assertNull(harness.argument(ARG_REPLY_MORE))
    }

    @Test
    fun `more choices with no first one are ignored rather than standing alone`() {
        harness.render()
        val more = encodeReplyChoices(listOf(ReplyTarget("a@b.c", "email")))
        val inbox = Routes.inbox("ws-1", WorkspaceRole.AGENCY)
        harness.navigate("$inbox/contact:ct-4?$ARG_REPLY_MORE=" + Uri.encode(more))

        composeRule.onNodeWithTag(THREAD_CHANNEL_CHIP_DESCRIPTION + 0).assertDoesNotExist()
    }
}
