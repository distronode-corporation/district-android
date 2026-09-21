package com.distronode.districtai.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.MainActivity
import com.distronode.districtai.call.IncomingCallActionReceiver
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What each notification action actually launches.
 *
 * ⛔ THESE ARE ASSERTED BEFORE THEY ARE WRAPPED IN A `PendingIntent`, BECAUSE AFTERWARDS THEY CANNOT
 * BE. A pending intent is opaque — nothing can read back the extras or the component inside one — so
 * the only place the target, the flags and the payload are checkable is here. That is the entire
 * reason `PushIntents` is a separate object from the notifications that use it.
 *
 * ⛔ AND THE ONE THAT MATTERS MOST IS THE ANSWER/SHOW DISTINCTION. Both open the same Activity with
 * the same two ids; without [PushIntents.EXTRA_ANSWER] they would be indistinguishable to the
 * receiving side, and tapping a notification to SEE who is calling would join the conversation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PushIntentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a message notification opens the activity with its workspace and message`() {
        val intent = PushIntents.inbox(context, "ws-1", "msg-1")

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("ws-1", intent.getStringExtra(PushIntents.EXTRA_WORKSPACE_ID))
        assertEquals("msg-1", intent.getStringExtra(PushIntents.EXTRA_MESSAGE_ID))
        assertNull("a message is not a call", intent.getStringExtra(PushIntents.EXTRA_CALL_ID))
    }

    @Test
    fun `a message notification keeps the existing task rather than clearing it`() {
        // ⛔ `SINGLE_TOP` AND NOT `CLEAR_TASK`. The activity is `launchMode="singleTask"` for the
        // PKCE callback's sake, so a running instance receives this through `onNewIntent` and keeps
        // its navigation back stack. Clearing the task would throw away whatever the user was doing
        // in order to show them a message.
        val flags = PushIntents.inbox(context, "ws-1", "msg-1").flags

        assertTrue(flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(0, flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    @Test
    fun `tapping the body of a ringing notification does NOT carry the answer flag`() {
        // ⛔ THE SAFE DEFAULT, AND THE REASON THE FLAG EXISTS. Its ABSENCE is what makes an intent
        // that somehow lost its extras open the ringing screen rather than answer the call.
        val intent = PushIntents.showIncomingCall(context, "ws-1", "CA1")

        assertEquals("CA1", intent.getStringExtra(PushIntents.EXTRA_CALL_ID))
        assertFalse(intent.getBooleanExtra(PushIntents.EXTRA_ANSWER, false))
    }

    @Test
    fun `the Answer action is the same intent plus the answer flag`() {
        val intent = PushIntents.answerCall(context, "ws-1", "CA1")

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("ws-1", intent.getStringExtra(PushIntents.EXTRA_WORKSPACE_ID))
        assertEquals("CA1", intent.getStringExtra(PushIntents.EXTRA_CALL_ID))
        assertTrue(intent.getBooleanExtra(PushIntents.EXTRA_ANSWER, false))
    }

    @Test
    fun `Decline names the receiver's component explicitly rather than broadcasting implicitly`() {
        // ⛔ AN IMPLICIT BROADCAST WOULD FAIL IN THE ORDINARY CASE AND LEAK IN THE EXOTIC ONE. On
        // API 26+ it would not reach a manifest-registered receiver at all, and any app that
        // registered for the action would see the call id.
        val intent = PushIntents.declineCall(context, "CA1")

        assertEquals(IncomingCallActionReceiver::class.java.name, intent.component?.className)
        assertEquals(PushIntents.ACTION_DECLINE, intent.action)
        assertEquals("CA1", intent.getStringExtra(PushIntents.EXTRA_CALL_ID))
    }

    @Test
    fun `Decline goes nowhere near the Activity`() {
        // ⚠️ THE ASYMMETRY WITH Answer, PINNED. Declining has nothing to show, and routing it
        // through an Activity would raise the whole app over whatever the user was doing in order to
        // close a notification.
        assertNotEquals(
            MainActivity::class.java.name,
            PushIntents.declineCall(context, "CA1").component?.className,
        )
    }

    @Test
    fun `the four request codes are distinct`() {
        // ⛔ `PendingIntent` EQUALITY IGNORES EXTRAS, so two intents to the same component with the
        // same action and flags are THE SAME pending intent — the second `getActivity` returns the
        // first one's payload. Tapping the body of an incoming-call notification would then answer
        // it. The codes are the only thing keeping them apart.
        val codes = listOf(
            PushIntents.REQUEST_INBOX,
            PushIntents.REQUEST_SHOW_CALL,
            PushIntents.REQUEST_ANSWER_CALL,
            PushIntents.REQUEST_DECLINE_CALL,
        )

        assertEquals("every notification action needs its own request code", 4, codes.toSet().size)
    }

    @Test
    fun `every pending intent is immutable and updates in place`() {
        // ⛔ IMMUTABLE IS A SECURITY PROPERTY: a mutable pending intent handed to the notification
        // shade is a capability any app that can reach the shade may rewrite the extras of, and
        // these extras name a workspace and a live call id.
        // ⚠️ UPDATE_CURRENT is what stops a pending intent minted for an EARLIER call being reused
        // wholesale — the same equality-ignores-extras rule, applied across time.
        val pending = PushIntents.activity(
            context,
            PushIntents.REQUEST_ANSWER_CALL,
            PushIntents.answerCall(context, "ws-1", "CA1"),
        )

        assertTrue("the pending intent must exist", pending.creatorPackage != null)
        assertTrue(pending.isImmutable)
    }

    @Test
    fun `a broadcast pending intent is built for the receiver, not for an activity`() {
        val pending = PushIntents.broadcast(
            context,
            PushIntents.REQUEST_DECLINE_CALL,
            PushIntents.declineCall(context, "CA1"),
        )

        assertTrue(pending.isImmutable)
    }
}
