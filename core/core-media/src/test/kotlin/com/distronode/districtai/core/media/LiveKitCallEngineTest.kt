package com.distronode.districtai.core.media

import android.content.Context
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.EventListenable
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.Runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import livekit.org.webrtc.FrameCryptorFactory
import livekit.org.webrtc.FrameCryptorKeyProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The engine against a mocked SDK.
 *
 * A real `Room` cannot be built off a device: `LiveKit.create` initialises libwebrtc and fails
 * with `UnsatisfiedLinkError` (no `lkjingle_peerconnection_so`). So the SDK surface the engine
 * touches is mocked with MockK, and each test asserts what the engine does with it: the state it
 * publishes, and the calls it forwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveKitCallEngineTest {

    private val room = mockk<Room>(relaxed = true)
    private val local = mockk<LocalParticipant>(relaxed = true)
    private val events = MutableSharedFlow<RoomEvent>()
    private val appContext = mockk<Context>()
    private val callerContext = mockk<Context> { every { applicationContext } returns appContext }

    @Before
    fun setUp() {
        mockkObject(LiveKit)
        every { LiveKit.create(any(), any(), any()) } returns room
        every { room.events } returns object : EventListenable<RoomEvent> {
            override val events: SharedFlow<RoomEvent> = this@LiveKitCallEngineTest.events
        }
        every { room.localParticipant } returns local
        every { room.remoteParticipants } returns emptyMap()
    }

    @After
    fun tearDown() = unmockkAll()

    private fun TestScope.engine() = LiveKitCallEngine(callerContext, backgroundScope)

    private fun remote(
        identity: String?,
        name: String? = null,
        speaking: Boolean = false,
        micOn: Boolean = true,
        kind: Participant.Kind = Participant.Kind.STANDARD,
        video: List<Pair<TrackPublication, Track?>> = emptyList(),
    ): RemoteParticipant = mockk {
        every { this@mockk.identity } returns identity?.let { Participant.Identity(it) }
        every { this@mockk.name } returns name
        every { isSpeaking } returns speaking
        every { isMicrophoneEnabled } returns micOn
        every { this@mockk.kind } returns kind
        every { videoTrackPublications } returns video
    }

    private fun publication(subscribed: Boolean): TrackPublication =
        mockk { every { this@mockk.subscribed } returns subscribed }

    private fun videoTrack(sid: String?, name: String = "camera"): RemoteVideoTrack = mockk {
        every { this@mockk.sid } returns sid
        every { this@mockk.name } returns name
    }

    private fun roomHas(vararg participants: RemoteParticipant) {
        every { room.remoteParticipants } returns participants.associateBy {
            Participant.Identity(it.identity?.value ?: "")
        }
    }

    @Test
    fun `the room is built from the application context and the engine starts idle`() = runTest {
        // The engine outlives the screen that asked for it, so holding an Activity context here
        // would leak it for the length of a call.
        val engine = engine()

        verify { LiveKit.create(appContext, any(), any()) }
        assertEquals(CallConnectionState.Idle, engine.connectionState.value)
        assertEquals(emptyList<MediaParticipant>(), engine.participants.value)
        assertFalse(engine.isMicrophoneEnabled.value)
        assertFalse(engine.isCameraEnabled.value)
    }

    @Test
    fun `connect reads Connecting during the join, then Connected with the room's participants`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = engine()
            var duringJoin: CallConnectionState? = null
            coEvery { room.connect(any(), any(), any()) } coAnswers {
                duringJoin = engine.connectionState.value
                roomHas(remote("user-a", name = "Ada"))
                null
            }

            engine.connect("wss://media.example", "tok-1")

            coVerify { room.connect("wss://media.example", "tok-1", any()) }
            assertEquals(CallConnectionState.Connecting, duringJoin)
            assertEquals(CallConnectionState.Connected, engine.connectionState.value)
            assertEquals(listOf(MediaParticipant(identity = "user-a", name = "Ada")), engine.participants.value)
        }

    @Test
    fun `a join with no key or a blank key clears the room's encryption options`() =
        runTest(UnconfinedTestDispatcher()) {
            // The room outlives a disconnect, so a join without a key must actively install null
            // rather than inherit the previous room's key.
            val engine = engine()

            engine.connect("wss://media.example", "tok", null)
            engine.connect("wss://media.example", "tok", "   ")

            verify(exactly = 2) { room.e2eeOptions = null }
        }

    @Test
    fun `a join with a key installs it as a shared passphrase, UTF-8 encoded and never decoded`() =
        runTest(UnconfinedTestDispatcher()) {
            // The native key provider is the one piece that needs libwebrtc; everything the SDK
            // does with the passphrase on the way to it runs for real.
            val rtcProvider = mockk<FrameCryptorKeyProvider>(relaxed = true)
            mockkStatic(FrameCryptorFactory::class)
            every {
                FrameCryptorFactory.createFrameCryptorKeyProvider(
                    any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } returns rtcProvider
            val installed = slot<E2EEOptions>()
            every { room.e2eeOptions = capture(installed) } just Runs
            val sharedKey = slot<ByteArray>()
            every { rtcProvider.setSharedKey(any(), capture(sharedKey)) } returns true
            val key = "cUwBK6pBSbabCBfQZ8VZW+bW5dOg2dwb3JG8q7E4Jc4="

            engine().connect("wss://media.example", "tok", key)

            assertTrue((installed.captured.keyProvider as BaseKeyProvider).enableSharedKey)
            assertArrayEquals(key.toByteArray(Charsets.UTF_8), sharedKey.captured)
        }

    @Test
    fun `a failed join reads Failed with the SDK's message and rethrows the same error`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = engine()
            val boom = IOException("socket closed")
            coEvery { room.connect(any(), any(), any()) } throws boom

            val thrown = runCatching { engine.connect("wss://media.example", "tok") }.exceptionOrNull()

            assertSame(boom, thrown)
            assertEquals(CallConnectionState.Failed("socket closed"), engine.connectionState.value)
        }

    @Test
    fun `room lifecycle events drive the connection state`() = runTest(UnconfinedTestDispatcher()) {
        val engine = engine()
        engine.connect("wss://media.example", "tok")

        events.emit(RoomEvent.Reconnecting(room))
        assertEquals(CallConnectionState.Reconnecting, engine.connectionState.value)

        events.emit(RoomEvent.Reconnected(room))
        assertEquals(CallConnectionState.Connected, engine.connectionState.value)

        events.emit(RoomEvent.Disconnected(room, null, DisconnectReason.DUPLICATE_IDENTITY))
        assertEquals(CallConnectionState.Disconnected("DUPLICATE_IDENTITY"), engine.connectionState.value)
    }

    @Test
    fun `every participant or track event re-reads the room, and other events do not`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = engine()
            engine.connect("wss://media.example", "tok")
            val someone = remote("user-x")
            val pub = publication(subscribed = true)
            val track = videoTrack("TR_X")
            val participantEvents = listOf(
                RoomEvent.ParticipantConnected(room, someone),
                RoomEvent.ParticipantDisconnected(room, someone),
                RoomEvent.TrackSubscribed(room, track, pub, someone),
                RoomEvent.TrackUnsubscribed(room, track, pub, someone),
                RoomEvent.ActiveSpeakersChanged(room, listOf(someone)),
                RoomEvent.TrackMuted(room, pub, someone),
                RoomEvent.TrackUnmuted(room, pub, someone),
            )

            participantEvents.forEachIndexed { i, event ->
                roomHas(remote("user-$i"))
                events.emit(event)
                assertEquals(
                    "${event::class.simpleName} refreshes the list",
                    listOf("user-$i"),
                    engine.participants.value.map { it.identity },
                )
            }

            roomHas(remote("user-unrelated"))
            events.emit(RoomEvent.RoomMetadataChanged(room, "new", "old"))
            assertEquals(listOf("user-6"), engine.participants.value.map { it.identity })
        }

    @Test
    fun `a second connect replaces the first event collector rather than adding one`() =
        runTest(UnconfinedTestDispatcher()) {
            // Two collectors would both apply every event, and one of them would outlive the join
            // it belonged to.
            val engine = engine()

            engine.connect("wss://media.example", "tok-1")
            assertEquals(1, events.subscriptionCount.value)
            engine.connect("wss://media.example", "tok-2")
            assertEquals(1, events.subscriptionCount.value)
        }

    @Test
    fun `disconnect stops listening, leaves the room and reads Disconnected with no reason`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = engine()
            engine.connect("wss://media.example", "tok")

            engine.disconnect()

            verify { room.disconnect() }
            assertEquals(0, events.subscriptionCount.value)
            assertEquals(CallConnectionState.Disconnected(), engine.connectionState.value)
            events.emit(RoomEvent.Reconnecting(room))
            assertEquals(CallConnectionState.Disconnected(), engine.connectionState.value)
        }

    @Test
    fun `disconnect is safe before any connect and again after one`() =
        runTest(UnconfinedTestDispatcher()) {
            // Hang-up paths race, so a second disconnect (or one with nothing joined) must still
            // leave the room and settle on Disconnected rather than throw.
            val engine = engine()

            engine.disconnect()
            engine.disconnect()

            verify(exactly = 2) { room.disconnect() }
            assertEquals(CallConnectionState.Disconnected(), engine.connectionState.value)
        }

    @Test
    fun `microphone and camera flags follow what the SDK accepted`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = engine()

            engine.setMicrophoneEnabled(true)
            engine.setCameraEnabled(true)
            coVerify { local.setMicrophoneEnabled(true) }
            coVerify { local.setCameraEnabled(true) }
            assertTrue(engine.isMicrophoneEnabled.value)
            assertTrue(engine.isCameraEnabled.value)

            engine.setMicrophoneEnabled(false)
            engine.setCameraEnabled(false)
            coVerify { local.setMicrophoneEnabled(false) }
            coVerify { local.setCameraEnabled(false) }
            assertFalse(engine.isMicrophoneEnabled.value)
            assertFalse(engine.isCameraEnabled.value)
        }

    @Test
    fun `a publish the SDK refuses leaves the flag where it was`() =
        runTest(UnconfinedTestDispatcher()) {
            // The flag is set after the SDK call returns, so a refused publish (no permission, no
            // device) never shows the control as on.
            val engine = engine()
            coEvery { local.setMicrophoneEnabled(true) } throws IllegalStateException("no mic")
            coEvery { local.setCameraEnabled(true) } throws IllegalStateException("no camera")

            runCatching { engine.setMicrophoneEnabled(true) }
            runCatching { engine.setCameraEnabled(true) }

            assertFalse(engine.isMicrophoneEnabled.value)
            assertFalse(engine.isCameraEnabled.value)
        }

    @Test
    fun `flipping the camera switches the published local camera track`() =
        runTest(UnconfinedTestDispatcher()) {
            val camera = mockk<LocalVideoTrack>(relaxed = true)
            val pub = mockk<LocalTrackPublication> { every { track } returns camera }
            every { local.getTrackPublication(Track.Source.CAMERA) } returns pub

            engine().flipCamera()

            verify { camera.switchCamera(null, null) }
        }

    @Test
    fun `flipping with no camera published, or no track behind it, does nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            // A softphone call never publishes video, and a publication can exist before its track
            // is attached; neither is an error.
            val engine = engine()
            every { local.getTrackPublication(Track.Source.CAMERA) } returns null
            engine.flipCamera()

            val empty = mockk<LocalTrackPublication> { every { track } returns null }
            every { local.getTrackPublication(Track.Source.CAMERA) } returns empty
            engine.flipCamera()

            verify(exactly = 2) { local.getTrackPublication(Track.Source.CAMERA) }
            verify { empty.track }
        }

    private fun switchHandler(vararg devices: AudioDevice): AudioSwitchHandler =
        mockk(relaxed = true) { every { availableAudioDevices } returns devices.toList() }

    @Test
    fun `speakerphone on selects the loudspeaker`() = runTest {
        val speaker = mockk<AudioDevice.Speakerphone>()
        val handler = switchHandler(mockk<AudioDevice.Earpiece>(), speaker)
        every { room.audioHandler } returns handler

        engine().setSpeakerphoneOn(true)

        verify { handler.selectDevice(speaker) }
    }

    @Test
    fun `speakerphone on with no loudspeaker leaves the route alone`() = runTest {
        val handler = switchHandler(mockk<AudioDevice.Earpiece>())
        every { room.audioHandler } returns handler

        engine().setSpeakerphoneOn(true)

        verify(exactly = 0) { handler.selectDevice(any()) }
    }

    @Test
    fun `speakerphone off prefers the earpiece`() = runTest {
        val earpiece = mockk<AudioDevice.Earpiece>()
        val handler = switchHandler(mockk<AudioDevice.Speakerphone>(), mockk<AudioDevice.BluetoothHeadset>(), earpiece)
        every { room.audioHandler } returns handler

        engine().setSpeakerphoneOn(false)

        verify { handler.selectDevice(earpiece) }
    }

    @Test
    fun `speakerphone off on a device with no earpiece picks the first non-speaker route`() = runTest {
        // Tablets have no earpiece; "off" then means whatever is not the loudspeaker.
        val headset = mockk<AudioDevice.WiredHeadset>()
        val handler = switchHandler(mockk<AudioDevice.Speakerphone>(), headset)
        every { room.audioHandler } returns handler

        engine().setSpeakerphoneOn(false)

        verify { handler.selectDevice(headset) }
    }

    @Test
    fun `speakerphone off with only a loudspeaker leaves the route alone`() = runTest {
        val handler = switchHandler(mockk<AudioDevice.Speakerphone>())
        every { room.audioHandler } returns handler

        engine().setSpeakerphoneOn(false)

        verify(exactly = 0) { handler.selectDevice(any()) }
    }

    @Test
    fun `speakerphone is a no-op when the room is not routing through AudioSwitch`() = runTest {
        // A room built with a custom audio handler owns its own routing; the engine must not
        // reach past it.
        val other = mockk<AudioHandler>()
        every { room.audioHandler } returns other

        engine().setSpeakerphoneOn(true)

        verify { room.audioHandler }
        confirmVerified(other)
    }

    @Test
    fun `a participant maps every field the UI renders, and the first subscribed video wins`() =
        runTest(UnconfinedTestDispatcher()) {
            val unsubscribed = videoTrack("TR_OFF")
            val first = videoTrack("TR_1")
            val second = videoTrack("TR_2")
            roomHas(
                remote(
                    "agent-job1",
                    name = "Companion",
                    speaking = true,
                    micOn = false,
                    kind = Participant.Kind.AGENT,
                    video = listOf(
                        publication(subscribed = false) to unsubscribed,
                        publication(subscribed = true) to first,
                        publication(subscribed = true) to second,
                    ),
                ),
            )
            val engine = engine()

            engine.connect("wss://media.example", "tok")

            val p = engine.participants.value.single()
            assertEquals("agent-job1", p.identity)
            assertEquals("Companion", p.name)
            assertTrue(p.isSpeaking)
            assertFalse(p.isMicrophoneEnabled)
            assertTrue(p.isAgent)
            assertEquals(VideoTrackHandle(Any(), "TR_1"), p.videoTrack)
            assertSame(first, p.videoTrack!!.sdkTrack)
        }

    @Test
    fun `a participant with no identity, no name and no video maps to empty values`() =
        runTest(UnconfinedTestDispatcher()) {
            roomHas(remote(identity = null, name = null, kind = Participant.Kind.SIP))
            val engine = engine()

            engine.connect("wss://media.example", "tok")

            val p = engine.participants.value.single()
            assertEquals("", p.identity)
            assertNull(p.name)
            assertFalse(p.isAgent)
            assertNull(p.videoTrack)
        }

    @Test
    fun `a blank name is no name, so the UI falls back rather than rendering nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            roomHas(remote("user-a", name = "   "))
            val engine = engine()

            engine.connect("wss://media.example", "tok")

            assertNull(engine.participants.value.single().name)
        }

    @Test
    fun `a subscribed publication with no track attached yet has no video`() =
        runTest(UnconfinedTestDispatcher()) {
            roomHas(remote("user-a", video = listOf(publication(subscribed = true) to null)))
            val engine = engine()

            engine.connect("wss://media.example", "tok")

            assertNull(engine.participants.value.single().videoTrack)
        }

    @Test
    fun `a video track with no sid yet is keyed by its name`() = runTest(UnconfinedTestDispatcher()) {
        roomHas(remote("user-a", video = listOf(publication(subscribed = true) to videoTrack(null, "cam-front"))))
        val engine = engine()

        engine.connect("wss://media.example", "tok")

        assertEquals(VideoTrackHandle(Any(), "cam-front"), engine.participants.value.single().videoTrack)
    }
}
