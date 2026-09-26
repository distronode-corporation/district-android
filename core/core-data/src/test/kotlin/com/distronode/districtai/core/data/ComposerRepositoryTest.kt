package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.AiDraftResponse
import com.distronode.districtai.core.model.DraftListResponse
import com.distronode.districtai.core.model.DraftResponse
import com.distronode.districtai.core.model.MediaUploadResponse
import com.distronode.districtai.core.model.MessageDraft
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⛔ THREE OF THESE GUARD SOMETHING OTHER THAN CORRECTNESS.
 *   - The two local pre-checks stop a five-megabyte upload being spent on a metered connection to
 *     be refused by a rule the client already knew.
 *   - The blank-body refusal stops a PUT the server answers 400 to, which would also burn one of
 *     the workspace's 60 draft writes per minute to achieve nothing.
 *   - `generateDraft` is one billed Vertex call per invocation, so nothing here may retry it.
 */
class ComposerRepositoryTest {

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

    private fun repo(api: FakeDistrictApi) = ComposerRepository(api)

    // ── Uploads ──────────────────────────────────────────────────────────────

    @Test
    fun `a supported image is uploaded and its url returned`() = runTest {
        val api = FakeDistrictApi()
        val result = repo(api).uploadMedia("ws-1", "roof.png", "image/png", png)

        assertTrue(result is ApiResult.Success)
        assertEquals(
            "https://www.distronode.test/api/media/media-1",
            (result as ApiResult.Success).value.url,
        )
        assertEquals(1, api.uploads.size)
        assertEquals(Triple("roof.png", "image/png", png.size), api.uploads.single())
    }

    /**
     * ⛔ REFUSED BEFORE THE REQUEST, NOT AFTER IT. The server enforces the same allowlist, so this
     * changes no outcome — it changes the COST of the outcome, which on a phone is the point. The
     * assertion that matters is `api.uploads` being empty: a refusal that still uploaded would be
     * indistinguishable in the result and identical in cost to no check at all.
     */
    @Test
    fun `an unsupported type is refused without spending the upload`() = runTest {
        val api = FakeDistrictApi()
        val result = repo(api).uploadMedia("ws-1", "scan.pdf", "application/pdf", png)

        assertTrue(result is ApiResult.Failure)
        assertTrue(api.uploads.isEmpty())
        assertTrue(
            "the refusal must name the rule",
            (result as ApiResult.HttpFailure).message.contains("JPEG"),
        )
    }

    @Test
    fun `an oversized image is refused without spending the upload`() = runTest {
        val api = FakeDistrictApi()
        val tooBig = ByteArray(ComposerRepository.MAX_UPLOAD_BYTES + 1)

        val result = repo(api).uploadMedia("ws-1", "huge.png", "image/png", tooBig)

        assertTrue(result is ApiResult.Failure)
        assertTrue(api.uploads.isEmpty())
    }

    /**
     * ⚠️ ZERO BYTES IS A FAILED READ, NOT AN IMAGE, and the server's floor is 1 byte. Refusing it
     * here means the operator is told to pick again rather than being shown a server 400 whose
     * wording is about sizes.
     */
    @Test
    fun `an empty file is refused`() = runTest {
        val api = FakeDistrictApi()
        val result = repo(api).uploadMedia("ws-1", "empty.png", "image/png", ByteArray(0))

        assertTrue(result is ApiResult.Failure)
        assertTrue(api.uploads.isEmpty())
    }

    /**
     * ⛔ `success` ALONE IS NOT ENOUGH. A success-shaped envelope with no media would otherwise
     * attach an EMPTY URL to a billable send, which the carrier then fails on — a charge spent on
     * a message that cannot be delivered.
     */
    @Test
    fun `a success envelope with no media is treated as a failure`() = runTest {
        val api = FakeDistrictApi().apply {
            uploadMediaResult = ApiResult.Success(MediaUploadResponse(success = true, media = null))
        }

        val result = repo(api).uploadMedia("ws-1", "roof.png", "image/png", png)

        assertTrue(result is ApiResult.Failure)
    }

    @Test
    fun `the server's own refusal text is carried through verbatim`() = runTest {
        val api = FakeDistrictApi().apply {
            uploadMediaResult = ApiResult.Success(
                MediaUploadResponse(success = false, error = "Attachments must be between 1 byte and 5MB"),
            )
        }

        val result = repo(api).uploadMedia("ws-1", "roof.png", "image/png", png)

        assertEquals(
            "Attachments must be between 1 byte and 5MB",
            (result as ApiResult.HttpFailure).message,
        )
    }

    // ── Drafts ───────────────────────────────────────────────────────────────

    /**
     * ⛔ A NULL DRAFT IS A SUCCESS. Almost every thread has none, and mapping it to a failure would
     * put an error banner on the ordinary open path of nearly every conversation.
     */
    @Test
    fun `a thread with no draft succeeds with null rather than failing`() = runTest {
        val api = FakeDistrictApi().apply {
            draftResult = ApiResult.Success(DraftResponse(success = true, draft = null))
        }

        val result = repo(api).loadDraft("ws-1", "contact:c1")

        assertTrue(result is ApiResult.Success)
        assertNull((result as ApiResult.Success).value)
    }

    @Test
    fun `a saved draft comes back with its attachments`() = runTest {
        val api = FakeDistrictApi().apply {
            draftResult = ApiResult.Success(
                DraftResponse(
                    success = true,
                    draft = MessageDraft(
                        threadKey = "contact:c1",
                        body = "half a sentence",
                        mediaUrls = listOf("https://www.distronode.test/api/media/m1"),
                    ),
                ),
            )
        }

        val draft = (repo(api).loadDraft("ws-1", "contact:c1") as ApiResult.Success).value

        assertEquals("half a sentence", draft?.body)
        assertEquals(1, draft?.mediaUrls?.size)
    }

    /**
     * ⛔ KEYS, NOT BODIES. The Inbox list needs to know WHICH threads carry a draft so it can badge
     * them; pulling the text into a list screen would put a colleague-invisible half-sentence into
     * every screenshot of the Inbox.
     */
    @Test
    fun `the badge read returns thread keys and discards the text`() = runTest {
        val api = FakeDistrictApi().apply {
            draftsResult = ApiResult.Success(
                DraftListResponse(
                    success = true,
                    drafts = listOf(
                        MessageDraft(threadKey = "contact:c1", body = "a price not yet agreed"),
                        MessageDraft(threadKey = "addr:14165550168", body = "a tone not yet softened"),
                    ),
                ),
            )
        }

        val keys = (repo(api).draftThreadKeys("ws-1") as ApiResult.Success).value

        assertEquals(setOf("contact:c1", "addr:14165550168"), keys)
    }

    /**
     * ⛔ A BLANK BODY NEVER REACHES THE SERVER. It answers 400 `empty_body` and means "send DELETE";
     * firing it anyway burns one of the workspace's 60 writes/min to be refused. `draftSaves` being
     * empty is the assertion — a local refusal that still made the call would look identical in the
     * result and cost exactly as much as no check.
     */
    @Test
    fun `a blank draft body is refused locally and never sent`() = runTest {
        val api = FakeDistrictApi()

        val result = repo(api).saveDraft("ws-1", "contact:c1", "   ")

        assertTrue(result is ApiResult.Failure)
        assertTrue("no PUT may be issued for a blank body", api.draftSaves.isEmpty())
    }

    @Test
    fun `a saved draft sends its thread key, body and attachments`() = runTest {
        val api = FakeDistrictApi()

        repo(api).saveDraft(
            workspaceId = "ws-1",
            threadKey = "contact:c1",
            body = "Thursday at 2pm works.",
            mediaUrls = listOf("https://www.distronode.test/api/media/m1"),
        )

        val saved = api.draftSaves.single()
        assertEquals("contact:c1", saved.threadKey)
        assertEquals("Thursday at 2pm works.", saved.body)
        assertEquals(listOf("https://www.distronode.test/api/media/m1"), saved.mediaUrls)
    }

    @Test
    fun `deleting a draft is idempotent and reports success`() = runTest {
        val api = FakeDistrictApi()

        val result = repo(api).deleteDraft("ws-1", "contact:c1")

        assertTrue(result is ApiResult.Success)
        assertEquals(listOf("contact:c1"), api.draftDeletes)
    }

    // ── AI generation ────────────────────────────────────────────────────────

    /**
     * ⛔ EXACTLY ONE CALL PER INVOCATION. Every one is a billed Vertex generation capped at 20/min
     * per workspace, so a repository that retried internally would double a tenant's bill with
     * nothing on screen to show it had.
     */
    @Test
    fun `generating a draft makes exactly one billable call`() = runTest {
        val api = FakeDistrictApi()

        val result = repo(api).generateDraft("ws-1", contactId = "c1", address = null)

        assertEquals("Happy to help — when suits you?", (result as ApiResult.Success).value)
        assertEquals(1, api.generations.size)
    }

    /**
     * ⚠️ THE ADDRESS GOES IN `phoneNumber`, which is the server's historical name for the selector
     * and now carries an email too. Renaming it would 400, and the route requires one of the two.
     */
    @Test
    fun `an address-keyed thread generates against phoneNumber`() = runTest {
        val api = FakeDistrictApi()

        repo(api).generateDraft("ws-1", contactId = null, address = "ada@contract.test")

        val request = api.generations.single()
        assertNull(request.contactId)
        assertEquals("ada@contract.test", request.phoneNumber)
    }

    /**
     * ⛔ AN EMPTY GENERATION IS A SUCCESS CARRYING "", NOT A FAILURE. The route answers `""` when
     * the model returns nothing, and the caller must be the one to decide what to do — because the
     * wrong decision here (writing it into the composer) would blank text the operator had typed.
     */
    @Test
    fun `an empty generation succeeds with an empty string rather than failing`() = runTest {
        val api = FakeDistrictApi().apply {
            generateDraftResult = ApiResult.Success(AiDraftResponse(success = true, draft = ""))
        }

        val result = repo(api).generateDraft("ws-1", "c1", null)

        assertEquals("", (result as ApiResult.Success).value)
    }

    @Test
    fun `a refused generation surfaces the server's own text`() = runTest {
        val api = FakeDistrictApi().apply {
            generateDraftResult = ApiResult.Success(
                AiDraftResponse(success = false, error = "Too many draft generations for this workspace."),
            )
        }

        val result = repo(api).generateDraft("ws-1", "c1", null)

        assertEquals(
            "Too many draft generations for this workspace.",
            (result as ApiResult.HttpFailure).message,
        )
    }

    @Test
    fun `a refusal with no sentence is still a refusal, with an empty message`() = runTest {
        val api = FakeDistrictApi().apply {
            generateDraftResult = ApiResult.Success(AiDraftResponse(success = false))
        }

        assertEquals(ApiResult.HttpFailure(status = 200, message = ""), repo(api).generateDraft("ws-1", "c1", null))
    }

    @Test
    fun `a failed upload, draft save or draft delete is passed through unchanged`() = runTest {
        // ⚠️ None of these may report success on a failure: a lost upload would send a message
        // with a dead attachment, and a lost save or delete would show a draft state that is not
        // the one stored.
        val offline = ApiResult.NetworkFailure(IOException("offline"))
        val api = FakeDistrictApi().apply {
            uploadMediaResult = offline
            saveDraftResult = offline
            deleteDraftResult = offline
        }

        assertEquals(offline, repo(api).uploadMedia("ws-1", "photo.png", "image/png", png))
        assertEquals(offline, repo(api).saveDraft("ws-1", "contact:c1", "See you at noon"))
        assertEquals(offline, repo(api).deleteDraft("ws-1", "contact:c1"))
    }
}
