package com.distronode.districtai.ui.hq.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.distronode.districtai.telecom.DistrictCallRegistry

/** What the voice session asks of the device's audio, behind a seam so it is testable. */
interface HqVoiceAudio {
    /** Whether a call or a meeting holds the audio, so voice must not start. */
    fun isBusy(): Boolean

    /** Whether the microphone permission is held. ⛔ Asked only after the member's consent. */
    fun hasMicrophonePermission(): Boolean
}

/**
 * The device's audio.
 *
 * ⚠️ BUSY IS THE AUDIO MODE, NOT A LIST OF THIS APP'S SCREENS: a phone call puts the device in
 * `MODE_IN_CALL`, and a District call, a meeting or another app's voice call in
 * `MODE_IN_COMMUNICATION`. A softphone call this app placed is also busy while Telecom holds its
 * connection, before the room has set the mode.
 */
class AndroidHqVoiceAudio(private val context: Context) : HqVoiceAudio {
    override fun isBusy(): Boolean {
        val mode = context.getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            DistrictCallRegistry.current() != null
    }

    override fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
