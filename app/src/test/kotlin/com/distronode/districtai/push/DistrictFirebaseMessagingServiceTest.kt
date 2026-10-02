package com.distronode.districtai.push

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import java.util.concurrent.CountDownLatch
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.AppContainer
import com.distronode.districtai.AppContainerSeams
import com.distronode.districtai.AppContainerOwner
import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.core.auth.TokenStore
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.dialer.FakeTelecomBridge
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * A credential store holding a session, or none: all the push gate reads is whether one exists.
 */
private class SessionTokenStore(
    private val session: PersistedSession?,
    /** Runs before every read; see [PushTestApplication.readGate]. */
    private val beforeRead: () -> Unit,
) : TokenStore {
    override fun read(): PersistedSession? {
        beforeRead()
        return session
    }
    override fun write(session: PersistedSession) = Unit
    override fun clear() = Unit
    override fun pendingRefreshToken(): String? = null
    override fun markRefreshPending(refreshToken: String) = Unit
    override fun clearRefreshPending() = Unit
    override fun pendingRevokeToken(): String? = null
    override fun markRevokePending(refreshToken: String) = Unit
    override fun clearRevokePending() = Unit
}

/**
 * The application these tests run under: the real [AppContainer], with its network, credential
 * store and Telecom bridge replaced, reached through the same [AppContainerOwner] the real
 * application implements.
 *
 * ⚠️ THE APPLICATION SCOPE RUNS ON AN `UnconfinedTestDispatcher` WITH A JOB THE TEST OWNS, so a
 * delivery runs inline up to the credential read (which hops to IO, as in production), and the ring
 * timeout the controller arms is a VIRTUAL delay that never fires on its own. It is cancelled with
 * the job after each test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PushTestApplication : Application(), AppContainerOwner {

    val appJob = SupervisorJob()
    val api = FakeDistrictApi()
    val telecom = FakeTelecomBridge()
    var signedIn = true

    /**
     * Held by every credential read until the test releases it.
     *
     * ⛔ WHAT MAKES THE DELIVERY'S JOB IDENTIFIABLE. The read runs on an IO thread, and without the
     * hold a fast one finished the delivery (and armed the ring timeout) before the test could tell
     * which new job was the delivery, so which job it joined depended on thread timing.
     */
    @Volatile
    var readGate: CountDownLatch = CountDownLatch(0)

    override val container: AppContainer by lazy {
        val session = PersistedSession(
            refreshToken = "refresh",
            refreshTokenExpiresAt = Long.MAX_VALUE,
            deviceId = "device-1",
        )
        AppContainer(
            this,
            appScope = CoroutineScope(appJob + UnconfinedTestDispatcher()),
            seams = AppContainerSeams(
                tokenStore = SessionTokenStore(session.takeIf { signedIn }) { readGate.await() },
                pushApi = api.pushApi,
                pushTokenSource = { null },
                districtApi = api,
                telecomBridge = telecom,
            ),
        )
    }
}

/**
 * The FCM entry point, driven through the application graph.
 *
 * ⛔ THE ASSERTIONS THAT MATTER MOST ARE THE SILENT ONES. A malformed, foreign or signed-out push
 * must never ring, never register a Telecom call and never draw a notification that would navigate
 * somewhere: the payload is ids-only, and a push the app does not understand is one it ignores.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], application = PushTestApplication::class)
class DistrictFirebaseMessagingServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val app: PushTestApplication get() = context as PushTestApplication

    @After
    fun tearDown() {
        (context as? PushTestApplication)?.appJob?.cancel()
    }

    private fun service(signedIn: Boolean = true): DistrictFirebaseMessagingService {
        app.signedIn = signedIn
        return Robolectric.buildService(DistrictFirebaseMessagingService::class.java).create().get()
    }

    /**
     * Deliver [data] and wait for the delivery itself, not for anything it started.
     *
     * ⛔ ONLY THE DELIVERY'S OWN JOB IS JOINED: the ring it starts arms a timeout on the same scope
     * that never completes by itself. The credential read is held while the delivery's job is picked
     * out, so the timeout cannot exist yet at that moment; see [PushTestApplication.readGate].
     */
    private fun deliver(service: DistrictFirebaseMessagingService, data: Map<String, String>) {
        val gate = CountDownLatch(1)
        app.readGate = gate
        val before = app.appJob.children.toSet()
        service.onMessageReceived(RemoteMessage.Builder("sender@fcm.googleapis.com").setData(data).build())
        val delivery = app.appJob.children.toSet() - before
        gate.countDown()
        runBlocking { delivery.joinAll() }
    }

    private fun posted() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test
    fun `a rotated token is registered with the server`() {
        service().onNewToken("fcm-rotated")

        assertEquals(listOf("fcm-rotated"), app.api.pushApi.pushRegisterRequests.map { it.token })
    }

    @Test
    fun `an incoming call rings through Telecom and the call notification`() {
        deliver(service(), mapOf("type" to "incoming_call", "workspaceId" to "ws-1", "callId" to "CA1"))

        assertEquals(listOf("incoming"), app.telecom.calls)
        val ring = shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(AndroidPushNotifier.NOTIFICATION_ID_INCOMING_CALL)
        assertEquals(AndroidPushNotifier.CHANNEL_INCOMING_CALLS, ring.channelId)
        val state = app.container.incomingCallController.state.value
        assertEquals("CA1", state?.callId)
        assertEquals("ws-1", state?.workspaceId)
    }

    @Test
    fun `a message draws a message notification and rings nothing`() {
        deliver(service(), mapOf("type" to "message", "workspaceId" to "ws-1", "messageId" to "m-1"))

        val drawn = posted()
        assertEquals(1, drawn.size)
        assertEquals(AndroidPushNotifier.CHANNEL_MESSAGES, drawn.single().channelId)
        assertTrue(app.telecom.calls.isEmpty())
    }

    @Test
    fun `malformed, foreign and unknown payloads never ring and never draw`() {
        val service = service()
        listOf(
            emptyMap(),
            mapOf("type" to "incoming_call", "callId" to "CA1"),
            mapOf("type" to "incoming_call", "workspaceId" to "ws-1"),
            mapOf("type" to "incoming_call", "workspaceId" to " ", "callId" to "CA1"),
            mapOf("type" to "message", "workspaceId" to "ws-1"),
            mapOf("type" to "sms_blast", "workspaceId" to "ws-1", "callId" to "CA1"),
            mapOf("workspaceId" to "ws-1", "callId" to "CA1"),
        ).forEach { deliver(service, it) }

        assertTrue(app.telecom.calls.isEmpty())
        assertTrue(posted().isEmpty())
        assertNull(app.container.incomingCallController.state.value)
    }

    @Test
    fun `a push for a device with no session rings nothing and draws nothing`() {
        deliver(
            service(signedIn = false),
            mapOf("type" to "incoming_call", "workspaceId" to "ws-1", "callId" to "CA1"),
        )

        assertTrue(app.telecom.calls.isEmpty())
        assertTrue(posted().isEmpty())
    }

    @Test
    @Config(application = Application::class)
    fun `under an application that owns no graph, a delivery does nothing rather than crash`() {
        val service = Robolectric.buildService(DistrictFirebaseMessagingService::class.java).create().get()

        service.onNewToken("fcm-orphan")
        service.onMessageReceived(
            RemoteMessage.Builder("sender@fcm.googleapis.com")
                .setData(mapOf("type" to "incoming_call", "workspaceId" to "ws-1", "callId" to "CA1"))
                .build(),
        )

        assertTrue(posted().isEmpty())
    }
}
