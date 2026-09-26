package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.DeskLogoRemovalResponse
import com.distronode.districtai.core.model.DeskMessage
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.core.model.DeskSettingsPatch
import com.distronode.districtai.core.model.DeskTicketDetail
import com.distronode.districtai.core.model.DeskTicketDraft
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.DeskTicketSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DeskApi
import java.util.UUID

/**
 * The desk's data layer.
 *
 * ⛔ WHAT THIS FILE PROTECTS, IN ORDER OF HOW BADLY IT READS WHEN IT FAILS:
 * 1. **A structurally empty 200 must not read as "no customer has ever contacted you".** Every
 *    field of every response DTO defaults, so `{}` decodes into a well-formed empty queue — which
 *    an operator reads as their customers' tickets having vanished. [rejectedEnvelope] is what
 *    keeps drift and emptiness apart, and it is applied to all nine calls.
 * 2. **A blank form box must reach the wire as ABSENT, not as `""`.** `requesterEmail: ""` fails
 *    the route's `.email()` and takes the whole create down with "A subject and a description are
 *    required" — naming two fields the operator did fill in. [blankToNull] is the one place that
 *    happens, so a screen may hand this repository whatever is in its boxes.
 * 3. **A double tap must not raise two tickets.** The idempotency key is minted HERE, per call,
 *    rather than held by a screen — see [keys].
 */
class DeskRepository(
    private val api: DeskApi,
    /**
     * ⛔ MINTED PER CALL, WHICH IS PER SUBMIT, AND THAT IS THE WHOLE PROTECTION. A key held across
     * submits would swallow the operator's SECOND, genuinely different ticket as a duplicate — the
     * exact inverse of the bug it exists to prevent. Injected only so a test can assert the key
     * reached the wire; production has one implementation.
     */
    private val keys: () -> String = { UUID.randomUUID().toString() },
) {

    // ── Settings ─────────────────────────────────────────────────────────────

    /**
     * ⛔ THE READ THAT DECIDES WHICH SCREEN TO DRAW. Its FAILURE is a third state alongside
     * `enabled: false` and an empty queue, and the caller must keep all three apart: "the desk is
     * off", "nobody has written in" and "we could not ask" are three different sentences and only
     * one of them offers a retry.
     */
    suspend fun settings(workspaceId: String): ApiResult<DeskSettings> =
        when (val result = api.deskSettings(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(DESK_SETTINGS_ENVELOPE, result.value.success)
                    ?: settingsOrDrift(result.value.settings)
            is ApiResult.Failure -> result
        }

    /**
     * ⛔ DECLINES AN EMPTY PATCH RATHER THAN SENDING ONE. The route answers 400 for a body with no
     * fields, deliberately, because an empty body is always a client bug — so a caller that has
     * nothing to change must not call at all, and this is the guard that makes that true even if a
     * screen forgets. It is reported as a decode-class failure rather than silently succeeding,
     * because a save that quietly did nothing is worse than one that says so.
     */
    suspend fun saveSettings(
        workspaceId: String,
        patch: DeskSettingsPatch,
    ): ApiResult<DeskSettings> {
        if (patch.isEmpty) {
            return ApiResult.DecodeFailure(
                cause = IllegalArgumentException("An empty desk settings patch is a 400, not a no-op"),
                bodyPreview = "DeskSettingsPatch{}",
            )
        }
        return when (val result = api.saveDeskSettings(workspaceId, patch)) {
            is ApiResult.Success ->
                rejectedEnvelope(DESK_SETTINGS_ENVELOPE, result.value.success)
                    ?: settingsOrDrift(result.value.settings)
            is ApiResult.Failure -> result
        }
    }

    /**
     * ⚠️ ANSWERS THE WHOLE SETTINGS ROW, so the screen adopts the echo rather than assuming the
     * logo it just uploaded is now the stored one. The object key is content-addressed, so
     * re-uploading the same image legitimately returns the URL that was already there.
     */
    suspend fun uploadLogo(
        workspaceId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<DeskSettings> =
        when (val result = api.uploadDeskLogo(workspaceId, fileName, mimeType, bytes)) {
            is ApiResult.Success ->
                rejectedEnvelope(DESK_SETTINGS_ENVELOPE, result.value.success)
                    ?: settingsOrDrift(result.value.settings)
            is ApiResult.Failure -> result
        }

    /**
     * ⛔ RETURNS `objectRemoved` AS WELL AS THE SETTINGS, NOT JUST THE SETTINGS. The takedown has two
     * halves and only the first is guaranteed by a 200 (see [DeskLogoRemovalResponse]). Collapsing
     * it to the settings row would discard the one field that says whether the bytes are still
     * being served.
     *
     * ⚠️ AS A [DeskLogoRemoval], whose settings are non-null, rather than the wire response: the
     * null case is drift and is answered here, so no caller carries a fallback that cannot run.
     */
    suspend fun deleteLogo(workspaceId: String): ApiResult<DeskLogoRemoval> =
        when (val result = api.deleteDeskLogo(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(LOGO_REMOVAL_ENVELOPE, result.value.success)
                    ?: result.value.settings
                        ?.let { ApiResult.Success(DeskLogoRemoval(it, result.value.objectRemoved)) }
                    ?: drift(LOGO_REMOVAL_ENVELOPE, "settings")
            is ApiResult.Failure -> result
        }

    // ── The queue ────────────────────────────────────────────────────────────

    /**
     * ⚠️ THE FILTER IS DELIBERATELY NOT PASSED BY THE SCREEN THAT SHOWS COUNTS. Every chip on the
     * queue carries a count, so filtering server-side would mean one request per chip and the
     * counts could then disagree between responses. The parameter exists for a caller that
     * genuinely wants one slice.
     */
    suspend fun tickets(
        workspaceId: String,
        status: DeskTicketStatus? = null,
    ): ApiResult<List<DeskTicketSummary>> =
        when (val result = api.deskTickets(workspaceId, status)) {
            is ApiResult.Success ->
                rejectedEnvelope(TICKETS_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.tickets)
            is ApiResult.Failure -> result
        }

    suspend fun ticket(workspaceId: String, ticketId: String): ApiResult<DeskTicketDetail> =
        when (val result = api.deskTicket(workspaceId, ticketId)) {
            is ApiResult.Success ->
                rejectedEnvelope(TICKET_ENVELOPE, result.value.success)
                    ?: result.value.ticket?.let { ApiResult.Success(it) }
                    ?: drift(TICKET_ENVELOPE, "ticket")
            is ApiResult.Failure -> result
        }

    /**
     * Raise a ticket on a customer's behalf.
     *
     * ⛔ THE THREE REQUESTER FIELDS ARE TRIMMED TO NULL HERE. See the ⛔ on [blankToNull]: a
     * present-but-empty email is a 400 for the WHOLE object, and the message it returns names the
     * wrong fields.
     *
     * ⚠️ RETURNS `null` FOR A DEDUPLICATED SUBMIT RATHER THAN FAILING. The server answers a
     * replayed key with no ticket at all, and that is a success: the ticket exists, we simply have
     * no row to show. A caller should re-read the queue rather than report an error.
     */
    suspend fun createTicket(
        workspaceId: String,
        draft: DeskTicketDraft,
    ): ApiResult<DeskTicketSummary?> {
        val trimmed = draft.copy(
            subject = draft.subject.trim(),
            message = draft.message.trim(),
            requesterName = blankToNull(draft.requesterName),
            requesterEmail = blankToNull(draft.requesterEmail),
            requesterPhone = blankToNull(draft.requesterPhone),
            contactId = blankToNull(draft.contactId),
        )
        return when (val result = api.createDeskTicket(workspaceId, trimmed, keys())) {
            is ApiResult.Success ->
                rejectedEnvelope(CREATE_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.ticket)
            is ApiResult.Failure -> result
        }
    }

    /**
     * Answer the customer.
     *
     * ⛔ RETURNS THE ECHOED TICKET AS WELL AS THE MESSAGE, AND BOTH MAY BE NULL. A reply auto-sets
     * the ticket to `waiting` unless it is resolved, so a screen that assumed `waiting` would be
     * wrong for a resolved ticket where the server deliberately leaves the state alone — and on a
     * degraded replay the server sends neither, which is still a success.
     */
    suspend fun reply(
        workspaceId: String,
        ticketId: String,
        message: String,
    ): ApiResult<DeskReply> =
        when (val result = api.replyToDeskTicket(workspaceId, ticketId, message.trim(), keys())) {
            is ApiResult.Success ->
                rejectedEnvelope(REPLY_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(
                        DeskReply(
                            ticket = result.value.ticket,
                            message = result.value.message,
                            notified = result.value.notified,
                            deduplicated = result.value.deduplicated == true,
                        ),
                    )
            is ApiResult.Failure -> result
        }

    /**
     * ⛔ THE ECHOED TICKET IS THE ANSWER, NOT THE STATUS THAT WAS ASKED FOR. Resolving stamps
     * `resolvedAt` and anything else clears it, server-side, so a screen holding its own copy would
     * show a resolution time for a ticket that has been reopened.
     */
    suspend fun setStatus(
        workspaceId: String,
        ticketId: String,
        status: DeskTicketStatus,
    ): ApiResult<DeskTicketSummary> =
        when (val result = api.setDeskTicketStatus(workspaceId, ticketId, status)) {
            is ApiResult.Success ->
                rejectedEnvelope(STATUS_ENVELOPE, result.value.success)
                    ?: result.value.ticket?.let { ApiResult.Success(it) }
                    ?: drift(STATUS_ENVELOPE, "ticket")
            is ApiResult.Failure -> result
        }

    private companion object {
        const val LOGO_REMOVAL_ENVELOPE = "DeskLogoRemovalResponse"
        const val TICKETS_ENVELOPE = "DeskTicketsResponse"
        const val TICKET_ENVELOPE = "DeskTicketResponse"
        const val CREATE_ENVELOPE = "DeskTicketCreateResponse"
        const val REPLY_ENVELOPE = "DeskReplyResponse"
        const val STATUS_ENVELOPE = "DeskTicketStatusResponse"
    }
}

/**
 * ⚠️ TOP-LEVEL RATHER THAN MEMBERS, and the reason is a lint ceiling worth respecting rather than
 * raising: detekt caps a class at 11 functions and fires AT the threshold. Both are pure and read
 * no state of [DeskRepository], so moving them out is the honest answer — the same call
 * `DistrictApiClient` makes for `isJsonLike`.
 *
 * ⛔ THE ENVELOPE NAME IS CARRIED INTO THE FAILURE because a `DecodeFailure` with no provenance
 * reads as "the server sent something unparseable" when the truth is "the server affirmed success
 * and then omitted the field the screen needs". Those are different bugs on different sides.
 */
private fun <T> drift(envelope: String, field: String): ApiResult<T> = ApiResult.DecodeFailure(
    cause = IllegalStateException("$envelope affirmed success without $field"),
    bodyPreview = "$envelope{success=true, $field=null}",
)

private fun settingsOrDrift(settings: DeskSettings?): ApiResult<DeskSettings> =
    settings?.let { ApiResult.Success(it) } ?: drift(DESK_SETTINGS_ENVELOPE, "settings")

/** ⚠️ Shared by the GET, the PATCH and the logo POST, which all answer the same envelope. */
private const val DESK_SETTINGS_ENVELOPE = "DeskSettingsResponse"

/**
 * A logo takedown that succeeded: the settings row the server stored, and whether the stored object
 * was deleted too. See [DeskRepository.deleteLogo].
 */
data class DeskLogoRemoval(val settings: DeskSettings, val objectRemoved: Boolean)

/**
 * What a reply actually produced.
 *
 * ⚠️ EVERY FIELD IS OPTIONAL BECAUSE THE SERVER HAS THREE SUCCESS SHAPES. [deduplicated] with
 * nothing else is the degraded replay: the reply landed, and we cannot say which one it was.
 * [notified] is null on that path too, so "we do not know whether the customer was emailed" is a
 * state a screen has to be able to say.
 */
data class DeskReply(
    val ticket: DeskTicketSummary?,
    val message: DeskMessage?,
    val notified: Boolean?,
    val deduplicated: Boolean,
)

/**
 * ⛔ BLANK BECOMES ABSENT, WHICH IS NOT THE SAME AS BLANK BECOMING EMPTY. The route permits these
 * keys to be missing and rejects them present-and-empty, so a form that sends `""` for an untouched
 * box takes down the whole create with a message naming two fields that were filled in.
 */
internal fun blankToNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
