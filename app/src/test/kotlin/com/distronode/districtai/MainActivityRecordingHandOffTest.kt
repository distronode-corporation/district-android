package com.distronode.districtai

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.model.CallDetailResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.testCall
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.calls.CALL_DETAIL_PLAY_DESCRIPTION
import com.distronode.districtai.ui.calls.CALL_LOG_ROOT_DESCRIPTION
import com.distronode.districtai.ui.scrolledIntoView
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The recording hand-off across a configuration change, through the real activity.
 *
 * ⛔ THE RESOLVE OUTLIVES THE ACTIVITY THAT ASKED FOR IT, exactly as the scheduling mint does (see
 * [MainActivitySchedulingHandOffTest]). The ViewModel is scoped to the nav entry and survives a
 * rotation; the Activity does not. So what the ViewModel holds while the presigned URL resolves
 * must report to whichever Activity exists when the answer lands, never to the one that pressed
 * Play.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], application = ShellTestApplication::class)
class MainActivityRecordingHandOffTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(compose)

    private val harness = ShellHarness(compose)

    @After
    fun tearDown() = harness.close()

    @Test
    fun `no player after a rotation mid-resolve is still reported, on the new activity`() {
        // ⛔ THE OLD CALLBACK REPORTED THROUGH `onShowMessage`, whose snackbar is launched on the
        // pressing Activity's `lifecycleScope`. Rotated mid-resolve, the answer landed on a
        // destroyed Activity, the cancelled scope dropped the message, and Play simply did nothing.
        harness.signedIn()
        val call = testCall(recordingUrl = "https://legacy.test/a.mp3")
        harness.app.api.callsResult = ApiResult.Success(listOf(call))
        harness.app.api.detailResult = ApiResult.Success(CallDetailResponse(success = true, call = call))
        val gate = CompletableDeferred<Unit>()
        harness.app.api.recordingGate = gate
        harness.launch(harness.viewIntent("https://www.distronode.com/dashboard/district/calls"))
        harness.awaitDescription(CALL_LOG_ROOT_DESCRIPTION)
        harness.awaitText(call.callerName)
        compose.onAllNodesWithText(call.callerName).onFirst().performClick()
        harness.awaitDescription(CALL_DETAIL_PLAY_DESCRIPTION)
        // Every start throws from here on, as it does on a device with nothing that plays audio.
        // ⚠️ Not before the launch, which starts activities of its own that have no handler here.
        shadowOf(harness.app).checkActivities(true)
        compose.onNodeWithContentDescription(CALL_DETAIL_PLAY_DESCRIPTION).scrolledIntoView().performClick()

        harness.recreate()
        gate.complete(Unit)

        harness.awaitText(harness.string(R.string.call_detail_recording_no_player))
    }
}
