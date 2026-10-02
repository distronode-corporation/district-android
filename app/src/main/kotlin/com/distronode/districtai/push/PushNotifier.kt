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
 * ⛔ NOTHING HERE TAKES CONTENT, ONLY IDENTIFIERS, AND THAT IS THE SERVER'S PRIVACY DECISION
 * SURVIVING INTO THE UI. The payload carries no message body and no caller number precisely because
 * a notification is readable by the OS and by any notification-listener app; a signature here that
 * accepted a body would be an invitation to start sending one.
 */
internal interface PushNotifier {

    /** A message arrived. ⚠️ Tapping it opens the workspace's INBOX, not a thread — see [PushEvent.Message]. */
    fun showMessage(workspaceId: String, messageId: String)

    /**
     * A call is ringing.
     *
     * ⛔ HEADS-UP WITH Answer/Decline, AND DELIBERATELY **NO** `USE_FULL_SCREEN_INTENT` THIS BATCH.
     * The permission's auto-grant requires the app's core functionality to be calling, and a
     * nineteen-section business dashboard will not clear that bar — so on Android 14+ a full-screen
     * intent would be DEMOTED to a heads-up notification anyway, and the app would carry a
     * permission it was refused for nothing. The heads-up path is the one that is guaranteed to
     * work, which is why it is the one that is built.
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
internal class AndroidPushNotifier(context: Context) : PushNotifier {

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
    override fun showMessage(workspaceId: String, messageId: String) {
        val id = messageNotificationId(messageId)
        val notification = Notification.Builder(appContext, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.push_message_title))
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
        val notification = Notification.Builder(appContext, CHANNEL_INCOMING_CALLS)
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
            .setContentIntent(
                PushIntents.activity(
                    appContext,
                    PushIntents.REQUEST_SHOW_CALL,
                    PushIntents.showIncomingCall(appContext, workspaceId, callId),
                ),
            )
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
            .build()
        notify(NOTIFICATION_ID_INCOMING_CALL, notification)
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
