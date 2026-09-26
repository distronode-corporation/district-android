package com.distronode.districtai.ui.inbox

import androidx.lifecycle.SavedStateHandle
import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.model.AiDraftResponse
import com.distronode.districtai.core.model.CHANNEL_EMAIL
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.DraftResponse
import com.distronode.districtai.core.model.MediaUploadResponse
import com.distronode.districtai.core.model.MessageDraft
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.TimelineResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The composer half of [ThreadViewModel]: attachments, the persisted draft and the AI generator.
 *
 * ⛔ FIVE OF THESE GUARD MONEY OR DATA LOSS, WHICH IS WHY THEY ARE HERE RATHER THAN LEFT TO A
 * SCREEN TEST:
 *   - autosave must DELETE on a cleared box and never PUT a blank one, or the server 400s and the
 *     workspace's 60 writes/min are spent on refusals;
 *   - autosave must be DEBOUNCED, or one fast typist consumes that budget alone and 429s a
 *     colleague in the same workspace;
 *   - a successful send must delete the draft AND cancel the pending autosave, or the reply is
 *     resurrected and sent twice from the next device;
 *   - a restored draft must never overwrite text the operator has already typed;
 *   - an empty AI generation must never blank a composer that has text in it.
 *
 * ⚠️ Driven on a [StandardTestDispatcher] so the two-second debounce is virtual time. A real delay
 * would make this suite two seconds slower per case and flaky on a loaded machine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadComposerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

    private fun api() = TestDistrictApi().apply {
        timelineResult = ApiResult.Success(
            TimelineResponse(
                success = true,
                timeline = listOf(
                    TimelineEvent(
                        id = "m1",
                        type = "sms",
                        timestamp = "2026-08-15T09:00:00.000Z",
                        direction = "inbound",
                        body = "Are you open Thursday?",
                    ),
                ),
            ),
        )
    }

    private fun viewModel(
        api: TestDistrictApi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
        channel: String = CHANNEL_SMS,
        threadKey: String = "contact:c1",
        reader: AttachmentReader = AttachmentReader { PickedAttachment("roof.png", "image/png", png) },
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = ThreadViewModel(
        repository = InboxRepository(api),
        composer = ComposerRepository(api),
        target = ThreadTarget(
            workspaceId = "ws-1",
            contactId = "c1",
            address = null,
            threadKey = threadKey,
            replyTarget = ReplyTarget("+14165550142", channel),
        ),
        role = role,
        attachmentReader = reader,
        savedState = savedState,
    )

    private fun content(vm: ThreadViewModel) = vm.state.value as ThreadUiState.Content

    // ── Draft restore and the merge rule ─────────────────────────────────────

    @Test
    fun `an empty composer adopts the server's draft on open`() = runTest(dispatcher) {
        val api = api().apply {
            draftResult = ApiResult.Success(
                DraftResponse(
                    success = true,
                    draft = MessageDraft(threadKey = "contact:c1", body = "Thursday at 2pm works."),
                ),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals("Thursday at 2pm works.", vm.composerText.value)
    }

    /**
     * ⛔ THE LOCAL BOX WINS, AND THE ASYMMETRY IS THE WHOLE POINT. Adopting into an empty composer
     * can only add; overwriting a non-empty one can only lose. A timestamp comparison was
     * considered and rejected because the restored local text has no timestamp — it is whatever
     * survived process death — so the comparison would have to invent one, and inventing it on the
     * wrong side silently destroys what the operator typed.
     */
    @Test
    fun `a composer that already has text is not overwritten by the server draft`() = runTest(dispatcher) {
        val api = api().apply {
            draftResult = ApiResult.Success(
                DraftResponse(
                    success = true,
                    draft = MessageDraft(threadKey = "contact:c1", body = "the older draft"),
                ),
            )
        }
        // What a process-death restore looks like: the handle already holds the text.
        val restored = SavedStateHandle(mapOf("thread-composer-text" to "what I was just typing"))

        val vm = viewModel(api, savedState = restored)
        advanceUntilIdle()

        assertEquals("what I was just typing", vm.composerText.value)
    }

    /**
     * ⚠️ ATTACHMENTS COME BACK WITH THE TEXT. Restoring the words alone would send a message the
     * operator believed had pictures on it.
     */
    @Test
    fun `a restored draft brings its attachments back as chips`() = runTest(dispatcher) {
        val api = api().apply {
            draftResult = ApiResult.Success(
                DraftResponse(
                    success = true,
                    draft = MessageDraft(
                        threadKey = "contact:c1",
                        body = "See the photo",
                        mediaUrls = listOf("https://www.distronode.test/api/media/m1"),
                    ),
                ),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(1, content(vm).attachments.size)
        assertEquals("https://www.distronode.test/api/media/m1", content(vm).attachments.single().url)
    }

    // ── Autosave ─────────────────────────────────────────────────────────────

    /**
     * ⛔ DEBOUNCED, NOT PER KEYSTROKE. The drafts route allows 60 writes/min per WORKSPACE and that
     * budget is SHARED — two operators typing in the same workspace draw on the same bucket — so a
     * write per character would trip it in under a second and the 429 would land on whoever
     * happened to type last.
     */
    @Test
    fun `typing does not save until the debounce elapses`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.onComposerChange("Thurs")
        advanceTimeBy(500)
        assertTrue("nothing may be written mid-typing", api.draftSaves.isEmpty())

        advanceUntilIdle()
        assertEquals(1, api.draftSaves.size)
        assertEquals("Thurs", api.draftSaves.single().body)
    }

    /**
     * ⚠️ THE TIMER RESTARTS ON EACH EDIT RATHER THAN EXTENDING FROM THE FIRST. A sustained typist
     * would otherwise never save at all — and the four keystrokes below must cost ONE write, not
     * four.
     */
    @Test
    fun `a burst of edits produces a single save carrying the last text`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.onComposerChange("T")
        advanceTimeBy(300)
        vm.onComposerChange("Th")
        advanceTimeBy(300)
        vm.onComposerChange("Thu")
        advanceTimeBy(300)
        vm.onComposerChange("Thursday works")
        advanceUntilIdle()

        assertEquals("four edits must cost one write", 1, api.draftSaves.size)
        assertEquals("Thursday works", api.draftSaves.single().body)
    }

    /**
     * ⛔ A CLEARED BOX SENDS DELETE, NEVER A PUT WITH AN EMPTY BODY. The server answers 400
     * `empty_body` to the latter — a blank draft is the ABSENCE of one — so a PUT here would burn a
     * write to be refused AND leave the previous draft in place, which the next device would then
     * restore over an empty composer the operator had deliberately cleared.
     */
    @Test
    fun `clearing the composer deletes the draft instead of saving a blank one`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.onComposerChange("half a sentence")
        advanceUntilIdle()
        assertEquals(1, api.draftSaves.size)

        vm.onComposerChange("")
        advanceUntilIdle()

        assertEquals("no second PUT", 1, api.draftSaves.size)
        assertEquals(listOf("contact:c1"), api.draftDeletes)
    }

    @Test
    fun `whitespace-only text is treated as cleared, not as a draft`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.onComposerChange("   ")
        advanceUntilIdle()

        assertTrue(api.draftSaves.isEmpty())
        assertEquals(listOf("contact:c1"), api.draftDeletes)
    }

    @Test
    fun `an autosave carries the attachments that are on the composer when it fires`() =
        runTest(dispatcher) {
            val api = api()
            val vm = viewModel(api)
            advanceUntilIdle()

            vm.onComposerChange("look at this")
            // ⚠️ Attached DURING the debounce, which is the case a naive implementation gets wrong:
            // capturing the URLs at edit time would save the draft without the image.
            vm.attach("content://pick/1")
            advanceUntilIdle()

            assertEquals(
                listOf("https://www.distronode.test/api/media/media-1"),
                api.draftSaves.last().mediaUrls,
            )
        }

    // ── Send ─────────────────────────────────────────────────────────────────

    /**
     * ⛔ THE DRAFT IS DELETED AND THE PENDING AUTOSAVE CANCELLED. Without the cancel, a debounce
     * armed by the last keystroke fires AFTER the delete and re-creates the draft that was just
     * sent — which the operator's next device then restores, and they send it again.
     */
    @Test
    fun `a successful send deletes the draft and cancels the pending autosave`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.onComposerChange("on our way")
        // Sent BEFORE the debounce fires, which is what an operator who types and taps does.
        vm.send("on our way")
        advanceUntilIdle()

        assertEquals(1, api.sends.size)
        assertEquals(listOf("contact:c1"), api.draftDeletes)
        assertTrue("the cancelled debounce must not resurrect the draft", api.draftSaves.isEmpty())
        assertEquals("", vm.composerText.value)
    }

    @Test
    fun `a send carries the attached media urls`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.attach("content://pick/1")
        advanceUntilIdle()

        vm.send("Here is the roof.")
        advanceUntilIdle()

        assertEquals(
            listOf("https://www.distronode.test/api/media/media-1"),
            api.sends.single().mediaUrls,
        )
    }

    /**
     * ⛔ A FAILED SEND KEEPS THE ATTACHMENTS. They are already uploaded and already paid for;
     * dropping them would make the operator re-pick every image to retry a send that failed for an
     * unrelated reason.
     */
    @Test
    fun `a failed send keeps the attachments so a retry does not re-pick them`() = runTest(dispatcher) {
        val api = api().apply {
            sendResult = ApiResult.HttpFailure(status = 500, message = "carrier unavailable")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.attach("content://pick/1")
        advanceUntilIdle()
        vm.send("Here is the roof.")
        advanceUntilIdle()

        assertEquals(1, content(vm).attachments.size)
        assertNotNull(content(vm).sendFailure)
    }

    // ── Attachments ──────────────────────────────────────────────────────────

    @Test
    fun `attaching uploads the picked bytes and adds a chip`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.attach("content://pick/1")
        advanceUntilIdle()

        assertEquals(Triple("roof.png", "image/png", png.size), api.uploads.single())
        assertEquals(1, content(vm).attachments.size)
        assertFalse(content(vm).attaching)
    }

    @Test
    fun `a removed attachment leaves the rest in place`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.attach("content://pick/1")
        advanceUntilIdle()
        val url = content(vm).attachments.single().url

        vm.removeAttachment(url)

        assertTrue(content(vm).attachments.isEmpty())
    }

    /**
     * ⛔ THE SIXTH PICK IS REFUSED BEFORE IT IS READ. `messages/send` rejects more than five
     * mediaUrls with a 400 for the WHOLE message, so allowing a sixth would let the operator build
     * a message that could only fail to send.
     */
    @Test
    fun `the sixth attachment is refused rather than uploaded`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        repeat(ComposerRepository.MAX_ATTACHMENTS) {
            // ⚠️ A distinct URL per upload, or the chips would collide on one key.
            api.uploadMediaResult = ApiResult.Success(
                MediaUploadResponse(
                    success = true,
                    media = com.distronode.districtai.core.model.UploadedMedia(
                        id = "m$it",
                        mimeType = "image/png",
                        sizeBytes = png.size,
                        url = "https://www.distronode.test/api/media/m$it",
                    ),
                ),
            )
            vm.attach("content://pick/$it")
            advanceUntilIdle()
        }
        assertEquals(ComposerRepository.MAX_ATTACHMENTS, content(vm).attachments.size)

        vm.attach("content://pick/overflow")
        advanceUntilIdle()

        assertEquals(ComposerRepository.MAX_ATTACHMENTS, content(vm).attachments.size)
        assertEquals(
            "the sixth must not have been uploaded",
            ComposerRepository.MAX_ATTACHMENTS,
            api.uploads.size,
        )
        assertNotNull(content(vm).sendFailure)
    }

    @Test
    fun `an unsupported type is refused without an upload`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(
            api,
            reader = AttachmentReader { PickedAttachment("scan.pdf", "application/pdf", png) },
        )
        advanceUntilIdle()

        vm.attach("content://pick/1")
        advanceUntilIdle()

        assertTrue(api.uploads.isEmpty())
        assertTrue(content(vm).attachments.isEmpty())
        assertNotNull(content(vm).sendFailure)
    }

    @Test
    fun `an oversized image is refused without an upload`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(
            api,
            reader = AttachmentReader {
                PickedAttachment("huge.png", "image/png", ByteArray(ComposerRepository.MAX_UPLOAD_BYTES + 1))
            },
        )
        advanceUntilIdle()

        vm.attach("content://pick/1")
        advanceUntilIdle()

        assertTrue(api.uploads.isEmpty())
        assertNotNull(content(vm).sendFailure)
    }

    /**
     * ⚠️ "COULD NOT BE READ" IS A DIFFERENT ANSWER FROM "WRONG FORMAT", and the operator's next
     * action differs: pick again versus pick something else. Collapsing them would leave someone
     * re-picking the same unsupported file.
     */
    @Test
    fun `an unreadable pick reports a failure rather than crashing`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api, reader = AttachmentReader { null })
        advanceUntilIdle()

        vm.attach("content://pick/revoked")
        advanceUntilIdle()

        assertTrue(api.uploads.isEmpty())
        assertFalse(content(vm).attaching)
        assertNotNull(content(vm).sendFailure)
    }

    @Test
    fun `an upload rejected by the server surfaces its refusal`() = runTest(dispatcher) {
        val api = api().apply {
            uploadMediaResult = ApiResult.Success(
                MediaUploadResponse(success = false, error = "Attachments must be between 1 byte and 5MB"),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.attach("content://pick/1")
        advanceUntilIdle()

        assertTrue(content(vm).attachments.isEmpty())
        assertFalse(content(vm).attaching)
        assertNotNull(content(vm).sendFailure)
    }

    // ── Channel gating ───────────────────────────────────────────────────────

    /**
     * ⛔ EMAIL THREADS OFFER NO ATTACH CONTROL, AND THE REASON IS WORSE THAN A 400. The send route's
     * email branch never looks at `mediaUrls` at all — it hands `text` to Postmark — so an
     * attachment on an email thread would upload, cost a database write, and silently NOT be
     * delivered. That is the one failure mode with no error anywhere, which is why it is gated
     * client-side rather than left to the server.
     */
    @Test
    fun `an email thread offers no attach control and refuses an attach outright`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api, channel = CHANNEL_EMAIL)
        advanceUntilIdle()

        assertFalse(vm.canAttach)

        vm.attach("content://pick/1")
        advanceUntilIdle()

        assertTrue("an email thread must never upload", api.uploads.isEmpty())
    }

    @Test
    fun `an sms thread offers the attach control`() = runTest(dispatcher) {
        val vm = viewModel(api(), channel = CHANNEL_SMS)
        advanceUntilIdle()

        assertTrue(vm.canAttach)
    }

    /** ⚠️ A viewer cannot send, so a viewer cannot attach — the gate is `canReply` first. */
    @Test
    fun `a viewer can neither reply nor attach`() = runTest(dispatcher) {
        val vm = viewModel(api(), role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(vm.canReply)
        assertFalse(vm.canAttach)
    }

    // ── AI draft ─────────────────────────────────────────────────────────────

    /**
     * ⛔ THE GENERATED TEXT IS TREATED AS TYPED TEXT — it autosaves like anything else. The
     * alternative (holding it un-persisted until the operator edits it) was rejected because it
     * makes the composer lie: the box would show text a process death would silently discard.
     */
    @Test
    fun `a generated draft fills the composer and autosaves like typed text`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.generateDraft()
        advanceUntilIdle()

        assertEquals("Happy to help — when suits you?", vm.composerText.value)
        assertEquals(1, api.generations.size)
        assertEquals("Happy to help — when suits you?", api.draftSaves.single().body)
        assertFalse(content(vm).generating)
    }

    /**
     * ⛔ AN EMPTY GENERATION MUST NOT BLANK THE COMPOSER. The route answers `""` when the model
     * returns nothing, and writing that into a box the operator had typed in is the single most
     * destructive thing this button could do.
     */
    @Test
    fun `an empty generation leaves the operator's text alone`() = runTest(dispatcher) {
        val api = api().apply {
            generateDraftResult = ApiResult.Success(AiDraftResponse(success = true, draft = ""))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.onComposerChange("something I typed")
        advanceUntilIdle()

        vm.generateDraft()
        advanceUntilIdle()

        assertEquals("something I typed", vm.composerText.value)
        assertFalse(content(vm).generating)
    }

    /**
     * ⛔ ONE GENERATION PER TAP, AND THE GUARD IS ABOUT MONEY. Each is a billed Vertex call capped
     * at 20/min per workspace; a double tap must not become two of them.
     */
    @Test
    fun `a second tap while generating is ignored`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.generateDraft()
        vm.generateDraft()
        advanceUntilIdle()

        assertEquals(1, api.generations.size)
    }

    @Test
    fun `a viewer cannot generate a billable draft`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        vm.generateDraft()
        advanceUntilIdle()

        assertTrue(api.generations.isEmpty())
    }

    @Test
    fun `a refused generation surfaces the failure and clears the in-flight flag`() = runTest(dispatcher) {
        val api = api().apply {
            generateDraftResult = ApiResult.RateLimited("Too many draft generations for this workspace.")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.generateDraft()
        advanceUntilIdle()

        assertFalse(content(vm).generating)
        assertNotNull(content(vm).sendFailure)
        assertEquals("", vm.composerText.value)
    }

    /**
     * ⚠️ A DRAFT LOOKUP THAT FAILS MUST NOT BREAK THE THREAD. The conversation is still readable
     * and the composer is still usable; refusing to open a thread because a draft restore 500ed
     * would be strictly worse than opening it with an empty box.
     */
    @Test
    fun `a failed draft restore leaves the thread usable`() = runTest(dispatcher) {
        val api = api().apply {
            draftResult = ApiResult.HttpFailure(status = 500, message = "boom")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value is ThreadUiState.Content)
        assertEquals("", vm.composerText.value)
        assertNull(content(vm).sendFailure)
    }

    // ── A thread that failed to load has no composer to change ───────────────

    @Test
    fun `on a thread that failed to load, the composer's controls change nothing and spend nothing`() =
        runTest(dispatcher) {
            val api = api().apply { timelineResult = ApiResult.NetworkFailure(java.io.IOException("offline")) }
            val vm = viewModel(api)
            advanceUntilIdle()
            val failed = vm.state.value
            assertTrue(failed is ThreadUiState.Failed)

            vm.attach("content://picked")
            vm.removeAttachment("https://www.distronode.test/api/media/media-1")
            vm.dismissSendFailure()
            vm.generateDraft()
            advanceUntilIdle()

            assertEquals(failed, vm.state.value)
            assertTrue(api.uploads.isEmpty())
            assertTrue(api.generations.isEmpty())
        }

    @Test
    fun `an autosave that fires while the thread is not loaded saves the text with no media`() =
        runTest(dispatcher) {
            val api = api().apply { timelineResult = ApiResult.NetworkFailure(java.io.IOException("offline")) }
            val vm = viewModel(api)
            advanceUntilIdle()

            vm.onComposerChange("Still here.")
            advanceUntilIdle()

            val save = api.draftSaves.single()
            assertEquals("Still here.", save.body)
            assertTrue(save.mediaUrls.isEmpty())
        }

    @Test
    fun `a second attach while an upload is in flight is ignored`() = runTest(dispatcher) {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.attach("content://one")
        vm.attach("content://two")
        advanceUntilIdle()

        assertEquals(1, api.uploads.size)
        assertEquals(1, content(vm).attachments.size)
    }

    @Test
    fun `a restored draft's attachments are dropped when the thread itself failed to load`() =
        runTest(dispatcher) {
            val api = api().apply {
                timelineResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
                draftResult = ApiResult.Success(
                    DraftResponse(
                        success = true,
                        draft = MessageDraft(
                            threadKey = "contact:c1",
                            body = "See attached.",
                            mediaUrls = listOf("https://www.distronode.test/api/media/media-9"),
                        ),
                    ),
                )
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            // The text still comes back, since it lives in the composer rather than in the thread.
            assertEquals("See attached.", vm.composerText.value)
            assertTrue(vm.state.value is ThreadUiState.Failed)
        }
}
