package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallHangUpResponse
import com.distronode.districtai.core.model.MessageSearchHit
import com.distronode.districtai.core.model.MessageSearchResponse
import com.distronode.districtai.core.model.MessageThreadResponse
import com.distronode.districtai.core.model.MessageThreadTarget
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Searching, resolving a push, and ending a carrier leg.
 */
class MessageSearchRepositoryTest {

    private fun hit(id: String) = MessageSearchHit(
        messageId = id,
        key = "phone:+14165550142",
        threadKey = "contact:c1",
        counterpart = "+14165550142",
        kind = "phone",
        body = "Refund policy is 30 days.",
        direction = "inbound",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    @Test
    fun `a query below the floor answers empty and sends nothing`() = runTest {
        // ⛔ MEASURED ON THE TRIMMED VALUE, because the server trims before it measures. The route
        // answers such a query with an empty list and no `limit`, so asking would spend a request
        // to be told what the floor already says.
        val api = FakeInboxExtrasApi()

        val result = MessageSearchRepository(api).search("ws-1", "  a  ")

        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).value.hits.isEmpty())
        assertTrue("no request may be sent", api.searches.isEmpty())
    }

    @Test
    fun `a full page reports as truncated, and a short one does not`() = runTest {
        // ⛔ A FULL PAGE IS A DIFFERENT STATEMENT FROM A COMPLETE ONE. There is no offset to page
        // on, so drawing thirty rows without saying so presents an incomplete answer as a whole one.
        val full = FakeInboxExtrasApi().apply {
            searchResult = ApiResult.Success(
                MessageSearchResponse(success = true, results = listOf(hit("m1"), hit("m2")), limit = 2),
            )
        }
        val fullResult = MessageSearchRepository(full).search("ws-1", "refund")
        assertTrue((fullResult as ApiResult.Success).value.truncated)

        val short = FakeInboxExtrasApi().apply {
            searchResult = ApiResult.Success(
                MessageSearchResponse(success = true, results = listOf(hit("m1")), limit = 30),
            )
        }
        val shortResult = MessageSearchRepository(short).search("ws-1", "refund")
        assertFalse((shortResult as ApiResult.Success).value.truncated)
    }

    @Test
    fun `an answer with no limit key cannot be truncated`() = runTest {
        // ⚠️ THE SHORT-QUERY BRANCH omits `limit` entirely, and inferring truncation from an absent
        // cap would caption an empty list as "older matches exist".
        val api = FakeInboxExtrasApi().apply {
            searchResult = ApiResult.Success(MessageSearchResponse(success = true, results = emptyList()))
        }

        val result = MessageSearchRepository(api).search("ws-1", "refund")

        assertFalse((result as ApiResult.Success).value.truncated)
    }

    @Test
    fun `a failed search is forwarded rather than reported as no matches`() = runTest {
        // ⛔ "No matches" AND "I could not look" ARE DIFFERENT ANSWERS, and the first is the one
        // somebody acts on.
        val api = FakeInboxExtrasApi().apply {
            searchResult = ApiResult.NetworkFailure(IOException("down"))
        }

        assertTrue(MessageSearchRepository(api).search("ws-1", "refund") is ApiResult.NetworkFailure)
    }

    @Test
    fun `a resolved thread is the target a push navigates on`() = runTest {
        val api = FakeInboxExtrasApi().apply {
            threadResult = ApiResult.Success(
                MessageThreadResponse(
                    success = true,
                    thread = MessageThreadTarget(
                        threadKey = "contact:c1",
                        contactId = "c1",
                        counterpart = "+14165550142",
                        channel = "sms",
                    ),
                ),
            )
        }

        val result = MessageSearchRepository(api).threadFor("ws-1", "msg_1")

        assertTrue(result is ApiResult.Success)
        assertEquals("contact:c1", (result as ApiResult.Success).value.threadKey)
    }

    @Test
    fun `a thread with no key is a decode failure rather than a navigation to nowhere`() = runTest {
        val api = FakeInboxExtrasApi().apply {
            threadResult = ApiResult.Success(MessageThreadResponse(success = true))
        }

        assertTrue(MessageSearchRepository(api).threadFor("ws-1", "msg_1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a hang-up that deleted the room reports ended`() = runTest {
        val api = FakeCallControlApi().apply {
            result = ApiResult.Success(CallHangUpResponse(success = true, ended = true))
        }

        assertEquals(HangUpOutcome.Ended, CallControlRepository(api).hangUp("ws-1", "CA1"))
        assertEquals(listOf("ws-1" to "CA1"), api.hangUps)
    }

    @Test
    fun `ended false and a 404 are both successes, because the call is already over`() = runTest {
        // ⚠️ `ended: false` MEANS NO DEPLOYMENT HELD THE ROOM — the callee hung up, the room
        // emptied, or a previous hangup already ran. A 404 means this workspace cannot see the
        // call. Neither is a fault, and by the time this is sent the local teardown has happened.
        val gone = FakeCallControlApi().apply {
            result = ApiResult.Success(CallHangUpResponse(success = true, ended = false))
        }
        assertEquals(HangUpOutcome.AlreadyEnded, CallControlRepository(gone).hangUp("ws-1", "CA1"))

        val missing = FakeCallControlApi().apply { result = ApiResult.NotFound("Call not found") }
        assertEquals(HangUpOutcome.AlreadyEnded, CallControlRepository(missing).hangUp("ws-1", "CA1"))
    }

    @Test
    fun `a 409 is not a direct softphone call, and a 503 is a real failure`() = runTest {
        // ⛔ THE 503 IS THE ONE BRANCH WHERE A CARRIER LEG MAY STILL BE BILLING. An unconfigured
        // LiveKit answers 503 rather than `ended:false`, because "I could not check" must never be
        // reported as "it is over".
        val conflict = FakeCallControlApi().apply {
            result = ApiResult.HttpFailure(status = 409, message = "not a direct softphone call")
        }
        assertEquals(
            HangUpOutcome.NotDirectCall,
            CallControlRepository(conflict).hangUp("ws-1", "CA1"),
        )

        val unconfigured = FakeCallControlApi().apply {
            result = ApiResult.HttpFailure(status = 503, message = "Calling service is not configured.")
        }
        assertTrue(
            CallControlRepository(unconfigured).hangUp("ws-1", "CA1") is HangUpOutcome.NotEnded,
        )
    }
}
