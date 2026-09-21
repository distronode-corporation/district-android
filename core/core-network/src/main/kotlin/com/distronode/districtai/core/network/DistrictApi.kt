package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.AiDraftRequest
import com.distronode.districtai.core.model.AiDraftResponse
import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.core.model.ClearIntelResponse
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.WorkspaceBillingResponse
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
import com.distronode.districtai.core.model.CallDetailResponse
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.model.CallTranscriptResponse
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
import com.distronode.districtai.core.model.ToolsPatchRequest
import com.distronode.districtai.core.model.WorkspaceConfigResponse
import com.distronode.districtai.core.model.WorkspaceConfigSaveResponse
import com.distronode.districtai.core.model.WorkspaceListResponse
import com.distronode.districtai.core.model.CampaignStatusResponse
import com.distronode.districtai.core.model.WorkflowListResponse
import com.distronode.districtai.core.model.WorkflowRunsResponse
import com.distronode.districtai.core.model.WorkflowToggleResponse

/**
 * The typed endpoint surface, **split per dashboard section**.
 *
 * ⛔ SPLIT BECAUSE ONE INTERFACE DOES NOT SCALE TO THIS PRODUCT. Two sections in, the combined
 * interface already hit detekt's 11-function ceiling — and there are seventeen more sections to build,
 * several with a create/update/delete triple of their own. A single god-interface would end up at
 * fifty-plus functions that every test fake has to stub. Splitting per section means a screen depends
 * on the one slice it uses, and a fake only implements that slice.
 *
 * [DistrictApi] remains as the composed view for the wiring layer, and declares nothing itself — so
 * it stays at zero functions no matter how many sections exist.
 */
interface DistrictApi :
    WorkspaceApi,
    OverviewApi,
    CallsApi,
    ContactsApi,
    InboxApi,
    MessageDraftsApi,
    HqApi,
    AnalyticsApi,
    DgiApi,
    NumbersApi,
    DevicesApi,
    BillingApi,
    ConfigApi,
    KnowledgeApi,
    MessagingApi,
    MembersApi,
    MeetingsApi,
    DialApi,
    InboundCallApi,
    PushApi,
    WorkflowsApi,
    SchedulingApi

/** Workspaces the signed-in user may operate on. */
interface WorkspaceApi {
    /**
     * ⚠️ Takes no `workspaceId` — it is USER-scoped, and is the one district route guarded by
     * `requireAuth` rather than `requireWorkspaceRole`. An account with no workspaces gets a normal
     * empty list here, whereas a workspace-scoped route answers 404 "User has no workspace".
     *
     * Can fail with [ApiResult.RegionsDegraded], which is NOT an empty account.
     */
    suspend fun workspaceList(): ApiResult<WorkspaceListResponse>
}

/** The dashboard landing screen. */
interface OverviewApi {
    /**
     * @param workspaceId ⚠️ Passing null is legal and makes the SERVER choose — it falls back to the
     *   first workspace of its own membership listing. Prefer passing it: this client holds no
     *   selection cookie, so the server's fallback cannot know which workspace the user picked, and
     *   omitting it on a multi-workspace account silently reports on the wrong one.
     */
    suspend fun overview(workspaceId: String?): ApiResult<OverviewResponse>
}

/** The call log and its per-call reads. */
interface CallsApi {
    /**
     * One page of the call log, newest first.
     *
     * ⛔ RETURNS A BARE JSON ARRAY WITH NO PAGINATION METADATA — no total, no `hasMore`, no cursor. End
     * of list can only be inferred from a short page. Do not add a synthetic wrapper; the endpoint
     * genuinely has none.
     *
     * ⚠️ Offset paging over a live feed is not stable: ordering is a fixed `createdAt desc`, so a call
     * arriving mid-scroll shifts every window and the SAME call can be returned twice. See
     * `OffsetPagingSource`, which deduplicates because a duplicate key crashes a lazy list.
     *
     * @param limit clamped server-side to 100. A non-positive or non-numeric value falls back to the
     *   server's default of 10 rather than being clamped up, so passing 0 quietly yields ten rows.
     * @param offset ⚠️ Deliberately UNCAPPED server-side, so a large value is a slow query rather than
     *   a wrong page.
     */
    suspend fun calls(workspaceId: String, limit: Int, offset: Int): ApiResult<List<CallSummary>>

    /**
     * One call, in exactly the shape a feed row has.
     *
     * ⚠️ Needed because a detail screen must survive process death and be openable by a push deep
     * link, where the only thing the app holds is an id.
     *
     * ⚠️ A 404 does NOT mean the id was malformed: the server reads by id and checks ownership
     * afterwards so another tenant's id is indistinguishable from a missing one.
     */
    suspend fun callDetail(workspaceId: String, callId: String): ApiResult<CallDetailResponse>

    /**
     * A single call's transcript, fetched on demand.
     *
     * ⚠️ Separate from the feed because transcripts are large: the contact timeline used to embed them
     * and they dominated its payload. An absent transcript is the empty string, not null.
     */
    suspend fun callTranscript(workspaceId: String, callId: String): ApiResult<CallTranscriptResponse>

    /**
     * Resolve a playable URL for a call's recording.
     *
     * ⛔ THE SERVER ANSWERS 302, NOT JSON, and the client deliberately does not follow it — following
     * it would stream the whole audio file through this process to learn its address.
     *
     * ⚠️ The URL is SHORT-LIVED (a presigned object URL) so it must be resolved at playback time and
     * never cached. A call with no recording answers [ApiResult.NotFound].
     */
    suspend fun callRecordingUrl(workspaceId: String, callId: String): ApiResult<String>
}

/**
 * The CRM.
 *
 * ⛔ THE THREE MUTATIONS USE THREE DIFFERENT HTTP CONVENTIONS — POST+body, PATCH+body, DELETE+query —
 * and all three exclude `viewer` server-side.
 */
interface ContactsApi {
    /**
     * One page of the CRM, newest first.
     *
     * ⚠️ Unlike the calls feed, the response carries a real `total`, so end-of-list is KNOWN rather
     * than inferred. The server clamps `limit` to 100 and echoes what it actually applied.
     *
     * ⚠️ Ordering is `createdAt desc` with `id` as a tie-break, which matters: a bulk import writes
     * hundreds of rows sharing one createdAt, and without the second key offset paging would skip or
     * repeat rows.
     */
    suspend fun contacts(workspaceId: String, limit: Int, offset: Int): ApiResult<ContactListResponse>

    /** One contact, in exactly the shape a list row has. */
    suspend fun contact(workspaceId: String, contactId: String): ApiResult<ContactDetailResponse>

    /**
     * Create a contact. ⛔ Excludes `viewer` — gate the UI so it is never offered.
     *
     * ⚠️ A duplicate phone or email answers **409**, not a validation error, because the database
     * enforces one contact per phone and per lowercased email per workspace. Surface it as "this
     * contact already exists", not as a server fault.
     */
    suspend fun createContact(request: CreateContactRequest): ApiResult<ContactMutationResponse>

    /**
     * Update a contact.
     *
     * ⛔ THE SERVER EXPECTS **PATCH**, AND THIS IS THE ONLY ROUTE IN THE ENTIRE API WHERE `workspaceId`
     * IS MANDATORY — omitting it once made Prisma drop the tenant filter, so the route now validates it
     * explicitly. This client therefore cannot apply one "the server can resolve the workspace" policy
     * across the surface.
     */
    suspend fun updateContact(request: UpdateContactRequest): ApiResult<ContactMutationResponse>

    /**
     * Delete a contact.
     *
     * ⛔ **DELETE with QUERY PARAMETERS and no body** — a third convention within one section. Answers
     * 404 when nothing matched, which for a delete means it was already gone.
     */
    suspend fun deleteContact(workspaceId: String, contactId: String): ApiResult<ContactMutationResponse>
}

/**
 * ⚠️ A contact needs a phone number **or** an email address — contacts became email-first.
 * `bulk-create` disagrees and requires a phone per row, silently counting an email-only row as
 * invalid; that inconsistency is server-side and is not smoothed over here.
 */
@kotlinx.serialization.Serializable
data class CreateContactRequest(
    val workspaceId: String,
    val name: String,
    val phoneNumber: String? = null,
    val email: String? = null,
)

/**
 * ⚠️ `workspaceId` is REQUIRED by this route specifically. See [ContactsApi.updateContact].
 *
 * ⛔ A WHOLESALE REPLACE WEARING A PATCH. The route writes every column below whether or not its key
 * is present (an absent key and a null are the same thing to it), so a body that omits a column the
 * row holds CLEARS it. Build it through `ContactsRepository.update`, which starts from the loaded row.
 */
@kotlinx.serialization.Serializable
data class UpdateContactRequest(
    val workspaceId: String,
    val contactId: String,
    val name: String? = null,
    val phoneNumber: String? = null,
    val email: String? = null,
    /**
     * ⚠️ A BARE HANDLE, NOT THE `socialHandles` OBJECT. The route wraps it as
     * `socialHandles: {linkedin}` and REPLACES the column with that, so any other platform key the
     * row held is dropped by the server regardless of what is sent here.
     */
    val linkedin: String? = null,
    /**
     * ⚠️ THE `latestContextSummary` COLUMN, UNDER A DIFFERENT NAME. The route reads `contextSummary`
     * off the body and writes `latestContextSummary`; a client sending the column name would have the
     * field ignored and the summary replaced with the literal "Manual edit update."
     */
    val contextSummary: String? = null,
    val budget: String? = null,
    val timeline: String? = null,
    val website: String? = null,
)

/**
 * The unified Inbox: SMS and email in one thread per counterpart.
 *
 * ⛔ READS ADMIT `viewer`; THE TWO WRITES DO NOT. `conversations`, `timeline` and `unreadCount` allow
 * agency/client/viewer, while `sendMessage` and `markRead` are agency/client only — so the reply box
 * must be gated on the same role the app already threads through for contacts mutations.
 *
 * ⛔ AND `sendMessage` SPENDS REAL MONEY. SMS/MMS segments or a Postmark email, capped server-side at
 * 30 requests per minute PER WORKSPACE (not per user, because the cost lands on the workspace either
 * way). A client that retries a send automatically is spending someone's money on its own initiative;
 * this one does not, and a 429 is surfaced rather than swallowed.
 */
interface InboxApi {
    /**
     * The Inbox list, most recent first.
     *
     * ⛔ NOT PAGED, AND NOT BECAUSE NOBODY GOT AROUND TO IT. The server scans a bounded window of
     * recent messages (500) and GROUPS them into threads, so there is no stable offset to page on —
     * a thread's position depends on messages that may fall outside the window. The response reports
     * `scanned` and `scanLimit` so the client can say the list is partial instead of implying it is
     * complete.
     */
    suspend fun conversations(workspaceId: String): ApiResult<ConversationsResponse>

    /**
     * One thread's full history: SMS, email AND calls, interleaved.
     *
     * ⚠️ Takes `contactId` when the thread resolved to a Contact and `address` when it did not —
     * exactly one is required. The server's parameter is still named `phoneNumber` for the address
     * case and now accepts an email in it; that name is historical and is not worth mirroring here.
     *
     * ⚠️ EXPAND-ONLY PAGING, AND OMITTING BOTH CURSOR ARGUMENTS IS THE NEWEST WINDOW — byte for
     * byte the request this made before paging existed. The client's query builder DROPS null
     * entries rather than sending them empty (see `DistrictApiClient`'s `buildUrl`), so a
     * no-cursor call adds no parameters at all; `InboxRequestBodyTest` pins that URL.
     *
     * ⛔ [beforeId] WITHOUT [before] IS A 400 FROM THE ROUTE, NOT A DEFAULT. An id alone cannot say
     * which timestamp it breaks a tie at, and the alternatives the server rejected — guess a
     * timestamp, or ignore the parameter — both end with the client walking backwards through the
     * same window forever. The pairing is enforced one layer up, in
     * `com.distronode.districtai.core.data.ThreadCursor`, which cannot hold an id without a
     * timestamp; these stay two nullable strings because this layer is the wire's shape.
     *
     * @param before ISO-8601, echoed back from a previous response's `pageInfo.oldest`. Never a
     *   timestamp this client formatted: the server compares it against what it emitted.
     * @param beforeId that response's `pageInfo.oldestId`, breaking ties among rows sharing the
     *   timestamp. Only valid alongside [before].
     */
    suspend fun timeline(
        workspaceId: String,
        contactId: String?,
        address: String?,
        before: String? = null,
        beforeId: String? = null,
    ): ApiResult<TimelineResponse>

    /**
     * The workspace's total unread message count.
     *
     * ⚠️ A SEPARATE CALL FROM [conversations] ON PURPOSE. The nav badge needs this number without
     * paying for the 500-message scan the list costs, and the web sidebar polls exactly this route
     * for the same reason.
     */
    suspend fun unreadCount(workspaceId: String): ApiResult<UnreadCountResponse>

    /**
     * Send a reply.
     *
     * ⛔ EXCLUDES `viewer`, AND IT IS BILLABLE. See the ⛔ on the interface. The server's refusals are
     * specific — unverified sender, exhausted A2P registration, per-workspace rate limit — and are
     * surfaced verbatim, because "could not send" throws all of that away.
     */
    suspend fun sendMessage(request: SendMessageRequest): ApiResult<SendMessageResponse>

    /**
     * Mark a thread read.
     *
     * ⚠️ `contactId` OR `counterpart`; neither is a 400. A thread with no Contact row has only an
     * address, which is why the server accepts both.
     */
    suspend fun markRead(request: MarkReadRequest): ApiResult<MarkReadResponse>

    /**
     * Upload one image and get back the URL [sendMessage] will accept as a `mediaUrl`.
     *
     * ⛔ EXCLUDES `viewer` — it is guarded agency/client like the two writes above, because an
     * upload writes bytes into the workspace's regional database.
     *
     * ⛔ THE RETURNED URL IS ANONYMOUS. `/api/media/<uuid>` answers without a session, because a
     * carrier's MMS fetcher has none. Do not attach the bearer token when loading it back.
     *
     * ⚠️ SERVER LIMITS, MIRRORED CLIENT-SIDE RATHER THAN TRUSTED TO THE ROUND TRIP: JPEG, PNG,
     * GIF or WebP only, 1 byte to 5MB. The client pre-checks both so a 5MB upload is not spent on
     * a metered connection to be told no.
     *
     * @param bytes the whole file, in memory. ⛔ Held rather than streamed so the single 401 retry
     *   can re-send it — see [DistrictApiClient.sendMultipart].
     */
    suspend fun uploadMedia(
        workspaceId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<MediaUploadResponse>
}

/**
 * The composer's persistence and generation routes.
 *
 * ⛔ SPLIT OUT OF [InboxApi] RATHER THAN ADDED TO IT, AND NOT ONLY FOR THE FUNCTION CEILING.
 * These five have a different shape of risk from the Inbox's reads: four are per-AUTHOR writes on
 * a draft row, and the fifth SPENDS MONEY on every call. A section boundary here means a fake in a
 * test that only reads the Inbox cannot accidentally be handed a working [generateDraft].
 *
 * ⛔ `drafts` (PLURAL, persistence) AND `draft` (SINGULAR, AI generation) ARE DIFFERENT ROUTES ONE
 * LETTER APART. The first is cheap and idempotent; the second is a billed Vertex call. See
 * [AiDraftRequest].
 *
 * ⛔ ALL FIVE EXCLUDE `viewer`. A viewer cannot compose, so a viewer has no drafts and no reason
 * to read anyone else's — the server guards every one of them agency/client.
 */
interface MessageDraftsApi {
    /**
     * One thread's saved draft, or `null` when there is none.
     *
     * ⛔ `null` IS THE ORDINARY ANSWER AND NOT A FAILURE. Almost every thread has no draft; the
     * server answers `{success, draft: null}` rather than 404 precisely so the composer's normal
     * open path is not an error in every log. Not rate limited, deliberately — a 429 here would
     * blank a composer that has text waiting for it.
     */
    suspend fun draft(workspaceId: String, threadKey: String): ApiResult<DraftResponse>

    /**
     * Every draft this author has open, newest first.
     *
     * ⚠️ Read ONCE per Inbox load to badge the list, not per row. It is one indexed query capped
     * at 100 rows; a per-thread call would be one request per visible conversation.
     */
    suspend fun drafts(workspaceId: String): ApiResult<DraftListResponse>

    /**
     * Upsert a draft.
     *
     * ⛔ NEVER WITH A BLANK BODY. The server answers 400 `code: "empty_body"` and means "send
     * DELETE instead" — a blank draft is the absence of one. The refusal is deliberate rather
     * than a silent redirect, so a client that clears the box learns to delete.
     *
     * ⚠️ Rate limited at 60 writes/min per WORKSPACE, shared with [deleteDraft]. That is an
     * autosave ceiling rather than a meter — nothing billable hangs off it — but a debounce
     * tighter than a second would reach it with two operators in one workspace.
     */
    suspend fun saveDraft(request: DraftSaveRequest): ApiResult<DraftResponse>

    /** Idempotent: deleting a draft that is not there succeeds, because the goal state is met. */
    suspend fun deleteDraft(workspaceId: String, threadKey: String): ApiResult<DraftDeleteResponse>

    /**
     * Generate a reply with the model.
     *
     * ⛔ NON-IDEMPOTENT AND BILLABLE — one Vertex generation per call, capped at 20/min per
     * workspace. Flagged the same way `sendMessage` and `enrichContact` are: nothing in this
     * client may retry it, and the single 401 refresh-retry inside [DistrictApiClient] is the only
     * re-send that can ever happen. That one is acceptable here for the same reason it is on the
     * HQ routes: a 401 is refused by `requireWorkspaceRole` BEFORE the handler reaches Vertex, so
     * the rejected attempt cost nothing.
     */
    suspend fun generateDraft(request: AiDraftRequest): ApiResult<AiDraftResponse>
}

/**
 * District HQ: the agentic command console.
 *
 * ⛔ ONE ROUTE, TWO OPERATIONS, AND **NEITHER IS IDEMPOTENT**. `POST /api/district/hq` branches on
 * the request body: [prompt] runs a Gemini function-calling loop (billable model tokens, and it
 * reads workspace data through the HQ tool catalog), while [confirm] EXECUTES A WRITE — a persona
 * change, a deletion, a routing replacement, an outbound campaign, or a real email or SMS to a
 * customer. Sending either one twice is not free and, for [confirm], is not recoverable.
 *
 * ⛔ THAT MATTERS HERE SPECIFICALLY BECAUSE [DistrictApiClient.send] RE-SENDS ON A 401. Its own ⛔
 * spells out the rule — check that a duplicate delivery is acceptable before routing a
 * non-idempotent endpoint through it — and the honest answer for HQ is that it is NOT, in exactly
 * the way `messages/send` is not. The trade is taken deliberately and it is bounded: the retry
 * fires only when the access token was rejected, which means the FIRST attempt was refused before
 * any handler ran (`requireWorkspaceRole` is the first thing the route does, and it answers 401
 * before reading the body). A 401 that arrived AFTER a write would require the session to be
 * revoked mid-request, which cannot happen inside one call. The server offers no idempotency key
 * and no request id, so there is nothing stronger available to build on.
 *
 * ⛔ AND THE CLIENT MUST NOT ADD A RETRY OF ITS OWN. No automatic re-ask on a network failure, and
 * above all no automatic re-confirm: a confirm that timed out may well have executed, and
 * re-sending it deletes a second contact or sends a second email. A failed confirm is surfaced to
 * the operator, who is the only party that can decide whether to repeat it.
 *
 * ⛔ RATE LIMITED AT 30/MIN PER ACCOUNT (not per workspace), shared by both operations, answering
 * 429. Surfaced rather than absorbed — see [ApiResult.RateLimited].
 *
 * ⚠️ READS ADMIT `viewer`. A viewer may ask questions; the server refuses their writes inside the
 * tool executor, BEFORE the confirm gate, so the model's tool call comes back as a "view-only"
 * error and no proposal is ever surfaced. A viewer therefore simply never sees a confirm prompt —
 * the UI does not have to hide one.
 */
interface HqApi {
    /**
     * Ask a question, or ask for a change.
     *
     * ⚠️ STATELESS: the server holds no conversation. The client sends the transcript as
     * [HqPromptRequest.history] and the server keeps the last 6 turns of it.
     *
     * ⚠️ A response carrying `needsConfirmation` has changed NOTHING yet — see [HqPromptResponse].
     */
    suspend fun hqPrompt(request: HqPromptRequest): ApiResult<HqPromptResponse>

    /**
     * Execute the write the operator approved.
     *
     * ⛔ THE ARGUMENTS ARE ECHOED BACK VERBATIM from the proposal, never rebuilt. See
     * [HqConfirmRequest].
     *
     * ⚠️ A tool name that is not a genuine write tool answers **400**, not 403 — the server refuses
     * to let a crafted confirm body invoke a read tool or an unknown name.
     */
    suspend fun hqConfirm(request: HqConfirmRequest): ApiResult<HqConfirmResponse>
}

/**
 * Telephony analytics and metered usage.
 *
 * ⚠️ ALL THREE ARE READS AND ALL THREE ADMIT `viewer`, so nothing on this screen needs a role
 * gate. That is unusual enough in this API to state: contacts, the Inbox writes and HQ's confirm
 * all exclude viewers, and a reader arriving from those sections would reasonably expect the same
 * here.
 *
 * ⛔ TWO DIFFERENT ROUTES, DELIBERATELY GROUPED. Analytics lives at `/api/district/analytics` and
 * usage at `/api/district/workspace/usage` — different path families, different data layers, no
 * server-side relationship. They are one interface because one SCREEN needs both and a section
 * should depend on the slice it uses; splitting them would mean two fakes for one screen's tests.
 */
interface AnalyticsApi {
    /**
     * The analytics window.
     *
     * ⛔ AN UNRECOGNISED `timeRange` IS NOT AN ERROR — the server silently falls back to 7d and
     * answers 200. So sending a wrong value serves a week's figures under whatever heading the UI
     * is showing, with nothing anywhere reporting a problem. [AnalyticsRange.wire] is the only
     * thing standing between this and that outcome, which is why the range is an enum rather than
     * a String on this signature.
     *
     * ⚠️ THE RESPONSE SIZE DEPENDS ON THE WINDOW. 7d and 30d bucket daily; 90d buckets WEEKLY. A
     * caller sizing a chart from a constant will be wrong for two of the three.
     */
    suspend fun analytics(
        workspaceId: String,
        range: AnalyticsRange,
    ): ApiResult<AnalyticsResponse>

    /**
     * This month's metered usage.
     *
     * ⛔ A SUCCESSFUL RESPONSE MAY CARRY `usage: null`, WHICH MEANS "NOTHING METERED YET" AND NOT
     * "ZERO OF EVERYTHING". See [UsageResponse].
     */
    suspend fun usage(workspaceId: String): ApiResult<UsageResponse>

    /**
     * The last [months] months, newest first.
     *
     * ⚠️ SAME PATH AS [usage], SWITCHED BY A QUERY PARAMETER, and the response TYPE changes with
     * it: `usage` is an object there and an array here. That is why these are two functions
     * rather than one with a flag — one function would have to return a union, and a caller able
     * to confuse them could read a single month as an empty history.
     *
     * ⚠️ [months] is CLAMPED to 1..24 server-side (each month is its own query, so an unclamped
     * value turns one request into arbitrarily many round trips). The response can also be
     * shorter than asked for, because months with no rows are skipped.
     */
    suspend fun usageHistory(workspaceId: String, months: Int): ApiResult<UsageHistoryResponse>
}

/**
 * District Global Intelligence: the contact dossier's two writes.
 *
 * ⛔ BOTH EXCLUDE `viewer`, AND [enrichContact] IS **NOT IDEMPOTENT**. It schedules an external
 * crawl and an LLM synthesis — one request buys one model run, whether it lands on the Pub/Sub
 * subscriber or on the route's in-process fallback — so this is the same class of endpoint as
 * `messages/send` and HQ's confirm, and it carries the same warning.
 *
 * ⛔ THAT MATTERS BECAUSE [DistrictApiClient.send] RE-SENDS ONCE ON A 401. The trade is taken
 * deliberately and it is bounded in exactly the way [HqApi] documents: the retry fires only when
 * the access token was rejected, which means the FIRST attempt was refused before any handler ran
 * (`requireWorkspaceRole` is the first thing this route does, before it reads the body or touches
 * a row). A 401 arriving AFTER the enqueue would require the session to be revoked mid-request,
 * which cannot happen inside one call. The server offers no idempotency key, so there is nothing
 * stronger to build on.
 *
 * ⛔ AND NOTHING IN THIS CLIENT MAY ADD A RETRY OF ITS OWN — no automatic re-enrich on a network
 * failure. An enrichment that timed out may well have been queued, and re-sending it spends a
 * second model run on the same contact.
 *
 * ⛔ RATE LIMITED AT 30/MIN PER WORKSPACE (not per user — the cost lands on the workspace either
 * way), answering 429. Surfaced rather than absorbed; see [ApiResult.RateLimited]. ⚠️ The limiter
 * is Redis-backed and FAIL-OPEN, so it bounds a loop rather than metering enrichment. Do not
 * treat a green response as proof the spend cap held.
 */
interface DgiApi {
    /**
     * Queue an enrichment for one contact.
     *
     * ⛔ ANSWERS **403** WHEN THE WORKSPACE HAS NOT OPTED IN, and the body of that 403 is the
     * product rather than boilerplate: it names the exact settings page that turns the feature on
     * ("Settings → AI Agent → Skills & Integrations"). Show it VERBATIM. A generic "you can't do
     * that" leaves the operator with a button that fails and no route to the switch. The opt-in
     * check runs BEFORE the row is stamped, so a refused enrichment never leaves a contact
     * showing a dossier that will never arrive.
     *
     * ⚠️ Answers 200 with `status: "pending"` and returns in under 100ms — it reports that work
     * was SCHEDULED, never that a dossier exists. The result arrives on the contact row, so the
     * caller has to poll [ContactsApi.contact] afterwards.
     *
     * ⚠️ A 404 means the contact id did not resolve within this workspace. Indistinguishable from
     * another tenant's id, by design.
     */
    suspend fun enrichContact(request: EnrichRequest): ApiResult<EnrichResponse>

    /**
     * Clear a contact's dossier, keeping the contact.
     *
     * ⛔ THIS DELETES THE DOSSIER, NOT THE CONTACT. `intelligence`, `company` and `website` are
     * nulled; phone, email and timeline are untouched. Confirm it in the UI anyway — it is not
     * recoverable and the crawl has to be paid for again.
     *
     * ⛔ AND IT RESETS `dgiStatus` TO **NULL**, NOT "pending". Nothing is re-queued, so a client
     * that optimistically showed "pending" after a clear would spin forever against a job that
     * does not exist. Re-read the contact and render what the server actually says.
     */
    suspend fun clearContactIntel(request: ClearIntelRequest): ApiResult<ClearIntelResponse>
}

/** ⚠️ Both DGI writes take the same two fields, but they are separate types so a call site cannot
 * pass a clear where an enrich was meant — one costs money and the other destroys data. */
@kotlinx.serialization.Serializable
data class EnrichRequest(val workspaceId: String, val contactId: String)

/** See [EnrichRequest] for why this is not the same type. */
@kotlinx.serialization.Serializable
data class ClearIntelRequest(val workspaceId: String, val contactId: String)

/**
 * The phone-number marketplace — READ ONLY.
 *
 * ⛔ THERE IS NO PURCHASE, RELEASE OR CONFIGURE CALL HERE, AND THAT IS A DECISION RATHER THAN AN
 * OMISSION. Those routes exist server-side; this client deliberately does not reach them. Buying
 * a number creates a recurring carrier charge and releasing one takes a live line out of service,
 * neither of which should be one mis-tap away on a phone — and a released number cannot be
 * reclaimed. The app shows what the workspace has and what is available; the web dashboard is
 * where a number changes hands. Adding a write here is a product decision, not a gap to fill.
 *
 * ⚠️ BOTH READS ADMIT `viewer`, so nothing on this surface needs a role gate. The role still
 * travels with the screen, because the captions differ — a viewer is told changes happen on the
 * web dashboard for a different reason than an agency operator is.
 */
interface NumbersApi {
    /**
     * Search the carrier's available inventory.
     *
     * ⛔ A WORKSPACE WITH NO CARRIER CONNECTED ANSWERS **400**, NOT AN EMPTY LIST —
     * `{success:false, error:"Messaging provider not configured for workspace"}`. That is a
     * legitimate account state rather than a fault, and it must render as an empty state that
     * explains itself, never as an error the operator is invited to retry.
     *
     * ⚠️ [areaCode] and [type] are optional filters; [country] defaults to "US" server-side when
     * omitted. The server hardcodes the result limit (10) and the capability filter (sms+voice),
     * so neither is offered here — a parameter for either would be a control the route ignores.
     *
     * ⚠️ [provider] narrows to ONE configured carrier. Naming one the workspace has not connected
     * is a 400 with its own message, not a fallback to the default.
     */
    suspend fun searchNumbers(
        workspaceId: String,
        areaCode: String?,
        country: String?,
        type: String?,
        provider: String?,
    ): ApiResult<NumberSearchResponse>

    /**
     * Every number the workspace already has, from every source.
     *
     * ⛔ THE ANSWER CAN BE INCOMPLETE ON A 200. When one carrier fails and another answers, the
     * response is `partial: true` with `failedProviders` naming the one that did not — see
     * [OwnedNumbersResponse]. Render the rows AND the warning. The 502 is reserved for the case
     * where nothing resolved at all.
     */
    suspend fun ownedNumbers(workspaceId: String): ApiResult<OwnedNumbersResponse>
}

/**
 * The account's signed-in devices, and the two ways to sign one out.
 *
 * ⛔ THE ONLY SECTION THAT IS NOT UNDER `/api/district/`. These three live under
 * `/api/auth/native/`, which is a PUBLIC prefix in `proxy.ts` — so the default-deny middleware
 * never runs on them and each route's own `requireAuth` is the entire access control. They ride
 * this client anyway, and must: `requireAuth` accepts a native bearer precisely so the app can
 * manage its own devices, and routing them through [DistrictApiClient] is what gives them token
 * acquisition, the one-shot 401 refresh and the shared failure mapping. Only
 * `POST /api/auth/native/revoke` — the self-authenticating one, whose credential IS the refresh
 * token — stays outside, in `NativeAuthApi`.
 *
 * ⛔ ACCOUNT-SCOPED, NOT WORKSPACE-SCOPED. Not one of these takes a `workspaceId`, and none
 * could: a native session belongs to a USER. The scope comes from the verified session and
 * there is no parameter that could widen it — the same rule the voice agent's support lookup
 * documents, that an identity arriving as an argument is an identity the caller chose.
 *
 * ⚠️ SIGNING OUT THE CURRENT DEVICE IS A LOCAL EVENT TOO. Both writes can end this
 * installation's own session (deliberately, in `revokeAllDevices`'s case), and the server has
 * no way to tell this process that its credential just died — it will simply 401 on the next
 * request. Whatever calls these has to drive the local sign-out itself.
 */
interface DevicesApi {
    /**
     * Every live install on this account, newest first.
     *
     * ⚠️ CAN LAG A ROTATION, so a short or empty list is not proof of a signed-out account.
     * See [DeviceListResponse].
     *
     * ⚠️ Rate limited at 30/min PER ACCOUNT (not per IP — the caller is authenticated), which
     * is sized for a settings screen rather than a poll loop.
     */
    suspend fun devices(): ApiResult<DeviceListResponse>

    /**
     * Sign out one install.
     *
     * ⛔ ANSWERS `revoked: 0` RATHER THAN 404 FOR A DEVICE THAT IS NOT YOURS, on purpose —
     * see [DeviceRevokeResponse]. A client that reported zero as an error would be exposing
     * the oracle the server declined to build.
     *
     * ⚠️ The server validates `deviceId` at 8..200 characters, mirroring the token route's
     * window exactly. An id outside it could never have been stored.
     */
    suspend fun revokeDevice(request: DeviceRevokeRequest): ApiResult<DeviceRevokeResponse>

    /**
     * Sign out every install, INCLUDING THIS ONE.
     *
     * ⛔ THE CALLING DEVICE IS NOT SPARED, AND THAT IS THE SERVER'S DECISION, NOT AN OVERSIGHT.
     * Its route header states it: an "all" that quietly excepted the caller would be a control
     * nobody could reason about. So a caller must treat a successful response as its own
     * sign-out.
     *
     * ⚠️ NO BODY AT ALL. The route parses nothing — there is no parameter that could widen or
     * narrow it — so this sends no JSON rather than an empty object a client would have to
     * guess at.
     */
    suspend fun revokeAllDevices(): ApiResult<DeviceRevokeResponse>
}

/**
 * ⚠️ A TYPE RATHER THAN A BARE `String` PARAMETER, matching the DGI pair's reasoning: this is
 * the body of a write that ends a session, and a call site able to pass any string where a
 * device id was meant is one typo from revoking the wrong row. It is also the only one of the
 * three device calls that HAS a body.
 */
@kotlinx.serialization.Serializable
data class DeviceRevokeRequest(val deviceId: String)

/**
 * Billing — READ ONLY, and here that is a STORE POLICY REQUIREMENT rather than a product judgement.
 *
 * ⛔ THERE IS NO CANCEL, NO PLAN CHANGE, NO PAYMENT-METHOD EDIT AND NO PROMO CODE IN THIS
 * INTERFACE, AND ADDING ONE WOULD BREACH GOOGLE PLAY'S PAYMENTS POLICY. `POST /api/billing` exists
 * and does all four; this client deliberately does not reach it. The marketplace's read-only
 * decision (see [NumbersApi]) was ours to reverse; this one is not. The app shows what is being
 * billed and says where a change happens.
 *
 * ⛔ TWO ROUTES FROM DIFFERENT FAMILIES, AND THEIR ENVELOPES DISAGREE. [workspaceBilling] is a
 * District route under `/api/district/workspace/` answering `{success, billing}`; [stripeBilling]
 * is at `/api/billing`, is CALLER-scoped rather than workspace-scoped, and answers a BARE OBJECT
 * with no `success` key at all. They are one interface because one SCREEN needs both — the same
 * reasoning [AnalyticsApi] documents — and a fake only has to implement the slice.
 *
 * ⚠️ BOTH ARE READS AND [workspaceBilling] ADMITS `viewer`, so nothing here needs a role gate.
 * The role still travels with the screen because the read-only CAPTION differs, exactly as it does
 * for the marketplace: telling a viewer to change the plan on the web dashboard sends them
 * somewhere that will also refuse them.
 */
interface BillingApi {
    /**
     * The workspace's plan, status, overage state and this month's usage — with NO Stripe call.
     *
     * ⛔ THE ABSENCE OF STRIPE IS THE FEATURE. Every field comes from columns the Stripe webhooks
     * wrote to our own database, so this answer is exactly as available as our own origins are.
     * [stripeBilling] is only as available as Stripe is, which is why the two are separate calls
     * with separate failure states rather than one.
     *
     * ⚠️ Region-resolved server-side: a ca/eu/apac workspace has no row in the hub at all, so a
     * hub-only read would report an empty plan for every non-us tenant rather than an error.
     *
     * ⛔ A SUCCESSFUL RESPONSE MAY CARRY `usage: null`, WHICH MEANS "NOTHING METERED THIS MONTH"
     * AND NOT "ZERO OF EVERYTHING". Same rule as [AnalyticsApi.usage]; see `UsageResponse`.
     */
    suspend fun workspaceBilling(workspaceId: String): ApiResult<WorkspaceBillingResponse>

    /**
     * Subscriptions and invoices, from Stripe.
     *
     * ⛔ TAKES NO `workspaceId`, AND COULD NOT. It is guarded by `requireAuth` rather than
     * `requireWorkspaceRole` and resolves the caller's OWN Stripe customer from server-owned
     * state — the same shape as [DevicesApi]'s account scope. The workspace it reports on comes
     * BACK in `usageWorkspaceId`; it cannot be chosen by a parameter, deliberately, because that
     * parameter would be an identity the caller supplied.
     *
     * ⛔ NEVER ANSWERS `{success:...}`, SO THE ENVELOPE GUARD MUST NOT BE APPLIED TO IT — see
     * `StripeBilling`. A 200 here is decoded directly and `billingUnavailable` is read as a STATE.
     *
     * ⛔ AND IT ANSWERS **200 WHEN STRIPE IS DOWN**, with `billingUnavailable: true` and empty
     * arrays. That is not a failure to surface as one: the client must render "billing is
     * temporarily unavailable" and must never render it as a free or unstarted account. The
     * no-customer response is the same body WITHOUT the flag and does legitimately mean the
     * latter.
     */
    suspend fun stripeBilling(): ApiResult<StripeBilling>
}

/**
 * Workspace settings: one read, and the two writes that are SAFE to make from a phone.
 *
 * ⛔ THE READ IS NOT OPTIONAL BEFORE EITHER WRITE, AND THAT IS THE WHOLE REASON THIS SECTION IS
 * SHAPED THIS WAY. `workspace/directory` (PATCH), `workspace/routing-rules` (POST) and
 * `workspace/tools` (PATCH) all REPLACE their stored value wholesale rather than merging it. A
 * form that opened empty and saved through one of them would not "save nothing" — it would
 * DELETE the transfer directory the voice agent routes live callers through, or the agent's tool
 * allowlist. The web never had to think about this: its settings page is a server component that
 * hydrates every form from the row during render. A phone has no such prop, so [workspaceConfig]
 * is that prop, fetched.
 *
 * ⛔ ALL THREE DESTRUCTIVE ROUTES NOW LIVE HERE, AND THE SHAPE OF THEIR PARAMETERS IS THE GUARD.
 * [saveDirectory] and [saveRoutingRules] each take a request type whose array is NON-NULLABLE and
 * is built by a `core-model` helper from rows that were decoded out of a loaded config — there is
 * no overload taking a list a caller assembled, and adding one is how the wipe happens.
 *
 * ⛔ AND ALL THREE CALLS EXCLUDE `viewer` SERVER-SIDE, INCLUDING THE READ. That is unusual on
 * this surface (`workspace/usage` and `workspace/list` both admit viewers) and it is not an
 * oversight: the payload carries staff phone numbers and the operator's own prompt, and a viewer
 * has no mutation form to hydrate. The entry point to this section must therefore be hidden for
 * a viewer, or they get a 403 on a screen they were invited to open.
 */
interface ConfigApi {
    /**
     * The workspace settings row, redacted.
     *
     * ⚠️ A PLAIN READ WITH NO SIDE EFFECTS AND NO VENDOR CALL, by design — it cannot itself be
     * the reason a client fails open into an empty form.
     *
     * ⚠️ `messagingConfig` arrives already narrowed by `redactWorkspaceSecrets` (the platform's
     * managed sub-account credentials are dropped, connector secrets become `hasSecret`), and
     * `twilioConfig` is not returned at all. Nothing here needs re-redacting client-side.
     */
    suspend fun workspaceConfig(workspaceId: String): ApiResult<WorkspaceConfigResponse>

    /**
     * Save persona fields.
     *
     * ⛔ SEND ONLY WHAT THE OPERATOR CHANGED. The server merges per field
     * (`x !== undefined ? x : existing`), so an omitted key is preserved — and a client that
     * posted its whole form state would overwrite the engine choice, the avatar settings and the
     * tuning parameters with whatever it happened to hold. [PersonaPatchRequest]'s nulls are
     * dropped on the wire precisely so that cannot happen by accident.
     *
     * ⛔ ANSWERS `{success:true}` AND NOTHING MORE — the updated config is NOT echoed, so a
     * caller that needs fresh state must re-read. See [WorkspaceConfigSaveResponse].
     *
     * ⚠️ RATE LIMITED AT 30/MIN PER WORKSPACE (not per user — the config being rewritten belongs
     * to the workspace), answering 429. Surfaced rather than absorbed.
     */
    suspend fun savePersona(request: PersonaPatchRequest): ApiResult<WorkspaceConfigSaveResponse>

    /**
     * Save the agent's capability allowlist.
     *
     * ⛔ `allowedTools` IS WRITTEN WHOLESALE AND IS REQUIRED. Whatever list arrives becomes the
     * stored one; a missing array is a 400 rather than a no-op. The caller's obligation is to
     * send the list it LOADED with the operator's toggles applied — including any id this
     * client's catalog does not recognise, which is not hypothetical: `transfer_to_creator` was
     * retired in 2026 and workspaces still store it.
     *
     * ⛔ ANSWERS `{success:true}` ONLY, same as [savePersona]. Re-read for fresh state.
     */
    suspend fun saveTools(request: ToolsPatchRequest): ApiResult<WorkspaceConfigSaveResponse>

    /**
     * Replace the call transfer directory.
     *
     * ⛔ THE MOST DESTRUCTIVE CALL IN THIS CLIENT, AND ITS FAILURE MODE IS A 200. The handler writes
     * `callDirectory: (callDirectory || [])` — so an empty array, or a body that simply omits the
     * key, WIPES every transfer target the voice agent can put a live caller through to, and
     * answers `{success:true}`. There is no "save nothing" on this route.
     *
     * ⛔ AND THE ROWS MUST BE CARRIED WHOLE. The route's zod schema is `.passthrough()` and names
     * only `name` and `phoneNumber`; the column is `Json` and holds whatever anyone ever wrote. A
     * request rebuilt from a typed model would strip the rest — see `WorkspaceEditModels.kt`.
     */
    suspend fun saveDirectory(request: DirectoryPatchRequest): ApiResult<WorkspaceConfigSaveResponse>

    /**
     * Replace the dynamic-persona routing rules.
     *
     * ⛔ **POST**, NOT PATCH, AND ALSO WHOLESALE. One difference from [saveDirectory] is worth
     * knowing: this route requires `Array.isArray(routingRules)` and answers **400 "Invalid
     * payload"** without it, so an omitted array is refused — but an EMPTY array is accepted and
     * deletes every rule.
     *
     * ⚠️ CAN ANSWER **400 "Invalid voice identifier: X"** (or model) when the workspace restricts
     * either vocabulary. That is a real refusal to surface verbatim, not a fault: this client
     * cannot see the allow-lists and deliberately does not pre-validate against a guess.
     */
    suspend fun saveRoutingRules(request: RoutingRulesRequest): ApiResult<WorkspaceConfigSaveResponse>
}

/**
 * The knowledge base: the documents the agent answers from, and where it answers from.
 *
 * ⛔ THE READS ADMIT `viewer` AND THE WRITES DO NOT, which is the OPPOSITE split from [ConfigApi],
 * whose read excludes viewers as well. Nothing on this surface is a staff phone number, so a
 * viewer may read the list and the mode.
 *
 * ⛔ AND [createDocument] SPENDS REAL MONEY PER CALL — see [KnowledgeCreateRequest]. It is not
 * idempotent, it goes through [DistrictApiClient.send] (which re-sends once on a 401, bounded for
 * the reason [HqApi] documents), and nothing in this client may add a retry of its own.
 */
interface KnowledgeApi {

    /** ⚠️ Newest first. Admits `viewer`. An empty array is a real answer, not an error. */
    suspend fun knowledgeDocuments(workspaceId: String): ApiResult<KnowledgeListResponse>

    /** ⛔ Billable and rate limited at 20/min per WORKSPACE. See [KnowledgeCreateRequest]. */
    suspend fun createDocument(request: KnowledgeCreateRequest): ApiResult<KnowledgeCreateResponse>

    /**
     * Delete one document; its chunks cascade.
     *
     * ⛔ **DELETE WITH QUERY PARAMETERS AND NO BODY**, and the id parameter is spelled
     * **`documentId`** — not `id`, not `docId`. The route reads `searchParams.get("documentId")`
     * and answers **400 "Missing documentId"** for anything else, which reads as a broken client
     * rather than as a typo.
     *
     * ⚠️ ANSWERS `{success:true}` EVEN WHEN NOTHING MATCHED. The delete is a `deleteMany` scoped to
     * `{id, workspaceId}` whose count is never read, so a cross-tenant id is indistinguishable from
     * a real delete — deliberate, and it means the caller must re-read the list.
     */
    suspend fun deleteDocument(
        workspaceId: String,
        documentId: String,
    ): ApiResult<KnowledgeDeleteResponse>

    /** ⚠️ `mode` is a TOP-LEVEL key of the envelope, not a nested object. See [KnowledgeModeResponse]. */
    suspend fun knowledgeMode(workspaceId: String): ApiResult<KnowledgeModeResponse>

    /**
     * Choose the knowledge source.
     *
     * ⛔ SWITCHING TO `linked` SENDS THIS WORKSPACE'S QUESTIONS TO ATLASSIAN. It is a
     * data-residency change rather than a display preference, which is why the write excludes
     * `viewer` while the read admits it, and why the UI confirms it.
     *
     * ⚠️ AN UNKNOWN MODE IS A **400**, not a coerced value: the route validates with
     * `z.enum(KB_MODES)`. Send a value from `KB_MODES`.
     */
    suspend fun saveKnowledgeMode(
        request: KnowledgeModePatchRequest,
    ): ApiResult<KnowledgeModeResponse>
}

/**
 * The workspace's outbound carrier accounts: one read, five writes on one path, and a probe.
 *
 * ⛔ TWO RISKS SHAPE THE WRITES:
 *
 *   - An edit form does NOT have to ask an operator to retype a live carrier secret on a phone
 *     keyboard. The route treats a blank or absent secret as "keep the stored ciphertext", so the
 *     ordinary edit types no credential at all. It is the redaction on the READ that makes the
 *     write safe.
 *   - A delete DOES release the phone-number claims that stop another tenant sending as this one,
 *     which is answered by a confirmation that names that consequence. See
 *     [com.distronode.districtai.core.model.MessagingDeleteRequest].
 *
 * ⛔ THE READ ADMITS `viewer` AND EVERY WRITE EXCLUDES ONE (`["agency","client"]`). That split is
 * load-bearing in both directions: a viewer reaches this screen (the settings hub is open to
 * them) and must be offered no control here, while a viewer who somehow pressed one
 * would meet a 403 the UI is meant to have prevented.
 *
 * ⛔ ONE OF THESE IS NOT IDEMPOTENT AND THE BOUND ON THE RETRY IS WHAT MAKES IT SAFE.
 * [saveMessagingAccount] with no `accountId` CREATES an account under a freshly minted
 * `acct-<uuid>`, so two deliveries are two accounts. It goes through [DistrictApiClient.send] like
 * every tenant-scoped write — which re-sends once after a 401 refresh — and that is acceptable for
 * exactly the reason [KnowledgeApi] and `messages/send` state: a 401 is returned by
 * `requireWorkspaceRole` BEFORE the handler runs, so the retried request is the first one that can
 * ever reach `handleUpsert`. ⛔ Nothing in this client may add a retry of its own on top.
 */
interface MessagingApi {

    /** ⚠️ Admits `viewer`. Redacted server-side: no credential is on this response. */
    suspend fun messaging(workspaceId: String): ApiResult<MessagingResponse>

    /**
     * Create or edit one carrier account.
     *
     * ⛔ NON-IDEMPOTENT WHEN `accountId` IS NULL — see the ⛔ on the interface.
     *
     * ⛔ FOUR DISTINCT REFUSALS REACH THE FORM VERBATIM AND MUST NOT BE FLATTENED. A managed
     * request without the entitlement is **403**; a number another workspace holds is **403** with
     * a deliberately non-disclosing sentence; a number this account's own carrier does not own is
     * **403** with the same sentence; and a carrier that could not be reached is **502**, which is
     * NOT a refusal — the route separates it precisely so an outage does not read as theft.
     *
     * ⚠️ THE PROVIDER MAY BE CHANGED ON AN EXISTING ACCOUNT, and doing so DISCARDS the stored
     * secrets rather than merging them (`existingEnc` is only reused when the provider is
     * unchanged). So switching provider on an edit is effectively a re-entry of credentials, and a
     * blank secret field there means "store nothing", not "keep".
     */
    suspend fun saveMessagingAccount(
        request: MessagingAccountRequest,
    ): ApiResult<MessagingAccountSaveResponse>

    /** Point every outbound send at one account. ⚠️ Idempotent and reversible. */
    suspend fun setDefaultAccount(
        request: MessagingDefaultRequest,
    ): ApiResult<MessagingDefaultResponse>

    /**
     * Override the sender for one channel.
     *
     * ⚠️ MERGES ONE KEY into the stored map; there is no clear. A `channel` outside
     * `[sms, voice, whatsapp]` is a 400 naming the value.
     */
    suspend fun setChannelDefault(
        request: MessagingChannelDefaultRequest,
    ): ApiResult<MessagingChannelDefaultResponse>

    /**
     * Remove one carrier account.
     *
     * ⛔ ALSO FREES EVERY PHONE NUMBER ONLY THIS ACCOUNT HELD, in the hub index that routes inbound
     * calls and SMS. Confirm it with wording that says so.
     *
     * ⚠️ ANSWERS 404 "Account not found" for an id the workspace does not hold, which on this
     * screen means the list is stale rather than that anything is broken.
     */
    suspend fun deleteMessagingAccount(
        request: MessagingDeleteRequest,
    ): ApiResult<MessagingDefaultResponse>

    /**
     * Write the workspace's creator cell number.
     *
     * ⚠️ ITS OWN ACTION because a workspace with NO carrier account has no upsert to carry it, and
     * that is the workspace most likely to be setting it.
     */
    suspend fun saveCreatorCell(request: MessagingMetaRequest): ApiResult<MessagingMetaResponse>

    /**
     * Ask the carrier whether these credentials authenticate.
     *
     * ⛔ A REFUSAL IS A 200 WITH `success:false`, NOT AN ERROR STATUS — see
     * [com.distronode.districtai.core.model.MessagingTestResponse]. Anything that applies this
     * client's usual envelope guard to it will report "we could not understand the response" for
     * the answer the button exists to give.
     *
     * ⛔ TAKES PLAINTEXT, UNSAVED CREDENTIALS, so it can only be offered when the form actually
     * holds them, and it is rate limited to 10/min per WORKSPACE. Never call it on a loop or a
     * recomposition.
     */
    suspend fun testMessagingCredentials(
        request: MessagingTestRequest,
    ): ApiResult<MessagingTestResponse>
}

/**
 * Multi-party `meet_` rooms: the credential to join one, and the meetings the Companion wrote up.
 *
 * ⛔ THE JOIN TOKEN LIVES HERE RATHER THAN ON [CallsApi], EVEN THOUGH ITS PATH IS
 * `/api/district/calls/token`. That route serves two unrelated cases behind one body — a `meet_`
 * or `video_` prefix means a standalone ROOM, anything else means a `Call.id` and the caller is a
 * SUPERVISOR joining someone else's live phone call. They imply opposite roles (the route stamps
 * `participant` for one and `supervisor` for the other, and the voice agent unsubscribes a
 * supervisor's microphone), and only the room case is reachable from this client. Grouping it with
 * the meetings it produces keeps the surface honest; grouping it with the call log would imply
 * this client can monitor calls, which it cannot. The path's own oddity is recorded on
 * [DistrictPaths.CALLS_TOKEN] so nobody "fixes" it to a rooms path that would 404.
 *
 * ⛔ ONE OF THESE IS A WRITE-SHAPED READ AND MUST NOT BE RETRIED BLINDLY. [roomToken] is a POST
 * that mints a signed capability and, for a non-viewer, a twelve-hour transferable guest invite.
 * It is idempotent in the sense that nothing is persisted, so a retry is safe — but each call
 * mints a fresh invite, so a screen that called it on every recomposition would be minting
 * capabilities at the rate it redraws.
 *
 * ⚠️ ALL THREE ADMIT `viewer`, so nothing behind this needs a role GATE. The role still changes
 * what comes back: a viewer's token carries `canPublish:false` and no guest invite, which the UI
 * has to respect rather than merely reflect — see [com.distronode.districtai.core.model.RoomTokenResponse].
 *
 * ⚠️ AND THE MEETINGS LIST IS A BARE ARRAY, unlike almost everything else in this API. See
 * [meetings].
 */
interface MeetingsApi {

    /**
     * Mint a join token for a `meet_` room.
     *
     * ⛔ THE ROOM NAME IS NOT AN AUTHORIZATION CLAIM AND MUST NOT BE TREATED AS ONE. The server
     * parses the workspace id back out of it and runs `requireWorkspaceRole` against THAT, so
     * naming another tenant's room answers 403 rather than granting anything. It is also not a
     * secret: suffixes are human-typed and low entropy, which is precisely why an unauthenticated
     * guest needs a signed invite instead of a name.
     *
     * ⚠️ `identity` IS REQUIRED BY THE ROUTE AND IGNORED BY IT. A missing value is a 400; a present
     * one is never read, because the route derives a hashed identity from the SESSION (accepting a
     * client-supplied identity was an impersonation hole, and a random one per join produced
     * duplicate tiles because LiveKit evicts only on a repeated identity). [RoomTokenRequest]
     * therefore defaults it to a constant.
     */
    suspend fun roomToken(request: RoomTokenRequest): ApiResult<RoomTokenResponse>

    /**
     * The workspace's meetings, newest first, capped at 50 server-side.
     *
     * ⛔ A BARE JSON ARRAY, NOT AN ENVELOPE — `NextResponse.json(results)`, the same shape as
     * `/api/district/calls` and unlike almost every other district route. So there is no `success`
     * flag to check here, and [com.distronode.districtai.core.data.ResponseEnvelope]'s guard does
     * not apply: an empty array is a legitimate "no meetings yet" that no envelope check could
     * distinguish from a broken read anyway.
     *
     * ⚠️ THE 50-ROW CAP IS SILENT. Nothing in the response says whether it was hit, so a client
     * must not describe this as the complete history.
     */
    suspend fun meetings(workspaceId: String): ApiResult<List<MeetingSummary>>

    /**
     * One meeting in full — minutes, transcript, action items.
     *
     * ⛔ NOT A SUPERSET OF THE LIST ROW, WHICH IS THE TRAP. This returns the raw database row, so
     * it carries `summary` and `participants` where the list carried `summaryPreview` and
     * `participantCount`. Neither model decodes the other's payload.
     *
     * ⚠️ `workspaceId` IS REQUIRED AND IS NOT DERIVED FROM THE ID. The route scopes the lookup on
     * both, so the RLS context and the role check apply to the tenant the caller claims — a
     * meeting id from another workspace is simply not found.
     */
    suspend fun meetingDetail(workspaceId: String, meetingId: String): ApiResult<MeetingDetail>
}

/**
 * ⚠️ A TYPE RATHER THAN TWO BARE STRINGS, for the reason [DeviceRevokeRequest] is one: the two
 * fields are both strings and swapping them at a call site would compile. `identity` carries a
 * default because the route requires the key and ignores the value — see [MeetingsApi.roomToken] —
 * so no caller should be inventing one.
 */
@kotlinx.serialization.Serializable
data class RoomTokenRequest(
    val roomName: String,
    /**
     * ⛔ A CONSTANT, DELIBERATELY. The server derives the real participant identity from the
     * session and hashes it; anything sent here is discarded. Sending a device id or an email
     * would put a value on the wire that is neither used nor needed.
     */
    val identity: String = "android",
)

/**
 * Who belongs to this workspace, and what it is called.
 *
 * ⛔ THE MUTATIONS ARE AGENCY-ONLY, WHICH IS NARROWER THAN ANYTHING ELSE ON THIS SURFACE. Every
 * other write in this API admits `["agency","client"]`; these three admit `agency` alone, because
 * membership is what `getWorkspaceRole` answers from — a client or viewer who could write here
 * could grant themselves any role and bypass every `requireWorkspaceRole` in the product. So
 * [WorkspaceRole.canMutate] is the WRONG gate for them; it mirrors the wider allow-list, and its
 * own KDoc says to check the route when one is narrower. Gate on `AGENCY` explicitly.
 *
 * ⛔ AND TWO OF THEM CAN BE REFUSED WITH A MACHINE-READABLE CODE RATHER THAN A STATUS ALONE. A
 * duplicate address is 409 `member_exists`; demoting or removing the last agency member is 409
 * `last_agency_member`. Both arrive as [ApiResult.HttpFailure] with `code` populated — see
 * [ApiErrorEnvelope] — and both deserve their own sentence, because "already a member" and "this
 * workspace would have no administrator" are not the same problem and only one of them is
 * something the operator did wrong.
 *
 * ⛔ [renameWorkspace] IS HERE AND NOT ON [WorkspaceApi], WHICH LOOKS LIKE THE WRONG HOME UNTIL
 * THE GUARDS ARE COMPARED. `WorkspaceApi` is USER-scoped: its one function takes no `workspaceId`
 * and is the single district route guarded by `requireAuth` rather than `requireWorkspaceRole`.
 * `workspace/rename` is workspace-scoped and role-guarded, so putting it there would break the one
 * invariant that interface's KDoc states. It belongs with the membership calls for the reason
 * [AnalyticsApi] and [BillingApi] give for their own pairings — ONE SCREEN needs both, and the
 * screen that lists who a workspace belongs to is the natural place to change what it is called.
 * ⚠️ Its guard is `["agency","client"]`, i.e. WIDER than the membership writes above it, so the
 * two controls on that screen are gated separately.
 *
 * ⚠️ THREE HTTP CONVENTIONS AGAIN — GET with a query, POST/PATCH with a body, DELETE with a query
 * and no body — plus a PATCH on the sibling rename route. They are the server's, mirrored rather
 * than smoothed over.
 */
interface MembersApi {

    /**
     * The roster.
     *
     * ⚠️ ADMITS ALL THREE ROLES, INCLUDING `viewer` — deliberately, per the route: a viewer who
     * cannot see who else is in the workspace cannot tell who to ask for help. So the LIST is
     * reachable by a role the workspace-settings hub in front of it excludes; see
     * `Routes.WORKSPACE_SETTINGS_MEMBERS`.
     *
     * ⚠️ Ordered `createdAt asc` — oldest first, the opposite of every other list here.
     */
    suspend fun members(workspaceId: String): ApiResult<MemberListResponse>

    /**
     * Add one member.
     *
     * ⛔ NO INVITATION IS SENT AND NO ACCOUNT IS PROVISIONED. This makes an EXISTING login a member;
     * an address that has never signed up simply has a row waiting for it. The route's header is
     * explicit that invitation is a separate feature and deliberately out of scope.
     *
     * ⛔ 409 `member_exists` FOR A DUPLICATE, from a pre-check and from the unique-constraint catch
     * behind it, so the code arrives for the ordinary case and for a race between two adds.
     *
     * ⚠️ 404 IF THE WORKSPACE DOES NOT EXIST, and that check is not redundant with the role guard:
     * for a caller with support access the guard returns "agency" for ANY id, so without it a
     * typo would create a member row pointing at nothing.
     *
     * ⚠️ Rate limited 20/min per WORKSPACE (not per caller) across all three writes.
     */
    suspend fun addMember(request: MemberAddRequest): ApiResult<MemberMutationResponse>

    /**
     * Change one member's role.
     *
     * ⛔ REFUSED WITH 409 `last_agency_member` WHEN IT WOULD DEMOTE THE LAST AGENCY MEMBER. The
     * count and the write share one interactive transaction, so two concurrent demotions cannot
     * both read "there are still two" and both commit.
     *
     * ⚠️ 404 for an address that is not a member — distinct from the 409s, and it means the roster
     * on screen is stale rather than that the request was wrong.
     */
    suspend fun changeMemberRole(request: MemberRoleRequest): ApiResult<MemberMutationResponse>

    /**
     * Remove one member.
     *
     * ⛔ **DELETE WITH QUERY PARAMETERS AND NO BODY**, and the parameters are spelled `workspaceId`
     * and `email`. Same shape as the knowledge delete, and the same failure if misspelled: a 400
     * naming the field, which reads as a broken client rather than as a typo.
     *
     * ⛔ 409 `last_agency_member` HERE TOO, by the same transaction-bounded count.
     *
     * ⚠️ ANSWERS A BARE `{success:true}` WITH NO `member` KEY. See [MemberMutationResponse]: that
     * is why the echoed row is nullable, and it is the one response a client must not fail to
     * decode, since the row really is gone.
     */
    suspend fun removeMember(
        workspaceId: String,
        email: String,
    ): ApiResult<MemberMutationResponse>

    /**
     * Rename the workspace.
     *
     * ⛔ THE RESPONSE ECHOES THE **TRIMMED, STORED** NAME, and adopting that echo rather than the
     * requested string is what makes a re-read unnecessary — the server trims before it measures,
     * so what comes back is what a later read will see.
     *
     * ⛔ NAME ONLY. The slug is unique in two physically separate databases with no cross-database
     * transaction between them, so it is not editable from here at all.
     *
     * ⚠️ ADMITS `agency` AND `client`, unlike the membership writes above. ⚠️ Rate limited 10/min
     * keyed on the CALLER's email rather than on the workspace, which is the opposite key from the
     * membership limiter — two operators renaming get one budget each.
     */
    suspend fun renameWorkspace(request: WorkspaceRenameRequest): ApiResult<RenameResponse>
}

/**
 * The outbound softphone: one route, one call, real money.
 *
 * ⛔ ITS OWN INTERFACE RATHER THAN A FUNCTION ON [CallsApi], AND THE SPLIT IS THE SAME ONE
 * [MeetingsApi] MAKES FOR THE ROOM TOKEN. `CallsApi` is four READS of a log that has already
 * happened; this PLACES a telephone call, spends the workspace's minutes and creates a row. A
 * screen that lists calls has no business being able to make one, and a fake that stubs the log
 * should not have to stub the dialler. It is also the only interface in this file every one of
 * whose callers must be role-gated client-side — see the ⚠️ below.
 *
 * ⛔ AND IT IS THE ONE ROUTE IN THIS CLIENT THAT MUST NEVER BE RETRIED BLINDLY. [DistrictApiClient]
 * re-sends once on a 401, which is safe everywhere else here because those routes either read or
 * persist idempotently. This one dials. The re-send is nonetheless correct, and the reason is
 * worth stating rather than assuming: the 401 retry happens only when the request was REFUSED
 * before the handler ran, so the first attempt provably never reached the carrier. Anything that
 * retried after a 5xx — where the dial may have gone out — would ring the callee twice.
 *
 * ⚠️ EXCLUDES `viewer` SERVER-SIDE (`["agency","client"]`), so the UI must hide the entry rather
 * than offer a button that 403s. That is presence, not wording — the same call `WorkspaceSettings`
 * makes and unlike the marketplace's read-only caption.
 */
interface DialApi {

    /**
     * Place a direct outbound call and receive the credential to join its room.
     *
     * ⛔ THE RESPONSE ARRIVES BEFORE THE CALLEE ANSWERS, DELIBERATELY, and a client that treated
     * it as "connected" would be wrong about the most visible thing on the screen. The route does
     * NOT pass `waitUntilAnswered` — it returns as soon as the carrier accepts the dial — so the
     * app joins the room while the far end is still ringing. Ring versus answer is observable
     * only as a PARTICIPANT appearing; see `DialerViewModel`.
     *
     * ⛔ SIX WAYS TO BE REFUSED, IN THIS ORDER: role (403), lapsed subscription (402
     * `subscription_inactive`), a hard overage cap (409 `overage_cap_reached`), the per-workspace
     * rate limit (429, 20/min), DNC (403), and no usable number or a Sinch-only workspace (400).
     * Two of those are 403s carrying nothing machine-readable, which is why
     * `com.distronode.districtai.core.data.DialRepository` branches on the CODE where there is one
     * and forwards the server's own sentence where there is not.
     *
     * ⚠️ THE NUMBER IS NORMALISED SERVER-SIDE and every guard runs against the normalised form, so
     * this client sends what the operator typed rather than inventing a canonicaliser that could
     * disagree with the DNC list.
     */
    suspend fun dial(request: DialRequest): ApiResult<DialResponse>
}

/**
 * ⚠️ A TYPE RATHER THAN TWO BARE STRINGS, for the reason [RoomTokenRequest] is one: both fields are
 * strings and swapping them at a call site would compile — and here the two arguments swapped would
 * dial a workspace id.
 */
@kotlinx.serialization.Serializable
data class DialRequest(
    val workspaceId: String,
    /**
     * The number to call, AS TYPED.
     *
     * ⚠️ NOT PRE-NORMALISED HERE. The server runs `normalizePhoneNumber` and then checks DNC,
     * resolves the Contact and dials against THAT one form; a client-side canonicaliser could
     * disagree with it, and the disagreement would show up as a call placed to a number the DNC
     * check was never run against. The route rejects anything under 8 digits after normalising.
     */
    val to: String,
)

/**
 * The automation monitor: which workflows exist, whether they are running, and what happened.
 *
 * ⛔ THREE OF THE FOUR ADMIT `viewer` AND THE FOURTH DOES NOT, WHICH IS THE ONLY SPLIT ON THIS
 * SECTION. `workflows` (GET), `workflowRuns` and `campaignStatus` all admit agency/client/viewer —
 * a monitor is a read — while `setWorkflowActive` is agency/client, like every other write in this
 * API. So the SCREEN is present for every role and only the switch is gated; a viewer sees the
 * state of each workflow without a control to change it, which is the marketplace's caption
 * distinction rather than workspace settings' presence one.
 *
 * ⛔ THE CREATE, THE FULL EDIT AND THE DELETE ARE DELIBERATELY UNREACHABLE FROM THIS CLIENT. The
 * same `/api/district/workflows` path serves POST and DELETE, and PATCH accepts `name`, `trigger`,
 * `triggerMetadata` and `actions` as well as `active`. None of that is here. A workflow ACTION is
 * an outbound SMS, an email, a compliance DNC registration or a `notify_ops` webhook — things that
 * spend money and change a customer's contactable state — and authoring one on a phone means
 * typing a webhook URL and a message body into a form whose save replaces the stored array
 * wholesale. Adding a write beyond the toggle is a product decision, not a gap to fill.
 *
 * ⛔ [campaignStatus] IS HERE RATHER THAN ON [ConfigApi], AND THE REASON IS THE ROLE GUARD RATHER
 * THAN THE PATH. `workspace/config` excludes `viewer` from its READ because its payload carries
 * staff transfer numbers and the operator's own prompt; `workspace/campaign-status` exists
 * precisely so the three SDR fields are reachable without any of that. Putting it on [ConfigApi]
 * would file a viewer-readable route under an interface whose KDoc states that every call on it
 * excludes viewers. It is grouped here for the reason [AnalyticsApi] and [BillingApi] give for
 * their own pairings: ONE SCREEN needs both, and a fake only has to implement the slice.
 *
 * ⛔ THE CAMPAIGN PAUSE IS A **SEPARATE ROUTE FROM THE SETTINGS FORM**, AND THIS NOTE USED TO SAY
 * THERE WAS NO PAUSE AT ALL. That was true while the only candidate was `PATCH
 * workspace/campaign-settings`, which rebuilds all three SDR fields from the body and therefore
 * wipes the goal and resets the batch size on a partial one. It stopped being true when
 * `PATCH workspace/campaign-status` shipped: it reads the stored Json, spreads it, and assigns a
 * single key. [setCampaignEnabled] is that route and only that route — pointing a pause at
 * `campaign-settings` is still destructive, with a 200.
 */
interface WorkflowsApi {

    /**
     * Every workflow in the workspace, newest first, each with its latest run.
     *
     * ⚠️ ADMITS `viewer`. ⚠️ `latestRun` is an explicit NULL for a workflow that has never run,
     * not an omitted key — see [com.distronode.districtai.core.model.WorkflowListItem].
     *
     * ⚠️ NOT PAGED, and the server applies no cap. The rollup is one `distinct` query rather than
     * one lookup per workflow, so the cost is two queries regardless of how many rows there are.
     */
    suspend fun workflows(workspaceId: String): ApiResult<WorkflowListResponse>

    /**
     * One workflow's execution history, newest first.
     *
     * ⚠️ ADMITS `viewer` — read-only history, the same judgement as the list above.
     *
     * ⛔ `workflowId` IS REQUIRED AND ITS ABSENCE IS A **400**, not an unfiltered list. The route
     * checks it before the role guard even runs.
     *
     * @param limit CLAMPED SERVER-SIDE to 1..50, defaulting to 10 — and a non-numeric value is
     *   REPLACED with the default rather than clamped, because a NaN `take` makes Prisma throw.
     *   The response echoes what was actually applied, which is the only way a caller learns.
     * @param offset a negative value becomes 0. Deliberately uncapped upward, so a large one is a
     *   slow query rather than a wrong page.
     */
    suspend fun workflowRuns(
        workspaceId: String,
        workflowId: String,
        limit: Int,
        offset: Int,
    ): ApiResult<WorkflowRunsResponse>

    /**
     * Turn one workflow on or off.
     *
     * ⛔ EXCLUDES `viewer` (`["agency","client"]`), the ONLY call on this section that does. Gate
     * the control rather than offering one that 403s — and note the refusal is a plain role 403
     * with no machine-readable code, so a viewer who raced a role change gets the generic message.
     *
     * ⛔ ANSWERS A BARE `{success:true}` WITH NO ECHO of the updated row, so a caller needing fresh
     * state must re-read. See [WorkflowToggleRequest] for why this client sends only `active`.
     *
     * ⚠️ IDEMPOTENT, which is what makes it safe through [DistrictApiClient.send] and its single
     * 401 re-send: setting `active` to the value it already holds writes the same row. It spends
     * no money directly — but the workflows it enables do, so nothing here may retry on its own.
     */
    suspend fun setWorkflowActive(
        request: WorkflowToggleRequest,
    ): ApiResult<WorkflowToggleResponse>

    /**
     * Whether the always-on SDR campaign is running, and how it is configured.
     *
     * ⚠️ ADMITS `viewer`, and that is why it is a separate route from `workspace/config` at all.
     *
     * ⚠️ REGION-RESOLVED SERVER-SIDE: a ca/eu/apac workspace has no `Workspace` row in the hub, so
     * a hub-only read would report every non-us tenant as having no campaign configured.
     *
     * ⚠️ A 404 means the workspace id did not resolve, which is distinct from "no campaign" — that
     * is a 200 with all three fields at their empty values.
     */
    suspend fun campaignStatus(workspaceId: String): ApiResult<CampaignStatusResponse>

    /**
     * Pause or resume the always-on SDR engine, and touch nothing else.
     *
     * ⛔ THIS IS **NOT** `workspace/campaign-settings`, AND THE DISTINCTION IS THE WHOLE REASON
     * THE CALL EXISTS. That route rebuilds all three SDR fields from the request body
     * (`sdrCampaignGoal: goal || ""`, `sdrBatchSize: floor(Number(size) || 1)`), so a partial body
     * sent to pause a campaign WIPES the goal text and resets the batch size to 1 — with a 200.
     * `PATCH campaign-status` reads the stored Json, spreads it, and assigns ONE key; the other
     * two are not in its vocabulary, so it cannot destroy them. Never point this at the other
     * path to "reuse" a route.
     *
     * ⛔ EXCLUDES `viewer` (`["agency","client"]`), unlike the GET on the same path — which is the
     * only verb split in this API. Watching a campaign and pausing one are different powers.
     *
     * ⛔ ANSWERS THE GET'S SHAPE, DERIVED FROM WHAT WAS WRITTEN. That is what lets the caller
     * adopt the reply instead of re-reading — and it is why the client does not flip optimistically
     * here the way [setWorkflowActive] does: there IS an echo, and a resume starts spending money.
     *
     * ⚠️ A NON-BOOLEAN `infiniteSdrEnabled` IS A 400 SERVER-SIDE RATHER THAN A COERCION, which is
     * why [CampaignPauseRequest.infiniteSdrEnabled] is non-nullable: `explicitNulls = false` must
     * never be able to drop it.
     */
    suspend fun setCampaignEnabled(
        request: CampaignPauseRequest,
    ): ApiResult<CampaignStatusResponse>
}

/**
 * The campaign pause/resume body.
 *
 * ⛔ TWO FIELDS AND NO MORE. The route ignores unknown keys rather than refusing them, so an
 * `sdrBatchSize` or `sdrCampaignGoal` added here would not fail — it would simply be dropped,
 * which is the shape that makes a client look like it is writing something it is not. The merge
 * server-side is what protects the stored object; this type is what stops anyone reaching for the
 * destructive route by habit.
 *
 * ⚠️ A TYPE RATHER THAN TWO BARE PARAMETERS, matching [WorkflowToggleRequest]: a `(String,
 * Boolean)` signature is one whose arguments cannot be swapped, but the type also keeps the
 * serialized field names — which are the contract — in one place next to the route they describe.
 */
@kotlinx.serialization.Serializable
data class CampaignPauseRequest(
    val workspaceId: String,
    val infiniteSdrEnabled: Boolean,
)

/**
 * The toggle's body.
 *
 * ⛔ THREE FIELDS AND NO MORE, DELIBERATELY. The route's PATCH accepts `name`, `trigger`,
 * `triggerMetadata` and `actions` too, and it distinguishes "sent" from "absent" with
 * `"x" in body` rather than by truthiness — which is the whole reason `active: false` works at
 * all. A request type carrying nullable optionals for the other four would put them one
 * `explicitNulls` setting away from the wire, and `actions` is written WHOLESALE: an accidental
 * empty array would delete every action a workflow has, with a 200.
 *
 * ⚠️ A TYPE RATHER THAN THREE BARE PARAMETERS, for the reason [DeviceRevokeRequest] is one: two of
 * the three are strings and swapping them at a call site would compile — and here the swap sends a
 * workspace id as a workflow id, which the route answers with a Prisma failure rather than a
 * refusal.
 */
@kotlinx.serialization.Serializable
data class WorkflowToggleRequest(
    val workspaceId: String,
    val workflowId: String,
    val active: Boolean,
)

/**
 * The workspace's booking pages: what state the tenancy is in, the one button that brings one
 * into existence, and the hand-off into the scheduler's own admin.
 *
 * ⛔ NEITHER READ CARRIES A `success` ENVELOPE, SO NOTHING BEHIND THIS INTERFACE MAY BE RUN
 * THROUGH `rejectedEnvelope`. `status` answers `{eligible, canManage, tenant}` and `enable`
 * answers `{ok, status, publicHost, error}`; there is no flag, so the envelope guard would be
 * checking a key the server never sends. The required fields on the DTOs are what reject `{}` in
 * its place — see [SchedulingStatusResponse].
 *
 * ⛔ THERE IS NO BOOKING EDITOR HERE AND THERE MUST NOT BE ONE. Event types, availability,
 * questions and branding are edited in the scheduler's own admin, served from the workspace's
 * public host, which is why [schedulingSsoTarget] exists at all. Rebuilding any of that in this
 * client would be a second editor over the same rows with no server surface of its own to talk to.
 */
interface SchedulingApi {
    /**
     * What the Scheduling card renders: eligibility, whether this member may act, and the tenancy
     * row if there is one.
     *
     * ⚠️ READABLE BY EVERY ROLE INCLUDING `viewer` — it reports whether the workspace has booking
     * pages and where they are, which is the same class of fact as "this workspace has a phone
     * number". The response's `canManage` is what decides which buttons to draw.
     *
     * ⛔ TAKES A NON-OPTIONAL `workspaceId`, UNLIKE [OverviewApi.overview]. The route would accept
     * an absent one and let `requireWorkspaceRole` pick a default from the caller's own membership
     * listing — but this client holds no selection cookie, so on a multi-workspace account that
     * default silently reports on the wrong workspace, and here the wrong answer is a booking URL
     * belonging to somebody else's tenancy.
     */
    suspend fun schedulingStatus(workspaceId: String): ApiResult<SchedulingStatusResponse>

    /**
     * Provision this workspace's scheduling tenancy.
     *
     * ⛔ IT CREATES REAL THINGS AT TWO THIRD PARTIES — a tenancy at the scheduler and a DNS record
     * at Cloudflare — so it is never fired on a timer, never retried and never looped. The
     * server's own brake is 5 per hour PER WORKSPACE (three colleagues pressing the same button
     * share one budget), and a sixth call answers **429**. That limiter fails open, so it is not a
     * guarantee: the durable half is the provisioner reusing the existing row and the existing
     * hostname rather than allocating a second one.
     *
     * ⛔ IT ANSWERS **202**, AND `ok: false` INSIDE THAT 202 IS STILL A SUCCESSFUL RESPONSE. See
     * [SchedulingEnableResponse].
     *
     * ⛔ OWNER AND ADMIN ONLY (`client` and `agency`), AND ALSO ALLOWLIST-GATED, which is a SECOND
     * check rather than the same one worded differently. The role check asks whether this person
     * may act for this workspace; the allowlist asks whether this workspace is one anybody has
     * decided to provision at all. While the feature is dark the second answer is no for
     * everybody, so even an owner gets **403** — which is why the card must read `eligible` from
     * the status route instead of offering the button by role.
     *
     * ⚠️ THE WORKSPACE TRAVELS IN THE BODY. The route reads `req.json()` first and falls back to
     * the query string, so both work; the body is what the web client sends, and matching it keeps
     * one capture readable as the other.
     */
    suspend fun enableScheduling(
        request: SchedulingEnableRequest,
    ): ApiResult<SchedulingEnableResponse>

    /**
     * Mint a hand-off into the scheduler's own admin, and report WHERE it points without going
     * there.
     *
     * ⛔ THE 302 IS READ, NEVER FOLLOWED, AND THAT IS THE WHOLE POINT OF THIS CALL. The `Location`
     * carries a 60-second single-use JWT in its query string, so following it would SPEND that
     * credential on a transport the user never sees and the browser would then be handed a token
     * that has already been claimed in `sso_nonces`. `DistrictApiClient.redirectTarget` clones the
     * shared client with `followRedirects(false)` for exactly this shape — see its own ⛔, which
     * was written for the recording endpoint where following merely wastes bandwidth. Here it
     * would break the feature.
     *
     * ⛔ AND THE BROWSER CANNOT MAKE THIS REQUEST ITSELF. A Custom Tab carries neither this app's
     * bearer nor a session cookie (the native API path sets none at all, deliberately), so
     * pointing it at the route would land the operator on a sign-in page rather than in their
     * scheduler. The bearer is spent here, once, and only the resulting URL crosses over.
     *
     * ⛔ NOTHING MAY LOG, STORE OR CACHE THE RESULT. It is minted per press, lives 60 seconds and
     * may be spent once.
     *
     * ⚠️ A **409** IS NOT A FAULT. The route answers it when the tenancy is not `ready`, which is
     * the honest state of a workspace mid-provision, and the screen says so rather than reporting
     * a failure the user would try to fix.
     *
     * @param next where to land inside the scheduler. ⚠️ The route VALIDATES it and may drop it —
     *   a value it refuses costs the operator the deep link and nothing else, because the far end
     *   still signs them in and shows its default page.
     */
    suspend fun schedulingSsoTarget(workspaceId: String, next: String): ApiResult<String>

    /**
     * Mint a single-use code that turns a browser sheet into a SIGNED-IN dashboard session.
     *
     * ⛔ THIS IS THE REPLACEMENT FOR [schedulingSsoTarget], NOT A SIBLING OF IT. That one hands off
     * into the scheduler's own `/admin/` console, which is being switched off per region; this one
     * lands on the dashboard's scheduling pages, which are cookie-only and would otherwise show the
     * app's operator a login screen. Both ship for one release so an installed build keeps working
     * either side of the flip.
     *
     * ⛔ AN ORDINARY POST, NOT A REDIRECT READ, WHICH IS THE OPPOSITE SHAPE FROM ITS PREDECESSOR.
     * The code arrives in a JSON body rather than in a `Location`, so this goes through
     * [DistrictApiClient.send] and follows nothing.
     *
     * ⛔ AND THE RESPONSE IS A LIVE CREDENTIAL: 43 characters, one use, sixty seconds. Nothing may
     * log, store or cache it, and it is minted at the moment of the tap rather than in advance —
     * a code held for later is a code that has already expired.
     *
     * ⚠️ THE ONE 401-REFRESH RETRY INSIDE [DistrictApiClient.send] IS ACCEPTABLE HERE, AND IT IS
     * worth saying out loud because that retry is forbidden for anything that spends money. A
     * second mint costs a row and a rate-limit slot; the first code is simply never redeemed and
     * dies in sixty seconds. It is not free — the limiter is 10/min per ACCOUNT — but it is
     * recoverable, unlike a duplicated SMS.
     *
     * ⚠️ **403** MEANS THE BEARER, NOT THE ROLE. The route answers `{"error":"Forbidden"}` for a
     * missing, unverifiable or mismatched token, so the honest sentence for it is "sign in again",
     * not the generic permission wording.
     *
     * ⚠️ **429** IS 10/MIN PER ACCOUNT AND ITS BODY IS `{"error":"rate_limited","message":"…"}`.
     * ⛔ `error` THERE IS A MACHINE TOKEN, so [ApiErrorEnvelope] surfaces the snake_case string and
     * a caller that showed it verbatim would print `rate_limited` on a customer's phone.
     */
    suspend fun schedulingHandOff(
        request: SchedulingHandOffRequest,
    ): ApiResult<SchedulingHandOffResponse>
}

/**
 * The dashboard hand-off body.
 *
 * ⛔ NO `deviceId`, AND ITS ABSENCE IS LOAD-BEARING RATHER THAN AN OMISSION. The server takes the
 * installation off the VERIFIED bearer and ignores anything the body claims, so a field here could
 * only ever be a client asserting an identity it does not get to assert — and a reader would take
 * its presence as evidence the server honours it.
 *
 * ⚠️ [next] IS OPTIONAL AND IS VALIDATED SERVER-SIDE. Anything that is not a same-origin path under
 * `/dashboard` silently becomes `/dashboard/district/scheduling`, so a value the route refuses
 * costs the deep link and nothing else. `BODY_JSON` sets `explicitNulls = false`, so a null one is
 * omitted from the wire rather than sent as `"next": null`.
 */
@kotlinx.serialization.Serializable
data class SchedulingHandOffRequest(
    val workspaceId: String,
    val next: String? = null,
)

/**
 * The enable body.
 *
 * ⛔ ONE FIELD AND NO MORE. The route reads `workspaceId` off the body and falls back to the query
 * string; there is nothing else it accepts, and a type is what stops a caller reaching for the
 * status route's shape by habit.
 *
 * ⚠️ A TYPE RATHER THAN A BARE `String`, matching [WorkflowToggleRequest] and [CampaignPauseRequest]:
 * it keeps the serialized field name — which is the contract — next to the route that reads it.
 */
@kotlinx.serialization.Serializable
data class SchedulingEnableRequest(val workspaceId: String)

/**
 * Taking a call this device is ringing on.
 *
 * ⛔ ITS OWN INTERFACE RATHER THAN A FUNCTION ON [DialApi] OR [CallsApi], AND THE SPLIT IS THE ONE
 * THE SERVER MADE FIRST. `calls/answer` is a NEW ROUTE rather than a flag on `calls/token`
 * precisely because that route already conflated two structurally opposite cases once, stamped
 * `role: "supervisor"` on both, and the voice agent's whisper handler responded by unsubscribing
 * the caller's microphone — the AI then greeted a human it could not hear and hung up on silence.
 * Mirroring that separation on this side means a fake that stubs the dialler cannot answer a call
 * and vice versa.
 *
 * ⛔ AND IT IS NOT THE SAME RISK SHAPE AS [DialApi], WHICH IS WORTH SAYING BECAUSE THEY LOOK ALIKE.
 * Dialling spends money and rings a stranger; answering spends nothing and rings nobody — the call
 * already exists and somebody is already on it. What answering DOES do is put this operator's
 * microphone into a live customer conversation, which is why the server excludes `viewer` from it
 * on exactly the same footing as `calls/dial`.
 *
 * ⚠️ EXCLUDES `viewer` SERVER-SIDE (`["agency","client"]`). Unlike the dialler, the UI cannot
 * simply hide the entry: the entry is a PUSH NOTIFICATION the server sent, and the server fans out
 * to every registered device in the workspace without consulting roles. So a viewer's phone can
 * genuinely ring, and the 403 is surfaced as "you cannot answer calls in this workspace" rather
 * than prevented.
 */
interface InboundCallApi {

    /**
     * Answer [callId] and receive the credential to join its room.
     *
     * ⛔ CALLED **AFTER** THE HUMAN PRESSES ANSWER, NEVER ON THE PUSH ITSELF, AND THAT ORDERING IS
     * THE WHOLE POINT OF THE ROUTE EXISTING. This call WRITES the Redis rendezvous the agent's
     * `ring-app` transfer is blocking on, so calling it to "pre-warm" a credential would tell the
     * agent a human took the call while the phone was still ringing in a pocket — and the caller
     * would be handed to nobody. The push carries ids precisely so nothing has to be fetched until
     * somebody has actually answered.
     *
     * ⛔ THREE REFUSALS, AND TWO OF THEM MEAN THE SAME THING TO A USER. **404** is a call id this
     * workspace cannot see (indistinguishable from another tenant's, deliberately) and **409** is a
     * call whose status is no longer answerable or that has no room — between them they are "the
     * call ended while your phone was ringing", which is the ordinary race on a ringing screen
     * rather than a fault. **403** is the viewer refusal above and is genuinely different.
     *
     * ⚠️ SAFE UNDER [DistrictApiClient.send]'S SINGLE 401 RETRY, for the reason the dial route is:
     * a 401 comes from `requireWorkspaceRole` before the handler runs, so the refused attempt
     * provably never minted a token or wrote the rendezvous. ⛔ Nothing may add a retry on top —
     * not because a second answer is billable, but because a 5xx may have already released the
     * agent's transfer, and a second attempt would race a call that is being connected.
     *
     * @param callId travels as a PATH SEGMENT, so it goes through `addPathSegment` (singular) like
     *   every other id in this client — see [DistrictApiClient.get].
     */
    suspend fun answerCall(
        callId: String,
        request: CallAnswerRequest,
    ): ApiResult<com.distronode.districtai.core.model.CallAnswerResponse>
}

/**
 * ⚠️ A TYPE RATHER THAN A BARE `String`, for the reason [DialRequest] is one: `callId` is already a
 * path segment on this call, so a body taking a second bare string is one transposition away from
 * sending the call id where the workspace id belongs — which the server would answer 404, i.e. as
 * "that call ended", on a screen where that sentence is a plausible lie.
 */
@kotlinx.serialization.Serializable
data class CallAnswerRequest(val workspaceId: String)

/**
 * This installation's push registration.
 *
 * ⛔ NEITHER CALL TAKES A `deviceId`, AND NEITHER COULD. The server recovers the installation from
 * the bearer it was presented with (one extra HMAC verification) because `deviceId` is the UPSERT
 * KEY on the row: a caller able to name it could
 * point somebody else's row at their own token and start receiving that person's notifications.
 * This is the identity-as-an-argument rule the voice agent's support lookup states, applied to a
 * write instead of a read.
 *
 * ⛔ AND NEITHER IS WORKSPACE-SCOPED. A device belongs to a PERSON across every workspace they
 * hold, so the guard is `requireAuth`; scoping it would mean a token registered while the app
 * happened to be showing workspace A could not be pushed for workspace B, on the same phone and
 * the same human.
 *
 * ⚠️ UNDER `/api/district/`, WHICH IS THE OPPOSITE OF WHERE [DevicesApi] LIVES DESPITE THE SIMILAR
 * NAME. Session management is `/api/auth/native/devices/…` because a native session is an auth
 * object; PUSH registration is a district resource and sits under the district prefix with the
 * default-deny proxy in front of it. Neither prefix is interchangeable and each would 404 at the
 * other's path.
 *
 * ⚠️ RATE LIMITED AT 20/MIN PER ACCOUNT, shared shape with the device-revoke sibling. That is sized
 * for an app-start and a token rotation, not a poll — so nothing here may be called on a timer.
 */
interface PushApi {

    /**
     * Record (or refresh) this installation's FCM token.
     *
     * ⛔ AN IDEMPOTENT UPSERT KEYED ON THE INSTALLATION, so re-sending the same token is free and
     * re-sending a NEW one replaces the old. It is therefore safe under the client's single 401
     * retry, and it is also why the registrar simply registers again rather than tracking whether
     * it has.
     *
     * ⚠️ A DEVICE RE-REGISTERED BY A DIFFERENT ACCOUNT MOVES TO THAT ACCOUNT rather than
     * accumulating a second row — one phone, one token, one owner. That is what stops a signed-out
     * account's notifications from arriving on a handset somebody else is now holding, and it is
     * the reason registering after sign-in is not optional politeness.
     */
    suspend fun registerPushToken(
        request: PushTokenRegisterRequest,
    ): ApiResult<com.distronode.districtai.core.model.PushRegistrationResponse>

    /**
     * Stop pushing to this installation.
     *
     * ⛔ NO BODY AT ALL — the route parses nothing, because there is no parameter a caller could
     * send that would be honoured. ⚠️ An empty JSON object is sent anyway: [DistrictApiClient.send]
     * maps a null body onto `Request.method("POST", null)`, which OkHttp REJECTS outright, and that
     * throws from the request builder rather than becoming an [ApiResult]. Same constraint, and the
     * same `{}`, as [DevicesApi.revokeAllDevices].
     *
     * ⛔ IT NEEDS A LIVE BEARER, WHICH IS WHY IT RUNS BEFORE THE SIGN-OUT'S REVOKE AND NOT AFTER.
     * Once the refresh token is revoked there is no credential left to authenticate this with, and
     * the row would sit registered until FCM eventually reported the token gone.
     */
    suspend fun unregisterPushToken(): ApiResult<com.distronode.districtai.core.model.PushRegistrationResponse>
}

/**
 * ⛔ `platform` HAS **NO KOTLIN DEFAULT**, AND THAT IS A WIRE-FORMAT CONSTRAINT RATHER THAN A STYLE
 * CHOICE. `HttpDistrictApi`'s body encoder is `Json { explicitNulls = false }`, which leaves
 * `encodeDefaults` at kotlinx.serialization's own default of FALSE — so a property equal to its
 * declared default is OMITTED from the request entirely. A `= PLATFORM_ANDROID` here would therefore
 * have produced `{"token": "..."}` on the wire, silently, and the field would have been "sent
 * explicitly" only in the source. `PushRequestTest` caught exactly that.
 *
 * ⚠️ IT MATTERS BECAUSE THE SERVER'S OWN DEFAULT IS THE THING BEING AVOIDED. The route's zod schema
 * makes the field optional with a default of `"android"`, so omitting it works today — and would
 * silently mislabel every row the day an iOS client ships, because that client would have to
 * remember to send what this one relied on being assumed.
 */
@kotlinx.serialization.Serializable
data class PushTokenRegisterRequest(
    /**
     * The FCM registration token, as the SDK issued it.
     *
     * ⛔ NOT A CREDENTIAL OF OURS AND NOT A SECRET TO LOG. It authorises sending TO this device and
     * is useless without the Firebase service account — but it identifies one installation, so it
     * belongs in a request body and nowhere else. The server stores it as issued rather than hashed
     * because it has to be replayed to FCM.
     */
    val token: String,
    /** ⚠️ "android" or "ios"; the server validates against exactly that pair. */
    val platform: String,
)

/** The only value this client may send. See [PushTokenRegisterRequest.platform]. */
const val PLATFORM_ANDROID: String = "android"
