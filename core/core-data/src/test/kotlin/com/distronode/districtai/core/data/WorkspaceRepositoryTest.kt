package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceListResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Active-workspace resolution.
 *
 * ⛔ WHY THIS DESERVES ITS OWN TEST CLASS. Getting this wrong does not crash — it silently
 * shows one tenant's call log under another tenant's name. The precedence rules and, more
 * importantly, the rule that a candidate is only ever adopted if the SERVER still lists it,
 * are the only things standing between a stale local preference and that outcome.
 */
class WorkspaceRepositoryTest {

    /** A fake whose workspace-list answer is [result]. Keeps the apply blocks out of call sites. */
    private fun listing(result: ApiResult<WorkspaceListResponse>) =
        FakeDistrictApi().apply { workspaceListResult = result }

    private fun entry(id: String, name: String = id, role: String = "client") =
        WorkspaceEntry(id = id, name = name, region = "us", role = role)

    private fun repository(
        response: WorkspaceListResponse,
        stored: String? = null,
    ): Pair<WorkspaceRepository, FakeSelectionStore> {
        val store = FakeSelectionStore(stored)
        val repo = WorkspaceRepository(listing(ApiResult.Success(response)), store)
        return repo to store
    }

    private suspend fun state(
        response: WorkspaceListResponse,
        stored: String? = null,
    ): WorkspaceState {
        val (repo, _) = repository(response, stored)
        return (repo.load() as ApiResult.Success).value
    }

    // ── Precedence ───────────────────────────────────────────────────────────

    @Test
    fun `prefers this device's own selection`() = runTest {
        val result = state(
            WorkspaceListResponse(
                success = true,
                workspaces = listOf(entry("ws-a"), entry("ws-b")),
                defaultWorkspaceId = "ws-a",
            ),
            stored = "ws-b",
        )

        // The local choice beats both the account default and index 0 — it is the user's most
        // recent, most specific intent.
        assertEquals("ws-b", result.active?.id)
    }

    @Test
    fun `falls back to the account's stored default`() = runTest {
        val result = state(
            WorkspaceListResponse(
                success = true,
                workspaces = listOf(entry("ws-a"), entry("ws-b")),
                defaultWorkspaceId = "ws-b",
            ),
        )

        // So a fresh install lands where the browser would.
        assertEquals("ws-b", result.active?.id)
    }

    @Test
    fun `falls back to index 0, which is the server's own answer`() = runTest {
        val result = state(
            WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a"), entry("ws-b"))),
        )

        // ⚠️ Index 0 is not arbitrary: the server has already applied owned-first ordering, so
        // a workspace the user OWNS sorts before a shared org they merely belong to.
        assertEquals("ws-a", result.active?.id)
    }

    // ── Never adopt an id the server did not list ────────────────────────────

    @Test
    fun `ignores a stored selection the server no longer lists`() = runTest {
        // Removed from the workspace, or its subscription lapsed. Sending it would 403.
        val result = state(
            WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a"))),
            stored = "ws-gone",
        )

        assertEquals("ws-a", result.active?.id)
    }

    @Test
    fun `ignores a stored default the server no longer lists`() = runTest {
        // ⚠️ The server echoes defaultWorkspaceId WITHOUT checking it against the list, so
        // this genuinely happens rather than being defensive coding.
        val result = state(
            WorkspaceListResponse(
                success = true,
                workspaces = listOf(entry("ws-a")),
                defaultWorkspaceId = "ws-lapsed",
            ),
        )

        assertEquals("ws-a", result.active?.id)
    }

    @Test
    fun `forgets a dead selection so it is not re-read every launch`() = runTest {
        val (repo, store) = repository(
            WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a"))),
            stored = "ws-gone",
        )

        repo.load()

        assertNull(store.selectedWorkspaceId())
    }

    @Test
    fun `keeps a selection that is merely missing from an INCOMPLETE list`() = runTest {
        // ⛔ THE IMPORTANT HALF OF THE PREVIOUS TEST. A degraded region means the list is a
        // subset of the truth, so an absent id proves nothing — discarding the user's choice
        // because a region was briefly unreachable would lose it permanently.
        val (repo, store) = repository(
            WorkspaceListResponse(
                success = true,
                workspaces = listOf(entry("ws-a")),
                degradedRegions = listOf("eu"),
            ),
            stored = "ws-in-eu",
        )

        repo.load()

        assertEquals("ws-in-eu", store.selectedWorkspaceId())
    }

    // ── Distinguishing the three kinds of "nothing" ──────────────────────────

    @Test
    fun `an empty account is reported as having no workspaces`() = runTest {
        val result = state(WorkspaceListResponse(success = true))

        assertTrue(result.hasNoWorkspaces)
        assertFalse(result.isBillingBlocked)
        assertFalse(result.isPartial)
    }

    @Test
    fun `a lapsed account is billing-blocked, not empty`() = runTest {
        // ⛔ A DIFFERENT SCREEN. "You have no workspaces" is wrong and alarming when the real
        // answer is "your subscription lapsed" — and inactiveCount is the only thing that
        // tells them apart, because the server withholds unpaid workspaces to match the web.
        val result = state(WorkspaceListResponse(success = true, inactiveCount = 2))

        assertFalse(result.hasNoWorkspaces)
        assertTrue(result.isBillingBlocked)
    }

    @Test
    fun `a partial list is flagged rather than presented as complete`() = runTest {
        val result = state(
            WorkspaceListResponse(
                success = true,
                workspaces = listOf(entry("ws-a")),
                degradedRegions = listOf("ca", "apac"),
            ),
        )

        assertTrue(result.isPartial)
        assertFalse(result.hasNoWorkspaces)
        assertEquals(listOf("ca", "apac"), result.degradedRegions)
    }

    // ── Failures pass through ────────────────────────────────────────────────

    @Test
    fun `passes a degraded-regions failure through unchanged`() = runTest {
        // ⛔ MUST NOT become an empty WorkspaceState. Folding it into "no workspaces" is
        // exactly the conflation that sent a paying customer to checkout on the web.
        val failure = ApiResult.RegionsDegraded("unreachable", listOf("eu"))
        val repo = WorkspaceRepository(listing(failure), FakeSelectionStore(null))

        assertEquals(failure, repo.load())
    }

    @Test
    fun `passes an unauthorized failure through unchanged`() = runTest {
        val failure = ApiResult.Unauthorized(reason = null)
        val repo = WorkspaceRepository(listing(failure), FakeSelectionStore(null))

        assertEquals(failure, repo.load())
    }

    // ── Selection ────────────────────────────────────────────────────────────

    @Test
    fun `select persists and clearSelection forgets`() = runTest {
        val (repo, store) = repository(WorkspaceListResponse(success = true))

        repo.select("ws-chosen")
        assertEquals("ws-chosen", store.selectedWorkspaceId())

        repo.clearSelection()
        assertNull(store.selectedWorkspaceId())
    }
}
