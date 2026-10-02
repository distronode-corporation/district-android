package com.distronode.districtai.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.distronode.districtai.MainActivity
import com.distronode.districtai.call.IncomingCallActionReceiver

/**
 * Every intent a notification can fire, built in one place.
 *
 * ⛔ THE BUILDERS ARE SEPARATE FROM THE NOTIFICATIONS THEY GO INTO SO THEY CAN BE ASSERTED. A
 * notification's `PendingIntent` is opaque once wrapped — nothing can read back what is inside one —
 * so the only place the extras, the flags and the target component are checkable is before they are
 * wrapped. Every rule below is a rule a test can hold this file to.
 *
 * ⛔ `FLAG_IMMUTABLE` ON EVERY `PendingIntent`, AND IT IS A SECURITY PROPERTY RATHER THAN AN API-31
 * FORMALITY. A mutable pending intent handed to the notification shade is a capability any app that
 * can reach the shade may rewrite the extras of — and these extras name a workspace and a live call
 * id. Android 12 requires one of the two flags to be stated; immutable is the one that is correct
 * here, because nothing needs to fill anything in.
 *
 * ⛔ THE ANSWER ACTION TARGETS AN **ACTIVITY**, NOT A BROADCAST, AND THE DECLINE TARGETS A
 * BROADCAST, NOT AN ACTIVITY. That asymmetry is deliberate in both directions. Answering has to put
 * a UI in front of the user — they are about to be in a conversation and need mute, speaker and hang
 * up — and on Android 12+ a notification action may not launch an activity through a trampoline, so
 * it has to name the activity directly. Declining has nothing to show: routing it through an
 * activity would raise the app over whatever the user was doing in order to close a notification.
 *
 * ⛔ AND EVERY REQUEST CODE IS DISTINCT. `PendingIntent` equality ignores extras, so two intents to
 * the same component with the same action and the same flags are THE SAME pending intent — the
 * second `getActivity` call returns the first one's payload. Tapping the body of an incoming-call
 * notification would then answer it. The codes below are what keeps them apart. ⚠️ A message tap
 * has no fixed code here: the same rule applies ACROSS messages, so each one uses its own
 * notification id (see `AndroidPushNotifier.showMessage`), which sits above all of these.
 */
internal object PushIntents {

    /**
     * The workspace a notification is about.
     *
     * ⚠️ CARRIED EVEN WHEN IT IS THE ACTIVE ONE. A push can arrive for any workspace the account
     * belongs to, and the app's selected workspace is client state that need not match — so the
     * consumer compares rather than assumes. See `PushDeepLink`.
     */
    const val EXTRA_WORKSPACE_ID: String = "com.distronode.districtai.push.WORKSPACE_ID"

    /** Present on a message notification's tap intent. Its presence is what selects the inbox. */
    const val EXTRA_MESSAGE_ID: String = "com.distronode.districtai.push.MESSAGE_ID"

    /** The ringing call. Present on all three incoming-call intents. */
    const val EXTRA_CALL_ID: String = "com.distronode.districtai.push.CALL_ID"

    /**
     * ⛔ THE DIFFERENCE BETWEEN "SHOW ME THE RINGING CALL" AND "ANSWER IT". Both open the activity
     * with the same two ids, and without this flag the body tap and the Answer button would be
     * indistinguishable to the receiving side — so tapping to SEE who is calling would join the
     * conversation. Its absence is the safe default: an intent that somehow lost its extras opens
     * the ringing screen rather than answering.
     */
    const val EXTRA_ANSWER: String = "com.distronode.districtai.push.ANSWER"

    /** Broadcast action for the Decline button. ⚠️ Handled by [IncomingCallActionReceiver]. */
    const val ACTION_DECLINE: String = "com.distronode.districtai.push.DECLINE"

    /**
     * Open the inbox for the workspace a message arrived in.
     *
     * ⛔ `FLAG_ACTIVITY_SINGLE_TOP` AND NOT `CLEAR_TASK`. The activity is `launchMode="singleTask"`
     * for the PKCE callback's sake, so a running instance receives this through `onNewIntent` and
     * keeps its navigation back stack; clearing the task would throw away whatever the user was
     * doing in order to show them a message.
     */
    fun inbox(context: Context, workspaceId: String, messageId: String): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            .putExtra(EXTRA_MESSAGE_ID, messageId)

    /**
     * Bring the ringing call to the screen WITHOUT answering it.
     *
     * ⚠️ This is the body tap. See [EXTRA_ANSWER] for why it is a different intent from the Answer
     * button rather than the same one.
     */
    fun showIncomingCall(context: Context, workspaceId: String, callId: String): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_WORKSPACE_ID, workspaceId)
            .putExtra(EXTRA_CALL_ID, callId)

    /** The Answer button. ⛔ The only intent in this file that carries [EXTRA_ANSWER]. */
    fun answerCall(context: Context, workspaceId: String, callId: String): Intent =
        showIncomingCall(context, workspaceId, callId).putExtra(EXTRA_ANSWER, true)

    /**
     * The Decline button.
     *
     * ⛔ IT NAMES THE RECEIVER'S COMPONENT EXPLICITLY RATHER THAN RELYING ON THE ACTION STRING. An
     * implicit broadcast with a custom action would be delivered to any app that registered for it,
     * and it would not be delivered to a manifest-registered receiver at all on API 26+ — so the
     * feature would fail in the ordinary case and leak the call id in the exotic one.
     */
    fun declineCall(context: Context, callId: String): Intent =
        Intent(context, IncomingCallActionReceiver::class.java)
            .setAction(ACTION_DECLINE)
            .putExtra(EXTRA_CALL_ID, callId)

    /** ⚠️ See the ⛔ on the object: distinct codes are what stop two actions collapsing into one. */
    const val REQUEST_SHOW_CALL: Int = 2
    const val REQUEST_ANSWER_CALL: Int = 3
    const val REQUEST_DECLINE_CALL: Int = 4

    /**
     * ⚠️ `FLAG_UPDATE_CURRENT` SO A SECOND CALL REPLACES THE FIRST'S EXTRAS. Without it, a pending
     * intent created for an earlier call id is reused wholesale and the Answer button answers the
     * PREVIOUS call — the same equality-ignores-extras rule the request codes work around, applied
     * across time instead of across actions.
     */
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun activity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent, FLAGS)

    fun broadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode, intent, FLAGS)
}
