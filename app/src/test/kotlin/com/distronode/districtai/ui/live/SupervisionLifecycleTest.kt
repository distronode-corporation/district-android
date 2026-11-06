package com.distronode.districtai.ui.live

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.SuperviseRepository
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.live.LiveSupervisor.Phase
import com.distronode.districtai.ui.rooms.FakeCallEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The supervise session across the activity's lifecycle, through [SupervisionLifecycle] as the
 * destination wires it (review H1 of PR #117, its rotation cases inverted).
 *
 * ⛔ A ROTATION IS A RECREATION (MainActivity declares no `configChanges`), and a recreation
 * dispatches ON_STOP. These pin that it neither drops a listener nor hands a taken-over call back,
 * and that a real stop still leaves.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SupervisionLifecycleTest {

    private val ai = FakeSuperviseApi.AI_CALL.id
    private val engines = mutableListOf<FakeCallEngine>()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val vm = LiveCallsViewModel(
        LiveCallsSources(SuperviseRepository(FakeSuperviseApi()), CallsRepository(LiveCallsLog())),
        "ws-1",
        true,
        CallEngineFactory { FakeCallEngine().also { engines += it } },
        feed = null,
        engineScope = engineScope,
    )

    @After
    fun tearDown() {
        engineScope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun screen(): ActivityController<ComponentActivity> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { SupervisionLifecycle(vm) }
        idle()
        return controller
    }

    private fun listening(): ActivityController<ComponentActivity> {
        val controller = screen()
        vm.listen(ai)
        idle()
        assertTrue(vm.supervision.value.live)
        return controller
    }

    @Test
    fun `a rotation keeps the call being listened to`() {
        val controller = listening()

        controller.recreate()
        idle()

        assertEquals(LiveSupervisor.State(callId = ai, phase = Phase.CONNECTED, joined = true), vm.supervision.value)
        assertTrue("the room was never left", engines.single().calls.none { it == "disconnect" })
    }

    @Test
    fun `a rotation keeps a taken-over call on the supervisor's microphone`() {
        val controller = listening()
        vm.takeOver.ask(ai)
        vm.takeOver.confirm()
        vm.takeOver.onMicrophoneRequested()
        vm.takeOver.onMicrophonePermissionResult(true)
        idle()
        assertTrue(vm.supervision.value.takenOver)

        controller.recreate()
        idle()

        assertEquals(
            LiveSupervisor.State(callId = ai, takenOver = true, phase = Phase.CONNECTED, joined = true),
            vm.supervision.value,
        )
        assertEquals("mic:true", engines.last().calls.last())
    }

    @Test
    fun `a microphone answer that arrives after a rotation still takes the call over`() {
        val controller = listening()
        vm.takeOver.ask(ai)
        vm.takeOver.confirm()
        vm.takeOver.onMicrophoneRequested()

        controller.recreate()
        idle()
        vm.takeOver.onMicrophonePermissionResult(true)
        idle()

        assertTrue(vm.supervision.value.takenOver)
        assertTrue(vm.supervision.value.live)
    }

    @Test
    fun `going to the background still leaves the call`() {
        val controller = listening()

        controller.pause().stop()
        idle()

        assertEquals(LiveSupervisor.State(), vm.supervision.value)
        assertEquals("disconnect", engines.single().calls.last())
    }
}
