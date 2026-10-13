package com.distronode.districtai.ui.calls

import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.model.CallDetailResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.live.FinalTranscriptState
import com.distronode.districtai.core.network.live.LiveDependencies
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeTelemetryApi
import com.distronode.districtai.core.network.testing.FakeTransport
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.core.network.testing.VirtualClock
import com.distronode.districtai.core.network.testing.testCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The live transcript's place in the call detail: made once for a call in progress, following the
 * screen's start and stop, and told by every reload whether the call is still going.
 *
 * ⚠️ `runCurrent`, NEVER `advanceUntilIdle`: an open socket always has a timer pending (its silence
 * watchdog), so "idle" never comes. ⛔ AND EVERY TEST STOPS THE SCREEN BEFORE IT ENDS: `runTest`
 * drains the shared scheduler on the way out, the ViewModel's scope included, and a socket left
 * open would re-arm its watchdog forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallDetailLiveViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val api = FakeDistrictApi()
    private val transport = FakeTransport()
    private var made = 0

    private fun status(status: String) {
        api.detailResult = ApiResult.Success(CallDetailResponse(success = true, call = testCall(status = status)))
    }

    private fun TestScope.viewModel(live: Boolean = true) = CallDetailViewModel(
        repository = CallsRepository(api),
        workspaceId = LiveTranscriptFrames.WORKSPACE,
        callId = LiveTranscriptFrames.CALL,
        liveTranscript = if (!live) {
            null
        } else {
            { scope ->
                made += 1
                LiveTranscriptController(
                    workspaceId = LiveTranscriptFrames.WORKSPACE,
                    callId = LiveTranscriptFrames.CALL,
                    dependencies = LiveDependencies(
                        FakeTelemetryApi(),
                        transport,
                        VirtualClock(this),
                        jitter = { 0.0 }
                    ),
                    fetchTranscript = { ApiResult.Success("All of it.") },
                    scope = scope,
                )
            }
        },
    )

    @Test
    fun `a call in progress gets one live transcript, kept across reloads`() = runTest(dispatcher) {
        status("in-progress")
        val socket = transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()
        assertNotNull(vm.liveState.value)
        assertEquals(1, transport.connects.size)

        vm.load()
        runCurrent()
        assertEquals(1, made)
        assertEquals(1, transport.connects.size)

        vm.onScreenStarted(false)
        runCurrent()
        assertTrue(socket.closed)
    }

    @Test
    fun `a live transcript made before the screen starts waits for it`() = runTest(dispatcher) {
        status("in-progress")
        transport.socket()
        val vm = viewModel()
        runCurrent()
        assertNotNull(vm.liveState.value)
        assertEquals(0, transport.connects.size)
        vm.onScreenStarted(true)
        runCurrent()
        assertEquals(1, transport.connects.size)
        vm.onScreenStarted(false)
        runCurrent()
    }

    @Test
    fun `a reload that finds the call over ends the live transcript`() = runTest(dispatcher) {
        status("in-progress")
        transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()
        status("completed")
        vm.load()
        runCurrent()
        assertEquals(FinalTranscriptState.Fetching(1), vm.liveState.value?.finalTranscript)
        vm.onScreenStarted(false)
        runCurrent()
    }

    @Test
    fun `a ringing, finished, or unwired call gets none`() = runTest(dispatcher) {
        listOf("ringing", "completed").forEach {
            status(it)
            val vm = viewModel()
            vm.onScreenStarted(true)
            runCurrent()
            assertNull(it, vm.liveState.value)
        }
        status("in-progress")
        val vm = viewModel(live = false)
        vm.onScreenStarted(true)
        vm.onScreenStarted(false)
        runCurrent()
        assertNull(vm.liveState.value)
        assertEquals(0, made)
    }
}
