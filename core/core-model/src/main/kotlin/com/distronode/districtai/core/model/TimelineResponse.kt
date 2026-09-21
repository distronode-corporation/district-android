package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/timeline?workspaceId=&contactId=` (or `&phoneNumber=`) — one thread's history.
 *
 * ⛔ THIS IS A CONTACT'S WHOLE TIMELINE, NOT A MESSAGE LIST. It interleaves SMS, email and CALLS,
 * which is the point: an operator reading a thread needs to see that the customer phoned between
 * two texts. A client that filtered to messages only would silently drop the call that explains
 * the gap.
 *
 * ⚠️ PAGED, EXPAND-ONLY, AND ON A CURSOR RATHER THAN AN OFFSET. The server returns the NEWEST
 * window (50 rows per source, messages and calls windowed separately) and [pageInfo] says where
 * that window ended. To reach further back, send [TimelinePageInfo.oldest] as `before` and
 * [TimelinePageInfo.oldestId] as `beforeId` — the pair the previous response handed back, never a
 * timestamp the client invented. There is no `after`: a thread opens on the newest window and only
 * ever expands backwards, and new events arrive by re-reading it.
 *
 * ⛔ `beforeId` WITHOUT `before` IS A 400, NOT A DEFAULT. An id alone cannot say which timestamp it
 * breaks a tie at, so the route refuses it rather than silently serving the newest window forever
 * while the client believes it is walking backwards. The two travel together — see
 * `com.distronode.districtai.core.data.ThreadCursor`, which is why they cannot be separated here.
 *
 * ⛔ PAGES MAY OVERLAP, BY CONTRACT, SO THE CLIENT MUST DEDUPE BY [TimelineEvent.id]. The two
 * sources are windowed independently and merged afterwards, so one cursor point can sit inside one
 * source's window and past the other's; sending it back re-reads a handful of rows the client
 * already holds from the less dense source. The server documents this as the deliberate trade
 * against a per-source cursor pair — a duplicate a `Set` removes is cheaper than a wider contract.
 */
@Serializable
data class TimelineResponse(
    val success: Boolean = false,
    val timeline: List<TimelineEvent> = emptyList(),
    /**
     * Where this window ended.
     *
     * ⚠️ DEFAULTED TO AN EMPTY [TimelinePageInfo] RATHER THAN LEFT REQUIRED, matching every other
     * additive nested field in this module (`AnalyticsResponse.metrics`, `OverviewResponse.metrics`,
     * `ConversationSummary.lastMessage`). A deployment older than the paging change omits the key
     * entirely, and a required field would turn that into a decode failure — an empty thread on
     * screen — rather than a thread with no page behind it, which is exactly what such a server has.
     * The defaults say precisely that: `hasMore = false`, no cursor.
     */
    val pageInfo: TimelinePageInfo = TimelinePageInfo(),
)

/**
 * The cursor the next (older) page is fetched with, and whether there is one to fetch.
 *
 * ⛔ [hasMore] MEANS "AT LEAST ONE SOURCE FILLED ITS WINDOW", NOT "THERE ARE DEFINITELY MORE
 * EVENTS". A source with exactly 50 rows left reports true and the following page comes back
 * empty. That is the server's deliberate choice of which way to be wrong — taking 51 rows and
 * discarding one would cost a row on every thread open to save one empty request at the very end —
 * so a client must treat an empty older page as the end of the thread rather than as a fault.
 *
 * ⚠️ [oldest]/[oldestId] NAME THE OLDEST EVENT OF THE MERGED PAGE, not the oldest row of either
 * source, and they are null only on an empty page. They are echoed back verbatim; parsing or
 * re-formatting the timestamp locally would hand the server a value it never emitted.
 */
@Serializable
data class TimelinePageInfo(
    val hasMore: Boolean = false,
    /** ISO-8601, straight from the server. Sent back as `before`. */
    val oldest: String? = null,
    /** The id of that same event, breaking ties among rows sharing its timestamp. Sent back as `beforeId`. */
    val oldestId: String? = null,
)

/**
 * One event in a contact's history.
 *
 * ⚠️ `direction` INCLUDES `missed`, which is neither inbound nor outbound. A two-state boolean
 * (`isInbound`) would have to put a missed call on one side of the conversation or the other, and
 * both are wrong — a missed call is an event, not a message from anybody.
 */
@Serializable
data class TimelineEvent(
    val id: String = "",
    /** `sms` | `call` | `email` | `whatsapp`. */
    val type: String = "",
    /**
     * ISO-8601 from the server.
     *
     * ⚠️ NOT REFORMATTED ON THE CLIENT, for the same reason the call log's `time` is not: the
     * server renders in the OPERATOR's timezone, and re-rendering in the device's would make the
     * app disagree with the browser for anyone travelling or on a device set to another zone.
     */
    val timestamp: String = "",
    /** `inbound` | `outbound` | `missed`. See the ⚠️ on the class. */
    val direction: String = "",
    val body: String = "",
    val status: String = "",
    /** Email only. */
    val subject: String? = null,
    /** MMS/email attachments. Absent on a plain SMS. */
    val mediaUrls: List<String> = emptyList(),
    /** Call only, in seconds. */
    val duration: Int? = null,
    /** The voice agent's post-call summary, when one exists. */
    val summary: String? = null,
    val hasTranscript: Boolean = false,
    val transcript: String? = null,
) {
    /** A message the operator can read as conversation, as opposed to a call event. */
    val isMessage: Boolean get() = type != CALL

    /** True for a call that was never answered — rendered as an event, not as a bubble. */
    val isMissedCall: Boolean get() = type == CALL && direction == MISSED

    private companion object {
        const val CALL = "call"
        const val MISSED = "missed"
    }
}

/**
 * `POST /api/district/messages/send`.
 *
 * ⛔ [channel] HAS NO DEFAULT HERE, AND THAT IS A BUG FIX. It was declared `= "sms"`, and this
 * KDoc claimed the value was "sent explicitly" — which was FALSE. kotlinx.serialization omits
 * default-valued properties unless `encodeDefaults` is on, so the field never reached the wire and
 * the server applied its OWN default instead. Harmless only because the two defaults happen to
 * agree today; a server-side change would have silently redirected every reply to another channel,
 * and nothing would have reported it. Caught by `InboxRequestBodyTest`, which asserts the encoded
 * body rather than the object.
 *
 * ⚠️ Fixed by REMOVING the default rather than by annotating it `@EncodeDefault` or flipping
 * `encodeDefaults` on the shared body encoder. Every real caller already passes a channel
 * ([com.distronode.districtai.core.data.InboxRepository.send] requires one), so the default was
 * only ever a way for a value to go missing; and flipping the encoder would change the shape of
 * every other request body this client builds.
 *
 * A thread carrying both channels has no obvious default anyway — letting the server pick means the
 * operator's choice of reply channel is decided by something they cannot see.
 */
@Serializable
data class SendMessageRequest(
    val workspaceId: String,
    val to: String,
    val body: String,
    /** `sms` | `email`. ⛔ No default — see the class doc. */
    val channel: String,
    /**
     * Email only; ignored for SMS.
     *
     * ⚠️ OMITTED, not null, when absent: the client's body encoder sets `explicitNulls = false`.
     * Asserted in `InboxRequestBodyTest` because these routes read their fields off a bare
     * `req.json()` with no schema, so a field that arrives in the wrong form is not rejected.
     */
    val subject: String? = null,
    /**
     * MMS attachments: up to five absolute https URLs, each one a [MediaUploadResponse]'s
     * `media.url`.
     *
     * ⛔ [body] IS STILL REQUIRED WHEN THIS IS SET. The route's first guard is
     * `if (!workspaceId || !to || !body)`, which does not know about attachments — so a
     * media-only message with an empty body is a 400 "Missing required parameters", not an
     * image with no caption. The composer therefore refuses to send blank text even with a
     * picture attached, and says so locally rather than spending a round trip to be told.
     *
     * ⛔ REJECTED BY THE SERVER ON TWO CHANNELS THE CLIENT CAN ONLY PARTLY SEE. WhatsApp is
     * refused outright ("WhatsApp sends are text-only for now"), and so is any workspace whose
     * gateway resolves to SINCH — which is decided server-side at send time from the workspace's
     * messaging config and is NOT knowable here. So attachment failures on a Sinch workspace
     * surface as the server's own refusal text rather than being prevented; only the channel
     * gate is enforceable client-side. See `ui.inbox.ThreadViewModel`.
     *
     * ⚠️ DEFAULTED TO EMPTY, AND kotlinx OMITS A DEFAULT — which is correct here and was NOT
     * correct for [channel]. `sanitizeMediaUrls` maps an absent value to `[]`, so omission and
     * an empty array mean the same thing to this route. [channel] was the opposite case: the
     * server had its own default that could silently drift away from the client's.
     */
    val mediaUrls: List<String> = emptyList(),
)

@Serializable
data class SendMessageResponse(
    val success: Boolean = false,
    val message: SentMessage? = null,
    /**
     * The server's own refusal text.
     *
     * ⚠️ Worth surfacing verbatim rather than replacing: this endpoint spends real money and its
     * refusals are specific — an unverified sender, an exhausted A2P registration, a per-workspace
     * rate limit of 30/min. "Could not send" would throw all of that away.
     */
    val error: String? = null,
)

/**
 * The message row the send endpoint echoes back.
 *
 * ⛔ THE WHOLE ROW, NOT A RECEIPT, AND THIS TYPE USED TO GET THAT WRONG. It modelled five fields —
 * id, messageSid, from, to, body — out of the thirteen the server sends, so [status] was being
 * thrown away. That is the only field distinguishing "accepted by the carrier and still queued"
 * from "delivered", i.e. the difference between a reply that went out and one that is about to
 * fail. Caught by pinning `district-message-send.json`, not by anything in the app.
 *
 * ⚠️ ONE ENDPOINT, TWO SHAPES, WHICH IS WHY SO MANY FIELDS ARE NULLABLE. The SMS branch sends
 * [externalId] and [accountId] and no [subject]; the email branch sends [subject] and neither of
 * the other two. Both fixtures are committed for exactly this reason — a single fixture would have
 * made one branch's fields look mandatory.
 */
@Serializable
data class SentMessage(
    val id: String = "",
    /** The provider's own id for the message: a Twilio SID, or a Postmark message id. */
    val messageSid: String? = null,
    val workspaceId: String = "",
    /**
     * The workspace's sending identity — its phone number, or its verified email sender.
     *
     * ⚠️ NOT THE RECIPIENT. This row is outbound, so the customer is [to]. A client that labelled
     * the bubble with `from` would attribute the operator's own reply to itself.
     */
    val from: String = "",
    val to: String = "",
    val body: String = "",
    /** Email only; absent on SMS. */
    val subject: String? = null,
    /** Always `outbound` here, but modelled rather than assumed. */
    val direction: String = "",
    /** `sms` | `email` | `whatsapp`. */
    val type: String = "",
    /**
     * The PROVIDER's word for where the message got to — `queued` from Twilio, `sent` from
     * Postmark.
     *
     * ⚠️ Not normalised, and not replaced with a boolean. The two channels genuinely report
     * different values for the same successful send, so neither is a constant the client may
     * assume, and collapsing them would discard the distinction between queued and delivered.
     */
    val status: String = "",
    /** ISO-8601, server-stamped. */
    val createdAt: String = "",
    /** SMS only: the provider's external id. Absent on the email branch. */
    val externalId: String? = null,
    /** `twilio` | `telnyx` | `sinch` | `postmark`. */
    val provider: String = "",
    /** SMS only: which of the workspace's gateway accounts sent it. Absent on the email branch. */
    val accountId: String? = null,
)

/**
 * `POST /api/district/messages/mark-read`.
 *
 * ⚠️ Takes a `contactId` OR a `counterpart`, matching the server, because a thread with no Contact
 * row has only an address. Sending both is harmless; sending neither is a 400.
 */
@Serializable
data class MarkReadRequest(
    val workspaceId: String,
    val contactId: String? = null,
    val counterpart: String? = null,
)

@Serializable
data class MarkReadResponse(
    val success: Boolean = false,
    val marked: Int = 0,
)
