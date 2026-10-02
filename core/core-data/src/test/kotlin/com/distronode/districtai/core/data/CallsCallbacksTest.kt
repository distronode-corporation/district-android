package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * The dialler's call-back list.
 *
 * ⛔ THE FILTER IS THE WHOLE FEATURE, AND GETTING IT WRONG DIALS THE WORKSPACE'S OWN NUMBER.
 * `CallSummary.from` is the raw `Call.from` column, which on an OUTBOUND row is the number the
 * call was placed FROM — the workspace's own line, because `calls/dial` writes `from: fromNumber`
 * and puts the callee in `callerName`. A list built without this filter would offer "call back"
 * against the workspace's own number: it would connect, it would bill, and it would look like a
 * carrier fault rather than a client one.
 *
 * ⚠️ WHICH IS ALSO WHY THE FEATURE IS "CALL BACK" AND NOT "REDIAL". The call log has no reliable
 * record of the number an outbound call reached, so repeating an outbound call is not something
 * this data can support and the UI must not promise it.
 */
class CallsCallbacksTest {

    private val api = FakeDistrictApi()
    private val repository = CallsRepository(api)

    private fun call(
        id: String,
        direction: String?,
        from: String?,
    ) = CallSummary(
        id = id,
        type = direction ?: "inbound",
        number = from ?: "Unknown",
        status = "completed",
        duration = "1m 5s",
        time = "Aug 15, 02:30 PM",
        aiSummary = "",
        hasTranscript = false,
        callerName = from ?: "Unknown",
        from = from,
        direction = direction,
        summary = "",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    @Test
    fun `only inbound rows with a number survive`() = runTest {
        api.callsResult = ApiResult.Success(
            listOf(
                call("in-1", direction = "inbound", from = "+14165550100"),
                // ⛔ THE ROW THAT WOULD DIAL US. Its `from` is the workspace's own line.
                call("out-1", direction = "outbound", from = "+16475550199"),
                // ⚠️ An unknown direction is not evidence that `from` is a callee, so it is
                // excluded rather than guessed at — the permissive guess is the one that dials
                // the wrong number.
                call("unknown-1", direction = null, from = "+14165550111"),
                // A row with nothing to call back is not a call-back candidate.
                call("in-2", direction = "inbound", from = null),
                call("in-3", direction = "inbound", from = "  "),
            ),
        )

        val result = repository.recentCallbacks("ws-1")

        val ids = (result as ApiResult.Success).value.map { it.id }
        assertEquals(listOf("in-1"), ids)
    }

    @Test
    fun `duplicates are kept, because each row is a distinct call`() = runTest {
        // ⚠️ ONE PERSON WHO CALLED THREE TIMES IS THREE ROWS. Collapsing them here would
        // misreport the log — each has its own time and outcome — so the decision belongs to
        // whatever draws the list, not to the read.
        api.callsResult = ApiResult.Success(
            listOf(
                call("a", direction = "inbound", from = "+14165550100"),
                call("b", direction = "inbound", from = "+14165550100"),
            ),
        )

        val result = repository.recentCallbacks("ws-1")

        assertEquals(2, (result as ApiResult.Success).value.size)
    }

    @Test
    fun `an all-outbound page is an empty list, not a failure`() = runTest {
        // ⚠️ A HEALTHY WORKSPACE CAN LEGITIMATELY HAVE NOTHING TO SHOW HERE — nobody has called
        // in, or the last page happened to be all outbound. It is an empty state, and the screen
        // must not draw it as a broken read.
        api.callsResult = ApiResult.Success(
            listOf(call("out-1", direction = "outbound", from = "+16475550199")),
        )

        val result = repository.recentCallbacks("ws-1")

        assertTrue((result as ApiResult.Success).value.isEmpty())
    }

    @Test
    fun `a failed read stays a failure rather than becoming an empty list`() = runTest {
        // ⛔ "WE COULD NOT LOOK" AND "THERE IS NOTHING" ARE DIFFERENT ANSWERS. This route is a
        // bare array with no envelope to check, so the only thing keeping the two apart is that
        // the failure is forwarded unchanged.
        val failure = ApiResult.NetworkFailure(java.io.IOException("offline"))
        api.callsResult = failure

        assertEquals(failure, repository.recentCallbacks("ws-1"))
    }
}
