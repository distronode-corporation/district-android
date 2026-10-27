package com.distronode.districtai.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.distronode.districtai.R

/**
 * What the push layer is allowed to put on screen.
 *
 * ⛔ AN INTERFACE SO THE **DECISIONS** ARE TESTABLE WITHOUT THE NOTIFICATION MANAGER. What matters
 * about this layer is not that a notification renders — that is the platform's job — but WHICH one
 * is posted, for which ids, and above all which ones are NOT: a push for a signed-out account, a
 * push with no workspace, an unknown type. Those are `PushMessageHandler`'s rules, and a recorder
 * behind this interface is how they are asserted. See [AndroidPushNotifier] for the half that
 * genuinely needs a device.
 *
 * ⛔ NOTHING HERE TAKES CONTENT FROM THE PAYLOAD, AND THE ONE THING THAT IS NOT AN IDENTIFIER IS
 * FETCHED, NOT PUSHED. The payload carries no message body and no caller number precisely because a
 * notification is readable by the OS and by any notification-listener app. The sender's display
 * name on a message alert (3.1, matching iOS) is read by `MessageSenderLookup` under this app's own
 * session after the push arrives, and the route answers null rather than an address for an unnamed
 * sender, so a number or an email address still never reaches the shade. ⛔ A signature here that
 * accepted a message body would be an invitation to start sending one.
 */
internal interface PushNotifier {

    /**
     * A message arrived. ⚠️ Tapping it opens the workspace's INBOX, not a thread — see [PushEvent.Message].
     *
     * @param senderName the sender's display name, fetched under the app's session; null keeps the
     *   generic title, which is also what every failure to fetch it falls back to.
     * @param interrupt the server's "needs you" flag: posts to the high-importance channel.
     */
    fun showMessage(
        workspaceId: String,
        messageId: String,
        senderName: String? = null,
        interrupt: Boolean = false,
    )

    /**
     * A call is ringing.
     *
     * ⛔ HEADS-UP WITH Answer/Decline EVERYWHERE, AND FULL SCREEN WHERE THE APP MAY USE IT. On a
     * locked phone a heads-up is a line on the lock screen; the full-screen intent is what puts the
     * ringing screen up, as the system dialler does. `USE_FULL_SCREEN_INTENT` is an install-time
     * grant below Android 14 and a grant the user (or Play's calling-app auto-grant) controls from
     * 14, so it is attached only when `canUseFullScreenIntent` says it will be honoured; without
     * it the notification is exactly the 3.0.0 heads-up, which is the guaranteed floor.
     */
    fun showIncomingCall(workspaceId: String, callId: String)

    /**
     * Take the ringing notification away.
     *
     * ⛔ CALLED ON **EVERY** TERMINAL PATH — answered, declined, timed out, or the caller hung up.
     * A ring notification left behind after a call has ended is the one failure of this feature a
     * user cannot work around: its Answer button is still there, and pressing it can only 404.
     */
    fun cancelIncomingCall()
}

/**
 * The real notifier.
 *
 * ⛔ CHANNELS ARE CREATED HERE AND ON EVERY CONSTRUCTION, NOT ONCE AT INSTALL. `createNotification
 * Channel` is idempotent — re-creating an existing channel updates only the fields the USER has not
 * overridden — and the alternative (a one-shot at first launch) leaves an app that was upgraded
 * across a channel addition with no channel at all, so its notifications are dropped by the platform
 * with nothing logged.
 *
 * ⚠️ IMPORTANCE IS SET AT CREATION AND THE USER OWNS IT AFTERWARDS. Raising `messages` later would
 * not take effect on an existing install, which is the right behaviour and worth knowing before
 * treating a channel's importance as something this code controls.
 *
 * ⚠️ `POST_NOTIFICATIONS` IS AN API 33 RUNTIME PERMISSION AND NOTHING HERE CHECKS IT. Without the
 * grant `notify` is a silent no-op rather than an exception, which is exactly the behaviour wanted:
 * the push still arrives, the incoming-call path still registers the Telecom connection, and only
 * the visual half is absent. The grant is requested at the first post-sign-in landing — see the nav
 * host — because a permission dialog with no visible reason is the one users deny permanently.
 */
internal class AndroidPushNotifier(
    context: Context,
    /**
     * ⚠️ A SEAM BECAUSE THE ANSWER IS THE USER'S AND THE DEVICE'S, NOT THIS CODE'S: always true below
     * API 34, and on 34+ whatever Settings > Special app access says. Tests drive both answers.
     */
    private val canUseFullScreenIntent: (NotificationManagerCompat) -> Boolean = { it.canUseFullScreenIntent() },
) : PushNotifier {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        // ⚠️ minSdk is 26, so NotificationChannel is unconditionally available and there is no
        // version guard here. A guard would read as though one level of this app's range lacked it.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                appContext.getString(R.string.push_channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.push_channel_messages_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_URGENT_MESSAGES,
                appContext.getString(R.string.push_channel_urgent_messages),
                // ⛔ HIGH, AND A CHANNEL OF ITS OWN RATHER THAN A PRIORITY ON THE MESSAGES ONE.
                // Importance belongs to the channel, not the notification, from API 26, and the user
                // owns it afterwards: a separate channel is the only way to make "needs you" heads-up
                // while letting a person who finds it too loud turn just these down, and to leave
                // ordinary messages exactly as quiet as they were.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = appContext.getString(R.string.push_channel_urgent_messages_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING_CALLS,
                appContext.getString(R.string.push_channel_calls),
                // ⛔ HIGH, WHICH IS WHAT MAKES IT A HEADS-UP. At DEFAULT the platform posts it to
                // the shade silently, so a ringing call would arrive as a line the user finds later
                // — and the server is holding a caller for twenty-five seconds waiting for an
                // answer that will never come.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = appContext.getString(R.string.push_channel_calls_description)
                setShowBadge(false)
            },
        )
    }

    /**
     * ⛔ THE NOTIFICATION ID IS ALSO THE PENDING INTENT'S REQUEST CODE, AND THAT IS THE FIX FOR A
     * WRONG-THREAD TAP. Every message used to share one request code, and pending-intent identity
     * ignores extras, so each new message's `FLAG_UPDATE_CURRENT` rewrote the intent behind every
     * older notification still on screen: tapping an older one opened the newest message, or nothing
     * when that message was in a workspace that was not active. One code per notification keeps each
     * tap with its own message. ⚠️ Two messages whose ids hash to the same notification id still
     * share a pending intent, which is consistent: the second notification has replaced the first.
     */
    override fun showMessage(workspaceId: String, messageId: String, senderName: String?, interrupt: Boolean) {
        val id = messageNotificationId(messageId)
        val channel = if (interrupt) CHANNEL_URGENT_MESSAGES else CHANNEL_MESSAGES
        // ⚠️ THE NAME IS THE SERVER'S DISPLAY NAME, NEVER TRANSLATED; the middle dot keeps it out of
        // the sentence, so the French needs no elision before a vowel (the iOS wording, verbatim).
        val title = senderName
            ?.let { appContext.getString(R.string.push_message_title_named, it) }
            ?: appContext.getString(R.string.push_message_title)
        val notification = Notification.Builder(appContext, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(appContext.getString(R.string.push_message_body))
            .setAutoCancel(true)
            .setContentIntent(
                PushIntents.activity(
                    appContext,
                    id,
                    PushIntents.inbox(appContext, workspaceId, messageId),
                ),
            )
            .build()
        notify(id, notification)
    }

    override fun showIncomingCall(workspaceId: String, callId: String) {
        val showCall = PushIntents.activity(
            appContext,
            PushIntents.REQUEST_SHOW_CALL,
            PushIntents.showIncomingCall(appContext, workspaceId, callId),
        )
        val builder = Notification.Builder(appContext, CHANNEL_INCOMING_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.push_call_title))
            .setContentText(appContext.getString(R.string.push_call_body))
            // ⛔ ONGOING AND NOT AUTO-CANCEL: a swipe must not dismiss a ringing call, because the
            // Telecom connection and the server's rendezvous would both still be live and the user
            // would have no way back to the Answer button.
            .setOngoing(true)
            // ⚠️ CATEGORY_CALL is what lets the platform rank this above ordinary heads-up
            // notifications and show it through some Do Not Disturb configurations.
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(showCall)
            .addAction(
                Notification.Action.Builder(
                    null,
                    appContext.getString(R.string.push_call_answer),
                    PushIntents.activity(
                        appContext,
                        PushIntents.REQUEST_ANSWER_CALL,
                        PushIntents.answerCall(appContext, workspaceId, callId),
                    ),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    appContext.getString(R.string.push_call_decline),
                    PushIntents.broadcast(
                        appContext,
                        PushIntents.REQUEST_DECLINE_CALL,
                        PushIntents.declineCall(appContext, callId),
                    ),
                ).build(),
            )
        // ⛔ THE SAME PENDING INTENT AS THE BODY TAP, SO A FULL-SCREEN LAUNCH SHOWS THE RING AND
        // NEVER ANSWERS IT (see `PushIntents.EXTRA_ANSWER`). `MainActivity` puts itself over the
        // keyguard while the call is live. ⚠️ Omitted, not attached and left to be demoted, when the
        // grant is missing: what the platform does with a refused full-screen intent has changed
        // between releases, and the heads-up alone is a known shape.
        if (canUseFullScreenIntent(manager)) builder.setFullScreenIntent(showCall, true)
        notify(NOTIFICATION_ID_INCOMING_CALL, builder.build())
    }

    override fun cancelIncomingCall() {
        manager.cancel(NOTIFICATION_ID_INCOMING_CALL)
    }

    /**
     * ⚠️ `NotificationManagerCompat.notify` THROWS `SecurityException` ON SOME OEM BUILDS when the
     * POST_NOTIFICATIONS grant is missing, rather than no-opping the way AOSP does. Losing the
     * visual half of a push is acceptable; crashing the process that is about to answer a call is
     * not, and this is reached from a system service callback where an escape would be an ANR-shaped
     * crash with no screen to attribute it to.
     */
    private fun notify(id: Int, notification: Notification) {
        // ⛔ AN EXPLICIT `catch (SecurityException)` RATHER THAN `runCatching`, AND THE DIFFERENCE
        // IS A LINT ERROR RATHER THAN A STYLE PREFERENCE — the same trap `AndroidTelecomBridge`
        // documents for `placeCall`. `notify` is annotated
        // `@RequiresPermission(POST_NOTIFICATIONS)`, which is a DANGEROUS runtime permission, so
        // Android Lint's `MissingPermission` demands either a `checkSelfPermission` call or a
        // VISIBLE `SecurityException` handler — and it does not recognise `runCatching` as one.
        //
        // ⚠️ THE HANDLER IS ALSO THE CORRECT BEHAVIOUR, not merely the one that satisfies the tool.
        // AOSP makes `notify` a silent no-op without the grant, but some OEM builds throw, and this
        // is reached from a system service callback where an escape would be a crash with no screen
        // to attribute it to. Losing the visual half of a push is acceptable; crashing the process
        // that is about to answer a call is not.
        try {
            manager.notify(id, notification)
        } catch (ignored: SecurityException) {
            // The user has not granted POST_NOTIFICATIONS. The push still arrived, the Telecom
            // connection still rings, and the call is still answerable from the OS's own surfaces.
        }
    }

    internal companion object {

        const val CHANNEL_MESSAGES: String = "messages"

        /** ⚠️ A NEW ID, never a renamed one: an existing channel's importance is the user's. */
        const val CHANNEL_URGENT_MESSAGES: String = "messages_urgent"
        const val CHANNEL_INCOMING_CALLS: String = "incoming_calls"

        /**
         * ⛔ ONE ID FOR THE RINGING NOTIFICATION, BECAUSE THERE IS AT MOST ONE RINGING CALL. The
         * same invariant `DistrictCallRegistry` holds for the Telecom connection: one engine, one
         * audio focus. A per-call id would leave a second ring on screen for a call the first one
         * already replaced.
         */
        const val NOTIFICATION_ID_INCOMING_CALL: Int = 2001

        /** The foreground service's. ⚠️ Distinct from the ring: both are on screen at the handover. */
        const val NOTIFICATION_ID_ONGOING_CALL: Int = 2002

        /**
         * A stable id per message, derived rather than counted.
         *
         * ⛔ DERIVED SO A REDELIVERY REPLACES RATHER THAN STACKS. FCM may deliver the same data
         * message more than once — it guarantees at-least-once — so a counter would put two
         * notifications on screen for one message. ⚠️ The `absoluteValue` and the offset keep it
         * clear of [NOTIFICATION_ID_INCOMING_CALL] and [NOTIFICATION_ID_ONGOING_CALL]; a hash that
         * happened to land on one of those would let a message cancel a ringing call. The same
         * range keeps it clear of the fixed `PushIntents.REQUEST_*` codes, because [showMessage]
         * uses it as the tap intent's request code as well.
         */
        fun messageNotificationId(messageId: String): Int =
            MESSAGE_ID_BASE + (messageId.hashCode() % MESSAGE_ID_SPACE).let {
                if (it < 0) it + MESSAGE_ID_SPACE else it
            }

        private const val MESSAGE_ID_BASE = 100_000
        private const val MESSAGE_ID_SPACE = 100_000
    }
}
