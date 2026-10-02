package com.distronode.districtai.ui.inbox

import androidx.lifecycle.SavedStateHandle
import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.model.CHANNEL_EMAIL
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.DraftResponse
import com.distronode.districtai.core.model.MessageDraft
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.SendMessageResponse
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.TimelinePageInfo
import com.distronode.districtai.core.model.TimelineResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ⛔ FOUR OF THESE GUARD MONEY. Every send is billable SMS/MMS segments or a Postmark email, and the
 * server caps a workspace at 30/min — so the client must not send twice for one tap, must not send for
 * a viewer, must not send an empty body, and must not retry on its own initiative. Each of those is a
 * charge somebody else pays for, and a duplicate is a message the customer receives twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun api(vararg events: TimelineEvent) = TestDistrictApi().apply {
        timelineResult = ApiResult.Success(TimelineResponse(success = true, timeline = events.toList()))
    }

    private fun event(id: String = "m1", timestamp: String = "2026-08-15T09:00:00.000Z") =
        TimelineEvent(
            id = id,
            type = "sms",
            timestamp = timestamp,
            direction = "inbound",
            body = "hi",
        )

    /**
     * One page as the server would send it.
     *
     * ⚠️ THE CURSOR DEFAULTS TO THE PAGE'S OWN OLDEST EVENT, which is what the server derives it
     * from — index 0 of the ascending list. A test that wants the two to disagree passes them.
     */
    private fun page(
        vararg events: TimelineEvent,
        hasMore: Boolean = false,
        oldest: String? = events.firstOrNull()?.timestamp,
        oldestId: String? = events.firstOrNull()?.id,
    ) = ApiResult.Success(
        TimelineResponse(
            success = true,
            timeline = events.toList(),
            pageInfo = TimelinePageInfo(hasMore = hasMore, oldest = oldest, oldestId = oldestId),
        ),
    )

    private fun contentOf(vm: ThreadViewModel) = vm.state.value as ThreadUiState.Content

    /**
     * ⚠️ [replyTo] IS A SEPARATE PARAMETER FROM [address] ON PURPOSE, AND THIS HELPER USED TO
     * CONFLATE THEM. Every case here set both `contactId` and `address`, so the old
     * `to = address ?: contactId` always picked the address and the contact-keyed path — the
     * majority of real threads — was never exercised. See the two tests below it.
     */
    private fun viewModel(
        api: TestDistrictApi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
        contactId: String? = "c1",
        address: String? = "+14165550142",
        replyTo: String? = "+14165550142",
        channel: String = CHANNEL_SMS,
        threadKey: String = "contact:c1",
    ) = ThreadViewModel(
        repository = InboxRepository(api),
        composer = ComposerRepository(api),
        target = ThreadTarget(
            workspaceId = "ws-1",
            contactId = contactId,
            address = address,
            threadKey = threadKey,
            replyTarget = replyTo?.let { ReplyTarget(it, channel) },
        ),
        role = role,
        // ⚠️ THE COMPOSER'S OWN SEAMS ARE DEFAULTED HERE AND EXERCISED IN
        // ThreadComposerViewModelTest. A reader that answers nothing and an empty handle keep
        // this file about the send path it was written for — and keep the helper under detekt's
        // parameter ceiling, which is what a ninth argument would have broken.
        attachmentReader = AttachmentReader { null },
        savedState = SavedStateHandle(),
    )

    @Test
    fun `a contact-keyed thread sends to the resolved address, never the contact id`() =
        runTest(dispatcher) {
            // ⛔ THE REGRESSION THIS FILE MISSED. A `contact:<id>` thread has no address in its
            // key, and the recipient used to fall back to the contact id — so the server was asked
            // to text a cuid, which fails at the carrier and reads as a broken inbox.
            val api = api(event())
            val vm = viewModel(
                api,
                contactId = "c1",
                address = null,
                replyTo = "+14165550150",
            )
            advanceUntilIdle()

            vm.send("on our way")
            advanceUntilIdle()

            assertEquals(1, api.sends.size)
            assertEquals("+14165550150", api.sends.single().to)
            assertNotEquals("c1", api.sends.single().to)
        }

    @Test
    fun `the resolved channel is what gets sent`() = runTest(dispatcher) {
        // ⛔ The channel used to be hardcoded to sms, so an email-only thread sent an email
        // address down the carrier branch. It arrives resolved now, paired with its address.
        val api = api(event())
        val vm = viewModel(
            api,
            contactId = "c1",
            address = null,
            replyTo = "ada@example.com",
            channel = CHANNEL_EMAIL,
        )
        advanceUntilIdle()

        vm.send("thanks")
        advanceUntilIdle()

        assertEquals(CHANNEL_EMAIL, api.sends.single().channel)
        assertEquals("ada@example.com", api.sends.single().to)
    }

    @Test
    fun `a thread with nothing to reply on offers no reply and sends nothing`() =
        runTest(dispatcher) {
            // A thread the server marked neither canSms nor canEmail has no reachable address.
            // Collecting a reply for it would only ever produce a failed send.
            val api = api(event())
            val vm = viewModel(api, contactId = "c1", address = null, replyTo = null)
            advanceUntilIdle()

            assertFalse(vm.canReply)

            vm.send("hello?")
            advanceUntilIdle()

            assertTrue(api.sends.isEmpty())
        }

    @Test
    fun `the thread loads on construction`() = runTest(dispatcher) {
        val vm = viewModel(api(event()))
        advanceUntilIdle()

        assertEquals(1, (vm.state.value as ThreadUiState.Content).events.size)
    }

    @Test
    fun `a send marks the thread sending, then re-reads it`() = runTest(dispatcher) {
        // ⚠️ RE-READ RATHER THAN APPENDED LOCALLY. The sent row's id, status and timestamp come from
        // the server, and a locally-invented bubble would show a delivery status this client made up —
        // the one thing an operator is actually checking after a send.
        val api = api(event())
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.send("on our way")
        assertTrue((vm.state.value as ThreadUiState.Content).sending)

        advanceUntilIdle()
        assertFalse((vm.state.value as ThreadUiState.Content).sending)
        assertEquals(1, api.sends.size)
    }

    @Test
    fun `a second tap while sending does not send twice`() = runTest(dispatcher) {
        // ⛔ THE DUPLICATE-CHARGE GUARD.
        val api = api(event())
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.send("on our way")
        vm.send("on our way")
        advanceUntilIdle()

        assertEquals("one tap, one charge", 1, api.sends.size)
    }

    @Test
    fun `a viewer cannot send`() = runTest(dispatcher) {
        val api = api(event())
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(vm.canReply)
        vm.send("hello")
        advanceUntilIdle()

        assertEquals(0, api.sends.size)
    }

    @Test
    fun `an empty or blank body is refused locally`() = runTest(dispatcher) {
        // ⚠️ The server answers 400, but spending a round trip to be told what the client can already
        // see is pointless, and the resulting error would read as a fault.
        val api = api(event())
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.send("")
        vm.send("   ")
        advanceUntilIdle()

        assertEquals(0, api.sends.size)
    }

    @Test
    fun `a refused send keeps the thread and attaches the reason`() = runTest(dispatcher) {
        // ⛔ The conversation is still good; only the reply failed. Blanking it loses what the operator
        // was reading, and the server's refusal is specific enough to show verbatim.
        val api = api(event()).apply {
            sendResult = ApiResult.Success(
                SendMessageResponse(success = false, error = "Sender not verified."),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.send("on our way")
        advanceUntilIdle()

        val state = vm.state.value as ThreadUiState.Content
        assertEquals("the thread survives a failed reply", 1, state.events.size)
        assertFalse(state.sending)
        assertNotNull(state.sendFailure)
    }

    @Test
    fun `a send failure can be dismissed without disturbing the thread`() = runTest(dispatcher) {
        val api = api(event()).apply {
            sendResult = ApiResult.RateLimited("Too many messages.")
        }
        val vm = viewModel(api)
        advanceUntilIdle()
        vm.send("hi")
        advanceUntilIdle()
        assertNotNull((vm.state.value as ThreadUiState.Content).sendFailure)

        vm.dismissSendFailure()

        val state = vm.state.value as ThreadUiState.Content
        assertNull(state.sendFailure)
        assertEquals(1, state.events.size)
    }

    @Test
    fun `a thread with neither a contact nor an address cannot send`() = runTest(dispatcher) {
        // ⚠️ Defensive, and cheap: there is no `to` to give the server, so the request could only 400.
        //
        // ⛔ THE SELECTOR NO LONGER DECIDES THE RECIPIENT, which is why `replyTo` is nulled here
        // too. A malformed thread key used to imply "nothing to send to"; now the two are
        // independent (a contact-keyed thread has no address in its key and is still sendable),
        // so this pins the both-absent case rather than inferring one from the other.
        val api = api(event())
        val vm = viewModel(api, contactId = null, address = null, replyTo = null)
        advanceUntilIdle()

        vm.send("hello")
        advanceUntilIdle()

        assertEquals(0, api.sends.size)
    }

    @Test
    fun `a load failure becomes a failed state`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            timelineResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value is ThreadUiState.Failed)
    }

    @Test
    fun `sending is impossible from a failed state`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            timelineResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.send("hello")
        advanceUntilIdle()

        assertEquals(0, api.sends.size)
    }

    // ── Expand-only paging ───────────────────────────────────────────────────

    @Test
    fun `an initial load adopts the server's cursor and its hasMore`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            timelineResult = page(
                event("m5", "2026-08-15T09:00:00.000Z"),
                event("m6", "2026-08-15T10:00:00.000Z"),
                hasMore = true,
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val content = contentOf(vm)
        assertTrue("a filled window must offer the affordance", content.hasMore)
        // ⛔ THE CURSOR IS THE OLDEST EVENT, i.e. the FIRST of an ascending list. Taking the last
        // would send the newest point back as `before` and re-read the same window forever.
        assertEquals("2026-08-15T09:00:00.000Z", content.olderCursor?.before)
        assertEquals("m5", content.olderCursor?.beforeId)
        assertFalse(content.loadingOlder)
        assertNull(content.olderFailure)
    }

    @Test
    fun `loading older sends back the cursor the previous page handed over`() =
        runTest(dispatcher) {
            // ⛔ THE FAKE IS KEYED ON THE CURSOR, so serving page two AT ALL is the assertion: a
            // client that sent no cursor, or the wrong one, falls through to the first page again.
            val api = TestDistrictApi().apply {
                timelineResult = page(event("m5", "2026-08-15T09:00:00.000Z"), hasMore = true)
                timelinePages["2026-08-15T09:00:00.000Z"] =
                    page(event("m1", "2026-08-15T07:00:00.000Z"))
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            vm.loadOlder()
            advanceUntilIdle()

            assertEquals(
                listOf(null to null, "2026-08-15T09:00:00.000Z" to "m5"),
                api.timelineCursors,
            )
            assertEquals(listOf("m1", "m5"), contentOf(vm).events.map { it.id })
        }

    @Test
    fun `an overlapping older page is deduped by event id`() = runTest(dispatcher) {
        // ⛔ OVERLAP IS PART OF THE SERVER'S CONTRACT, not a fault: the two sources are windowed
        // independently, so a cursor can re-read rows the client already holds from the less dense
        // one. Appending blind shows the operator the same message twice AND puts a duplicate key
        // in the LazyColumn, which is a crash rather than a rendering oddity.
        val held = event("call-1", "2026-08-15T08:00:00.000Z")
        val api = TestDistrictApi().apply {
            timelineResult = page(
                held,
                event("m5", "2026-08-15T09:00:00.000Z"),
                hasMore = true,
                oldest = "2026-08-15T08:00:00.000Z",
                oldestId = "call-1",
            )
            timelinePages["2026-08-15T08:00:00.000Z"] = page(
                event("m1", "2026-08-15T06:00:00.000Z"),
                // The same row again, from the source whose window was never filled.
                held,
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadOlder()
        advanceUntilIdle()

        val ids = contentOf(vm).events.map { it.id }
        assertEquals(listOf("m1", "call-1", "m5"), ids)
        assertEquals("no id may appear twice", ids.size, ids.toSet().size)
    }

    @Test
    fun `an older page is merged into ascending order rather than trusted to concatenate`() =
        runTest(dispatcher) {
            // ⚠️ The page arrives newest-first and interleaves with what is held. Concatenation
            // would leave the conversation reading out of order; the merge re-sorts with the same
            // comparator the repository and the server both use.
            val api = TestDistrictApi().apply {
                timelineResult = page(event("m9", "2026-08-15T09:00:00.000Z"), hasMore = true)
                timelinePages["2026-08-15T09:00:00.000Z"] = page(
                    event("m3", "2026-08-15T05:00:00.000Z"),
                    event("m1", "2026-08-15T03:00:00.000Z"),
                    event("m2", "2026-08-15T04:00:00.000Z"),
                    oldest = "2026-08-15T03:00:00.000Z",
                    oldestId = "m1",
                )
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            vm.loadOlder()
            advanceUntilIdle()

            assertEquals(listOf("m1", "m2", "m3", "m9"), contentOf(vm).events.map { it.id })
        }

    @Test
    fun `a page that reports nothing behind it ends the affordance`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            timelineResult = page(event("m5", "2026-08-15T09:00:00.000Z"), hasMore = true)
            timelinePages["2026-08-15T09:00:00.000Z"] =
                page(event("m1", "2026-08-15T07:00:00.000Z"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadOlder()
        advanceUntilIdle()

        val content = contentOf(vm)
        assertFalse("the end of the thread must retire the control", content.hasMore)
        assertEquals("2026-08-15T07:00:00.000Z", content.olderCursor?.before)

        // ⛔ AND A FURTHER TAP FETCHES NOTHING. `hasMore` is the guard, so a control left on screen
        // by a stale recomposition cannot spend a read.
        vm.loadOlder()
        advanceUntilIdle()
        assertEquals(2, api.timelineCursors.size)
    }

    @Test
    fun `an empty older page is the end of the thread, not a fault`() = runTest(dispatcher) {
        // ⛔ hasMore MEANS "A SOURCE FILLED ITS WINDOW", so a source with exactly 50 rows left
        // reports true and this page comes back empty. That is the server being optimistic on
        // purpose, and it must read as the end rather than as an error.
        val api = TestDistrictApi().apply {
            timelineResult = page(event("m5", "2026-08-15T09:00:00.000Z"), hasMore = true)
            timelinePages["2026-08-15T09:00:00.000Z"] = page()
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadOlder()
        advanceUntilIdle()

        val content = contentOf(vm)
        assertEquals(listOf("m5"), content.events.map { it.id })
        assertFalse(content.hasMore)
        assertNull("an empty page has no anchor to page from", content.olderCursor)
        assertNull("an empty page is not a failure", content.olderFailure)
    }

    @Test
    fun `a failed older page keeps the thread on screen`() = runTest(dispatcher) {
        // ⛔ THE CONVERSATION IS STILL CORRECT. Failing to read a page BEHIND it is no reason to
        // take it away — and the cursor survives, so the same control is the retry.
        val api = TestDistrictApi().apply {
            timelineResult = page(event("m5", "2026-08-15T09:00:00.000Z"), hasMore = true)
            timelinePages["2026-08-15T09:00:00.000Z"] =
                ApiResult.NetworkFailure(java.io.IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadOlder()
        advanceUntilIdle()

        val content = contentOf(vm)
        assertEquals(listOf("m5"), content.events.map { it.id })
        assertNotNull("the failure must be surfaced", content.olderFailure)
        assertFalse(content.loadingOlder)
        assertTrue("the retry must still be offered", content.hasMore)
        assertEquals("2026-08-15T09:00:00.000Z", content.olderCursor?.before)
    }

    @Test
    fun `a second tap while a page is in flight does not fetch twice`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            timelineResult = page(event("m5", "2026-08-15T09:00:00.000Z"), hasMore = true)
            timelinePages["2026-08-15T09:00:00.000Z"] =
                page(event("m1", "2026-08-15T07:00:00.000Z"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadOlder()
        // ⚠️ No advance between them: the second tap lands while the first read is still in
        // flight, which is the case the flag exists for.
        vm.loadOlder()
        advanceUntilIdle()

        assertEquals(2, api.timelineCursors.size)
        assertEquals(listOf("m1", "m5"), contentOf(vm).events.map { it.id })
    }

    @Test
    fun `loading older is refused when the server said there is nothing behind`() =
        runTest(dispatcher) {
            val api = TestDistrictApi().apply {
                timelineResult = page(event("m5", "2026-08-15T09:00:00.000Z"))
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            vm.loadOlder()
            advanceUntilIdle()

            assertEquals(listOf<Pair<String?, String?>>(null to null), api.timelineCursors)
        }

    @Test
    fun `expanding the thread leaves the composer and its attachments untouched`() =
        runTest(dispatcher) {
            // ⛔ AN EXPANSION OF HISTORY MUST NOT DISTURB A REPLY HALF-WRITTEN. The text lives in
            // the SavedStateHandle and the attachments are already uploaded; losing either would
            // make the operator retype and re-pick because they scrolled up.
            val api = TestDistrictApi().apply {
                timelineResult = page(event("m5", "2026-08-15T09:00:00.000Z"), hasMore = true)
                timelinePages["2026-08-15T09:00:00.000Z"] =
                    page(event("m1", "2026-08-15T07:00:00.000Z"))
                draftResult = ApiResult.Success(
                    DraftResponse(
                        success = true,
                        draft = MessageDraft(
                            threadKey = "contact:c1",
                            body = "restored",
                            mediaUrls = listOf("https://www.distronode.test/api/media/media-1"),
                        ),
                    ),
                )
            }
            val vm = viewModel(api)
            advanceUntilIdle()
            vm.onComposerChange("half a reply")
            advanceUntilIdle()

            vm.loadOlder()
            advanceUntilIdle()

            assertEquals("half a reply", vm.composerText.value)
            assertEquals(
                listOf("https://www.distronode.test/api/media/media-1"),
                contentOf(vm).attachments.map { it.url },
            )
        }

    @Test
    fun `loading older is impossible from a failed state`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            timelineResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadOlder()
        advanceUntilIdle()

        assertTrue(vm.state.value is ThreadUiState.Failed)
        assertEquals(1, api.timelineCursors.size)
    }

    @Test
    fun `a page that says there is more but names no cursor asks for nothing`() = runTest(dispatcher) {
        // ⚠️ An empty first window with an optimistic hasMore: there is no oldest event to page
        // back from, so any request would be a guess at a cursor.
        val api = TestDistrictApi().apply { timelineResult = page(hasMore = true) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadOlder()
        advanceUntilIdle()

        assertEquals(listOf(null to null), api.timelineCursors)
        assertFalse(contentOf(vm).loadingOlder)
    }

    @Test
    fun `a send that fails after a reload began does not paint the old thread back`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            timelineResult = page(event("m1"))
            sendResult = ApiResult.RateLimited("Slow down.")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.send("Hello")
        vm.load()
        advanceUntilIdle()

        assertEquals(1, api.sends.size)
        assertNull(contentOf(vm).sendFailure)
        assertFalse(contentOf(vm).sending)
    }

    /**
     * ⚠️ A reload that starts while an older page is in flight wins: the page lands on a Loading
     * state and is dropped, rather than being merged into a thread that is being replaced.
     */
    @Test
    fun `an older page that lands during a reload is dropped`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            timelineResult = page(event("m5", "2026-08-15T09:00:00.000Z"), hasMore = true)
            timelinePages["2026-08-15T09:00:00.000Z"] = page(event("m1", "2026-08-15T07:00:00.000Z"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadOlder()
        vm.load()
        advanceUntilIdle()

        assertEquals(listOf("m5"), contentOf(vm).events.map { it.id })
        assertFalse(contentOf(vm).loadingOlder)
    }
}
