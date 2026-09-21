package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.ClearIntelResponse
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactDetailResponse
import com.distronode.districtai.core.model.EnrichResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contact dossier: two writes that are not interchangeable, and the poll that is the only way
 * a finished dossier ever reaches a client.
 *
 * ⛔ ONE OF THESE CALLS SPENDS MONEY AND THE OTHER DESTROYS DATA, so the assertions that matter
 * most are about what was NOT sent: an enrichment issued once rather than twice, and a poll that
 * stops rather than re-reading a contact nobody is watching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsDgiRepositoryTest {

    private fun contactRow(dgiStatus: String? = null) = Contact(
        id = "c1",
        workspaceId = "ws-1",
        name = "Ada",
        dgiStatus = dgiStatus,
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private fun api(dgiStatus: String? = null) = FakeDistrictApi().apply {
        contactResult = ApiResult.Success(
            ContactDetailResponse(success = true, contact = contactRow(dgiStatus)),
        )
    }

    // ── Enrich ───────────────────────────────────────────────────────────────

    @Test
    fun `an enrichment is requested exactly once, for the named contact`() = runTest {
        val api = api()

        val result = ContactsRepository(api).enrich("ws-1", "c1")

        assertTrue(result is ApiResult.Success)
        // ⛔ ONE ENTRY IS ONE EXTERNAL CRAWL AND ONE LLM RUN. The size of this list is a claim
        // about real spend, not about state.
        assertEquals(1, api.enrichRequests.size)
        assertEquals("ws-1", api.enrichRequests.single().workspaceId)
        assertEquals("c1", api.enrichRequests.single().contactId)
    }

    @Test
    fun `a 200 that does not affirm success is contract drift, not a queued job`() = runTest {
        // ⛔ THE EMPTY-BODY CASE. Every field of EnrichResponse has a default, so `{}` decodes
        // into a well-formed response — and reporting that as "enrichment scheduled" would leave
        // the screen polling for a dossier nothing is building.
        val api = api().apply { enrichResult = ApiResult.Success(EnrichResponse()) }

        val result = ContactsRepository(api).enrich("ws-1", "c1")

        assertTrue("must be reported as drift", result is ApiResult.DecodeFailure)
    }

    @Test
    fun `the workspace opt-in refusal is passed through untouched`() = runTest {
        // ⛔ A 403 HERE IS USUALLY NOT ABOUT THE CALLER'S ROLE. The workspace-level enrichment
        // opt-in is off, and the server's own message names the settings page that turns it on.
        // This layer must not translate it — a generic "you do not have permission" sends the
        // operator looking at their account for a switch that lives on the workspace.
        val message = "Lead enrichment is off for this workspace. Turn it on in Settings → " +
            "AI Agent → Skills & Integrations to enrich contacts with external business data."
        val api = api().apply { enrichResult = ApiResult.Forbidden(message) }

        val result = ContactsRepository(api).enrich("ws-1", "c1")

        assertEquals(message, (result as ApiResult.Forbidden).message)
    }

    @Test
    fun `a rate limit is surfaced rather than retried`() = runTest {
        // ⛔ NOTHING IN THIS CLIENT MAY RETRY AN ENRICHMENT. A 429 that was silently re-sent
        // would spend a second model run on the same contact, and the server's limiter is
        // Redis-backed and FAIL-OPEN so it would not stop the second one either.
        val api = api().apply {
            enrichResult = ApiResult.RateLimited("Too many enrichment requests for this workspace.")
        }

        val result = ContactsRepository(api).enrich("ws-1", "c1")

        assertTrue(result is ApiResult.RateLimited)
        assertEquals(1, api.enrichRequests.size)
    }

    // ── Clear ────────────────────────────────────────────────────────────────

    @Test
    fun `clearing a dossier asserts the envelope rather than assuming a 200 means cleared`() =
        runTest {
            val api = api()

            assertTrue(ContactsRepository(api).clearIntel("ws-1", "c1") is ApiResult.Success)
            assertEquals(1, api.clearIntelRequests.size)
        }

    @Test
    fun `an empty body is not a successful clear`() = runTest {
        // ⛔ THE FLAG IS THE ENTIRE PAYLOAD OF THIS RESPONSE, which makes it the DTO most exposed
        // to an empty 200 — there is no other field whose absence would give the lie away.
        val api = api().apply { clearIntelResult = ApiResult.Success(ClearIntelResponse()) }

        assertTrue(
            ContactsRepository(api).clearIntel("ws-1", "c1") is ApiResult.DecodeFailure,
        )
    }

    @Test
    fun `a 404 on a clear is NOT folded into success`() = runTest {
        // ⚠️ THE OPPOSITE CALL FROM `delete`, DELIBERATELY. There, "the row was already gone" is
        // the outcome the caller wanted. Here it means the contact could not be found at all, and
        // reporting "dossier cleared" for a contact nobody touched is a claim about data.
        val api = api().apply { clearIntelResult = ApiResult.NotFound("Contact c1 not found.") }

        assertTrue(ContactsRepository(api).clearIntel("ws-1", "c1") is ApiResult.NotFound)
    }

    // ── The poll ─────────────────────────────────────────────────────────────

    @Test
    fun `the poll stops as soon as the dossier reaches a terminal status`() = runTest {
        // ⚠️ The fake answers "complete" on the first read, so one emission is the whole flow.
        val api = api(dgiStatus = "complete")

        val emissions = ContactsRepository(api).dossierUpdates("ws-1", "c1").toList()

        assertEquals(1, emissions.size)
        assertEquals(1, api.contactRequestCount)
    }

    @Test
    fun `the poll keeps running through crawling and synthesizing, not just pending`() = runTest {
        // ⛔ THE REGRESSION THIS FILE EXISTS TO CATCH. The pipeline advances
        // pending -> crawling -> synthesizing -> complete, so a terminal check written against
        // "pending" alone quits the moment the crawler starts — leaving the screen showing a
        // stale dossier for a job still running, and re-offering an enrich button that would buy
        // a second model run for a contact already being enriched.
        val statuses = listOf("crawling", "synthesizing", "complete")
        var index = 0
        val api = object : FakeDistrictApi() {
            override suspend fun contact(
                workspaceId: String,
                contactId: String,
            ): ApiResult<ContactDetailResponse> {
                val status = statuses[index.coerceAtMost(statuses.lastIndex)]
                index += 1
                return ApiResult.Success(
                    ContactDetailResponse(success = true, contact = contactRow(status)),
                )
            }
        }

        val emissions = ContactsRepository(api).dossierUpdates("ws-1", "c1").toList()

        assertEquals("must poll through every in-flight status", 3, emissions.size)
        assertEquals(
            listOf("crawling", "synthesizing", "complete"),
            emissions.map { (it as ApiResult.Success).value.dgiStatus },
        )
    }

    @Test
    fun `a null status ends the poll rather than spinning forever`() = runTest {
        // ⛔ AFTER `clear-intel` THE STATUS IS NULL AND NOTHING IS QUEUED. Treating null as
        // "pending" would poll a job that does not exist, on a route that fans out to a regional
        // database, until the screen is closed.
        val api = api(dgiStatus = null)

        assertEquals(1, ContactsRepository(api).dossierUpdates("ws-1", "c1").toList().size)
    }

    @Test
    fun `the poll terminates on a failure instead of hammering a dead session`() = runTest {
        // ⛔ AN Unauthorized IS NOT WORTH RETRYING EVERY 2.5 SECONDS. The session is gone and
        // waiting cannot fix it; the screen keeps the contact it already has and offers a reload.
        val api = api(dgiStatus = "pending").apply {
            contactResult = ApiResult.Unauthorized(reason = null)
        }

        val emissions = ContactsRepository(api).dossierUpdates("ws-1", "c1").toList()

        assertEquals(1, emissions.size)
        assertTrue(emissions.single() is ApiResult.Unauthorized)
    }

    @Test
    fun `the first read happens after the interval, never immediately`() = runTest {
        // ⚠️ The caller has just read this contact — that is how it knows an enrichment is in
        // flight — so a leading emission would be a duplicate request answering a question
        // already answered. `runTest`'s virtual clock is what makes this observable at all.
        val api = api(dgiStatus = "complete")
        val started = testScheduler.currentTime

        ContactsRepository(api).dossierUpdates("ws-1", "c1", intervalMillis = 2_500).toList()

        assertEquals(2_500L, testScheduler.currentTime - started)
    }
}
