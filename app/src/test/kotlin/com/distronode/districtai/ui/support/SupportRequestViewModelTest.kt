package com.distronode.districtai.ui.support

import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.core.model.SupportCloseResponse
import com.distronode.districtai.core.model.SupportMessage
import com.distronode.districtai.core.model.SupportReplyResponse
import com.distronode.districtai.core.model.SupportRequestDetail
import com.distronode.districtai.core.model.SupportRequestDetailResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SupportApi
import kotlinx.coroutines.CompletableDeferred
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
 * One support request's state machine.
 *
 * ⛔ NEITHER WRITE HERE IS IDEMPOTENT AND NEITHER MAY EVER BE RETRIED AUTOMATICALLY:
 * - A reply is posted as a PUBLIC Jira comment, so a repeat leaves a second copy in the customer's
 *   own thread and notifies the agent twice.
 * - A close posts a PUBLIC audit comment naming who asked BEFORE it applies the transition —
 *   deliberately, so the attribution survives a transition that fails — so a repeat that still
 *   finds a transition leaves a second "Closed at the requester's request by …" beside it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupportRequestViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val request = SupportRequestDetail(
        issueKey = "DA-42",
        id = "row_1",
        subject = "Calls drop",
        statusName = "In Progress",
        statusCategory = "INDETERMINATE",
        closeable = true,
        messages = listOf(SupportMessage(id = "c1", role = "customer", body = "It drops.")),
    )

    private fun api(detail: SupportRequestDetail = request) = FakeSupportApiForUi().apply {
        detailResult = ApiResult.Success(
            SupportRequestDetailResponse(success = true, request = detail),
        )
    }

    private fun viewModel(
        api: FakeSupportApiForUi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = SupportRequestViewModel(
        SupportRepository(api),
        workspaceId = "ws-1",
        key = "DA-42",
        role = role,
    )

    private fun content(model: SupportRequestViewModel) =
        model.state.value as SupportRequestUiState.Content

    @Test
    fun `a viewer sends no read`() = runTest {
        val api = api()

        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(model.canUse)
        assertEquals(0, api.detailReads)
    }

    @Test
    fun `the thread loads with the server's closeable verdict`() = runTest {
        val model = viewModel(api())
        advanceUntilIdle()

        assertTrue(content(model).canClose)
        assertEquals(listOf("c1"), content(model).request.messages.map { it.id })
    }

    @Test
    fun `close is NOT offered when the server says the request is not closeable`() = runTest {
        // ⛔ THE SERVER'S ANSWER, NOT A ROLE DERIVATION. The workflow either offers no resolving
        // transition or offers several, and picking one would decide on the customer's behalf
        // whether their request was "done" or "won't do".
        val model = viewModel(api(request.copy(closeable = false)))
        advanceUntilIdle()

        assertFalse(content(model).canClose)
    }

    @Test
    fun `close is NOT offered on an already-resolved request`() = runTest {
        // ⛔ A REPEAT LEAVES A SECOND AUDIT COMMENT in the customer's own thread, because the
        // comment is posted before the transition is applied.
        val model = viewModel(api(request.copy(statusCategory = "DONE")))
        advanceUntilIdle()

        assertFalse(content(model).canClose)
    }

    @Test
    fun `closing when the server said no sends nothing`() = runTest {
        val api = api(request.copy(closeable = false))
        val model = viewModel(api)
        advanceUntilIdle()

        model.close()
        advanceUntilIdle()

        assertTrue(api.closedKeys.isEmpty())
    }

    @Test
    fun `a close adopts the desk's own statusName rather than substituting English`() = runTest {
        val api = api().apply {
            closeResult = ApiResult.Success(
                SupportCloseResponse(success = true, statusName = "Terminé"),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.close()
        advanceUntilIdle()

        // ⚠️ THE LIVE WORKFLOW IS LOCALISED. Printing "Closed" here would put English over a status
        // Atlassian spells in another language.
        assertEquals("Terminé", content(model).request.statusName)
        assertEquals("Terminé", model.closedAs.value)
        // ⚠️ The CATEGORY is what `isResolved` reads and the close route does not echo it, so it is
        // set explicitly rather than inferred from a name that cannot be compared.
        assertTrue(content(model).request.isResolved)
        assertEquals(1, api.closedKeys.size)
    }

    @Test
    fun `a 409 not-closeable keeps the request open and shows the server's sentence`() = runTest {
        // ⚠️ AN ANSWER, NOT AN ERROR. The sentence names the way forward (reply and we will close
        // it) and should be shown as sent.
        val api = api().apply {
            closeResult = ApiResult.HttpFailure(
                409,
                "This request cannot be closed from here. Reply to let us know it is resolved.",
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.close()
        advanceUntilIdle()

        val state = content(model)
        assertFalse(state.closing)
        assertTrue(state.closeFailure != null)
        assertFalse("the request must stay open", state.request.isResolved)
    }

    @Test
    fun `a second close while the first is in flight sends nothing`() = runTest {
        // ⛔ A REPEAT LEAVES A SECOND AUDIT COMMENT. The control stays on screen while the first
        // close is in flight (disabled, labelled as closing), so the ViewModel is what refuses the
        // second one.
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.close()
        val inFlight = content(model)
        assertTrue(inFlight.closing)
        assertTrue("the control stays offered while it closes", inFlight.canClose)
        model.close()
        advanceUntilIdle()

        assertEquals(listOf("DA-42"), api.closedKeys)
    }

    @Test
    fun `a failed close is not repeated`() = runTest {
        val api = api().apply { closeResult = ApiResult.HttpFailure(502, "We could not close it.") }
        val model = viewModel(api)
        advanceUntilIdle()

        model.close()
        advanceUntilIdle()

        assertEquals(1, api.closedKeys.size)
    }

    // ── The reply ────────────────────────────────────────────────────────────

    @Test
    fun `a reply appends the echoed message and clears the draft`() = runTest {
        val api = api().apply {
            replyResult = ApiResult.Success(
                SupportReplyResponse(
                    success = true,
                    message = SupportMessage(id = "c2", role = "customer", body = "Thanks."),
                ),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editDraft("Thanks.")
        model.send()
        advanceUntilIdle()

        assertEquals(listOf("c1", "c2"), content(model).request.messages.map { it.id })
        assertEquals("", model.draft.value)
        // ⚠️ NO RE-READ. The detail read is a live Atlassian fetch; spending one to show a sentence
        // already in hand would be slower and could fail.
        assertEquals(1, api.detailReads)
    }

    @Test
    fun `a 409 on reply keeps the draft, because the message was never delivered`() = runTest {
        // ⚠️ THE REQUEST IS STILL BEING OPENED: we hold it, it has no Atlassian thread yet, and
        // accepting the reply would silently drop the one message the customer wanted us to see.
        val api = api().apply {
            replyResult = ApiResult.HttpFailure(409, "This request is still being opened.")
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editDraft("Any news?")
        model.send()
        advanceUntilIdle()

        assertEquals("Any news?", model.draft.value)
        assertTrue(content(model).sendFailure != null)
        assertEquals(listOf("c1"), content(model).request.messages.map { it.id })
    }

    @Test
    fun `a reply is still offered on a resolved request, because there is no reopen`() = runTest {
        // ⛔ THE SERVER EXPOSES NO REOPEN — the desk's workflow has no single unambiguous transition
        // back out of `done` — so replying on a closed request is the supported path and the box
        // must stay usable.
        val api = api(request.copy(statusCategory = "DONE")).apply {
            replyResult = ApiResult.Success(
                SupportReplyResponse(success = true, message = SupportMessage(id = "c2")),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()

        model.editDraft("Actually it is back.")
        model.send()
        advanceUntilIdle()

        assertEquals(listOf("Actually it is back."), api.replyBodies)
    }

    @Test
    fun `a blank draft sends nothing`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()

        model.editDraft("   ")
        model.send()
        advanceUntilIdle()

        assertTrue(api.replyBodies.isEmpty())
    }

    @Test
    fun `a viewer neither replies nor closes`() = runTest {
        val api = api()
        val model = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        model.editDraft("Hello")
        model.send()
        model.close()
        advanceUntilIdle()

        assertTrue(api.replyBodies.isEmpty())
        assertTrue(api.closedKeys.isEmpty())
    }

    @Test
    fun `retryOrNoop replays the read and NEVER either write`() = runTest {
        val api = api().apply { detailResult = ApiResult.NotFound("Not found") }
        val model = viewModel(api)
        advanceUntilIdle()
        val afterFailure = api.detailReads

        model.retryOrNoop()
        advanceUntilIdle()

        assertTrue(api.detailReads > afterFailure)
        assertTrue(api.replyBodies.isEmpty())
        assertTrue(api.closedKeys.isEmpty())
    }

    @Test
    fun `a 404 is surfaced as sent and the client does not narrate a reason`() = runTest {
        // ⛔ "no such request", "not this workspace's" and "erased" answer IDENTICALLY so a
        // sequential key cannot be probed with a session and a loop.
        val api = api().apply { detailResult = ApiResult.NotFound("Not found") }
        val model = viewModel(api)
        advanceUntilIdle()

        val state = model.state.value as SupportRequestUiState.Failed
        assertEquals("Not found", state.failure.message.literalOrNull)
        assertFalse("a 404 here offers no retry", state.failure.retryable)
    }

    @Test
    fun `neither write is attempted before the thread has loaded`() = runTest {
        // ⚠️ There is no thread to append to or close yet; a tap in that window must not post.
        val api = api()
        val model = viewModel(api)
        model.editDraft("Still happening")

        model.send()
        model.close()
        advanceUntilIdle()

        assertTrue(api.replyBodies.isEmpty())
        assertTrue(api.closedKeys.isEmpty())
    }

    @Test
    fun `a second send while the first is in flight posts one comment`() = runTest {
        // ⛔ A reply is a PUBLIC comment; a double tap would put two copies in the customer's thread.
        val api = api().apply {
            replyResult = ApiResult.Success(
                SupportReplyResponse(
                    success = true,
                    message = SupportMessage(id = "c2", role = "customer", body = "Still happening"),
                ),
            )
        }
        val model = viewModel(api)
        advanceUntilIdle()
        model.editDraft("Still happening")

        model.send()
        model.send()
        advanceUntilIdle()

        assertEquals(listOf("Still happening"), api.replyBodies)
    }

    @Test
    fun `retryOrNoop on a loaded thread reads nothing`() = runTest {
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        val reads = api.detailReads

        model.retryOrNoop()
        advanceUntilIdle()

        assertEquals(reads, api.detailReads)
    }

    @Test
    fun `a close during a send keeps the posted reply and stops both spinners`() = runTest {
        // ⛔ CLOSE STAYS TAPPABLE WHILE A REPLY SENDS. Before the fix the close landed on its
        // tap-time snapshot (taken with sending = true and without the reply), so the posted reply
        // vanished and Send spun forever.
        val api = api().apply {
            replyResult = ApiResult.Success(
                SupportReplyResponse(success = true, message = SupportMessage(id = "c2", body = "Thanks")),
            )
            closeResult = ApiResult.Success(SupportCloseResponse(success = true, statusName = "Done"))
        }
        val replyGate = CompletableDeferred<Unit>()
        val closeGate = CompletableDeferred<Unit>()
        val gated = object : SupportApi by api {
            override suspend fun replyToSupportRequest(
                workspaceId: String,
                key: String,
                body: String,
            ): ApiResult<SupportReplyResponse> {
                replyGate.await()
                return api.replyToSupportRequest(workspaceId, key, body)
            }

            override suspend fun closeSupportRequest(
                workspaceId: String,
                key: String,
            ): ApiResult<SupportCloseResponse> {
                closeGate.await()
                return api.closeSupportRequest(workspaceId, key)
            }
        }
        val model = SupportRequestViewModel(
            SupportRepository(gated),
            workspaceId = "ws-1",
            key = "DA-42",
            role = WorkspaceRole.CLIENT,
        )
        advanceUntilIdle()

        model.editDraft("Thanks")
        model.send()
        model.close()
        advanceUntilIdle()
        replyGate.complete(Unit)
        advanceUntilIdle()
        closeGate.complete(Unit)
        advanceUntilIdle()

        val state = content(model)
        assertFalse(state.sending)
        assertFalse(state.closing)
        assertEquals(listOf("c1", "c2"), state.request.messages.map { it.id })
        assertEquals("Done", state.request.statusName)
    }
}
