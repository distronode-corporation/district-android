package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.DeskBrandName
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [DeskApi] over [DistrictApiClient].
 *
 * ⛔ ITS REQUEST BODIES ARE BUILT AS `JsonObject`s BY HAND RATHER THAN SERIALISED FROM A DTO, WHICH
 * IS THE OPPOSITE OF EVERY OTHER SECTION IN THIS MODULE. The settings PATCH needs THREE states for
 * one field — absent, a string, and an explicit `null` — and the shared body encoder runs
 * `explicitNulls = false`, which DROPS a null pair by design. A `@Serializable` request DTO
 * therefore physically cannot express "clear the brand name": it would encode to an absent key,
 * which the route reads as "leave it alone". See [DeskBrandName].
 *
 * ⚠️ THE OTHER THREE BODIES ARE BUILT THE SAME WAY FOR CONSISTENCY WITHIN THE FILE rather than
 * because they need it, and each drops its nulls explicitly at the call site. Mixing two body
 * mechanisms in one section is how one of them quietly acquires the wrong null semantics.
 */
class HttpDeskApi(private val client: DistrictApiClient) : DeskApi {

    override suspend fun deskSettings(workspaceId: String): ApiResult<DeskSettingsResponse> =
        client.get(
            segments = DeskPaths.SETTINGS,
            serializer = DeskSettingsResponse.serializer(),
            query = workspaceQuery(workspaceId),
        )

    override suspend fun saveDeskSettings(
        workspaceId: String,
        patch: DeskSettingsPatch,
    ): ApiResult<DeskSettingsResponse> =
        client.send(
            method = "PATCH",
            segments = DeskPaths.SETTINGS,
            serializer = DeskSettingsResponse.serializer(),
            query = workspaceQuery(workspaceId),
            body = patchBody(patch),
        )

    /**
     * ⛔ NO FORM FIELDS AND A QUERY PARAMETER — the inverse of `uploadMedia`, one route over. See
     * the ⛔ on [DeskApi.uploadDeskLogo], and on [DistrictApiClient.sendMultipart]'s `query`.
     */
    override suspend fun uploadDeskLogo(
        workspaceId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<DeskSettingsResponse> =
        client.sendMultipart(
            segments = DeskPaths.LOGO,
            serializer = DeskSettingsResponse.serializer(),
            fields = emptyMap(),
            fileName = fileName,
            contentType = mimeType,
            bytes = bytes,
            query = workspaceQuery(workspaceId),
        )

    override suspend fun deleteDeskLogo(workspaceId: String): ApiResult<DeskLogoRemovalResponse> =
        client.send(
            method = "DELETE",
            segments = DeskPaths.LOGO,
            serializer = DeskLogoRemovalResponse.serializer(),
            query = workspaceQuery(workspaceId),
        )

    override suspend fun deskTickets(
        workspaceId: String,
        status: DeskTicketStatus?,
    ): ApiResult<DeskTicketsResponse> =
        client.get(
            segments = DeskPaths.TICKETS,
            serializer = DeskTicketsResponse.serializer(),
            // ⚠️ A null status is DROPPED by the query builder rather than sent empty.
            // `status=` present-and-empty is not in the route's vocabulary, so it would fall
            // through to "no filter" — the same answer by accident rather than by contract.
            query = workspaceQuery(workspaceId) + ("status" to status?.wire),
        )

    override suspend fun createDeskTicket(
        workspaceId: String,
        draft: DeskTicketDraft,
        idempotencyKey: String?,
    ): ApiResult<DeskTicketCreateResponse> =
        client.send(
            method = "POST",
            segments = DeskPaths.TICKETS,
            serializer = DeskTicketCreateResponse.serializer(),
            query = workspaceQuery(workspaceId),
            body = createBody(draft, idempotencyKey),
        )

    override suspend fun deskTicket(
        workspaceId: String,
        ticketId: String,
    ): ApiResult<DeskTicketResponse> =
        client.get(
            segments = DeskPaths.ticket(ticketId),
            serializer = DeskTicketResponse.serializer(),
            query = workspaceQuery(workspaceId),
        )

    override suspend fun replyToDeskTicket(
        workspaceId: String,
        ticketId: String,
        message: String,
        idempotencyKey: String?,
    ): ApiResult<DeskReplyResponse> =
        client.send(
            method = "POST",
            segments = DeskPaths.ticketReply(ticketId),
            serializer = DeskReplyResponse.serializer(),
            query = workspaceQuery(workspaceId),
            // ⛔ `message`, NOT `body`. See the ⛔ on [DeskApi.replyToDeskTicket].
            body = jsonObjectOf(
                "message" to JsonPrimitive(message),
                "idempotencyKey" to idempotencyKey?.let(::JsonPrimitive),
            ),
        )

    override suspend fun setDeskTicketStatus(
        workspaceId: String,
        ticketId: String,
        status: DeskTicketStatus,
    ): ApiResult<DeskTicketStatusResponse> =
        client.send(
            method = "POST",
            segments = DeskPaths.ticketStatus(ticketId),
            serializer = DeskTicketStatusResponse.serializer(),
            query = workspaceQuery(workspaceId),
            body = jsonObjectOf("status" to JsonPrimitive(status.wire)),
        )
}

/**
 * ⛔ THE EXPLICIT-NULL ESCAPE HATCH, USED DELIBERATELY AND EXACTLY ONCE. `publicBrandName` is the
 * only key in this client that may reach the wire as a literal `null`, and it means "clear the
 * column" rather than "no opinion".
 *
 * ⚠️ TOP-LEVEL RATHER THAN A MEMBER, because detekt caps a class at 11 functions and fires AT the
 * threshold. It is pure and reads no state of [HttpDeskApi], so moving it out is the honest answer
 * rather than raising a ceiling — the same call `DistrictApiClient` makes for `isJsonLike`.
 */
private fun patchBody(patch: DeskSettingsPatch): JsonObject = jsonObjectOf(
    "enabled" to patch.enabled?.let(::JsonPrimitive),
    "notifyCustomersByEmail" to patch.notifyCustomersByEmail?.let(::JsonPrimitive),
    "publicBrandName" to when (val change = patch.publicBrandName) {
        null -> null
        is DeskBrandName.Set -> JsonPrimitive(change.name)
        DeskBrandName.Clear -> JsonNull
    },
)

/**
 * ⛔ A BLANK OPTIONAL IS DROPPED RATHER THAN SENT AS `""`. `requesterEmail: ""` fails the
 * route's `.email()` and takes the whole object down, so a phone-only ticket would 400 with "A
 * subject and a description are required" — naming two fields that were both filled in.
 * `DeskRepository` is what blanks them to null; this is the second half of that contract.
 */
private fun createBody(draft: DeskTicketDraft, idempotencyKey: String?): JsonObject =
    jsonObjectOf(
        "subject" to JsonPrimitive(draft.subject),
        "message" to JsonPrimitive(draft.message),
        "requesterName" to draft.requesterName?.let(::JsonPrimitive),
        "requesterEmail" to draft.requesterEmail?.let(::JsonPrimitive),
        "requesterPhone" to draft.requesterPhone?.let(::JsonPrimitive),
        "contactId" to draft.contactId?.let(::JsonPrimitive),
        "idempotencyKey" to idempotencyKey?.let(::JsonPrimitive),
    )

/**
 * The desk's five paths.
 *
 * ⚠️ ITS OWN OBJECT RATHER THAN ENTRIES ON `DistrictPaths`. That object is `internal` to this
 * module and would serve, but it lives inside `HttpDistrictApi.kt` — a file three parallel
 * workstreams are editing — and a path constant is the cheapest possible thing to keep beside the
 * one class that uses it.
 */
private object DeskPaths {
    private val DESK = ApiRoots.DISTRICT + "desk"

    val SETTINGS = DESK + "settings"
    val LOGO = DESK + "logo"
    val TICKETS = DESK + "tickets"

    /** ⚠️ The ticket's UUID. The `T-n` display reference is not addressable. */
    fun ticket(ticketId: String) = TICKETS + ticketId

    fun ticketReply(ticketId: String) = ticket(ticketId) + "reply"

    fun ticketStatus(ticketId: String) = ticket(ticketId) + "status"
}
