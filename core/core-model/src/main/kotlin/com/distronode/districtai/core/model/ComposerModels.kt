package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * Everything the reply composer needs beyond the send itself: an attachment upload, the
 * server-persisted unsent draft, and the AI reply generator.
 *
 * ⛔ THREE ROUTES, THREE VERY DIFFERENT COST AND SAFETY PROFILES, AND CONFLATING THEM IS THE
 * MISTAKE THIS FILE EXISTS TO PREVENT:
 *   - `messages/media` writes BYTES INTO POSTGRES and hands back an ANONYMOUS capability URL.
 *     Anyone holding that URL can fetch the image without a session — that is deliberate,
 *     because a carrier's MMS fetcher has no session either, and it is why the id is a uuid
 *     rather than a guessable sequence.
 *   - `messages/drafts` is cheap, idempotent and per-AUTHOR. It is the only one of the three
 *     safe to call on a timer.
 *   - `messages/draft` (SINGULAR) is a BILLED VERTEX GENERATION per request. See
 *     [AiDraftRequest].
 *
 * ⚠️ `drafts` AND `draft` ARE DIFFERENT ROUTES WHOSE NAMES DIFFER BY ONE LETTER. One persists
 * what the operator typed; the other invents text and costs money. Pointing either at the
 * other's path is a one-character mistake with a very asymmetric blast radius, which is why
 * `DistrictPaths` gives them separate constants with the same warning.
 */

/**
 * `POST /api/district/messages/media` — the response to a multipart attachment upload.
 *
 * ⚠️ THE ENVELOPE IS NOT THE MEDIA. The server answers `{success, media:{...}}` and the useful
 * value is [media].url, which is the only thing `messages/send` accepts.
 */
@Serializable
data class MediaUploadResponse(
    val success: Boolean = false,
    val media: UploadedMedia? = null,
    /**
     * The server's own refusal, carried verbatim. Its 400s name the actual rule that was broken
     * ("Only JPEG, PNG, GIF, or WebP images can be attached to MMS", "Attachments must be
     * between 1 byte and 5MB"), which is more useful than a generic "upload failed".
     */
    val error: String? = null,
)

/**
 * One stored attachment.
 *
 * ⛔ [url] IS AN ANONYMOUS CAPABILITY URL AND MUST NOT BE FETCHED WITH THE BEARER TOKEN. It is
 * `/api/media/<uuid>` and answers to anyone, because carriers fetch it to deliver the MMS.
 * Attaching this client's `Authorization` header to it would send an access token to a route
 * that does not need one, which is a credential-leak surface for no benefit.
 *
 * ⚠️ [sizeBytes] IS THE SERVER'S COUNT, NOT THE CLIENT'S. They should agree; if they ever do
 * not, the server's is the one the carrier will be billed on.
 */
@Serializable
data class UploadedMedia(
    val id: String = "",
    /** `image/jpeg` | `image/png` | `image/gif` | `image/webp`. The server allows nothing else. */
    val mimeType: String = "",
    val sizeBytes: Int = 0,
    /** Absolute https URL, built server-side from config rather than from a Host header. */
    val url: String = "",
)

/**
 * `GET /api/district/messages/drafts?workspaceId=&threadKey=` — one thread's unsent draft.
 *
 * ⛔ `draft: null` IS THE NORMAL ANSWER, NOT AN ERROR, AND THE SERVER CHOSE null OVER 404 FOR
 * EXACTLY THAT REASON. Almost every thread has no draft; a 404 would make the composer's
 * ordinary open path look like a fault in every log. A client that treated a null draft as a
 * failure would show an error on the common case.
 */
@Serializable
data class DraftResponse(
    val success: Boolean = false,
    val draft: MessageDraft? = null,
    val error: String? = null,
    /**
     * The server's machine-readable refusal code.
     *
     * ⛔ `empty_body` IS THE ONE THAT MATTERS AND IT MEANS "YOU SHOULD HAVE SENT DELETE". A PUT
     * whose body is blank is a 400, deliberately, because a blank draft is the ABSENCE of a
     * draft rather than an empty one. See [MessageDraft].
     */
    val code: String? = null,
)

/** `GET /api/district/messages/drafts?workspaceId=` — every draft this author has open. */
@Serializable
data class DraftListResponse(
    val success: Boolean = false,
    /**
     * Newest first, capped at 100 server-side.
     *
     * ⚠️ THE CAP IS NOT A PAGE. There is no cursor and no `total`: a workspace with more than
     * 100 open drafts has a client bug, not a paging need. Do not build a "load more" against it.
     */
    val drafts: List<MessageDraft> = emptyList(),
    val error: String? = null,
)

/**
 * One persisted, unsent reply.
 *
 * ⛔ AUTHOR-SCOPED, UNLIKE EVERYTHING ELSE IN THE INBOX. `Message.readAt` is workspace-level —
 * one agent opening a thread marks it read for the team — but a draft is unfinished thought, and
 * a colleague reading it reads it as a decision. The server keys the row by `authorEmail` as
 * well, so two agents hold their own draft on the same thread. This client never sends an author:
 * the session decides it, and a field for it would be a way to read someone else's.
 *
 * ⚠️ [mediaUrls] IS ALWAYS AN ARRAY ON THE WIRE, NEVER OMITTED AND NEVER NULL. The server
 * normalises its nullable Json column to `[]` precisely so a strict decoder never has to branch
 * on absent-versus-empty. The default here covers the shape, not an expected omission.
 */
@Serializable
data class MessageDraft(
    /** `contact:<id>` or `addr:<normalized>` — the same thread identity the Inbox list uses. */
    val threadKey: String = "",
    /**
     * ⛔ NEVER BLANK ON A ROW THAT EXISTS. The server refuses to store one (400 `empty_body`),
     * so "a draft exists" and "there is text to restore" are the same statement.
     */
    val body: String = "",
    /** Email threads only. */
    val subject: String? = null,
    val mediaUrls: List<String> = emptyList(),
    /** ISO-8601, server-stamped. Used to decide which of two devices typed last. */
    val updatedAt: String = "",
)

/**
 * `PUT /api/district/messages/drafts` — upsert on (workspace, thread, author).
 *
 * ⛔ NEVER SEND A BLANK [body]. The server answers 400 with `code: "empty_body"` and tells the
 * caller to DELETE instead. That refusal is deliberate rather than a silent redirect: a client
 * that clears the box should learn to send DELETE, because doing it for them hides the bug until
 * an offline queue replays the two writes out of order.
 *
 * ⚠️ PUT rather than POST because autosave has no notion of create-versus-update — the client
 * knows the thread and the text and wants the server to end up holding exactly that.
 */
@Serializable
data class DraftSaveRequest(
    val workspaceId: String,
    val threadKey: String,
    val body: String,
    /**
     * ⚠️ OMITTED, not null, when absent — the client's body encoder sets `explicitNulls = false`.
     * The server treats a blank subject as null anyway, so the two agree.
     */
    val subject: String? = null,
    /**
     * ⚠️ DEFAULTED TO EMPTY, AND kotlinx OMITS A DEFAULT VALUE, WHICH IS CORRECT HERE AND WAS NOT
     * CORRECT FOR [SendMessageRequest.channel]. An absent `mediaUrls` and an empty one mean the
     * SAME thing to this route (`sanitizeMediaUrls` maps null to `[]`), so omission changes
     * nothing. `channel` was the opposite case — the server had its own default that could drift
     * away from the client's — which is why that one has no default at all.
     */
    val mediaUrls: List<String> = emptyList(),
)

/**
 * `POST /api/district/messages/draft` (SINGULAR) — AI reply GENERATION, not persistence.
 *
 * ⛔ NON-IDEMPOTENT AND BILLABLE. Every call is one Vertex/Gemini generation against
 * `VERTEX_PROJECT_ID`'s billing account, capped server-side at 20/min per WORKSPACE. Same rule as
 * `messages/send` and `contacts/enrich`: nothing in this client may retry it automatically, and
 * the single 401 retry inside `DistrictApiClient.send` is the only re-send that may ever happen.
 *
 * ⚠️ `contactId` OR `phoneNumber`, at least one. `phoneNumber` is the server's historical name for
 * the address selector and now carries an email too — the same asymmetry the timeline route has.
 */
@Serializable
data class AiDraftRequest(
    val workspaceId: String,
    val contactId: String? = null,
    val phoneNumber: String? = null,
)

/**
 * The generated reply.
 *
 * ⚠️ [draft] IS A BARE STRING HERE, WHERE THE PERSISTENCE ROUTE'S `draft` IS AN OBJECT. Two
 * routes one letter apart use the same key name for two different types; that is the server's
 * shape and mirroring it is the only option. The fixtures for both are committed side by side so
 * the difference is visible rather than inferred.
 *
 * ⚠️ Can legitimately be empty: the model occasionally returns nothing and the route sends `""`
 * rather than failing. An empty generation must not blank a composer the operator has typed in.
 */
@Serializable
data class AiDraftResponse(
    val success: Boolean = false,
    val draft: String = "",
    val error: String? = null,
)

/** `DELETE /api/district/messages/drafts?workspaceId=&threadKey=` — idempotent. */
@Serializable
data class DraftDeleteResponse(
    val success: Boolean = false,
    val error: String? = null,
)
