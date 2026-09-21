package com.distronode.districtai.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one place a raw FCM `data` map becomes something this app acts on.
 *
 * ⛔ THIS IS THE ONLY PART OF THE PUSH PATH THAT CAN BE TESTED ON THIS MACHINE AT ALL. Waydroid is
 * API 33 with no Play Services, so delivery, Doze wake and token acquisition are all real-device
 * work — and this is where every hostile-payload decision lives, which is why it is a pure function
 * over a map rather than logic inside a system-constructed service.
 *
 * ⛔ THE MAP IS THE LEAST TRUSTWORTHY INPUT IN THE APP. FCM `data` has exactly one type (string),
 * every value is chosen by the sender, and a malformed payload is indistinguishable from a malicious
 * one. Every assertion below is about the same rule: anything this build cannot act on is DROPPED,
 * never defaulted.
 */
class PushPayloadTest {

    private fun message(vararg pairs: Pair<String, String>) = PushPayload.parse(mapOf(*pairs))

    @Test
    fun `a message payload parses to its two ids`() {
        val event = message(
            PushPayload.KEY_TYPE to PushPayload.TYPE_MESSAGE,
            PushPayload.KEY_WORKSPACE_ID to "ws-1",
            PushPayload.KEY_MESSAGE_ID to "msg-1",
        )

        assertEquals(PushEvent.Message(workspaceId = "ws-1", messageId = "msg-1"), event)
    }

    @Test
    fun `an incoming-call payload parses to its two ids`() {
        val event = message(
            PushPayload.KEY_TYPE to PushPayload.TYPE_INCOMING_CALL,
            PushPayload.KEY_WORKSPACE_ID to "ws-1",
            PushPayload.KEY_CALL_ID to "CA1",
        )

        assertEquals(PushEvent.IncomingCall(workspaceId = "ws-1", callId = "CA1"), event)
    }

    @Test
    fun `an unknown type is dropped silently, which is forward compatibility`() {
        // ⛔ THE SERVER MAY SHIP A PUSH TYPE BEFORE THIS BUILD IS ON EVERY HANDSET — that is the
        // ordinary state of a shipped app, not an exception. Surfacing it would show users a
        // notification about a feature they do not have; it is the same "lenient in the field" rule
        // the API parser follows.
        assertNull(
            message(
                PushPayload.KEY_TYPE to "voicemail",
                PushPayload.KEY_WORKSPACE_ID to "ws-1",
                PushPayload.KEY_MESSAGE_ID to "msg-1",
            ),
        )
    }

    @Test
    fun `a payload with no type at all is dropped`() {
        assertNull(message(PushPayload.KEY_WORKSPACE_ID to "ws-1", PushPayload.KEY_MESSAGE_ID to "m"))
    }

    @Test
    fun `an empty payload is dropped`() {
        assertNull(PushPayload.parse(emptyMap()))
    }

    @Test
    fun `a message with no workspace is dropped rather than notified about nothing`() {
        assertNull(
            message(
                PushPayload.KEY_TYPE to PushPayload.TYPE_MESSAGE,
                PushPayload.KEY_MESSAGE_ID to "msg-1",
            ),
        )
    }

    @Test
    fun `a call with no call id is dropped, because its Answer button could only 404`() {
        // ⛔ THE SHARPEST OF THE THREE DROPS. A ringing screen whose Answer button cannot name a
        // call is worse than no ring: the user answers, the request 404s, and the caller — whom the
        // server is holding for twenty-five seconds — reaches nobody while the app says it tried.
        assertNull(
            message(
                PushPayload.KEY_TYPE to PushPayload.TYPE_INCOMING_CALL,
                PushPayload.KEY_WORKSPACE_ID to "ws-1",
            ),
        )
    }

    @Test
    fun `blank and whitespace-only ids are treated as absent`() {
        // ⚠️ FCM's `data` HAS EXACTLY ONE TYPE, so "no id" and "an empty id" are the same wire
        // shape, and a whitespace-only value is what a mis-templated sender produces.
        assertNull(
            message(
                PushPayload.KEY_TYPE to PushPayload.TYPE_INCOMING_CALL,
                PushPayload.KEY_WORKSPACE_ID to "  ",
                PushPayload.KEY_CALL_ID to "CA1",
            ),
        )
        assertNull(
            message(
                PushPayload.KEY_TYPE to PushPayload.TYPE_INCOMING_CALL,
                PushPayload.KEY_WORKSPACE_ID to "ws-1",
                PushPayload.KEY_CALL_ID to "",
            ),
        )
    }

    @Test
    fun `ids are trimmed, so a padded value is still usable`() {
        assertEquals(
            PushEvent.IncomingCall(workspaceId = "ws-1", callId = "CA1"),
            message(
                PushPayload.KEY_TYPE to PushPayload.TYPE_INCOMING_CALL,
                PushPayload.KEY_WORKSPACE_ID to " ws-1 ",
                PushPayload.KEY_CALL_ID to "\tCA1\n",
            ),
        )
    }

    @Test
    fun `the type is matched exactly, not case-insensitively or by prefix`() {
        // ⚠️ THE SERVER SENDS THESE TWO LITERALS AND NOTHING ELSE. Loosening the match would mean a
        // future `incoming_call_v2` silently taking the old branch, which on this path means ringing
        // a phone with a payload this build cannot fully read.
        assertNull(
            message(
                PushPayload.KEY_TYPE to "Incoming_Call",
                PushPayload.KEY_WORKSPACE_ID to "ws-1",
                PushPayload.KEY_CALL_ID to "CA1",
            ),
        )
    }

    @Test
    fun `extra keys are ignored rather than rejected`() {
        // ⚠️ THE OPPOSITE OF THE CONTRACT FIXTURES' STRICTNESS, AND DELIBERATELY SO: those pin what
        // the server sends so drift reds CI, while the SHIPPED parser must degrade to "ignored" on
        // an already-installed build rather than dropping every push after a server change.
        assertEquals(
            PushEvent.Message(workspaceId = "ws-1", messageId = "msg-1"),
            message(
                PushPayload.KEY_TYPE to PushPayload.TYPE_MESSAGE,
                PushPayload.KEY_WORKSPACE_ID to "ws-1",
                PushPayload.KEY_MESSAGE_ID to "msg-1",
                "somethingNew" to "value",
            ),
        )
    }
}
