// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.media

import android.content.Context
import android.media.AudioManager
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.EventListenable
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.datastream.TextStreamInfo
import io.livekit.android.room.datastream.incoming.TextStreamReceiver
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackException
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import livekit.org.webrtc.FrameCryptorFactory
import livekit.org.webrtc.FrameCryptorKeyProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The HQ voice engine against a mocked SDK, as [LiveKitCallEngineTest] tests the call engine: a
 * real `Room` cannot be built off a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveKitHqVoiceEngineTest {

    private val room = mockk<Room>(relaxed = true)
    private val local = mockk<LocalParticipant>(relaxed = true)
    private val audio = mockk<AudioSwitchHandler>(relaxed = true)
    private val roomEvents = MutableSharedFlow<RoomEvent>()
    private val appContext = mockk<Context>()
    private val context = mockk<Context> { every { applicationContext } returns appContext }
    private val streamHandler = slot<(TextStreamReceiver, Participant.Identity) -> Unit>()
    private val focus = slot<AudioManager.OnAudioFocusChangeListener>()

    @Before
    fun setUp() {
        mockkObject(LiveKit)
        every { LiveKit.create(any(), any(), any()) } returns room
        every { room.events } returns object : EventListenable<RoomEvent> {
            override val events: SharedFlow<RoomEvent> = roomEvents
        }
        every { room.localParticipant } returns local
        every { room.remoteParticipants } returns emptyMap()
        every { room.audioHandler } returns audio
        every { audio.registerOnAudioFocusChangeListener(capture(focus)) } just Runs
        every { room.registerTextStreamHandler(any(), capture(streamHandler)) } just Runs
        // The native key provider is the one piece that needs libwebrtc.
        mockkStatic(FrameCryptorFactory::class)
        every {
            FrameCryptorFactory.createFrameCryptorKeyProvider(any(), any(), any(), any(), any(), any(), any(), any())
        } returns mockk<FrameCryptorKeyProvider>(relaxed = true)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun TestScope.engine() = LiveKitHqVoiceEngine(context, backgroundScope, "room-key")

    /** Collects the engine's events for the test's length. */
    private fun TestScope.collect(engine: HqVoiceEngine): MutableList<HqVoiceEngineEvent> {
        val seen = mutableListOf<HqVoiceEngineEvent>()
        backgroundScope.launch { engine.events.toList(seen) }
        return seen
    }

    private fun agent(state: String? = null, kind: Participant.Kind = Participant.Kind.AGENT): RemoteParticipant =
        mockk {
            every { this@mockk.kind } returns kind
            every { attributes } returns (state?.let { mapOf("lk.agent.state" to it) } ?: emptyMap())
        }

    @Test
    fun `the room is encrypted with the key before the join, and joins the credential's server`() =
        runTest(UnconfinedTestDispatcher()) {
            val installed = slot<E2EEOptions>()
            every { room.e2eeOptions = capture(installed) } just Runs

            engine().connect("wss://media.example", "jwt")

            assertTrue((installed.captured.keyProvider as BaseKeyProvider).enableSharedKey)
            coVerifyOrder {
                room.e2eeOptions = any()
                room.connect("wss://media.example", "jwt", any())
            }
            coVerify { room.connect("wss://media.example", "jwt", any()) }
            verify { room.registerTextStreamHandler("lk.transcription", any()) }
            verify { LiveKit.create(appContext, any(), any()) }
        }

    @Test
    fun `an agent already in the room reports its state at the join`() = runTest(UnconfinedTestDispatcher()) {
        every { room.remoteParticipants } returns mapOf(
            Participant.Identity("agent") to agent("thinking"),
            Participant.Identity("member") to agent("speaking", Participant.Kind.STANDARD),
            Participant.Identity("silent") to agent(null),
        )
        val engine = engine()
        val seen = collect(engine)

        engine.connect("wss://media.example", "jwt")

        assertEquals(listOf(HqVoiceEngineEvent.AgentState("thinking")), seen)
    }

    @Test
    fun `the room's events become the session's`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine()
        val seen = collect(engine)
        engine.connect("wss://media.example", "jwt")
        val payload = """{"type":"HQ_PROPOSAL"}""".encodeToByteArray()

        roomEvents.emit(mockk<RoomEvent.ParticipantConnected> { every { participant } returns agent("listening") })
        roomEvents.emit(
            mockk<RoomEvent.ParticipantAttributesChanged> { every { participant } returns agent("speaking") }
        )
        roomEvents.emit(
            mockk<RoomEvent.DataReceived> {
                every { topic } returns "hq.proposal"
                every { data } returns payload
            }
        )
        roomEvents.emit(mockk<RoomEvent.DataReceived> { every { topic } returns "other" })
        roomEvents.emit(mockk<RoomEvent.ParticipantDisconnected> { every { participant } returns agent() })
        roomEvents.emit(
            mockk<RoomEvent.ParticipantDisconnected> {
                every { participant } returns agent(kind = Participant.Kind.STANDARD)
            },
        )
        roomEvents.emit(mockk<RoomEvent.Reconnecting>())
        roomEvents.emit(mockk<RoomEvent.Disconnected>())

        assertEquals(HqVoiceEngineEvent.AgentState("listening"), seen[0])
        assertEquals(HqVoiceEngineEvent.AgentState("speaking"), seen[1])
        assertArrayEquals(payload, (seen[2] as HqVoiceEngineEvent.Packet).payload)
        assertEquals(HqVoiceEngineEvent.AgentLeft, seen[3])
        assertEquals(HqVoiceEngineEvent.Disconnected, seen[4])
        assertEquals(5, seen.size)
    }

    @Test
    fun `a closed transcript stream is a final segment, the member's own when it names their microphone`() =
        runTest(UnconfinedTestDispatcher()) {
            every { local.getTrackPublication(Track.Source.MICROPHONE) } returns
                mockk<LocalTrackPublication> { every { sid } returns "TR_mic" }
            val engine = engine()
            val seen = collect(engine)
            engine.connect("wss://media.example", "jwt")

            fun stream(id: String, attributes: Map<String, String>, vararg parts: String) = mockk<TextStreamReceiver> {
                every { info } returns mockk<TextStreamInfo> {
                    every { this@mockk.id } returns id
                    every { this@mockk.attributes } returns attributes
                }
                coEvery { readAll() } returns parts.toList()
            }
            streamHandler.captured(
                stream("st-1", mapOf("lk.segment_id" to "seg-1", "lk.transcribed_track_id" to "TR_mic"), "Hel", "lo"),
                Participant.Identity("member"),
            )
            streamHandler.captured(
                stream("st-2", mapOf("lk.transcribed_track_id" to "TR_agent"), "Hi."),
                Participant.Identity("agent"),
            )
            streamHandler.captured(stream("st-3", emptyMap(), "Plain."), Participant.Identity("agent"))

            assertEquals(
                listOf(
                    HqVoiceEngineEvent.Transcript("seg-1", "Hello", fromMember = true),
                    HqVoiceEngineEvent.Transcript("st-2", "Hi.", fromMember = false),
                    HqVoiceEngineEvent.Transcript("st-3", "Plain.", fromMember = false),
                ),
                seen,
            )
        }

    @Test
    fun `the audio being taken ends the session, a duck does not`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine()
        val seen = collect(engine)
        engine.connect("wss://media.example", "jwt")

        focus.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        focus.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        focus.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focus.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(listOf(HqVoiceEngineEvent.AudioInterrupted, HqVoiceEngineEvent.AudioInterrupted), seen)
    }

    @Test
    fun `the microphone answers the state the SDK is left in`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine()
        coEvery { local.setMicrophoneEnabled(true) } returns true

        assertTrue(engine.setMicrophone(true))
        coEvery { local.setMicrophoneEnabled(false) } returns false
        assertTrue("an unmade mute reads as still open", engine.setMicrophone(false))

        coEvery { local.setMicrophoneEnabled(true) } throws TrackException.PublishException("refused")
        assertFalse("a refused open leaves it closed", engine.setMicrophone(true))
        coEvery { local.setMicrophoneEnabled(false) } throws TrackException.PublishException("refused")
        assertTrue("a refused close leaves it open", engine.setMicrophone(false))
        coEvery { local.setMicrophoneEnabled(true) } throws IllegalStateException("audio device lost")
        assertFalse("any SDK failure is answered, never thrown", engine.setMicrophone(true))
        coEvery { local.setMicrophoneEnabled(true) } throws kotlinx.coroutines.CancellationException("left")
        val cancelled = runCatching { engine.setMicrophone(true) }.exceptionOrNull()
        assertTrue("a cancellation still goes on up", cancelled is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `disconnecting leaves the room, gives back the focus and the stream, and ends the events`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = engine()
            var ended = false
            backgroundScope.launch {
                engine.events.collect {}
                ended = true
            }
            engine.connect("wss://media.example", "jwt")

            engine.disconnect()

            coVerify { room.disconnect() }
            verify { room.unregisterTextStreamHandler("lk.transcription") }
            verify { audio.unregisterOnAudioFocusChangeListener(focus.captured) }
            assertTrue("the event flow completes", ended)
        }

    @Test
    fun `leaving before a join still leaves cleanly`() = runTest(UnconfinedTestDispatcher()) {
        engine().disconnect()

        coVerify { room.disconnect() }
    }

    @Test
    fun `a transcript heard before the microphone was ever published is HQ's`() =
        runTest(UnconfinedTestDispatcher()) {
            every { local.getTrackPublication(Track.Source.MICROPHONE) } returns null
            val engine = engine()
            val seen = collect(engine)
            engine.connect("wss://media.example", "jwt")

            streamHandler.captured(
                mockk {
                    every { info } returns mockk {
                        every { id } returns "st-9"
                        every { attributes } returns mapOf("lk.transcribed_track_id" to "TR_x")
                    }
                    coEvery { readAll() } returns listOf("Hi.")
                },
                Participant.Identity("agent"),
            )

            assertEquals(listOf(HqVoiceEngineEvent.Transcript("st-9", "Hi.", fromMember = false)), seen)
        }

    @Test
    fun `a room without the SDK's audio switch still joins and leaves`() = runTest(UnconfinedTestDispatcher()) {
        every { room.audioHandler } returns mockk(relaxed = true)
        val engine = engine()

        engine.connect("wss://media.example", "jwt")
        engine.disconnect()

        coVerify { room.disconnect() }
    }

    @Test
    fun `the wire names are the agent's`() {
        assertEquals("hq.proposal", HqVoiceRoomWire.PROPOSAL_TOPIC)
        assertEquals("lk.transcription", HqVoiceRoomWire.TRANSCRIPTION_TOPIC)
        assertEquals("lk.segment_id", HqVoiceRoomWire.SEGMENT_ID_ATTRIBUTE)
        assertEquals("lk.transcribed_track_id", HqVoiceRoomWire.TRANSCRIBED_TRACK_ID_ATTRIBUTE)
        assertEquals("lk.agent.state", HqVoiceRoomWire.AGENT_STATE_ATTRIBUTE)
    }
}
