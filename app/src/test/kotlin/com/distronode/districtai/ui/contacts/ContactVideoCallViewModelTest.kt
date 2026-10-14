package com.distronode.districtai.ui.contacts

import com.distronode.districtai.R
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.MeetingsRepository
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.RoomTokenResponse
import com.distronode.districtai.core.model.SendMessageResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.FailureText
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
 * ⛔ THE SEND IS METERED AND CANNOT BE RECALLED, so most of this file is about what must NOT happen:
 * a second send for a double tap, a send without a server-minted link, a send for a viewer, a send
 * after a failed token, and a "try again" after a failed send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactVideoCallViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val guestPath = "/meet/meet_ws-1_call-1786973400?e=1787016600&s=sig"

    private fun api(guestPath: String? = this.guestPath) = FakeDistrictApi().apply {
        roomTokenResult = ApiResult.Success(
            RoomTokenResponse(success = true, token = "jwt", url = "wss://livekit.test", guestPath = guestPath),
        )
    }

    private fun contact(phone: String? = "+14165550142", email: String? = "ada@example.com") =
        Contact(
            id = "c1",
            workspaceId = "ws-1",
            name = "Ada",
            phoneNumber = phone,
            email = email,
            createdAt = "2026-10-13T14:30:00.000Z",
        )

    private fun viewModel(
        api: FakeDistrictApi = api(),
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
        workspaceId: String = "ws-1",
    ) = ContactVideoCallViewModel(
        meetings = MeetingsRepository(api),
        inbox = InboxRepository(api),
        workspaceId = workspaceId,
        role = role,
        copy = ContactVideoInvitationCopy(webOrigin = "https://www.distronode.test/", emailSubject = "Subject") {
            "Join: $it"
        },
        now = { 1_786_973_400_123L },
    )

    private fun failure(message: Int) =
        ContactVideoCallPhase.Failed(FailureText(UiText.Resource(message), retryable = false))

    @Test
    fun `a phone number gets an SMS of the server's guest link and the room is reported`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)

        vm.start(contact())
        assertEquals(ContactVideoCallPhase.Starting, vm.phase.value)
        advanceUntilIdle()

        assertEquals(ContactVideoCallPhase.Sent("meet_ws-1_call-1786973400"), vm.phase.value)
        assertEquals(listOf("meet_ws-1_call-1786973400"), api.roomTokenRequests.map { it.roomName })
        val send = api.sends.single()
        assertEquals("ws-1", send.workspaceId)
        assertEquals("+14165550142", send.to)
        assertEquals("sms", send.channel)
        // The link is the origin plus the server's path verbatim, never rebuilt.
        assertEquals("Join: https://www.distronode.test$guestPath", send.body)
        // ⚠️ No subject on SMS.
        assertNull(send.subject)
    }

    @Test
    fun `an email-only contact gets an email with a subject`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)

        vm.start(contact(phone = "  "))
        advanceUntilIdle()

        val send = api.sends.single()
        assertEquals("ada@example.com", send.to)
        assertEquals("email", send.channel)
        assertEquals("Subject", send.subject)
    }

    @Test
    fun `the target prefers SMS and trims, and is null without either column`() {
        assertEquals(ReplyTarget("+1", "sms"), ContactVideoCallViewModel.target(contact(phone = " +1 ")))
        assertEquals(ReplyTarget("a@b.c", "email"), ContactVideoCallViewModel.target(contact(null, "a@b.c")))
        assertNull(ContactVideoCallViewModel.target(contact(phone = null, email = " ")))
        assertNull(ContactVideoCallViewModel.target(contact(phone = null, email = null)))
    }

    @Test
    fun `a double tap is one token and one message`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)

        vm.start(contact())
        vm.start(contact())
        advanceUntilIdle()
        // And a tap after the send does not start a second attempt either.
        vm.start(contact())
        advanceUntilIdle()

        assertEquals(1, api.roomTokenRequests.size)
        assertEquals(1, api.sends.size)
    }

    @Test
    fun `a viewer is never offered the action and a start does nothing`() = runTest(dispatcher) {
        val api = api()
        for (role in listOf(WorkspaceRole.VIEWER, null)) {
            val vm = viewModel(api, role = role)
            assertFalse(vm.canStart)
            vm.start(contact())
            advanceUntilIdle()
            assertEquals(ContactVideoCallPhase.Idle, vm.phase.value)
        }
        assertTrue(api.roomTokenRequests.isEmpty())
        assertTrue(api.sends.isEmpty())
        assertTrue(viewModel(api, role = WorkspaceRole.AGENCY).canStart)
    }

    @Test
    fun `a contact with no address fails before any request`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)

        vm.start(contact(phone = null, email = null))
        advanceUntilIdle()

        assertEquals(failure(R.string.contact_video_no_address), vm.phase.value)
        assertTrue(api.roomTokenRequests.isEmpty())
    }

    @Test
    fun `a blank workspace id is refused before any request`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api, workspaceId = " ")

        vm.start(contact())
        advanceUntilIdle()

        assertEquals(failure(R.string.contact_video_could_not_start), vm.phase.value)
        assertTrue(api.roomTokenRequests.isEmpty())
    }

    @Test
    fun `a refused token sends nothing and shows the server's coded sentence`() = runTest(dispatcher) {
        val api = api().apply { roomTokenResult = ApiResult.Forbidden("No access", code = "FORBIDDEN") }
        val vm = viewModel(api)

        vm.start(contact())
        advanceUntilIdle()

        assertEquals(
            ContactVideoCallPhase.Failed(FailureText(UiText.Server("No access", "FORBIDDEN"), retryable = false)),
            vm.phase.value,
        )
        assertTrue(api.sends.isEmpty())
    }

    @Test
    fun `a token without a guest link is a refusal, not a bare-room invite`() = runTest(dispatcher) {
        val api = api(guestPath = null)
        val vm = viewModel(api)

        vm.start(contact())
        advanceUntilIdle()

        assertEquals(failure(R.string.contact_video_no_invite), vm.phase.value)
        assertTrue(api.sends.isEmpty())
    }

    @Test
    fun `a lost connection on the send says it was not sent and offers no retry`() = runTest(dispatcher) {
        val api = api().apply { sendResult = ApiResult.NetworkFailure(IOException("timeout")) }
        val vm = viewModel(api)

        vm.start(contact())
        advanceUntilIdle()

        assertEquals(failure(R.string.contact_video_send_failed), vm.phase.value)
    }

    @Test
    fun `a refused send keeps the server's sentence and is never retryable`() = runTest(dispatcher) {
        val api = api().apply {
            sendResult = ApiResult.RateLimited("Too many messages", code = "RATE_LIMITED")
        }
        val vm = viewModel(api)

        vm.start(contact())
        advanceUntilIdle()

        val failed = vm.phase.value as ContactVideoCallPhase.Failed
        assertEquals(UiText.Server("Too many messages", "RATE_LIMITED"), failed.failure.message)
        assertFalse(failed.failure.retryable)
        assertEquals(1, api.sends.size)
    }

    @Test
    fun `a 200 refusal of the send is a failure, not a sent invitation`() = runTest(dispatcher) {
        val api = api().apply { sendResult = ApiResult.Success(SendMessageResponse(success = false, error = "No")) }
        val vm = viewModel(api)

        vm.start(contact())
        advanceUntilIdle()

        assertTrue(vm.phase.value is ContactVideoCallPhase.Failed)
    }

    @Test
    fun `dismissing a failure brings the button back`() = runTest(dispatcher) {
        val vm = viewModel(api(guestPath = null))
        vm.start(contact())
        advanceUntilIdle()

        vm.reset()

        assertEquals(ContactVideoCallPhase.Idle, vm.phase.value)
    }
}
