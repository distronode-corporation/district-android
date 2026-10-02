package com.distronode.districtai.ui.contacts

import com.distronode.districtai.R
import com.distronode.districtai.core.data.ContactsRepository
import com.distronode.districtai.core.model.ContactMutationResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.resourceIdOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Creating a contact.
 *
 * ⛔ CONTACTS ARE EMAIL-FIRST, so the rule is "a name PLUS a phone OR an email" — not both. The
 * database allows any number of phone-less rows per workspace (Postgres treats NULLs as distinct in the
 * unique index), so demanding a number would refuse legitimate input. Note `bulk-create` disagrees and
 * requires a phone per row; that server-side inconsistency is deliberately not smoothed over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        api: TestDistrictApi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = ContactsViewModel(ContactsRepository(api), workspaceId = "ws-1", role = role)

    private fun api(id: String = "new-contact") = TestDistrictApi().apply {
        mutationResult = ApiResult.Success(ContactMutationResponse(success = true, id = id))
    }

    // ── Role gating ──────────────────────────────────────────────────────────

    @Test
    fun `a viewer may not create and sends nothing`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)

        assertFalse(vm.canMutate)
        vm.create("Ada", "+14165550142", "")
        advanceUntilIdle()

        assertTrue("a viewer must send no mutation", api.mutations.isEmpty())
        assertEquals(CreateContactUiState.Idle, vm.createState.value)
    }

    @Test
    fun `an unparsed role may not create`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api, role = null)

        vm.create("Ada", "+14165550142", "")
        advanceUntilIdle()

        assertTrue(api.mutations.isEmpty())
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    fun `accepts a phone-only contact`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)

        vm.create("Ada", "+14165550142", "")
        advanceUntilIdle()

        assertEquals(listOf("create:Ada"), api.mutations)
    }

    @Test
    fun `accepts an email-only contact`() = runTest(dispatcher) {
        // ⛔ The email-first case. Refusing this would reject a legal, common contact.
        val api = api()
        val vm = viewModel(api)

        vm.create("Ada", "", "ada@engines.test")
        advanceUntilIdle()

        assertEquals(listOf("create:Ada"), api.mutations)
    }

    @Test
    fun `refuses a contact with neither a phone nor an email`() = runTest(dispatcher) {
        // The server would reject it too; refusing locally saves a round trip and an opaque error.
        val api = api()
        val vm = viewModel(api)

        vm.create("Ada", "", "")
        advanceUntilIdle()

        assertTrue(api.mutations.isEmpty())
    }

    @Test
    fun `refuses a blank name`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)

        vm.create("   ", "+14165550142", "")
        advanceUntilIdle()

        assertTrue(api.mutations.isEmpty())
    }

    @Test
    fun `trims whitespace before deciding and before sending`() = runTest(dispatcher) {
        // A field containing only spaces is absent, not present-and-empty — the server distinguishes an
        // omitted key from an empty string, and an empty phone would fail its format check.
        val api = api()
        val vm = viewModel(api)

        vm.create("  Ada  ", "   ", "  ada@engines.test  ")
        advanceUntilIdle()

        assertEquals(listOf("create:Ada"), api.mutations)
    }

    // ── Outcomes ─────────────────────────────────────────────────────────────

    @Test
    fun `reports the new id so the caller can refresh and open it`() = runTest(dispatcher) {
        val api = api(id = "contact-99")
        val vm = viewModel(api)

        vm.create("Ada", "+14165550142", "")
        advanceUntilIdle()

        assertEquals(CreateContactUiState.Created("contact-99"), vm.createState.value)
    }

    @Test
    fun `a duplicate is reported as a conflict, not a server fault`() = runTest(dispatcher) {
        // ⚠️ The database enforces one contact per phone and per lowercased email per workspace, so a
        // 409 means "already exists". Wording it as a failure of ours would be wrong and unactionable.
        val api = api().apply {
            mutationResult = ApiResult.HttpFailure(409, "A contact with that phone number already exists.")
        }
        val vm = viewModel(api)

        vm.create("Ada", "+14165550142", "")
        advanceUntilIdle()

        val state = vm.createState.value as CreateContactUiState.Failed
        assertTrue(state.failure.message.literalOrNull!!.contains("already exists"))
        assertTrue("a conflict is worth retrying after editing", state.failure.retryable)
    }

    @Test
    fun `a success with no id is a malformed response, not a silent success`() = runTest(dispatcher) {
        // ⛔ create is documented to return the new id. A 2xx without one is a server bug, and treating
        // it as success would leave the UI unable to refresh to the right place.
        val api = api().apply {
            mutationResult = ApiResult.Success(ContactMutationResponse(success = true, id = null))
        }
        val vm = viewModel(api)

        vm.create("Ada", "+14165550142", "")
        advanceUntilIdle()

        val state = vm.createState.value as CreateContactUiState.Failed
        assertEquals(R.string.failure_unexpected_response, state.failure.message.resourceIdOrNull)
    }

    @Test
    fun `does not fire a second create while one is in flight`() = runTest(dispatcher) {
        // Guards a double tap, which would otherwise attempt two rows and 409 the second.
        val api = api()
        val vm = viewModel(api)

        vm.create("Ada", "+14165550142", "")
        vm.create("Ada", "+14165550142", "")
        advanceUntilIdle()

        assertEquals(1, api.mutations.size)
    }

    @Test
    fun `clearing the state returns to idle`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        vm.create("Ada", "+14165550142", "")
        advanceUntilIdle()

        vm.clearCreateState()

        assertEquals(CreateContactUiState.Idle, vm.createState.value)
    }
}
