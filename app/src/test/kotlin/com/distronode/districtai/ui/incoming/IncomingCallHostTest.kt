package com.distronode.districtai.ui.incoming

import android.Manifest
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.call.IncomingCallController
import com.distronode.districtai.call.IncomingCallPhase
import com.distronode.districtai.call.IncomingCallSurfaces
import com.distronode.districtai.call.InboundCallSessionFactory
import com.distronode.districtai.core.data.InboundCallRepository
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.push.RecordingPushNotifier
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.ScriptedResultRegistry
import com.distronode.districtai.ui.dialer.FakeTelecomBridge
import com.distronode.districtai.ui.rooms.FakeCallEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * The plumbing between the process-scoped call controller and the ringing screen.
 *
 * ⛔ ANSWER ASKS FOR THE MICROPHONE FIRST, FROM EITHER DOOR. The on-screen button and the
 * notification's Answer action both go through the permission request, and a denial still answers:
 * the user pressed Answer. What is asserted is the request made and the controller's phase after it.
 *
 * ⚠️ THE CONTROLLER RUNS ON AN `UnconfinedTestDispatcher`, so an answer's refusal (the fake API's
 * default) lands before the next assertion without any clock, and the ring timeout is a virtual
 * delay that never fires on its own. It is cancelled with the scope in [tearDown].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class IncomingCallHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val api = FakeDistrictApi()
    private val telecom = FakeTelecomBridge()
    private val registry = ScriptedResultRegistry()
    private val controller = IncomingCallController(
        repository = InboundCallRepository(api),
        surfaces = IncomingCallSurfaces(
            telecom = telecom,
            notifier = RecordingPushNotifier(),
            sessions = InboundCallSessionFactory(CallEngineFactory { FakeCallEngine() }, telecom),
            foreground = {},
        ),
        scope = scope,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun render() {
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                DistrictTheme { IncomingCallHost(controller) }
            }
        }
    }

    private fun ringing() {
        controller.onIncomingCall(workspaceId = "ws-1", callId = "CA1")
        composeRule.waitForIdle()
    }

    @Test
    fun `with no call, nothing is drawn`() {
        render()

        composeRule.onNodeWithContentDescription(INCOMING_ROOT_DESCRIPTION).assertDoesNotExist()
        assertEquals(emptyList<Any?>(), registry.launched)
    }

    @Test
    fun `Answer asks for the microphone and then answers`() {
        render()
        ringing()
        composeRule.onNodeWithContentDescription(INCOMING_ANSWER_DESCRIPTION).assertExists()

        composeRule.onNodeWithContentDescription(INCOMING_ANSWER_DESCRIPTION).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf<Any?>(Manifest.permission.RECORD_AUDIO), registry.launched)
        assertEquals(listOf("CA1/ws-1"), api.pushApi.answerRequests)
        assertEquals(IncomingCallPhase.ENDED, controller.state.value?.phase)
    }

    @Test
    fun `a denied microphone still answers, because the user pressed Answer`() {
        registry.permissionGranted = false
        render()
        ringing()

        composeRule.onNodeWithContentDescription(INCOMING_ANSWER_DESCRIPTION).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("CA1/ws-1"), api.pushApi.answerRequests)
    }

    @Test
    fun `the notification's Answer goes through the same permission request, once`() {
        render()
        ringing()

        controller.requestAnswerFromNotification()
        composeRule.waitForIdle()

        assertEquals(listOf<Any?>(Manifest.permission.RECORD_AUDIO), registry.launched)
        assertFalse("the request is consumed so a recomposition cannot replay it", controller.answerRequested.value)
        assertEquals(listOf("CA1/ws-1"), api.pushApi.answerRequests)
    }

    @Test
    fun `Decline ends the ring without asking for anything, and Done clears the screen`() {
        render()
        ringing()

        composeRule.onNodeWithContentDescription(INCOMING_DECLINE_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(IncomingCallPhase.ENDED, controller.state.value?.phase)
        assertEquals(emptyList<Any?>(), registry.launched)
        assertEquals(emptyList<String>(), api.pushApi.answerRequests)

        composeRule.onNodeWithContentDescription(INCOMING_DISMISS_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertNull(controller.state.value)
        composeRule.onNodeWithContentDescription(INCOMING_ROOT_DESCRIPTION).assertDoesNotExist()
    }
}
