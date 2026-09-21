package com.distronode.districtai.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deciding what an Activity should do about the intent a notification just delivered.
 *
 * ⛔ EVERYTHING THAT CAN BE WRONG ABOUT A NOTIFICATION TAP IS A DECISION OVER FOUR NULLABLE EXTRAS,
 * and none of it needs an Activity, an Intent or a device. That is why it is a pure function: the
 * Activity is `singleTask`, constructed by the system, and its interesting cases — a cold start from
 * a notification, a stale pending intent after a rotation — are precisely the ones a test host does
 * not reproduce.
 */
class PushIntentActionTest {

    @Test
    fun `a message intent carries its workspace and its message id`() {
        val action = pushIntentAction(
            workspaceId = "ws-1",
            messageId = "msg-1",
            callId = null,
            answer = false,
        )

        // ⚠️ THE ID MUST SURVIVE THIS FAR: it is what `GET /api/district/messages/[id]` exchanges
        // for the thread, and dropping it here would land every tap on the inbox list again.
        assertEquals(PushIntentAction.OpenInbox("ws-1", "msg-1"), action)
    }

    @Test
    fun `a call intent with the answer flag answers`() {
        val action = pushIntentAction(
            workspaceId = "ws-1",
            messageId = null,
            callId = "CA1",
            answer = true,
        )

        assertEquals(PushIntentAction.AnswerCall, action)
    }

    @Test
    fun `a call intent without the flag only shows the call`() {
        // ⛔ THE DISTINCTION IS THE WHOLE REASON THE FLAG EXISTS. Without it, tapping a notification
        // to SEE who is calling would join the conversation.
        val action = pushIntentAction(
            workspaceId = "ws-1",
            messageId = null,
            callId = "CA1",
            answer = false,
        )

        assertEquals(PushIntentAction.ShowCall, action)
    }

    @Test
    fun `the answer flag alone answers nothing`() {
        // ⛔ IT IS CHECKED AGAINST A CALL ID, NOT ON ITS OWN. An intent carrying the flag and no call
        // id is malformed, not a request to answer "whatever is ringing" — and treating it as one
        // would let a stale `PendingIntent` from a previous call join the current one.
        assertNull(
            pushIntentAction(workspaceId = "ws-1", messageId = null, callId = null, answer = true),
        )
        assertNull(
            pushIntentAction(workspaceId = null, messageId = null, callId = "  ", answer = true),
        )
    }

    @Test
    fun `an ordinary launcher intent produces nothing`() {
        // ⚠️ THE COMMON CASE BY FAR. Every launcher tap and every PKCE callback also arrives as an
        // intent, and this has to be silent about all of them.
        assertNull(pushIntentAction(null, null, null, answer = false))
    }

    @Test
    fun `a message intent missing either id produces nothing`() {
        assertNull(pushIntentAction(workspaceId = "ws-1", messageId = null, callId = null, answer = false))
        assertNull(pushIntentAction(workspaceId = null, messageId = "msg-1", callId = null, answer = false))
        assertNull(pushIntentAction(workspaceId = "", messageId = "msg-1", callId = null, answer = false))
    }

    @Test
    fun `a call id wins over a message id when somehow both are present`() {
        // ⛔ THE ORDERING IS DELIBERATE. Both notification families carry a workspace id and only the
        // call family carries a call id, so testing the message branch first would make a ringing
        // call open the inbox on any build that ever adds a message id to a call intent — a silent
        // downgrade from "answer this call" to "read your messages", while the caller waits.
        val action = pushIntentAction(
            workspaceId = "ws-1",
            messageId = "msg-1",
            callId = "CA1",
            answer = true,
        )

        assertEquals(PushIntentAction.AnswerCall, action)
    }
}
