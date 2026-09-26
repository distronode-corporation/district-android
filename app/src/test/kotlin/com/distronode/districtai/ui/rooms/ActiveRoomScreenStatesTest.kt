package com.distronode.districtai.ui.rooms

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.MediaParticipant
import com.distronode.districtai.core.media.VideoTrackHandle
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The room in the states the grid and chrome tests do not reach: the other banners, the speaker
 * label, an unnamed participant, a participant WITH video, and the same room redrawn.
 *
 * ⚠️ A VIDEO HANDLE IS BUILT REFLECTIVELY because its constructor is `internal` to core-media, on
 * purpose (an app module holding an SDK track is what the handle prevents). Its SDK half here is a
 * bare object, which [com.distronode.districtai.core.media.VideoTile] attaches nothing to.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ActiveRoomScreenStatesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val controls = RoomControls({}, {}, {}, {})

    private fun render(state: ActiveRoomUiState, modifier: Modifier = Modifier) {
        composeRule.setContent {
            DistrictTheme {
                ActiveRoomScreen(
                    state = state,
                    roomName = "meet_ws-abc_standup",
                    controls = controls,
                    onLeave = {},
                    onShareInvite = {},
                    modifier = modifier,
                )
            }
        }
    }

    private fun videoHandle(sid: String): VideoTrackHandle =
        VideoTrackHandle::class.java
            .getDeclaredConstructor(Any::class.java, String::class.java, Function1::class.java)
            .newInstance(Any(), sid, { _: Any -> })

    @Test
    fun `the caller's modifier reaches the room`() {
        render(ActiveRoomUiState(), Modifier.testTag("room-host"))

        composeRule.onNodeWithTag("room-host").assertIsDisplayed()
    }

    @Test
    fun `joining says it is connecting, and leaving says the room was left`() {
        var connection: CallConnectionState by mutableStateOf(CallConnectionState.Connecting)
        composeRule.setContent {
            DistrictTheme {
                ActiveRoomScreen(
                    state = ActiveRoomUiState(connection = connection),
                    roomName = "meet_ws-abc_standup",
                    controls = controls,
                    onLeave = {},
                    onShareInvite = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(ROOM_BANNER_CONNECTING_DESCRIPTION).assertIsDisplayed()

        connection = CallConnectionState.Disconnected()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(ROOM_BANNER_LEFT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ROOM_BANNER_CONNECTING_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a live microphone offers Mute, a live camera offers Stop video, and the speaker says it is on`() {
        render(
            ActiveRoomUiState(
                connection = CallConnectionState.Connected,
                micEnabled = true,
                cameraEnabled = true,
                speakerOn = true,
            ),
        )

        composeRule.onNode(hasContentDescription(ROOM_MIC_DESCRIPTION) and hasText("Mute")).assertExists()
        composeRule.onNode(hasContentDescription(ROOM_CAMERA_DESCRIPTION) and hasText("Stop video")).assertExists()
        composeRule.onNode(hasContentDescription(ROOM_SPEAKER_DESCRIPTION) and hasText("Speaker on")).assertExists()
    }

    @Test
    fun `a participant with no name, or a blank one, is shown as someone rather than as nothing`() {
        render(
            ActiveRoomUiState(
                connection = CallConnectionState.Connected,
                participants = listOf(
                    MediaParticipant(identity = "user-nameless", name = null),
                    MediaParticipant(identity = "user-blank", name = "  "),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(audioTileDescription("user-nameless")).assertTextEquals("Someone")
        composeRule.onNodeWithContentDescription(audioTileDescription("user-blank")).assertTextEquals("Someone")
    }

    @Test
    fun `a participant with video gets a video tile, not the audio-only label`() {
        render(
            ActiveRoomUiState(
                connection = CallConnectionState.Connected,
                participants = listOf(
                    MediaParticipant(identity = "user-cam", name = "Ada", videoTrack = videoHandle("TR_cam")),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(participantTileDescription("user-cam")).assertExists()
        composeRule.onNodeWithContentDescription(audioTileDescription("user-cam")).assertDoesNotExist()
        // The name still labels the tile under the video.
        composeRule.onNodeWithText("Ada").assertExists()
    }

    @Test
    fun `a participant who changes is redrawn in place, and a redraw with nothing new keeps them`() {
        // ⚠️ THE SAME IDENTITY IS THE SAME TILE: a mute or a rename redraws it rather than adding a
        // second one, and a redraw for an unrelated reason (the theme) leaves it as it was.
        var ada by mutableStateOf(MediaParticipant(identity = "user-4f2a", name = "Ada"))
        var dark by mutableStateOf(false)
        composeRule.setContent {
            DistrictTheme(darkTheme = dark) {
                ActiveRoomScreen(
                    state = ActiveRoomUiState(
                        connection = CallConnectionState.Connected,
                        participants = listOf(ada),
                        canPublish = true,
                        permissions = RoomPermissions(requested = true),
                    ),
                    roomName = "meet_ws-abc_standup",
                    controls = controls,
                    onLeave = {},
                    onShareInvite = {},
                )
            }
        }

        ada = ada.copy(name = "Ada L.", isMicrophoneEnabled = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(audioTileDescription("user-4f2a")).assertTextEquals("Ada L.")

        dark = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(audioTileDescription("user-4f2a")).assertTextEquals("Ada L.")
        composeRule.onNodeWithContentDescription(ROOM_NO_MIC_DESCRIPTION).assertIsDisplayed()
        assertEquals(
            "one tile per identity",
            1,
            composeRule.onAllNodes(hasContentDescription(participantTileDescription("user-4f2a")))
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `someone joining adds a tile and leaves the people already there as they were`() {
        val ada = MediaParticipant(identity = "user-4f2a", name = "Ada")
        var people by mutableStateOf(listOf(ada))
        composeRule.setContent {
            DistrictTheme {
                ActiveRoomScreen(
                    state = ActiveRoomUiState(connection = CallConnectionState.Connected, participants = people),
                    roomName = "meet_ws-abc_standup",
                    controls = controls,
                    onLeave = {},
                    onShareInvite = {},
                )
            }
        }

        // ⚠️ AHEAD of Ada, so her tile moves as well as a new one appearing.
        people = listOf(MediaParticipant(identity = "user-9c11", name = "Grace"), ada)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(participantTileDescription("user-4f2a")).assertExists()
        composeRule.onNodeWithContentDescription(participantTileDescription("user-9c11")).assertExists()
        composeRule.onNodeWithContentDescription(audioTileDescription("user-4f2a")).assertTextEquals("Ada")
    }

    @Test
    fun `a resized window keeps the room, its notices and its tiles`() {
        // ⚠️ A ROTATION OR A SPLIT-SCREEN RESIZE REMEASURES THE SCAFFOLD, which recomposes its body
        // with nothing about the call changed. Nothing may drop out when that happens.
        var height by mutableStateOf(800.dp)
        composeRule.setContent {
            DistrictTheme {
                Box(Modifier.height(height)) {
                    ActiveRoomScreen(
                        state = ActiveRoomUiState(
                            connection = CallConnectionState.Connected,
                            participants = listOf(MediaParticipant(identity = "user-4f2a", name = "Ada")),
                            permissions = RoomPermissions(requested = true, microphoneGranted = false),
                        ),
                        roomName = "meet_ws-abc_standup",
                        controls = controls,
                        onLeave = {},
                        onShareInvite = {},
                    )
                }
            }
        }

        height = 600.dp
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(ROOM_NO_MIC_DESCRIPTION).assertExists()
        composeRule.onNodeWithContentDescription(ROOM_NO_CAMERA_DESCRIPTION).assertExists()
        composeRule.onNodeWithContentDescription(participantTileDescription("user-4f2a")).assertExists()
    }

    @Test
    fun `the design preview draws a connected room with its Companion`() {
        composeRule.setContent { ActiveRoomScreenPreview() }

        composeRule.onNodeWithContentDescription(ROOM_COMPANION_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(participantTileDescription("user-4f2a")).assertExists()
    }
}
