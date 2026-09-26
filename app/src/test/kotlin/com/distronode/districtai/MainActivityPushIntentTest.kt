package com.distronode.districtai

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.push.InboxDeepLink
import com.distronode.districtai.push.PushIntents
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A tapped notification reaching the activity, on a cold start and while it runs.
 *
 * ⛔ EACH REQUEST IS ACTED ON EXACTLY ONCE. A `singleTask` activity keeps its launch intent and
 * re-delivers it to `onCreate` on every recreation, so extras left in place would re-answer a call
 * on a rotation, or pull the user back to the inbox every time they turned the phone. The extras are
 * removed after reading, and these tests recreate the activity to prove it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], application = ShellTestApplication::class)
class MainActivityPushIntentTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(compose)

    private val harness = ShellHarness(compose)

    @After
    fun tearDown() = harness.close()

    private fun inboxIntent(): Intent = harness.launchIntent()
        .putExtra(PushIntents.EXTRA_WORKSPACE_ID, "ws-1")
        .putExtra(PushIntents.EXTRA_MESSAGE_ID, "msg-9")

    private fun callIntent(answer: Boolean): Intent = harness.launchIntent()
        .putExtra(PushIntents.EXTRA_WORKSPACE_ID, "ws-1")
        .putExtra(PushIntents.EXTRA_CALL_ID, "call-7")
        .putExtra(PushIntents.EXTRA_ANSWER, answer)

    private fun assertPushExtrasGone(intent: Intent) {
        listOf(
            PushIntents.EXTRA_WORKSPACE_ID,
            PushIntents.EXTRA_MESSAGE_ID,
            PushIntents.EXTRA_CALL_ID,
            PushIntents.EXTRA_ANSWER,
        ).forEach { key -> assertFalse("$key must be consumed", intent.hasExtra(key)) }
    }

    @Test
    fun `a cold start from a message notification records the inbox link for the graph`() {
        harness.signedOut()

        harness.launch(inboxIntent())

        assertEquals(InboxDeepLink("ws-1", "msg-9"), harness.container.pushDeepLinks.pending.value)
        assertPushExtrasGone(harness.activity.intent)
    }

    @Test
    fun `a recreation does not re-deliver the inbox link the user already followed`() {
        harness.signedOut()
        harness.launch(inboxIntent())
        harness.container.pushDeepLinks.clear()

        harness.recreate()

        assertNull(harness.container.pushDeepLinks.pending.value)
    }

    @Test
    fun `a message notification tapped while the app runs is recorded too`() {
        harness.signedOut()
        harness.launch()

        val tapped = inboxIntent()
        harness.deliver(tapped)

        assertEquals(InboxDeepLink("ws-1", "msg-9"), harness.container.pushDeepLinks.pending.value)
        // ⚠️ The stored intent is the new one, so a later getIntent() reads what was just handled.
        assertTrue(harness.activity.intent === tapped)
        assertPushExtrasGone(tapped)
    }

    @Test
    fun `Answer on a ringing call asks for the microphone from the ringing screen`() {
        harness.signedOut()
        harness.launch()
        harness.container.incomingCallController.onIncomingCall("ws-1", "call-7")
        compose.waitForIdle()

        harness.deliver(callIntent(answer = true))

        // ⛔ RECORDED, NOT ANSWERED: the answer needs the microphone first, and the request is made
        // by the ringing screen, which has the launcher. Nothing is sent to the server until then.
        val requested = shadowOf(harness.activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), requested?.requestedPermissions?.toList())
        assertEquals(emptyList<Any>(), harness.app.api.pushApi.answerRequests)
        assertPushExtrasGone(harness.activity.intent)
    }

    @Test
    fun `Answer from a stale notification for a call that is not ringing does nothing`() {
        harness.signedOut()
        harness.launch()

        harness.deliver(callIntent(answer = true))

        assertNull(shadowOf(harness.activity).lastRequestedPermission)
        assertFalse(harness.container.incomingCallController.answerRequested.value)
    }

    @Test
    fun `tapping the ringing notification's body brings the app forward and answers nothing`() {
        harness.signedOut()
        harness.launch()
        harness.container.incomingCallController.onIncomingCall("ws-1", "call-7")
        compose.waitForIdle()

        harness.deliver(callIntent(answer = false))

        assertNull(shadowOf(harness.activity).lastRequestedPermission)
        assertFalse(harness.container.incomingCallController.answerRequested.value)
        assertPushExtrasGone(harness.activity.intent)
    }

    @Test
    fun `extras that are not a notification's are ignored`() {
        harness.signedOut()

        harness.launch(harness.launchIntent().putExtra("unrelated", "value"))

        assertNull(harness.container.pushDeepLinks.pending.value)
        assertEquals("value", harness.activity.intent.getStringExtra("unrelated"))
    }
}
