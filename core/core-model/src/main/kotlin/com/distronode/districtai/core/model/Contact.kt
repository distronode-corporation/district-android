package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One CRM contact, as both `GET /api/district/contacts` and
 * `GET /api/district/contacts/get?contactId=` return it.
 *
 * ⛔ BOTH ROUTES RETURN THE RAW PRISMA ROW, WHICH IS WHY ONE DTO SERVES BOTH. The list route was
 * written to match `contacts/get` deliberately. The obvious alternative — reusing the server's
 * `getPaginatedContacts` helper — returns a MAPPED shape that silently drops [dgiStatus], [dgiError]
 * and [visualMemory] and converts absent values to `undefined` instead of `null`. The detail screen
 * needs `dgiStatus`, so using the mapper would have forced two contact shapes with different
 * nullability onto this client. The contract suite asserts the two responses are equal.
 *
 * ⚠️ EMAIL-FIRST: [phoneNumber] IS NULLABLE. Contacts used to require a phone and no longer do. The
 * database enforces one contact per phone per workspace, but Postgres treats NULLs as distinct in a
 * btree unique, so any number of phone-less contacts coexist — that is required, not tolerated. Do
 * not assume a phone number is present, and do not use it as an identity key; use [id].
 */
@Serializable
data class Contact(
    val id: String,
    val workspaceId: String,
    /** Non-null server-side. The mapped helper substitutes "Unknown"; the raw row does not. */
    val name: String,
    /** ⚠️ Nullable — see the class note on email-first contacts. */
    val phoneNumber: String? = null,
    val email: String? = null,

    /**
     * Arbitrary platform → handle pairs.
     *
     * ⚠️ MODELLED AS OPAQUE JSON BECAUSE THE COLUMN IS `Json?` WITH NO SERVER-SIDE SHAPE. The lib
     * casts it to `Record<string, unknown>`, so an object is the intended form and a row holding
     * something else would fail the contract test — which is the signal we want, rather than a
     * crash on a phone. Read known keys defensively; do not invent a data class for a column nothing
     * validates.
     */
    val socialHandles: JsonObject? = null,

    /**
     * Firmographics. The server casts this to `{ name?, domain?, industry? }`, so those three are the
     * documented keys — but nothing enforces them, which is why every field on [ContactCompany] is
     * optional with a default.
     */
    val company: ContactCompany? = null,

    /**
     * The DGI enrichment dossier. Unstructured by design — its contents come from a model and change
     * with the prompt, so pinning a schema here would break on every prompt revision.
     */
    val intelligence: JsonObject? = null,

    /**
     * ⚠️ TYPED AS OPAQUE JSON EVEN THOUGH THE SCHEMA COMMENT SAYS "Array of strings". The column is
     * `Json?`, so nothing prevents a row from holding an object or a scalar, and a mismatched type is
     * NOT rescued by the lenient production parser — leniency ignores unknown KEYS, not wrong types,
     * so `List<String>?` here would throw on any row that disagreed. Not rendered in the app yet;
     * inspect it before trusting its shape.
     */
    val visualMemory: JsonElement? = null,

    val latestContextSummary: String? = null,

    /**
     * DGI dossier state.
     *
     * ⛔ NULL AND "pending" MEAN DIFFERENT THINGS AND MUST NOT BE CONFLATED. The column defaults to
     * "pending", but `contacts/clear-intel` resets it to NULL deliberately so that nothing re-crawls
     * the contact. So null means "no dossier, and none is queued" — render it as an offer to enrich,
     * not as a spinner. Treating null as "pending" would show a job that will never complete.
     *
     * ⛔ THE FULL SERVER VOCABULARY IS `pending -> crawling -> synthesizing -> complete | failed`,
     * PLUS NULL — and it is NOT what the web console shows. `useDgi` in the dashboard renders an
     * optimistic "processing" that no route ever writes; a client that polled for it would be
     * waiting for a status that cannot arrive. Written down here because the two surfaces disagree
     * and only one of them is the contract.
     */
    val dgiStatus: String? = null,
    val dgiError: String? = null,

    val budget: String? = null,
    val timeline: String? = null,
    val website: String? = null,

    /** ISO-8601 instant, or null if never enriched. */
    val lastUpdated: String? = null,
    /** ISO-8601 instant. */
    val createdAt: String,
) {
    /**
     * A label to show when the name is unhelpful.
     *
     * ⚠️ The raw row's `name` is non-null but can be the literal "Unknown" (the voice agent writes it
     * for an unidentified caller), so a blank check alone is not enough.
     */
    val displayName: String?
        get() = name.takeUnless { it.isBlank() || it == UNKNOWN_NAME }

    /**
     * Whether a dossier has been requested and is still running. See the note on [dgiStatus].
     *
     * ⛔ ALL THREE IN-FLIGHT STATUSES, NOT JUST "pending", AND THIS USED TO BE ONLY "pending".
     * The pipeline advances the row to "crawling" and then "synthesizing" as it works, so a
     * check against "pending" alone reports a running enrichment as FINISHED the moment the
     * crawler starts — which stops the poll, hides the badge, and re-offers the enrich button
     * for a job already in flight. Pressing it again is a second billable model run. The web
     * console's own poll predicate is the same three values; this is the client-side half of it.
     *
     * ⚠️ "failed" IS DELIBERATELY NOT HERE. It is terminal, and it is the state
     * [Contact.dgiError] describes.
     */
    val dgiInProgress: Boolean get() = dgiStatus in DGI_IN_FLIGHT

    /** Whether enrichment can be offered: nothing stored and nothing running. */
    val dgiOfferable: Boolean get() = dgiStatus == null || dgiStatus == DGI_FAILED

    /**
     * The LinkedIn handle out of [socialHandles], or null.
     *
     * ⛔ IT EXISTS FOR THE UPDATE ROUND TRIP, NOT FOR DISPLAY. `contacts/update` takes a bare
     * `linkedin` string and REPLACES the whole `socialHandles` column with `{linkedin}`, so a rename
     * that did not send this back would delete the handle as a side effect of changing a name.
     * `ContactsRepository.update` reads it here for exactly that reason.
     *
     * ⚠️ DEFENSIVE ON PURPOSE. The column is `Json?` with no server-side shape (see [socialHandles]),
     * so a row can hold a non-string under this key, and `jsonPrimitive` would throw on one. Anything
     * that is not a non-blank string reads as absent, which the route writes back as `""` anyway.
     */
    val linkedinHandle: String?
        get() = (socialHandles?.get(LINKEDIN_KEY) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val UNKNOWN_NAME = "Unknown"
        const val DGI_FAILED = "failed"

        /** ⚠️ The server's key, verbatim. `contacts/update` writes `socialHandles: {linkedin}`. */
        const val LINKEDIN_KEY = "linkedin"

        /**
         * ⚠️ A SET RATHER THAN A CHAIN OF `==`, so the vocabulary is stated once and the poll
         * loop and the badge provably agree about it. Adding a status server-side means adding
         * it here, in one place.
         */
        val DGI_IN_FLIGHT = setOf("pending", "crawling", "synthesizing")
    }
}

/**
 * The `company` blob's documented keys.
 *
 * ⚠️ Every field optional with a default, for the same reason [CallAnalysis]'s are: the column is
 * `Json?` and nothing in the database enforces the shape, so a row written by an earlier pipeline may
 * carry fewer keys. Drift in the other direction — a NEW key — is caught by the contract test, which
 * decodes strictly, while the shipped parser stays lenient.
 */
@Serializable
data class ContactCompany(
    val name: String? = null,
    val domain: String? = null,
    val industry: String? = null,
)

/**
 * `GET /api/district/contacts?workspaceId=&limit=&offset=`
 *
 * ⚠️ UNLIKE THE CALLS FEED, THIS ONE CARRIES A REAL [total]. The calls endpoint is a bare array with
 * no total and no `hasMore`, so its client infers end-of-list from a short page. Here the count is
 * authoritative, so paging can know how far it goes.
 */
@Serializable
data class ContactListResponse(
    val success: Boolean = false,
    val contacts: List<Contact> = emptyList(),
    /** Total contacts in the workspace, not just this page. */
    val total: Int = 0,
    /**
     * The limit and offset the server ACTUALLY applied.
     *
     * ⚠️ Echoed because the server clamps: a limit above 100, non-numeric, or non-positive is replaced
     * rather than honoured. Page arithmetic should use these, not the values that were requested.
     */
    val limit: Int = 0,
    val offset: Int = 0,
)

/** `GET /api/district/contacts/get?workspaceId=&contactId=` — the same row, singly. */
@Serializable
data class ContactDetailResponse(
    val success: Boolean = false,
    /**
     * Null only on a malformed response. A genuinely missing contact is a 404, which the client maps
     * to [com.distronode.districtai.core.network.ApiResult.NotFound].
     */
    val contact: Contact? = null,
    /**
     * The contact's number, described. ⛔ A SIBLING OF [contact], NOT A FIELD OF IT: [Contact] is the
     * raw row and must decode identically from the list and from this route, so the derived block
     * rides beside it. Null for a contact with no phone number, or one that does not parse.
     */
    val phoneIntel: PhoneIntel? = null,
)

/**
 * The answer from a contacts mutation.
 *
 * ⚠️ THE THREE MUTATIONS RETURN THREE SLIGHTLY DIFFERENT BODIES, so this is their union: `create`
 * answers `{success, id}`, while `update` and `delete` answer `{success}` alone. [id] is therefore
 * populated only by a create.
 *
 * ⚠️ A 404 from `update` or `delete` means the row was not there — for a delete that is effectively
 * success, so treat it as "already gone" rather than as an error worth alarming about. A **409** from
 * `create` means a contact with that phone or email already exists in the workspace, enforced by the
 * database rather than by validation.
 */
@Serializable
data class ContactMutationResponse(
    val success: Boolean = false,
    /** Populated by `create` only. */
    val id: String? = null,
)
