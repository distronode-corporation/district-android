package com.distronode.districtai.ui.scheduling.admin

import com.distronode.districtai.ApiEnvironment
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminError
import com.distronode.districtai.core.data.SchedulingAdminFailureCode
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.ui.resourceIdOrNull
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared state of every scheduling section: the failure sentences, the access read, and the
 * confirm-then-remove flow.
 */
class SchedulingAdminStateTest {

    @Test
    fun `every error has the reference's sentence and retries only where retrying could help`() {
        val expected = listOf(
            SchedulingAdminError.Transport to (R.string.scheduling_admin_failure_offline to true),
            SchedulingAdminError.Decoding to (R.string.scheduling_admin_failure_stale_build to false),
            SchedulingAdminError.InvalidParams to (R.string.scheduling_admin_failure_unknown to false),
            SchedulingAdminError.Unavailable to (R.string.scheduling_admin_failure_unavailable to true),
            SchedulingAdminError.Refused(SchedulingAdminFailureCode.SLOT_TAKEN) to
                (R.string.scheduling_admin_failure_slot_taken to false),
            SchedulingAdminError.Forbidden to (R.string.scheduling_admin_failure_forbidden to false),
            SchedulingAdminError.NotReady to (R.string.scheduling_admin_failure_not_ready to false),
            SchedulingAdminError.Unknown to (R.string.scheduling_admin_failure_unknown to true),
        )
        expected.forEach { (error, sentence) ->
            val text = error.toFailureText()
            assertEquals(error.toString(), sentence.first, text.message.resourceIdOrNull)
            assertEquals(error.toString(), sentence.second, text.retryable)
        }
    }

    @Test
    fun `a result becomes a section state and a slot taken is recognised`() {
        assertEquals(SectionState.Ready(1), SchedulingAdminResult.Ok(1).toSectionState())
        val failed = SchedulingAdminResult.Failed(SchedulingAdminError.Refused(SchedulingAdminFailureCode.SLOT_TAKEN))
        assertTrue(failed.toSectionState() is SectionState.Failed)
        assertTrue(failed.isSlotTaken())
        assertFalse(SchedulingAdminResult.Failed(SchedulingAdminError.Unknown).isSlotTaken())
        assertFalse(SchedulingAdminResult.Ok(1).isSlotTaken())
        assertEquals(1, SectionState.Ready(1).valueOrNull)
        assertNull(SectionState.Loading.valueOrNull)
        assertTrue(WriteState.Working.isWorking)
        assertFalse(WriteState.Idle.isWorking)
    }

    @Test
    fun `access is the status read's answer, and closed when the read fails`() = runTest {
        val api = FakeDistrictApi()
        val repository = SchedulingRepository(api, ApiEnvironment.baseUrl)
        api.schedulingApi.schedulingStatusResult = ApiResult.Success(
            SchedulingStatusResponse(
                eligible = true,
                canManage = true,
                tenant = SchedulingTenant(
                    status = "ready",
                    publicHost = "acme-book.example",
                    region = "us",
                    hasCredentials = true,
                ),
            ),
        )
        assertEquals(SchedulingAccess(canManage = true, publicHost = "acme-book.example"), repository.access("ws"))

        api.schedulingApi.schedulingStatusResult = ApiResult.Success(
            SchedulingStatusResponse(
                eligible = true,
                canManage = false,
                tenant = SchedulingTenant(status = "ready", publicHost = " ", region = "us", hasCredentials = true),
            ),
        )
        assertEquals(SchedulingAccess(canManage = false, publicHost = null), repository.access("ws"))

        api.schedulingApi.schedulingStatusResult =
            ApiResult.Success(SchedulingStatusResponse(eligible = true, canManage = true, tenant = null))
        assertEquals(SchedulingAccess(canManage = true, publicHost = null), repository.access("ws"))

        api.schedulingApi.schedulingStatusResult = ApiResult.NetworkFailure(IOException("down"))
        assertEquals(SchedulingAccess(), repository.access("ws"))
    }

    @Test
    fun `a remove asks first, sends once, and reports how it ended`() {
        val scope = TestScope(StandardTestDispatcher())
        val removed = mutableListOf<String>()
        var answer: SchedulingAdminResult<Unit> = SchedulingAdminResult.Ok(Unit)
        val gate = CompletableDeferred<Unit>()
        var gated = false
        val sent = mutableListOf<String>()
        val removal = SchedulingRemoval<String>(
            scope = scope,
            remove = {
                sent += it
                if (gated) gate.await()
                answer
            },
            onRemoved = { removed += it },
        )

        // Nothing pending: confirm does nothing.
        removal.confirm()
        scope.advanceUntilIdle()
        assertEquals(emptyList<String>(), sent)

        removal.ask("a")
        assertEquals("a", removal.state.value.pending)
        removal.cancel()
        assertNull(removal.state.value.pending)

        removal.ask("a")
        removal.confirm()
        assertTrue(removal.state.value.busy)
        scope.advanceUntilIdle()
        assertEquals(RemovalState(removed = "a"), removal.state.value)
        assertEquals(listOf("a"), removed)

        // While one is in flight, another ask is ignored.
        gated = true
        removal.ask("b")
        removal.confirm()
        scope.advanceUntilIdle()
        removal.ask("c")
        assertNull(removal.state.value.pending)
        answer = SchedulingAdminResult.Failed(SchedulingAdminError.Unavailable)
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals(listOf("a", "b"), sent)
        val failure = removal.state.value.failure
        assertEquals(R.string.scheduling_admin_failure_unavailable, failure?.message?.resourceIdOrNull)
        removal.dismissFailure()
        assertNull(removal.state.value.failure)
    }
}
