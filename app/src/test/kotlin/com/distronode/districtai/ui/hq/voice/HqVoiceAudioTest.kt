// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.hq.voice

import android.Manifest
import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.media.HqVoiceRoomWire
import com.distronode.districtai.core.model.HqVoice
import com.distronode.districtai.telecom.DistrictCallRegistry
import com.distronode.districtai.telecom.DistrictConnection
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The device's audio as the voice session reads it, and the room's wire names held equal. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class HqVoiceAudioTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(AudioManager::class.java)
    private val audio = AndroidHqVoiceAudio(app)

    @After
    fun tearDown() {
        DistrictCallRegistry.current()?.let(DistrictCallRegistry::release)
        manager.mode = AudioManager.MODE_NORMAL
    }

    @Test
    fun `a phone call, a voice call or this app's own call is busy, nothing else is`() {
        assertFalse(audio.isBusy())

        manager.mode = AudioManager.MODE_IN_CALL
        assertTrue(audio.isBusy())
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        assertTrue(audio.isBusy())
        manager.mode = AudioManager.MODE_RINGTONE
        assertFalse("a ring is ended by the focus loss, not refused here", audio.isBusy())

        manager.mode = AudioManager.MODE_NORMAL
        DistrictCallRegistry.adopt(DistrictConnection.outgoing("+14165550100") {})
        assertTrue(audio.isBusy())
    }

    @Test
    fun `the microphone permission is read, never asked for, here`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(audio.hasMicrophonePermission())

        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(audio.hasMicrophonePermission())
    }

    @Test
    fun `the room's wire names are the model's`() {
        assertEquals(HqVoice.PROPOSAL_TOPIC, HqVoiceRoomWire.PROPOSAL_TOPIC)
        assertEquals(HqVoice.TRANSCRIPTION_TOPIC, HqVoiceRoomWire.TRANSCRIPTION_TOPIC)
        assertEquals(HqVoice.SEGMENT_ID_ATTRIBUTE, HqVoiceRoomWire.SEGMENT_ID_ATTRIBUTE)
        assertEquals(HqVoice.TRANSCRIBED_TRACK_ID_ATTRIBUTE, HqVoiceRoomWire.TRANSCRIBED_TRACK_ID_ATTRIBUTE)
        assertEquals(HqVoice.AGENT_STATE_ATTRIBUTE, HqVoiceRoomWire.AGENT_STATE_ATTRIBUTE)
    }
}
