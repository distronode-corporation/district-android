package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.data.ContactsRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactListResponse
import com.distronode.districtai.core.model.MessageThreadResponse
import com.distronode.districtai.core.model.MessageThreadTarget
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.SendMessageResponse
import com.distronode.districtai.core.model.SentMessage
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeInboxExtrasApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.UiText
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Starting a conversation: who, how, what, then where it landed.
 *
 * ⛔ THE ASSERTIONS THAT MATTER MOST ARE ABOUT MONEY AND DUPLICATES. A second tap must not become a
 * second send, a viewer must never fire one, and a send whose thread could not be looked up must be
 * reported as SENT: offering it again would be a second charge and a duplicate for the customer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    /** (limit, offset) per contacts read, so "one window of 50" is an assertion. */
    private val contactReads = mutableListOf<Pair<Int, Int>>()

    private val api = object : FakeDistrictApi() {
        override suspend fun contacts(workspaceId: String, limit: Int, offset: Int): ApiResult<ContactListResponse> {
            contactReads += limit to offset
            return contactsResult
        }
    }

    private val extras = FakeInboxExtrasApi()

    private fun contact(
        id: String,
        name: String,
        phone: String? = null,
        email: String? = null,
    ) = Contact(
        id = id,
        workspaceId = "ws-1",
        name = name,
        phoneNumber = phone,
        email = email,
        createdAt = "2026-10-01"
    )

    private val ada = contact("c1", "Ada Lovelace", phone = "+14165550100", email = "ada@example.com")
    private val unknown = contact("c2", "Unknown", phone = "+14165550199")
    private val grace = contact("c3", "Grace Hopper", email = "grace@example.com")
    private val nobody = contact("c4", "No Address", phone = " ", email = "")

    private fun viewModel(role: WorkspaceRole? = WorkspaceRole.CLIENT) = ComposeViewModel(
        ContactsRepository(api),
        InboxRepository(api),
        MessageSearchRepository(extras),
        "ws-1",
        role,
    )

    private fun withContacts(vararg contacts: Contact) {
        api.contactsResult = ApiResult.Success(ContactListResponse(success = true, contacts = contacts.toList()))
    }

    private fun sentWithId(id: String) {
        api.sendResult = ApiResult.Success(SendMessageResponse(success = true, message = SentMessage(id = id)))
    }

    private val landing = MessageThreadTarget(
        threadKey = "contact:c1",
        contactId = "c1",
        counterpart = "+14165550100",
        channel = "sms",
    )

    // ── Role ─────────────────────────────────────────────────────────────────

    @Test
    fun `a viewer is never offered the form and never sends`() = runTest(dispatcher) {
        val vm = viewModel(WorkspaceRole.VIEWER)

        vm.open()
        advanceUntilIdle()
        assertFalse(vm.canSend)
        assertFalse(vm.state.value.open)
        assertTrue(contactReads.isEmpty())

        vm.choose(ada)
        vm.onBodyChange("hello")
        vm.send()
        advanceUntilIdle()
        assertTrue(api.sends.isEmpty())
    }

    @Test
    fun `a role that could not be established errs low`() {
        assertFalse(viewModel(role = null).canSend)
    }

    // ── Who ──────────────────────────────────────────────────────────────────

    @Test
    fun `opening reads one window of fifty newest contacts and shows it as it came`() = runTest(dispatcher) {
        withContacts(ada, unknown, grace)
        val vm = viewModel()

        vm.open()
        assertEquals(ComposeContacts.Loading, vm.state.value.contacts)
        advanceUntilIdle()

        assertTrue(vm.state.value.open)
        assertEquals(listOf(ComposeViewModel.CONTACT_WINDOW to 0), contactReads)
        assertEquals(50, ComposeViewModel.CONTACT_WINDOW)
        assertEquals(listOf(ada, unknown, grace), vm.state.value.matches)
    }

    @Test
    fun `the field matches name, number and address, case-insensitively`() = runTest(dispatcher) {
        withContacts(ada, unknown, grace)
        val vm = viewModel()
        vm.open()
        advanceUntilIdle()

        vm.onQueryChange("  LOVELACE ")
        assertEquals(listOf(ada), vm.state.value.matches)
        // ⚠️ "Unknown" is only findable by its number.
        vm.onQueryChange("0199")
        assertEquals(listOf(unknown), vm.state.value.matches)
        vm.onQueryChange("GRACE@")
        assertEquals(listOf(grace), vm.state.value.matches)
        vm.onQueryChange("zzz")
        assertTrue(vm.state.value.matches.isEmpty())
    }

    @Test
    fun `a failed contact read is a caption, and a typed address still works`() = runTest(dispatcher) {
        api.contactsResult = ApiResult.NetworkFailure(IOException("offline"))
        val vm = viewModel()
        vm.open()
        advanceUntilIdle()

        assertTrue(vm.state.value.contacts is ComposeContacts.Failed)
        assertTrue(vm.state.value.matches.isEmpty())

        vm.onQueryChange(" +14165550123 ")
        vm.useTypedAddress()
        assertEquals(ComposeRecipient.Typed("+14165550123"), vm.state.value.recipient)
        assertEquals("+14165550123", vm.state.value.recipientLabel)
    }

    @Test
    fun `a blank typed address is refused`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("   ")
        vm.useTypedAddress()
        assertNull(vm.state.value.recipient)
        assertNull(vm.state.value.recipientLabel)
    }

    // ── How ──────────────────────────────────────────────────────────────────

    @Test
    fun `a contact with both addresses offers SMS first, then email, from its own columns`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.choose(ada)

        assertEquals(
            listOf(ReplyTarget("+14165550100", "sms"), ReplyTarget("ada@example.com", "email")),
            vm.state.value.channelChoices,
        )
        assertEquals(ReplyTarget("+14165550100", "sms"), vm.state.value.target)
        assertFalse(vm.state.value.requiresSubject)
        assertEquals("Ada Lovelace", vm.state.value.recipientLabel)

        vm.selectChannel(1)
        assertEquals("email", vm.state.value.target?.channel)
        assertTrue(vm.state.value.requiresSubject)

        // ⚠️ Out of range is ignored rather than pointing past the end.
        vm.selectChannel(2)
        vm.selectChannel(-1)
        assertEquals(1, vm.state.value.selectedChannel)

        // ⚠️ A new recipient starts on its own first choice.
        vm.choose(grace)
        assertEquals(0, vm.state.value.selectedChannel)
        assertEquals(listOf(ReplyTarget("grace@example.com", "email")), vm.state.value.channelChoices)
    }

    @Test
    fun `a contact with no usable address cannot be sent to`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.choose(nobody)
        vm.onBodyChange("hello")

        assertTrue(vm.state.value.channelChoices.isEmpty())
        assertNull(vm.state.value.target)
        assertFalse(vm.state.value.submittable)

        vm.send()
        advanceUntilIdle()
        assertTrue(api.sends.isEmpty())
    }

    @Test
    fun `a typed address is email on an at sign and SMS otherwise`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("someone@example.com")
        vm.useTypedAddress()
        assertEquals(listOf(ReplyTarget("someone@example.com", "email")), vm.state.value.channelChoices)

        vm.clearRecipient()
        assertNull(vm.state.value.recipient)
        vm.onQueryChange("+447700900123")
        vm.useTypedAddress()
        assertEquals(listOf(ReplyTarget("+447700900123", "sms")), vm.state.value.channelChoices)
    }

    // ── What ─────────────────────────────────────────────────────────────────

    @Test
    fun `a blank body, or an email with a blank subject, cannot be sent`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.choose(grace)
        assertFalse(vm.state.value.submittable)

        vm.onBodyChange("   ")
        assertFalse(vm.state.value.submittable)

        vm.onBodyChange("Your quote")
        assertFalse(vm.state.value.submittable)

        vm.onSubjectChange(" ")
        assertFalse(vm.state.value.submittable)

        vm.onSubjectChange("Quote for the roof")
        assertTrue(vm.state.value.submittable)
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    @Test
    fun `an SMS goes without a subject and lands in the thread the server resolved`() = runTest(dispatcher) {
        sentWithId("msg-1")
        extras.threadResult = ApiResult.Success(MessageThreadResponse(success = true, thread = landing))
        val vm = viewModel()
        vm.choose(ada)
        // ⚠️ A subject typed while email was selected must not ride along on an SMS.
        vm.selectChannel(1)
        vm.onSubjectChange("leftover")
        vm.selectChannel(0)
        vm.onBodyChange("On our way")

        vm.send()
        assertTrue(vm.state.value.sending)
        advanceUntilIdle()

        val sent = api.sends.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals("+14165550100", sent.to)
        assertEquals("sms", sent.channel)
        assertEquals("On our way", sent.body)
        assertNull(sent.subject)
        assertEquals(listOf("ws-1" to "msg-1"), extras.threadRequests)
        assertEquals(ComposeSendState.Sent(landing), vm.state.value.send)
    }

    @Test
    fun `an email carries its subject`() = runTest(dispatcher) {
        sentWithId("msg-2")
        val vm = viewModel()
        vm.choose(grace)
        vm.onSubjectChange("Quote for the roof")
        vm.onBodyChange("Attached below.")

        vm.send()
        advanceUntilIdle()

        assertEquals("Quote for the roof", api.sends.single().subject)
        assertEquals("email", api.sends.single().channel)
    }

    @Test
    fun `a failed thread lookup is still a sent message`() = runTest(dispatcher) {
        sentWithId("msg-3")
        extras.threadResult = ApiResult.NetworkFailure(IOException("offline"))
        val vm = viewModel()
        vm.choose(ada)
        vm.onBodyChange("hi")

        vm.send()
        advanceUntilIdle()

        assertEquals(ComposeSendState.Sent(null), vm.state.value.send)
        assertEquals(1, api.sends.size)
    }

    @Test
    fun `a send that echoed no row id is sent, and nothing is looked up`() = runTest(dispatcher) {
        api.sendResult = ApiResult.Success(SendMessageResponse(success = true))
        val vm = viewModel()
        vm.choose(ada)
        vm.onBodyChange("hi")

        vm.send()
        advanceUntilIdle()

        assertEquals(ComposeSendState.Sent(null), vm.state.value.send)
        assertTrue(extras.threadRequests.isEmpty())
    }

    @Test
    fun `a second tap while sending sends nothing more`() = runTest(dispatcher) {
        sentWithId("msg-4")
        val vm = viewModel()
        vm.choose(ada)
        vm.onBodyChange("hi")

        vm.send()
        vm.send()
        assertFalse(vm.state.value.submittable)
        advanceUntilIdle()

        assertEquals(1, api.sends.size)
    }

    @Test
    fun `a refusal keeps the draft, says the server's sentence, and is cleared by a new recipient`() =
        runTest(dispatcher) {
            api.sendResult = ApiResult.Success(SendMessageResponse(success = false, error = "Sender not verified."))
            val vm = viewModel()
            vm.choose(ada)
            vm.onBodyChange("hi")

            vm.send()
            advanceUntilIdle()

            val failed = vm.state.value.send as ComposeSendState.Failed
            assertEquals("Sender not verified.", (failed.failure.message as UiText.Server).value)
            assertEquals("hi", vm.state.value.body)
            assertTrue(vm.state.value.submittable)
            // ⛔ NOT RETRIED: one request, and the next one is the operator's own tap.
            assertEquals(1, api.sends.size)

            vm.clearRecipient()
            assertEquals(ComposeSendState.Idle, vm.state.value.send)

            vm.choose(ada)
            vm.send()
            advanceUntilIdle()
            assertTrue(vm.state.value.send is ComposeSendState.Failed)
            vm.onQueryChange("+14165550123")
            vm.useTypedAddress()
            assertEquals(ComposeSendState.Idle, vm.state.value.send)
        }

    // ── Opening and closing ──────────────────────────────────────────────────

    @Test
    fun `closing is refused mid-send, and a reopen starts clean`() = runTest(dispatcher) {
        withContacts(ada)
        sentWithId("msg-5")
        val vm = viewModel()
        vm.open()
        advanceUntilIdle()
        vm.choose(ada)
        vm.onBodyChange("hi")

        vm.send()
        vm.dismiss()
        assertTrue(vm.state.value.open)
        advanceUntilIdle()

        vm.dismiss()
        assertEquals(ComposeUiState(), vm.state.value)

        vm.open()
        assertEquals(ComposeUiState(open = true), vm.state.value)
        advanceUntilIdle()
        assertEquals(2, contactReads.size)
    }

    @Test
    fun `closing a form that was never opened is harmless`() {
        val vm = viewModel()
        vm.dismiss()
        assertEquals(ComposeUiState(), vm.state.value)
    }

    @Test
    fun `closing before the contacts arrive drops their answer`() = runTest(dispatcher) {
        withContacts(ada)
        val vm = viewModel()
        vm.open()
        vm.dismiss()
        advanceUntilIdle()

        assertEquals(ComposeUiState(), vm.state.value)
    }

    @Test
    fun `the prebuilt actions reach the same methods`() = runTest(dispatcher) {
        withContacts(ada)
        sentWithId("msg-6")
        val vm = viewModel()
        val actions = vm.actions

        actions.open()
        advanceUntilIdle()
        actions.onQueryChange("+14165550123")
        actions.useTypedAddress()
        actions.clearRecipient()
        actions.choose(ada)
        actions.selectChannel(1)
        actions.onSubjectChange("Quote")
        actions.onBodyChange("Hello")
        actions.send()
        advanceUntilIdle()
        assertEquals("ada@example.com", api.sends.single().to)
        assertEquals("Quote", api.sends.single().subject)

        actions.dismiss()
        assertEquals(ComposeUiState(), vm.state.value)
    }

    @Test
    fun `the factory builds a view model for the workspace`() {
        val vm = ComposeViewModel.Factory(
            ContactsRepository(api),
            InboxRepository(api),
            MessageSearchRepository(extras),
            "ws-9",
            WorkspaceRole.AGENCY,
        ).create(ComposeViewModel::class.java)

        assertEquals("ws-9", vm.workspaceId)
        assertTrue(vm.canSend)
    }

    // ── Labels ───────────────────────────────────────────────────────────────

    @Test
    fun `a contact is labelled by name, then number, then address, then the raw name`() {
        assertEquals("Ada Lovelace", ada.label())
        assertEquals("+14165550199", unknown.label())
        assertEquals("x@example.com", contact("c5", "Unknown", phone = " ", email = "x@example.com").label())
        assertEquals("Unknown", contact("c6", "Unknown").label())
        assertEquals("y@example.com", contact("c7", " ", email = "y@example.com").label())
        assertEquals("Unknown", contact("c8", "Unknown", email = " ").label())
    }
}
