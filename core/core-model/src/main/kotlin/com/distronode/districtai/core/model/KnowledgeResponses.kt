package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * The workspace knowledge base: the documents the agent answers from, and WHERE it answers from.
 *
 * ⛔ TWO ROUTES, AND THE SECOND ONE IS A DATA-RESIDENCY CONTROL RATHER THAN A PREFERENCE.
 * `workspace/knowledge` is the document store; `workspace/knowledge-mode` chooses between
 * `internal` (retrieval inside the workspace's own region, over documents the workspace uploaded)
 * and `linked` (the question is sent to Atlassian, which composes the answer). The route's own
 * header says it: switching to `linked` "starts sending this workspace's questions to a third
 * party, which is a data-residency change and not a display preference".
 *
 * ⚠️ THE READS ADMIT `viewer` AND THE WRITES DO NOT — which is the opposite split from
 * `workspace/config`, whose READ excludes viewers too. Nothing here carries a staff phone number,
 * so a viewer may see the document list and the mode; only agency/client may change either.
 */

/**
 * `GET /api/district/workspace/knowledge` — the document list, newest first.
 *
 * ⚠️ `documents` DEFAULTS TO EMPTY rather than being nullable, because the route always emits the
 * array (`findMany` returns `[]` for a workspace with nothing uploaded). An absent key would be
 * contract drift, and the envelope check is what catches a structurally empty body.
 */
@Serializable
data class KnowledgeListResponse(
    val success: Boolean = false,
    val documents: List<KnowledgeDocument> = emptyList(),
)

/**
 * One uploaded document, as the list route selects it.
 *
 * ⛔ THE SAME TYPE DECODES THE CREATE RESPONSE, WHICH SELECTS ONE FEWER FIELD. `POST` returns
 * `{id, title, sourceType, status, chunkCount, createdAt}` — with **no `sourceUrl`** — while `GET`
 * includes it. Every field defaults, so the shorter shape decodes cleanly; a type that required
 * `sourceUrl` would throw on the response to a successful upload. Both shapes are pinned:
 * `district-knowledge.json` and `district-knowledge-create.json`.
 *
 * ⚠️ [status] IS FREE TEXT ON THE WIRE. The ingest route writes `"ready"` today, but the column is
 * a plain string and older rows carry other values, so this is displayed rather than switched on
 * exhaustively — an unrecognised status must render as itself, not as an error.
 *
 * ⚠️ [chunkCount] IS THE EMBEDDING COUNT, and it is what a Vertex bill is made of. Shown because
 * "this document became 400 chunks" is the only visible signal that an upload was larger than
 * intended.
 */
@Serializable
data class KnowledgeDocument(
    val id: String = "",
    val title: String? = null,
    val sourceType: String? = null,
    /** ⚠️ Null for a pasted document, and ABSENT in the create response. See the ⛔ on the class. */
    val sourceUrl: String? = null,
    val status: String? = null,
    val chunkCount: Int? = null,
    /** ⚠️ An ISO-8601 STRING. This module deliberately owns no date parsing. */
    val createdAt: String? = null,
)

/**
 * The body of `POST /api/district/workspace/knowledge`.
 *
 * ⛔ THIS IS THE ONE CALL IN THIS CLIENT THAT SPENDS VERTEX EMBEDDING BUDGET, AND THE CALLER SETS
 * THE SIZE OF THE BILL. The route chunks [content] and embeds every chunk in one request, so one
 * tap can be one embedding call or four hundred. It is rate limited at **20/min per workspace**
 * (not per user — the spend lands on the tenant), and that limiter is Redis-backed and FAIL-OPEN,
 * so a green response is not proof a cap held. Nothing here may retry automatically.
 *
 * ⚠️ [title] IS TRUNCATED TO 200 CHARACTERS SERVER-SIDE and [sourceType] defaults to `"text"` when
 * absent or non-string. Both are sent explicitly so the stored row matches what the form showed.
 *
 * ⚠️ A [content] that chunks to nothing is a **400 "Document has no usable text"**, not a
 * zero-chunk document — worth surfacing verbatim, because "it saved but is empty" would be wrong.
 */
@Serializable
data class KnowledgeCreateRequest(
    val workspaceId: String,
    val title: String,
    val content: String,
    val sourceType: String? = null,
    val sourceUrl: String? = null,
)

/** ⚠️ The create echoes the row it made, unlike every workspace-settings save on this surface. */
@Serializable
data class KnowledgeCreateResponse(
    val success: Boolean = false,
    val document: KnowledgeDocument? = null,
)

/**
 * `DELETE /api/district/workspace/knowledge` — chunks cascade.
 *
 * ⛔ ANSWERS `{success:true}` EVEN WHEN NOTHING MATCHED. The route runs `deleteMany` scoped to
 * `{id, workspaceId}` and never reads the count, so "deleted" and "was not yours" are the same
 * response. That is deliberate cross-tenant hygiene, and it means a client must re-read the list
 * rather than believe the row is gone.
 */
@Serializable
data class KnowledgeDeleteResponse(val success: Boolean = false)

/**
 * `GET`/`PATCH /api/district/workspace/knowledge-mode`.
 *
 * ⛔ THE ROUTE SPREADS THE CONFIG INTO THE ENVELOPE (`{success:true, ...config}`), so `mode` is a
 * TOP-LEVEL key rather than a nested object. A DTO shaped `{success, config:{mode}}` would decode
 * to a null mode and read as "internal" for a workspace that chose `linked`.
 *
 * ⚠️ [mode] IS A PLAIN STRING, NOT AN ENUM, ON PURPOSE. The read path's sanitiser is total — an
 * unrecognised stored value is silently repaired to `internal` — but a THIRD mode added
 * server-side would arrive here as a value an enum could not decode, and failing a settings read
 * over a mode this build has not learned yet is the wrong direction. [KB_MODES] is what the
 * selector offers; anything else is displayed as itself and left alone.
 */
@Serializable
data class KnowledgeModeResponse(
    val success: Boolean = false,
    val mode: String? = null,
)

/**
 * The body of `PATCH /api/district/workspace/knowledge-mode`.
 *
 * ⛔ THE ROUTE VALIDATES `mode` WITH `z.enum(KB_MODES)`, so an unknown value is a **400** rather
 * than a stored value that matches no retrieval branch. Send only a value from [KB_MODES].
 */
@Serializable
data class KnowledgeModePatchRequest(val workspaceId: String, val mode: String)

/** ⚠️ Answers only from documents uploaded here, retrieved inside the workspace's own region. */
const val KB_MODE_INTERNAL: String = "internal"

/** ⛔ Sends the question to Atlassian to compose the answer. A data-residency change. */
const val KB_MODE_LINKED: String = "linked"

/**
 * The server's `KB_MODES`, in its order.
 *
 * ⚠️ THEY ARE NOT ADDITIVE, which is why this is a choice rather than a pair of switches. Running
 * both means two sources that drift and then contradict each other in front of a customer.
 */
val KB_MODES: List<String> = listOf(KB_MODE_INTERNAL, KB_MODE_LINKED)

/** ⚠️ `internal` — a workspace that never opens this setting must not have its questions leave. */
const val DEFAULT_KB_MODE: String = KB_MODE_INTERNAL
