package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * The two District Global Intelligence writes.
 *
 * ⛔ NEITHER OF THESE CARRIES THE DOSSIER. The dossier lives on the CONTACT row —
 * [Contact.intelligence], [Contact.company], [Contact.dgiStatus] — and is read back through
 * `contacts/get`. These routes only START the work and CLEAR it, which is why their bodies are
 * this small; a client looking here for the enrichment result would find nothing and conclude
 * the pipeline had failed.
 *
 * ⛔ AND THE STATUS VOCABULARY IS THE SERVER'S, NOT THE WEB CONSOLE'S. The real progression is
 * `pending -> crawling -> synthesizing -> complete | failed`, with NULL meaning "no dossier and
 * none queued". The web dashboard's `useDgi` hook additionally shows an optimistic "processing"
 * that no route ever sends; a client that polled for it would wait for a status that cannot
 * arrive. See [Contact.dgiStatus].
 */
@Serializable
data class EnrichResponse(
    val success: Boolean = false,
    /**
     * ⚠️ ALWAYS "pending" ON SUCCESS, and it is the value the poll loop's terminal check is
     * written against. The row is stamped BEFORE the response returns, so the very next
     * `contacts/get` reads "pending" rather than the pre-enrichment state — which is what lets a
     * client tell "queued" apart from "the button did nothing".
     */
    val status: String? = null,
    /** Operator-facing, English, server-side. Not localised. */
    val message: String? = null,
)

/**
 * `POST /api/district/contacts/clear-intel`
 *
 * ⛔ THE FLAG IS THE ENTIRE PAYLOAD, which makes this the DTO most exposed to the empty-body
 * problem: `{}` decodes into a well-formed `ClearIntelResponse()` and reads as a successful
 * clear. `rejectedEnvelope` in core-data is what stops that, and it is not optional here.
 *
 * ⛔ AFTER A CLEAR, `dgiStatus` IS **NULL** — not "pending". The route enqueues nothing, so a
 * "pending" would be a job with no worker behind it: the poll would never terminate and the
 * enrich control would stay disabled forever. Null means "no dossier, and none queued", which is
 * exactly the state that should offer enrichment again.
 */
@Serializable
data class ClearIntelResponse(
    val success: Boolean = false,
)
