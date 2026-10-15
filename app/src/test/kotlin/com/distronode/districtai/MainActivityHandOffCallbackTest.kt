package com.distronode.districtai

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.handoff.HandOffEvent
import com.distronode.districtai.handoff.LogcatHandOffLog
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The dashboard hand-off's nonce callback, `districtai://handoff`, through the real activity.
 *
 * ⛔ THE CALLBACK IS HOSTILE INPUT, AS THE LOGIN ONE IS. The activity is exported, so any app can
 * fire `districtai://handoff?state=…&nonce=…` at it. These tests check the routing half: the right
 * host reaches the hand-off (and never the login controller), it is consumed once, a recreation
 * cannot replay it, and nothing written to the log carries the values. Whether a callback is OURS
 * is HandOffCallbacks' decision, tested there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], application = ShellTestApplication::class)
class MainActivityHandOffCallbackTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(compose)

    private val harness = ShellHarness(compose)

    @After
    fun tearDown() = harness.close()

    private fun callback(state: String = STATE, nonce: String = NONCE) =
        harness.viewIntent("${ApiEnvironment.HANDOFF_CALLBACK_URI}?state=$state&nonce=$nonce")

    @Test
    fun `the manifest routes the hand-off callback to this activity`() {
        // ⛔ THE MANIFEST SPELLS THE HOST AS A LITERAL. If it drifted from ApiEnvironment the
        // server's redirect would reach nothing on the device, and every hand-off would sit out the
        // ten-second wait and fall back to the unbound flow.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${ApiEnvironment.HANDOFF_CALLBACK_URI}?state=s&nonce=n"))
            .addCategory(Intent.CATEGORY_BROWSABLE)

        val handlers = harness.app.packageManager.queryIntentActivities(intent, 0)

        assertEquals(listOf(MainActivity::class.java.name), handlers.map { it.activityInfo.name })
    }

    @Test
    fun `a callback for the pending hand-off hands its nonce over, and is consumed`() {
        harness.signedOut()
        harness.launch()
        val pending = checkNotNull(harness.container.handOffCallbacks.begin(STATE))

        harness.deliver(callback())

        assertEquals(NONCE, runBlocking { pending.awaitNonce(WAIT) })
        assertNull("consumed, so a recreation cannot replay it", harness.activity.intent.data)
        // ⛔ AND IT NEVER REACHED THE LOGIN CONTROLLER, which would read it as a sign-in with no code.
        assertNull(harness.loginStatus)
    }

    @Test
    fun `a callback on a cold start has nothing pending and is dropped quietly`() {
        // ⚠️ THE PROCESS-DEATH CASE: the ViewModel that was waiting died with the old process.
        harness.signedOut()

        harness.launch(callback())

        assertNull(harness.activity.intent.data)
        assertNull("not a sign-in either", harness.loginStatus)
        assertEquals(
            HandOffEvent.CALLBACK_NOTHING_PENDING,
            harness.container.handOffCallbacks.deliver(STATE, NONCE),
        )
    }

    @Test
    fun `a recreation does not deliver the same callback twice`() {
        harness.signedOut()
        harness.launch()
        val pending = checkNotNull(harness.container.handOffCallbacks.begin(STATE))
        harness.deliver(callback())
        ShadowLog.clear()

        harness.recreate()

        assertEquals(NONCE, runBlocking { pending.awaitNonce(WAIT) })
        assertFalse(
            "the recreated activity re-ran the consumed callback",
            ShadowLog.getLogsForTag(LogcatHandOffLog.TAG).isNotEmpty(),
        )
    }

    @Test
    fun `the log names the outcome and never the state or the nonce`() {
        harness.signedOut()
        harness.launch()
        checkNotNull(harness.container.handOffCallbacks.begin(STATE))
        ShadowLog.clear()

        harness.deliver(callback(state = "guessed-by-another-app"))
        harness.deliver(callback())

        val logs = ShadowLog.getLogsForTag(LogcatHandOffLog.TAG)
        assertEquals(
            listOf(HandOffEvent.CALLBACK_STATE_MISMATCH.name, HandOffEvent.CALLBACK_ACCEPTED.name),
            logs.map { it.msg },
        )
        assertEquals(listOf(Log.INFO, Log.INFO), logs.map { it.type })
        for (entry in logs) {
            assertFalse(entry.msg, entry.msg.contains(NONCE) || entry.msg.contains(STATE))
        }
    }

    private companion object {
        const val STATE = "pending-state-0123456789"
        const val NONCE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456"
        const val WAIT = 1_000L
    }
}
