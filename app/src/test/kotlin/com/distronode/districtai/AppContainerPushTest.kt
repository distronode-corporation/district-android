package com.distronode.districtai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.call.IncomingCallPhase
import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.push.PushPayload
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.dialer.FakeTelecomBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A delivered push, from the container's entry point to the call it rings.
 *
 * ⛔ THE SESSION GATE IS THE POINT. A push can reach a device whose account has signed out (the
 * server retires the registration lazily), and drawing a ringing call for an account that is no
 * longer on the phone would put a stranger's caller in front of whoever holds it now. So the same
 * payload is delivered twice: once with a stored credential, once without.
 *
 * ⚠️ REAL THREADS, JOINED RATHER THAN POLLED. The gate reads the store on `Dispatchers.IO`, which a
 * virtual test scheduler cannot drive, so the push's own coroutine is joined: when it has finished,
 * everything it was going to do has happened.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AppContainerPushTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = ShellTokenStore()
    private val telecom = FakeTelecomBridge()

    private val container = AppContainer(
        ApplicationProvider.getApplicationContext(),
        appScope = scope,
        seams = AppContainerSeams(
            tokenStore = store,
            pushTokenSource = { null },
            districtApi = TestDistrictApi(),
            telecomBridge = telecom,
        ),
    )

    @After
    fun tearDown() {
        // The ring timer is still counting on a ringing call; it must not outlive the test.
        scope.cancel()
    }

    private val incomingCall = mapOf(
        PushPayload.KEY_TYPE to PushPayload.TYPE_INCOMING_CALL,
        PushPayload.KEY_WORKSPACE_ID to "ws-1",
        PushPayload.KEY_CALL_ID to "call-7",
    )

    /** Deliver [data] and wait for the handling it started, and nothing launched after it. */
    private fun deliver(data: Map<String, String>) = runBlocking {
        val before = scope.coroutineContext.job.children.toSet()
        container.onPushReceived(data)
        val started: List<Job> = scope.coroutineContext.job.children.filterNot { it in before }.toList()
        withTimeout(WAIT_MILLIS) { started.forEach { it.join() } }
    }

    @Test
    fun `an incoming-call push rings when someone is signed in on this device`() {
        store.session = PersistedSession(
            refreshToken = "refresh-live",
            refreshTokenExpiresAt = Long.MAX_VALUE,
            deviceId = "device-abcdefgh",
        )

        deliver(incomingCall)

        val ringing = container.incomingCallController.state.value
        assertEquals(IncomingCallPhase.RINGING, ringing?.phase)
        assertEquals("call-7", ringing?.callId)
        assertEquals("ws-1", ringing?.workspaceId)
    }

    @Test
    fun `the same push is dropped when no credential is stored`() {
        deliver(incomingCall)

        assertNull(container.incomingCallController.state.value)
        assertEquals("nothing may be announced to the OS either", emptyList<String>(), telecom.calls)
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
