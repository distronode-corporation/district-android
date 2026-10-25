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
     *
     * @property interrupt the server's "needs you" flag (`priority: "interrupt"`): an urgent message,
     *   or one from a VIP contact. It picks the high-importance channel and nothing else.
     */
    data class Message(
        val workspaceId: String,
        val messageId: String,
        val interrupt: Boolean = false,
    ) : PushEvent

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

    /**
     * A ring this device may still be sounding is over, for the [reason] the server gives.
     *
     * ⛔ SENT ON EVERY END OF A RING, TO THE SAME DEVICES THE RING REACHED, INCLUDING THE ONE THAT
     * ANSWERED. So it only means anything to a device that is still RINGING that call: the device
     * that answered, and a device that never rang it, ignore it (see `IncomingCallController`).
     *
     * ⚠️ IDENTIFIED BY THE CALL ALONE. `workspaceId` is in the payload but is not needed to match:
     * a call id names one call, and a cancellation that matches nothing is a no-op, so requiring the
     * workspace would only turn a server-side slip into a phone that rings on for thirty seconds.
     */
    data class RingCancelled(val callId: String, val reason: RingCancelReason) : PushEvent
}

/**
 * Why the server withdrew a ring (`docs/ring_cancel_contract.md` in the server repository).
 *
 * ⚠️ ANYTHING ELSE, OR NOTHING, READS AS [WITHDRAWN], as the contract says, and never fails the
 * decode: a client older than a new reason must still stop ringing.
 */
internal enum class RingCancelReason(val wire: String) {
    /** Another device (or another person) took the call. */
    ANSWERED("answered"),

    /** Nobody answered within the server's rendezvous. */
    TIMEOUT("timeout"),

    /** The caller hung up while it rang. */
    CALL_ENDED("call_ended"),

    /**
     * This person declined on another of their devices, so the ring stops here too.
     *
     * ⚠️ NOT A MISSED CALL: the person saw it and said no. Sent only to the decliner's own devices;
     * a client older than this reason reads it as [WITHDRAWN], which also stops the ring.
     */
    DECLINED("declined"),

    /** The server stopped ringing app devices for any other reason. */
    WITHDRAWN("withdrawn"),
    ;

    internal companion object {
        fun fromWire(value: String?): RingCancelReason = entries.firstOrNull { it.wire == value } ?: WITHDRAWN
    }
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

    /** ⚠️ These six names are the server's push-payload keys, verbatim. */
    const val KEY_TYPE: String = "type"
    const val KEY_WORKSPACE_ID: String = "workspaceId"
    const val KEY_MESSAGE_ID: String = "messageId"
    const val KEY_CALL_ID: String = "callId"
    const val KEY_REASON: String = "reason"

    /**
     * ⚠️ OPTIONAL, AND ITS ONE VALUE IS [PRIORITY_INTERRUPT]. The server sends the key only when the
     * "needs you" rule matches; every other payload is byte-identical to one without it. Any other
     * value is read as absent, so a future value cannot promote a message by accident.
     */
    const val KEY_PRIORITY: String = "priority"
    const val PRIORITY_INTERRUPT: String = "interrupt"

    const val TYPE_MESSAGE: String = "message"
    const val TYPE_INCOMING_CALL: String = "incoming_call"
    const val TYPE_RING_CANCELLED: String = "call_ring_cancelled"

    /**
     * @return the event, or null for anything this build cannot act on.
     *
     * ⚠️ VALUES ARE TRIMMED AND BLANKS TREATED AS ABSENT, because FCM's `data` has exactly one
     * type — string — so "no id" and "an empty id" are the same wire shape and a whitespace-only
     * value is what a mis-templated sender produces.
     */
    fun parse(data: Map<String, String>): PushEvent? {
        // ⚠️ BEFORE THE WORKSPACE CHECK: a cancellation needs only the call id. See
        // [PushEvent.RingCancelled].
        if (data.value(KEY_TYPE) == TYPE_RING_CANCELLED) {
            return data.value(KEY_CALL_ID)?.let {
                PushEvent.RingCancelled(callId = it, reason = RingCancelReason.fromWire(data.value(KEY_REASON)))
            }
        }
        val workspaceId = data.value(KEY_WORKSPACE_ID) ?: return null
        return when (data.value(KEY_TYPE)) {
            TYPE_MESSAGE -> data.value(KEY_MESSAGE_ID)
                ?.let {
                    PushEvent.Message(
                        workspaceId = workspaceId,
                        messageId = it,
                        interrupt = data.value(KEY_PRIORITY) == PRIORITY_INTERRUPT,
                    )
                }
            TYPE_INCOMING_CALL -> data.value(KEY_CALL_ID)
                ?.let { PushEvent.IncomingCall(workspaceId = workspaceId, callId = it) }
            // ⛔ Includes a null type. See the ⛔ on the object: silence is forward compatibility.
            else -> null
        }
    }

    private fun Map<String, String>.value(key: String): String? =
        this[key]?.trim()?.takeIf { it.isNotEmpty() }
}
