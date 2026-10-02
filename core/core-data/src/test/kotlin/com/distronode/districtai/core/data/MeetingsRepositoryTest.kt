package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.GuestInvite
import com.distronode.districtai.core.model.MeetingDetail
import com.distronode.districtai.core.model.MeetingSummary
import com.distronode.districtai.core.model.RoomTokenResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * Joining a room, and reading back what the Companion wrote.
 *
 * ⛔ THE THREE THINGS THIS LAYER MUST NOT DO. It must not accept a `{}` token response as a join
 * (every field of that DTO has a default, so an empty body decodes into an empty token and an
 * empty URL, and the failure would present as a media-plane fault rather than as the server
 * refusal it was). It must not surface a `video_` room as a meeting, because those are billable
 * avatar sessions from a surface this app does not implement. And it must not treat an empty
 * meetings list as a failure — that is every workspace on day one.
 */
class MeetingsRepositoryTest {

    private fun repo(api: FakeDistrictApi) = MeetingsRepository(api)

    // ── The join token ───────────────────────────────────────────────────────

    @Test
    fun `a token is requested for exactly the room name given, with a constant identity`() = runTest {
        val api = FakeDistrictApi()
        val result = repo(api).roomToken("meet_ws-abc_standup")

        assertTrue(result is ApiResult.Success)
        assertEquals(1, api.roomTokenRequests.size)
        // ⛔ THE ROOM NAME IS PASSED THROUGH UNCHANGED. This layer never assembles one — see
        // MeetRoomName for why a template here would be one character from a billable avatar room.
        assertEquals("meet_ws-abc_standup", api.roomTokenRequests[0].roomName)
        // ⚠️ The server REQUIRES the key and IGNORES the value (it derives a hashed identity from
        // the session instead, because accepting a client identity was an impersonation hole). A
        // constant is therefore correct, and sending a device id would put a value on the wire
        // that is neither used nor needed.
        assertEquals("android", api.roomTokenRequests[0].identity)
    }

    @Test
    fun `a token response that does not affirm success is rejected as contract drift`() = runTest {
        // ⛔ THE `{}` CASE. Every field on RoomTokenResponse has a default, so an empty body decodes
        // cleanly into a well-formed object holding an empty token and an empty URL. Passing that
        // through would make the engine fail to connect, and the user would see a media failure
        // for what was really the server declining to mint a token.
        val api = FakeDistrictApi().apply {
            roomTokenResult = ApiResult.Success(RoomTokenResponse())
        }

        val result = repo(api).roomToken("meet_ws-abc_standup")

        assertTrue(
            "a 200 that does not affirm success must be a DecodeFailure, not a Success",
            result is ApiResult.DecodeFailure,
        )
    }

    @Test
    fun `a viewer token with no invite is a SUCCESS, not a degraded one`() = runTest {
        // ⛔ READ-ONLY ATTENDANCE IS A SUPPORTED SEAT. The route withholds the guest invite from a
        // viewer deliberately — it is a transferable twelve-hour capability that grants PUBLISH
        // rights — so an absent invite is the server working, and a repository that promoted it to
        // a failure would refuse a join the product offers.
        val api = FakeDistrictApi().apply {
            roomTokenResult = ApiResult.Success(
                RoomTokenResponse(success = true, token = "jwt", url = "wss://livekit.example.test"),
            )
        }

        val result = repo(api).roomToken("meet_ws-abc_standup")

        assertTrue(result is ApiResult.Success)
        assertNull((result as ApiResult.Success).value.guestInvite)
        assertNull(result.value.guestPath)
    }

    @Test
    fun `a non-viewer token carries the invite through untouched`() = runTest {
        val api = FakeDistrictApi().apply {
            roomTokenResult = ApiResult.Success(
                RoomTokenResponse(
                    success = true,
                    token = "jwt",
                    url = "wss://livekit.example.test",
                    guestInvite = GuestInvite(exp = 1786973400, sig = "sig"),
                    guestPath = "/meet/meet_ws-abc_standup?e=1786973400&s=sig",
                ),
            )
        }

        val result = repo(api).roomToken("meet_ws-abc_standup") as ApiResult.Success
        // ⚠️ The path is the SERVER'S assembly — the signature is computed over the room name, so a
        // locally rebuilt path is one `/api/meet/token` will refuse. Carried, never derived.
        assertEquals("/meet/meet_ws-abc_standup?e=1786973400&s=sig", result.value.guestPath)
        assertEquals(1786973400L, result.value.guestInvite!!.exp)
    }

    @Test
    fun `a failed token request is returned as-is`() = runTest {
        val api = FakeDistrictApi().apply {
            roomTokenResult = ApiResult.Forbidden("not a member")
        }

        assertTrue(repo(api).roomToken("meet_ws-abc_standup") is ApiResult.Forbidden)
    }

    // ── The meetings list ────────────────────────────────────────────────────

    @Test
    fun `video_ rooms are filtered out of the meetings list`() = runTest {
        // ⛔ THE Meeting TABLE RECORDS WHATEVER ROOM THE COMPANION WAS IN, INCLUDING AVATAR
        // SESSIONS. Those belong to a different product surface with a different billing story;
        // showing them here would list "meetings" the user never held, next to ones they did.
        val api = FakeDistrictApi().apply {
            meetingsResult = ApiResult.Success(
                listOf(
                    MeetingSummary(id = "m1", roomName = "meet_ws-abc_standup"),
                    MeetingSummary(id = "m2", roomName = "video_ws-abc_avatar"),
                    MeetingSummary(id = "m3", roomName = "meet_ws-abc_weekly"),
                ),
            )
        }

        val result = repo(api).meetings("ws-abc") as ApiResult.Success

        assertEquals(listOf("m1", "m3"), result.value.map { it.id })
        assertEquals("ws-abc", api.meetingsRequests.single())
    }

    @Test
    fun `an empty meetings list is a success, not a failure`() = runTest {
        // ⛔ EVERY WORKSPACE ON DAY ONE. Rendering this as a failure would tell a new customer
        // something is broken on a screen that is working perfectly. There is also no envelope to
        // check here — the route answers a bare array — so nothing could distinguish the two even
        // if it wanted to.
        val api = FakeDistrictApi().apply { meetingsResult = ApiResult.Success(emptyList()) }

        val result = repo(api).meetings("ws-abc")

        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).value.isEmpty())
    }

    @Test
    fun `a degraded-regions failure is carried, never flattened to an empty list`() = runTest {
        // ⛔ "WE COULD NOT LOOK" IS NOT "THERE IS NOTHING". That exact conflation routed a paying
        // customer to a checkout page on the web; here it would report a workspace as having held
        // no meetings when its database simply did not answer.
        val api = FakeDistrictApi().apply {
            meetingsResult = ApiResult.RegionsDegraded("eu is down", listOf("eu"))
        }

        val result = repo(api).meetings("ws-abc")

        assertTrue(result is ApiResult.RegionsDegraded)
        assertEquals(listOf("eu"), (result as ApiResult.RegionsDegraded).degradedRegions)
    }

    // ── The meeting detail ───────────────────────────────────────────────────

    @Test
    fun `a detail read is scoped on BOTH the workspace and the id`() = runTest {
        val api = FakeDistrictApi().apply {
            meetingDetailResult = ApiResult.Success(
                MeetingDetail(id = "m1", summary = "minutes", transcript = "Ada: hello"),
            )
        }

        val result = repo(api).meetingDetail("ws-abc", "m1") as ApiResult.Success

        assertEquals("minutes", result.value.summary)
        // ⚠️ The workspace is not derivable from the id — the route requires it so the RLS context
        // and the role check apply to the tenant the caller CLAIMS, which is what makes a meeting
        // id from another workspace simply not found.
        assertEquals("ws-abc/m1", api.meetingDetailRequests.single())
    }

    @Test
    fun `a missing meeting stays a NotFound rather than becoming an empty record`() = runTest {
        // ⚠️ 404 HERE MEANS "not yours OR not there", indistinguishably — the lookup is scoped on
        // both. An empty MeetingDetail would render as a meeting with no minutes, which is a
        // different and wrong claim.
        val api = FakeDistrictApi().apply {
            meetingDetailResult = ApiResult.NotFound("Meeting not found.")
        }

        assertTrue(repo(api).meetingDetail("ws-abc", "gone") is ApiResult.NotFound)
    }
}
