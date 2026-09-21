package com.distronode.districtai.push

/**
 * What an Activity should do about the intent a notification just delivered.
 *
 * ⛔ SEPARATED FROM `MainActivity` SO IT CAN BE TESTED AT ALL. Everything about a notification tap
 * that can be WRONG is a decision over four nullable extras — is this a message or a call, did the
 * user press Answer or merely tap the body, is there enough here to act on — and none of it needs an
 * Activity, an Intent or a device. What is left in the Activity is reading four extras and calling
 * one method, which is the part no test could add value to.
 *
 * ⛔ THE ANSWER FLAG IS CHECKED AGAINST A CALL ID AND NOT ON ITS OWN. An intent carrying
 * `EXTRA_ANSWER` and no call id is not a request to answer "whatever is ringing" — it is a
 * malformed intent, and treating it as an answer would let a stale `PendingIntent` from a previous
 * call join the current one.
 */
internal sealed interface PushIntentAction {

    /**
     * A message notification was tapped.
     *
     * ⚠️ IT CARRIES THE MESSAGE ID AS WELL AS THE WORKSPACE. The id is what
     * `GET /api/district/messages/[id]` exchanges for the thread, so it has to survive this far for
     * the tap to open the conversation. See [InboxDeepLink].
     */
    data class OpenInbox(val workspaceId: String, val messageId: String) : PushIntentAction

    /**
     * The Answer button was pressed.
     *
     * ⛔ IT CARRIES NO IDS, DELIBERATELY. There is at most one live inbound call by construction —
     * one Telecom connection, one engine, one audio focus — so "answer the ringing call" is
     * unambiguous, and an answer that matched on an id would be one stale `PendingIntent` away from
     * doing nothing at all, silently, with the ring still on screen.
     */
    data object AnswerCall : PushIntentAction

    /**
     * The body of a ringing notification was tapped: show the call, do not answer it.
     *
     * ⛔ A SEPARATE CASE FROM [AnswerCall] AND NOT A WEAKER VERSION OF IT. Without the distinction,
     * tapping a notification to SEE who is calling would join the conversation. It carries no work
     * for the Activity beyond existing, because the ringing screen is drawn from the controller's
     * state and is already on screen by the time this is read.
     */
    data object ShowCall : PushIntentAction
}

/**
 * @param answer the value of `PushIntents.EXTRA_ANSWER`. ⚠️ Absent defaults to false, which is the
 *   safe direction: an intent that somehow lost its extras shows the ringing screen rather than
 *   answering the call.
 * @return null when there is nothing here from this app's notifications — the ordinary case, since
 *   every launcher tap and every PKCE callback also arrives as an intent.
 */
internal fun pushIntentAction(
    workspaceId: String?,
    messageId: String?,
    callId: String?,
    answer: Boolean,
): PushIntentAction? = when {
    // ⛔ THE CALL BRANCH IS TESTED FIRST. Both notification families carry a workspace id, and only
    // the call family carries a call id — so ordering the message branch first would make a ringing
    // call open the inbox on any build that ever adds a message id to a call intent.
    !callId.isNullOrBlank() ->
        if (answer) PushIntentAction.AnswerCall else PushIntentAction.ShowCall
    !workspaceId.isNullOrBlank() && !messageId.isNullOrBlank() ->
        PushIntentAction.OpenInbox(workspaceId, messageId)
    else -> null
}
