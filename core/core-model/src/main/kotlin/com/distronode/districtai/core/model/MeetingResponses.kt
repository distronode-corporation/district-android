package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `POST /api/district/calls/token` for a `meet_` room.
 *
 * ⛔ THE PATH SAYS "calls" AND THE THING IT ANSWERS FOR HERE IS A ROOM. That route serves two
 * unrelated cases behind one body: when `roomName` starts with `meet_` or `video_` it is a
 * standalone room name, and otherwise it is a `Call.id` and the caller is a supervisor joining
 * someone else's live phone call. The name is historical and is not going to change; what matters
 * to this client is that only the first case is reachable from it.
 *
 * ⛔ [url] IS THE SERVER'S CHOICE OF MEDIA NODE AND MUST BE USED VERBATIM. The room only exists on
 * the deployment that created it, so a client that derived a URL from the workspace's region — or
 * from a constant — would join a bus that has never heard of the room. For a standalone room the
 * two answers agree today; for a call room they do not, because the SIP bridge and the outbound
 * trunks exist only on the US deployment, so an EU workspace's phone call lives in a US room.
 * Treating this field as advisory is how that becomes a silent failure rather than an error.
 *
 * ⛔ [guestInvite] AND [guestPath] ARE ABSENT, NOT NULL, FOR A VIEWER — and their absence is a
 * SECURITY decision rather than a shape quirk. `/api/meet/token` grants publish rights to whoever
 * presents a valid invite, and an invite is a transferable twelve-hour capability, so minting one
 * for a read-only seat would hand it a publish-capable route into the room it had just been
 * refused, and let it admit unauthenticated outsiders with publish rights too. The route spreads
 * the two keys in only for a non-viewer. `district-room-token.json` and
 * `district-room-token-viewer.json` pin both branches, which is required rather than thorough:
 * the contract decoder runs with `ignoreUnknownKeys = false`, so a DTO proven against one branch
 * is proven against half the responses this route produces.
 */
@Serializable
data class RoomTokenResponse(
    val success: Boolean = false,
    /**
     * The LiveKit access token, ~30 minutes.
     *
     * ⚠️ SHORT-LIVED BY DESIGN (`TOKEN_TTL = "30m"`), which is shorter than a long meeting. It
     * authorises the JOIN; an established connection is not re-checked against it, so this is not
     * a meeting length limit — but a rejoin after it expires needs a fresh one.
     */
    val token: String = "",
    /** ⛔ Used verbatim. See the class doc. */
    val url: String = "",
    /**
     * The room's end-to-end encryption key, present only for a room that HAS one.
     *
     * ⛔ ABSENT IS A REAL ANSWER AND MEANS "JOIN UNENCRYPTED", NOT "SOMETHING WENT WRONG". This
     * route serves two room families (see the class doc) and only the `meet_` one is encrypted:
     * a `call_` supervisor join has a SIP leg in it, and a carrier delivers a telephone call
     * unencrypted, so there is nothing an app-side key could protect there. A client that treated
     * the absence as an error would refuse to join exactly the rooms that work today.
     *
     * ⚠️ Encryption is a property of the ROOM, not of the seat, so a viewer receives the same key
     * as a publisher — unlike [guestInvite]/[guestPath], which really are role-dependent. It is
     * not a capability: holding it does not grant publish rights, and the LiveKit token still
     * decides what the holder may do.
     */
    val e2ee: E2eeInfo? = null,
    val guestInvite: GuestInvite? = null,
    /**
     * The path half of a shareable guest link, already URL-encoded and carrying `?e=` and `?s=`.
     *
     * ⛔ ASSEMBLED SERVER-SIDE AND NEVER BY A CLIENT. It binds the signature to the exact room
     * name, and a client that rebuilt it from [guestInvite] would be reimplementing the encoding
     * the signature was computed over. Join it onto the app's own origin and share that.
     */
    val guestPath: String? = null,
)

/**
 * A signed, expiring capability that lets an unauthenticated guest join one specific room.
 *
 * ⚠️ MEETING ROOM NAMES ARE LOW ENTROPY — they are human-typed and restricted to
 * `[a-zA-Z0-9-]` — so "knowing the room name" was never an acceptable authorization boundary.
 * This is what replaces it. Nothing here is secret to the person holding the link; it is secret
 * from everyone else, and it expires.
 */
@Serializable
data class GuestInvite(
    /** Unix seconds. The server mints twelve hours; a client only forwards it. */
    val exp: Long = 0,
    /** base64url HMAC over the room name and [exp]. */
    val sig: String = "",
)

/**
 * The shared symmetric key for one end-to-end encrypted room.
 *
 * ⛔ [key] IS A PASSPHRASE AND IS HANDED TO THE MEDIA SDK VERBATIM. IT MUST NEVER BE BASE64-DECODED.
 * It arrives as the base64 text of 32 random bytes, and it is tempting to read "base64" as an
 * instruction — it is not. Every LiveKit SDK UTF-8-encodes this string and runs PBKDF2 over those
 * ASCII bytes (salt `LKFrameEncryptionKey`) to derive the AES-GCM key: verified in the 2.28.0 AAR,
 * where `BaseKeyProvider.setSharedKey` calls `String.getBytes(Charsets.UTF_8)` and nothing else.
 * Decoding to 32 raw bytes on one platform selects a DIFFERENT derivation (HKDF) and therefore a
 * different AES key — and the failure mode is not an error. Both sides join, both sides publish,
 * and every track is undecryptable noise. The web client and the voice agent are bound by the same
 * rule and pass the same string.
 *
 * ⚠️ The key is derived per room by the server and by the agent independently, so it never rides
 * LiveKit signalling. It is still a secret in transit to this client: it lives only in the token
 * response, and it must not be logged or put in a push payload.
 */
@Serializable
data class E2eeInfo(
    /**
     * ⛔ Defaults to blank rather than being required, so a `{"e2ee":{}}` body decodes instead of
     * throwing — but a blank key is NOT usable and the caller must refuse to hand it to the SDK.
     * An empty passphrase derives a real AES key that nobody else derives, which would join the
     * room and then hear silence. [ActiveRoomViewModel] gates on `isNotBlank`.
     */
    val key: String = "",
)

/**
 * One row of `GET /api/district/meetings`.
 *
 * ⛔ THIS IS A PROJECTION, NOT THE MEETING ROW, AND THE TWO DIFFER BY MORE THAN OMISSION. The list
 * route RENAMES as it reshapes: `summary` is published as [summaryPreview] truncated to 220
 * characters, and the `participants` Json array is published as the integer [participantCount].
 * Neither original name appears on the wire here. A client that modelled this from
 * [MeetingDetail]'s shape would fail to decode every row.
 *
 * ⛔ [summaryPreview] IS NULL FOR EVERY MEETING THAT HAS NOT ENDED, WHICH IS THE ORDINARY CASE
 * RATHER THAN AN EDGE ONE. The voice agent's Companion writes the minutes when the room closes,
 * so a meeting in progress — including the one the user is sitting in — has no summary, no
 * [endedAt] and `durationSec: 0`. `district-meetings.json` carries one row of each shape so a
 * decoder that regressed to non-null is caught by the contract test rather than on a phone.
 *
 * ⚠️ IT IS A PREVIEW AND MUST NOT BE PRESENTED AS THE MINUTES. 220 characters is roughly two
 * sentences; the full text is on the detail route. A screen that rendered this as complete would
 * be truncating the deliverable without saying so.
 *
 * ⚠️ The timestamps are ISO-8601 STRINGS, not instants — `NextResponse.json` serialises a Prisma
 * `DateTime` through `JSON.stringify`. This module deliberately owns no date parsing, the same
 * call [CallSummary] makes.
 */
@Serializable
data class MeetingSummary(
    val id: String = "",
    /** ⚠️ The full `meet_<workspaceId>_<suffix>` name, not the human part of it. */
    val roomName: String = "",
    /** Null until somebody names the meeting; nothing generates one. */
    val title: String? = null,
    /** "in-progress" or "completed". Free text on the wire; the column has no enum. */
    val status: String = "",
    val startedAt: String? = null,
    /** ⚠️ Null for everything still running. */
    val endedAt: String? = null,
    val createdAt: String = "",
    /** ⚠️ Zero while a meeting is in progress — it is stamped at the end, not accumulated. */
    val durationSec: Int = 0,
    /** ⛔ Truncated to 220 characters, and null until the meeting ends. See the class doc. */
    val summaryPreview: String? = null,
    /** ⚠️ Zero when the `participants` column is null, which is every meeting that never ran. */
    val participantCount: Int = 0,
)

/**
 * `GET /api/district/meetings/[id]` — the WHOLE Prisma row, returned verbatim.
 *
 * ⛔ FOUR FIELDS HERE ARE NOT PUBLISHED BY THE LIST AT ALL: [roomSid], [transcript],
 * [actionItems] and [workspaceId]. That asymmetry is why this client carries two models rather
 * than treating the list as a subset — and it only runs in one direction, since the list's
 * `summaryPreview`/`participantCount` do not exist here either. Both fixtures are committed.
 *
 * ⚠️ [transcript] IS THE FULL CONVERSATION AND IS THE MOST SENSITIVE FIELD THIS APP DECODES. It
 * is ordered `Speaker: text` lines written by the Companion. Nothing about it is summarised or
 * redacted, so anywhere it is surfaced is a place a meeting's contents are surfaced.
 */
@Serializable
data class MeetingDetail(
    val id: String = "",
    /**
     * LiveKit's own `RM_...` session key.
     *
     * ⚠️ NULLABLE BECAUSE THE ROW CAN EXIST BEFORE THE SID DOES — the Companion creates the record
     * and LiveKit reports the sid separately. It is the canonical per-session key server-side and
     * means nothing to a person, so it is not something to display.
     */
    val roomSid: String? = null,
    val roomName: String = "",
    val workspaceId: String = "",
    val title: String? = null,
    val status: String = "",
    /** ⛔ The FULL minutes (markdown), not the list's 220-character preview. Null until the end. */
    val summary: String? = null,
    /** ⚠️ See the class doc: the complete conversation, unredacted. */
    val transcript: String? = null,
    /**
     * ⚠️ AN UNSTRUCTURED `Json?` COLUMN whose element shape belongs to the voice agent rather than
     * to this repo — documented as `[{text, owner?}]` and enforced by nothing. Modelled as opaque
     * JSON for the same reason `Contact.visualMemory` is: inventing a data class here would make
     * this client fail to decode the first time the agent adds a key.
     */
    val actionItems: JsonElement? = null,
    /** ⚠️ Opaque for the same reason as [actionItems]; documented as `[{identity, name}]`. */
    val participants: JsonElement? = null,
    val durationSec: Int = 0,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val createdAt: String = "",
)
