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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nonce-bound dashboard hand-off from the tap to leg 3, and every way it falls back.
 *
 * ⛔ THE RELEASE-ORDER RULE IS WHAT MOST OF THESE PIN. Today's production has no
 * `/dashboard/handoff/start`, so the bound flow must be attempted only when the server answers it,
 * and every way it can fail to deliver a nonce (a 404, a hung probe, a browser that never calls
 * back) must end in today's unbound flow rather than in a stuck button or an error page. And the
 * nonce must reach the mint's body and nowhere else: not the log, not an outcome, not a URL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardHandOffTest {

    private val api = FakeDistrictApi()
    private val repository =
        SchedulingRepository(api, ApiEnvironment.baseUrl, ApiEnvironment.HANDOFF_CALLBACK_URI)
    private val recorded = mutableListOf<HandOffEvent>()
    private val log = HandOffLog { recorded += it }
    private val callbacks = HandOffCallbacks(log)

    /** ⚠️ States handed out in order: the browser leg's first, then the probe's. */
    private val states = ArrayDeque(
        listOf(BROWSER_STATE, PROBE_STATE, "third-state-0123456789", "fourth-state-012345678"),
    )
    private val handOff = DashboardHandOff(repository, callbacks, log) { states.removeFirst() }

    /** Every URL handed to a browser, in order. */
    private val launched = mutableListOf<String>()

    private fun serveLegOne() {
        api.schedulingApi.schedulingHandOffStartResult =
            ApiResult.Success("${ApiEnvironment.HANDOFF_CALLBACK_URI}?state=$PROBE_STATE&nonce=$PROBE_NONCE")
    }

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

    private fun legOneUrl() = "${ApiEnvironment.baseUrl}/dashboard/handoff/start?state=$BROWSER_STATE"

    // ── The bound flow ───────────────────────────────────────────────────────

    @Test
    fun `a server that serves leg 1 gets a bound code, opened in the browser that took the cookie`() =
        runTest {
            serveLegOne()

            val outcome = handOff.open("ws-1", callingBrowser)

            assertEquals(DashboardHandOff.Outcome.Launched(opened = true), outcome)
            // ⛔ LEG 1 IN THE BROWSER, THEN THE CODE, THROUGH THE SAME LAUNCHER: one cookie jar.
            assertEquals(listOf(legOneUrl(), CODE_URL), launched)
            // ⛔ THE BROWSER'S NONCE IS WHAT BINDS THE CODE, never the probe's.
            assertEquals(listOf(NONCE), api.schedulingApi.schedulingHandOffs.map { it.nonce })
            assertEquals(listOf(HandOffEvent.CALLBACK_ACCEPTED, HandOffEvent.BOUND), recorded)
        }

    @Test
    fun `the probe gets its own state, never the one the browser carries`() = runTest {
        // ⚠️ SO THE PROBE'S `Location` CAN NEVER BE MISTAKEN FOR THE BROWSER LEG'S CALLBACK, and the
        // browser's state is seen by the browser alone.
        serveLegOne()

        handOff.open("ws-1", callingBrowser)

        assertEquals(listOf(PROBE_STATE), api.schedulingApi.schedulingHandOffStarts)
        assertNotEquals(BROWSER_STATE, PROBE_STATE)
    }

    @Test
    fun `a callback that arrives a few seconds later still binds the code`() = runTest {
        serveLegOne()
        val opening = async { handOff.open("ws-1", silentBrowser) }
        runCurrent()
        assertEquals("leg 1 is open and the app is waiting", listOf(legOneUrl()), launched)

        advanceTimeBy(CALLBACK_ARRIVES_AFTER)
        callbacks.deliver(BROWSER_STATE, NONCE)

        assertEquals(DashboardHandOff.Outcome.Launched(opened = true), opening.await())
        assertEquals(listOf(NONCE), api.schedulingApi.schedulingHandOffs.map { it.nonce })
        // ⚠️ AND THE CODE IS MINTED AT ONCE, not at the end of the wait: it lives sixty seconds.
        assertEquals(CALLBACK_ARRIVES_AFTER, testScheduler.currentTime)
    }

    @Test
    fun `an injected callback with the wrong state does not end the wait for the real one`() = runTest {
        serveLegOne()
        val opening = async { handOff.open("ws-1", silentBrowser) }
        runCurrent()

        callbacks.deliver("guessed-by-another-app-0", INJECTED_NONCE)
        callbacks.deliver(BROWSER_STATE, NONCE)

        opening.await()
        assertEquals(listOf(NONCE), api.schedulingApi.schedulingHandOffs.map { it.nonce })
    }

    // ── The fallbacks ────────────────────────────────────────────────────────

    @Test
    fun `today's production 404 on leg 1 falls back at once, with no browser leg on screen`() = runTest {
        // ⛔ THE DESIGN CHOICE. A Custom Tab on a server without the route would sit on a 404 page
        // that never calls back; the probe answers first, so nothing is shown and nothing is waited for.
        val outcome = handOff.open("ws-1", silentBrowser)

        assertEquals(DashboardHandOff.Outcome.Launched(opened = true), outcome)
        assertEquals("only the code's URL went to the browser", listOf(CODE_URL), launched)
        assertNull("today's unbound flow", api.schedulingApi.schedulingHandOffs.single().nonce)
        assertEquals(listOf(HandOffEvent.FALLBACK_START_UNAVAILABLE), recorded)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a probe that hangs falls back when its own budget runs out`() = runTest {
        serveLegOne()
        api.schedulingApi.schedulingHandOffStartGate = CompletableDeferred()

        val outcome = handOff.open("ws-1", silentBrowser)

        assertEquals(DashboardHandOff.Outcome.Launched(opened = true), outcome)
        assertEquals(listOf(CODE_URL), launched)
        assertNull(api.schedulingApi.schedulingHandOffs.single().nonce)
        assertEquals(listOf(HandOffEvent.FALLBACK_START_UNAVAILABLE), recorded)
        assertEquals(DashboardHandOff.START_PROBE_TIMEOUT_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun `a browser that never calls back falls back after the wait, and the code still opens`() = runTest {
        serveLegOne()

        val outcome = handOff.open("ws-1", silentBrowser)

        assertEquals(DashboardHandOff.Outcome.Launched(opened = true), outcome)
        assertEquals(listOf(legOneUrl(), CODE_URL), launched)
        assertNull(api.schedulingApi.schedulingHandOffs.single().nonce)
        assertEquals(listOf(HandOffEvent.FALLBACK_NO_CALLBACK), recorded)
        assertEquals(DashboardHandOff.CALLBACK_TIMEOUT_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun `a callback that turns up after the fallback is dropped rather than racing the unbound mint`() =
        runTest {
            serveLegOne()
            val mint = CompletableDeferred<Unit>()
            api.schedulingApi.schedulingHandOffGate = mint
            val opening = async { handOff.open("ws-1", silentBrowser) }

            advanceTimeBy(DashboardHandOff.CALLBACK_TIMEOUT_MILLIS + 1)
            runCurrent()
            assertEquals("the unbound mint is in flight", 1, api.schedulingApi.schedulingHandOffs.size)

            assertEquals(HandOffEvent.CALLBACK_ALREADY_SETTLED, callbacks.deliver(BROWSER_STATE, NONCE))
            mint.complete(Unit)
            opening.await()

            assertNull(api.schedulingApi.schedulingHandOffs.single().nonce)
            // ⚠️ And once the hand-off is over there is nothing for it to meet at all.
            assertEquals(HandOffEvent.CALLBACK_NOTHING_PENDING, callbacks.deliver(BROWSER_STATE, NONCE))
        }

    // ── Browsers, refusals, and one at a time ────────────────────────────────

    @Test
    fun `no browser for leg 1 says so and mints nothing`() = runTest {
        // ⚠️ A code nobody can open is a row and a rate-limit slot spent for nothing.
        serveLegOne()

        val outcome = handOff.open("ws-1") { false }

        assertEquals(DashboardHandOff.Outcome.Launched(opened = false), outcome)
        assertTrue(api.schedulingApi.schedulingHandOffs.isEmpty())
    }

    @Test
    fun `no browser for the code says so after the mint`() = runTest {
        val outcome = handOff.open("ws-1") { false }

        assertEquals(DashboardHandOff.Outcome.Launched(opened = false), outcome)
        assertEquals(1, api.schedulingApi.schedulingHandOffs.size)
    }

    @Test
    fun `a refused mint reaches the caller with its code, and opens nothing`() = runTest {
        // ⛔ `nonce_required` is the server refusing the unbound flow once its flag is on.
        val refusal = ApiResult.HttpFailure(status = 400, message = "Bad request", code = "nonce_required")
        api.schedulingApi.schedulingHandOffResult = refusal

        val outcome = handOff.open("ws-1", silentBrowser)

        assertEquals(DashboardHandOff.Outcome.Refused(refusal), outcome)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun `a second hand-off while one is running starts nothing, and the first finishes intact`() =
        runTest {
            // ⛔ A SECOND LEG 1 WOULD REPLACE THE COOKIE the first one's code is about to be bound to.
            serveLegOne()
            val first = async { handOff.open("ws-1", silentBrowser) }
            runCurrent()

            val second = handOff.open("ws-2", silentBrowser)

            assertEquals(DashboardHandOff.Outcome.AlreadyRunning, second)
            assertEquals(1, api.schedulingApi.schedulingHandOffStarts.size)
            callbacks.deliver(BROWSER_STATE, NONCE)
            first.await()
            assertEquals(listOf("ws-1"), api.schedulingApi.schedulingHandOffs.map { it.workspaceId })
            assertTrue(HandOffEvent.ALREADY_RUNNING in recorded)
        }

    @Test
    fun `the slot is held until leg 3 is handed over, then freed`() = runTest {
        val mint = CompletableDeferred<Unit>()
        api.schedulingApi.schedulingHandOffGate = mint
        val first = async { handOff.open("ws-1", silentBrowser) }
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
        serveLegOne()
        val opening = launch { handOff.open("ws-1", silentBrowser) }
        runCurrent()

        opening.cancel()
        opening.join()

        assertNotNull(callbacks.begin("after-the-cancel-0123456"))
        assertTrue(api.schedulingApi.schedulingHandOffs.isEmpty())
    }

    @Test
    fun `nothing recorded or returned carries the nonce or the code`() = runTest {
        serveLegOne()

        val outcome = handOff.open("ws-1", callingBrowser)

        val written = recorded.joinToString { it.name } + outcome.toString()
        for (secret in listOf(NONCE, PROBE_NONCE, BROWSER_STATE, PROBE_STATE, CODE_URL, "stub")) {
            assertFalse("<$secret> in <$written>", written.contains(secret))
        }
    }

    private companion object {
        const val BROWSER_STATE = "browser-state-0123456789"
        const val PROBE_STATE = "probe-state-0123456789ab"
        const val NONCE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456"
        const val PROBE_NONCE = "PROBEPROBEPROBEPROBEPROBEPROBEPROBEPROBEPRO"
        const val INJECTED_NONCE = "INJECTEDINJECTEDINJECTEDINJECTEDINJECTED___"
        const val CODE_URL = "https://www.distronode.com/dashboard/handoff?code=stub&next=%2Fdashboard"
        const val CALLBACK_ARRIVES_AFTER = 1_500L
    }
}
