package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.model.OverviewMetrics
import com.distronode.districtai.core.model.OverviewResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewRepositoryTest {

    private fun call(
        id: String = "c1",
        callerName: String = "Ada",
        from: String? = "+14165550142",
        direction: String? = "inbound",
        status: String = "completed",
        transferStatus: String? = null,
    ) = CallSummary(
        id = id,
        type = "inbound",
        number = callerName,
        status = status,
        duration = "1m 5s",
        time = "Aug 15, 02:30 PM",
        aiSummary = "",
        hasTranscript = false,
        callerName = callerName,
        from = from,
        direction = direction,
        summary = "",
        createdAt = "2026-08-15T14:30:00.000Z",
        transferStatus = transferStatus,
    )

    private suspend fun load(response: OverviewResponse): Overview {
        val repo = OverviewRepository(FakeDistrictApi().apply { overviewResult = ApiResult.Success(response) })
        return (repo.load("ws-1") as ApiResult.Success).value
    }

    @Test
    fun `sends the workspace it was given`() = runTest {
        // ⛔ The single most consequential line in this class. Omitting workspaceId is not an
        // error — the server falls back to index 0 of its own listing, which cannot know what
        // the user selected in the app, so the screen would quietly describe another tenant.
        val api = FakeDistrictApi()
        OverviewRepository(api).load("ws-selected")

        assertEquals(listOf("ws-selected"), api.requestedWorkspaceIds)
    }

    @Test
    fun `parses the effective role`() = runTest {
        val result = load(OverviewResponse(success = true, role = "agency"))

        assertEquals(WorkspaceRole.AGENCY, result.role)
    }

    @Test
    fun `fails closed on an unmodelled role`() = runTest {
        // ⛔ Null, not CLIENT. The server's own fallback to "client" happens only after it has
        // CONFIRMED a membership; here an unrecognised string means the role could not be
        // established, and assuming the mutating role would offer actions that all 403.
        val result = load(OverviewResponse(success = true, role = "superuser"))

        assertNull(result.role)
        assertFalse(result.role?.canMutate ?: false)
    }

    @Test
    fun `keeps the server's duration label rather than reformatting`() = runTest {
        // ⛔ Two duration formats ship and they disagree on the same input: the tile omits a
        // zero minutes component ("45s"), a call row always emits one ("0m 45s"). The server
        // sends the tile's own label so this client cannot pick the wrong one.
        val result = load(
            OverviewResponse(
                success = true,
                metrics = OverviewMetrics(avgDuration = 45),
                avgDurationLabel = "45s",
            ),
        )

        assertEquals("45s", result.avgDurationLabel)
        assertEquals(45, result.metrics.avgDuration)
    }

    @Test
    fun `treats a genuinely live call as live`() = runTest {
        val result = load(
            OverviewResponse(success = true, recentCalls = listOf(call(status = "in-progress"))),
        )

        assertTrue(result.recentCalls.single().live)
    }

    @Test
    fun `does not resurrect a stale call as live`() = runTest {
        // ⚠️ The server has ALREADY downgraded a call whose terminal webhook was lost to
        // "no-answer", so liveness is read off the display status and never recomputed. Doing
        // the arithmetic here again is how a day-old stuck row gets a pulsing Live badge
        // forever, which is a bug this product has already had.
        val result = load(
            OverviewResponse(success = true, recentCalls = listOf(call(status = "no-answer"))),
        )

        assertFalse(result.recentCalls.single().live)
    }

    @Test
    fun `renders a missing caller id as absent rather than the literal Unknown`() = runTest {
        // The API sends the string "Unknown"; the browser shows an italic "No caller ID"
        // placeholder. Passing "Unknown" through would print it as if it were a name.
        val result = load(
            OverviewResponse(
                success = true,
                recentCalls = listOf(call(callerName = "Unknown", from = null)),
            ),
        )

        assertNull(result.recentCalls.single().displayName)
    }

    @Test
    fun `falls back to the raw number when the name is unresolved`() = runTest {
        val result = load(
            OverviewResponse(
                success = true,
                recentCalls = listOf(call(callerName = "Unknown", from = "+14165550159")),
            ),
        )

        assertEquals("+14165550159", result.recentCalls.single().displayName)
    }

    @Test
    fun `flags direction and transfer outcomes`() = runTest {
        val result = load(
            OverviewResponse(
                success = true,
                recentCalls = listOf(
                    call(id = "a", direction = "outbound", transferStatus = "success"),
                    call(id = "b", direction = "inbound", transferStatus = "failed"),
                ),
            ),
        )

        val (first, second) = result.recentCalls
        assertTrue(first.outbound)
        assertTrue(first.transferred)
        assertFalse(second.outbound)
        assertTrue(second.transferFailed)
    }

    @Test
    fun `refuses to return an overview for a different workspace than was asked for`() = runTest {
        // ⛔ THIS USED TO BE A SUCCESS THE CALLER HAD TO INTERROGATE, VIA `Overview.disagreesWith`,
        // AND NO CALLER DID — so the app drew another tenant's numbers under the selected
        // workspace's name. The selection drifts with no user action whenever a membership is
        // removed or a subscription lapses mid-session. Detected in the repository so it cannot be
        // bypassed by a caller forgetting to ask.
        val repo = OverviewRepository(
            FakeDistrictApi().apply {
                overviewResult = ApiResult.Success(
                    OverviewResponse(success = true, workspaceId = "ws-other"),
                )
            },
        )

        val result = repo.load("ws-1")

        assertTrue("expected HttpFailure, got $result", result is ApiResult.HttpFailure)
        // ⚠️ Asserted on the CODE, not on the synthetic 409 — that status never came off the wire
        // and exists only because a dedicated ApiResult case would break an exhaustive `when` in
        // the ui module.
        assertEquals(
            OverviewRepository.CODE_WORKSPACE_MISMATCH,
            (result as ApiResult.HttpFailure).code,
        )
    }

    @Test
    fun `accepts the overview when the server confirms the workspace`() = runTest {
        val result = load(OverviewResponse(success = true, workspaceId = "ws-1"))

        assertEquals("ws-1", result.workspaceId)
    }

    @Test
    fun `treats an absent workspace id as agreement rather than drift`() = runTest {
        // ⚠️ The server only ECHOES the id; older deployments and the committed fixtures omit it.
        // Failing closed on absent would turn every load into a mismatch instead of only the
        // drifted ones — a worse outage than the bug being fixed.
        val result = load(OverviewResponse(success = true, workspaceId = null))

        assertNull(result.workspaceId)
    }

    @Test
    fun `rejects a 200 that does not affirm success`() = runTest {
        // ⛔ FOUR CONFIDENT ZEROS. Every field of OverviewResponse defaults, so an HTTP 200 whose
        // body is `{}` decoded cleanly and rendered as a real, quiet, all-zero overview.
        // OverviewMetrics' own KDoc warns about this exact shape and tells callers to branch on
        // the result type — which they do; the result type could not tell the two apart until the
        // envelope was asserted.
        val repo = OverviewRepository(
            FakeDistrictApi().apply {
                overviewResult = ApiResult.Success(OverviewResponse(success = false))
            },
        )

        assertTrue(repo.load("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `passes a failure through unchanged`() = runTest {
        val failure = ApiResult.Forbidden("Forbidden: Insufficient workspace privileges")
        val repo = OverviewRepository(FakeDistrictApi().apply { overviewResult = failure })

        assertEquals(failure, repo.load("ws-1"))
    }
}
