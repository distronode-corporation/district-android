package com.distronode.districtai.ui.contacts

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.MeetingsRepository
import com.distronode.districtai.core.model.CHANNEL_EMAIL
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.MeetRoomName
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.RoomTokenResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where one video-call attempt has got to. */
sealed interface ContactVideoCallPhase {
    data object Idle : ContactVideoCallPhase

    data object Starting : ContactVideoCallPhase

    /**
     * ⛔ CARRIES THE FULL `meet_` NAME SO THE SCREEN CAN NAVIGATE WITH IT, and navigation is the
     * LAST step. Opening the room first and sending afterwards would put the metered send on a
     * screen the operator has left, with nowhere to report that it did not go out.
     */
    data class Sent(val roomName: String) : ContactVideoCallPhase

    data class Failed(val failure: FailureText) : ContactVideoCallPhase
}

/**
 * What the invitation is made of: the origin its link is joined onto and its sentences.
 *
 * ⚠️ THE SENTENCES ARE RESOLVED BY THE CALLER because a ViewModel has no `Context`, and they are not
 * shown on this screen: they are SENT, so they must be text by the time they reach the request.
 *
 * @param webOrigin the origin a `guestPath` is joined onto, the same one `ActiveRoomViewModel` uses.
 */
class ContactVideoInvitationCopy(
    val webOrigin: String,
    val emailSubject: String,
    val invitation: (link: String) -> String,
)

/**
 * Starting a video room with one contact and telling them how to get in. Mirrors iOS
 * `ContactVideoCallModel`.
 *
 * ⛔ ITS OWN ViewModel, BECAUSE STARTING A ROOM IS NOT EDITING A CONTACT. [ContactDetailViewModel]'s
 * mutations all end in a re-read of the same row and share one `saving` flag; this one mints a
 * room, spends a METERED and unrecallable message, and then hands over a room to open. Folding it
 * in would put an irreversible send behind the flag that guards a rename.
 *
 * ⛔ THREE STEPS AND ONLY ONE OF THEM CAN BE TAKEN BACK. It mints a room name, asks `calls/token`
 * for a credential (which is what produces the shareable guest invite), and SENDS that invite
 * through `messages/send`: a metered SMS segment or Postmark email, capped at 30/min per
 * workspace. The room and the token cost nothing; the message is money and cannot be recalled. So
 * one deliberate tap is one attempt, and ⛔ NOTHING HERE RETRIES.
 *
 * ⛔ THE ROOM IS MINTED BY [MeetRoomName] AND BY NOTHING ELSE, so this cannot produce a billable
 * `video_` avatar name. An AI-avatar room and screen share are out of scope here, as on iOS.
 *
 * ⚠️ TWO TOKEN CALLS HAPPEN FOR ONE TAP AND THAT IS UNDERSTOOD RATHER THAN ACCIDENTAL. This one
 * exists to obtain the guest invite; `ActiveRoomViewModel` mints its own when the operator joins,
 * because a join needs a fresh LiveKit token. Only ONE invite ever leaves the device.
 *
 * @param now epoch milliseconds, for the room suffix. Injected so a test can pin the name.
 */
class ContactVideoCallViewModel(
    private val meetings: MeetingsRepository,
    private val inbox: InboxRepository,
    private val workspaceId: String,
    role: WorkspaceRole?,
    private val copy: ContactVideoInvitationCopy,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /**
     * Whether to OFFER the action at all.
     *
     * ⛔ BOTH ROUTES EXCLUDE `viewer`: `messages/send` refuses one outright, and `calls/token`
     * withholds `guestPath` from one, so a viewer would have no invitation to send even if the send
     * were allowed. Fails closed on an unparseable role, like every other gate here.
     */
    val canStart: Boolean = role.allowsMutation()

    private val _phase = MutableStateFlow<ContactVideoCallPhase>(ContactVideoCallPhase.Idle)
    val phase: StateFlow<ContactVideoCallPhase> = _phase.asStateFlow()

    /**
     * Mint a room, send its guest link to the contact, and report the room.
     *
     * ⛔ SINGLE-FLIGHT: [ContactVideoCallPhase.Starting] is set before anything suspends, so a
     * second tap dispatched before the button re-renders finds a non-idle phase and returns. A
     * double tap must not be two messages.
     */
    fun start(contact: Contact) {
        if (!canStart || _phase.value != ContactVideoCallPhase.Idle) return
        val target = target(contact)
        val room = MeetRoomName.of(workspaceId, "call-${now() / MILLIS_PER_SECOND}")
        _phase.value = when {
            target == null -> failed(R.string.contact_video_no_address)
            // Reachable only with a blank workspace id; refused rather than sent to the server as
            // a name it would answer with a 400 that reads as a server fault.
            room == null -> failed(R.string.contact_video_could_not_start)
            else -> ContactVideoCallPhase.Starting
        }
        if (target == null || room == null) return
        viewModelScope.launch {
            _phase.value = when (val token = meetings.roomToken(room)) {
                is ApiResult.Success -> invite(target, room, token.value)
                // ⚠️ NOTHING WAS SENT AND NOTHING WAS SPENT: a room nobody joined leaves no trace.
                is ApiResult.Failure -> ContactVideoCallPhase.Failed(token.toFailureText())
            }
        }
    }

    /** Clear a failure so the button comes back. */
    fun reset() {
        _phase.value = ContactVideoCallPhase.Idle
    }

    /**
     * Send the invite.
     *
     * ⛔ THE LINK IS THE SERVER'S `guestPath` JOINED ONTO THE ORIGIN, NEVER REBUILT: the signature
     * covers that exact encoding, so a hand-assembled link would look like a working invitation
     * until the guest could not join. ⛔ A MISSING `guestPath` IS A REFUSAL, not a bare-room invite
     * nobody unauthenticated could accept.
     */
    private suspend fun invite(
        target: ReplyTarget,
        room: String,
        credential: RoomTokenResponse,
    ): ContactVideoCallPhase {
        val path = credential.guestPath ?: return failed(R.string.contact_video_no_invite)
        val outcome = inbox.send(
            workspaceId = workspaceId,
            to = target.to,
            body = copy.invitation(copy.webOrigin.trimEnd('/') + path),
            channel = target.channel,
            // ⚠️ ONLY EMAIL TAKES ONE, and the server substitutes a generic subject when none is sent.
            subject = copy.emailSubject.takeIf { target.channel == CHANNEL_EMAIL },
        )
        return when (outcome) {
            is ApiResult.Success -> ContactVideoCallPhase.Sent(room)
            // ⛔ NOT RETRYABLE FROM HERE. A timeout means the message may already have reached the
            // customer, so "try again" would invite a second charge and a duplicate. A lost
            // connection is reported as "could not be sent" rather than "you are offline", which
            // would suggest a retry; the server's own refusals keep their coded sentence.
            is ApiResult.NetworkFailure -> failed(R.string.contact_video_send_failed)
            is ApiResult.Failure ->
                ContactVideoCallPhase.Failed(outcome.toFailureText().copy(retryable = false))
        }
    }

    private fun failed(@StringRes message: Int): ContactVideoCallPhase =
        ContactVideoCallPhase.Failed(FailureText(UiText.Resource(message), retryable = false))

    companion object {
        private const val MILLIS_PER_SECOND = 1000L

        /**
         * Which address the invitation goes to, or null when the contact has neither.
         *
         * ⛔ CHOSEN FROM WHICH TYPED COLUMN HOLDS A VALUE, never from the shape of an address: an
         * email address on the `sms` channel would reach the carrier branch, which validates
         * nothing. ⚠️ SMS FIRST, because a video invitation is time-sensitive (iOS does the same).
         */
        fun target(contact: Contact): ReplyTarget? {
            contact.phoneNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { return ReplyTarget(it, CHANNEL_SMS) }
            contact.email?.trim()?.takeIf { it.isNotEmpty() }?.let { return ReplyTarget(it, CHANNEL_EMAIL) }
            return null
        }

        fun factory(
            meetings: MeetingsRepository,
            inbox: InboxRepository,
            workspaceId: String,
            role: WorkspaceRole?,
            copy: ContactVideoInvitationCopy,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ContactVideoCallViewModel(meetings, inbox, workspaceId, role, copy) }
        }
    }
}
