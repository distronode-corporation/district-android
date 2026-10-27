package com.distronode.districtai.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.call.ongoingCallNotification
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * The three channels, the three notifications, and the ids that keep them apart.
 *
 * ⛔ THE CHANNEL IMPORTANCES ARE NOT COSMETIC. `incoming_calls` at anything below HIGH is posted to
 * the shade SILENTLY — so a ringing call arrives as a line the user finds later, while the server is
 * holding a caller for twenty-five seconds waiting for an answer that will never come. `messages` at
 * DEFAULT is the deliberate opposite: an inbox message is not an interruption.
 *
 * ⛔ AND THE IDS ARE A CORRECTNESS PROPERTY RATHER THAN BOOKKEEPING. The ringing notification and the
 * foreground service's are both on screen at the handover, and a message id that collided with
 * either would let an inbound SMS cancel a live call.
 *
 * ⚠️ WHAT ROBOLECTRIC CANNOT DO HERE: it does not render, it does not enforce `POST_NOTIFICATIONS`,
 * and it does not model heads-up behaviour or Do Not Disturb. Those are on-device questions. What it
 * DOES do is build the real `Notification` objects and hold the real channel records, which is where
 * every decision above is readable.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PushNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var manager: NotificationManager
    private lateinit var notifier: AndroidPushNotifier

    @Before
    fun setUp() {
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifier = AndroidPushNotifier(context)
    }

    private fun posted() = shadowOf(manager).allNotifications

    @Test
    fun `both channels exist, and the calls channel is the loud one`() {
        val messages = manager.getNotificationChannel(AndroidPushNotifier.CHANNEL_MESSAGES)
        val calls = manager.getNotificationChannel(AndroidPushNotifier.CHANNEL_INCOMING_CALLS)

        assertNotNull(messages)
        assertNotNull(calls)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, messages.importance)
        // ⛔ HIGH IS WHAT MAKES IT A HEADS-UP. At DEFAULT the platform posts it silently and the ring
        // is a line in the shade — for a call the server is actively holding a human for.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, calls.importance)
    }

    @Test
    fun `the urgent messages channel is its own, and heads-up`() {
        // ⛔ A SEPARATE CHANNEL, so "needs you" can be loud while ordinary messages stay at DEFAULT,
        // and a person who finds it too loud can turn just these down in the system settings.
        val urgent = manager.getNotificationChannel(AndroidPushNotifier.CHANNEL_URGENT_MESSAGES)

        assertNotNull(urgent)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, urgent.importance)
        assertEquals(context.getString(R.string.push_channel_urgent_messages), urgent.name.toString())
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(AndroidPushNotifier.CHANNEL_MESSAGES).importance,
        )
    }

    @Test
    fun `a message flagged as needing the person is posted on the urgent channel`() {
        notifier.showMessage("ws-1", "msg-1", senderName = null, interrupt = true)

        assertEquals(AndroidPushNotifier.CHANNEL_URGENT_MESSAGES, posted().single().channelId)
    }

    @Test
    fun `a message whose sender's name was fetched is titled with it`() {
        // ⚠️ THE iOS WORDING: "New message · {name}", the name never translated. The body stays the
        // generic line: the name is the one thing fetched, and no content is.
        notifier.showMessage("ws-1", "msg-1", senderName = "Avery Contract", interrupt = false)

        val notification = posted().single()
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

        assertEquals(context.getString(R.string.push_message_title_named, "Avery Contract"), title)
        assertTrue(title.endsWith("· Avery Contract"))
        assertEquals(context.getString(R.string.push_message_body), text)
        assertEquals(AndroidPushNotifier.CHANNEL_MESSAGES, notification.channelId)
    }

    @Test
    fun `constructing the notifier twice does not break the channels`() {
        // ⛔ CHANNELS ARE CREATED ON EVERY CONSTRUCTION RATHER THAN ONCE AT FIRST LAUNCH.
        // `createNotificationChannel` is idempotent — it updates only fields the USER has not
        // overridden — and the one-shot alternative leaves an app upgraded across a channel addition
        // with no channel at all, so its notifications are dropped by the platform with nothing
        // logged.
        AndroidPushNotifier(context)
        AndroidPushNotifier(context)

        assertNotNull(manager.getNotificationChannel(AndroidPushNotifier.CHANNEL_INCOMING_CALLS))
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(AndroidPushNotifier.CHANNEL_INCOMING_CALLS).importance,
        )
    }

    @Test
    fun `a message notification is posted on the messages channel and can be dismissed`() {
        notifier.showMessage("ws-1", "msg-1")

        val notification = posted().single()
        assertEquals(AndroidPushNotifier.CHANNEL_MESSAGES, notification.channelId)
        assertNotNull("tapping it must go somewhere", notification.contentIntent)
        // ⚠️ AUTO-CANCEL: an inbox notification the user has acted on should go away, unlike a
        // ringing call, which must survive a swipe.
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `each message notification opens its own message, not the newest one`() {
        // ⛔ `PendingIntent` IDENTITY IS THE REQUEST CODE PLUS `filterEquals`, WHICH IGNORES EXTRAS.
        // With one shared request code, the second message's `FLAG_UPDATE_CURRENT` rewrote the
        // first notification's intent, so tapping the older message opened the newer one, or
        // nothing at all when the newer one was in a workspace that was not active.
        notifier.showMessage("ws-1", "msg-1")
        notifier.showMessage("ws-2", "msg-2")

        val first = shadowOf(manager).getNotification(AndroidPushNotifier.messageNotificationId("msg-1"))
        val second = shadowOf(manager).getNotification(AndroidPushNotifier.messageNotificationId("msg-2"))
        val firstIntent = shadowOf(first.contentIntent).savedIntent
        val secondIntent = shadowOf(second.contentIntent).savedIntent

        assertEquals("msg-1", firstIntent.getStringExtra(PushIntents.EXTRA_MESSAGE_ID))
        assertEquals("ws-1", firstIntent.getStringExtra(PushIntents.EXTRA_WORKSPACE_ID))
        assertEquals("msg-2", secondIntent.getStringExtra(PushIntents.EXTRA_MESSAGE_ID))
        assertEquals("ws-2", secondIntent.getStringExtra(PushIntents.EXTRA_WORKSPACE_ID))
    }

    @Test
    fun `a message notification carries no content, only a way to open the app`() {
        // ⛔ THE SERVER'S IDS-ONLY DISCIPLINE REACHING THE SCREEN. A notification is readable by the
        // OS and by any installed notification-listener app, so the sender never puts a body, a
        // number or an address in the payload — and this is the surface where somebody would be
        // tempted to add one back. Without a fetched name the strings are fixed; the one format
        // argument (the sender's name) is fetched under the app's session, never pushed.
        notifier.showMessage("ws-1", "msg-1")

        val notification = posted().single()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

        assertEquals(context.getString(R.string.push_message_title), title)
        assertEquals(context.getString(R.string.push_message_body), text)
    }

    @Test
    fun `a ringing notification is ongoing, categorised as a call, and offers two actions`() {
        notifier.showIncomingCall("ws-1", "CA1")

        val notification = posted().single()
        assertEquals(AndroidPushNotifier.CHANNEL_INCOMING_CALLS, notification.channelId)
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        // ⛔ ONGOING: a swipe must not dismiss a ringing call, because the Telecom connection and the
        // server's rendezvous would both still be live and the user would have no way back to the
        // Answer button.
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(2, notification.actions.size)
        assertEquals(context.getString(R.string.push_call_answer), notification.actions[0].title)
        assertEquals(context.getString(R.string.push_call_decline), notification.actions[1].title)
    }

    @Test
    fun `a ring carries a full-screen intent that shows the call, when the app may use one`() {
        // ⛔ ON A LOCKED PHONE THE FULL-SCREEN INTENT IS WHAT PUTS THE RINGING SCREEN UP. 3.0.0 had
        // none (fullScreenIntent=null in the 2026-10-15 ring test), so a locked phone showed only a
        // line on the lock screen.
        val granted = AndroidPushNotifier(context, canUseFullScreenIntent = { true })

        granted.showIncomingCall("ws-1", "CA1")

        val notification = posted().single()
        assertNotNull(notification.fullScreenIntent)
        val launched = shadowOf(notification.fullScreenIntent).savedIntent
        assertEquals("CA1", launched.getStringExtra(PushIntents.EXTRA_CALL_ID))
        assertEquals("ws-1", launched.getStringExtra(PushIntents.EXTRA_WORKSPACE_ID))
        // ⛔ IT SHOWS THE RING AND NEVER ANSWERS IT: the same intent as the body tap.
        assertFalse(launched.hasExtra(PushIntents.EXTRA_ANSWER))
        assertTrue(notification.fullScreenIntent == notification.contentIntent)
    }

    @Test
    fun `without the full-screen grant the ring is the heads-up alone`() {
        // ⚠️ ANDROID 14+ WITHOUT THE GRANT: the intent is left off rather than attached and demoted,
        // and everything else about the ring is unchanged.
        val refused = AndroidPushNotifier(context, canUseFullScreenIntent = { false })

        refused.showIncomingCall("ws-1", "CA1")

        val notification = posted().single()
        assertNull(notification.fullScreenIntent)
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertEquals(2, notification.actions.size)
    }

    @Test
    fun `a second ring replaces the first rather than stacking`() {
        // ⛔ ONE ID FOR THE RINGING NOTIFICATION, BECAUSE THERE IS AT MOST ONE RINGING CALL — the
        // same invariant `DistrictCallRegistry` holds for the Telecom connection. A per-call id
        // would leave a second ring on screen for a call the first one already replaced.
        notifier.showIncomingCall("ws-1", "CA1")
        notifier.showIncomingCall("ws-1", "CA2")

        assertEquals(1, posted().size)
    }

    @Test
    fun `cancelling the ring takes it away`() {
        // ⛔ CALLED ON EVERY TERMINAL PATH — answered, declined, timed out, caller hung up. A ring
        // left behind after a call has ended is the one failure of this feature a user cannot work
        // around: its Answer button is still there and pressing it can only 404.
        notifier.showIncomingCall("ws-1", "CA1")
        notifier.cancelIncomingCall()

        assertEquals(0, posted().size)
    }

    @Test
    fun `a message id maps to a stable notification id that avoids the call ids`() {
        // ⛔ DERIVED SO A REDELIVERY REPLACES RATHER THAN STACKS — FCM guarantees at-least-once, so
        // a counter would put two notifications on screen for one message. ⚠️ And offset clear of
        // the two reserved ids, because a hash that landed on one of them would let a message cancel
        // a ringing call.
        val first = AndroidPushNotifier.messageNotificationId("msg-1")
        val again = AndroidPushNotifier.messageNotificationId("msg-1")
        val other = AndroidPushNotifier.messageNotificationId("msg-2")

        assertEquals(first, again)
        assertNotEquals(first, other)
        assertNotEquals(AndroidPushNotifier.NOTIFICATION_ID_INCOMING_CALL, first)
        assertNotEquals(AndroidPushNotifier.NOTIFICATION_ID_ONGOING_CALL, first)
        assertTrue("a negative hash must not produce a negative id", first > 0)
    }

    @Test
    fun `a message id whose hash is negative still lands in the safe range`() {
        // ⚠️ `String.hashCode()` IS SIGNED, and a modulo of a negative is negative in Kotlin — which
        // would produce ids below the reserved pair rather than above it. This drives a value known
        // to hash negative rather than trusting the arithmetic by inspection.
        val negative = generateSequence(0) { it + 1 }
            .map { "msg-$it" }
            .first { it.hashCode() < 0 }

        val id = AndroidPushNotifier.messageNotificationId(negative)

        assertTrue("id $id from $negative must be clear of the reserved ids", id >= 100_000)
    }

    @Test
    fun `the ongoing-call notification shares the calls channel and offers no actions`() {
        // ⛔ ON THE `incoming_calls` CHANNEL RATHER THAN A THIRD ONE: a user who silenced calls has
        // silenced this too, which is what they asked for. ⚠️ And NO actions — hanging up belongs on
        // the in-call screen, and an action here would be one more path into a teardown that has to
        // happen exactly once.
        val notification = ongoingCallNotification(context)

        assertEquals(AndroidPushNotifier.CHANNEL_INCOMING_CALLS, notification.channelId)
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(null, notification.actions)
    }

    @Test
    @Config(shadows = [RefusingNotificationManagerShadow::class])
    fun `a platform that refuses to post drops the notification rather than crashing the push`() {
        // ⚠️ SOME BUILDS THROW `SecurityException` FROM `notify` rather than dropping a post the app
        // may not make. A throw here would escape into the FCM callback and lose the ring's Telecom
        // half along with the notification.
        val refusing = AndroidPushNotifier(context)

        refusing.showMessage("ws-1", "msg-1")
        refusing.showIncomingCall("ws-1", "CA1")

        assertTrue(posted().isEmpty())
    }
}

/** A notification manager that refuses every post, the way some OEM builds do. */
@Implements(NotificationManager::class)
class RefusingNotificationManagerShadow : ShadowNotificationManager() {
    @Implementation
    override fun notify(tag: String?, id: Int, notification: Notification?) {
        throw SecurityException("posting notifications is not allowed")
    }
}
