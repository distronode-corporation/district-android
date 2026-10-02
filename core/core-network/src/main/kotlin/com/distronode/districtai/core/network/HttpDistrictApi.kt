package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.AiDraftRequest
import com.distronode.districtai.core.model.AiDraftResponse
import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.core.model.ClearIntelResponse
import com.distronode.districtai.core.model.DeviceListResponse
import com.distronode.districtai.core.model.DialResponse
import com.distronode.districtai.core.model.DeviceRevokeResponse
import com.distronode.districtai.core.model.EnrichResponse
import com.distronode.districtai.core.model.NumberSearchResponse
import com.distronode.districtai.core.model.OwnedNumbersResponse
import com.distronode.districtai.core.model.UnreadCountResponse
import com.distronode.districtai.core.model.MarkReadResponse
import com.distronode.districtai.core.model.MarkReadRequest
import com.distronode.districtai.core.model.SendMessageResponse
import com.distronode.districtai.core.model.SendMessageRequest
import com.distronode.districtai.core.model.TimelineResponse
import com.distronode.districtai.core.model.UsageHistoryResponse
import com.distronode.districtai.core.model.UsageResponse
import com.distronode.districtai.core.model.ConversationsResponse
import com.distronode.districtai.core.model.CallAnswerResponse
import com.distronode.districtai.core.model.CallDetailResponse
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.model.CallTranscriptResponse
import com.distronode.districtai.core.model.PushRegistrationResponse
import com.distronode.districtai.core.model.ContactDetailResponse
import com.distronode.districtai.core.model.ContactListResponse
import com.distronode.districtai.core.model.ContactMutationResponse
import com.distronode.districtai.core.model.HqConfirmRequest
import com.distronode.districtai.core.model.HqConfirmResponse
import com.distronode.districtai.core.model.HqPromptRequest
import com.distronode.districtai.core.model.HqPromptResponse
import com.distronode.districtai.core.model.DraftDeleteResponse
import com.distronode.districtai.core.model.DraftListResponse
import com.distronode.districtai.core.model.DraftResponse
import com.distronode.districtai.core.model.DraftSaveRequest
import com.distronode.districtai.core.model.MediaUploadResponse
import com.distronode.districtai.core.model.DirectoryPatchRequest
import com.distronode.districtai.core.model.KnowledgeCreateRequest
import com.distronode.districtai.core.model.KnowledgeCreateResponse
import com.distronode.districtai.core.model.KnowledgeDeleteResponse
import com.distronode.districtai.core.model.KnowledgeListResponse
import com.distronode.districtai.core.model.KnowledgeModePatchRequest
import com.distronode.districtai.core.model.KnowledgeModeResponse
import com.distronode.districtai.core.model.MemberAddRequest
import com.distronode.districtai.core.model.MemberListResponse
import com.distronode.districtai.core.model.MemberMutationResponse
import com.distronode.districtai.core.model.MemberRoleRequest
import com.distronode.districtai.core.model.MeetingDetail
import com.distronode.districtai.core.model.MeetingSummary
import com.distronode.districtai.core.model.MessagingAccountRequest
import com.distronode.districtai.core.model.MessagingAccountSaveResponse
import com.distronode.districtai.core.model.MessagingChannelDefaultRequest
import com.distronode.districtai.core.model.MessagingChannelDefaultResponse
import com.distronode.districtai.core.model.MessagingDefaultRequest
import com.distronode.districtai.core.model.MessagingDefaultResponse
import com.distronode.districtai.core.model.MessagingDeleteRequest
import com.distronode.districtai.core.model.MessagingMetaRequest
import com.distronode.districtai.core.model.MessagingMetaResponse
import com.distronode.districtai.core.model.MessagingResponse
import com.distronode.districtai.core.model.MessagingTestRequest
import com.distronode.districtai.core.model.MessagingTestResponse
import com.distronode.districtai.core.model.RoomTokenResponse
import com.distronode.districtai.core.model.SchedulingEnableResponse
import com.distronode.districtai.core.model.SchedulingHandOffResponse
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.RenameResponse
import com.distronode.districtai.core.model.WorkspaceRenameRequest
import com.distronode.districtai.core.model.OverviewResponse
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.RoutingRulesRequest
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.ToolsPatchRequest
import com.distronode.districtai.core.model.WorkspaceBillingResponse
import com.distronode.districtai.core.model.WorkspaceConfigResponse
import com.distronode.districtai.core.model.WorkspaceConfigSaveResponse
import com.distronode.districtai.core.model.WorkspaceListResponse
import com.distronode.districtai.core.model.CampaignStatusResponse
import com.distronode.districtai.core.model.WorkflowListResponse
import com.distronode.districtai.core.model.WorkflowRunsResponse
import com.distronode.districtai.core.model.WorkflowToggleResponse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * [DistrictApi] over [DistrictApiClient], composed from one implementation per section.
 *
 * ⛔ BUILT BY INTERFACE DELEGATION RATHER THAN AS ONE CLASS. Two sections in, a single implementation
 * already exceeded detekt's function ceiling, and seventeen sections remain. Delegation keeps each
 * section's paths and quirks in a small class that can be read in one sitting, while callers still get
 * one object to inject.
 */
class HttpDistrictApi(client: DistrictApiClient) :
    DistrictApi,
    WorkspaceApi by HttpWorkspaceApi(client),
    OverviewApi by HttpOverviewApi(client),
    CallsApi by HttpCallsApi(client),
    ContactsApi by HttpContactsApi(client),
    InboxApi by HttpInboxApi(client),
    MessageDraftsApi by HttpMessageDraftsApi(client),
    HqApi by HttpHqApi(client),
    AnalyticsApi by HttpAnalyticsApi(client),
    DgiApi by HttpDgiApi(client),
    NumbersApi by HttpNumbersApi(client),
    DevicesApi by HttpDevicesApi(client),
    BillingApi by HttpBillingApi(client),
    ConfigApi by HttpConfigApi(client),
    KnowledgeApi by HttpKnowledgeApi(client),
    MessagingApi by HttpMessagingApi(client),
    MembersApi by HttpMembersApi(client),
    MeetingsApi by HttpMeetingsApi(client),
    DialApi by HttpDialApi(client),
    InboundCallApi by HttpInboundCallApi(client),
    PushApi by HttpPushApi(client),
    WorkflowsApi by HttpWorkflowsApi(client),
    SchedulingApi by HttpSchedulingApi(client)

internal class HttpWorkspaceApi(private val client: DistrictApiClient) : WorkspaceApi {
    override suspend fun workspaceList(): ApiResult<WorkspaceListResponse> =
        client.get(
            segments = DistrictPaths.WORKSPACE_LIST,
            serializer = WorkspaceListResponse.serializer(),
        )
}

internal class HttpOverviewApi(private val client: DistrictApiClient) : OverviewApi {
    override suspend fun overview(workspaceId: String?): ApiResult<OverviewResponse> =
        client.get(
            segments = DistrictPaths.OVERVIEW,
            serializer = OverviewResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )
}

internal class HttpCallsApi(private val client: DistrictApiClient) : CallsApi {

    override suspend fun calls(
        workspaceId: String,
        limit: Int,
        offset: Int,
    ): ApiResult<List<CallSummary>> =
        client.get(
            segments = DistrictPaths.CALLS,
            // ⛔ A LIST SERIALIZER, NOT AN ENVELOPE'S. This route is `NextResponse.json(calls)` — a bare
            // array — while almost every other district route answers `{success, ...}`.
            serializer = ListSerializer(CallSummary.serializer()),
            query = mapOf(
                "workspaceId" to workspaceId,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ),
        )

    override suspend fun callDetail(
        workspaceId: String,
        callId: String,
    ): ApiResult<CallDetailResponse> =
        client.get(
            segments = DistrictPaths.CALLS + callId,
            serializer = CallDetailResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun callTranscript(
        workspaceId: String,
        callId: String,
    ): ApiResult<CallTranscriptResponse> =
        client.get(
            segments = DistrictPaths.CALLS + callId + "transcript",
            serializer = CallTranscriptResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun callRecordingUrl(
        workspaceId: String,
        callId: String,
    ): ApiResult<String> =
        client.redirectTarget(
            segments = DistrictPaths.CALLS + callId + "recording",
            query = mapOf("workspaceId" to workspaceId),
        )
}

internal class HttpContactsApi(private val client: DistrictApiClient) : ContactsApi {

    override suspend fun contacts(
        workspaceId: String,
        limit: Int,
        offset: Int,
    ): ApiResult<ContactListResponse> =
        client.get(
            segments = DistrictPaths.CONTACTS,
            serializer = ContactListResponse.serializer(),
            query = mapOf(
                "workspaceId" to workspaceId,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ),
        )

    override suspend fun contact(
        workspaceId: String,
        contactId: String,
    ): ApiResult<ContactDetailResponse> =
        client.get(
            // ⚠️ The id is a QUERY parameter here, not a path segment — this route predates the
            // /calls/{id} style and was not changed.
            segments = DistrictPaths.CONTACTS + "get",
            serializer = ContactDetailResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId, "contactId" to contactId),
        )

    override suspend fun createContact(
        request: CreateContactRequest,
    ): ApiResult<ContactMutationResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.CONTACTS + "create",
            serializer = ContactMutationResponse.serializer(),
            body = request.toJson(CreateContactRequest.serializer()),
        )

    override suspend fun updateContact(
        request: UpdateContactRequest,
    ): ApiResult<ContactMutationResponse> =
        client.send(
            // ⛔ PATCH, not POST. The route exports PATCH only; a POST would 405.
            method = "PATCH",
            segments = DistrictPaths.CONTACTS + "update",
            serializer = ContactMutationResponse.serializer(),
            body = request.toJson(UpdateContactRequest.serializer()),
        )

    override suspend fun deleteContact(
        workspaceId: String,
        contactId: String,
    ): ApiResult<ContactMutationResponse> =
        client.send(
            // ⛔ DELETE with QUERY PARAMETERS and NO BODY — the third convention in this one section.
            method = "DELETE",
            segments = DistrictPaths.CONTACTS + "delete",
            serializer = ContactMutationResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId, "contactId" to contactId),
        )
}

/**
 * The unified Inbox: the conversation list, one thread's timeline, and the two writes.
 *
 * ⛔ `sendMessage` AND `markRead` EXCLUDE `viewer` SERVER-SIDE, and `sendMessage` is billable — see
 * [InboxApi]. This layer only builds the requests; the role gate lives in the UI.
 */
internal class HttpInboxApi(private val client: DistrictApiClient) : InboxApi {
    override suspend fun conversations(workspaceId: String): ApiResult<ConversationsResponse> =
        client.get(
            segments = DistrictPaths.CONVERSATIONS,
            serializer = ConversationsResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun timeline(
        workspaceId: String,
        contactId: String?,
        address: String?,
        before: String?,
        beforeId: String?,
    ): ApiResult<TimelineResponse> =
        client.get(
            segments = DistrictPaths.TIMELINE,
            serializer = TimelineResponse.serializer(),
            // ⚠️ `phoneNumber` is the server's historical name for the address selector and now
            // carries an email too. Renaming it here would 400 — the route reads `phoneNumber` or
            // `address`, and only `phoneNumber` is guaranteed present in every deployed version.
            //
            // ⛔ THE CURSOR PAIR RELIES ON NULL ENTRIES BEING DROPPED, NOT SENT EMPTY, and that is
            // load-bearing rather than incidental: `buildUrl` skips a null value, so a no-cursor
            // read produces exactly the URL it produced before paging existed. Sending
            // `before=` instead would be a present-but-empty parameter, which `new Date("")` turns
            // into an Invalid Date and the route answers 400 — every thread open would break.
            query = mapOf(
                "workspaceId" to workspaceId,
                "contactId" to contactId,
                "phoneNumber" to address,
                "before" to before,
                "beforeId" to beforeId,
            ),
        )

    override suspend fun unreadCount(workspaceId: String): ApiResult<UnreadCountResponse> =
        client.get(
            segments = DistrictPaths.MESSAGES_UNREAD_COUNT,
            serializer = UnreadCountResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun sendMessage(request: SendMessageRequest): ApiResult<SendMessageResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.MESSAGES_SEND,
            serializer = SendMessageResponse.serializer(),
            body = request.toJson(SendMessageRequest.serializer()),
        )

    override suspend fun markRead(request: MarkReadRequest): ApiResult<MarkReadResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.MESSAGES_MARK_READ,
            serializer = MarkReadResponse.serializer(),
            body = request.toJson(MarkReadRequest.serializer()),
        )

    /**
     * ⛔ THE ONLY NON-JSON REQUEST THIS CLIENT MAKES. `workspaceId` travels as a FORM FIELD, not as
     * a query parameter: the route reads it off `req.formData()`, and a query parameter would leave
     * it undefined and trip the guard's 400 while the URL looked perfectly correct.
     */
    override suspend fun uploadMedia(
        workspaceId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<MediaUploadResponse> =
        client.sendMultipart(
            segments = DistrictPaths.MESSAGES_MEDIA,
            serializer = MediaUploadResponse.serializer(),
            fields = mapOf("workspaceId" to workspaceId),
            fileName = fileName,
            contentType = mimeType,
            bytes = bytes,
        )
}

/**
 * Draft persistence and AI generation.
 *
 * ⛔ TWO PATHS ONE LETTER APART, AND [DistrictPaths.MESSAGES_DRAFTS] IS THE CHEAP ONE.
 * `messages/drafts` stores what the operator typed; `messages/draft` spends a Vertex generation.
 * Pointing an autosave at the singular path would bill a model call on every keystroke debounce.
 *
 * ⚠️ THE THREE PERSISTENCE VERBS DO NOT SHARE A REQUEST SHAPE, WHICH IS THE SERVER'S ASYMMETRY
 * RATHER THAN A CHOICE HERE: GET and DELETE take QUERY parameters, PUT takes a JSON body. A helper
 * that assumed one form would silently not fit the others.
 */
internal class HttpMessageDraftsApi(private val client: DistrictApiClient) : MessageDraftsApi {

    override suspend fun draft(workspaceId: String, threadKey: String): ApiResult<DraftResponse> =
        client.get(
            segments = DistrictPaths.MESSAGES_DRAFTS,
            serializer = DraftResponse.serializer(),
            // ⛔ BOTH PARAMETERS, ALWAYS. The SAME path with `threadKey` ABSENT is the LIST
            // endpoint, which answers `{success, drafts: [...]}` — a different key and a different
            // type. Dropping the thread key here does not 400; it decodes as a draft-less response
            // and the composer silently restores nothing.
            query = mapOf("workspaceId" to workspaceId, "threadKey" to threadKey),
        )

    override suspend fun drafts(workspaceId: String): ApiResult<DraftListResponse> =
        client.get(
            segments = DistrictPaths.MESSAGES_DRAFTS,
            serializer = DraftListResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun saveDraft(request: DraftSaveRequest): ApiResult<DraftResponse> =
        client.send(
            // ⛔ PUT, NOT POST. The route exports GET/PUT/DELETE only; a POST is a 405, and the
            // upsert semantics are why PUT is the honest verb — autosave has no create-versus-
            // update distinction to express.
            method = "PUT",
            segments = DistrictPaths.MESSAGES_DRAFTS,
            serializer = DraftResponse.serializer(),
            body = request.toJson(DraftSaveRequest.serializer()),
        )

    override suspend fun deleteDraft(
        workspaceId: String,
        threadKey: String,
    ): ApiResult<DraftDeleteResponse> =
        client.send(
            method = "DELETE",
            segments = DistrictPaths.MESSAGES_DRAFTS,
            serializer = DraftDeleteResponse.serializer(),
            // ⚠️ QUERY PARAMETERS AND NO BODY, like `contacts/delete`. The route reads
            // `url.searchParams`, and OkHttp would happily send a DELETE body the server ignores.
            query = mapOf("workspaceId" to workspaceId, "threadKey" to threadKey),
        )

    override suspend fun generateDraft(request: AiDraftRequest): ApiResult<AiDraftResponse> =
        client.send(
            method = "POST",
            // ⛔ SINGULAR. This is the billed one. See the class doc.
            segments = DistrictPaths.MESSAGES_DRAFT,
            serializer = AiDraftResponse.serializer(),
            body = request.toJson(AiDraftRequest.serializer()),
        )
}

/**
 * District HQ: one path, two bodies.
 *
 * ⛔ BOTH CALLS ARE NON-IDEMPOTENT AND BOTH GO THROUGH [DistrictApiClient.send], WHICH RE-SENDS ONCE
 * ON A 401. See the ⛔ on [HqApi] for why that is acceptable here (the retry can only fire on a
 * request the server refused before running the handler) and for the rule that nothing in this
 * client may add a retry of its own on top of it.
 *
 * ⚠️ The two operations are separate functions rather than one taking a sealed request, because the
 * response types are genuinely different and a caller that could confuse them would be able to read
 * a write's outcome as a chat reply.
 */
internal class HttpHqApi(private val client: DistrictApiClient) : HqApi {
    override suspend fun hqPrompt(request: HqPromptRequest): ApiResult<HqPromptResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.HQ,
            serializer = HqPromptResponse.serializer(),
            body = request.toJson(HqPromptRequest.serializer()),
        )

    override suspend fun hqConfirm(request: HqConfirmRequest): ApiResult<HqConfirmResponse> =
        client.send(
            method = "POST",
            // ⛔ THE SAME PATH. The route distinguishes a confirm from a prompt by the PRESENCE of
            // the `confirm` key in the body, not by a path or a method — there is no /hq/confirm to
            // point this at, and inventing one would 404.
            segments = DistrictPaths.HQ,
            serializer = HqConfirmResponse.serializer(),
            body = request.toJson(HqConfirmRequest.serializer()),
        )
}

/**
 * Analytics and metered usage: two unrelated routes behind one screen.
 *
 * ⚠️ THE TWO USAGE CALLS SHARE A PATH AND DIFFER ONLY BY QUERY PARAMETERS, which is why the
 * single-month call passes them EXPLICITLY AS NULL rather than omitting the keys. The client
 * DROPS a null-valued query entry (see [DistrictApiClient.get]), so the two call sites read as
 * the same request with the history switch on or off — and the reader can see that the plain
 * read is genuinely `history` absent rather than `history=false`, which the server would also
 * accept but which would be a different claim about the route.
 */
internal class HttpAnalyticsApi(private val client: DistrictApiClient) : AnalyticsApi {

    override suspend fun analytics(
        workspaceId: String,
        range: AnalyticsRange,
    ): ApiResult<AnalyticsResponse> =
        client.get(
            segments = DistrictPaths.ANALYTICS,
            serializer = AnalyticsResponse.serializer(),
            query = mapOf(
                "workspaceId" to workspaceId,
                // ⛔ THE WIRE VALUE, NEVER THE ENUM NAME. An unrecognised timeRange is not an
                // error server-side — it silently serves 7d — so `SEVEN_DAYS` would produce a
                // week of data under a "90 days" heading with no failure anywhere.
                "timeRange" to range.wire,
            ),
        )

    override suspend fun usage(workspaceId: String): ApiResult<UsageResponse> =
        client.get(
            segments = DistrictPaths.WORKSPACE_USAGE,
            serializer = UsageResponse.serializer(),
            query = mapOf(
                "workspaceId" to workspaceId,
                // Dropped, not sent empty — see the ⚠️ on the class.
                "history" to null,
                "months" to null,
            ),
        )

    override suspend fun usageHistory(
        workspaceId: String,
        months: Int,
    ): ApiResult<UsageHistoryResponse> =
        client.get(
            segments = DistrictPaths.WORKSPACE_USAGE,
            // ⛔ A DIFFERENT SERIALIZER FOR THE SAME PATH. `usage` is an object above and an
            // ARRAY here; the switch is `history=true` in the query string and nothing else.
            serializer = UsageHistoryResponse.serializer(),
            query = mapOf(
                "workspaceId" to workspaceId,
                "history" to "true",
                "months" to months.toString(),
            ),
        )
}

/**
 * District Global Intelligence: queue a dossier, or clear one.
 *
 * ⛔ `enrichContact` GOES THROUGH [DistrictApiClient.send], WHICH RE-SENDS ONCE ON A 401, AND IT
 * IS NOT IDEMPOTENT — one request buys one LLM run. See the ⛔ on [DgiApi] for why that retry is
 * acceptable (it can only fire on a request the server refused before running the handler) and
 * for the rule that nothing here may add a retry on top of it.
 *
 * ⚠️ TWO SEPARATE FUNCTIONS OVER TWO SEPARATE PATHS, even though the request bodies are
 * identical. One spends money and the other destroys data; a single function taking a flag would
 * make those one typo apart.
 */
internal class HttpDgiApi(private val client: DistrictApiClient) : DgiApi {
    override suspend fun enrichContact(request: EnrichRequest): ApiResult<EnrichResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.CONTACTS_ENRICH,
            serializer = EnrichResponse.serializer(),
            body = request.toJson(EnrichRequest.serializer()),
        )

    override suspend fun clearContactIntel(
        request: ClearIntelRequest,
    ): ApiResult<ClearIntelResponse> =
        client.send(
            method = "POST",
            // ⚠️ POST, not DELETE, even though this removes data — the route exports POST only.
            // It is not deleting a RESOURCE; it is nulling four columns on one that stays.
            segments = DistrictPaths.CONTACTS_CLEAR_INTEL,
            serializer = ClearIntelResponse.serializer(),
            body = request.toJson(ClearIntelRequest.serializer()),
        )
}

/**
 * The read-only phone-number marketplace.
 *
 * ⛔ NO PURCHASE, RELEASE OR CONFIGURE CALL EXISTS IN THIS CLASS BY DECISION — see [NumbersApi].
 * The server has those routes; adding one here is a product change, not a missing line.
 *
 * ⚠️ THE OPTIONAL FILTERS ARE PASSED AS NULLS RATHER THAN OMITTED AT THE CALL SITE, because
 * [DistrictApiClient.get] DROPS a null-valued query entry. That keeps the request readable as
 * "these are the filters, some unset" instead of hiding the parameter list behind a builder.
 */
internal class HttpNumbersApi(private val client: DistrictApiClient) : NumbersApi {
    override suspend fun searchNumbers(
        workspaceId: String,
        areaCode: String?,
        country: String?,
        type: String?,
        provider: String?,
    ): ApiResult<NumberSearchResponse> =
        client.get(
            segments = DistrictPaths.NUMBERS_SEARCH,
            serializer = NumberSearchResponse.serializer(),
            query = mapOf(
                "workspaceId" to workspaceId,
                "areaCode" to areaCode,
                "country" to country,
                "type" to type,
                "provider" to provider,
            ),
        )

    override suspend fun ownedNumbers(workspaceId: String): ApiResult<OwnedNumbersResponse> =
        client.get(
            segments = DistrictPaths.PROVIDER_NUMBERS,
            serializer = OwnedNumbersResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )
}

/**
 * Device management: three routes under `/api/auth/native/`, all bearing the access token.
 *
 * ⛔ THE BASE URL IS THE ORIGIN, NOT `/api/district`, WHICH IS THE ONLY REASON THIS FITS HERE.
 * [DistrictApiClient] takes a list of segments and appends them to the origin, so
 * `["api","auth","native","devices"]` addresses these routes through exactly the same client —
 * and picks up token acquisition, the single 401 retry and the failure mapping for free. There
 * is nothing district-specific in the client; the name is historical.
 *
 * ⚠️ NO `workspaceId` ON ANY OF THE THREE. These are account-scoped by construction — see
 * [DevicesApi].
 */
internal class HttpDevicesApi(private val client: DistrictApiClient) : DevicesApi {
    override suspend fun devices(): ApiResult<DeviceListResponse> =
        client.get(
            segments = DistrictPaths.NATIVE_DEVICES,
            serializer = DeviceListResponse.serializer(),
        )

    override suspend fun revokeDevice(
        request: DeviceRevokeRequest,
    ): ApiResult<DeviceRevokeResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.NATIVE_DEVICES_REVOKE,
            serializer = DeviceRevokeResponse.serializer(),
            body = request.toJson(DeviceRevokeRequest.serializer()),
        )

    override suspend fun revokeAllDevices(): ApiResult<DeviceRevokeResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.NATIVE_REVOKE_ALL,
            serializer = DeviceRevokeResponse.serializer(),
            // ⛔ AN EMPTY OBJECT, NOT `null`, AND THIS IS AN OKHTTP CONSTRAINT RATHER THAN A
            // SERVER ONE. The route parses nothing and would accept a body-less POST happily —
            // but [DistrictApiClient.send] maps a null body to `Request.method("POST", null)`,
            // which OkHttp REJECTS outright ("method POST must have a request body"). That
            // throws from the request builder, outside the client's IOException handling, so
            // it would surface as a crash rather than an [ApiResult]. `{}` is the smallest
            // thing that is both legal here and ignored there.
            body = EMPTY_BODY,
        )
}

/**
 * Billing: the plan we own, and the invoices Stripe owns.
 *
 * ⛔ NO MUTATION IN THIS CLASS, AND IT IS NOT A GAP TO FILL. `POST /api/billing` cancels
 * subscriptions and changes plans; offering either in-app would breach Google Play's Payments
 * policy. See [BillingApi].
 *
 * ⛔ THE TWO CALLS USE DIFFERENT PATH FAMILIES **AND** DIFFERENT SCOPES, which is why they are two
 * functions rather than one. `workspace/billing` is workspace-scoped under `/api/district/`;
 * `/api/billing` is caller-scoped and takes no parameters at all. Passing a workspace to the second
 * would be a parameter the route ignores, and it would suggest a scope the caller does not have.
 */
internal class HttpBillingApi(private val client: DistrictApiClient) : BillingApi {
    override suspend fun workspaceBilling(
        workspaceId: String,
    ): ApiResult<WorkspaceBillingResponse> =
        client.get(
            segments = DistrictPaths.WORKSPACE_BILLING,
            serializer = WorkspaceBillingResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun stripeBilling(): ApiResult<StripeBilling> =
        client.get(
            // ⛔ NOT UNDER `/api/district/`. Only two sections in this client are: device
            // management (under `/api/auth/native/`) and this one. `/api/district/billing` does
            // not exist and would 404.
            segments = DistrictPaths.BILLING,
            serializer = StripeBilling.serializer(),
        )
}

/**
 * Workspace settings: the hydrating read, and the two merge-safe writes.
 *
 * ⛔ THE READ AND THE WRITES ARE ONE SECTION BECAUSE THE WRITES ARE NOT SAFE WITHOUT THE READ.
 * `workspace/tools` replaces `toolConfig.allowedTools` wholesale, so a save built on anything but
 * a successful `workspace/config` is a deletion wearing a save. Keeping them in one interface is
 * how that dependency stays visible to the next reader — see [ConfigApi].
 *
 * ⛔ BOTH WRITES ARE **PATCH**, NOT POST, and neither echoes what it wrote. The verb is the
 * server's (each route exports PATCH only; a POST would 405) and the empty response is why
 * `WorkspaceConfigRepository` re-reads rather than adopting a body.
 *
 * ⚠️ THE REQUEST BODIES GO THROUGH `toJson`, WHICH DROPS NULLS. That is not tidiness here: for
 * the persona route an omitted key is PRESERVED server-side and an explicit one is WRITTEN, so
 * `explicitNulls = false` is the mechanism by which "send only what changed" actually reaches the
 * wire. See [PersonaPatchRequest].
 */
internal class HttpConfigApi(private val client: DistrictApiClient) : ConfigApi {
    override suspend fun workspaceConfig(workspaceId: String): ApiResult<WorkspaceConfigResponse> =
        client.get(
            segments = DistrictPaths.WORKSPACE_CONFIG,
            serializer = WorkspaceConfigResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun savePersona(
        request: PersonaPatchRequest,
    ): ApiResult<WorkspaceConfigSaveResponse> =
        client.send(
            // ⛔ PATCH. The route exports PATCH only.
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_PERSONA,
            serializer = WorkspaceConfigSaveResponse.serializer(),
            body = request.toJson(PersonaPatchRequest.serializer()),
        )

    override suspend fun saveTools(
        request: ToolsPatchRequest,
    ): ApiResult<WorkspaceConfigSaveResponse> =
        client.send(
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_TOOLS,
            serializer = WorkspaceConfigSaveResponse.serializer(),
            // ⛔ `allowedTools` is non-nullable on the request type, so `explicitNulls = false`
            // cannot drop it — which matters because the route answers 400 for a missing array
            // and this is the one field on the whole surface that is written wholesale.
            body = request.toJson(ToolsPatchRequest.serializer()),
        )

    override suspend fun saveDirectory(
        request: DirectoryPatchRequest,
    ): ApiResult<WorkspaceConfigSaveResponse> =
        client.send(
            // ⛔ PATCH. The route exports PATCH only; a POST would 405.
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_DIRECTORY,
            serializer = WorkspaceConfigSaveResponse.serializer(),
            // ⛔ `callDirectory` IS NON-NULLABLE ON THE REQUEST TYPE, so `explicitNulls = false`
            // cannot drop it. That matters more here than anywhere else on this client: this route
            // treats an ABSENT array exactly like an empty one and wipes the directory with a 200.
            body = request.toJson(DirectoryPatchRequest.serializer()),
        )

    override suspend fun saveRoutingRules(
        request: RoutingRulesRequest,
    ): ApiResult<WorkspaceConfigSaveResponse> =
        client.send(
            // ⛔ POST, NOT PATCH — the one write on this surface that is not a PATCH. The route
            // exports POST only, and the asymmetry is the server's.
            method = "POST",
            segments = DistrictPaths.WORKSPACE_ROUTING_RULES,
            serializer = WorkspaceConfigSaveResponse.serializer(),
            body = request.toJson(RoutingRulesRequest.serializer()),
        )
}

/**
 * The knowledge base: two reads, two writes and a delete.
 *
 * ⛔ `createDocument` GOES THROUGH [DistrictApiClient.send], WHICH RE-SENDS ONCE ON A 401, AND IT
 * IS NOT IDEMPOTENT — one request buys one embedding run over however many chunks the content
 * produced. Same class of endpoint as `messages/send` and HQ's confirm, and the same bounded
 * trade: the retry can only fire on a request the server refused before running the handler.
 *
 * ⚠️ THREE HTTP CONVENTIONS IN ONE SECTION — GET with a query, POST with a body, DELETE with a
 * query and no body — plus a PATCH on the sibling mode route. They are the server's and are
 * mirrored rather than smoothed over.
 */
internal class HttpKnowledgeApi(private val client: DistrictApiClient) : KnowledgeApi {

    override suspend fun knowledgeDocuments(
        workspaceId: String,
    ): ApiResult<KnowledgeListResponse> =
        client.get(
            segments = DistrictPaths.WORKSPACE_KNOWLEDGE,
            serializer = KnowledgeListResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun createDocument(
        request: KnowledgeCreateRequest,
    ): ApiResult<KnowledgeCreateResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.WORKSPACE_KNOWLEDGE,
            serializer = KnowledgeCreateResponse.serializer(),
            body = request.toJson(KnowledgeCreateRequest.serializer()),
        )

    override suspend fun deleteDocument(
        workspaceId: String,
        documentId: String,
    ): ApiResult<KnowledgeDeleteResponse> =
        client.send(
            method = "DELETE",
            segments = DistrictPaths.WORKSPACE_KNOWLEDGE,
            serializer = KnowledgeDeleteResponse.serializer(),
            // ⛔ THE PARAMETER IS SPELLED `documentId`. The route reads exactly that name and
            // answers 400 "Missing documentId" for `id` or `docId` — a spelling mistake here is a
            // client that can never delete anything, and the error names the field rather than the
            // cause.
            query = mapOf("workspaceId" to workspaceId, "documentId" to documentId),
        )

    override suspend fun knowledgeMode(workspaceId: String): ApiResult<KnowledgeModeResponse> =
        client.get(
            segments = DistrictPaths.WORKSPACE_KNOWLEDGE_MODE,
            serializer = KnowledgeModeResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun saveKnowledgeMode(
        request: KnowledgeModePatchRequest,
    ): ApiResult<KnowledgeModeResponse> =
        client.send(
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_KNOWLEDGE_MODE,
            // ⚠️ THE SAME TYPE AS THE READ, because the write ECHOES the stored config
            // (`{success:true, ...config}`) — unlike every workspace-settings save, which answers a bare
            // `{success:true}`. That echo is why this one write needs no re-read.
            serializer = KnowledgeModeResponse.serializer(),
            body = request.toJson(KnowledgeModePatchRequest.serializer()),
        )
}

/**
 * The workspace's carrier accounts: a GET, five PATCHes on the SAME path, and a POST on a sibling.
 *
 * ⛔ [MessagingApi] records the two risks that shape the five writes below.
 *
 * ⛔ FIVE FUNCTIONS, ONE URL, AND THE `action` IS IN THE BODY RATHER THAN THE PATH. There is no
 * `messaging/default` or `messaging/delete` — both would 404 — so the only thing separating a
 * default change from a deletion is a string inside the JSON. That is why each one takes its own
 * request type with the action baked in as a default rather than sharing one body: a caller here
 * cannot pass the wrong action without changing a type.
 *
 * ⛔ THEY CARRY `send`'s SINGLE 401-REFRESH RETRY, like every tenant-scoped write behind
 * `requireWorkspaceRole`. See the ⛔ on [MessagingApi] for why that cannot duplicate the one
 * non-idempotent call among them.
 *
 * ⚠️ THE TEST ROUTE IS A DIFFERENT PATH AND A DIFFERENT VERB (POST on `messaging/test`), and its
 * failure answer is a 200. Nothing about it can be inferred from the five above.
 */
internal class HttpMessagingApi(private val client: DistrictApiClient) : MessagingApi {
    override suspend fun messaging(workspaceId: String): ApiResult<MessagingResponse> =
        client.get(
            segments = DistrictPaths.WORKSPACE_MESSAGING,
            serializer = MessagingResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun saveMessagingAccount(
        request: MessagingAccountRequest,
    ): ApiResult<MessagingAccountSaveResponse> =
        client.send(
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_MESSAGING,
            serializer = MessagingAccountSaveResponse.serializer(),
            // ⛔ `toJson` IS WHAT MAKES A BLANK SECRET MEAN "KEEP". It encodes with
            // `explicitNulls = false`, so a null field is OMITTED — and the route's
            // "keep the stored ciphertext" branch keys on the value being absent or blank. Encoding
            // this body any other way would send explicit nulls and, for the plaintext keys, write
            // them.
            body = request.toJson(MessagingAccountRequest.serializer()),
        )

    override suspend fun setDefaultAccount(
        request: MessagingDefaultRequest,
    ): ApiResult<MessagingDefaultResponse> =
        client.send(
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_MESSAGING,
            serializer = MessagingDefaultResponse.serializer(),
            body = request.toJson(MessagingDefaultRequest.serializer()),
        )

    override suspend fun setChannelDefault(
        request: MessagingChannelDefaultRequest,
    ): ApiResult<MessagingChannelDefaultResponse> =
        client.send(
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_MESSAGING,
            serializer = MessagingChannelDefaultResponse.serializer(),
            body = request.toJson(MessagingChannelDefaultRequest.serializer()),
        )

    override suspend fun deleteMessagingAccount(
        request: MessagingDeleteRequest,
    ): ApiResult<MessagingDefaultResponse> =
        client.send(
            // ⛔ **PATCH**, NOT DELETE, AND NOT A QUERY PARAMETER. Unlike `contacts` and
            // `knowledge`, removal here is an action on the shared PATCH body. A `DELETE` to this
            // path is a 405, and the account id belongs in the JSON.
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_MESSAGING,
            serializer = MessagingDefaultResponse.serializer(),
            body = request.toJson(MessagingDeleteRequest.serializer()),
        )

    override suspend fun saveCreatorCell(
        request: MessagingMetaRequest,
    ): ApiResult<MessagingMetaResponse> =
        client.send(
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_MESSAGING,
            serializer = MessagingMetaResponse.serializer(),
            body = request.toJson(MessagingMetaRequest.serializer()),
        )

    override suspend fun testMessagingCredentials(
        request: MessagingTestRequest,
    ): ApiResult<MessagingTestResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.WORKSPACE_MESSAGING_TEST,
            serializer = MessagingTestResponse.serializer(),
            body = request.toJson(MessagingTestRequest.serializer()),
        )
}

/**
 * Membership CRUD, plus the workspace rename.
 *
 * ⛔ FOUR VERBS ON ONE PATH AND A FIFTH ON A SIBLING. `members` serves GET/POST/PATCH/DELETE, and
 * `rename` is its own route with only a PATCH. Nothing here smooths that over: the DELETE genuinely
 * carries query parameters and no body, and inventing `members/remove` as a POST to make the four
 * look alike would 405.
 *
 * ⛔ THE THREE MEMBERSHIP WRITES GO THROUGH [DistrictApiClient.send], WHICH RE-SENDS ONCE ON A 401.
 * That is safe for all three and worth stating rather than assuming: the add is guarded by a unique
 * constraint that answers 409 (a duplicate delivery is a 409, not a second row), and the role change
 * and the removal are idempotent by nature. None of them spends money. Do not add a retry of any
 * other kind — the rate limit is 20/min per WORKSPACE and is shared across all three.
 */
internal class HttpMembersApi(private val client: DistrictApiClient) : MembersApi {

    override suspend fun members(workspaceId: String): ApiResult<MemberListResponse> =
        client.get(
            segments = DistrictPaths.WORKSPACE_MEMBERS,
            serializer = MemberListResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun addMember(
        request: MemberAddRequest,
    ): ApiResult<MemberMutationResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.WORKSPACE_MEMBERS,
            serializer = MemberMutationResponse.serializer(),
            body = request.toJson(MemberAddRequest.serializer()),
        )

    override suspend fun changeMemberRole(
        request: MemberRoleRequest,
    ): ApiResult<MemberMutationResponse> =
        client.send(
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_MEMBERS,
            serializer = MemberMutationResponse.serializer(),
            body = request.toJson(MemberRoleRequest.serializer()),
        )

    override suspend fun removeMember(
        workspaceId: String,
        email: String,
    ): ApiResult<MemberMutationResponse> =
        client.send(
            method = "DELETE",
            segments = DistrictPaths.WORKSPACE_MEMBERS,
            // ⛔ THE SAME TYPE AS THE OTHER TWO WRITES EVEN THOUGH THIS BODY HAS NO `member` KEY.
            // `MemberMutationResponse.member` is nullable precisely so a successful REMOVAL decodes
            // — the one response a client must not fail on, since the row really is gone.
            serializer = MemberMutationResponse.serializer(),
            // ⛔ QUERY PARAMETERS, NO BODY. The route reads `searchParams.get("workspaceId")` and
            // `searchParams.get("email")`; a body here would be ignored and both reads would answer
            // 400, which reads as a broken client rather than as the wrong convention.
            query = mapOf("workspaceId" to workspaceId, "email" to email),
        )

    override suspend fun renameWorkspace(
        request: WorkspaceRenameRequest,
    ): ApiResult<RenameResponse> =
        client.send(
            method = "PATCH",
            segments = DistrictPaths.WORKSPACE_RENAME,
            // ⚠️ ECHOES THE STORED, TRIMMED NAME — see MembersApi.renameWorkspace. That echo is why
            // this write is not followed by a re-read.
            serializer = RenameResponse.serializer(),
            body = request.toJson(WorkspaceRenameRequest.serializer()),
        )
}

/**
 * Every endpoint path, in one place.
 *
 * ⚠️ Lists of SEGMENTS, not strings. OkHttp's `addPathSegments` splits on "/" instead of encoding it,
 * so interpolating an id into a path template let the id inject segments — and OkHttp then resolves
 * "..". A call id of `a/../../admin` turned `api/district/calls/{id}/transcript` into
 * `/api/district/admin/transcript`. Building from a list means every element is encoded as exactly one
 * segment.
 */
/**
 * Rooms: the join credential, and the meetings that come out of them.
 *
 * ⛔ THREE ROUTES ACROSS TWO PATH FAMILIES, AND THE VERBS ARE NOT UNIFORM. The token is a POST with
 * a body under `calls/`; both meeting reads are GETs with a `workspaceId` query under `meetings/`.
 * That is the server's shape — see [DistrictPaths.CALLS_TOKEN] for why the token is not under a
 * rooms path — and normalising it here would produce a 404 or a 405 rather than tidiness.
 *
 * ⛔ THE TOKEN POST GOES THROUGH [DistrictApiClient.send], WHICH RE-SENDS ONCE ON A 401, AND THAT
 * IS SAFE HERE FOR A REASON WORTH STATING: the route persists nothing. It signs a JWT and, for a
 * non-viewer, an invite — so a duplicate delivery mints a second capability rather than creating a
 * second anything. Both are short-lived and bound to the same room. It spends no money and books
 * no resource.
 */
internal class HttpMeetingsApi(private val client: DistrictApiClient) : MeetingsApi {

    override suspend fun roomToken(request: RoomTokenRequest): ApiResult<RoomTokenResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.CALLS_TOKEN,
            serializer = RoomTokenResponse.serializer(),
            body = request.toJson(RoomTokenRequest.serializer()),
        )

    override suspend fun meetings(workspaceId: String): ApiResult<List<MeetingSummary>> =
        client.get(
            segments = DistrictPaths.MEETINGS,
            // ⛔ A LIST SERIALIZER, NOT AN ENVELOPE'S — this route is `NextResponse.json(results)`,
            // a bare array, the same shape as the call log and unlike almost everything else here.
            serializer = ListSerializer(MeetingSummary.serializer()),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun meetingDetail(
        workspaceId: String,
        meetingId: String,
    ): ApiResult<MeetingDetail> =
        client.get(
            // ⚠️ The id is appended as its own list element, so it is encoded as exactly ONE path
            // segment — the traversal guard [DistrictPaths] exists for. A meeting id is a uuid, but
            // it arrives here from a nav argument that survived process death.
            segments = DistrictPaths.MEETINGS + meetingId,
            serializer = MeetingDetail.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )
}

/**
 * The outbound softphone.
 *
 * ⛔ ONE FUNCTION, ITS OWN CLASS, AND THE ISOLATION IS THE POINT RATHER THAN THE SYMMETRY. This is
 * the only implementation in this file that spends money and rings a telephone, and keeping it out
 * of [HttpCallsApi] means the class every call-log screen depends on cannot place a call — not by
 * convention, but because the function is not on the interface it was handed.
 *
 * ⚠️ IT GOES THROUGH [DistrictApiClient.send], WHICH RE-SENDS ONCE ON A 401, and that is safe for a
 * reason worth stating rather than assuming. A 401 is produced by the auth guard BEFORE the handler
 * runs, so the refused attempt provably never reached the carrier — no row was written and no
 * number was dialled. The retry is not a general "this route is idempotent" claim: it is not, and
 * nothing may retry it after a 5xx, where the dial may already have gone out.
 */
internal class HttpDialApi(private val client: DistrictApiClient) : DialApi {

    override suspend fun dial(request: DialRequest): ApiResult<DialResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.CALLS_DIAL,
            serializer = DialResponse.serializer(),
            body = request.toJson(DialRequest.serializer()),
        )
}

/**
 * Answering an inbound call.
 *
 * ⛔ ITS OWN CLASS RATHER THAN A FUNCTION ON [HttpDialApi], MIRRORING THE SERVER'S OWN SPLIT. The
 * answer route was created separately from `calls/token` because folding a third case into that
 * branch is how a supervisor token was once minted for a primary participant, and the agent
 * unsubscribed the human's microphone. Keeping the two apart here means the isolation survives on
 * this side too: the class a ringing screen depends on cannot place a call, and the dialler's
 * cannot answer one.
 *
 * ⚠️ THE CALL ID IS A PATH SEGMENT AND GOES THROUGH THE SEGMENT LIST, never interpolation — see the
 * ⛔ on [DistrictApiClient.get] for the `a/../../admin` case that made that rule.
 */
internal class HttpInboundCallApi(private val client: DistrictApiClient) : InboundCallApi {

    override suspend fun answerCall(
        callId: String,
        request: CallAnswerRequest,
    ): ApiResult<CallAnswerResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.callAnswer(callId),
            serializer = CallAnswerResponse.serializer(),
            body = request.toJson(CallAnswerRequest.serializer()),
        )
}

/**
 * Push registration.
 *
 * ⛔ NEITHER CALL CARRIES A DEVICE ID, AND THE OMISSION IS THE SECURITY PROPERTY RATHER THAN a
 * saving. The server recovers the installation from the bearer; a body field would let a caller
 * name somebody else's row, which on `register` is the upsert key. See [PushApi].
 *
 * ⚠️ `unregisterPushToken` SENDS `{}` FOR AN OKHTTP REASON, NOT A SERVER ONE — the same constraint
 * and the same constant [HttpDevicesApi]'s `revokeAllDevices` uses.
 */
internal class HttpPushApi(private val client: DistrictApiClient) : PushApi {

    override suspend fun registerPushToken(
        request: PushTokenRegisterRequest,
    ): ApiResult<PushRegistrationResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.DEVICES_REGISTER,
            serializer = PushRegistrationResponse.serializer(),
            body = request.toJson(PushTokenRegisterRequest.serializer()),
        )

    override suspend fun unregisterPushToken(): ApiResult<PushRegistrationResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.DEVICES_UNREGISTER,
            serializer = PushRegistrationResponse.serializer(),
            body = EMPTY_BODY,
        )
}

/**
 * The automation monitor: three reads and one toggle.
 *
 * ⛔ THE TOGGLE IS A **PATCH ON THE COLLECTION PATH**, NOT ON A WORKFLOW'S OWN. There is no
 * `/api/district/workflows/{id}` route at all — the id travels in the BODY, and a client that
 * built a per-resource path would 404. The same path also serves GET, POST and DELETE; only two of
 * those four are reachable from here.
 *
 * ⛔ AND THE PATCH BODY CARRIES ONLY `active`. The route accepts four more fields and writes
 * `actions` WHOLESALE, so a body assembled from anything wider is one empty array away from
 * deleting every action a workflow has, with a 200. See [WorkflowToggleRequest].
 *
 * ⚠️ THE CAMPAIGN READ IS A DIFFERENT PATH FAMILY (`workspace/campaign-status`) AND IS GROUPED
 * HERE ANYWAY, the same call [HttpAnalyticsApi] and [HttpBillingApi] make: one SCREEN needs it,
 * and the alternative — filing it under `HttpConfigApi` — would put a viewer-readable route behind
 * an interface whose every other call excludes viewers.
 */
internal class HttpWorkflowsApi(private val client: DistrictApiClient) : WorkflowsApi {

    override suspend fun workflows(workspaceId: String): ApiResult<WorkflowListResponse> =
        client.get(
            segments = DistrictPaths.WORKFLOWS,
            serializer = WorkflowListResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun workflowRuns(
        workspaceId: String,
        workflowId: String,
        limit: Int,
        offset: Int,
    ): ApiResult<WorkflowRunsResponse> =
        client.get(
            segments = DistrictPaths.WORKFLOW_RUNS,
            serializer = WorkflowRunsResponse.serializer(),
            // ⛔ `workflowId` IS NOT OPTIONAL — its absence is a 400 raised before the role guard,
            // and this client sends it always. It is a query parameter rather than a path segment
            // because the route reads `searchParams`; a `/runs/{id}` path would 404.
            query = mapOf(
                "workspaceId" to workspaceId,
                "workflowId" to workflowId,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ),
        )

    override suspend fun setWorkflowActive(
        request: WorkflowToggleRequest,
    ): ApiResult<WorkflowToggleResponse> =
        client.send(
            // ⛔ PATCH. The route exports GET/POST/PATCH/DELETE and a POST here would CREATE a
            // workflow rather than update one — the two verbs on one path are not interchangeable
            // and the failure would be a new row, not an error.
            method = "PATCH",
            segments = DistrictPaths.WORKFLOWS,
            serializer = WorkflowToggleResponse.serializer(),
            // ⛔ `active` IS NON-NULLABLE ON THE REQUEST TYPE, so `explicitNulls = false` cannot
            // drop it. That matters: the route decides what to write with `"active" in body`, and
            // a dropped key is not "leave it alone" but "nothing to update", a 400.
            body = request.toJson(WorkflowToggleRequest.serializer()),
        )

    override suspend fun campaignStatus(workspaceId: String): ApiResult<CampaignStatusResponse> =
        client.get(
            segments = DistrictPaths.WORKSPACE_CAMPAIGN_STATUS,
            serializer = CampaignStatusResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun setCampaignEnabled(
        request: CampaignPauseRequest,
    ): ApiResult<CampaignStatusResponse> =
        client.send(
            method = "PATCH",
            // ⛔ THE STATUS PATH, NOT `WORKSPACE_CONFIG` AND NOT A `campaign-settings` SIBLING.
            // The settings route rebuilds all three SDR fields from the body, so the same JSON
            // sent one path over would answer 200 and wipe the goal text. Same object, same verb,
            // opposite outcome — see [DistrictApi.setCampaignEnabled].
            segments = DistrictPaths.WORKSPACE_CAMPAIGN_STATUS,
            // ⚠️ THE GET'S SERIALIZER. The route answers in the read's shape, derived from what it
            // wrote, which is what lets the caller adopt the reply instead of re-reading.
            serializer = CampaignStatusResponse.serializer(),
            // ⛔ `infiniteSdrEnabled` IS NON-NULLABLE ON THE REQUEST TYPE, so `explicitNulls =
            // false` cannot drop it. A dropped key here is not "leave it alone": the route reads
            // `typeof x !== "boolean"` and answers 400.
            body = request.toJson(CampaignPauseRequest.serializer()),
        )
}

/**
 * The workspace's booking pages: two JSON calls and one that is not JSON at all.
 *
 * ⛔ NEITHER READ IS ENVELOPED, so nothing here checks a `success` flag and nothing may start to —
 * see [SchedulingApi]. The DTOs' required fields are what reject a `{}` body.
 *
 * ⛔ AND `schedulingSsoTarget` GOES THROUGH [DistrictApiClient.redirectTarget], NOT [DistrictApiClient.get].
 * The route answers a 302 whose `Location` is a one-time sign-in URL; `get` would let OkHttp follow
 * it, which SPENDS the single-use token on a transport nobody can see and leaves the browser with a
 * credential the far end has already claimed. It is the same helper the call-recording endpoint
 * uses and the opposite consequence: there, following merely wastes bandwidth.
 */
internal class HttpSchedulingApi(private val client: DistrictApiClient) : SchedulingApi {
    override suspend fun schedulingStatus(
        workspaceId: String,
    ): ApiResult<SchedulingStatusResponse> =
        client.get(
            segments = DistrictPaths.SCHEDULING_STATUS,
            serializer = SchedulingStatusResponse.serializer(),
            // ⛔ REQUIRED, unlike the overview's optional one. This client holds no selection
            // cookie, so the server's own fallback would report on whichever workspace its
            // membership listing happens to name first — and here the wrong answer is a booking
            // URL belonging to another tenancy.
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun enableScheduling(
        request: SchedulingEnableRequest,
    ): ApiResult<SchedulingEnableResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.SCHEDULING_ENABLE,
            serializer = SchedulingEnableResponse.serializer(),
            // ⚠️ THE BODY, not a query parameter. The route reads `req.json()` first and falls
            // back to the query string, so both work — matching the web client keeps one capture
            // readable as the other.
            body = request.toJson(SchedulingEnableRequest.serializer()),
        )

    override suspend fun schedulingSsoTarget(
        workspaceId: String,
        next: String,
    ): ApiResult<String> =
        client.redirectTarget(
            segments = DistrictPaths.SCHEDULING_SSO,
            query = mapOf("workspaceId" to workspaceId, "next" to next),
        )

    /**
     * ⛔ [DistrictApiClient.send], NOT [DistrictApiClient.redirectTarget], WHICH IS THE OPPOSITE
     * CHOICE FROM ITS PREDECESSOR ONE FUNCTION ABOVE. This route answers a 200 with a JSON body
     * rather than a 302 with a `Location`, so redirects are irrelevant to it and `redirectTarget`
     * would treat the 200 as "not a redirect" and fall through to the error mapping — a working
     * hand-off reported as a failure.
     *
     * ⚠️ THE WORKSPACE TRAVELS IN THE BODY, matching [enableScheduling]. This route reads
     * `req.json()` and answers 400 without a `workspaceId`; there is no query-string fallback.
     */
    override suspend fun schedulingHandOff(
        request: SchedulingHandOffRequest,
    ): ApiResult<SchedulingHandOffResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.SCHEDULING_HANDOFF,
            serializer = SchedulingHandOffResponse.serializer(),
            body = request.toJson(SchedulingHandOffRequest.serializer()),
        )
}

internal object DistrictPaths {
    private val DISTRICT = ApiRoots.DISTRICT
    private val WORKSPACE = DISTRICT + "workspace"
    val WORKSPACE_LIST = WORKSPACE + "list"

    /**
     * ⚠️ UNDER THE WORKSPACE PATH FAMILY, NOT BESIDE [ANALYTICS]. The two routes back one screen
     * but they are unrelated server-side — usage is a workspace-administration read and analytics
     * is a telephony one — and inventing `/api/district/usage` to make them look like siblings
     * would 404.
     */
    val WORKSPACE_USAGE = WORKSPACE + "usage"

    /**
     * ⚠️ A SIBLING OF [WORKSPACE_USAGE], AND NOT TO BE CONFUSED WITH [BILLING]. This one is the
     * District, workspace-scoped, Stripe-free plan read; [BILLING] is the caller-scoped Stripe
     * detail at the top level of the API. One screen reads both, and pointing either at the
     * other's path would answer a plausible-looking body for the wrong scope.
     */
    val WORKSPACE_BILLING = WORKSPACE + "billing"

    /**
     * ⛔ THE READ THAT EXISTS FOR THIS CLIENT AND FOR NOTHING ELSE, and the two writes it guards.
     * The web settings page never needed [WORKSPACE_CONFIG] — it is a server component that
     * hydrates its forms from the row during render — so this is the one path in this object that
     * has no browser caller at all. Pointing a form at either write without going through it
     * first is how a wholesale-replace route becomes a delete.
     *
     * ⚠️ ALL THREE ARE SIBLINGS UNDER [WORKSPACE], despite doing very different things. They are
     * one group here because they are one screen's contract, not because the server groups them.
     */
    val WORKSPACE_CONFIG = WORKSPACE + "config"
    val WORKSPACE_PERSONA = WORKSPACE + "persona"
    val WORKSPACE_TOOLS = WORKSPACE + "tools"

    /**
     * ⛔ THE OTHER TWO WHOLESALE-REPLACE WRITES. `directory` is a PATCH and `routing-rules` is a
     * POST, which is the server's asymmetry rather than a choice available here — pointing either
     * at the other's verb would 405.
     */
    val WORKSPACE_DIRECTORY = WORKSPACE + "directory"
    val WORKSPACE_ROUTING_RULES = WORKSPACE + "routing-rules"

    /**
     * ⚠️ TWO SEPARATE ROUTES, NOT A NESTED ONE. The document store is `workspace/knowledge` and the
     * source choice is its SIBLING `workspace/knowledge-mode` — not `workspace/knowledge/mode`,
     * which does not exist. The route's own header explains the split: `toolConfig` gates which
     * tools may be called, while the mode decides where a question is ANSWERED.
     */
    val WORKSPACE_KNOWLEDGE = WORKSPACE + "knowledge"
    val WORKSPACE_KNOWLEDGE_MODE = WORKSPACE + "knowledge-mode"

    /**
     * ⛔ ONE PATH, TWO VERBS, AND THE PATCH IS FIVE DIFFERENT OPERATIONS. GET reads the redacted
     * account list; PATCH creates, edits, re-points the default, sets a per-channel sender, writes
     * the creator cell number, and DELETES an account — dispatched on an `action` string in the
     * body. There are no per-action sub-paths: `messaging/default` and `messaging/delete` do not
     * exist and would 404.
     *
     * ⚠️ [WORKSPACE_MESSAGING_TEST] IS A SIBLING ROUTE, NOT A SIXTH ACTION. It is a POST, it takes
     * UNSAVED plaintext credentials, and it answers a failed check with a 200 — none of which the
     * PATCH does.
     */
    val WORKSPACE_MESSAGING = WORKSPACE + "messaging"
    val WORKSPACE_MESSAGING_TEST = WORKSPACE_MESSAGING + "test"

    /**
     * ⛔ NOT `workspace/campaign-settings`, WHICH IS A DIFFERENT ROUTE AND A DESTRUCTIVE ONE. The
     * PATCH on `campaign-settings` writes all three SDR fields unconditionally, so a partial body
     * sent to it WIPES the goal text and resets the batch size to 1 with a 200. `campaign-status`
     * is the read that exists so a native client never has to touch it — and it is also the only
     * one of the two that admits `viewer`. One character of path difference separates a monitor
     * from a data loss.
     */
    val WORKSPACE_CAMPAIGN_STATUS = WORKSPACE + "campaign-status"

    /**
     * ⛔ ONE PATH, FOUR VERBS, AND ITS SIBLING IS A SEPARATE ROUTE. The roster lives at
     * `workspace/members` (GET/POST/PATCH/DELETE) and the display name at `workspace/rename` — NOT
     * `workspace/members/rename` and not a `name` field on the members PATCH, both of which would
     * 404 or be ignored. They are grouped here because one screen owns both, in the same way
     * [WORKSPACE_KNOWLEDGE] and [WORKSPACE_KNOWLEDGE_MODE] are.
     *
     * ⚠️ AND THEIR ROLE GUARDS DIFFER: the members WRITES are agency-only while `rename` admits
     * agency and client, so a shared path would also have implied a shared guard.
     */
    val WORKSPACE_MEMBERS = WORKSPACE + "members"
    val WORKSPACE_RENAME = WORKSPACE + "rename"

    /**
     * ⛔ NOT UNDER [DISTRICT], AND THE PREFIX IS NOT INTERCHANGEABLE — the same shape as
     * [NATIVE_DEVICES]. `/api/billing` is caller-scoped and predates the district namespace;
     * `/api/district/billing` does not exist and would 404. See [WORKSPACE_BILLING] for the one
     * that IS a district route.
     */
    val BILLING = ApiRoots.BILLING

    /**
     * ⚠️ THE MARKETPLACE'S TWO ROUTES ARE NOT SIBLINGS, WHICH LOOKS LIKE AN ACCIDENT AND IS NOT
     * ONE TO "TIDY". Searching available inventory lives at `workspace/numbers/search` while
     * listing what the workspace already owns lives at `workspace/provider/numbers` — different
     * path families for the same screen. Inventing `workspace/numbers/owned` to make them match
     * would 404.
     */
    val NUMBERS_SEARCH = WORKSPACE + "numbers" + "search"
    val PROVIDER_NUMBERS = WORKSPACE + "provider" + "numbers"
    val OVERVIEW = DISTRICT + "overview"
    val ANALYTICS = DISTRICT + "analytics"
    val CALLS = DISTRICT + "calls"

    /**
     * ⛔ THE ROOM-JOIN TOKEN LIVES UNDER `calls`, AND THAT IS THE SERVER'S PATH RATHER THAN A
     * MISFILING TO CORRECT. `/api/district/rooms/token` does not exist and would 404; the route
     * predates standalone rooms and grew the `meet_`/`video_` branch later, which is why its own
     * body parameter is called `roomName` even when it is a `Call.id`. Grouped with [CALLS] here
     * because that is where it is; grouped with meetings in [MeetingsApi] because that is what it
     * is for.
     */
    val CALLS_TOKEN = CALLS + "token"

    /**
     * ⛔ A SIBLING OF [CALLS_TOKEN] AND EMPHATICALLY NOT THE SAME ROUTE, even though both mint a
     * LiveKit token under `calls/`. `token` signs a credential for a room that already exists and
     * persists nothing; `dial` CREATES a call — it writes a `Call` row, tells the carrier to ring a
     * telephone, and spends the workspace's minutes. Pointing the softphone at `calls/token` would
     * answer 200 with a token for a room nobody is in, and the operator would sit in silence
     * waiting for a call that was never placed.
     *
     * ⚠️ `calls/outbound` IS THE THIRD ROUTE IN THIS FAMILY AND THIS CLIENT MUST NOT CALL IT. That
     * one is the AI campaign dialer: it creates a `call_` room the voice agent joins and speaks in.
     * A human dialling through it would find an agent on their own line.
     */
    val CALLS_DIAL = CALLS + "dial"

    /**
     * ⛔ A FUNCTION RATHER THAN A CONSTANT, BECAUSE THE ID SITS IN THE MIDDLE OF THE PATH. Every
     * other per-call route appends the id LAST (`calls/{id}`, `calls/{id}/transcript`); this one is
     * `calls/{id}/answer`, so a caller building it by hand is the shape that invites string
     * interpolation — which OkHttp splits on `/` and then resolves `..` through. One segment per
     * element, here, once.
     *
     * ⚠️ AND IT IS **NOT** A SIBLING OF [CALLS_DIAL] IN MEANING. `dial` places a call this app owns;
     * `answer` joins one that already exists, on a room the SIP bridge created. They share a path
     * family and nothing else.
     */
    fun callAnswer(callId: String): List<String> = CALLS + callId + "answer"

    /**
     * ⛔ UNDER [DISTRICT], WHICH IS THE OPPOSITE PREFIX FROM [NATIVE_DEVICES] DESPITE THE WORD
     * "devices" APPEARING IN BOTH. Push registration is a district resource and sits behind
     * proxy.ts's default-deny middleware as well as the route's own `requireAuth`; SESSION
     * management is `/api/auth/native/devices/…`, a PUBLIC prefix where the route guard is the
     * whole of the access control. `/api/auth/native/devices/register` and
     * `/api/district/devices/revoke` both 404, and each reads as a broken client.
     *
     * ⚠️ `register` AND `unregister` ARE SIBLINGS, not a verb on one path. There is no
     * `devices` collection route under the district prefix at all.
     */
    private val DEVICES = DISTRICT + "devices"
    val DEVICES_REGISTER = DEVICES + "register"
    val DEVICES_UNREGISTER = DEVICES + "unregister"

    /**
     * ⚠️ A TOP-LEVEL DISTRICT ROUTE, NOT A `workspace/` ONE, even though it takes a `workspaceId`
     * query parameter like the workspace family does. `workspace/meetings` would 404. The detail
     * route is this path plus the id — there is no `meetings/detail` segment.
     */
    val MEETINGS = DISTRICT + "meetings"

    /**
     * ⛔ ONE PATH, FOUR VERBS, AND ONLY TWO OF THEM ARE REACHABLE FROM THIS CLIENT — the same
     * shape as [WORKSPACE_MEMBERS]. GET lists and PATCH toggles; POST creates a workflow and
     * DELETE removes one, and neither is called here (a workflow's actions send SMS, send email,
     * register a DNC entry or POST a webhook). ⛔ There is NO per-workflow path: the id travels in
     * the PATCH body and in the runs query, so `workflows/{id}` would 404.
     *
     * ⚠️ `runs` IS A CHILD OF THIS PATH but takes BOTH ids as query parameters, not as segments.
     */
    val WORKFLOWS = DISTRICT + "workflows"
    val WORKFLOW_RUNS = WORKFLOWS + "runs"

    /**
     * ⛔ A TOP-LEVEL DISTRICT FAMILY WITH THREE SIBLING ROUTES AND NO COLLECTION ROUTE OF ITS OWN.
     * `/api/district/scheduling` does not exist and would 404; every call names one of the three
     * leaves below. `workspace/scheduling` would 404 too, despite each of them taking a
     * `workspaceId` the way the `workspace/` family does.
     *
     * ⛔ [SCHEDULING_SSO] IS NOT A JSON ROUTE, AND THAT IS THE ONE THING TO KNOW BEFORE TOUCHING
     * IT. It answers a **302** whose `Location` carries a 60-second single-use JWT, so it is
     * fetched through `redirectTarget` (redirects OFF) and the header is handed straight to a
     * browser. Pointing an ordinary `get` at it would follow the redirect and SPEND the token.
     *
     * ⚠️ `scheduling/webhook/{workspaceId}` IS THE FOURTH ROUTE IN THIS FAMILY AND IS ABSENT HERE
     * ON PURPOSE: the scheduler calls it, this client never does.
     */
    private val SCHEDULING = DISTRICT + "scheduling"
    val SCHEDULING_STATUS = SCHEDULING + "status"
    val SCHEDULING_ENABLE = SCHEDULING + "enable"
    val SCHEDULING_SSO = SCHEDULING + "sso"

    /**
     * ⛔ THE FIFTH ROUTE IN THIS FAMILY AND THE ONE THAT REPLACES [SCHEDULING_SSO]. It is an
     * ordinary JSON POST — no redirect, no `Location`, no `next=/admin/` — and its 200 carries a
     * single-use 60-second code destined for a browser sheet. `handoff` is spelled without a
     * hyphen, matching the dashboard route it mints a link to (`/dashboard/handoff`).
     */
    val SCHEDULING_HANDOFF = SCHEDULING + "handoff"
    val CONTACTS = DISTRICT + "contacts"
    val CONTACTS_ENRICH = CONTACTS + "enrich"
    val CONTACTS_CLEAR_INTEL = CONTACTS + "clear-intel"
    val CONVERSATIONS = DISTRICT + "conversations"
    val TIMELINE = DISTRICT + "timeline"
    private val MESSAGES = DISTRICT + "messages"
    val MESSAGES_UNREAD_COUNT = MESSAGES + "unread-count"
    val MESSAGES_SEND = MESSAGES + "send"
    val MESSAGES_MARK_READ = MESSAGES + "mark-read"

    /** The multipart attachment upload. Its response url is what [MESSAGES_SEND] accepts. */
    val MESSAGES_MEDIA = MESSAGES + "media"

    /**
     * ⛔ PLURAL IS PERSISTENCE, SINGULAR IS THE BILLED GENERATOR, AND THEY ARE ONE LETTER APART.
     * `messages/drafts` (GET/PUT/DELETE) stores an unsent reply and is cheap and idempotent;
     * `messages/draft` (POST) runs a Vertex generation and is capped at 20/min per workspace.
     * Autosave pointed at the singular path would bill a model call on every debounce, and
     * nothing about the name would suggest it.
     */
    val MESSAGES_DRAFTS = MESSAGES + "drafts"
    val MESSAGES_DRAFT = MESSAGES + "draft"
    val HQ = DISTRICT + "hq"

    /**
     * ⛔ NOT UNDER [DISTRICT], AND THE PREFIX IS NOT INTERCHANGEABLE. Device management lives
     * under `/api/auth/native/` — the same family as token exchange and refresh — because a
     * native session is an AUTH object rather than a district resource. `/api/district/devices`
     * does not exist and would 404.
     *
     * ⚠️ `revoke-all` IS A SIBLING OF `devices`, NOT A CHILD OF IT. The per-device revoke is
     * `devices/revoke`; the account-wide one is `revoke-all`, one level up. The asymmetry is
     * the server's and mirroring it here is the only option — inventing `devices/revoke-all`
     * to make them look like a pair would 404.
     */
    private val NATIVE_AUTH = ApiRoots.NATIVE_AUTH
    val NATIVE_DEVICES = NATIVE_AUTH + "devices"
    val NATIVE_DEVICES_REVOKE = NATIVE_DEVICES + "revoke"
    val NATIVE_REVOKE_ALL = NATIVE_AUTH + "revoke-all"
}

/** See the ⛔ at `revokeAllDevices`: the smallest body OkHttp will let a POST carry. */
private val EMPTY_BODY: JsonElement = JsonObject(emptyMap())
