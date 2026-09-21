package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.MeetRoomName
import com.distronode.districtai.core.model.MeetingDetail
import com.distronode.districtai.core.model.MeetingSummary
import com.distronode.districtai.core.model.RoomTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.MeetingsApi
import com.distronode.districtai.core.network.RoomTokenRequest

/**
 * Joining a `meet_` room, and reading back the meetings the Companion wrote up.
 *
 * ⛔ THE ROOM NAME IS BUILT HERE AND NOWHERE ELSE, THROUGH [MeetRoomName]. The token route accepts
 * `meet_` and `video_` through the same branch, and a `video_` room starts a billable Tavus avatar
 * session — so a call site free to write its own template is one character from spending money on
 * a surface this app does not implement. [roomToken] takes a room name that a caller obtained from
 * [MeetRoomName.of]; nothing in this layer will assemble one from a raw prefix.
 *
 * ⛔ NO CACHE, AND FOR THE TOKEN THAT IS NOT A PREFERENCE. A token is a signed, ~30-minute
 * capability bound to one room, and a guest invite minted alongside it is a transferable
 * twelve-hour one — caching either would mean handing a later joiner a credential minted for an
 * earlier session, and re-sharing an invite whose expiry the user never saw. The meeting reads are
 * uncached for the ordinary reason every sibling here is: the list is what someone checks right
 * after a meeting ends, and a stale copy answers "where are my minutes" with a snapshot from
 * before they existed.
 *
 * ⚠️ THE LIST HAS NO ENVELOPE TO CHECK. `GET /api/district/meetings` answers a bare array, so
 * [rejectedEnvelope] does not apply and an empty list is a legitimate "nothing yet" that no guard
 * could distinguish from a broken read anyway. Only [roomToken] carries a `success` flag, and it is
 * checked — a token response that did not affirm success would otherwise decode into an empty
 * token and an empty URL, and the join would fail as a LiveKit connection error rather than as the
 * server refusal it was.
 */
class MeetingsRepository(private val api: MeetingsApi) {

    /**
     * Mint the credential for one room join.
     *
     * ⛔ CALL THIS ONCE PER JOIN, NOT PER RECOMPOSITION. Each call mints a fresh guest invite for a
     * non-viewer, so a screen that reached for it on every redraw would be issuing twelve-hour
     * transferable capabilities at the rate it draws frames. The ViewModel that owns the call also
     * owns exactly one engine, and the two are minted together.
     *
     * ⚠️ A `viewer` GETS A VALID TOKEN WITH `canPublish:false` AND NO INVITE, WHICH IS A SUCCESS
     * AND NOT A DEGRADED ONE. Read-only attendance is a supported way to be in a room; the caller
     * must respect the absent invite rather than treat it as a missing field.
     */
    suspend fun roomToken(roomName: String): ApiResult<RoomTokenResponse> =
        when (val result = api.roomToken(RoomTokenRequest(roomName = roomName))) {
            // ⚠️ Envelope first, for the reason `ResponseEnvelope` documents: every field of this
            // DTO has a default, so a `{}` body decodes into a well-formed token response holding
            // an empty token and an empty URL — which would present as a media-plane failure.
            is ApiResult.Success -> rejectedEnvelope(TOKEN_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }

    /**
     * The workspace's meetings, newest first.
     *
     * ⛔ FILTERED TO `meet_` ROOMS, AND THE FILTER IS DELIBERATE RATHER THAN DEFENSIVE. The Meeting
     * table records whatever room the Companion was dispatched into, which includes `video_` avatar
     * sessions — a different product surface with a different billing story, whose rows would
     * appear here as meetings the user never held. A row that fails the filter belongs to something
     * else; it is not corrupt data and it is not an error.
     *
     * ⚠️ THE SERVER CAPS THIS AT 50 AND SAYS NOTHING ABOUT IT, so this is not a complete history
     * and a screen must not label it as one. Filtering client-side can only shorten it further,
     * which is why the cap is worth stating twice.
     */
    suspend fun meetings(workspaceId: String): ApiResult<List<MeetingSummary>> =
        when (val result = api.meetings(workspaceId)) {
            is ApiResult.Success ->
                ApiResult.Success(result.value.filter { MeetRoomName.isMeetRoom(it.roomName) })
            is ApiResult.Failure -> result
        }

    /**
     * One meeting in full.
     *
     * ⛔ NO ENVELOPE HERE EITHER — the route returns the raw row, so there is no `success` flag,
     * and a 404 arrives as [ApiResult.NotFound] rather than as a body. ⚠️ That 404 is also what a
     * meeting id from ANOTHER workspace produces, because the lookup is scoped on both id and
     * workspace: "not yours" and "not there" are deliberately indistinguishable.
     */
    suspend fun meetingDetail(workspaceId: String, meetingId: String): ApiResult<MeetingDetail> =
        api.meetingDetail(workspaceId, meetingId)

    private companion object {
        const val TOKEN_ENVELOPE = "RoomTokenResponse"
    }
}
