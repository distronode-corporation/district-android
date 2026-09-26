package com.distronode.districtai.core.media

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.livekit.android.LiveKit
import io.livekit.android.events.EventListenable
import io.livekit.android.events.RoomEvent
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoTrack
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import livekit.org.webrtc.SurfaceEglRenderer
import livekit.org.webrtc.VideoSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the tile decides: which renderer it opens, which track it attaches that renderer to, and
 * when it detaches and releases it. The pixels need an EGL context and a GPU, so they are not
 * under test; the SDK track is a mock that records what the tile asked of it, and the renderer's
 * GL half ([SurfaceEglRenderer]) is constructor-mocked so its `release` is observable.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VideoTileTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val log = mutableListOf<String>()
    private val initialised = mutableListOf<View>()
    private val attached = mutableMapOf<String, VideoSink>()
    private val detached = mutableMapOf<String, VideoSink>()

    @Before
    fun setUp() {
        mockkConstructor(SurfaceEglRenderer::class)
        every { anyConstructed<SurfaceEglRenderer>().release() } answers { log += "release" }
    }

    @After
    fun tearDown() = unmockkAll()

    private fun track(tag: String): VideoTrack = mockk {
        every { addRenderer(any()) } answers {
            log += "add:$tag"
            attached[tag] = firstArg()
        }
        every { removeRenderer(any()) } answers {
            log += "remove:$tag"
            detached[tag] = firstArg()
        }
    }

    private fun handle(sid: String, sdkTrack: Any = track(sid)) =
        VideoTrackHandle(sdkTrack, sid) { view ->
            log += "init:$sid"
            initialised += view
        }

    @Test
    fun `opening a tile initialises one renderer and only then attaches it to the track`() {
        composeRule.setContent { VideoTile(handle("A")) }
        composeRule.waitForIdle()

        assertEquals(listOf("init:A", "add:A"), log)
        assertTrue(initialised.single() is TextureViewRenderer)
        assertSame(initialised.single(), attached["A"])
    }

    @Test
    fun `the tile takes exactly the size the grid gives it`() {
        // No aspect ratio or size is set inside the tile; the grid's modifier decides the shape.
        composeRule.setContent {
            VideoTile(handle("A"), Modifier.size(width = 120.dp, height = 80.dp).testTag("tile"))
        }

        composeRule.onNodeWithTag("tile").assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(80.dp)
        assertEquals(listOf("init:A", "add:A"), log)
    }

    @Test
    fun `leaving composition detaches the renderer from the track before releasing it`() {
        var shown by mutableStateOf(true)
        composeRule.setContent { if (shown) VideoTile(handle("A")) }
        composeRule.waitForIdle()
        log.clear()

        shown = false
        composeRule.waitForIdle()

        assertEquals(listOf("remove:A", "release"), log)
        assertSame(initialised.single(), detached["A"])
    }

    @Test
    fun `a replaced track gets a fresh renderer and the old one is torn down`() {
        // A camera toggled off and on again arrives as a new track sid. Keeping the old renderer
        // would freeze the tile on the dead track's last frame.
        var current by mutableStateOf(handle("A"))
        composeRule.setContent { VideoTile(current) }
        composeRule.waitForIdle()
        log.clear()

        current = handle("B")
        composeRule.waitForIdle()

        assertEquals(setOf("init:B", "add:B", "remove:A", "release"), log.toSet())
        assertTrue(log.indexOf("remove:A") < log.indexOf("release"))
        assertTrue(log.indexOf("init:B") < log.indexOf("add:B"))
        assertNotSame(detached["A"], attached["B"])
        assertSame(initialised[0], detached["A"])
        assertSame(initialised[1], attached["B"])
    }

    @Test
    fun `an equal handle for the same track keeps the renderer it has`() {
        // Every participant refresh builds new handles; equal ones must not rebuild the view.
        var current by mutableStateOf(handle("A"))
        composeRule.setContent { VideoTile(current) }
        composeRule.waitForIdle()
        log.clear()

        current = handle("A")
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), log)
        assertEquals(1, initialised.size)
    }

    @Test
    fun `a handle that boxes no video track still gets a renderer, and it is still released`() {
        var shown by mutableStateOf(true)
        composeRule.setContent { if (shown) VideoTile(handle("A", sdkTrack = Any())) }
        composeRule.waitForIdle()

        shown = false
        composeRule.waitForIdle()

        assertEquals(listOf("init:A", "release"), log)
        assertTrue(attached.isEmpty() && detached.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a tile opened from an engine participant initialises its renderer against that room`() {
        // The handle carries the room's renderer initialiser; the tile must hand it the very
        // renderer it then attaches to the track.
        val room = mockk<Room>(relaxed = true)
        val events = MutableSharedFlow<RoomEvent>()
        val videoTrack = mockk<RemoteVideoTrack>(relaxed = true) { every { sid } returns "TR_1" }
        val pub = mockk<TrackPublication> { every { subscribed } returns true }
        val participant = mockk<RemoteParticipant>(relaxed = true) {
            every { identity } returns Participant.Identity("user-a")
            every { name } returns "Ada"
            every { videoTrackPublications } returns listOf(pub to videoTrack)
        }
        mockkObject(LiveKit)
        every { LiveKit.create(any(), any(), any()) } returns room
        every { room.events } returns object : EventListenable<RoomEvent> {
            override val events: SharedFlow<RoomEvent> = events
        }
        every { room.remoteParticipants } returns mapOf(Participant.Identity("user-a") to participant)
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var handle: VideoTrackHandle
        runTest(UnconfinedTestDispatcher()) {
            val engine = LiveKitCallEngine(context, backgroundScope)
            engine.connect("wss://media.example", "tok")
            handle = engine.participants.value.single().videoTrack!!
        }
        val sink = mutableListOf<VideoSink>()
        every { videoTrack.addRenderer(capture(sink)) } returns Unit

        composeRule.setContent { VideoTile(handle) }
        composeRule.waitForIdle()

        val renderer = sink.single() as TextureViewRenderer
        verify { room.initVideoRenderer(renderer) }

        // Any other view is a programming error inside this module and is not initialised.
        handle.initRenderer(View(context))
        verify(exactly = 1) { room.initVideoRenderer(any<TextureViewRenderer>()) }
    }
}
