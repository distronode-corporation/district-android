package com.distronode.districtai.ui.rooms

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.MediaParticipant
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.core.designsystem.DistrictTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ WHAT THIS SCREEN GETS WRONG IS ONLY VISIBLE IN THE RENDERING, WHICH IS WHY IT IS TESTED HERE
 * AND NOT ONLY AT THE STATE. Four failures live entirely in the drawing: rendering `Reconnecting`
 * as a failure (which would teach people to hang up on something that was about to recover),
 * drawing the Companion as a participant tile (a blank rectangle) or omitting it altogether (people
 * transcribed with nothing on screen saying so), dropping camera-off participants from the grid
 * (making a room look emptier than it is, which is the direction that matters — it is what somebody
 * checks before saying something private), and offering a share control for an invite the server
 * withheld.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ActiveRoomScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ada = MediaParticipant(identity = "user-4f2a", name = "Ada")
    private val grace = MediaParticipant(
        identity = "user-9c11",
        name = "Grace",
        isMicrophoneEnabled = false,
    )

    private var micTaps = 0
    private var cameraTaps = 0
    private var flipTaps = 0
    private var speakerTaps = 0
    private var leaveTaps = 0
    private var sharedLinks = mutableListOf<String>()

    private fun render(state: ActiveRoomUiState) {
        composeRule.setContent {
            DistrictTheme {
                ActiveRoomScreen(
                    state = state,
                    roomName = "meet_ws-abc_standup",
                    controls = RoomControls(
                        onToggleMicrophone = { micTaps++ },
                        onToggleCamera = { cameraTaps++ },
                        onFlipCamera = { flipTaps++ },
                        onToggleSpeaker = { speakerTaps++ },
                    ),
                    onLeave = { leaveTaps++ },
                    onShareInvite = { sharedLinks += it },
                )
            }
        }
    }

    private fun connected(
        participants: List<MediaParticipant> = listOf(ada, grace),
        companionPresent: Boolean = false,
        canPublish: Boolean = true,
        guestLink: String? = null,
        permissions: RoomPermissions = RoomPermissions(
            requested = true,
            microphoneGranted = true,
            cameraGranted = true,
        ),
    ) = ActiveRoomUiState(
        connection = CallConnectionState.Connected,
        participants = participants,
        companionPresent = companionPresent,
        canPublish = canPublish,
        guestLink = guestLink,
        permissions = permissions,
    )

    // ── The grid ─────────────────────────────────────────────────────────────

    @Test
    fun `every participant gets a tile`() {
        render(connected())

        composeRule.onNodeWithContentDescription(ACTIVE_ROOM_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ROOM_GRID_DESCRIPTION).assertExists()
        composeRule.onNodeWithContentDescription(participantTileDescription("user-4f2a")).assertExists()
        composeRule.onNodeWithContentDescription(participantTileDescription("user-9c11")).assertExists()
    }

    @Test
    fun `a participant with no video still gets a tile, as audio-only`() {
        // ⛔ CAMERA-OFF IS THE NORMAL WAY TO BE IN A MEETING. Dropping those people would make the
        // room look emptier than it is, which is the dangerous direction: it is what somebody
        // checks before saying something private.
        render(connected(participants = listOf(ada)))

        composeRule.onNodeWithContentDescription(participantTileDescription("user-4f2a")).assertExists()
        composeRule.onNodeWithContentDescription(audioTileDescription("user-4f2a")).assertExists()
    }

    @Test
    fun `an empty room says so rather than rendering an empty grid`() {
        render(connected(participants = emptyList()))

        composeRule.onNodeWithContentDescription(ROOM_ALONE_DESCRIPTION).assertIsDisplayed()
    }

    // ── The Companion ────────────────────────────────────────────────────────

    @Test
    fun `the Companion is a chip and NOT a tile`() {
        // ⛔ BOTH HALVES. It publishes no media, so a tile would be a blank rectangle; but it is
        // transcribing the conversation, so hiding it entirely would mean nothing on screen says
        // people are being recorded.
        render(connected(participants = listOf(ada), companionPresent = true))

        composeRule.onNodeWithContentDescription(ROOM_COMPANION_DESCRIPTION).assertIsDisplayed()
        // ⚠️ The agent identity is never in `participants` (the ViewModel filters it), so no tile
        // can carry it. Asserted so a future change that stopped filtering fails HERE, visibly.
        composeRule
            .onNodeWithContentDescription(participantTileDescription("agent-AJ_abc123"))
            .assertDoesNotExist()
    }

    @Test
    fun `no Companion means no chip`() {
        render(connected(companionPresent = false))

        composeRule.onNodeWithContentDescription(ROOM_COMPANION_DESCRIPTION).assertDoesNotExist()
    }

    // ── Connection state ─────────────────────────────────────────────────────

    @Test
    fun `Reconnecting is a banner OVER the call, and the tiles stay`() {
        // ⛔ THE ASSERTION THIS FILE EXISTS FOR. A phone handing over between wifi and its radio
        // reconnects within seconds and the SDK recovers by itself; a screen that replaced the grid
        // with an error would be ending meetings during the ordinary event it was meant to survive.
        render(connected().copy(connection = CallConnectionState.Reconnecting))

        composeRule
            .onNodeWithContentDescription(ROOM_BANNER_RECONNECTING_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ROOM_GRID_DESCRIPTION).assertExists()
        composeRule.onNodeWithContentDescription(participantTileDescription("user-4f2a")).assertExists()
        // ⛔ AND IT IS NOT THE FAILURE BANNER. Two different banners, two different tones, and only
        // one of them tells the user something has gone wrong.
        composeRule.onNodeWithContentDescription(ROOM_BANNER_FAILED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a connected room shows no banner at all`() {
        render(connected())

        composeRule.onNodeWithContentDescription(ROOM_BANNER_CONNECTING_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(ROOM_BANNER_RECONNECTING_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(ROOM_BANNER_FAILED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a lost session shows the failure banner`() {
        render(connected().copy(connection = CallConnectionState.Failed("gone")))

        composeRule.onNodeWithContentDescription(ROOM_BANNER_FAILED_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a join failure is shown with its reason`() {
        render(
            connected().copy(
                connection = CallConnectionState.Idle,
                joinFailure = FailureText(UiText.Literal("not a member of this workspace")),
            ),
        )

        composeRule.onNodeWithContentDescription(ROOM_JOIN_FAILURE_DESCRIPTION).assertIsDisplayed()
    }

    // ── Permissions and the viewer seat ──────────────────────────────────────

    @Test
    fun `nothing is claimed about permissions before they have been requested`() {
        // ⚠️ Before the request both flags are false and MEAN NOTHING. A "microphone blocked"
        // warning in that window tells the user something untrue about their own device.
        render(connected(permissions = RoomPermissions()))

        composeRule.onNodeWithContentDescription(ROOM_NO_MIC_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(ROOM_NO_CAMERA_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a denied microphone disables the control and says why`() {
        render(
            connected(
                permissions = RoomPermissions(
                    requested = true,
                    microphoneGranted = false,
                    cameraGranted = true,
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(ROOM_NO_MIC_DESCRIPTION).assertIsDisplayed()
        // ⛔ DISABLED, NOT HIDDEN. A missing control makes the user hunt for it; a disabled one
        // beside a sentence saying why is the pair that actually explains the state.
        composeRule.onNodeWithContentDescription(ROOM_MIC_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(ROOM_CAMERA_DESCRIPTION).assertIsEnabled()
    }

    @Test
    fun `a denied camera disables only the camera`() {
        render(
            connected(
                permissions = RoomPermissions(
                    requested = true,
                    microphoneGranted = true,
                    cameraGranted = false,
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(ROOM_NO_CAMERA_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ROOM_CAMERA_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(ROOM_MIC_DESCRIPTION).assertIsEnabled()
    }

    @Test
    fun `a viewer is told why its controls are off, and they are`() {
        // ⚠️ A DIFFERENT REASON FROM A DENIED PERMISSION, and the remedy differs — one is the
        // workspace's role, the other is the phone's settings. The viewer notice replaces the
        // permission ones rather than stacking with them.
        render(connected(canPublish = false))

        composeRule.onNodeWithContentDescription(ROOM_VIEWER_NOTICE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ROOM_MIC_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(ROOM_CAMERA_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(ROOM_NO_MIC_DESCRIPTION).assertDoesNotExist()
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    @Test
    fun `the controls fire their callbacks`() {
        render(connected().copy(cameraEnabled = true))

        composeRule.onNodeWithContentDescription(ROOM_MIC_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(ROOM_CAMERA_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(ROOM_FLIP_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(ROOM_SPEAKER_DESCRIPTION).performClick()

        assertEquals(1, micTaps)
        assertEquals(1, cameraTaps)
        assertEquals(1, flipTaps)
        assertEquals(1, speakerTaps)
    }

    @Test
    fun `flip is disabled while the camera is off`() {
        // ⚠️ Flipping a camera that is not publishing has no visible effect, so an enabled control
        // would report success for an action that did nothing.
        render(connected())

        composeRule.onNodeWithContentDescription(ROOM_FLIP_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `leaving fires exactly once per tap`() {
        render(connected())

        composeRule.onNodeWithContentDescription(ROOM_LEAVE_DESCRIPTION).performClick()

        assertEquals(1, leaveTaps)
    }

    // ── The guest invite ─────────────────────────────────────────────────────

    @Test
    fun `the share control is absent when the server minted no invite`() {
        // ⛔ ABSENT, NOT DISABLED. A disabled control suggests a viewer could invite somebody if
        // only something else were true; the server's refusal is categorical, and a link built
        // locally is one `/api/meet/token` will not honour.
        render(connected(guestLink = null))

        composeRule.onNodeWithContentDescription(ROOM_SHARE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `sharing hands out exactly the link the server produced`() {
        val link = "https://www.distronode.test/meet/meet_ws-abc_standup?e=1786973400&s=sig"
        render(connected(guestLink = link))

        composeRule.onNodeWithContentDescription(ROOM_SHARE_DESCRIPTION).performClick()

        assertEquals(listOf(link), sharedLinks)
    }
}
