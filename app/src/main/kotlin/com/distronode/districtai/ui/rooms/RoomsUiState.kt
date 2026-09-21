package com.distronode.districtai.ui.rooms

import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.MediaParticipant
import com.distronode.districtai.core.model.MeetingDetail
import com.distronode.districtai.core.model.MeetingSummary
import com.distronode.districtai.ui.FailureText

/**
 * The rooms lobby: past meetings with their minutes, and the field that starts a new room.
 *
 * ⛔ THE HISTORY AND THE JOIN FORM ARE INDEPENDENT, AND THE FORM MUST SURVIVE A FAILED HISTORY
 * READ. They are two unrelated server surfaces — the list is `GET /api/district/meetings`, the join
 * needs only a room name and a token — so a screen that hid the field behind a successful list read
 * would make an outage of the minutes history into an inability to hold a meeting. That is the
 * same rule the Inbox and the contact dossier follow for their read/write failure split.
 */
data class RoomsLobbyUiState(
    val meetings: MeetingsListState = MeetingsListState.Loading,
    /**
     * What the user typed, verbatim.
     *
     * ⚠️ HELD RAW AND NORMALISED SEPARATELY, deliberately. Rewriting the field's own text as
     * someone types moves their cursor and eats their spaces; showing them [normalizedName] beside
     * it tells them what the room will actually be called. A field that silently rewrote "Weekly
     * Review" at submit time would leave two people unable to explain why they are in different
     * rooms.
     */
    val roomName: String = "",
    /** The `meet_` suffix [roomName] will become. Empty when nothing usable was typed. */
    val normalizedName: String = "",
    /**
     * The meeting whose full record is open, or null.
     *
     * ⛔ AN OVERLAY ON THIS SCREEN RATHER THAN A DESTINATION OF ITS OWN, AND THAT IS A DELIBERATE
     * NARROWING. The detail route returns the meeting's COMPLETE TRANSCRIPT — every word everybody
     * said, unredacted and unsummarised. A destination would put that behind a route that survives
     * process death and appears in the back stack, so a phone left on a desk redisplays it on
     * resume. An overlay is dismissed with the screen and holds nothing across a kill. When there
     * is a reason for a durable meeting URL (a deep link from a notification) this can become one;
     * there is not one yet.
     */
    val openMeeting: MeetingDetailState? = null,
) {
    /**
     * ⛔ FALSE FOR AN EMPTY SUFFIX RATHER THAN LETTING THE SERVER REFUSE IT. `meet_<ws>_` fails the
     * route's `^(meet|video)_([a-zA-Z0-9-]+)_(.+)$` and comes back as 400 "Invalid meeting room
     * format", which reads as a server fault for what is really an empty field.
     */
    val canJoin: Boolean get() = normalizedName.isNotEmpty()
}

sealed interface MeetingsListState {

    data object Loading : MeetingsListState

    /**
     * @param meetings ⚠️ MAY BE EMPTY ON A PERFECTLY GOOD WORKSPACE — a workspace that has held no
     *   meetings yet, which is every workspace on day one. It renders as an explanatory empty state
     *   and never as a failure.
     */
    data class Ready(val meetings: List<MeetingSummary>) : MeetingsListState

    data class Failed(val failure: FailureText) : MeetingsListState
}

/**
 * One meeting's full record, while its overlay is open.
 *
 * ⚠️ A SEPARATE READ FROM THE LIST, because the two routes return different shapes rather than a
 * subset — the list publishes a 220-character `summaryPreview` and a participant COUNT, the detail
 * publishes the whole summary, the transcript and the action items. Nothing in the list row could
 * be reused to populate this.
 */
sealed interface MeetingDetailState {

    data object Loading : MeetingDetailState

    data class Ready(val meeting: MeetingDetail) : MeetingDetailState

    /**
     * ⚠️ A 404 HERE MEANS "not yours OR not there", indistinguishably. The route scopes its lookup
     * on both the id and the workspace, so a meeting another tenant owns is simply not found — the
     * wording must not promise which.
     */
    data class Failed(val failure: FailureText) : MeetingDetailState
}

/**
 * The live room.
 *
 * ⛔ [connection] IS RENDERED, NOT BRANCHED TO AN ERROR. `Reconnecting` is a normal event on a
 * phone — a radio handover walking out of wifi is the ordinary case — and the SDK resumes the
 * session itself. A UI that treated it as a failure would tear down calls that would have survived,
 * so it is a banner OVER the call rather than a replacement for it. See
 * [CallConnectionState.Reconnecting].
 *
 * ⛔ [participants] EXCLUDES THE COMPANION, WHICH IS WHY [companionPresent] IS A SEPARATE FLAG. The
 * transcription agent joins every `meet_` room and publishes no media, so leaving it in the grid
 * draws a blank muted tile in the middle of a meeting. Removing it silently would be worse: it is
 * listening and writing minutes, and a person in the room is entitled to know that. It is surfaced
 * as a chip.
 *
 * ⛔ [canPublish] IS THE SERVER'S ANSWER, NOT A LOCAL PREFERENCE. A viewer's token carries
 * `canPublish:false`, so its microphone and camera would be refused by the media server. The UI
 * disables both rather than offering controls that fail — and a viewer is still a legitimate
 * attendee, so the room is joined either way.
 */
data class ActiveRoomUiState(
    val connection: CallConnectionState = CallConnectionState.Idle,
    /** ⛔ Humans only. See the class doc. */
    val participants: List<MediaParticipant> = emptyList(),
    /** ⚠️ Surfaced as "taking notes", never hidden. See the class doc. */
    val companionPresent: Boolean = false,
    val micEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val speakerOn: Boolean = false,
    /** ⛔ From the token, not from the role string. See the class doc. */
    val canPublish: Boolean = true,
    /**
     * An absolute, shareable guest link, or null.
     *
     * ⛔ NULL FOR A VIEWER BY SERVER DECISION, AND THE UI MUST NOT SYNTHESISE ONE. The invite is a
     * transferable twelve-hour capability that grants PUBLISH rights to whoever holds it, so the
     * route mints it only for a non-viewer. A client that built a link from the room name would be
     * offering something `/api/meet/token` will refuse — and would look, to the person sharing it,
     * exactly like a working invitation until their guest could not join.
     */
    val guestLink: String? = null,
    /** ⚠️ The join itself failing, shown INSTEAD of the room. Distinct from [connection]. */
    val joinFailure: FailureText? = null,
    val permissions: RoomPermissions = RoomPermissions(),
)

/**
 * What the OS has granted, as far as this screen knows.
 *
 * ⛔ THE TWO PERMISSIONS ARE NOT EQUIVALENT AND MUST NOT BE ASKED FOR AS A PAIR THAT EITHER
 * SUCCEEDS OR FAILS. A denied CAMERA is a normal way to attend a meeting — audio-only, and the room
 * is fully usable. A denied MICROPHONE is not the same thing for a participant, but it is still not
 * a reason to refuse the join: a viewer never publishes anyway, and someone who only wants to
 * watch and read the minutes is doing something legitimate. So the screen degrades in two
 * different directions rather than gating on one boolean.
 *
 * ⚠️ [requested] IS "WE HAVE ASKED", NOT "WE HAVE BEEN GRANTED". Before the first request both
 * granted flags are false and mean nothing; rendering a "microphone blocked" warning in that window
 * would tell the user something untrue about their own device.
 */
data class RoomPermissions(
    val requested: Boolean = false,
    val microphoneGranted: Boolean = false,
    val cameraGranted: Boolean = false,
)
