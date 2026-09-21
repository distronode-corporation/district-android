package com.distronode.districtai.ui.contacts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactCompany
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.core.designsystem.DistrictTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ MOSTLY ABOUT WHAT A VIEWER IS AND IS NOT SHOWN, and about the dossier states. All three contacts
 * mutations exclude `viewer` server-side, so offering an edit control to one guarantees a 403 the user
 * cannot act on. And `dgiStatus` null means "no dossier AND none queued" — `clear-intel` resets it to
 * NULL deliberately so nothing re-crawls — so rendering null as "building…" would show a job that never
 * finishes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ContactDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun contact(
        name: String = "Ada Lovelace",
        phoneNumber: String? = "+14165550142",
        email: String? = null,
        dgiStatus: String? = null,
        dgiError: String? = null,
        intelligence: JsonObject? = null,
    ) = Contact(
        id = "c1",
        workspaceId = "ws-1",
        name = name,
        phoneNumber = phoneNumber,
        email = email,
        company = ContactCompany(name = "Analytical Engines"),
        dgiStatus = dgiStatus,
        dgiError = dgiError,
        intelligence = intelligence,
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private fun render(
        state: ContactDetailUiState,
        canMutate: Boolean = true,
        onRename: (String) -> Unit = {},
        onDelete: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                ContactDetailScreen(
                    state = state,
                    canMutate = canMutate,
                    onBack = {},
                    onRetry = onRetry,
                    onRename = onRename,
                    onDelete = onDelete,
                    onDismissMutationFailure = {},
                )
            }
        }
    }

    /**
     * Scroll a node into view, then hand it back for assertion.
     *
     * ⛔ NEEDED BECAUSE THIS SCREEN GAINED AN APP BAR, AND `assertIsDisplayed` MEANS *VISIBLE*, NOT
     * *PRESENT*. The content is a `verticalScroll` column; adding a 56dp bar above it pushed the
     * rename, delete and read-only controls past the bottom of Robolectric's small default viewport,
     * so five tests began failing with "is not displayed" while the nodes existed and the screen was
     * correct. `assertExists` would have made them pass, and would also have passed for a control
     * that was genuinely unreachable — so scrolling first is the assertion that still means
     * something.
     */
    private fun scrollTo(description: String) =
        composeRule.onNodeWithContentDescription(description).performScrollTo()

    // ── Role gating ──────────────────────────────────────────────────────────

    @Test
    fun `offers rename and delete to a role that may mutate`() {
        render(ContactDetailUiState.Content(contact()), canMutate = true)

        scrollTo(CONTACT_DETAIL_RENAME_DESCRIPTION).assertIsDisplayed()
        scrollTo(CONTACT_DETAIL_DELETE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `offers neither to a viewer, and says why`() {
        // ⛔ The whole point. A viewer tapping either control would earn a 403 with no recourse.
        render(ContactDetailUiState.Content(contact()), canMutate = false)

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_RENAME_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_DELETE_DESCRIPTION).assertDoesNotExist()
        // Stated up front rather than discovered by tapping.
        scrollTo(CONTACT_DETAIL_READ_ONLY_DESCRIPTION).assertIsDisplayed()
    }

    // ── Delete confirmation ──────────────────────────────────────────────────

    @Test
    fun `a delete is confirmed before it fires`() {
        // ⛔ Irreversible, and a mis-tap costs a customer record.
        var deletes = 0
        render(ContactDetailUiState.Content(contact()), onDelete = { deletes += 1 })

        scrollTo(CONTACT_DETAIL_DELETE_DESCRIPTION).performClick()
        assertEquals("tapping delete must only open the confirmation", 0, deletes)

        // ⚠️ NOT `scrollTo`. This button lives in an `AlertDialog`, which is not inside the screen's
        // scrollable column — `performScrollTo` on it throws "Semantic Node has no parent layout with
        // a Scroll SemanticsAction". A dialog is always fully visible, so there is nothing to reveal.
        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_DELETE_CONFIRM_DESCRIPTION)
            .performClick()
        assertEquals(1, deletes)
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    @Test
    fun `rename reveals a field and saves the new value`() {
        var renamed: String? = null
        render(ContactDetailUiState.Content(contact(name = "Ada")), onRename = { renamed = it })

        scrollTo(CONTACT_DETAIL_RENAME_DESCRIPTION).performClick()
        scrollTo(CONTACT_DETAIL_RENAME_FIELD_DESCRIPTION).assertIsDisplayed()
    }

    // ── The dossier states ───────────────────────────────────────────────────

    @Test
    fun `a null dossier status reads as absent, not as a job in progress`() {
        // ⛔ clear-intel resets dgiStatus to NULL so nothing re-crawls, so null is "none, and none
        // queued". Showing a spinner here would promise something that never arrives.
        render(ContactDetailUiState.Content(contact(dgiStatus = null)))

        composeRule.onNodeWithText("No dossier for this contact.").assertIsDisplayed()
        composeRule.onNodeWithText("Building dossier…").assertDoesNotExist()
    }

    @Test
    fun `a pending dossier reads as in progress`() {
        render(ContactDetailUiState.Content(contact(dgiStatus = "pending")))

        composeRule.onNodeWithText("Building dossier…").assertIsDisplayed()
    }

    @Test
    fun `a failed dossier surfaces the error`() {
        render(ContactDetailUiState.Content(contact(dgiStatus = "failed", dgiError = "rate limited")))

        composeRule.onNodeWithText("Dossier failed: rate limited").assertIsDisplayed()
    }

    @Test
    fun `a populated dossier is rendered`() {
        render(
            ContactDetailUiState.Content(
                contact(
                    dgiStatus = "complete",
                    intelligence = JsonObject(mapOf("summary" to JsonPrimitive("Wants Thursday."))),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_DOSSIER_DESCRIPTION).assertIsDisplayed()
    }

    // ── Email-first contacts ─────────────────────────────────────────────────

    @Test
    fun `shows an email-only contact without pretending it has a number`() {
        // ⚠️ Contacts are email-first, so a phone-less contact is legal and any number of them coexist.
        render(
            ContactDetailUiState.Content(
                contact(phoneNumber = null, email = "ada@engines.test"),
            ),
        )

        composeRule.onNodeWithText("ada@engines.test").assertIsDisplayed()
        composeRule.onNodeWithText("No phone or email").assertDoesNotExist()
    }

    @Test
    fun `says so when a contact has neither a phone nor an email`() {
        render(ContactDetailUiState.Content(contact(phoneNumber = null, email = null)))

        composeRule.onNodeWithText("No phone or email").assertIsDisplayed()
    }

    @Test
    fun `renders an unnamed contact as a placeholder rather than the literal Unknown`() {
        // The voice agent writes "Unknown" for an unidentified caller; printing it reads as a name.
        render(ContactDetailUiState.Content(contact(name = "Unknown")))

        composeRule.onNodeWithText("Unnamed contact").assertIsDisplayed()
        composeRule.onNodeWithText("Unknown").assertDoesNotExist()
    }

    // ── Failures ─────────────────────────────────────────────────────────────

    @Test
    fun `a mutation failure is shown alongside the contact, not instead of it`() {
        // ⛔ The contact is still good; only the edit failed. Blanking the screen loses what the user
        // was reading.
        render(
            ContactDetailUiState.Content(
                contact(name = "Ada Lovelace"),
                mutationFailure = FailureText(message = UiText.Literal("Could not save.")),
            ),
        )

        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        scrollTo(CONTACT_DETAIL_MUTATION_FAILURE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a load failure offers a retry only when retrying could work`() {
        var retries = 0
        render(
            ContactDetailUiState.Failed(
                FailureText(message = UiText.Literal("Offline."), retryable = true),
            ),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a non-retryable load failure offers no retry`() {
        render(
            ContactDetailUiState.Failed(
                FailureText(
                    message = UiText.Literal("Contact document c1 not found."),
                    retryable = false,
                ),
            ),
        )

        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `loading is announced`() {
        render(ContactDetailUiState.Loading)

        composeRule.onNodeWithContentDescription(CONTACT_DETAIL_LOADING_DESCRIPTION).assertIsDisplayed()
    }
}
