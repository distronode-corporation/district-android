package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.testCall
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Live calls list: the call log's newest page, kept to the calls in progress or ringing. */
class CallsLiveRepositoryTest {

    private class Recording : FakeDistrictApi() {
        val pages = mutableListOf<Pair<Int, Int>>()

        /** When set, the page at each offset; an offset it lacks answers [callsResult]. */
        var byOffset: Map<Int, ApiResult<List<CallSummary>>> = emptyMap()

        override suspend fun calls(workspaceId: String, limit: Int, offset: Int): ApiResult<List<CallSummary>> {
            pages += limit to offset
            return byOffset[offset] ?: callsResult
        }
    }

    private val now = Instant.parse("2026-11-06T12:00:00Z")

    /** A full page of finished calls started [minutesAgo] before [now], ids prefixed [tag]. */
    private fun fullPage(tag: String, minutesAgo: Long, live: Set<Int> = emptySet()) = ApiResult.Success(
        (0 until 100).map { index ->
            testCall(id = "$tag$index", status = if (index in live) "in-progress" else "completed")
                .copy(createdAt = now.minus(Duration.ofMinutes(minutesAgo)).toString())
        },
    )

    private fun repository(api: Recording) = CallsRepository(api) { now }

    @Test
    fun `only in-progress and ringing calls are live, and a short first page is the whole log`() = runTest {
        val api = Recording()
        api.callsResult = ApiResult.Success(
            listOf("in-progress", "ringing", "completed", "no-answer", "busy", "failed", "In-Progress")
                .mapIndexed { index, status -> testCall(id = "c$index", status = status) },
        )

        val live = (CallsRepository(api).liveCalls("ws-1") as ApiResult.Success).value

        assertEquals(listOf("c0", "c1"), live.map { it.id })
        assertEquals(listOf(100 to 0), api.pages)
    }

    @Test
    fun `a failed read is handed back as it came`() = runTest {
        val api = Recording()
        val offline = ApiResult.NetworkFailure(IOException("offline"))
        api.callsResult = offline

        assertEquals(offline, CallsRepository(api).liveCalls("ws-1"))
    }

    @Test
    fun `a full page inside the live window reads the next one, and live calls past the first hundred are listed`() =
        runTest {
            val api = Recording()
            api.byOffset = mapOf(
                0 to fullPage("a", minutesAgo = 5, live = setOf(0)),
                100 to fullPage("b", minutesAgo = 60, live = setOf(99)),
            )
            api.callsResult = ApiResult.Success(listOf(testCall(id = "c0", status = "ringing")))

            val live = (repository(api).liveCalls("ws-1") as ApiResult.Success).value

            assertEquals(listOf("a0", "b99", "c0"), live.map { it.id })
            assertEquals(listOf(100 to 0, 100 to 100, 100 to 200), api.pages)
        }

    @Test
    fun `a full page that reaches past the live window, with the clock slack, is the last one read`() = runTest {
        val api = Recording()
        // 4 h 15 min window plus 30 min slack: 284 minutes is inside it, 286 past it.
        api.byOffset = mapOf(0 to fullPage("a", minutesAgo = 284), 100 to fullPage("b", minutesAgo = 286))

        repository(api).liveCalls("ws-1")

        assertEquals(listOf(100 to 0, 100 to 100), api.pages)
    }

    @Test
    fun `a row that comes back on the next page, shifted by a new call, is kept once`() = runTest {
        val api = Recording()
        val first = fullPage("a", minutesAgo = 5, live = setOf(99))
        api.byOffset = mapOf(0 to first)
        api.callsResult = ApiResult.Success(listOf(first.value.last()))

        val live = (repository(api).liveCalls("ws-1") as ApiResult.Success).value

        assertEquals(listOf("a99"), live.map { it.id })
    }

    @Test
    fun `a createdAt that does not parse reads on, and the page bound stops the scan`() = runTest {
        val api = Recording()
        api.callsResult = ApiResult.Success(
            (0 until 100).map { testCall(id = "x$it").copy(createdAt = "not a time") },
        )

        repository(api).liveCalls("ws-1")

        assertEquals(20, api.pages.size)
        assertEquals(1900, api.pages.last().second)
    }

    @Test
    fun `a page that fails after the first fails the whole read, never a partial list`() = runTest {
        val api = Recording()
        val offline = ApiResult.NetworkFailure(IOException("offline"))
        api.byOffset = mapOf(0 to fullPage("a", minutesAgo = 5, live = setOf(0)))
        api.callsResult = offline

        assertEquals(offline, repository(api).liveCalls("ws-1"))
    }

    @Test
    fun `the real clock is the default`() = runTest {
        val api = Recording()
        // Rows from a minute ago on the real clock: inside the window, so the next page is read.
        api.byOffset = mapOf(
            0 to ApiResult.Success(
                (0 until 100).map { testCall(id = "r$it").copy(createdAt = Instant.now().minusSeconds(60).toString()) },
            ),
        )

        CallsRepository(api).liveCalls("ws-1")

        assertTrue(api.pages.size == 2)
    }
}
