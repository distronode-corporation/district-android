package com.distronode.districtai.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.ClearIntelRequest
import com.distronode.districtai.core.network.CreateContactRequest
import com.distronode.districtai.core.network.DistrictApi
import com.distronode.districtai.core.network.EnrichRequest
import com.distronode.districtai.core.network.UpdateContactRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The CRM: a paged list, one contact by id, and the three mutations.
 *
 * ⛔ THE THREE MUTATIONS USE THREE DIFFERENT HTTP CONVENTIONS, and this is the layer that hides that
 * from callers: `create` is POST with a body, `update` is **PATCH** with a body and is the only route
 * in the whole API where `workspaceId` is mandatory, and `delete` is **DELETE with query parameters
 * and no body**. None of that is worth leaking into a ViewModel.
 *
 * ⚠️ EVERY MUTATION HERE EXCLUDES `viewer` SERVER-SIDE. Gate the UI on the role so a viewer is never
 * offered an action that can only 403 — the gating is an affordance, not a security control, and the
 * server remains the authority.
 */
class ContactsRepository(private val api: DistrictApi) {

    /**
     * A paged contact list for [workspaceId], newest first.
     *
     * ⚠️ Ordering is `createdAt desc` with `id` as a tie-break server-side, which is required rather
     * than tidy: a bulk import writes hundreds of rows sharing one `createdAt`, and without a second
     * key offset paging silently skips or repeats them.
     */
    fun contactList(workspaceId: String): Flow<PagingData<Contact>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            // ⚠️ Kept under the SERVER's clamp. `limit` is capped at 100 and Paging requests
            // `initialLoadSize` on the first load — at pageSize 25 that is 75. Raising pageSize past 33
            // would make the initial request exceed the clamp and come back short, which paging reads
            // as END OF LIST, so the whole CRM would look one page long.
            initialLoadSize = PAGE_SIZE * INITIAL_LOAD_MULTIPLIER,
            // No placeholders: the list is drawn from what arrived, not from a projected count.
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { ContactsPagingSource(api, workspaceId) },
    ).flow

    /**
     * One contact by id.
     *
     * ⚠️ Fetched rather than carried from the list so the detail screen survives process death and can
     * be opened by deep link.
     */
    suspend fun detail(workspaceId: String, contactId: String): ApiResult<Contact> =
        when (val result = api.contact(workspaceId, contactId)) {
            // ⚠️ Envelope first — see [rejectedEnvelope]. A `{}` body would otherwise be reported
            // as "carried no contact", which names the wrong problem.
            is ApiResult.Success -> rejectedEnvelope(DETAIL_ENVELOPE, result.value.success)
                // A 2xx with no contact is a malformed response, not an absence — absence is a 404.
                ?: result.value.contact?.let { ApiResult.Success(it) }
                ?: ApiResult.DecodeFailure(
                    IllegalStateException("success response carried no contact"),
                    result.value.toString().take(DECODE_PREVIEW_CHARS),
                )
            is ApiResult.Failure -> result
        }

    /**
     * Create a contact, returning its new id.
     *
     * ⚠️ A **409** means a contact with that phone or email already exists in this workspace — the
     * database enforces one per phone and one per lowercased email, so this is a duplicate rather than
     * a validation problem. Surface it as "this contact already exists".
     *
     * ⚠️ A contact needs a phone **or** an email, not both. (`bulk-create` disagrees and requires a
     * phone per row, silently counting email-only rows as invalid — a server-side inconsistency that
     * is not smoothed over here.)
     */
    suspend fun create(
        workspaceId: String,
        name: String,
        phoneNumber: String?,
        email: String?,
    ): ApiResult<String> =
        when (
            val result = api.createContact(
                CreateContactRequest(
                    workspaceId = workspaceId,
                    name = name,
                    // Blank is normalised to absent: the server distinguishes an omitted key from an
                    // empty string, and an empty phone would fail its format check rather than being
                    // treated as "no phone".
                    phoneNumber = phoneNumber?.takeIf { it.isNotBlank() },
                    email = email?.takeIf { it.isNotBlank() },
                ),
            )
        ) {
            is ApiResult.Success -> rejectedEnvelope(MUTATION_ENVELOPE, result.value.success)
                ?: result.value.id?.let { ApiResult.Success(it) }
                ?: ApiResult.DecodeFailure(
                    IllegalStateException("create succeeded without returning an id"),
                    result.value.toString().take(DECODE_PREVIEW_CHARS),
                )
            is ApiResult.Failure -> result
        }

    /**
     * Update a contact. **Every column the route writes is sent, every time.**
     *
     * ⛔ `contacts/update` IS A WHOLESALE REPLACE, SO SENDING ONLY THE CHANGED KEYS BREAKS A RENAME.
     * A null field does NOT mean "leave it alone": the handler writes `name || "Unknown"`,
     * `phoneNumber` and `email` as the normalised value or NULL, and
     * `socialHandles`/`latestContextSummary`/`budget`/`timeline`/`website` through `|| ""` (or
     * `|| "Manual edit update."`) unconditionally. So a rename sending `{workspaceId, contactId,
     * name}` would clear both addresses, and because the route refuses a contact with neither it
     * answers **400 "A contact needs a phone number or an email address"**, about fields the
     * operator never touched. The iOS client's `ContactsRepository.rename` follows the same rule.
     *
     * ⛔ THE FIX IS THE SIGNATURE, NOT THE CALL SITE. [current] is the contact as last read, and every
     * edited parameter defaults to its value, so the full row is rebuilt HERE and a caller cannot
     * forget a column. A caller clears a field only by passing null for it explicitly.
     *
     * ⚠️ NULLS ARE STILL OMITTED ON THE WIRE (`explicitNulls = false`), which is harmless here for the
     * reason above: an absent key and a null are the same thing to this route. A column the row holds
     * as null goes back as absent, which is the value it would get anyway.
     *
     * ⚠️ TWO COLUMNS STAY LOSSY AND THAT IS THE SERVER'S SHAPE, NOT A CHOICE HERE: `socialHandles` is
     * REPLACED with `{linkedin}` alone, so any other platform key is dropped whatever this sends, and
     * an empty `latestContextSummary` becomes the literal "Manual edit update."
     *
     * @param current the contact as last read from the server. ⛔ Not a cache: everything not
     *   overridden below is written back from it, so re-read it before editing.
     */
    suspend fun update(
        workspaceId: String,
        current: Contact,
        name: String = current.name,
        phoneNumber: String? = current.phoneNumber,
        email: String? = current.email,
    ): ApiResult<Unit> = api.updateContact(
        UpdateContactRequest(
            // ⛔ Required by this route specifically — omitting it once made the server drop its tenant
            // filter, so it now validates the field explicitly.
            workspaceId = workspaceId,
            contactId = current.id,
            name = name,
            phoneNumber = phoneNumber,
            email = email,
            // ⚠️ THE FIVE COLUMNS NOBODY IS EDITING, CARRIED BACK VERBATIM. They are here because the
            // route overwrites them whether or not they are sent, so omitting one is a deletion, not
            // a no-op. A future editor for any of them overrides it the same way [name] does.
            linkedin = current.linkedinHandle,
            contextSummary = current.latestContextSummary,
            budget = current.budget,
            timeline = current.timeline,
            website = current.website,
        ),
    ).mapToUnit()

    /**
     * Delete a contact.
     *
     * ⚠️ A 404 means the row was already gone, which for a delete is the outcome the caller wanted.
     * Callers should treat it as success rather than showing a failure the user cannot act on.
     */
    suspend fun delete(workspaceId: String, contactId: String): ApiResult<Unit> =
        when (val result = api.deleteContact(workspaceId, contactId)) {
            is ApiResult.Success -> rejectedEnvelope(MUTATION_ENVELOPE, result.value.success)
                ?: ApiResult.Success(Unit)
            // Already absent: the desired end state, so report it as done. ⚠️ Deliberately NOT
            // envelope-checked — a 404 has no success envelope to check, and the row being gone
            // is the outcome the caller wanted.
            is ApiResult.NotFound -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

    /**
     * Queue a Global Intelligence dossier for one contact.
     *
     * ⛔ THIS SPENDS MONEY AND IT IS NOT IDEMPOTENT. One request buys one external crawl and one
     * LLM synthesis, so nothing above this layer may retry it automatically — see the ⛔ on
     * `DgiApi`. A caller that could not tell whether the request landed must ask the operator,
     * not re-send.
     *
     * ⛔ A **403** HERE IS USUALLY NOT A ROLE PROBLEM. The workspace-level enrichment opt-in is
     * off, and the server's message names the settings page that turns it on. Surface that
     * message verbatim; replacing it with "you do not have permission" sends the operator looking
     * at their own account for a switch that lives on the workspace.
     *
     * ⚠️ SUCCESS MEANS SCHEDULED, NOT DONE. The dossier arrives on the contact row later, which
     * is what [dossierUpdates] is for.
     */
    suspend fun enrich(workspaceId: String, contactId: String): ApiResult<Unit> =
        when (
            val result = api.enrichContact(EnrichRequest(workspaceId, contactId))
        ) {
            is ApiResult.Success -> rejectedEnvelope(ENRICH_ENVELOPE, result.value.success)
                ?: ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

    /**
     * Clear a contact's dossier, keeping the contact.
     *
     * ⚠️ Unlike [delete], a 404 is NOT folded into success. There the row being gone is the
     * outcome the caller wanted; here it means the contact could not be found at all, and
     * reporting "cleared" for a contact that does not exist would be a claim about data nobody
     * touched.
     *
     * ⛔ AFTER THIS, `dgiStatus` IS NULL AND NOTHING IS QUEUED. A caller must re-read rather than
     * optimistically showing "pending" — that pending would never resolve.
     */
    suspend fun clearIntel(workspaceId: String, contactId: String): ApiResult<Unit> =
        when (
            val result = api.clearContactIntel(ClearIntelRequest(workspaceId, contactId))
        ) {
            is ApiResult.Success -> rejectedEnvelope(CLEAR_INTEL_ENVELOPE, result.value.success)
                ?: ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

    /**
     * Re-read one contact on an interval until its enrichment settles.
     *
     * ⛔ THE ONLY WAY TO LEARN THAT A DOSSIER ARRIVED. `contacts/enrich` answers in under 100ms
     * with `status: "pending"` and the real work happens on a Pub/Sub subscriber; there is no
     * push, no webhook and no completion endpoint. Polling is the contract, not a shortcut.
     *
     * ⛔ IT EMITS AFTER THE FIRST DELAY, NEVER IMMEDIATELY. The caller has just read the contact
     * (that is how it knows an enrichment is in flight), so a leading emission would be a
     * duplicate request answering a question already answered.
     *
     * ⛔ AND IT TERMINATES ON A FAILURE AS WELL AS ON A TERMINAL STATUS. A poll that retried
     * through an [ApiResult.Unauthorized] would hammer the API every few seconds from a screen
     * whose session is dead and which the user cannot fix by waiting. The screen keeps the
     * contact it already has and offers a reload; that is a worse-case one extra tap, against an
     * unbounded loop.
     *
     * ⚠️ Cold, and cancelled by the collector's scope — hence "stop on screen exit" needs no code
     * here beyond collecting it in `viewModelScope`.
     *
     * ⚠️ [intervalMillis] matches the web console's own DGI poll so the two surfaces put the same
     * load on the same route.
     */
    fun dossierUpdates(
        workspaceId: String,
        contactId: String,
        intervalMillis: Long = DOSSIER_POLL_INTERVAL_MS,
    ): Flow<ApiResult<Contact>> = flow {
        while (true) {
            delay(intervalMillis)
            val result = detail(workspaceId, contactId)
            emit(result)
            // ⛔ `dgiInProgress` COVERS ALL THREE IN-FLIGHT STATUSES, not just "pending" — see
            // Contact. A check against "pending" alone stops this loop the moment the crawler
            // starts, leaving the screen showing a stale dossier for a job still running.
            val stillRunning = (result as? ApiResult.Success)?.value?.dgiInProgress == true
            if (!stillRunning) return@flow
        }
    }

    /**
     * ⚠️ ASSERTS THE ENVELOPE RATHER THAN DISCARDING IT. `update` and `delete` answer `{success}`
     * and nothing else, so the flag is the ENTIRE payload — dropping straight to `Unit` meant a
     * 200 with an empty body was reported to the user as "saved". See [rejectedEnvelope].
     */
    private fun ApiResult<com.distronode.districtai.core.model.ContactMutationResponse>.mapToUnit():
        ApiResult<Unit> = when (this) {
        is ApiResult.Success -> rejectedEnvelope(MUTATION_ENVELOPE, value.success)
            ?: ApiResult.Success(Unit)
        is ApiResult.Failure -> this
    }

    private companion object {
        /** Matches the calls feed's page size, and one of the web console's own options. */
        const val PAGE_SIZE = 25

        /** Paging's own default. Named because the server-clamp arithmetic above depends on it. */
        const val INITIAL_LOAD_MULTIPLIER = 3

        /** Short on purpose: a malformed-response preview must not carry customer data wholesale. */
        const val DECODE_PREVIEW_CHARS = 200

        /**
         * ⚠️ 2.5s, MATCHING THE WEB CONSOLE'S OWN DGI POLL. Not tuned independently: the two
         * surfaces poll the same route against the same database, and a shorter interval here
         * would put a load on it that nothing on the server side was sized for.
         */
        const val DOSSIER_POLL_INTERVAL_MS = 2_500L

        const val DETAIL_ENVELOPE = "ContactDetailResponse"
        const val MUTATION_ENVELOPE = "ContactMutationResponse"
        const val ENRICH_ENVELOPE = "EnrichResponse"
        const val CLEAR_INTEL_ENVELOPE = "ClearIntelResponse"
    }
}
