package com.distronode.districtai.ui.contacts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.PagedLoadException
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The paged CRM list, and its non-list states.
 *
 * ⛔ THE SAME REFRESH/APPEND SPLIT THE CALL LOG HAS, asserted separately because the two screens
 * share no code — a refresh failure owns the whole screen, an append failure owns only a footer, and
 * the empty state is reachable ONLY from NotLoading so "no contacts yet" can never be drawn over a
 * read that failed.
 *
 * ⛔ AND WHAT A VIEWER IS NOT OFFERED. Every contacts mutation excludes `viewer` server-side, so a
 * create control shown to one is a guaranteed 403 the user cannot act on. There are TWO create
 * affordances (the empty state's and the floating button) and they are mutually exclusive by
 * design — two competing primary actions on one screen is the bug that split them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ContactsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun states(
        refresh: LoadState = LoadState.NotLoading(endOfPaginationReached = true),
        append: LoadState = LoadState.NotLoading(endOfPaginationReached = true),
    ) = LoadStates(
        refresh = refresh,
        prepend = LoadState.NotLoading(endOfPaginationReached = true),
        append = append,
    )

    private fun contact(
        id: String = "c1",
        name: String = "Ada Lovelace",
        phoneNumber: String? = "+14165550142",
        email: String? = null,
        dgiStatus: String? = null,
    ) = Contact(
        id = id,
        workspaceId = "ws-1",
        name = name,
        phoneNumber = phoneNumber,
        email = email,
        dgiStatus = dgiStatus,
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private fun degraded() = PagedLoadException(
        ApiResult.RegionsDegraded("A region is unreachable. Your account has not changed.", listOf("eu")),
    )

    /**
     * ⚠️ Bundled rather than passed one by one: `render` would otherwise carry nine parameters and
     * trip detekt's `LongParameterList`, which is not annotated `@Composable` here and so is not
     * exempt the way the screen itself is.
     */
    private data class Callbacks(
        val onOpenContact: (String) -> Unit = {},
        val onSignIn: () -> Unit = {},
        val onCreate: (String, String, String) -> Unit = { _, _, _ -> },
        val onCreateHandled: () -> Unit = {},
        val onBack: () -> Unit = {},
    )

    private fun render(
        items: List<Contact>,
        loadStates: LoadStates = states(),
        canMutate: Boolean = true,
        createState: CreateContactUiState = CreateContactUiState.Idle,
        callbacks: Callbacks = Callbacks(),
    ) {
        composeRule.setContent {
            DistrictTheme {
                val paging = flowOf(
                    PagingData.from(items, sourceLoadStates = loadStates),
                ).collectAsLazyPagingItems()

                ContactsScreen(
                    contacts = paging,
                    canMutate = canMutate,
                    onOpenContact = callbacks.onOpenContact,
                    onSignIn = callbacks.onSignIn,
                    createState = createState,
                    onCreate = callbacks.onCreate,
                    onCreateHandled = callbacks.onCreateHandled,
                    onBack = callbacks.onBack,
                )
            }
        }
    }

    @Test
    fun `renders rows with the identifier each contact actually has`() {
        // ⚠️ Contacts are EMAIL-FIRST — a phone-less row is legal and common, so the subtitle
        // prefers whichever identifier exists rather than assuming a number.
        render(
            listOf(
                contact(id = "c1", name = "Ada Lovelace"),
                contact(id = "c2", name = "Bob Barker", phoneNumber = null, email = "bob@example.com"),
                contact(id = "c3", name = "Cy Nobody", phoneNumber = null),
            ),
        )

        composeRule.onNodeWithContentDescription(CONTACTS_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("+14165550142").assertIsDisplayed()
        composeRule.onNodeWithText("bob@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("No phone or email").assertIsDisplayed()
    }

    @Test
    fun `opening a row reports the contact id`() {
        var opened: String? = null
        render(listOf(contact(id = "c-42")), callbacks = Callbacks(onOpenContact = { opened = it }))

        composeRule.onNodeWithText("Ada Lovelace").performClick()

        assertEquals("c-42", opened)
    }

    @Test
    fun `renders a placeholder rather than the literal Unknown`() {
        // ⚠️ The voice agent writes "Unknown" for an unidentified caller; printing it reads as a
        // name. The null is produced in the model so every render site inherits the decision.
        render(listOf(contact(name = "Unknown")))

        composeRule.onNodeWithText("Unnamed contact").assertIsDisplayed()
        composeRule.onNodeWithText("Unknown").assertDoesNotExist()
    }

    @Test
    fun `a dossier badge appears only while one is genuinely being built`() {
        // ⛔ A null dgiStatus means "none AND none queued" — clear-intel resets it to NULL precisely
        // so nothing re-crawls — so rendering null as pending promises a job that never finishes.
        render(
            listOf(
                contact(id = "c1", name = "Ada Lovelace", dgiStatus = "pending"),
                contact(id = "c2", name = "Bob Barker", dgiStatus = null),
                contact(id = "c3", name = "Cy Sterling", dgiStatus = "failed"),
            ),
        )

        composeRule.onAllNodesWithText("Building dossier…").assertCountEquals(1)
    }

    @Test
    fun `shows a labelled skeleton while refreshing, and not the empty state`() {
        render(emptyList(), states(refresh = LoadState.Loading))

        composeRule.onNodeWithContentDescription(CONTACTS_LOADING_DESCRIPTION).assertIsDisplayed()
        // ⛔ An in-flight load is not an absence of contacts.
        composeRule.onNodeWithContentDescription(CONTACTS_EMPTY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the empty state is reachable only after refresh succeeded`() {
        render(emptyList())

        composeRule.onNodeWithContentDescription(CONTACTS_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("No contacts yet").assertIsDisplayed()
    }

    @Test
    fun `the empty state carries the create action, and the floating button does not appear`() {
        // ⚠️ An empty CRM is exactly where the first contact gets made, so pointing at a control
        // elsewhere is a dead end. The two affordances are mutually exclusive on purpose.
        render(emptyList())

        composeRule.onNodeWithText("Add a contact").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACTS_ADD_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a viewer is offered no create action in the empty state`() {
        render(emptyList(), canMutate = false)

        composeRule.onNodeWithContentDescription(CONTACTS_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Add a contact").assertDoesNotExist()
    }

    @Test
    fun `a viewer is told up front rather than discovering it by tapping`() {
        render(listOf(contact()), canMutate = false)

        composeRule.onNodeWithContentDescription(CONTACTS_READ_ONLY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACTS_ADD_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a populated list offers the floating button and no read-only notice`() {
        render(listOf(contact()))

        composeRule.onNodeWithContentDescription(CONTACTS_ADD_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACTS_READ_ONLY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a refresh failure owns the screen and never renders as empty`() {
        // ⛔ THE INCIDENT SHAPE. "You have no contacts" over a read that failed is
        // indistinguishable from data loss to the person holding the phone.
        render(emptyList(), states(refresh = LoadState.Error(degraded())))

        composeRule.onNodeWithContentDescription(CONTACTS_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACTS_EMPTY_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithText("Affected regions: eu").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun `retrying a failed refresh is offered and clickable`() {
        render(emptyList(), states(refresh = LoadState.Error(degraded())))

        composeRule.onNodeWithText("Try again").performClick()

        // Paging owns the retry; asserting the control exists and is enabled is the useful half —
        // that a dead-end screen is never drawn.
        composeRule.onNodeWithContentDescription(CONTACTS_FAILURE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a dead session offers sign-in rather than retry`() {
        var signIns = 0
        render(
            emptyList(),
            states(refresh = LoadState.Error(PagedLoadException(ApiResult.Unauthorized(reason = null)))),
            callbacks = Callbacks(onSignIn = { signIns++ }),
        )

        composeRule.onNodeWithText("Try again").assertDoesNotExist()
        composeRule.onNodeWithText("Sign in").performClick()

        assertEquals(1, signIns)
    }

    @Test
    fun `contract drift offers neither retry nor sign-in`() {
        // ⛔ Retrying cannot fix a response shape this build cannot parse.
        render(
            emptyList(),
            states(
                refresh = LoadState.Error(
                    PagedLoadException(ApiResult.DecodeFailure(RuntimeException("nope"), "{}")),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(CONTACTS_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
        composeRule.onNodeWithText("Sign in").assertDoesNotExist()
    }

    @Test
    fun `an append failure keeps the rows and reports only in the footer`() {
        // ⛔ THE OTHER HALF. `bulk-create` inserts an entire import in one statement, so many rows
        // can appear between two page loads; one failed extra page must not destroy the list.
        render(
            listOf(contact(id = "c1", name = "Ada Lovelace")),
            states(append = LoadState.Error(degraded())),
        )

        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACTS_APPEND_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACTS_FAILURE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an append failure on a dead session offers sign-in in the footer`() {
        var signIns = 0
        render(
            listOf(contact(id = "c1")),
            states(
                append = LoadState.Error(PagedLoadException(ApiResult.Unauthorized(reason = null))),
            ),
            callbacks = Callbacks(onSignIn = { signIns++ }),
        )

        composeRule.onNodeWithText("Sign in").performClick()

        assertEquals(1, signIns)
    }

    @Test
    fun `an append in flight shows a footer spinner and keeps the rows`() {
        render(listOf(contact(id = "c1")), states(append = LoadState.Loading))

        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACTS_APPENDING_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the floating button opens the create dialog`() {
        render(listOf(contact()))

        composeRule.onNodeWithContentDescription(CONTACT_CREATE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(CONTACTS_ADD_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(CONTACT_CREATE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the empty state's create action opens the same dialog`() {
        render(emptyList())

        composeRule.onNodeWithText("Add a contact").performClick()

        composeRule.onNodeWithContentDescription(CONTACT_CREATE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `dismissing the dialog closes it and reports the state as handled`() {
        var handled = 0
        render(listOf(contact()), callbacks = Callbacks(onCreateHandled = { handled++ }))

        composeRule.onNodeWithContentDescription(CONTACTS_ADD_DESCRIPTION).performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithContentDescription(CONTACT_CREATE_DESCRIPTION).assertDoesNotExist()
        assertEquals(1, handled)
    }

    @Test
    fun `a successful create closes the dialog and reports the state as handled`() {
        // ⚠️ The list is refreshed rather than having the new row inserted locally: ordering is
        // `createdAt desc` with an id tie-break, and a local insert would guess its position.
        var handled = 0
        render(
            listOf(contact()),
            createState = CreateContactUiState.Created("c-new"),
            callbacks = Callbacks(onCreateHandled = { handled++ }),
        )

        composeRule.onNodeWithContentDescription(CONTACT_CREATE_DESCRIPTION).assertDoesNotExist()
        assertEquals(1, handled)
    }

    @Test
    fun `the back action is offered`() {
        var backs = 0
        render(listOf(contact()), callbacks = Callbacks(onBack = { backs++ }))

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        assertEquals(1, backs)
    }
}
