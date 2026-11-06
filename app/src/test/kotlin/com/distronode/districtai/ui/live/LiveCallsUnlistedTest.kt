package com.distronode.districtai.ui.live

import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.SuperviseRepository
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.CallDetailResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.live.LiveSupervisor.Phase
import com.distronode.districtai.ui.rooms.FakeCallEngine
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A supervised call the Live calls list no longer reaches (Seer on #123): a read that does not list
 * it is not taken as its end until the call, read by id, says so. The rest of the ViewModel is
 * [LiveCallsViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveCallsUnlistedTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val api = FakeSuperviseApi()
    private val log = LiveCallsLog()
    private val engines = mutableListOf<FakeCallEngine>()
    private val factory = CallEngineFactory { FakeCallEngine().also { engines += it } }
    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val ai = FakeSuperviseApi.AI_CALL.id
    private val team = FakeSuperviseApi.TEAM_CALL

    @After
    fun tearDown() {
        engineScope.cancel()
    }

    private fun TestScope.viewModel(): LiveCallsViewModel =
        LiveCallsViewModel(
            LiveCallsSources(SuperviseRepository(api), CallsRepository(log)),
            "ws-1",
            canWrite = true,
            factory,
            feed = null,
            engineScope = engineScope,
        )
            .also { runCurrent() }

    /** Listening to [ai], then a read that does not list it (it is past the list's bound). */
    private fun TestScope.listeningThenUnlisted(): LiveCallsViewModel {
        val vm = viewModel()
        vm.listen(ai)
        runCurrent()
        log.callsResult = ApiResult.Success(listOf(FakeSuperviseApi.TEAM_CALL))
        return vm
    }

    @Test
    fun `a supervised call missing from a read but live by id keeps its card and its session`() =
        runTest(dispatcher) {
            val vm = listeningThenUnlisted()
            log.detail = ApiResult.Success(CallDetailResponse(success = true, call = FakeSuperviseApi.AI_CALL))

            vm.refresh()
            runCurrent()

            assertEquals(listOf(ai), log.detailReads)
            // ⛔ Its card stays, after the listed rows, and still ends the session.
            assertEquals(LiveCallList.Loaded(listOf(team, FakeSuperviseApi.AI_CALL)), vm.list.value)
            assertEquals(Phase.CONNECTED, vm.supervision.value.phase)
            assertFalse("never left", engines.single().calls.contains("disconnect"))
        }

    @Test
    fun `a supervised call missing from a read that is over by id is left`() = runTest(dispatcher) {
        val vm = listeningThenUnlisted()
        log.detail = ApiResult.Success(
            CallDetailResponse(success = true, call = FakeSuperviseApi.AI_CALL.copy(status = "completed")),
        )

        vm.refresh()
        runCurrent()

        assertEquals(LiveCallList.Loaded(listOf(FakeSuperviseApi.TEAM_CALL)), vm.list.value)
        assertEquals("disconnect", engines.single().calls.last())
        assertEquals(LiveSupervisor.State(), vm.supervision.value)
    }

    @Test
    fun `a supervised call whose read by id fails keeps the card it had, until a read can tell`() =
        runTest(dispatcher) {
            val vm = listeningThenUnlisted()
            log.detail = ApiResult.NetworkFailure(IOException("offline"))

            vm.refresh()
            runCurrent()
            assertEquals(LiveCallList.Loaded(listOf(team, FakeSuperviseApi.AI_CALL)), vm.list.value)
            assertEquals(Phase.CONNECTED, vm.supervision.value.phase)

            log.detail = null // the call log's own answer: not listed, so not found
            vm.refresh()
            runCurrent()
            assertEquals(LiveSupervisor.State(), vm.supervision.value)
        }

    @Test
    fun `a supervised call whose read by id is refused for the session is left`() = runTest(dispatcher) {
        val vm = listeningThenUnlisted()
        log.detail = ApiResult.Unauthorized(null)

        vm.refresh()
        runCurrent()

        assertEquals(LiveSupervisor.State(), vm.supervision.value)
    }

    @Test
    fun `a supervised call the read lists is not read by id`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.listen(ai)
        runCurrent()
        vm.refresh()
        runCurrent()
        assertTrue("listed: no read by id", log.detailReads.isEmpty())
    }
}
