package com.distronode.districtai.ui

import android.Manifest
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import com.distronode.districtai.str
import com.distronode.districtai.ui.calls.CALL_LOG_LIVE_CALLS_TAG
import com.distronode.districtai.ui.live.LIVE_CALLS_CONFIRM_YES_TAG
import com.distronode.districtai.ui.live.LIVE_CALLS_LISTEN_TAG
import com.distronode.districtai.ui.live.LIVE_CALLS_ROOT_TAG
import com.distronode.districtai.ui.live.LIVE_CALLS_TAKE_OVER_TAG
import com.distronode.districtai.ui.live.liveCallTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Live calls in the graph: reached from the call log with the log's role, the microphone asked for
 * only on a confirmed takeover, and the room left when the destination goes away.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class NavHostLiveCallsTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val harness = NavHostHarness(composeRule)

    @After
    fun tearDown() = harness.close()

    private val ai = FakeSuperviseApi.AI_CALL.id

    private fun open(role: WorkspaceRole?) {
        harness.api.callsResult = ApiResult.Success(listOf(FakeSuperviseApi.AI_CALL, FakeSuperviseApi.TEAM_CALL))
        harness.render()
        harness.navigate(Routes.liveCalls("ws-1", role))
        harness.awaitDescription(LIVE_CALLS_ROOT_TAG)
        composeRule.waitUntil(TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasTestTag(liveCallTag(ai))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tapIn(callId: String, tag: String) {
        composeRule.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(liveCallTag(callId)))).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `the call log opens live calls with its own role`() {
        harness.render()
        harness.navigate(Routes.callLog("ws-1", WorkspaceRole.CLIENT))

        harness.awaitMain { harness.api.callsReads > 0 }
        val before = harness.api.callsReads
        harness.tap(CALL_LOG_LIVE_CALLS_TAG)
        harness.awaitMain { harness.api.callsReads > before }

        assertEquals(Routes.LIVE_CALLS, harness.route())
        assertEquals("client", harness.argument(ARG_ROLE))
        assertTrue("the list is read from the call log", harness.api.callsReads > before)
    }

    @Test
    fun `a confirmed takeover asks for the microphone once and takes the call over`() {
        open(WorkspaceRole.AGENCY)

        tapIn(ai, LIVE_CALLS_LISTEN_TAG)
        assertTrue(
            "listening asks for no microphone",
            harness.registry.launched.none { it == Manifest.permission.RECORD_AUDIO }
        )
        tapIn(ai, LIVE_CALLS_TAKE_OVER_TAG)
        harness.tap(LIVE_CALLS_CONFIRM_YES_TAG)

        assertEquals(1, harness.registry.launched.count { it == Manifest.permission.RECORD_AUDIO })
        assertEquals(listOf("ws-1/$ai"), harness.supervise.takeovers)
        assertTrue(harness.engines.last().calls.contains("mic:true"))
        composeRule.onNodeWithTag(LIVE_CALLS_TAKE_OVER_TAG).assertDoesNotExist()
    }

    @Test
    fun `a refused microphone takes nothing over`() {
        harness.registry.permissionGranted = false
        open(WorkspaceRole.AGENCY)

        tapIn(ai, LIVE_CALLS_LISTEN_TAG)
        tapIn(ai, LIVE_CALLS_TAKE_OVER_TAG)
        harness.tap(LIVE_CALLS_CONFIRM_YES_TAG)

        assertTrue(harness.supervise.takeovers.isEmpty())
        harness.awaitText(str(R.string.live_calls_microphone_denied))
    }

    @Test
    fun `an unknown role listens and is offered nothing else`() {
        open(null)

        tapIn(ai, LIVE_CALLS_LISTEN_TAG)

        assertEquals(listOf(ai), harness.supervise.monitors)
        composeRule.onNodeWithTag(LIVE_CALLS_TAKE_OVER_TAG).assertDoesNotExist()
    }

    @Test
    fun `going back leaves the call`() {
        open(WorkspaceRole.AGENCY)
        tapIn(ai, LIVE_CALLS_LISTEN_TAG)

        harness.back()

        assertEquals("disconnect", harness.engines.single().calls.last())
    }

    @Test
    fun `a new session reads the list again`() {
        open(WorkspaceRole.AGENCY)
        val before = harness.api.callsReads

        harness.bumpEpoch()

        assertTrue(harness.api.callsReads > before)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
