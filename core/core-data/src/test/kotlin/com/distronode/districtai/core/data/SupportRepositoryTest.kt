package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SupportCloseResponse
import com.distronode.districtai.core.model.SupportMessage
import com.distronode.districtai.core.model.SupportReplyResponse
import com.distronode.districtai.core.model.SupportRequestCreateResponse
import com.distronode.districtai.core.model.SupportRequestDetail
import com.distronode.districtai.core.model.SupportRequestDetailResponse
import com.distronode.districtai.core.model.SupportRequestDraft
import com.distronode.districtai.core.model.SupportRequestFiling
import com.distronode.districtai.core.model.SupportRequestKind
import com.distronode.districtai.core.model.SupportRequestListResponse
import com.distronode.districtai.core.model.SupportRequestSummary
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeSupportApi

/**
 * The support surface's data layer.
 *
 * ⛔ THE FAILURE THIS FILE EXISTS FOR HAS ALREADY HAPPENED ON THE WEB: every list failure was
 * swallowed into `[]`, so a customer with three open tickets was told they had none, stopped
 * chasing, and nobody here ever saw the request. Keeping an empty list and a failed read as
 * DIFFERENT results is the whole job.
 *
 * ⛔ AND ONE SECURITY PROPERTY, asserted rather than left to review: nothing this repository sends
 * identifies the requester. The workspace is the only scope; the server derives and hashes the
 * requester from the session. That is the boundary against the voice lookup, which takes no
 * identity argument precisely because caller ID is spoofable.
 */
class SupportRepositoryTest {

    private val draft = SupportRequestDraft(
        kind = SupportRequestKind.PROBLEM,
        subject = "  Calls drop after 30 seconds  ",
        message = "  Every inbound call ends abruptly.  ",
    )

    // ── An empty list is not a failure, and a failure is not an empty list ───

    @Test
    fun `an empty list on a success envelope is a real answer`() = runTest {
        val api = FakeSupportApi().apply {
            listResult = ApiResult.Success(SupportRequestListResponse(success = true))
        }

        val result = SupportRepository(api).requests("ws-1")

        assertEquals(emptyList<SupportRequestSummary>(), (result as ApiResult.Success).value)
    }

    @Test
    fun `a 200 that does not affirm success is drift, not an empty list`() = runTest {
        // ⛔ THE ONE THAT LIES, AND ON THIS SURFACE IT IS THE EXPENSIVE DIRECTION. Every field of
        // the response DTO defaults, so `{}` decodes into a well-formed "you have raised nothing".
        val api = FakeSupportApi().apply {
            listResult = ApiResult.Success(SupportRequestListResponse())
        }

        assertTrue(SupportRepository(api).requests("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a list failure is passed through rather than becoming an empty list`() = runTest {
        val api = FakeSupportApi().apply {
            listResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
        }

        assertTrue(SupportRepository(api).requests("ws-1") is ApiResult.NetworkFailure)
    }

    // ── The detail ───────────────────────────────────────────────────────────

    @Test
    fun `a detail that affirms success with no request is drift`() = runTest {
        val api = FakeSupportApi().apply {
            detailResult = ApiResult.Success(SupportRequestDetailResponse(success = true))
        }

        assertTrue(SupportRepository(api).request("ws-1", "DA-42") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a detail or a reply that does not affirm success is drift even with its payload present`() =
        runTest {
            val api = FakeSupportApi().apply {
                detailResult = ApiResult.Success(
                    SupportRequestDetailResponse(success = false, request = SupportRequestDetail()),
                )
                replyResult = ApiResult.Success(SupportReplyResponse(success = false, message = SupportMessage()))
            }
            val repository = SupportRepository(api)

            assertTrue(repository.request("ws-1", "DA-42") is ApiResult.DecodeFailure)
            assertTrue(repository.reply("ws-1", "DA-42", "Thanks") is ApiResult.DecodeFailure)
        }

    @Test
    fun `a 404 is passed through unchanged and is never interpreted`() = runTest {
        // ⛔ "no such request", "not this workspace's request" and "erased" answer IDENTICALLY, so
        // that a sequential key cannot be probed by anyone with a session and a loop. Telling them
        // apart here would rebuild the oracle the server declined to offer.
        val api = FakeSupportApi().apply { detailResult = ApiResult.NotFound("Not found") }

        val result = SupportRepository(api).request("ws-1", "DA-41")

        assertTrue(result is ApiResult.NotFound)
        assertEquals("Not found", (result as ApiResult.NotFound).message)
    }

    @Test
    fun `a detail carries the whole thread and the server's closeable verdict`() = runTest {
        val api = FakeSupportApi().apply {
            detailResult = ApiResult.Success(
                SupportRequestDetailResponse(
                    success = true,
                    request = SupportRequestDetail(
                        issueKey = "DA-42",
                        statusName = "In Progress",
                        statusCategory = "INDETERMINATE",
                        closeable = true,
                        messages = listOf(SupportMessage(id = "c1", role = "agent")),
                    ),
                ),
            )
        }

        val request = (SupportRepository(api).request("ws-1", "DA-42") as ApiResult.Success).value

        assertTrue(request.closeable)
        assertEquals(1, request.messages.size)
    }

    // ── The create, and the idempotency rule that is the DESK'S INVERSE ──────

    @Test
    fun `the caller's key is forwarded unchanged, because a retry must REUSE it`() = runTest {
        // ⛔ THE OPPOSITE OF `DeskRepository.createTicket`, WHICH MINTS ONE PER CALL. Here the
        // server claims the key before it calls Atlassian and answers a re-used one with
        // `deduplicated:true`, so a retry carrying the SAME key collapses onto the first request
        // while a retry that minted a fresh one would put a second ticket in a human's queue.
        // Minting here would defeat the protection exactly when it is needed.
        val api = FakeSupportApi()
        val repository = SupportRepository(api)

        repository.createRequest("ws-1", draft, "draft-key")
        repository.createRequest("ws-1", draft, "draft-key")

        assertEquals(listOf("draft-key", "draft-key"), api.createKeys)
    }

    @Test
    fun `the subject and message are trimmed and the kind is passed through`() = runTest {
        val api = FakeSupportApi()

        SupportRepository(api).createRequest("ws-1", draft, "key-1")

        val sent = api.createDrafts.single()
        assertEquals("Calls drop after 30 seconds", sent.subject)
        assertEquals("Every inbound call ends abruptly.", sent.message)
        assertEquals(SupportRequestKind.PROBLEM, sent.kind)
    }

    @Test
    fun `all three filings are successes, PENDING included`() = runTest {
        val filed = FakeSupportApi().apply {
            createResult = ApiResult.Success(
                SupportRequestCreateResponse(success = true, issueKey = "DA-43"),
            )
        }
        assertEquals(
            SupportRequestFiling.Filed("DA-43"),
            (SupportRepository(filed).createRequest("ws-1", draft, "k") as ApiResult.Success).value,
        )

        val deduplicated = FakeSupportApi().apply {
            createResult = ApiResult.Success(
                SupportRequestCreateResponse(success = true, deduplicated = true),
            )
        }
        assertEquals(
            SupportRequestFiling.Deduplicated,
            (
                SupportRepository(deduplicated)
                    .createRequest("ws-1", draft, "k") as ApiResult.Success
                ).value,
        )

        // ⛔ PENDING IS A SUCCESS: we hold the claim row and a human will see it, only the reference
        // is missing. Returning a failure here is what makes an operator send the same request
        // twice, which is the one thing the key exists to prevent.
        val pending = FakeSupportApi().apply {
            createResult = ApiResult.Success(
                SupportRequestCreateResponse(success = true, pending = true),
            )
        }
        assertEquals(
            SupportRequestFiling.Pending,
            (
                SupportRepository(pending)
                    .createRequest("ws-1", draft, "k") as ApiResult.Success
                ).value,
        )
    }

    @Test
    fun `a create that does not affirm success is drift`() = runTest {
        val api = FakeSupportApi().apply {
            createResult = ApiResult.Success(SupportRequestCreateResponse())
        }

        assertTrue(
            SupportRepository(api).createRequest("ws-1", draft, "k") is ApiResult.DecodeFailure,
        )
    }

    @Test
    fun `a 429 carrying the server's sentence is passed through verbatim`() = runTest {
        // ⚠️ The sentence NAMES THE REMEDY, so replacing it with a generic message drops the only
        // useful thing in it.
        val api = FakeSupportApi().apply {
            createResult = ApiResult.RateLimited(
                "You have reached the daily limit for new requests. Please reply on an existing one.",
            )
        }

        val result = SupportRepository(api).createRequest("ws-1", draft, "k")

        assertTrue((result as ApiResult.RateLimited).message.contains("reply on an existing one"))
    }

    // ── The two writes that must never be retried ────────────────────────────

    @Test
    fun `a reply that affirms success with no message is drift`() = runTest {
        val api = FakeSupportApi().apply {
            replyResult = ApiResult.Success(SupportReplyResponse(success = true))
        }

        assertTrue(SupportRepository(api).reply("ws-1", "DA-42", "hi") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a reply is trimmed and sent exactly once, never retried`() = runTest {
        // ⛔ THE REPLY IS POSTED AS A PUBLIC JIRA COMMENT, so a repeat leaves a second copy in the
        // customer's own thread and notifies the agent twice. Nothing in this layer may retry.
        val api = FakeSupportApi().apply {
            replyResult = ApiResult.Success(
                SupportReplyResponse(success = true, message = SupportMessage(id = "c3")),
            )
        }

        SupportRepository(api).reply("ws-1", "DA-42", "  Thanks.  ")

        assertEquals(listOf("Thanks."), api.replyBodies)
    }

    @Test
    fun `a failed reply is sent once and not repeated`() = runTest {
        val api = FakeSupportApi().apply {
            replyResult = ApiResult.HttpFailure(409, "This request is still being opened.")
        }

        val result = SupportRepository(api).reply("ws-1", "row_2", "Any news?")

        assertEquals(409, (result as ApiResult.HttpFailure).status)
        assertEquals(1, api.replyBodies.size)
    }

    @Test
    fun `close returns the desk's own statusName and calls the route once`() = runTest {
        // ⛔ NOT IDEMPOTENT: the server posts a PUBLIC audit comment naming who asked BEFORE it
        // applies the transition, so a repeat that still finds a transition leaves a second
        // "Closed at the requester's request by …" in the customer's own thread.
        val api = FakeSupportApi().apply {
            closeResult = ApiResult.Success(
                SupportCloseResponse(success = true, statusName = "Terminé"),
            )
        }

        val result = SupportRepository(api).close("ws-1", "DA-42")

        // ⚠️ THE LIVE WORKFLOW IS LOCALISED. Substituting "Closed" would print English over a status
        // Atlassian spells in another language.
        assertEquals("Terminé", (result as ApiResult.Success).value)
        assertEquals(1, api.closeCalls)
    }

    @Test
    fun `a close that does not affirm success is drift`() = runTest {
        val api = FakeSupportApi().apply {
            closeResult = ApiResult.Success(SupportCloseResponse())
        }

        assertTrue(SupportRepository(api).close("ws-1", "DA-42") is ApiResult.DecodeFailure)
    }

    @Test
    fun `every call scopes on the workspace and nothing else`() = runTest {
        // ⛔ THE SECURITY PROPERTY. No method on this repository takes an email address, a phone
        // number or a requester hash, and its signatures are what make that checkable rather than
        // a matter of review discipline.
        val api = FakeSupportApi()
        val repository = SupportRepository(api)

        repository.requests("ws-1")
        repository.request("ws-1", "DA-42")
        repository.createRequest("ws-1", draft, "k")
        repository.reply("ws-1", "DA-42", "hi")
        repository.close("ws-1", "DA-42")

        assertEquals(List(5) { "ws-1" }, api.workspaceIds)
    }
}
