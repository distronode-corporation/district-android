package com.distronode.districtai.call

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.push.AndroidPushNotifier
import com.distronode.districtai.push.PushIntents
import com.distronode.districtai.push.PushTestApplication
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Decline button on the ringing notification, through the application graph (with its network
 * and Telecom bridge faked, see [PushTestApplication]).
 *
 * ⛔ A DECLINE ENDS THE RING AND TELLS THE SERVER NOTHING. It routes to the same `decline()` a ring
 * timeout does; what is visible here is the ring notification going away and the controller moving
 * to ENDED. Anything that is not the app's own Decline action must do nothing at all.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], application = PushTestApplication::class)
class IncomingCallActionReceiverTest {

    private val application: PushTestApplication = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        // ⚠️ Cancels the ring timeout the call armed on the application scope.
        application.appJob.cancel()
    }

    private fun ringingCall(): IncomingCallController {
        val controller = application.container.incomingCallController
        controller.onIncomingCall(workspaceId = "ws-1", callId = "CA1")
        return controller
    }

    private fun ring() = shadowOf(application.getSystemService(NotificationManager::class.java))
        .getNotification(AndroidPushNotifier.NOTIFICATION_ID_INCOMING_CALL)

    @Test
    fun `the Decline action ends the ringing call and takes the ring notification down`() {
        val controller = ringingCall()
        assertNotNull("the ring is on screen before the decline", ring())

        IncomingCallActionReceiver().onReceive(application, PushIntents.declineCall(application, "CA1"))

        assertEquals(IncomingCallPhase.ENDED, controller.state.value?.phase)
        assertNull(ring())
    }

    @Test
    fun `a broadcast that is not the Decline action leaves the ring alone`() {
        val controller = ringingCall()

        IncomingCallActionReceiver().onReceive(application, Intent("com.example.SOMETHING_ELSE"))
        IncomingCallActionReceiver().onReceive(application, Intent())

        assertEquals(IncomingCallPhase.RINGING, controller.state.value?.phase)
        assertNotNull(ring())
    }

    @Test
    fun `under an application that is not ours, a decline does nothing rather than crash`() {
        val controller = ringingCall()
        val foreign = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = Application()
        }

        IncomingCallActionReceiver().onReceive(foreign, PushIntents.declineCall(application, "CA1"))

        assertEquals(IncomingCallPhase.RINGING, controller.state.value?.phase)
    }
}
