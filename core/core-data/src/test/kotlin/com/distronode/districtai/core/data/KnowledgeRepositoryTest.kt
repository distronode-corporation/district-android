package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.KnowledgeCreateRequest
import com.distronode.districtai.core.model.KnowledgeCreateResponse
import com.distronode.districtai.core.model.KnowledgeDeleteResponse
import com.distronode.districtai.core.model.KnowledgeDocument
import com.distronode.districtai.core.model.KnowledgeListResponse
import com.distronode.districtai.core.model.KnowledgeModeResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The knowledge base's data layer.
 *
 * ⛔ WHAT THIS FILE PROTECTS IS DIFFERENT FROM ITS SIBLING'S. `WorkspaceConfigRepositoryTest`
 * guards against a wholesale replace built from nothing; there is no such array here. What can go
 * wrong on this surface is (1) a structurally empty 200 read as "this workspace has no documents",
 * which reads to an operator as their uploads having vanished, (2) the DELETE reaching the server
 * with the wrong parameter name, which makes deletion silently impossible, and (3) the mode being
 * reported as the value that was ASKED for rather than the value the server stored.
 */
class KnowledgeRepositoryTest {

    private val documents = listOf(
        KnowledgeDocument(id = "doc-1", title = "Refund policy", status = "ready", chunkCount = 4),
        KnowledgeDocument(id = "doc-2", title = "Service area", status = "processing"),
    )

    private fun api() = FakeDistrictApi().apply {
        knowledgeListResult = ApiResult.Success(
            KnowledgeListResponse(success = true, documents = documents),
        )
    }

    // ── The list ─────────────────────────────────────────────────────────────

    @Test
    fun `documents returns the list and sends the workspace it was asked for`() = runTest {
        val api = api()

        val result = KnowledgeRepository(api).documents("ws-1")

        assertEquals(documents, (result as ApiResult.Success).value)
        assertEquals(listOf("ws-1"), api.knowledgeListRequests)
    }

    @Test
    fun `an empty list is a real answer rather than a failure`() = runTest {
        // ⚠️ A workspace that has uploaded nothing is the ordinary first-day state. Reporting it as
        // a failure would put a retry in front of an empty state that is simply true.
        val api = FakeDistrictApi().apply {
            knowledgeListResult = ApiResult.Success(KnowledgeListResponse(success = true))
        }

        val result = KnowledgeRepository(api).documents("ws-1")

        assertEquals(emptyList<KnowledgeDocument>(), (result as ApiResult.Success).value)
    }

    @Test
    fun `a 200 that does not affirm success is drift, not an empty knowledge base`() = runTest {
        // ⛔ THE ONE THAT LIES. Every field of the response DTO defaults, so `{}` decodes into a
        // well-formed "no documents" — which an operator reads as their uploads having been lost.
        val api = FakeDistrictApi().apply {
            knowledgeListResult = ApiResult.Success(KnowledgeListResponse())
        }

        assertTrue(KnowledgeRepository(api).documents("ws-1") is ApiResult.DecodeFailure)
    }

    // ── The create ───────────────────────────────────────────────────────────

    @Test
    fun `addDocument sends the request whole and returns the echoed row`() = runTest {
        val api = api().apply {
            knowledgeCreateResult = ApiResult.Success(
                KnowledgeCreateResponse(
                    success = true,
                    document = KnowledgeDocument(id = "doc-new", title = "Holiday hours"),
                ),
            )
        }
        val request = KnowledgeCreateRequest(
            workspaceId = "ws-1",
            title = "Holiday hours",
            content = "We are closed on the 25th.",
        )

        val result = KnowledgeRepository(api).addDocument(request)

        // ⛔ ONE ENTRY HERE IS ONE EMBEDDING RUN IN PRODUCTION, so a count is a claim about spend
        // rather than about call plumbing.
        assertEquals(listOf(request), api.knowledgeCreates)
        assertEquals("doc-new", (result as ApiResult.Success).value?.id)
    }

    @Test
    fun `a failed create is reported and is NOT retried`() = runTest {
        // ⛔ NOT IDEMPOTENT. A create that timed out may well have embedded and persisted; a
        // repository that retried would pay twice for a duplicate document. Asserted as "exactly
        // one attempt reached the API".
        val api = api().apply {
            knowledgeCreateResult = ApiResult.HttpFailure(429, "Too many knowledge uploads")
        }

        val result = KnowledgeRepository(api).addDocument(
            KnowledgeCreateRequest(workspaceId = "ws-1", title = "t", content = "c"),
        )

        assertEquals(429, (result as ApiResult.HttpFailure).status)
        assertEquals(1, api.knowledgeCreates.size)
    }

    // ── The delete ───────────────────────────────────────────────────────────

    @Test
    fun `deleteDocument passes the workspace and the document id through`() = runTest {
        // ⛔ THE PARAMETER SPELLING IS `documentId` AND THE ROUTE 400s ON ANYTHING ELSE. That
        // spelling is asserted where it is actually built — at the HTTP layer — but the pair
        // arriving in the right ORDER is this layer's job, and swapping them would delete nothing
        // while answering success.
        val api = api()

        val result = KnowledgeRepository(api).deleteDocument("ws-1", "doc-2")

        assertEquals(listOf("ws-1" to "doc-2"), api.knowledgeDeletes)
        assertTrue(result is ApiResult.Success)
    }

    @Test
    fun `a delete that matched nothing still reports success, which is the route's contract`() =
        runTest {
            // ⛔ `deleteMany` SCOPED `{id, workspaceId}` WITH ITS COUNT NEVER READ, so another
            // tenant's id is indistinguishable from a real delete. Deliberate — and it is why the
            // caller re-reads the list instead of dropping the row on the strength of this.
            val api = api().apply {
                knowledgeDeleteResult = ApiResult.Success(KnowledgeDeleteResponse(success = true))
            }

            assertTrue(KnowledgeRepository(api).deleteDocument("ws-1", "not-ours") is ApiResult.Success)
        }

    // ── The mode ─────────────────────────────────────────────────────────────

    @Test
    fun `mode reads the top-level key`() = runTest {
        val api = api().apply {
            knowledgeModeResult = ApiResult.Success(
                KnowledgeModeResponse(success = true, mode = "linked"),
            )
        }

        assertEquals("linked", (KnowledgeRepository(api).mode("ws-1") as ApiResult.Success).value)
    }

    @Test
    fun `setMode adopts the SERVER's echo rather than the value it was asked to store`() = runTest {
        // ⛔ THE ROUTE RE-READS THROUGH ITS OWN SANITISER BEFORE ANSWERING, so the echo is what a
        // later read will see. Returning the requested value would report a mode nobody stored if
        // the sanitiser ever disagreed — on a setting that decides whether a customer's questions
        // leave their region.
        val api = api().apply {
            saveKnowledgeModeResult = ApiResult.Success(
                KnowledgeModeResponse(success = true, mode = "internal"),
            )
        }

        val result = KnowledgeRepository(api).setMode("ws-1", "linked")

        assertEquals("linked", api.knowledgeModePatches.single().mode)
        assertEquals("internal", (result as ApiResult.Success).value)
    }

    @Test
    fun `a mode response that does not affirm success is drift rather than the default`() =
        runTest {
            // ⛔ A `{}` BODY WOULD DECODE AS mode=null, which the screen must not render as
            // "internal". Failing here is what lets the UI withhold the selector instead.
            val api = api().apply {
                knowledgeModeResult = ApiResult.Success(KnowledgeModeResponse())
            }

            assertTrue(KnowledgeRepository(api).mode("ws-1") is ApiResult.DecodeFailure)
        }

    @Test
    fun `an affirmed envelope with no mode is carried as null rather than defaulted`() = runTest {
        // ⚠️ Different from the case above: the envelope IS affirmed, so this is a server that said
        // success and sent no mode. Null is carried up so the UI can say "we could not read it"
        // instead of asserting a residency choice nobody made.
        val api = api().apply {
            knowledgeModeResult = ApiResult.Success(KnowledgeModeResponse(success = true))
        }

        assertNull((KnowledgeRepository(api).mode("ws-1") as ApiResult.Success).value)
    }
}
