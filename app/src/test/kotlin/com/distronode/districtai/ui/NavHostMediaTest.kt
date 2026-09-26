package com.distronode.districtai.ui

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.content.IntentCompat
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ApiEnvironment
import com.distronode.districtai.R
import com.distronode.districtai.core.model.MeetingSummary
import com.distronode.districtai.core.model.RoomTokenResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.dialer.DIALER_CALL_DESCRIPTION
import com.distronode.districtai.ui.dialer.DIALER_FIELD_DESCRIPTION
import com.distronode.districtai.ui.rooms.ACTIVE_ROOM_ROOT_DESCRIPTION
import com.distronode.districtai.ui.rooms.ROOMS_JOIN_DESCRIPTION
import com.distronode.districtai.ui.rooms.ROOMS_NAME_FIELD_DESCRIPTION
import com.distronode.districtai.ui.rooms.ROOM_LEAVE_DESCRIPTION
import com.distronode.districtai.ui.rooms.ROOM_SHARE_DESCRIPTION
import com.distronode.districtai.ui.rooms.STATUS_IN_PROGRESS
import com.distronode.districtai.ui.rooms.meetingRejoinDescription
import com.distronode.districtai.ui.settings.workspace.PERSONA_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_PERSONA_ROW_DESCRIPTION
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The destinations that hold a live media session: the rooms, the dialler and the persona audition.
 *
 * ⛔ THE PERMISSION LAUNCHERS LIVE IN THE GRAPH, SO THIS IS THE ONLY PLACE THEY CAN BE PROVEN. Each
 * screen takes plain lambdas precisely so its own tests need no activity-result registry; the cost is
 * that what the launcher asks for, and what its answer is turned into, exists only here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class NavHostMediaTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    @After
    fun tearDown() = harness.close()

    // ── The lobby ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `joining from the lobby opens the minted meet room with the lobby's role`() {
        harness.render()
        harness.navigate(Routes.rooms("ws-1", WorkspaceRole.CLIENT))

        composeRule.onNodeWithContentDescription(ROOMS_NAME_FIELD_DESCRIPTION).performTextInput("Weekly Review")
        harness.tap(ROOMS_JOIN_DESCRIPTION)

        assertEquals(Routes.ACTIVE_ROOM, harness.route())
        assertEquals("meet_ws-1_weekly-review", harness.argument(ARG_ROOM_NAME))
        assertEquals("client", harness.argument(ARG_ROLE))
    }

    @Test
    fun `a live meeting is rejoined by its own room name`() {
        harness.api.meetingsResult = ApiResult.Success(
            listOf(MeetingSummary(id = "m1", roomName = "meet_ws-1_standup", status = STATUS_IN_PROGRESS)),
        )
        harness.render()
        harness.navigate(Routes.rooms("ws-1", WorkspaceRole.AGENCY))

        harness.tap(meetingRejoinDescription("m1"))

        assertEquals(Routes.ACTIVE_ROOM, harness.route())
        assertEquals("meet_ws-1_standup", harness.argument(ARG_ROOM_NAME))
    }

    // ── One live room ──────────────────────────────────────────────────────────────────────

    private fun enterRoom(role: WorkspaceRole = WorkspaceRole.AGENCY) {
        harness.render()
        harness.navigate(Routes.rooms("ws-1", role))
        harness.navigate(Routes.activeRoom("ws-1", role, "meet_ws-1_standup"))
    }

    @Test
    fun `entering a room asks for the microphone and camera once and joins on the answer`() {
        enterRoom()

        harness.awaitDescription(ACTIVE_ROOM_ROOT_DESCRIPTION)
        val asked = harness.registry.launched.filterIsInstance<Array<*>>()
        assertEquals(1, asked.size)
        assertArrayEquals(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), asked.single())
        assertEquals(listOf("meet_ws-1_standup"), harness.api.roomTokenRequests.map { it.roomName })
        assertEquals("connect:wss://livekit.test:test-jwt", harness.engines.single().calls.first())
    }

    @Test
    fun `a room joined with both permissions refused still connects, listen-only`() {
        harness.registry.permissionGranted = false
        enterRoom()

        assertEquals("connect:wss://livekit.test:test-jwt", harness.engines.single().calls.first())
        assertTrue(harness.engines.single().calls.none { it == "mic:true" || it == "camera:true" })
    }

    @Test
    fun `leaving disconnects first and only then returns to the lobby`() {
        enterRoom()

        harness.tap(ROOM_LEAVE_DESCRIPTION)

        assertEquals("disconnect", harness.engines.single().calls.last())
        assertEquals(Routes.ROOMS, harness.route())
    }

    @Test
    fun `the guest invite goes to the system share sheet as a new task`() {
        harness.api.roomTokenResult = ApiResult.Success(
            RoomTokenResponse(
                success = true,
                token = "test-jwt",
                url = "wss://livekit.test",
                guestPath = "/meet/meet_ws-1_standup?exp=1&sig=abc",
            ),
        )
        enterRoom()

        harness.tap(ROOM_SHARE_DESCRIPTION)

        val chooser = harness.started.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(
            ApiEnvironment.baseUrl.trimEnd('/') + "/meet/meet_ws-1_standup?exp=1&sig=abc",
            send.getStringExtra(Intent.EXTRA_TEXT),
        )
    }

    @Test
    fun `a device that cannot share keeps the room rather than crashing it`() {
        harness.api.roomTokenResult = ApiResult.Success(
            RoomTokenResponse(success = true, token = "t", url = "wss://livekit.test", guestPath = "/meet/x"),
        )
        enterRoom()
        harness.startFailure = android.content.ActivityNotFoundException("nothing accepts text/plain")

        harness.tap(ROOM_SHARE_DESCRIPTION)

        assertEquals(Routes.ACTIVE_ROOM, harness.route())
        harness.awaitDescription(ACTIVE_ROOM_ROOT_DESCRIPTION)
    }

    // ── The dialler ────────────────────────────────────────────────────────────────────────

    private fun dial(number: String) {
        harness.render()
        harness.navigate(Routes.dialer("ws-1", WorkspaceRole.AGENCY))
        // ⚠️ Nothing is asked on entry: the microphone dialog belongs to the first dial.
        assertTrue(harness.registry.launched.none { it == Manifest.permission.RECORD_AUDIO })
        composeRule.onNodeWithContentDescription(DIALER_FIELD_DESCRIPTION).performTextInput(number)
        harness.tap(DIALER_CALL_DESCRIPTION)
    }

    @Test
    fun `a dial asks for the microphone and dials on a grant`() {
        dial("4165550123")

        assertEquals(1, harness.registry.launched.count { it == Manifest.permission.RECORD_AUDIO })
        assertEquals(1, harness.api.dialRequests.size)
    }

    @Test
    fun `a refused microphone places no call and says why`() {
        harness.registry.permissionGranted = false

        dial("4165550123")

        assertTrue(harness.api.dialRequests.isEmpty())
        harness.awaitText(
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(R.string.dialer_needs_microphone),
        )
    }

    // ── The persona form ───────────────────────────────────────────────────────────────────

    @Test
    fun `the hub's persona row opens the persona form, which reads this workspace's configuration`() {
        harness.render()
        harness.navigate(Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY))

        harness.tap(WORKSPACE_SETTINGS_PERSONA_ROW_DESCRIPTION)

        assertEquals(Routes.WORKSPACE_SETTINGS_PERSONA, harness.route())
        harness.awaitDescription(PERSONA_ROOT_DESCRIPTION)
        assertEquals(listOf("ws-1"), harness.api.configRequests)
    }
}
