package com.distronode.districtai.ui.contacts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class BlockedContactsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ada = BlockedContact("c1", "Ada", "+14165550142", "2026-10-13T12:00:00.000Z")
    private val unnamed = BlockedContact("c2", " ", null, "2026-10-13T12:00:00.000Z")

    private var unblocked = mutableListOf<String>()
    private var dismissed = 0

    private fun render(state: BlockedContactsUiState, canMutate: Boolean = true) {
        composeRule.setContent {
            DistrictTheme {
                BlockedContactsScreen(
                    state = state,
                    canMutate = canMutate,
                    onBack = {},
                    onRetry = {},
                    onSignIn = {},
                    onUnblock = { unblocked += it },
                    onDismissFailure = { dismissed++ },
                )
            }
        }
    }

    @Test
    fun `loading and a failed read each say so`() {
        render(BlockedContactsUiState.Loading)
        composeRule.onNodeWithTag(BLOCKED_LOADING_TAG).assertIsDisplayed()
    }

    @Test
    fun `a failed read is not an empty list`() {
        render(BlockedContactsUiState.Failed(FailureText(UiText.Literal("Could not load."))))
        composeRule.onNodeWithTag(BLOCKED_FAILURE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(BLOCKED_EMPTY_TAG).assertDoesNotExist()
    }

    @Test
    fun `no one blocked is an empty state`() {
        render(BlockedContactsUiState.Content(emptyList()))
        composeRule.onNodeWithText(str(R.string.contact_block_empty)).assertIsDisplayed()
    }

    @Test
    fun `an unblock is confirmed first`() {
        render(BlockedContactsUiState.Content(listOf(ada, unnamed)))
        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.contacts_unnamed)).assertIsDisplayed()
        composeRule.onNodeWithTag(blockedUnblockTag("c1")).performClick()
        composeRule.onNodeWithText(str(R.string.contact_block_unblock_prompt)).assertIsDisplayed()
        composeRule.onNodeWithTag(BLOCK_CONFIRM_TAG).performClick()
        assertEquals(listOf("c1"), unblocked)
    }

    @Test
    fun `cancelling the confirmation unblocks no one`() {
        render(BlockedContactsUiState.Content(listOf(ada)))
        composeRule.onNodeWithTag(blockedUnblockTag("c1")).performClick()
        composeRule.onNodeWithText(str(R.string.contact_block_cancel)).performClick()
        assertEquals(emptyList<String>(), unblocked)
    }

    @Test
    fun `rows are disabled while one is being unblocked, and a failure can be dismissed`() {
        render(
            BlockedContactsUiState.Content(
                listOf(ada),
                unblocking = "c1",
                failure = FailureText(UiText.Literal("Try again shortly.")),
            ),
        )
        composeRule.onNodeWithTag(blockedUnblockTag("c1")).assertIsNotEnabled()
        composeRule.onNodeWithText("Try again shortly.").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.contact_block_dismiss)).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a viewer sees the badge instead of Unblock`() {
        render(BlockedContactsUiState.Content(listOf(ada)), canMutate = false)
        composeRule.onNodeWithTag(blockedUnblockTag("c1")).assertDoesNotExist()
        composeRule.onNodeWithText(str(R.string.contact_block_badge)).assertIsDisplayed()
    }
}
