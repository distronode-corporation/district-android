package com.distronode.districtai.ui.contacts

import com.distronode.districtai.core.data.ContactsRepository
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactDetailResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The dossier half of the contact detail screen: an enrichment that costs money, a clear that
 * destroys data, and the poll that is the only way a finished dossier ever reaches the screen.
 *
 * ⛔ THE ASSERTIONS THAT MATTER MOST ARE ABOUT WHAT WAS NOT SENT. `enrichRequests.size` is a claim
 * about real spend — one entry is one external crawl plus one LLM synthesis — so "a viewer never
 * reached the endpoint", "a running crawl bought nothing" and "the poll did not re-trigger it" are
 * all assertions about that list rather than about state.
 *
 * ⛔ AND EVERY TEST HERE USES `runCurrent()` PLUS AN EXPLICIT `advanceTimeBy`, NEVER
 * `advanceUntilIdle()`, WHENEVER A POLL IS RUNNING. `advanceUntilIdle` advances until no task
 * remains SCHEDULED, and a poll over a contact that never settles is an infinite chain of delayed
 * tasks — so the convenient call hangs the test rather than failing it. That is also why each test
 * ends by settling the contact: the loop has to be given a terminal status to stop on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactDetailDgiViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun contactRow(dgiStatus: String? = null) = Contact(
        id = "c1",
        workspaceId = "ws-1",
        name = "Ada",
        dgiStatus = dgiStatus,
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private fun api(dgiStatus: String? = null) = TestDistrictApi().apply {
        answerWith(dgiStatus)
    }

    private fun TestDistrictApi.answerWith(dgiStatus: String?) {
        contactResult = ApiResult.Success(
            ContactDetailResponse(success = true, contact = contactRow(dgiStatus)),
        )
    }

    private fun viewModel(
        api: TestDistrictApi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = ContactDetailViewModel(
        ContactsRepository(api),
        workspaceId = "ws-1",
        contactId = "c1",
        role = role,
    )

    private fun content(vm: ContactDetailViewModel) =
        vm.state.value as ContactDetailUiState.Content

    /** One poll cycle: cross the interval, then let the read it triggers run. */
    private fun TestScope.tick() {
        advanceTimeBy(POLL_INTERVAL_MS + 1)
        runCurrent()
    }

    /** End a test with the poll stopped, so no delayed task outlives it. */
    private fun TestScope.settle(api: TestDistrictApi) {
        api.answerWith("complete")
        tick()
    }

    // ── Enrich ───────────────────────────────────────────────────────────────

    @Test
    fun `enrich sends one request and re-reads the stamped contact`() = runTest {
        val api = api()
        val vm = viewModel(api)
        runCurrent()

        // ⚠️ The server stamps "pending" BEFORE responding, so the re-read is what puts the badge
        // on screen. The client never invents the status — which is the difference between "the
        // job is queued" and "the button was tapped".
        api.answerWith("pending")
        vm.enrich()
        runCurrent()

        assertEquals(1, api.enrichRequests.size)
        assertEquals("ws-1", api.enrichRequests.single().workspaceId)
        assertEquals("pending", content(vm).contact.dgiStatus)
        assertTrue(content(vm).contact.dgiInProgress)

        settle(api)
    }

    @Test
    fun `a viewer never reaches the endpoint`() = runTest {
        // ⛔ The server excludes `viewer`, so this request could only ever 403. The gate is an
        // affordance rather than the security boundary — but a request the app knows will fail is
        // one it should not make.
        val api = api()
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)
        runCurrent()

        vm.enrich()
        runCurrent()

        assertTrue(api.enrichRequests.isEmpty())
    }

    @Test
    fun `a contact already being enriched cannot be enriched again`() = runTest {
        // ⛔ ONE IMPATIENT TAP WOULD BUY A SECOND CRAWL AND A SECOND MODEL RUN. The endpoint is
        // not idempotent, and the server's rate limit is Redis-backed and FAIL-OPEN, so nothing
        // behind this guard would stop the second one either.
        val api = api(dgiStatus = "crawling")
        val vm = viewModel(api)
        runCurrent()

        vm.enrich()
        runCurrent()

        assertTrue("a running crawl is not offerable", api.enrichRequests.isEmpty())

        settle(api)
    }

    @Test
    fun `the workspace opt-in refusal is shown verbatim, and nothing is retried`() = runTest {
        // ⛔ A 403 HERE IS USUALLY THE WORKSPACE OPT-IN RATHER THAN THE CALLER'S ROLE, and the
        // server's message is the only thing that names the settings page the operator has to
        // visit. Replacing it with "you do not have permission" leaves them holding a button that
        // fails and no route to the switch.
        val message = "Lead enrichment is off for this workspace. Turn it on in Settings → " +
            "AI Agent → Skills & Integrations to enrich contacts with external business data."
        val api = api().apply { enrichResult = ApiResult.Forbidden(message) }
        val vm = viewModel(api)
        runCurrent()

        vm.enrich()
        runCurrent()

        assertEquals(message, content(vm).mutationFailure?.message?.literalOrNull)
        // ⛔ NO AUTOMATIC RETRY, EVER: an enrichment that failed may still have been queued.
        assertEquals(1, api.enrichRequests.size)
        // ⚠️ The contact stays on screen — only the action failed.
        assertEquals("Ada", content(vm).contact.name)
        assertTrue("the write is no longer in flight", !content(vm).saving)
    }

    // ── Clear ────────────────────────────────────────────────────────────────

    @Test
    fun `clearing re-reads, and the cleared contact is offerable again`() = runTest {
        val api = api(dgiStatus = "complete")
        val vm = viewModel(api)
        runCurrent()

        // ⛔ After a clear the status is NULL with nothing queued — which is exactly what
        // re-enables the enrich control. A client that optimistically showed "pending" here would
        // spin forever against a job that does not exist.
        api.answerWith(null)
        vm.clearIntel()
        runCurrent()

        assertEquals(1, api.clearIntelRequests.size)
        assertNull(content(vm).contact.dgiStatus)
        assertTrue(content(vm).contact.dgiOfferable)
        assertTrue("null is not a job in progress", !content(vm).contact.dgiInProgress)
    }

    @Test
    fun `a viewer never reaches the clear endpoint either`() = runTest {
        val api = api(dgiStatus = "complete")
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)
        runCurrent()

        vm.clearIntel()
        runCurrent()

        assertTrue(api.clearIntelRequests.isEmpty())
    }

    // ── The poll ─────────────────────────────────────────────────────────────

    @Test
    fun `an in-flight contact is re-read on the interval until it settles, then stops`() = runTest {
        val api = api(dgiStatus = "pending")
        val vm = viewModel(api)
        runCurrent()
        val afterLoad = api.contactRequestCount

        // ⛔ "crawling" AND "synthesizing" ARE STILL IN FLIGHT. A terminal check written against
        // "pending" alone would quit here, leaving a stale dossier on screen for a job still
        // running — and re-offering an enrich button that would buy a second model run.
        api.answerWith("crawling")
        tick()
        assertEquals("crawling", content(vm).contact.dgiStatus)
        assertEquals(afterLoad + 1, api.contactRequestCount)

        api.answerWith("synthesizing")
        tick()
        assertEquals("synthesizing", content(vm).contact.dgiStatus)
        assertEquals(afterLoad + 2, api.contactRequestCount)

        api.answerWith("complete")
        tick()
        assertEquals("complete", content(vm).contact.dgiStatus)

        // ⛔ AND IT STOPS. Two further intervals must produce no reads at all — a poll that ran on
        // past a terminal status would keep a regional-database query going for as long as the
        // screen stayed open.
        val afterSettle = api.contactRequestCount
        tick()
        tick()
        assertEquals(afterSettle, api.contactRequestCount)
    }

    @Test
    fun `a settled contact is never polled at all`() = runTest {
        val api = api(dgiStatus = "complete")
        val vm = viewModel(api)
        runCurrent()
        val afterLoad = api.contactRequestCount

        tick()
        tick()
        tick()

        assertEquals(afterLoad, api.contactRequestCount)
        assertEquals("complete", content(vm).contact.dgiStatus)
    }

    @Test
    fun `polling starts after an enrich, and never re-triggers the billable write`() = runTest {
        val api = api()
        val vm = viewModel(api)
        runCurrent()

        api.answerWith("pending")
        vm.enrich()
        runCurrent()
        val afterEnrich = api.contactRequestCount

        api.answerWith("complete")
        tick()

        assertEquals("the poll re-read the contact", afterEnrich + 1, api.contactRequestCount)
        assertEquals("complete", content(vm).contact.dgiStatus)
        // ⛔ THE POLL IS A READ. It must never re-issue the write it is watching.
        assertEquals(1, api.enrichRequests.size)
    }

    @Test
    fun `a poll landing on top of a failed mutation does not erase the failure`() = runTest {
        // ⚠️ A background read is not the outcome of anything the operator did. It replaces the
        // CONTACT and nothing else — clearing a message they have not read yet would make the
        // failure vanish under them for no visible reason.
        val api = api(dgiStatus = "pending").apply {
            mutationResult = ApiResult.RateLimited("Slow down.")
        }
        val vm = viewModel(api)
        runCurrent()

        vm.rename("Grace")
        runCurrent()
        val failure = content(vm).mutationFailure
        assertTrue("the rename must have failed for this test to mean anything", failure != null)

        api.answerWith("complete")
        tick()

        assertEquals("complete", content(vm).contact.dgiStatus)
        assertEquals(failure, content(vm).mutationFailure)
    }

    private companion object {
        /** Mirrors ContactsRepository.DOSSIER_POLL_INTERVAL_MS, which is the web console's own. */
        const val POLL_INTERVAL_MS = 2_500L
    }
}
