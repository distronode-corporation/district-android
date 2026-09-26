package com.distronode.districtai.call

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.push.AndroidPushNotifier
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService

/**
 * A platform that refuses a `microphone`-typed foreground service and accepts a `phoneCall` one.
 *
 * ⚠️ THAT IS THE SHAPE API 34+ TAKES when the microphone type cannot be backed (a revoked
 * permission, a background start the OS will not extend the type to). Robolectric's own shadow
 * either accepts every type or throws on every call, so neither can show the fallback.
 */
@Implements(Service::class)
class RefusingMicrophoneServiceShadow : ShadowService() {
    @Implementation
    override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
        if (foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) {
            throw SecurityException("microphone type refused")
        }
        super.startForeground(id, notification, foregroundServiceType)
    }
}

/**
 * The service that keeps an answered call's process alive, and the host that starts and stops it.
 *
 * ⛔ WHAT MATTERS IS WHAT THE PLATFORM IS TOLD: the notification it shows (the calls channel, a
 * call category, ongoing, no actions), the foreground type it is asked to back, and that a refusal
 * stops the service instead of crashing the process in the middle of a call.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CallForegroundServiceTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun grantMicrophone() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun start(): Pair<CallForegroundService, Int> {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        val result = service.onStartCommand(Intent(application, CallForegroundService::class.java), 0, 1)
        return service to result
    }

    @Test
    fun `an answered call is a foreground phone call on the calls channel, with nothing to tap`() {
        grantMicrophone()
        val (service, result) = start()

        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertEquals(AndroidPushNotifier.NOTIFICATION_ID_ONGOING_CALL, shadow.lastForegroundNotificationId)
        assertEquals(AndroidPushNotifier.CHANNEL_INCOMING_CALLS, notification.channelId)
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertTrue(
            "an ongoing call cannot be swiped away",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        assertNull("hanging up belongs on the in-call screen", notification.actions)
        assertEquals(
            application.getString(R.string.push_ongoing_call_title),
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(
            application.getString(R.string.push_ongoing_call_body),
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            service.foregroundServiceType,
        )
        assertFalse(shadow.isStoppedBySelf)
        // ⛔ Not sticky: a restarted service would show "call in progress" for a call that ended.
        assertEquals(Service.START_NOT_STICKY, result)
        assertNull("nothing binds to it", service.onBind(Intent()))
    }

    @Test
    fun `without the microphone permission the service claims only the phone-call type`() {
        val (service, _) = start()

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL, service.foregroundServiceType)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    @Config(shadows = [RefusingMicrophoneServiceShadow::class])
    fun `a refused microphone type falls back to a phone-call service rather than none`() {
        grantMicrophone()
        val (service, _) = start()

        val shadow = shadowOf(service)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL, service.foregroundServiceType)
        assertEquals(AndroidPushNotifier.NOTIFICATION_ID_ONGOING_CALL, shadow.lastForegroundNotificationId)
        assertFalse(shadow.isStoppedBySelf)
    }

    @Test
    fun `a platform that refuses every form stops the service instead of crashing the call`() {
        grantMicrophone()
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        shadowOf(service).setThrowInStartForeground(IllegalStateException("start not allowed"))

        val result = service.onStartCommand(Intent(application, CallForegroundService::class.java), 0, 1)

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, result)
    }

    @Test
    fun `the host starts the service for a live call and stops it when the call ends`() {
        val host = AndroidForegroundCallHost(application)

        host.setCallActive(true)
        val started = shadowOf(application).nextStartedService
        assertEquals(ComponentName(application, CallForegroundService::class.java), started.component)

        host.setCallActive(false)
        val stopped = shadowOf(application).nextStoppedService
        assertEquals(ComponentName(application, CallForegroundService::class.java), stopped.component)
    }

    @Test
    fun `a start the platform refuses leaves the call running rather than throwing`() {
        val refusing = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this

            override fun startForegroundService(service: Intent?): ComponentName =
                throw IllegalStateException("ForegroundServiceStartNotAllowedException")

            override fun stopService(name: Intent?): Boolean = throw SecurityException("not ours")
        }
        val host = AndroidForegroundCallHost(refusing)

        // Neither throw escapes: losing the keep-alive must not end the call.
        host.setCallActive(true)
        host.setCallActive(false)
        assertNull(shadowOf(application).nextStartedService)
    }
}
