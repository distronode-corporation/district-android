package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.DeskLogoRemovalResponse
import com.distronode.districtai.core.model.DeskReplyResponse
import com.distronode.districtai.core.model.DeskSettingsPatch
import com.distronode.districtai.core.model.DeskSettingsResponse
import com.distronode.districtai.core.model.DeskTicketCreateResponse
import com.distronode.districtai.core.model.DeskTicketDraft
import com.distronode.districtai.core.model.DeskTicketResponse
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.DeskTicketStatusResponse
import com.distronode.districtai.core.model.DeskTicketsResponse

/**
 * District Desk: the tenant's own customers' tickets, and the queue's settings.
 *
 * ⛔ ITS OWN INTERFACE RATHER THAN NINE MORE METHODS ON `DistrictApi`, WHICH IS THE SAME CALL EVERY
 * `Http*Api` SECTION MAKES AND FOR THE SAME REASON. That interface is delegated to per section
 * precisely because one implementation of all of it exceeded detekt's function ceiling two sections
 * in. Keeping desk here means its paths and its quirks can be read in one sitting.
 *
 * ⛔ EVERY ONE OF THESE NINE CARRIES `workspaceId` AS A **QUERY** PARAMETER, INCLUDING THE MULTIPART
 * UPLOAD AND THE THREE THAT ALSO HAVE BODIES. All nine routes read
 * `new URL(req.url).searchParams.get("workspaceId")` and hand it to `requireWorkspaceRole` before
 * anything else runs. Putting it in the body instead leaves the guard with null and the request is
 * refused before the handler is reached, while the body looks entirely correct.
 *
 * ⛔ ALL NINE ARE `["agency","client"]` AND EXCLUDE `viewer`, READS INCLUDED — see the ⛔ on
 * `DeskSettings`. The entry point must be HIDDEN for a viewer, not merely captioned.
 *
 * ⚠️ NOTHING HERE IS BILLABLE AND NOTHING HERE IS IRREVERSIBLE, which is worth stating because most
 * of the write surfaces in this client are one or the other. The rate limits that exist bound a
 * runaway client rather than a spend.
 */
interface DeskApi {

    /**
     * The workspace's desk configuration.
     *
     * ⛔ THIS IS THE READ THAT DECIDES WHICH SCREEN TO DRAW, AND ITS FAILURE IS A THIRD STATE.
     * `enabled: false` means the queue is empty BY CONSTRUCTION and nothing is being recorded; a
     * failed read means we could not ask. Rendering the second as the first sends an operator to
     * turn on something already on, and rendering either as an empty ticket list says "no customer
     * has ever contacted you", which is a lie in both cases.
     *
     * ⚠️ A WORKSPACE THAT HAS NEVER TOUCHED THE DESK STILL GETS A FULL BODY. There is no row until
     * something is saved and the server answers its own defaults rather than 404, so "no settings
     * yet" is not a state this client can observe.
     */
    suspend fun deskSettings(workspaceId: String): ApiResult<DeskSettingsResponse>

    /**
     * Change one or more desk settings.
     *
     * ⛔ A PATCH THAT MERGES PER FIELD — see the ⛔ on `DeskSettingsPatch` for why sending the whole
     * form is the `blank_form_overwrites_config` bug, and why an empty patch is a 400 rather than a
     * no-op.
     *
     * ⚠️ IT ECHOES THE WHOLE STORED ROW, so this write needs no re-read — and the echo is what must
     * be adopted, never the values that were sent.
     */
    suspend fun saveDeskSettings(
        workspaceId: String,
        patch: DeskSettingsPatch,
    ): ApiResult<DeskSettingsResponse>

    /**
     * Publish a logo for the tenant's customer-facing thread page.
     *
     * ⛔ THE WORKSPACE IS A QUERY PARAMETER AND THE MULTIPART BODY CARRIES **NO FIELDS AT ALL**,
     * which is the one way this differs from `uploadMedia` and the only way to get it wrong. That
     * route reads `workspaceId` off `req.formData()`; this one reads it off the URL.
     *
     * ⚠️ ITS REFUSALS ARE NOT ALL 400. 413 for too large, 415 for a media type the server will not
     * host (an SVG lands here, sniffed from the bytes rather than trusted from the header), 400 for
     * empty or out-of-bounds pixel dimensions, 502 when object storage refused the write and 503
     * when logo hosting is not configured at all. Surface the server's own sentence; this client
     * cannot pre-compute any of them.
     *
     * ⚠️ RE-UPLOADING THE SAME IMAGE IS A NO-OP RATHER THAN A CHURN. The object key is
     * content-addressed, so the row ends up pointing at the same URL.
     */
    suspend fun uploadDeskLogo(
        workspaceId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<DeskSettingsResponse>

    /** Take the logo down. ⛔ A DELETE WITH A QUERY AND NO BODY — see [DeskLogoRemovalResponse]. */
    suspend fun deleteDeskLogo(workspaceId: String): ApiResult<DeskLogoRemovalResponse>

    /**
     * The workspace's ticket queue, newest activity first.
     *
     * ⚠️ THE `status` FILTER IS OPTIONAL AND A SCREEN SHOWING COUNTS SHOULD NOT USE IT. Filtering
     * server-side would mean one request per chip to draw one row of chips, and the counts could
     * then disagree with each other between responses. Read the queue whole and filter locally.
     *
     * ⚠️ THE ROUTE CAPS AT 100 ROWS AND HAS NO PAGING — no cursor, no offset, no `total` — so a
     * workspace past the cap silently sees its 100 most recently updated tickets.
     *
     * ⛔ AN EMPTY LIST IS NOT "THE DESK IS OFF". Those are two different screens with two different
     * sentences, and [deskSettings] is what tells them apart.
     */
    suspend fun deskTickets(
        workspaceId: String,
        // No default: every caller passes this explicitly, so a default could never be taken.
        status: DeskTicketStatus?,
    ): ApiResult<DeskTicketsResponse>

    /**
     * Raise a ticket by hand, for something a customer brought another way.
     *
     * ⛔ THE OPENING MESSAGE FIELD IS `message`, MATCHING THE REPLY ROUTE AND NOT THE SUPPORT
     * DESK'S `body`.
     *
     * @param idempotencyKey ⚠️ PER SUBMIT, NOT PER SCREEN. It must be minted when the operator taps
     *   send, so a double tap collapses onto one ticket; a key held across submits would swallow the
     *   SECOND ticket as a duplicate. A `uuid` in the schema. Omitting it is legal and simply
     *   forgoes the protection.
     */
    suspend fun createDeskTicket(
        workspaceId: String,
        draft: DeskTicketDraft,
        // No default: every caller passes this explicitly, so a default could never be taken.
        idempotencyKey: String?,
    ): ApiResult<DeskTicketCreateResponse>

    /**
     * One ticket and its whole thread.
     *
     * ⚠️ A FOREIGN ID IS A **404**, not a 403, and that is a disclosure decision as well as a
     * correctness one: the read is scoped by `workspaceId` in the where-clause AND by RLS, so a
     * foreign id simply finds nothing, and 403 would confirm the id exists somewhere on the
     * platform.
     *
     * ⚠️ THE SEGMENT IS THE TICKET'S UUID, NOT ITS `T-n` DISPLAY REFERENCE.
     */
    suspend fun deskTicket(workspaceId: String, ticketId: String): ApiResult<DeskTicketResponse>

    /**
     * Answer the customer.
     *
     * ⛔ **THE FIELD IS `message`, NOT `body`.** The route's schema is `z.object({ message })`, so a
     * body spelled `body` parses to nothing and every reply 400s with "A message is required" —
     * while the adjacent SUPPORT desk's reply takes exactly `body`. Two surfaces one word apart, and
     * the failure is a plausible-looking request that never lands.
     *
     * ⛔ AND THE ROUTE FILE ITSELF READS LIKE EVIDENCE FOR THE WRONG ANSWER, WHICH IS WHY THIS IS
     * PINNED BY A TEST RATHER THAN BY THIS COMMENT. The server's reply route contains the word `body` twice
     * and NEITHER is the request field: one declares the local holding the parsed request, the other
     * passes `body: parsed.data.message` into an INTERNAL call on the far side of the schema. A
     * reader grepping that file for "body" finds both, in plausible positions, and concludes the
     * opposite of the truth. `DeskRequestBodyTest` asserts the serialised keys.
     *
     * ⛔ A REPLY AUTO-SETS THE TICKET TO `waiting` UNLESS IT IS RESOLVED, which is why the response
     * carries the ticket as well as the message. Adopt the echoed status.
     */
    suspend fun replyToDeskTicket(
        workspaceId: String,
        ticketId: String,
        message: String,
        // No default: every caller passes this explicitly, so a default could never be taken.
        idempotencyKey: String?,
    ): ApiResult<DeskReplyResponse>

    /**
     * Move a ticket between open, waiting and resolved.
     *
     * ⛔ THE PARAMETER IS A [DeskTicketStatus] RATHER THAN A `String`, WHICH IS THE OPPOSITE OF THE
     * READ. The route validates with `z.enum` so an unrecognised value is a 400, but the column
     * behind it is plain `TEXT` — chosen so adding a state never needs a migration on four
     * databases — which makes that enum the only thing between a typo and a permanent, unfilterable
     * status on a customer's ticket.
     *
     * ⛔ RESOLVING STAMPS `resolvedAt` AND ANY OTHER STATUS CLEARS IT, both server-side, which is why
     * the echoed ticket must be adopted rather than the requested status.
     */
    suspend fun setDeskTicketStatus(
        workspaceId: String,
        ticketId: String,
        status: DeskTicketStatus,
    ): ApiResult<DeskTicketStatusResponse>
}
