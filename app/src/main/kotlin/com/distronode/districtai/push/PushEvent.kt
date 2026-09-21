package com.distronode.districtai.push

/**
 * What a push payload turned out to mean.
 *
 * ⛔ THE PAYLOAD CARRIES IDENTIFIERS AND NOTHING ELSE, WHICH IS A SERVER-SIDE PRIVACY DECISION THIS
 * TYPE EXISTS TO MAKE STRUCTURAL. A push notification is readable by the operating system and by any
 * installed notification-listener app, so there is no message body here, no phone number, no email
 * address and (the expensive one) no LiveKit token. The server's push sender documents the same
 * rule, and the shape of this type is what stops a client author from "conveniently" reading a
 * field that must never be sent. If a field like that ever appears in a payload, adding it here is
 * the wrong fix.
 *
 * ⛔ SO EVERY BRANCH BELOW ENDS IN A FETCH UNDER THIS APP'S OWN BEARER. That is the only point in
 * the chain where a request is attributable to a PERSON rather than to a handset, and it is why an
 * incoming call is answered by calling `calls/{id}/answer` rather than by joining a room the push
 * described.
 */
internal sealed interface PushEvent {

    /**
     * A message arrived in a workspace's inbox.
     *
     * ⚠️ THE ID IS NOT A THREAD KEY. `messageId` names one row, and the inbox groups rows into
     * threads server-side over a bounded window, so there is no client-side way to turn this into a
     * thread without a request. The notification therefore deep-links to the workspace's INBOX
     * rather than to a thread — see `PushIntents`.
     */
    data class Message(val workspaceId: String, val messageId: String) : PushEvent

    /**
     * A call is ringing and this workspace's devices are being asked to take it.
     *
     * ⛔ THE SERVER IS BLOCKING ON THIS. `actions/ring-app` pushes and then polls a Redis rendezvous
     * for ~25 seconds; the agent's transfer is held open for that window and falls back to PSTN when
     * it expires. So the cost of mishandling this event is a caller sitting in silence, and the cost
     * of handling it EARLY — answering before a human pressed anything — is a caller handed to
     * nobody. Nothing may call the answer route on the strength of this event alone.
     */
    data class IncomingCall(val workspaceId: String, val callId: String) : PushEvent
}

/**
 * The one place a raw FCM `data` map becomes a [PushEvent].
 *
 * ⛔ PURE, AND THAT IS WHAT MAKES IT THE ONLY PART OF THE PUSH PATH THAT CAN BE TESTED ON THIS
 * MACHINE AT ALL. Waydroid is API 33 with no Play Services, so delivery, Doze wake and token
 * acquisition are all real-device work; a `Map<String, String>` in and a sealed type out is
 * exercisable here, and it is where every hostile-payload decision lives.
 *
 * ⛔ UNKNOWN TYPES ARE DROPPED SILENTLY AND MUST STAY THAT WAY. The server is free to add a push
 * type before this build is on every handset — that is the ordinary state of a shipped app — so an
 * unrecognised `type` is forward compatibility rather than an error, and a client that surfaced it
 * would show users a notification about a feature they do not have. It is the same "lenient in the
 * field" rule the API parser follows.
 *
 * ⛔ AND A MISSING ID IS A DROP RATHER THAN A DEFAULT. Every field of an FCM data payload is a
 * string the sender chose, and this map is the least trustworthy input in the app: it arrives
 * unauthenticated from the OS's perspective, and a malformed one is indistinguishable from a
 * malicious one. A blank `workspaceId` would produce a notification about nothing, and a blank
 * `callId` would produce a ringing screen whose Answer button could only ever 404.
 */
internal object PushPayload {

    /** ⚠️ These four names are the server's push-payload keys, verbatim. */
    const val KEY_TYPE: String = "type"
    const val KEY_WORKSPACE_ID: String = "workspaceId"
    const val KEY_MESSAGE_ID: String = "messageId"
    const val KEY_CALL_ID: String = "callId"

    const val TYPE_MESSAGE: String = "message"
    const val TYPE_INCOMING_CALL: String = "incoming_call"

    /**
     * @return the event, or null for anything this build cannot act on.
     *
     * ⚠️ VALUES ARE TRIMMED AND BLANKS TREATED AS ABSENT, because FCM's `data` has exactly one
     * type — string — so "no id" and "an empty id" are the same wire shape and a whitespace-only
     * value is what a mis-templated sender produces.
     */
    fun parse(data: Map<String, String>): PushEvent? {
        val workspaceId = data.value(KEY_WORKSPACE_ID) ?: return null
        return when (data.value(KEY_TYPE)) {
            TYPE_MESSAGE -> data.value(KEY_MESSAGE_ID)
                ?.let { PushEvent.Message(workspaceId = workspaceId, messageId = it) }
            TYPE_INCOMING_CALL -> data.value(KEY_CALL_ID)
                ?.let { PushEvent.IncomingCall(workspaceId = workspaceId, callId = it) }
            // ⛔ Includes a null type. See the ⛔ on the object: silence is forward compatibility.
            else -> null
        }
    }

    private fun Map<String, String>.value(key: String): String? =
        this[key]?.trim()?.takeIf { it.isNotEmpty() }
}
