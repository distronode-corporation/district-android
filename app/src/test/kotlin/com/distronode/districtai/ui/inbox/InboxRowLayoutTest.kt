package com.distronode.districtai.ui.inbox

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.AVATAR_FALLBACK_TAG
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.ConversationLastMessage
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Inbox row at a narrow phone width (320 dp): the badges go under the preview, an address with
 * no contact name and the owner line each stay on one line, and an avatar with no letter shows the
 * person icon.
 *
 * ⚠️ NATIVE GRAPHICS: the legacy mode measures no real glyphs, so it never wraps a line, and the two
 * one-line tests passed with their fix reverted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w320dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InboxRowLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val longAddress = "ig:17841400000000001234567890"
    private val longOwner = "a.very.long.teammate.address@example-workspace.com"

    private val mixed = ConversationSummary(
        threadKey = "contact:c1",
        counterpart = "+14165550100",
        contactName = "Ada Lovelace",
        channels = listOf("whatsapp", "sms"),
        assigneeEmail = longOwner,
        unreadCount = 2,
        lastMessage = ConversationLastMessage(body = "Hi, do you do flat roofs?"),
    )

    private val unnamed = ConversationSummary(
        threadKey = "addr:$longAddress",
        counterpart = longAddress,
        contactName = null,
        channels = listOf("instagram"),
        lastMessage = ConversationLastMessage(body = "Saw your post"),
    )

    private fun render(vararg rows: ConversationSummary) {
        composeRule.setContent {
            DistrictTheme {
                InboxScreen(
                    state = InboxUiState.Content(rows.toList(), partial = false),
                    onOpenThread = {},
                    onRetry = {},
                    onSignIn = {},
                    onBack = {},
                    searchState = InboxSearchState(),
                    onSearchQueryChanged = {},
                    onOpenHit = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** ⚠️ The unmerged tree: a row is clickable, so its texts merge into one node with its bounds. */
    private fun text(value: String) = composeRule.onNodeWithText(value, useUnmergedTree = true)

    private fun tag(value: String) = composeRule.onNodeWithTag(value, useUnmergedTree = true)

    private fun SemanticsNodeInteraction.lines(): Int {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().lineCount
    }

    @Test
    fun `on a narrow screen the channel chips and the unread count sit under the preview`() {
        render(mixed)

        val preview = text("Hi, do you do flat roofs?").getUnclippedBoundsInRoot()
        val chip = text(str(R.string.inbox_channel_whatsapp)).getUnclippedBoundsInRoot()
        val unread = tag(INBOX_UNREAD_DESCRIPTION).getUnclippedBoundsInRoot()

        assertTrue("the chip starts below the preview", chip.top >= preview.bottom)
        assertTrue("the unread count starts below the preview", unread.top >= preview.bottom)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `on a wider screen the badges stay beside the text`() {
        render(mixed)

        val preview = text("Hi, do you do flat roofs?").getUnclippedBoundsInRoot()
        val chip = text(str(R.string.inbox_channel_whatsapp)).getUnclippedBoundsInRoot()

        assertTrue("the chip sits beside the text", chip.left >= preview.right)
    }

    @Test
    fun `an address with no contact name stays on one line, never broken mid-token`() {
        render(unnamed)

        assertEquals(1, text(longAddress).lines())
    }

    @Test
    fun `the owner line stays on one line, never broken mid-address`() {
        render(mixed)

        assertEquals(1, text(str(R.string.inbox_assigned_to, longOwner)).lines())
    }

    @Test
    fun `an avatar for a name with no letter shows the person icon, never a dot`() {
        render(unnamed)

        tag(AVATAR_FALLBACK_TAG).assertExists()
        text("·").assertDoesNotExist()
    }
}
