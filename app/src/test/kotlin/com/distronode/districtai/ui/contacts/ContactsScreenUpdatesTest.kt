package com.distronode.districtai.ui.contacts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.ContactsRepository
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactListResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.distronode.districtai.ui.MainLooperDrain
import org.junit.rules.RuleChain
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * The contact list while it is ALIVE: inputs that change under a list already on screen, a create
 * that lands while its dialog is open, and the model that feeds the list in production.
 *
 * ⚠️ A REAL `Pager` HERE, NOT `PagingData.from`. Two of these tests are about the list being READ
 * (a create must re-read it, and placeholder slots come only from a source), and a static
 * `PagingData` has no source to count or to ask for placeholders.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ContactsScreenUpdatesTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private fun row(id: String, name: String) = Contact(
        id = id,
        workspaceId = "ws-1",
        name = name,
        phoneNumber = "+14165550142",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    /** One page, every time, and a count of how many times it was asked for. */
    private class CountingSource(
        private val rows: List<Contact>,
        private val placeholdersAfter: Int,
        private val loads: IntArray,
    ) : PagingSource<Int, Contact>() {
        override fun getRefreshKey(state: PagingState<Int, Contact>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Contact> {
            loads[0] += 1
            return LoadResult.Page(
                data = rows,
                prevKey = null,
                nextKey = null,
                itemsBefore = 0,
                itemsAfter = placeholdersAfter,
            )
        }
    }

    private fun pager(rows: List<Contact>, loads: IntArray, placeholdersAfter: Int = 0) = Pager(
        config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = placeholdersAfter > 0),
        pagingSourceFactory = { CountingSource(rows, placeholdersAfter, loads) },
    ).flow

    @Test
    fun `a create that lands while the dialog is open closes it, re-reads the list and is handled once`() {
        val loads = intArrayOf(0)
        val flow = pager(listOf(row("c1", "Ada Lovelace")), loads)
        var createState by mutableStateOf<CreateContactUiState>(CreateContactUiState.Idle)
        var handled = 0
        composeRule.setContent {
            DistrictTheme {
                ContactsScreen(
                    contacts = flow.collectAsLazyPagingItems(),
                    canMutate = true,
                    onOpenContact = {},
                    onSignIn = {},
                    createState = createState,
                    onCreate = { _, _, _ -> },
                    onCreateHandled = { handled++ },
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        val firstRead = loads[0]
        composeRule.onNodeWithContentDescription(CONTACTS_ADD_DESCRIPTION).performClick()

        // Saving is not done: the dialog stays up and nothing is reported yet.
        createState = CreateContactUiState.Saving
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(CONTACT_CREATE_DESCRIPTION).assertIsDisplayed()
        assertEquals(0, handled)

        createState = CreateContactUiState.Created("c-new")
        composeRule.waitForIdle()

        // One re-read: the list the server orders, rather than a row guessed into place locally.
        assertEquals(firstRead + 1, loads[0])

        composeRule.onNodeWithContentDescription(CONTACT_CREATE_DESCRIPTION).assertDoesNotExist()
        assertEquals(1, handled)
    }

    @Test
    fun `losing the right to edit takes the add button away and says so, and regaining it restores both`() {
        val loads = intArrayOf(0)
        val flow = pager(listOf(row("c1", "Ada Lovelace")), loads)
        var canMutate by mutableStateOf(true)
        composeRule.setContent {
            DistrictTheme {
                ContactsScreen(
                    contacts = flow.collectAsLazyPagingItems(),
                    canMutate = canMutate,
                    onOpenContact = {},
                    onSignIn = {},
                    createState = CreateContactUiState.Idle,
                    onCreate = { _, _, _ -> },
                    onCreateHandled = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(CONTACTS_ADD_DESCRIPTION).assertIsDisplayed()

        canMutate = false
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(CONTACTS_ADD_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(CONTACTS_READ_ONLY_DESCRIPTION).assertIsDisplayed()

        canMutate = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(CONTACTS_ADD_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACTS_READ_ONLY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a callback replaced while the list is up is the one a tap reaches`() {
        val loads = intArrayOf(0)
        val flow = pager(listOf(row("c-42", "Ada Lovelace")), loads)
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        var onOpen by mutableStateOf<(String) -> Unit>({ first += it })
        composeRule.setContent {
            DistrictTheme {
                ContactsScreen(
                    contacts = flow.collectAsLazyPagingItems(),
                    canMutate = true,
                    onOpenContact = onOpen,
                    onSignIn = {},
                    createState = CreateContactUiState.Idle,
                    onCreate = { _, _, _ -> },
                    onCreateHandled = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()

        onOpen = { second += it }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ada Lovelace").performClick()

        assertEquals(emptyList<String>(), first)
        assertEquals(listOf("c-42"), second)
    }

    @Test
    fun `a list replaced by another shows the new rows, and a replaced handler is the one a create reaches`() {
        val loads = intArrayOf(0)
        var flow by mutableStateOf(pager(listOf(row("c1", "Ada Lovelace")), loads))
        var createState by mutableStateOf<CreateContactUiState>(CreateContactUiState.Idle)
        val firstHandled = mutableListOf<Unit>()
        val secondHandled = mutableListOf<Unit>()
        var onHandled by mutableStateOf<() -> Unit>({ firstHandled += Unit })
        composeRule.setContent {
            DistrictTheme {
                ContactsScreen(
                    contacts = flow.collectAsLazyPagingItems(),
                    canMutate = true,
                    onOpenContact = {},
                    onSignIn = {},
                    createState = createState,
                    onCreate = { _, _, _ -> },
                    onCreateHandled = onHandled,
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()

        flow = pager(listOf(row("c2", "Grace Hopper")), loads)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Grace Hopper").assertIsDisplayed()
        composeRule.onNodeWithText("Ada Lovelace").assertDoesNotExist()

        onHandled = { secondHandled += Unit }
        createState = CreateContactUiState.Created("c-new")
        composeRule.waitForIdle()

        assertEquals(0, firstHandled.size)
        assertEquals(1, secondHandled.size)
    }

    @Test
    fun `placeholder slots from a source that counts ahead draw nothing, and the loaded row still does`() {
        // ⚠️ The production source disables placeholders, but the screen takes any paged list, and a
        // slot with no item yet must not crash it or draw a blank row.
        val loads = intArrayOf(0)
        val flow = pager(listOf(row("c1", "Ada Lovelace")), loads, placeholdersAfter = 3)
        composeRule.setContent {
            DistrictTheme {
                ContactsScreen(
                    contacts = flow.collectAsLazyPagingItems(),
                    canMutate = true,
                    onOpenContact = {},
                    onSignIn = {},
                    createState = CreateContactUiState.Idle,
                    onCreate = { _, _, _ -> },
                    onCreateHandled = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithText("Unnamed contact").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(CONTACTS_EMPTY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the model built by its factory lists the repository's first page`() {
        val api = FakeDistrictApi().apply {
            contactsResult = ApiResult.Success(
                ContactListResponse(
                    success = true,
                    contacts = listOf(row("c1", "Ada Lovelace"), row("c2", "Grace Hopper")),
                    total = 2,
                    limit = 75,
                    offset = 0,
                ),
            )
        }
        composeRule.setContent {
            DistrictTheme {
                val model: ContactsViewModel = viewModel(
                    factory = ContactsViewModel.factory(ContactsRepository(api), "ws-1", WorkspaceRole.VIEWER),
                )
                ContactsScreen(
                    contacts = model.contacts.collectAsLazyPagingItems(),
                    canMutate = model.canMutate,
                    onOpenContact = {},
                    onSignIn = {},
                    createState = CreateContactUiState.Idle,
                    onCreate = { _, _, _ -> },
                    onCreateHandled = {},
                    onBack = {},
                )
            }
        }

        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithText("Grace Hopper").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        // The role travelled through the factory: a viewer is told the list is read-only.
        composeRule.onNodeWithContentDescription(CONTACTS_READ_ONLY_DESCRIPTION).assertIsDisplayed()
    }

    private companion object {
        const val PAGE_SIZE = 10
        const val WAIT_MS = 5_000L
    }
}
