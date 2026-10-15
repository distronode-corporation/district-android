package com.distronode.districtai.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import java.io.IOException

/**
 * The sound and the vibration of a ringing inbound call.
 *
 * ⛔ A SELF-MANAGED CALL RINGS ONLY IF THE APP RINGS IT, AND 3.0.0 DID NOT. Telecom's own ringer
 * runs for a self-managed connection but plays nothing (its `AsyncRingtonePlayer` holds no ringtone
 * when `isSelfManaged` is true: the platform leaves the ring to the app). Measured on API 37 against
 * 3.0.0 (ring test, 2026-10-15): the connection reached RINGING, the heads-up notification showed,
 * and the only sound was the notification channel's single 1.9 s chime. No
 * `USAGE_NOTIFICATION_RINGTONE` player was ever created. This is that player.
 *
 * ⚠️ AN INTERFACE FOR THE REASON [ForegroundCallHost] IS ONE: what matters about the ringer is WHEN
 * it starts and that it stops on every terminal path, which a recorder can assert without a speaker.
 * [AndroidIncomingRinger] is the half that needs the device, and [ringPlan] and [dndAllowsCall] are
 * the decisions it makes, kept pure so every branch is testable.
 *
 * ⚠️ BOTH METHODS ARE IDEMPOTENT. The controller stops the ring at the Answer press and again from
 * the exit path, and a system surface can end a call from a binder thread at any moment.
 */
internal interface IncomingRinger {
    fun start()
    fun stop()
}

/** What a ring may do on this device right now. */
internal data class RingPlan(val sound: Boolean, val vibrate: Boolean) {
    internal companion object {
        val SILENT: RingPlan = RingPlan(sound = false, vibrate = false)
    }
}

/**
 * What the system would do with a call ring, from the three settings that decide it.
 *
 * ⛔ THE RULE IS "NEVER LOUDER THAN THE SYSTEM WOULD BE". Silent mode silences the ring and its
 * vibration, vibrate mode vibrates only, and Do Not Disturb that does not let this call through
 * ([dndAllowsCall]) silences both. A ringing business phone that ignores a user's silent switch is
 * the fastest way to get the app's notifications turned off for good.
 *
 * @param vibrateWhenRinging the user's "vibrate for calls" setting; see [AndroidIncomingRinger].
 */
internal fun ringPlan(ringerMode: Int, dndAllows: Boolean, vibrateWhenRinging: Boolean): RingPlan =
    when {
        !dndAllows -> RingPlan.SILENT
        ringerMode == AudioManager.RINGER_MODE_SILENT -> RingPlan.SILENT
        ringerMode == AudioManager.RINGER_MODE_VIBRATE -> RingPlan(sound = false, vibrate = true)
        else -> RingPlan(sound = true, vibrate = vibrateWhenRinging)
    }

/**
 * Whether Do Not Disturb lets this call ring.
 *
 * ⛔ THE CALLER IS ALWAYS UNKNOWN TO THE DEVICE, SO ONLY "CALLS FROM ANYONE" LETS IT THROUGH. The
 * push carries no number (see `DistrictConnection`), so this call can never match a starred contact
 * or a contact, which are the other two sender settings. Under priority-only DND it rings when the
 * policy allows calls AND allows them from any sender; under alarms-only or total silence it never
 * does. ⚠️ A null policy (the platform refused to say) is treated as "does not allow": a missed ring
 * is the lesser failure than a phone that rings through a user's DND.
 */
internal fun dndAllowsCall(interruptionFilter: Int, policy: NotificationManager.Policy?): Boolean =
    when (interruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL,
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
        -> true
        NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
            policy != null &&
                policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0 &&
                policy.priorityCallSenders == NotificationManager.Policy.PRIORITY_SENDERS_ANY
        else -> false
    }

/**
 * The real ringer: the user's ringtone on the ring stream, and the call vibration.
 *
 * ⛔ THE SOUND IS BOUNDED BY THIS PROCESS BEING ALIVE, AND THAT IS WHY IT LOOPS BY RESTARTING
 * RATHER THAN WITH `isLooping`. `MediaPlayer` plays in the media server, not in this process, so a
 * native loop keeps going if this process is frozen or killed mid-ring, and nothing would ever stop
 * it. Restarting from the completion callback means the loop needs this process to run, so the
 * worst case is one play-through of the ringtone. The vibration is bounded the same way, by
 * construction: one finite waveform a little longer than the ring timeout, never a repeating one.
 *
 * ⛔ `Settings.System.DEFAULT_RINGTONE_URI` IS THE FIRST SOURCE, NOT THE ACTUAL RINGTONE'S URI.
 * The settings URI is served from the system's own cached copy of the ringtone, which any app can
 * read; the actual URI can be a file in shared storage this app has no permission to open. The
 * actual URI is the fallback. ⚠️ A user who chose "None" as their ringtone gets no sound (and still
 * the vibration their settings ask for), which is what the system does for its own calls.
 *
 * ⚠️ AUDIO FOCUS IS REQUESTED BEFORE THE SOUND AND A REFUSAL MEANS NO SOUND. The platform refuses
 * transient focus during a phone call, and a softphone ring blaring over a cellular conversation is
 * worse than a silent heads-up; the vibration still runs.
 *
 * ⚠️ SYNCHRONISED, because the controller reaches [stop] from a Telecom binder thread (a hang-up on
 * a headset) as well as from the main thread.
 */
internal class AndroidIncomingRinger(
    context: Context,
    /** ⚠️ Injectable so the pre-API-33 vibration setting is testable on one Robolectric level. */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : IncomingRinger {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notifications =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Suppress("DEPRECATION")
    private val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?

    private var ringing = false
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    /** The vibrator a ring started, so [stop] cancels exactly what was started. */
    private var vibrating: Vibrator? = null

    /** ⚠️ For the tests: whether the ringtone is playing right now. */
    internal val sounding: Boolean
        @Synchronized get() = player?.isPlaying == true

    @Synchronized
    override fun start() {
        if (ringing) return
        ringing = true
        val plan = ringPlan(
            ringerMode = audio.ringerMode,
            dndAllows = dndAllowsCall(notifications.currentInterruptionFilter, notificationPolicy()),
            vibrateWhenRinging = vibrateWhenRinging(),
        )
        if (plan.vibrate) vibrate()
        if (plan.sound) playSound()
    }

    @Synchronized
    override fun stop() {
        ringing = false
        releaseSound()
        vibrating?.cancel()
        vibrating = null
    }

    /** The player, if any, and the focus, if held. ⚠️ The one teardown for the sound, from every path. */
    private fun releaseSound() {
        player?.release()
        player = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    /**
     * ⚠️ API 33+ LEAVES THE DECISION TO THE SYSTEM, WHICH OWNS IT THERE. From Android 13 the
     * "vibrate for calls" control is the ring vibration intensity, which the vibrator service applies
     * itself to every vibration carrying the ringtone usage (an intensity of off drops it), and
     * Telecom ignores the older `VIBRATE_WHEN_RINGING` flag. Before 13 that flag is the setting.
     */
    private fun vibrateWhenRinging(): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU ||
            Settings.System.getInt(appContext.contentResolver, Settings.System.VIBRATE_WHEN_RINGING, 0) != 0

    /**
     * ⚠️ READING THE POLICY CAN BE REFUSED on some builds without notification-policy access, and a
     * refusal is answered with null, which [dndAllowsCall] treats as "does not allow".
     */
    private fun notificationPolicy(): NotificationManager.Policy? =
        try {
            notifications.notificationPolicy
        } catch (ignored: SecurityException) {
            null
        }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val device = vibrator?.takeIf { it.hasVibrator() } ?: return
        // ⚠️ THE DEPRECATED (effect, AudioAttributes) OVERLOAD, ON PURPOSE: it exists from API 26
        // and the platform maps the ringtone usage onto the ring vibration settings on 33+, so one
        // call is right on every level this app supports.
        device.vibrate(VibrationEffect.createWaveform(VIBRATION_TIMINGS, NO_REPEAT), RING_ATTRIBUTES)
        vibrating = device
    }

    private fun playSound() {
        // ⚠️ "None" chosen as the ringtone: nothing to play, as the system would play nothing.
        val actual = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE)
            ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(RING_ATTRIBUTES)
            .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        focus = request
        val created = listOf(Settings.System.DEFAULT_RINGTONE_URI, actual).firstNotNullOfOrNull(::playerFor)
        // Neither source opened: give the focus back rather than holding it in silence.
        if (created == null) {
            releaseSound()
            return
        }
        player = created
        created.prepareAsync()
    }

    /** A player prepared to loop [uri], or null when the source cannot be opened. */
    private fun playerFor(uri: Uri): MediaPlayer? {
        val candidate = MediaPlayer()
        candidate.setAudioAttributes(RING_ATTRIBUTES)
        candidate.setOnPreparedListener { prepared -> onReady(prepared) }
        candidate.setOnCompletionListener { finished -> onReady(finished) }
        candidate.setOnErrorListener { failed, _, _ ->
            onFailed(failed)
            true
        }
        return try {
            candidate.setDataSource(appContext, uri)
            candidate
        } catch (ignored: IOException) {
            candidate.release()
            null
        } catch (ignored: SecurityException) {
            candidate.release()
            null
        } catch (ignored: IllegalArgumentException) {
            candidate.release()
            null
        }
    }

    /**
     * Prepared, or one play-through finished: play (again) from the start.
     *
     * ⛔ ONLY WHILE IT IS STILL THE CURRENT PLAYER. A callback queued before [stop] arrives after
     * it, against a released player that must not be touched. ⚠️ Internal so a test can deliver
     * that stale callback, which Robolectric's player never queues past a release.
     */
    @Synchronized
    internal fun onReady(source: MediaPlayer) {
        if (source !== player) return
        source.seekTo(0)
        source.start()
    }

    /**
     * The player failed mid-ring: release it and give the focus back.
     *
     * ⛔ ONLY WHILE IT IS STILL THE CURRENT PLAYER, for the reason on [onReady]: a stale error must
     * not abandon the focus a LATER ring holds. ⚠️ Internal for the same test.
     */
    @Synchronized
    internal fun onFailed(source: MediaPlayer) {
        if (source !== player) return
        releaseSound()
    }

    internal companion object {
        /** ⛔ The ring's usage: the ring stream's volume, and the ring vibration settings. */
        val RING_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        private const val VIBRATE_MILLIS = 1_000L
        private const val PAUSE_MILLIS = 1_000L

        /**
         * ⚠️ ENOUGH CYCLES TO OUTLAST THE RING TIMEOUT, AND NO MORE. See the ⛔ on the class: a
         * finite waveform stops on its own if this process never gets to cancel it.
         */
        private val CYCLES: Int =
            (IncomingCallController.RING_TIMEOUT_MILLIS / (VIBRATE_MILLIS + PAUSE_MILLIS)).toInt() + 1

        val VIBRATION_TIMINGS: LongArray = LongArray(CYCLES * 2 + 1) { index ->
            when {
                index == 0 -> 0L
                index % 2 == 1 -> VIBRATE_MILLIS
                else -> PAUSE_MILLIS
            }
        }

        private const val NO_REPEAT = -1
    }
}
