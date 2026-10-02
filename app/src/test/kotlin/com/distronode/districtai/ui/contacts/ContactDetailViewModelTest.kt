package com.distronode.districtai.ui.contacts

import com.distronode.districtai.R
import com.distronode.districtai.core.data.ContactsRepository
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactDetailResponse
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The first mutating surface in the app.
 *
 * ⛔ THE ROLE GATE IS THE POINT OF THIS FILE. All three contacts mutations exclude `viewer`
 * server-side, so a viewer tapping an edit control gets a 403 they can do nothing about. These tests
 * assert the app does not even SEND those requests — while remembering that the gate is an affordance,
 * not the security boundary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun contact(name: String = "Ada") = Contact(
        id = "c1",
        workspaceId = "ws-1",
        name = name,
        phoneNumber = "+14165550142",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private fun api(contact: Contact = contact()) = TestDistrictApi().apply {
        contactResult = ApiResult.Success(ContactDetailResponse(success = true, contact = contact))
    }

    private fun viewModel(
        api: TestDistrictApi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = ContactDetailViewModel(
        ContactsRepository(api),
        workspaceId = "ws-1",
        contactId = "c1",
        role = role,
    )

    // ── Loading ──────────────────────────────────────────────────────────────

    @Test
    fun `fetches the contact by id`() = runTest(dispatcher) {
        // ⚠️ Fetched rather than carried from the list, so the screen survives process death.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals("c1", (vm.state.value as ContactDetailUiState.Content).contact.id)
    }

    @Test
    fun `a missing contact is reported without implying the id was wrong`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            contactResult = ApiResult.NotFound("Contact document c1 not found.")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = vm.state.value as ContactDetailUiState.Failed
        assertEquals("Contact document c1 not found.", state.failure.message.literalOrNull)
        assertFalse(state.failure.retryable)
    }

    @Test
    fun `a success carrying no contact is a malformed response, not an empty state`() = runTest(dispatcher) {
        // ⛔ Absence is a 404. A 2xx with no contact is a server bug and must not be dressed as an
        // ordinary empty screen.
        val api = TestDistrictApi().apply {
            contactResult = ApiResult.Success(ContactDetailResponse(success = true))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = vm.state.value as ContactDetailUiState.Failed
        assertEquals(R.string.failure_unexpected_response, state.failure.message.resourceIdOrNull)
    }

    // ── Role gating ──────────────────────────────────────────────────────────

    @Test
    fun `a viewer is not offered mutations and never sends one`() = runTest(dispatcher) {
        // ⛔ Both halves matter. Hiding the control is the affordance; refusing to send is what stops a
        // stale composition or a programmatic call from earning a 403.
        val api = api()
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(vm.canMutate)

        vm.rename("New Name")
        vm.delete { }
        advanceUntilIdle()

        assertTrue("a viewer must send no mutation", api.mutations.isEmpty())
    }

    @Test
    fun `an unparsed role is treated as no privileges`() = runTest(dispatcher) {
        // Fails closed: an unrecognised role string offers nothing rather than defaulting to permissive.
        val api = api()
        val vm = viewModel(api, role = null)
        advanceUntilIdle()

        assertFalse(vm.canMutate)
        vm.rename("New Name")
        advanceUntilIdle()
        assertTrue(api.mutations.isEmpty())
    }

    @Test
    fun `a client may mutate`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api, role = WorkspaceRole.CLIENT)
        advanceUntilIdle()

        assertTrue(vm.canMutate)
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    @Test
    fun `renames and re-reads rather than patching locally`() = runTest(dispatcher) {
        // ⚠️ Re-reads on success because the server may normalise the value; a local edit that disagrees
        // with the list is worse than one extra round trip.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.rename("Ada Lovelace")
        advanceUntilIdle()

        assertEquals(listOf("update:c1"), api.mutations)
        assertEquals("two reads: the initial load and the re-read", 2, api.contactRequestCount)
    }

    @Test
    fun `a rename whose re-read fails keeps the contact on screen and says so`() = runTest(dispatcher) {
        // ⚠️ THE RENAME LANDED. Before the fix the re-read went through load(), so a transient read
        // failure replaced the contact with the full-screen failure state.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        api.contactResult = ApiResult.NetworkFailure(java.io.IOException())
        vm.rename("Ada Lovelace")
        advanceUntilIdle()

        val state = vm.state.value as ContactDetailUiState.Content
        assertEquals(listOf("update:c1"), api.mutations)
        assertTrue(state.mutationFailure != null)
        assertFalse(state.saving)
    }

    @Test
    fun `a rename sends the whole loaded contact back with only the name changed`() = runTest(dispatcher) {
        // ⛔ `contacts/update` IS A WHOLESALE REPLACE. A name-only body cleared both addresses and
        // answered 400 "A contact needs a phone number or an email address", so a rename from this
        // screen could never succeed. Every column the route writes must ride along unchanged.
        val loaded = contact("Ada").copy(
            email = "ada@contract.test",
            socialHandles = JsonObject(mapOf("linkedin" to JsonPrimitive("in/ada"))),
            latestContextSummary = "Asked about Thursday.",
            budget = "5000",
            timeline = "Q4",
            website = "https://ada.test",
        )
        val api = api(loaded)
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.rename("Ada Lovelace")
        advanceUntilIdle()

        val sent = api.contactUpdates.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals("c1", sent.contactId)
        assertEquals("Ada Lovelace", sent.name)
        assertEquals("+14165550142", sent.phoneNumber)
        assertEquals("ada@contract.test", sent.email)
        assertEquals("in/ada", sent.linkedin)
        assertEquals("Asked about Thursday.", sent.contextSummary)
        assertEquals("5000", sent.budget)
        assertEquals("Q4", sent.timeline)
        assertEquals("https://ada.test", sent.website)
    }

    @Test
    fun `ignores a blank or unchanged rename`() = runTest(dispatcher) {
        // Neither is an edit, and a blank name would be rejected server-side as a validation error.
        val api = api(contact("Ada"))
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.rename("   ")
        vm.rename("Ada")
        vm.rename("  Ada  ")
        advanceUntilIdle()

        assertTrue("no request should be sent", api.mutations.isEmpty())
    }

    @Test
    fun `a failed rename keeps the contact on screen`() = runTest(dispatcher) {
        // ⛔ The contact is still perfectly good; only the edit failed. Blanking the screen would lose
        // data the user was looking at.
        val api = api().apply { mutationResult = ApiResult.HttpFailure(500, "Boom") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.rename("Ada Lovelace")
        advanceUntilIdle()

        val state = vm.state.value as ContactDetailUiState.Content
        assertEquals("c1", state.contact.id)
        assertFalse("no longer saving", state.saving)
        // ⛔ NOT "Boom". A 5xx body is not showable — these routes return the raw exception message
        // on an unhandled error, so it can be a Postgres or Prisma fragment. See FailureTextTest.
        assertEquals(R.string.failure_server, state.mutationFailure?.message?.resourceIdOrNull)
    }

    @Test
    fun `a mutation failure can be dismissed without reloading`() = runTest(dispatcher) {
        val api = api().apply { mutationResult = ApiResult.HttpFailure(500, "Boom") }
        val vm = viewModel(api)
        advanceUntilIdle()
        vm.rename("Ada Lovelace")
        advanceUntilIdle()

        vm.clearMutationFailure()

        val state = vm.state.value as ContactDetailUiState.Content
        assertNull(state.mutationFailure)
        assertEquals("dismissing must not refetch", 1, api.contactRequestCount)
    }

    @Test
    fun `does not fire a second mutation while one is in flight`() = runTest(dispatcher) {
        // Guards a double tap: two renames racing would make the final value depend on completion order.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.rename("First")
        vm.rename("Second")

        advanceUntilIdle()
        assertEquals(listOf("update:c1"), api.mutations)
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    @Test
    fun `deletes and notifies the caller so it can navigate away`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        var deleted = false
        vm.delete { deleted = true }
        advanceUntilIdle()

        assertEquals(listOf("delete:c1"), api.mutations)
        assertTrue("the screen must be told, or it shows a row that no longer exists", deleted)
    }

    @Test
    fun `a 404 on delete counts as deleted`() = runTest(dispatcher) {
        // ⚠️ "Already gone" is the outcome the user asked for, not a failure they can act on.
        val api = api().apply { mutationResult = ApiResult.NotFound("Contact not found") }
        val vm = viewModel(api)
        advanceUntilIdle()

        var deleted = false
        vm.delete { deleted = true }
        advanceUntilIdle()

        assertTrue(deleted)
    }

    @Test
    fun `a failed delete keeps the contact and reports why`() = runTest(dispatcher) {
        val api = api().apply { mutationResult = ApiResult.Forbidden("Forbidden") }
        val vm = viewModel(api)
        advanceUntilIdle()

        var deleted = false
        vm.delete { deleted = true }
        advanceUntilIdle()

        assertFalse("must not navigate away from a contact that still exists", deleted)
        val state = vm.state.value as ContactDetailUiState.Content
        assertEquals("Forbidden", state.mutationFailure?.message?.literalOrNull)
        assertFalse(state.mutationFailure!!.retryable)
    }
}
