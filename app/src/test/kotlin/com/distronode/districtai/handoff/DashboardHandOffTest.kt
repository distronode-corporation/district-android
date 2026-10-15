package com.distronode.districtai.handoff

import com.distronode.districtai.ApiEnvironment
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nonce-bound dashboard hand-off from the tap to leg 3, and every way it ends.
 *
 * ⛔ THE RULE MOST OF THESE PIN IS THAT NO CODE IS EVER MINTED UNBOUND (security item S33). A
 * browser that never calls back, or calls back too late, ends the hand-off with nothing minted and a
 * retryable outcome, so the server can require the nonce. The type makes an unbound mint impossible
 * to write; these assert it never happens on any path, and that the nonce reaches the mint's body
 * and nowhere else: not the log, not an outcome, not a URL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardHandOffTest {

    private val api = FakeDistrictApi()
    private val repository = SchedulingRepository(api, ApiEnvironment.baseUrl)
    private val recorded = mutableListOf<HandOffEvent>()
    private val log = HandOffLog { recorded += it }
    private val callbacks = HandOffCallbacks(log)

    /** ⚠️ States handed out in order, one per hand-off. */
    private val states = ArrayDeque(listOf(BROWSER_STATE, SECOND_STATE))
    private val handOff = DashboardHandOff(repository, callbacks, log) { states.removeFirst() }

    /** Every URL handed to a browser, in order. */
    private val launched = mutableListOf<String>()

    /** A browser that follows leg 1's redirect straight back into the app, as a working one does. */
    private val callingBrowser: (String) -> Boolean = { url ->
        launched += url
        if (url == legOneUrl()) callbacks.deliver(BROWSER_STATE, NONCE)
        true
    }

    /** A browser that opens anything and never calls back. */
    private val silentBrowser: (String) -> Boolean = { url ->
        launched += url
        true
    }

    private fun legOneUrl(state: String = BROWSER_STATE) =
        "${ApiEnvironment.baseUrl}/dashboard/handoff/start?state=$state"

    /** ⛔ The assertion every path ends on: each mint that happened carried this hand-off's nonce. */
    private fun assertEveryMintBound() {
        assertTrue(
            "a mint without the browser's nonce",
            api.schedulingApi.schedulingHandOffs.all { it.nonce == NONCE },
        )
    }

    // ── The bound flow ───────────────────────────────────────────────────────

    @Test
    fun `a browser that calls back gets a bound code, opened in the browser that took the cookie`() =
        runTest {
            val outcome = handOff.open("ws-1", callingBrowser)

            assertEquals(DashboardHandOff.Outcome.Launched(opened = true), outcome)
            // ⛔ LEG 1 IN THE BROWSER, THEN THE CODE, THROUGH THE SAME LAUNCHER: one cookie jar.
            assertEquals(listOf(legOneUrl(), CODE_URL), launched)
            assertEquals(listOf(NONCE), api.schedulingApi.schedulingHandOffs.map { it.nonce })
            assertEquals(listOf(HandOffEvent.CALLBACK_ACCEPTED, HandOffEvent.BOUND), recorded)
        }

    @Test
    fun `leg 1 is the first thing opened, with no request to the server before it`() = runTest {
        // ⚠️ NO PROBE: the browser's own leg 1 is the only request leg 1 gets.
        val opening = async { handOff.open("ws-1", silentBrowser) }
        runCurrent()

        assertEquals(listOf(legOneUrl()), launched)
        assertTrue(api.schedulingApi.schedulingHandOffs.isEmpty())
        callbacks.deliver(BROWSER_STATE, NONCE)
        opening.await()
    }

    @Test
    fun `a callback that arrives late in the wait still binds the code, minted at once`() = runTest {
        val opening = async { handOff.open("ws-1", silentBrowser) }
        runCurrent()

        // ⚠️ A PERSON READING THE BROWSER'S "Open District AI?" PROMPT: well past the old ten
        // seconds, inside the thirty.
        advanceTimeBy(CALLBACK_ARRIVES_AFTER)
        callbacks.deliver(BROWSER_STATE, NONCE)

        assertEquals(DashboardHandOff.Outcome.Launched(opened = true), opening.await())
        assertEquals(listOf(NONCE), api.schedulingApi.schedulingHandOffs.map { it.nonce })
        assertEquals(CALLBACK_ARRIVES_AFTER, testScheduler.currentTime)
    }

    @Test
    fun `an injected callback with the wrong state does not end the wait for the real one`() = runTest {
        val opening = async { handOff.open("ws-1", silentBrowser) }
        runCurrent()

        callbacks.deliver("guessed-by-another-app-0", INJECTED_NONCE)
        callbacks.deliver(BROWSER_STATE, NONCE)

        opening.await()
        assertEquals(listOf(NONCE), api.schedulingApi.schedulingHandOffs.map { it.nonce })
    }

    // ── No callback: nothing is minted ───────────────────────────────────────

    @Test
    fun `a browser that never calls back ends the hand-off after thirty seconds, minting nothing`() =
        runTest {
            // ⛔ THE UNBOUND FALLBACK THIS REPLACED would have minted here, and that one mint is what
            // keeps the server from requiring the nonce.
            val outcome = handOff.open("ws-1", silentBrowser)

            assertEquals(DashboardHandOff.Outcome.Unanswered, outcome)
            assertEquals("only leg 1 went to the browser", listOf(legOneUrl()), launched)
            assertTrue("nothing was minted", api.schedulingApi.schedulingHandOffs.isEmpty())
            assertEquals(listOf(HandOffEvent.NO_CALLBACK), recorded)
            assertEquals(THIRTY_SECONDS, DashboardHandOff.CALLBACK_TIMEOUT_MILLIS)
            assertEquals(DashboardHandOff.CALLBACK_TIMEOUT_MILLIS, testScheduler.currentTime)
        }

    @Test
    fun `a callback that turns up after the wait is dropped, and still nothing is minted`() = runTest {
        val outcome = handOff.open("ws-1", silentBrowser)
        assertEquals(DashboardHandOff.Outcome.Unanswered, outcome)

        assertEquals(HandOffEvent.CALLBACK_NOTHING_PENDING, callbacks.deliver(BROWSER_STATE, NONCE))
        assertTrue(api.schedulingApi.schedulingHandOffs.isEmpty())
    }

    @Test
    fun `a retry after an unanswered hand-off starts a fresh leg 1 and binds`() = runTest {
        assertEquals(DashboardHandOff.Outcome.Unanswered, handOff.open("ws-1", silentBrowser))

        val retry = async { handOff.open("ws-1", silentBrowser) }
        runCurrent()
        callbacks.deliver(SECOND_STATE, NONCE)

        assertEquals(DashboardHandOff.Outcome.Launched(opened = true), retry.await())
        assertEquals(listOf(legOneUrl(), legOneUrl(SECOND_STATE), CODE_URL), launched)
        assertEquals(1, api.schedulingApi.schedulingHandOffs.size)
        assertEveryMintBound()
    }

    // ── Browsers, refusals, and one at a time ────────────────────────────────

    @Test
    fun `no browser for leg 1 says so and mints nothing`() = runTest {
        // ⚠️ A code nobody can open is a row and a rate-limit slot spent for nothing.
        val outcome = handOff.open("ws-1") { false }

        assertEquals(DashboardHandOff.Outcome.Launched(opened = false), outcome)
        assertTrue(api.schedulingApi.schedulingHandOffs.isEmpty())
    }

    @Test
    fun `no browser for the code says so after the bound mint`() = runTest {
        val outcome = handOff.open("ws-1") { url ->
            if (url == legOneUrl()) callbacks.deliver(BROWSER_STATE, NONCE)
            url == legOneUrl()
        }

        assertEquals(DashboardHandOff.Outcome.Launched(opened = false), outcome)
        assertEquals(1, api.schedulingApi.schedulingHandOffs.size)
        assertEveryMintBound()
    }

    @Test
    fun `a refused mint reaches the caller with its code, and opens nothing more`() = runTest {
        val refusal = ApiResult.HttpFailure(status = 400, message = "Bad request", code = "invalid_nonce")
        api.schedulingApi.schedulingHandOffResult = refusal

        val outcome = handOff.open("ws-1", callingBrowser)

        assertEquals(DashboardHandOff.Outcome.Refused(refusal), outcome)
        assertEquals("only leg 1", listOf(legOneUrl()), launched)
        assertEveryMintBound()
    }

    @Test
    fun `a second hand-off while one is running starts nothing, and the first finishes intact`() =
        runTest {
            // ⛔ A SECOND LEG 1 WOULD REPLACE THE COOKIE the first one's code is about to be bound to.
            val first = async { handOff.open("ws-1", silentBrowser) }
            runCurrent()

            val second = handOff.open("ws-2", silentBrowser)

            assertEquals(DashboardHandOff.Outcome.AlreadyRunning, second)
            assertEquals("one leg 1", listOf(legOneUrl()), launched)
            callbacks.deliver(BROWSER_STATE, NONCE)
            first.await()
            assertEquals(listOf("ws-1"), api.schedulingApi.schedulingHandOffs.map { it.workspaceId })
            assertTrue(HandOffEvent.ALREADY_RUNNING in recorded)
            assertEveryMintBound()
        }

    @Test
    fun `the slot is held until leg 3 is handed over, then freed`() = runTest {
        val mint = CompletableDeferred<Unit>()
        api.schedulingApi.schedulingHandOffGate = mint
        val first = async { handOff.open("ws-1", callingBrowser) }
        runCurrent()

        assertNull("still minting", callbacks.begin("probe-of-the-slot-0123"))
        mint.complete(Unit)
        first.await()

        assertNotNull("free once the code went to the browser", callbacks.begin("probe-of-the-slot-0123"))
    }

    @Test
    fun `a hand-off cancelled mid-wait frees the slot for the next tap`() = runTest {
        // ⛔ THE SCREEN CAN CLOSE WHILE LEG 1 IS OPEN, which cancels the ViewModel's scope. A slot
        // left taken would refuse every later tap until the process died.
        val opening = launch { handOff.open("ws-1", silentBrowser) }
        runCurrent()

        opening.cancel()
        opening.join()

        assertNotNull(callbacks.begin("after-the-cancel-0123456"))
        assertTrue(api.schedulingApi.schedulingHandOffs.isEmpty())
    }

    @Test
    fun `nothing recorded or returned carries the nonce or the code`() = runTest {
        val outcome = handOff.open("ws-1", callingBrowser)

        val written = recorded.joinToString { it.name } + outcome.toString()
        for (secret in listOf(NONCE, BROWSER_STATE, CODE_URL, "stub")) {
            assertFalse("<$secret> in <$written>", written.contains(secret))
        }
    }

    private companion object {
        const val BROWSER_STATE = "browser-state-0123456789"
        const val SECOND_STATE = "second-state-0123456789a"
        const val NONCE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456"
        const val INJECTED_NONCE = "INJECTEDINJECTEDINJECTEDINJECTEDINJECTED___"
        const val CODE_URL = "https://www.distronode.com/dashboard/handoff?code=stub&next=%2Fdashboard"
        const val THIRTY_SECONDS = 30_000L

        /** ⚠️ Past the old ten-second wait, inside the thirty. */
        const val CALLBACK_ARRIVES_AFTER = 20_000L
    }
}
