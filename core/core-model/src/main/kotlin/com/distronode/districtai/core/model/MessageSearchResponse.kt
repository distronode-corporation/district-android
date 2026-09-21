package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/messages/search?workspaceId=&q=` — full-content search across the whole
 * `Message` table for the workspace.
 *
 * ⛔ NOT THE CONVERSATION LIST FILTERED. That list scans a bounded window of recent messages (500)
 * and groups them; this route queries every row, body AND email subject, case-insensitive. A
 * client that filtered the loaded conversations instead would silently answer "no matches" for
 * messages the workspace definitely has.
 *
 * ⛔ THE SHORT-QUERY BRANCH OMITS `limit` ENTIRELY. A `q` shorter than two characters (after the
 * server's own trim) answers `{success, results: []}` with **no `limit` key at all**, which is why
 * [limit] is nullable. A non-nullable `Int` there is a decode failure waiting for the first person
 * who types one letter, and this client decodes strictly.
 *
 * ⚠️ ADMITS `viewer`, like the other Inbox reads and unlike every write on this surface.
 */
@Serializable
data class MessageSearchResponse(
    val success: Boolean = false,
    val results: List<MessageSearchHit> = emptyList(),
    /**
     * ⚠️ THIRTY, SERVER-CAPPED, AND THE CAP IS REPORTED RATHER THAN INFERRED. A full page means
     * older matches exist and are not shown; say so instead of drawing a truncated list as if it
     * were complete.
     */
    val limit: Int? = null,
)

/** One matching message, already resolved to the thread it opens. */
@Serializable
data class MessageSearchHit(
    val messageId: String = "",
    /**
     * ⚠️ THE SERVER'S DEPRECATED NORMALISED ADDRESS KEY, superseded by [threadKey] and kept on the
     * wire through one deploy for client skew. It is decoded because the decoder is strict, not
     * because anything should navigate on it.
     */
    val key: String = "",
    /**
     * ⛔ THE VALUE TO NAVIGATE ON. It is the SAME value the conversation list computes, which is
     * what lets a hit open the already-loaded conversation instead of forking a new one.
     */
    val threadKey: String = "",
    val counterpart: String = "",
    /** ⚠️ `phone` or `email` on the wire, carried as a STRING; this module owns no channel enum. */
    val kind: String = "",
    val contactId: String? = null,
    val contactName: String? = null,
    val contactEmail: String? = null,
    val body: String = "",
    /** ⚠️ Email only. null on every SMS hit, which is most of them. */
    val subject: String? = null,
    val direction: String = "",
    val type: String? = null,
    val createdAt: String = "",
)

/**
 * What to put on a search row.
 *
 * ⚠️ THE COUNTERPART IS THE FALLBACK, NOT A BLANK. A hit with no `Contact` row is an ordinary
 * result rather than a broken one, and the address is the only thing that identifies it.
 */
val MessageSearchHit.displayName: String
    get() = contactName?.takeIf { it.isNotBlank() } ?: counterpart

/**
 * ⚠️ MEASURED ON THE TRIMMED QUERY, because the server trims before it measures. A caller
 * mirroring this floor against the untrimmed value would send requests the route answers empty.
 */
const val MESSAGE_SEARCH_MIN_QUERY_LENGTH: Int = 2
