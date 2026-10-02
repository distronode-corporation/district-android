package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.AnalyticsMetrics
import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.core.model.UsageHistoryResponse
import com.distronode.districtai.core.model.UsageResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * The analytics and usage reads, and the one distinction this layer exists to keep:
 * "there is genuinely nothing metered" is a SUCCESS carrying null, never a failure and never zeros.
 */
class AnalyticsRepositoryTest {

    private fun repository(api: FakeDistrictApi) = AnalyticsRepository(api)

    // ── Analytics ────────────────────────────────────────────────────────────

    @Test
    fun `an analytics window is requested with the wire value the server matches`() = runTest {
        // ⛔ AN UNRECOGNISED timeRange IS NOT AN ERROR SERVER-SIDE — it silently falls back to 7d
        // and answers 200. So sending the enum NAME would serve a week of data under a "90 days"
        // heading, with nothing anywhere reporting a problem. This is the only assertion that can
        // catch it, because the response looks perfectly well-formed either way.
        val api = FakeDistrictApi().apply {
            analyticsResult = ApiResult.Success(AnalyticsResponse(success = true))
        }

        repository(api).analytics("ws-1", AnalyticsRange.NINETY_DAYS)

        assertEquals(listOf("ws-1" to AnalyticsRange.NINETY_DAYS), api.analyticsRequests)
        assertEquals("90d", AnalyticsRange.NINETY_DAYS.wire)
    }

    @Test
    fun `a populated window is returned whole`() = runTest {
        val report = AnalyticsResponse(
            success = true,
            metrics = AnalyticsMetrics(totalCalls = 48, avgDuration = 120),
        )
        val api = FakeDistrictApi().apply { analyticsResult = ApiResult.Success(report) }

        val result = repository(api).analytics("ws-1", AnalyticsRange.SEVEN_DAYS)

        assertEquals(report, (result as ApiResult.Success).value)
    }

    @Test
    fun `a 200 that does not affirm success is contract drift, not a quiet week`() = runTest {
        // ⛔ THE WORST FAILURE AVAILABLE ON THIS SCREEN. Every field of AnalyticsResponse has a
        // default, so an empty body decodes into a complete, well-formed report reading ZERO
        // calls, ZERO conversions and a flat trend — indistinguishable, to an operator, from a
        // genuinely quiet week. The envelope check is the only thing that separates them.
        val api = FakeDistrictApi().apply {
            analyticsResult = ApiResult.Success(AnalyticsResponse())
        }

        val result = repository(api).analytics("ws-1", AnalyticsRange.SEVEN_DAYS)

        assertTrue("must be reported as drift", result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a transport failure is passed through untouched`() = runTest {
        val api = FakeDistrictApi().apply {
            analyticsResult = ApiResult.HttpFailure(503, "Service unavailable")
        }

        val result = repository(api).analytics("ws-1", AnalyticsRange.THIRTY_DAYS)

        assertTrue(result is ApiResult.HttpFailure)
    }

    // ── Usage ────────────────────────────────────────────────────────────────

    @Test
    fun `a metered month is unwrapped from its envelope`() = runTest {
        val month = UsageData(month = "2026-08", smsOutbound = 412.0, callMinutesInbound = 1204.25)
        val api = FakeDistrictApi().apply {
            usageResult = ApiResult.Success(UsageResponse(success = true, usage = month))
        }

        val result = repository(api).usage("ws-1")

        assertEquals(month, (result as ApiResult.Success).value)
        assertEquals(listOf("ws-1"), api.requestedWorkspaceIds)
    }

    @Test
    fun `a month with no rows is a SUCCESS carrying null, not a failure`() = runTest {
        // ⛔ THE DISTINCTION THIS SIGNATURE EXISTS FOR. Success(null) means "we asked, and nothing
        // has been metered"; a Failure means "we could not find out". Collapsing them in EITHER
        // direction is wrong: as a failure, the screen would offer a retry for a state that will
        // never change this month; as a zeroed row, it would assert a billing figure that was
        // never read.
        val api = FakeDistrictApi().apply {
            usageResult = ApiResult.Success(UsageResponse(success = true, usage = null))
        }

        val result = repository(api).usage("ws-1")

        assertTrue("an empty month is not a failure", result is ApiResult.Success)
        assertNull("and it is genuinely null", (result as ApiResult.Success).value)
    }

    @Test
    fun `a usage envelope that does not affirm success is drift, not an empty month`() = runTest {
        // ⚠️ AND THIS IS WHY THE NULL ABOVE IS SAFE. `UsageResponse()` — the empty body — also has
        // a null `usage`, so without the envelope check the two would be identical and "the server
        // sent us nothing" would render as "you have no usage this month".
        val api = FakeDistrictApi().apply {
            usageResult = ApiResult.Success(UsageResponse())
        }

        val result = repository(api).usage("ws-1")

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a usage failure is passed through rather than becoming an empty month`() = runTest {
        val api = FakeDistrictApi().apply {
            usageResult = ApiResult.NetworkFailure(java.io.IOException("dropped"))
        }

        val result = repository(api).usage("ws-1")

        assertTrue(result is ApiResult.NetworkFailure)
    }

    // ── Usage history ────────────────────────────────────────────────────────

    @Test
    fun `history defaults to the span the web console shows`() = runTest {
        val api = FakeDistrictApi().apply {
            usageHistoryResult = ApiResult.Success(UsageHistoryResponse(success = true))
        }

        repository(api).usageHistory("ws-1")

        assertEquals(listOf("ws-1" to 3), api.usageHistoryRequests)
    }

    @Test
    fun `a short history is a legitimate answer, not an error`() = runTest {
        // ⚠️ The server skips months with no rows and clamps the request to 1..24, so asking for
        // three and receiving one is normal. A client treating a short list as a fault would show
        // a failure to every workspace younger than the window it asked for.
        val api = FakeDistrictApi().apply {
            usageHistoryResult = ApiResult.Success(
                UsageHistoryResponse(success = true, usage = listOf(UsageData(month = "2026-08"))),
            )
        }

        val result = repository(api).usageHistory("ws-1", months = 12)

        assertEquals(1, (result as ApiResult.Success).value.size)
        assertEquals(listOf("ws-1" to 12), api.usageHistoryRequests)
    }

    @Test
    fun `an empty history decodes as an empty list rather than failing`() = runTest {
        val api = FakeDistrictApi().apply {
            usageHistoryResult = ApiResult.Success(UsageHistoryResponse(success = true))
        }

        val result = repository(api).usageHistory("ws-1")

        assertTrue((result as ApiResult.Success).value.isEmpty())
    }

    @Test
    fun `a history envelope that does not affirm success is drift`() = runTest {
        val api = FakeDistrictApi().apply {
            usageHistoryResult = ApiResult.Success(UsageHistoryResponse())
        }

        assertTrue(repository(api).usageHistory("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a history failure is passed through`() = runTest {
        val api = FakeDistrictApi().apply {
            usageHistoryResult = ApiResult.RateLimited("Too many requests.")
        }

        assertTrue(repository(api).usageHistory("ws-1") is ApiResult.RateLimited)
    }
}
