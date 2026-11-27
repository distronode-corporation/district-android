// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.media

import android.content.Context
import android.media.AudioManager
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The wire names an HQ voice room speaks in, as the agent and the web use them.
 *
 * ⚠️ KEPT HERE AS WELL AS IN core-model's `HqVoice`, because this module does not depend on the
 * model (that would put the serialization runtime on its locked classpath for five strings). The
 * app's `HqVoiceAudioTest` holds the two copies equal.
 */
object HqVoiceRoomWire {
    const val PROPOSAL_TOPIC: String = "hq.proposal"
    const val TRANSCRIPTION_TOPIC: String = "lk.transcription"
    const val SEGMENT_ID_ATTRIBUTE: String = "lk.segment_id"
    const val TRANSCRIBED_TRACK_ID_ATTRIBUTE: String = "lk.transcribed_track_id"
    const val AGENT_STATE_ATTRIBUTE: String = "lk.agent.state"
}

/**
 * The LiveKit room for HQ by voice.
 *
 * ⛔ THIN BY POLICY, for [LiveKitCallEngine]'s reason: a real `Room` cannot be built off a device,
 * so every line here is one more SDK behaviour a mock stands in for. This class only forwards.
 *
 * ⛔ ALWAYS ENCRYPTED. The key is handed to the SDK verbatim before the join, as
 * [LiveKitCallEngine] does; the session joins only a credential that passed `isJoinable`.
 *
 * ⛔ IT PUBLISHES THE MICROPHONE AND NO DATA, and the microphone is OFF at the join: the session
 * decides when it opens (open mode at once, push-to-talk only while held).
 *
 * ⛔ A FOCUS LOSS ENDS THE SESSION. The SDK's audio handler holds the audio focus for the room; a
 * phone call, a District call's ringer (which takes transient focus) or another app's audio taking
 * it is reported as [HqVoiceEngineEvent.AudioInterrupted]. A duck (`CAN_DUCK`) is not a loss.
 */
class LiveKitHqVoiceEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val e2eeKey: String,
) : HqVoiceEngine {

    private val room: Room = LiveKit.create(context.applicationContext)
    private val channel = Channel<HqVoiceEngineEvent>(Channel.UNLIMITED)
    override val events: Flow<HqVoiceEngineEvent> = channel.receiveAsFlow()
    private var eventsJob: Job? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            channel.trySend(HqVoiceEngineEvent.AudioInterrupted)
        }
    }

    override suspend fun connect(url: String, token: String) {
        // ⛔ SET BEFORE `connect`: the SDK builds its E2EE manager inside it. See LiveKitCallEngine.
        room.e2eeOptions = E2EEOptions(sharedKey = e2eeKey)
        (room.audioHandler as? AudioSwitchHandler)?.registerOnAudioFocusChangeListener(focusListener)
        room.registerTextStreamHandler(HqVoiceRoomWire.TRANSCRIPTION_TOPIC) { reader, _ ->
            scope.launch {
                // ⛔ READ AFTER THE STREAM CLOSED: a closed stream is a final segment, and the
                // attributes sent at the close are seen (as iOS reads them).
                val text = reader.readAll().joinToString("")
                val attributes = reader.info.attributes
                val transcribed = attributes[HqVoiceRoomWire.TRANSCRIBED_TRACK_ID_ATTRIBUTE]
                channel.trySend(
                    HqVoiceEngineEvent.Transcript(
                        id = attributes[HqVoiceRoomWire.SEGMENT_ID_ATTRIBUTE] ?: reader.info.id,
                        text = text,
                        fromMember = transcribed != null && transcribed == microphoneTrackSid(),
                    ),
                )
            }
        }
        eventsJob = scope.launch { room.events.collect(::forward) }
        room.connect(url, token)
        // ⚠️ AN AGENT ALREADY IN THE ROOM sent its state before this client could hear it.
        room.remoteParticipants.values.forEach(::reportAgentState)
    }

    private fun forward(event: RoomEvent) {
        when (event) {
            is RoomEvent.Disconnected -> channel.trySend(HqVoiceEngineEvent.Disconnected)
            is RoomEvent.ParticipantDisconnected ->
                if (event.participant.kind == Participant.Kind.AGENT) {
                    channel.trySend(HqVoiceEngineEvent.AgentLeft)
                }
            is RoomEvent.ParticipantConnected -> reportAgentState(event.participant)
            is RoomEvent.ParticipantAttributesChanged -> reportAgentState(event.participant)
            is RoomEvent.DataReceived ->
                if (event.topic == HqVoiceRoomWire.PROPOSAL_TOPIC) {
                    channel.trySend(HqVoiceEngineEvent.Packet(event.data))
                }
            else -> Unit
        }
    }

    private fun reportAgentState(participant: Participant) {
        if (participant.kind != Participant.Kind.AGENT) return
        participant.attributes[HqVoiceRoomWire.AGENT_STATE_ATTRIBUTE]?.let {
            channel.trySend(HqVoiceEngineEvent.AgentState(it))
        }
    }

    private fun microphoneTrackSid(): String? =
        room.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.sid

    /**
     * ⛔ NO SDK EXCEPTION ESCAPES: the change runs on the application's scope, where an uncaught
     * exception ends the process. An exception leaves the microphone where it was, and that is what
     * is answered; a cancellation goes on up. ⚠️ An `Error` (a broken native library) is not caught
     * on purpose: the room is unusable then, and hiding it would only move the crash.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun setMicrophone(enabled: Boolean): Boolean =
        try {
            // ⚠️ THE SDK ANSWERS FALSE FOR A CHANGE IT COULD NOT MAKE, which leaves the microphone
            // where it was: a failed mute must not read as muted.
            if (room.localParticipant.setMicrophoneEnabled(enabled)) enabled else !enabled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // ⚠️ A REFUSED CHANGE LEAVES THE MICROPHONE WHERE IT WAS, which is what is answered.
            !enabled
        }

    override suspend fun disconnect() {
        eventsJob?.cancel()
        eventsJob = null
        room.unregisterTextStreamHandler(HqVoiceRoomWire.TRANSCRIPTION_TOPIC)
        (room.audioHandler as? AudioSwitchHandler)?.unregisterOnAudioFocusChangeListener(focusListener)
        room.disconnect()
        channel.close()
    }
}
