package com.distronode.districtai.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.VibrationAttributes
import android.os.Vibrator
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.IOException
import java.time.Duration

/**
 * The ringtone and vibration of a ringing inbound call, against the platform's own decisions.
 *
 * ⛔ THE CASES THAT MATTER MOST ARE THE QUIET ONES. A ring that sounds through silent mode or Do Not
 * Disturb is the fastest way to get the app's calls muted for good, so every setting that silences
 * the system's own ring is asserted to silence this one too.
 *
 * ⚠️ WHAT ROBOLECTRIC CANNOT DO: it plays nothing and routes nothing, so "the speaker rang" is the
 * on-device ring test's question. What it does model is the ringer mode, the interruption filter,
 * audio focus, the vibrator and `MediaPlayer`'s state machine, which is where every decision is.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class IncomingRingerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private val defaultSource = DataSource.toDataSource(context, Settings.System.DEFAULT_RINGTONE_URI)
    private val actualUri: Uri = Uri.parse("content://media/internal/audio/media/7")
    private val actualSource = DataSource.toDataSource(context, actualUri)

    @Before
    fun setUp() {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, actualUri)
        ShadowMediaPlayer.addMediaInfo(defaultSource, ShadowMediaPlayer.MediaInfo(RINGTONE_MILLIS, 0))
        ShadowMediaPlayer.addMediaInfo(actualSource, ShadowMediaPlayer.MediaInfo(RINGTONE_MILLIS, 0))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun vibrating() = shadowOf(vibrator).isVibrating

    @Test
    fun `a normal ring plays the ringtone on the ring usage, vibrates, and takes transient focus`() {
        val ringer = AndroidIncomingRinger(context)

        ringer.start()
        idle()

        assertTrue(ringer.sounding)
        assertTrue(vibrating())
        val focus = shadowOf(audio).lastAudioFocusRequest
        assertNotNull(focus)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, focus.durationHint)
        assertEquals(
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            focus.audioFocusRequest.audioAttributes.usage,
        )
        val attributes = shadowOf(vibrator).vibrationAttributesFromLastVibration as VibrationAttributes
        assertEquals(VibrationAttributes.USAGE_RINGTONE, attributes.usage)
    }

    @Test
    fun `stopping silences the ringtone, cancels the vibration and gives the focus back`() {
        val ringer = AndroidIncomingRinger(context)
        ringer.start()
        idle()

        ringer.stop()

        assertFalse(ringer.sounding)
        assertFalse(vibrating())
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        // ⚠️ IDEMPOTENT: the controller stops at the press and again on the way out.
        ringer.stop()
        assertFalse(ringer.sounding)
    }

    @Test
    fun `the ringtone loops until it is stopped`() {
        val ringer = AndroidIncomingRinger(context)
        ringer.start()
        idle()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RINGTONE_MILLIS * 3L))

        assertTrue("still ringing after three play-throughs", ringer.sounding)
        ringer.stop()
    }

    @Test
    fun `a second start while ringing does not start a second ring`() {
        val ringer = AndroidIncomingRinger(context)
        ringer.start()
        idle()
        val first = shadowOf(audio).lastAudioFocusRequest

        ringer.start()
        idle()

        assertTrue(first === shadowOf(audio).lastAudioFocusRequest)
        ringer.stop()
    }

    @Test
    fun `silent mode rings neither the speaker nor the motor`() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        val ringer = AndroidIncomingRinger(context)

        ringer.start()
        idle()

        assertFalse(ringer.sounding)
        assertFalse(vibrating())
        assertNull("no focus is taken for a silent ring", shadowOf(audio).lastAudioFocusRequest)
    }

    @Test
    fun `vibrate mode vibrates and plays nothing`() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        val ringer = AndroidIncomingRinger(context)

        ringer.start()
        idle()

        assertFalse(ringer.sounding)
        assertTrue(vibrating())
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        ringer.stop()
        assertFalse(vibrating())
    }

    @Test
    fun `Do Not Disturb that does not let the call through silences the ring entirely`() {
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        val ringer = AndroidIncomingRinger(context)

        ringer.start()
        idle()

        assertFalse(ringer.sounding)
        assertFalse(vibrating())
    }

    @Test
    fun `priority-only Do Not Disturb that allows calls from anyone still rings`() {
        notifications.notificationPolicy = NotificationManager.Policy(
            NotificationManager.Policy.PRIORITY_CATEGORY_CALLS,
            NotificationManager.Policy.PRIORITY_SENDERS_ANY,
            NotificationManager.Policy.PRIORITY_SENDERS_ANY,
        )
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val ringer = AndroidIncomingRinger(context)

        ringer.start()
        idle()

        assertTrue(ringer.sounding)
        ringer.stop()
    }

    @Test
    fun `a refused audio focus means no sound, and the vibration still runs`() {
        // ⚠️ THE PLATFORM REFUSES TRANSIENT FOCUS DURING A PHONE CALL: no ringtone over a cellular
        // conversation.
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val ringer = AndroidIncomingRinger(context)

        ringer.start()
        idle()

        assertFalse(ringer.sounding)
        assertTrue(vibrating())
        ringer.stop()
    }

    @Test
    fun `a ringtone of None plays nothing`() {
        RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, null)
        val ringer = AndroidIncomingRinger(context)

        ringer.start()
        idle()

        assertFalse(ringer.sounding)
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        assertTrue("the vibration the user's settings ask for still runs", vibrating())
        ringer.stop()
    }

    @Test
    fun `the actual ringtone is the fallback when the system's copy cannot be opened`() {
        ShadowMediaPlayer.addException(defaultSource, IOException("no cached ringtone"))
        val ringer = AndroidIncomingRinger(context)

        ringer.start()
        idle()

        assertTrue(ringer.sounding)
        ringer.stop()
    }

    @Test
    fun `when no source opens the focus is given back`() {
        ShadowMediaPlayer.addException(defaultSource, SecurityException("not readable"))
        ShadowMediaPlayer.addException(actualSource, IllegalArgumentException("not a ringtone"))
        val ringer = AndroidIncomingRinger(context)

        ringer.start()
        idle()

        assertFalse(ringer.sounding)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        ringer.stop()
    }

    @Test
    fun `a player error releases the player and gives the focus back`() {
        ShadowMediaPlayer.addMediaInfo(
            defaultSource,
            ShadowMediaPlayer.MediaInfo(RINGTONE_MILLIS, 0).apply { scheduleErrorAtOffset(ERROR_AT_MILLIS, 1, 0) },
        )
        val ringer = AndroidIncomingRinger(context)
        ringer.start()
        idle()
        assertTrue(ringer.sounding)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ERROR_AT_MILLIS * 2L))

        assertFalse(ringer.sounding)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        ringer.stop()
    }

    @Test
    fun `nothing sounds until the player is prepared`() {
        val ringer = AndroidIncomingRinger(context)

        ringer.start()

        assertFalse("prepared asynchronously, so not yet playing", ringer.sounding)
        idle()
        assertTrue(ringer.sounding)
        ringer.stop()
    }

    @Test
    fun `a callback from a player that is no longer current touches nothing`() {
        // ⛔ A PREPARED OR ERROR CALLBACK QUEUED BEFORE A STOP ARRIVES AFTER IT. It must neither start
        // a released player nor give back the focus a later ring holds.
        val ringer = AndroidIncomingRinger(context)
        ringer.start()
        idle()
        val stale = MediaPlayer()

        ringer.onReady(stale)
        ringer.onFailed(stale)

        assertFalse(stale.isPlaying)
        assertTrue("the current ring is untouched", ringer.sounding)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        ringer.stop()
        stale.release()
    }

    @Test
    fun `a device with no vibrator rings without one`() {
        shadowOf(vibrator).setHasVibrator(false)
        val ringer = AndroidIncomingRinger(context)

        ringer.start()
        idle()

        assertTrue(ringer.sounding)
        assertFalse(vibrating())
        ringer.stop()
    }

    @Test
    fun `before Android 13 the vibrate-when-ringing setting decides the vibration`() {
        Settings.System.putInt(context.contentResolver, Settings.System.VIBRATE_WHEN_RINGING, 0)
        val off = AndroidIncomingRinger(context, sdkInt = Build.VERSION_CODES.S_V2)
        off.start()
        idle()
        assertTrue(off.sounding)
        assertFalse(vibrating())
        off.stop()

        Settings.System.putInt(context.contentResolver, Settings.System.VIBRATE_WHEN_RINGING, 1)
        val on = AndroidIncomingRinger(context, sdkInt = Build.VERSION_CODES.S_V2)
        on.start()
        idle()
        assertTrue(vibrating())
        on.stop()
    }

    @Test
    fun `the ring plan never rings louder than the system would`() {
        val normal = AudioManager.RINGER_MODE_NORMAL
        val vibrate = AudioManager.RINGER_MODE_VIBRATE
        val silent = AudioManager.RINGER_MODE_SILENT
        assertEquals(RingPlan(sound = true, vibrate = true), ringPlan(normal, true, vibrateWhenRinging = true))
        assertEquals(RingPlan(sound = true, vibrate = false), ringPlan(normal, true, vibrateWhenRinging = false))
        assertEquals(RingPlan(sound = false, vibrate = true), ringPlan(vibrate, true, vibrateWhenRinging = false))
        assertEquals(RingPlan.SILENT, ringPlan(silent, true, vibrateWhenRinging = true))
        assertEquals("DND that refuses the call wins", RingPlan.SILENT, ringPlan(normal, false, true))
    }

    @Test
    fun `Do Not Disturb lets an unknown caller through only when calls from anyone are allowed`() {
        fun policy(categories: Int, callSenders: Int) =
            NotificationManager.Policy(categories, callSenders, NotificationManager.Policy.PRIORITY_SENDERS_ANY)
        val calls = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS
        val anyone = NotificationManager.Policy.PRIORITY_SENDERS_ANY
        val contacts = NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS
        val priority = NotificationManager.INTERRUPTION_FILTER_PRIORITY

        assertTrue(dndAllowsCall(NotificationManager.INTERRUPTION_FILTER_ALL, null))
        assertTrue(dndAllowsCall(NotificationManager.INTERRUPTION_FILTER_UNKNOWN, null))
        assertTrue(dndAllowsCall(priority, policy(calls, anyone)))
        assertFalse("the caller is never a contact", dndAllowsCall(priority, policy(calls, contacts)))
        assertFalse("calls not allowed at all", dndAllowsCall(priority, policy(0, anyone)))
        assertFalse("an unreadable policy does not ring", dndAllowsCall(priority, null))
        assertFalse(dndAllowsCall(NotificationManager.INTERRUPTION_FILTER_ALARMS, policy(calls, anyone)))
        assertFalse(dndAllowsCall(NotificationManager.INTERRUPTION_FILTER_NONE, policy(calls, anyone)))
    }

    @Test
    fun `the vibration is finite and outlasts the ring timeout`() {
        // ⛔ FINITE SO IT STOPS ON ITS OWN if this process never gets to cancel it.
        val total = AndroidIncomingRinger.VIBRATION_TIMINGS.sum()
        assertTrue(total >= IncomingCallController.RING_TIMEOUT_MILLIS)
        assertEquals(0L, AndroidIncomingRinger.VIBRATION_TIMINGS.first())
    }

    private companion object {
        const val RINGTONE_MILLIS = 2_000
        const val ERROR_AT_MILLIS = 500
    }
}
