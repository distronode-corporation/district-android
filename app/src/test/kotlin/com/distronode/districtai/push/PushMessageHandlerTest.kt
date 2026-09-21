package com.distronode.districtai.push

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A [PushNotifier] that records what it was asked to draw.
 *
 * ⚠️ HAND-WRITTEN RATHER THAN MOCKED, matching every other test double here: the question is "what
 * did a delivered push actually put on screen", which reads better as a list than as an argument
 * captor — and the most important assertions are that the list is EMPTY.
 */
internal class RecordingPushNotifier : PushNotifier {
    val drawn: MutableList<String> = mutableListOf()

    override fun showMessage(workspaceId: String, messageId: String) {
        drawn += "message:$workspaceId:$messageId"
    }

    override fun showIncomingCall(workspaceId: String, callId: String) {
        drawn += "call:$workspaceId:$callId"
    }

    override fun cancelIncomingCall() {
        drawn += "cancel"
    }
}

/**
 * The gate a delivered push has to pass, and the two places it can go.
 *
 * ⛔ THE ASSERTIONS THAT MATTER MOST ARE THE ONES WHERE NOTHING HAPPENS. A push that draws a
 * notification for an account no longer on this device is a disclosure the ids-only payload was
 * designed to prevent — and it is reachable rather than theoretical: the server's `DevicePushToken`
 * row survives a sign-out whose unregister could not be delivered, and FCM keeps delivering to a
 * live token regardless of what this app thinks.
 */
class PushMessageHandlerTest {

    private class Harness(private val signedIn: Boolean = true) {
        val notifier = RecordingPushNotifier()
        val calls: MutableList<PushEvent.IncomingCall> = mutableListOf()
        val handler = PushMessageHandler(
            signedIn = { signedIn },
            notifier = notifier,
            onIncomingCall = { calls += it },
        )
    }

    private fun messagePayload() = mapOf(
        PushPayload.KEY_TYPE to PushPayload.TYPE_MESSAGE,
        PushPayload.KEY_WORKSPACE_ID to "ws-1",
        PushPayload.KEY_MESSAGE_ID to "msg-1",
    )

    private fun callPayload() = mapOf(
        PushPayload.KEY_TYPE to PushPayload.TYPE_INCOMING_CALL,
        PushPayload.KEY_WORKSPACE_ID to "ws-1",
        PushPayload.KEY_CALL_ID to "CA1",
    )

    @Test
    fun `a message push draws a notification for its workspace`() = runTest {
        val harness = Harness()

        val event = harness.handler.handle(messagePayload())

        assertEquals(PushEvent.Message("ws-1", "msg-1"), event)
        assertEquals(listOf("message:ws-1:msg-1"), harness.notifier.drawn)
        assertEquals(emptyList<PushEvent.IncomingCall>(), harness.calls)
    }

    @Test
    fun `an incoming-call push goes to the call controller, not to the notifier`() = runTest {
        // ⛔ THE SPLIT IS THE POINT. Answering a call is not a notification: it registers a Telecom
        // connection, arms a ring timeout and eventually holds a microphone. The controller owns all
        // of that and draws its OWN notification, so a handler that posted one here would put two
        // rings on screen for one call.
        val harness = Harness()

        val event = harness.handler.handle(callPayload())

        assertEquals(PushEvent.IncomingCall("ws-1", "CA1"), event)
        assertEquals(listOf(PushEvent.IncomingCall("ws-1", "CA1")), harness.calls)
        assertEquals(emptyList<String>(), harness.notifier.drawn)
    }

    @Test
    fun `a signed-out device draws nothing and rings nothing`() = runTest {
        // ⛔ REACHABLE, NOT THEORETICAL. The server's row survives a sign-out whose unregister could
        // not be delivered, and FCM keeps delivering to a live token — so a phone signed out on an
        // aeroplane can be woken by a push for the account that used to be on it. A notification
        // saying a message arrived, on a handset somebody else is now holding, is exactly the
        // disclosure the ids-only payload exists to prevent.
        val harness = Harness(signedIn = false)

        assertNull(harness.handler.handle(messagePayload()))
        assertNull(harness.handler.handle(callPayload()))

        assertEquals(emptyList<String>(), harness.notifier.drawn)
        assertEquals(emptyList<PushEvent.IncomingCall>(), harness.calls)
    }

    @Test
    fun `a malformed payload is dropped before the session is even consulted`() = runTest {
        // ⚠️ THE ORDER IS DELIBERATE: parse first, so a malformed push costs no Keystore read; the
        // session check second, so nothing is drawn or rung for an account that has left. This
        // asserts the first half — a signed-out harness would pass either way, so the signed-IN one
        // is what makes the drop attributable to the parse.
        val harness = Harness(signedIn = true)

        assertNull(harness.handler.handle(mapOf("type" to "voicemail")))
        assertNull(harness.handler.handle(emptyMap()))

        assertEquals(emptyList<String>(), harness.notifier.drawn)
    }

    @Test
    fun `two message pushes draw two notifications, because delivery is at-least-once`() = runTest {
        // ⚠️ THE HANDLER DEDUPLICATES NOTHING, DELIBERATELY. FCM guarantees at-least-once delivery,
        // and the collapse happens one layer down: `AndroidPushNotifier` derives a stable id from
        // the message id, so a redelivery REPLACES rather than stacks. Doing it here instead would
        // need a set that grows for the life of the process.
        val harness = Harness()

        harness.handler.handle(messagePayload())
        harness.handler.handle(messagePayload())

        assertEquals(listOf("message:ws-1:msg-1", "message:ws-1:msg-1"), harness.notifier.drawn)
    }
}
