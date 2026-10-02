package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.AiDraftRequest
import com.distronode.districtai.core.model.AiDraftResponse
import com.distronode.districtai.core.model.DraftDeleteResponse
import com.distronode.districtai.core.model.DraftListResponse
import com.distronode.districtai.core.model.DraftResponse
import com.distronode.districtai.core.model.DraftSaveRequest
import com.distronode.districtai.core.model.MediaUploadResponse
import com.distronode.districtai.core.model.MessageDraft
import com.distronode.districtai.core.model.UploadedMedia
import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.core.model.ClearIntelResponse
import com.distronode.districtai.core.model.DeviceListResponse
import com.distronode.districtai.core.model.DeviceRevokeResponse
import com.distronode.districtai.core.model.CallAnswerResponse
import com.distronode.districtai.core.model.DialResponse
import com.distronode.districtai.core.model.PushRegistrationResponse
import com.distronode.districtai.core.model.EnrichResponse
import com.distronode.districtai.core.model.NumberSearchResponse
import com.distronode.districtai.core.model.OwnedNumbersResponse
import com.distronode.districtai.core.model.MarkReadResponse
import com.distronode.districtai.core.model.MarkReadRequest
import com.distronode.districtai.core.model.UsageHistoryResponse
import com.distronode.districtai.core.model.UsageResponse
import com.distronode.districtai.core.model.SchedulingEnableResponse
import com.distronode.districtai.core.model.SchedulingHandOffResponse
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SendMessageResponse
import com.distronode.districtai.core.model.SendMessageRequest
import com.distronode.districtai.core.model.UnreadCountResponse
import com.distronode.districtai.core.model.TimelineResponse
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
import com.distronode.districtai.core.model.RenameResponse
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.model.WorkspaceRenameRequest
import com.distronode.districtai.core.model.RoutingRulesRequest
import com.distronode.districtai.core.model.OverviewResponse
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.ToolsPatchRequest
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.model.WorkspaceConfigResponse
import com.distronode.districtai.core.model.WorkspaceConfigSaveResponse
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.WorkspaceBilling
import com.distronode.districtai.core.model.WorkspaceBillingResponse
import com.distronode.districtai.core.model.WorkspaceListResponse
import com.distronode.districtai.core.model.CampaignStatus
import com.distronode.districtai.core.model.CampaignStatusResponse
import com.distronode.districtai.core.model.WorkflowListResponse
import com.distronode.districtai.core.model.WorkflowRunsResponse
import com.distronode.districtai.core.model.WorkflowToggleResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.InboundCallApi
import com.distronode.districtai.core.network.MessagingApi
import com.distronode.districtai.core.network.PushApi
import com.distronode.districtai.core.network.CampaignPauseRequest
import com.distronode.districtai.core.network.SchedulingEnableRequest
import com.distronode.districtai.core.network.SchedulingHandOffRequest
import com.distronode.districtai.core.network.SchedulingApi
import com.distronode.districtai.core.network.ClearIntelRequest
import com.distronode.districtai.core.network.CreateContactRequest
import com.distronode.districtai.core.network.DeviceRevokeRequest
import com.distronode.districtai.core.network.CallAnswerRequest
import com.distronode.districtai.core.network.DialRequest
import com.distronode.districtai.core.network.PushTokenRegisterRequest
import com.distronode.districtai.core.network.DistrictApi
import com.distronode.districtai.core.network.WorkflowToggleRequest
import com.distronode.districtai.core.network.EnrichRequest
import com.distronode.districtai.core.network.RoomTokenRequest
import com.distronode.districtai.core.network.UpdateContactRequest
import kotlinx.coroutines.CompletableDeferred

/**
 * A [DistrictApi] whose every answer is settable, shared by the repository tests in core-data and
 * the screen tests in the app.
 *
 * ⛔ ONE COPY, PUBLISHED FROM core-network's TEST FIXTURES, BECAUSE TWO HAD ALREADY DRIFTED. The app
 * and core-data each kept their own 1,200-line fake of this interface, and the copies disagreed on
 * what they recorded (one kept the workspace id of every read, the other per-endpoint lists and the
 * gates), so the same assertion could be written in one tier and not the other. Every recorder
 * either copy had is kept here, which is why some reads land in two lists.
 *
 * ⚠️ Deliberately a real implementation rather than a mocking framework: the interface is small, and
 * a hand-written fake makes "what did the caller actually ask for" assertable by reading a list
 * rather than by configuring argument captors.
 *
 * ⚠️ `open` so a test needing one bespoke endpoint can subclass and override just that one.
 */
open class FakeDistrictApi(
    /**
     * ⛔ THE MESSAGING SLICE, DELEGATED RATHER THAN INLINED, BECAUSE INLINING IT (five messaging
     * writes) PUTS THIS CLASS OVER detekt's `LargeClass` CEILING. `MessagingApi by`
     * satisfies the composed interface exactly as an inline stub did; a test reaches the recorders
     * and the settable results through this property (`messagingApi.messagingSaves`).
     *
     * ⚠️ A CONSTRUCTOR PARAMETER ONLY BECAUSE KOTLIN'S DELEGATION REQUIRES THE EXPRESSION AT
     * CONSTRUCTION. It is defaulted, so no existing `FakeDistrictApi()` call site changes — and it is the one
     * exception to the "mutable properties, not constructor parameters" note below, which is about
     * the LongParameterList ceiling and is unaffected by a single defaulted argument.
     */
    val messagingApi: FakeMessagingApi = FakeMessagingApi(),
    /**
     * ⛔ THE INBOUND-CALL AND PUSH SLICES, DELEGATED FOR THE REASON [messagingApi] IS: adding them
     * inline puts this class over detekt's `LargeClass` ceiling. Reach the
     * recorders through this property (`pushApi.answerRequests`, `pushApi.pushRegisterRequests`).
     *
     * ⚠️ ONE FAKE FOR TWO INTERFACES, unlike the production side where `HttpInboundCallApi` and
     * `HttpPushApi` are separate classes. That split exists so a dialler screen cannot answer a
     * call; a TEST fake has no such boundary to protect, and two objects would mean two properties
     * for one feature.
     */
    val pushApi: FakeInboundPushApi = FakeInboundPushApi(),
    /**
     * ⛔ THE BOOKING-PAGES SLICE, DELEGATED FOR THE SAME `LargeClass` REASON [messagingApi] IS. A
     * test reaches its recorders and settable results through this property
     * (`schedulingApi.schedulingEnables`).
     */
    val schedulingApi: FakeSchedulingApi = FakeSchedulingApi(),
) : DistrictApi,
    MessagingApi by messagingApi,
    InboundCallApi by pushApi,
    PushApi by pushApi,
    SchedulingApi by schedulingApi {

    // ⚠️ MUTABLE PROPERTIES, NOT CONSTRUCTOR PARAMETERS — as a constructor this hit detekt's
    // LongParameterList ceiling two sections in, and seventeen remain. Set them with `apply {}`.
    var workspaceListResult: ApiResult<WorkspaceListResponse> =
        ApiResult.Success(WorkspaceListResponse(success = true))
    var overviewResult: ApiResult<OverviewResponse> = ApiResult.Success(OverviewResponse(success = true))
    var callsResult: ApiResult<List<CallSummary>> = ApiResult.Success(emptyList())
    var detailResult: ApiResult<CallDetailResponse> = ApiResult.Success(CallDetailResponse(success = true))
    var transcriptResult: ApiResult<CallTranscriptResponse> =
        ApiResult.Success(CallTranscriptResponse(success = true, transcript = "Agent: hello."))
    var recordingResult: ApiResult<String> = ApiResult.Success("https://recordings.test/x.mp3")
    var contactsResult: ApiResult<ContactListResponse> =
        ApiResult.Success(ContactListResponse(success = true))
    var contactResult: ApiResult<ContactDetailResponse> =
        ApiResult.Success(ContactDetailResponse(success = true))
    var mutationResult: ApiResult<ContactMutationResponse> =
        ApiResult.Success(ContactMutationResponse(success = true))

    /** Every per-call read, so "was it fetched once or on every recomposition" is assertable. */
    val transcriptRequests: MutableList<Pair<String, String>> = mutableListOf()
    val recordingRequests: MutableList<Pair<String, String>> = mutableListOf()
    val detailRequests: MutableList<Pair<String, String>> = mutableListOf()

    /** Which workspace each overview request named. Omitting it is not an error, so assert on it. */
    val overviewRequests: MutableList<String?> = mutableListOf()

    /**
     * Every workspace-scoped read below that names one (overview, conversations, usage, owned
     * numbers, workspace billing), in order, beside the per-endpoint lists.
     *
     * ⚠️ RECORDED TWICE ON PURPOSE. A repository test asks "did it send the ACTIVE workspace" across
     * whichever reads it makes, and a screen test asks about one endpoint; omitting the id is not an
     * error, it just silently reports on whichever workspace the server picks.
     */
    var requestedWorkspaceIds: MutableList<String?> = mutableListOf()
        private set

    /** (workspaceId, callId) for detail, transcript and recording reads alike, in order. */
    var callReads: MutableList<Pair<String, String>> = mutableListOf()
        private set

    /** Every contacts mutation attempted, so role gating can be asserted as "never even called". */
    val mutations: MutableList<String> = mutableListOf()

    /** ⚠️ The full `contacts/update` bodies, because that route clears any column a body omits. */
    val contactUpdates: MutableList<UpdateContactRequest> = mutableListOf()

    override suspend fun workspaceList() = workspaceListResult

    override suspend fun overview(workspaceId: String?): ApiResult<OverviewResponse> {
        overviewRequests += workspaceId
        requestedWorkspaceIds += workspaceId
        return overviewResult
    }

    override suspend fun contacts(workspaceId: String, limit: Int, offset: Int) = contactsResult

    /** How many times the contact detail was read, so a "re-read on success" claim is checkable. */
    var contactRequestCount: Int = 0
        private set

    override suspend fun contact(workspaceId: String, contactId: String): ApiResult<ContactDetailResponse> {
        contactRequestCount += 1
        return contactResult
    }

    override suspend fun createContact(request: CreateContactRequest): ApiResult<ContactMutationResponse> {
        mutations += "create:${request.name}"
        return mutationResult
    }

    override suspend fun updateContact(request: UpdateContactRequest): ApiResult<ContactMutationResponse> {
        mutations += "update:${request.contactId}"
        contactUpdates += request
        return mutationResult
    }

    override suspend fun deleteContact(workspaceId: String, contactId: String): ApiResult<ContactMutationResponse> {
        mutations += "delete:$contactId"
        return mutationResult
    }

    override suspend fun calls(workspaceId: String, limit: Int, offset: Int) = callsResult

    override suspend fun callDetail(workspaceId: String, callId: String): ApiResult<CallDetailResponse> {
        detailRequests += workspaceId to callId
        callReads += workspaceId to callId
        return detailResult
    }

    override suspend fun callTranscript(
        workspaceId: String,
        callId: String,
    ): ApiResult<CallTranscriptResponse> {
        transcriptRequests += workspaceId to callId
        callReads += workspaceId to callId
        return transcriptResult
    }

    override suspend fun callRecordingUrl(workspaceId: String, callId: String): ApiResult<String> {
        recordingRequests += workspaceId to callId
        callReads += workspaceId to callId
        return recordingResult
    }

    // ── Inbox ────────────────────────────────────────────────────────────────
    // ⚠️ Defaults are WELL-FORMED envelopes for the same reason as every sibling above: a fake
    // standing in for a healthy server has to affirm `success`, or a repository rejects it as
    // contract drift. A test wanting the drift case sets it false explicitly.
    var conversationsResult: ApiResult<ConversationsResponse> =
        ApiResult.Success(ConversationsResponse(success = true))
    var timelineResult: ApiResult<TimelineResponse> =
        ApiResult.Success(TimelineResponse(success = true))
    var unreadCountResult: ApiResult<UnreadCountResponse> =
        ApiResult.Success(UnreadCountResponse(success = true, count = 0, workspaceId = "ws-1"))
    var sendResult: ApiResult<SendMessageResponse> =
        ApiResult.Success(SendMessageResponse(success = true))
    var markReadResult: ApiResult<MarkReadResponse> =
        ApiResult.Success(MarkReadResponse(success = true))

    /** Every send this fake was asked to make, so billable calls can be counted in a test. */
    var sends: MutableList<SendMessageRequest> = mutableListOf()
        private set

    override suspend fun conversations(workspaceId: String): ApiResult<ConversationsResponse> {
        requestedWorkspaceIds += workspaceId
        return conversationsResult
    }

    /**
     * Answers for a paged thread read, keyed by the `before` cursor the caller sent — null is the
     * first, newest window.
     *
     * ⛔ A MAP RATHER THAN A QUEUE, AND THAT IS THE ASSERTION. A queue would hand page two to any
     * second call, including one that echoed back the wrong cursor or none at all, which is
     * exactly the paging bug worth catching. Keying on the cursor means a page is only served to a
     * client that asked for it correctly. A `before` with no entry falls through to
     * [timelineResult], so every existing test keeps its single-answer fake.
     */
    var timelinePages: MutableMap<String?, ApiResult<TimelineResponse>> = mutableMapOf()

    /** Every `(before, beforeId)` this fake was asked for, in order, so a test can pin the pair. */
    var timelineCursors: MutableList<Pair<String?, String?>> = mutableListOf()
        private set

    /**
     * Holds every thread read until completed, so a test can let the draft read finish FIRST.
     * Against a fake both answer instantly and in launch order, which is the one ordering that hid
     * a restored draft's attachments being dropped. Left null by default.
     */
    var timelineGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun timeline(
        workspaceId: String,
        contactId: String?,
        address: String?,
        before: String?,
        beforeId: String?,
    ): ApiResult<TimelineResponse> {
        timelineCursors += before to beforeId
        timelineGate?.await()
        return timelinePages[before] ?: timelineResult
    }

    override suspend fun unreadCount(workspaceId: String): ApiResult<UnreadCountResponse> =
        unreadCountResult

    override suspend fun sendMessage(request: SendMessageRequest): ApiResult<SendMessageResponse> {
        sends += request
        return sendResult
    }

    override suspend fun markRead(request: MarkReadRequest): ApiResult<MarkReadResponse> =
        markReadResult

    // ── Composer: media, drafts, AI generation (Task A1) ─────────────────────
    // ⚠️ Same rule as every block above: the defaults are WELL-FORMED SUCCESSES. A composer fake
    // that answered `success = false` by default would make every unrelated test that opens a
    // thread fail on a draft restore it never asked about.
    var uploadMediaResult: ApiResult<MediaUploadResponse> = ApiResult.Success(
        MediaUploadResponse(
            success = true,
            media = UploadedMedia(
                id = "media-1",
                mimeType = "image/png",
                sizeBytes = 3,
                url = "https://www.distronode.test/api/media/media-1",
            ),
        ),
    )
    var draftResult: ApiResult<DraftResponse> = ApiResult.Success(DraftResponse(success = true))
    var draftsResult: ApiResult<DraftListResponse> =
        ApiResult.Success(DraftListResponse(success = true))
    var saveDraftResult: ApiResult<DraftResponse>? = null
    var deleteDraftResult: ApiResult<DraftDeleteResponse> =
        ApiResult.Success(DraftDeleteResponse(success = true))
    var generateDraftResult: ApiResult<AiDraftResponse> =
        ApiResult.Success(AiDraftResponse(success = true, draft = "Happy to help — when suits you?"))

    /** ⛔ Every draft PUT, so an autosave debounce can be counted rather than inferred. */
    var draftSaves: MutableList<DraftSaveRequest> = mutableListOf()
        private set

    /** ⛔ Every draft DELETE, keyed by thread. A blank composer must produce one of these, not a PUT. */
    var draftDeletes: MutableList<String> = mutableListOf()
        private set

    /** ⛔ Every generation, so a billable model call cannot be made twice by one tap unnoticed. */
    var generations: MutableList<AiDraftRequest> = mutableListOf()
        private set

    /** The multipart uploads requested, with their bytes, so a retry can be told from a re-read. */
    var uploads: MutableList<Triple<String, String, Int>> = mutableListOf()
        private set

    override suspend fun uploadMedia(
        workspaceId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<MediaUploadResponse> {
        uploads += Triple(fileName, mimeType, bytes.size)
        return uploadMediaResult
    }

    override suspend fun draft(workspaceId: String, threadKey: String): ApiResult<DraftResponse> =
        draftResult

    /**
     * ⛔ RECORDED, so "a viewer never asks for drafts" is assertable. The route excludes `viewer`
     * server-side, and a client that called it anyway would fire a known 403 on every Inbox load.
     */
    var draftReads: MutableList<String> = mutableListOf()
        private set

    override suspend fun drafts(workspaceId: String): ApiResult<DraftListResponse> {
        draftReads += workspaceId
        return draftsResult
    }

    override suspend fun saveDraft(request: DraftSaveRequest): ApiResult<DraftResponse> {
        draftSaves += request
        // ⚠️ Echoes the request back by default, which is what the real route does. A test that
        // needs a specific stored shape sets `saveDraftResult`.
        return saveDraftResult ?: ApiResult.Success(
            DraftResponse(
                success = true,
                draft = MessageDraft(
                    threadKey = request.threadKey,
                    body = request.body,
                    subject = request.subject,
                    mediaUrls = request.mediaUrls,
                    updatedAt = "2026-08-18T12:00:00.000Z",
                ),
            ),
        )
    }

    override suspend fun deleteDraft(
        workspaceId: String,
        threadKey: String,
    ): ApiResult<DraftDeleteResponse> {
        draftDeletes += threadKey
        return deleteDraftResult
    }

    override suspend fun generateDraft(request: AiDraftRequest): ApiResult<AiDraftResponse> {
        generations += request
        return generateDraftResult
    }

    // ── District HQ (Task A7) ────────────────────────────────────────────────
    var hqPromptResult: ApiResult<HqPromptResponse> =
        ApiResult.Success(HqPromptResponse(success = true, answer = "You had 3 calls this week."))
    var hqConfirmResult: ApiResult<HqConfirmResponse> =
        ApiResult.Success(HqConfirmResponse(success = true, executed = true, tool = "update_persona"))

    /**
     * Every prompt turn, WITH the history it carried.
     *
     * ⛔ THE HISTORY IS RECORDED, NOT JUST THE PROMPT. The route is stateless and drops any turn
     * whose role is neither `user` nor `model`, silently — so "did the client send the conversation,
     * and did it label each turn in the server's vocabulary" is the assertion that matters, and it
     * is only answerable if the request is kept whole.
     */
    val hqPrompts: MutableList<HqPromptRequest> = mutableListOf()

    /** Every confirmed write. ⛔ A write executes per entry here, so a count is a real-world claim. */
    val hqConfirms: MutableList<HqConfirmRequest> = mutableListOf()

    override suspend fun hqPrompt(request: HqPromptRequest): ApiResult<HqPromptResponse> {
        hqPrompts += request
        return hqPromptResult
    }

    override suspend fun hqConfirm(request: HqConfirmRequest): ApiResult<HqConfirmResponse> {
        hqConfirms += request
        return hqConfirmResult
    }

    // ── Analytics (Task A3) ──────────────────────────────────────────────────
    var analyticsResult: ApiResult<AnalyticsResponse> =
        ApiResult.Success(AnalyticsResponse(success = true))
    var usageResult: ApiResult<UsageResponse> = ApiResult.Success(UsageResponse(success = true))
    var usageHistoryResult: ApiResult<UsageHistoryResponse> =
        ApiResult.Success(UsageHistoryResponse(success = true))

    /** Every window that was asked for, so a range switch can be asserted as a real request. */
    val analyticsRequests: MutableList<Pair<String, AnalyticsRange>> = mutableListOf()
    val usageRequests: MutableList<String> = mutableListOf()

    /**
     * `<workspaceId>` to the `months` span asked for, per history read.
     *
     * ⚠️ THE SPAN IS RECORDED, NOT JUST THE COUNT. The repository owns the default so the phone
     * and the web console show the same window; a caller that started passing its own number
     * would be invisible to an assertion that only counted requests.
     */
    val usageHistoryRequests: MutableList<Pair<String, Int>> = mutableListOf()

    /**
     * Holds the analytics read open until it is completed.
     *
     * ⛔ THIS IS HOW "THE TWO READS RUN IN PARALLEL" IS ASSERTED AT ALL. Both requests complete
     * instantly against a fake, so a sequential implementation and a concurrent one produce
     * identical call lists and identical state — the difference is invisible to any assertion
     * about outcomes. Blocking one of them makes the ordering observable: with analytics parked,
     * a usage request that has ALREADY been issued proves the second call was not waiting on the
     * first. Left null by default so every other test is unaffected.
     */
    var analyticsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun analytics(
        workspaceId: String,
        range: AnalyticsRange,
    ): ApiResult<AnalyticsResponse> {
        analyticsRequests += workspaceId to range
        analyticsGate?.await()
        return analyticsResult
    }

    override suspend fun usage(workspaceId: String): ApiResult<UsageResponse> {
        usageRequests += workspaceId
        requestedWorkspaceIds += workspaceId
        return usageResult
    }

    override suspend fun usageHistory(
        workspaceId: String,
        months: Int,
    ): ApiResult<UsageHistoryResponse> {
        usageHistoryRequests += workspaceId to months
        return usageHistoryResult
    }

    // ── DGI and the number marketplace (Task A8) ─────────────────────────────
    var enrichResult: ApiResult<EnrichResponse> =
        ApiResult.Success(EnrichResponse(success = true, status = "pending"))
    var clearIntelResult: ApiResult<ClearIntelResponse> =
        ApiResult.Success(ClearIntelResponse(success = true))
    var searchResult: ApiResult<NumberSearchResponse> =
        ApiResult.Success(NumberSearchResponse(success = true))
    var ownedResult: ApiResult<OwnedNumbersResponse> =
        ApiResult.Success(OwnedNumbersResponse(success = true))

    /**
     * ⛔ EVERY ENRICH ATTEMPT. Each entry is one external crawl plus one LLM synthesis, so the
     * SIZE of this list is a claim about real spend — which is what makes "the poll never
     * re-triggered it" and "a viewer never reached it" checkable at all.
     */
    val enrichRequests: MutableList<EnrichRequest> = mutableListOf()
    val clearIntelRequests: MutableList<ClearIntelRequest> = mutableListOf()

    /** Each search's full filter tuple, so the form's values can be asserted as SENT. */
    val searchRequests: MutableList<List<String?>> = mutableListOf()
    val ownedRequests: MutableList<String> = mutableListOf()

    override suspend fun enrichContact(request: EnrichRequest): ApiResult<EnrichResponse> {
        enrichRequests += request
        return enrichResult
    }

    override suspend fun clearContactIntel(
        request: ClearIntelRequest,
    ): ApiResult<ClearIntelResponse> {
        clearIntelRequests += request
        return clearIntelResult
    }

    override suspend fun searchNumbers(
        workspaceId: String,
        areaCode: String?,
        country: String?,
        type: String?,
        provider: String?,
    ): ApiResult<NumberSearchResponse> {
        searchRequests += listOf(workspaceId, areaCode, country, type, provider)
        return searchResult
    }

    override suspend fun ownedNumbers(workspaceId: String): ApiResult<OwnedNumbersResponse> {
        ownedRequests += workspaceId
        requestedWorkspaceIds += workspaceId
        return ownedResult
    }

    // ── Devices ──────────────────────────────────────────────────────────────
    var devicesResult: ApiResult<DeviceListResponse> =
        ApiResult.Success(DeviceListResponse(success = true))

    /**
     * ⚠️ ONE STUB FOR BOTH WRITES, because the two routes answer the identical shape. Which one
     * was called is recorded in [deviceWrites] instead — a test asserting "revoke-all was the
     * call" reads a list rather than configuring two near-identical results.
     */
    var deviceRevokeResult: ApiResult<DeviceRevokeResponse> =
        ApiResult.Success(DeviceRevokeResponse(success = true, revoked = 1))

    /** How many times the list was read, so "did the revoke re-read" is assertable. */
    val deviceListRequests: MutableList<Unit> = mutableListOf()

    /** Every device write attempted, in order: `revoke:<id>` or `revoke-all`. */
    val deviceWrites: MutableList<String> = mutableListOf()

    /**
     * Every device call, reads and writes interleaved in order: `list`, `revoke:<id>`, `revoke-all`.
     *
     * ⚠️ NO `requestedWorkspaceIds` ENTRY FOR ANY OF THE THREE, and that is the contract rather than
     * an omission: device management is ACCOUNT-scoped, so there is no workspace to get wrong.
     */
    val deviceCalls: MutableList<String> = mutableListOf()

    override suspend fun devices(): ApiResult<DeviceListResponse> {
        deviceListRequests += Unit
        deviceCalls += "list"
        return devicesResult
    }

    override suspend fun revokeDevice(
        request: DeviceRevokeRequest,
    ): ApiResult<DeviceRevokeResponse> {
        deviceWrites += "revoke:${request.deviceId}"
        deviceCalls += "revoke:${request.deviceId}"
        return deviceRevokeResult
    }

    override suspend fun revokeAllDevices(): ApiResult<DeviceRevokeResponse> {
        deviceWrites += "revoke-all"
        deviceCalls += "revoke-all"
        return deviceRevokeResult
    }

    // ── Billing (Task A6) ────────────────────────────────────────────────────
    var workspaceBillingResult: ApiResult<WorkspaceBillingResponse> =
        ApiResult.Success(WorkspaceBillingResponse(success = true, billing = WorkspaceBilling()))

    /**
     * ⚠️ THE DEFAULT IS A HEALTHY BARE OBJECT, NOT AN AFFIRMED ENVELOPE, and that is the one place
     * this fake's convention differs from every sibling above. `GET /api/billing` sends no
     * `success` key at all, so `StripeBilling()` IS what a healthy empty account looks like —
     * there is no envelope for a repository to reject. A test wanting the outage sets
     * `billingUnavailable = true`, which arrives on a 200 rather than as a failure.
     */
    var stripeBillingResult: ApiResult<StripeBilling> = ApiResult.Success(StripeBilling())

    /**
     * Holds the workspace-billing read open until it is completed.
     *
     * ⛔ THE ONLY WAY "THE TWO READS ARE CONCURRENT" IS OBSERVABLE AT ALL — same device as
     * [analyticsGate]. Against a fake both complete instantly, so a sequential implementation and a
     * parallel one produce identical state; parking one makes the ordering visible. It matters more
     * here than on analytics: sequentially, a failing Stripe read would prevent the PLAN read being
     * issued, and the plan read is the half that works when Stripe does not.
     */
    var workspaceBillingGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Every workspace asked about, so the request can be asserted rather than inferred. */
    val workspaceBillingRequests: MutableList<String> = mutableListOf()

    /** ⚠️ Takes no workspace, so a count is all there is to record. Kept for ordering assertions. */
    val stripeBillingRequests: MutableList<Unit> = mutableListOf()

    override suspend fun workspaceBilling(
        workspaceId: String,
    ): ApiResult<WorkspaceBillingResponse> {
        workspaceBillingRequests += workspaceId
        requestedWorkspaceIds += workspaceId
        workspaceBillingGate?.await()
        return workspaceBillingResult
    }

    override suspend fun stripeBilling(): ApiResult<StripeBilling> {
        stripeBillingRequests += Unit
        return stripeBillingResult
    }

    // ── Workspace settings ───────────────────────────────────────────────────
    //
    // ⛔ THE TWO WRITES HERE RECORD THEIR REQUESTS, NOT JUST THEIR OUTCOMES, AND THAT IS THE WHOLE
    // POINT OF THIS FAKE FOR THIS SECTION. `workspace/tools` replaces `toolConfig.allowedTools`
    // wholesale, and `workspace/persona` merges only the keys it receives — so the interesting
    // assertion is never "did it return success", it is WHAT WAS ON THE WIRE: the complete list in
    // the order it was loaded, and nothing but the fields a human actually changed.
    var workspaceConfigResult: ApiResult<WorkspaceConfigResponse> =
        ApiResult.Success(WorkspaceConfigResponse(success = true, config = WorkspaceConfig()))
    var savePersonaResult: ApiResult<WorkspaceConfigSaveResponse> =
        ApiResult.Success(WorkspaceConfigSaveResponse(success = true))
    var saveToolsResult: ApiResult<WorkspaceConfigSaveResponse> =
        ApiResult.Success(WorkspaceConfigSaveResponse(success = true))

    /** ⚠️ Counted so a save's mandatory RE-READ is visible — neither route echoes its write. */
    val configRequests: MutableList<String> = mutableListOf()
    val personaPatches: MutableList<PersonaPatchRequest> = mutableListOf()
    val toolsPatches: MutableList<ToolsPatchRequest> = mutableListOf()

    override suspend fun workspaceConfig(workspaceId: String): ApiResult<WorkspaceConfigResponse> {
        configRequests += workspaceId
        return workspaceConfigResult
    }

    override suspend fun savePersona(
        request: PersonaPatchRequest,
    ): ApiResult<WorkspaceConfigSaveResponse> {
        personaPatches += request
        return savePersonaResult
    }

    override suspend fun saveTools(
        request: ToolsPatchRequest,
    ): ApiResult<WorkspaceConfigSaveResponse> {
        toolsPatches += request
        return saveToolsResult
    }

    // ── Workspace settings: the two destructive arrays (A4b) ─────────────────
    //
    // ⛔ THE REQUESTS ARE RECORDED WHOLE, NOT COUNTED. Both routes REPLACE their stored array with
    // exactly what arrives, so the only assertion worth making is what was on the wire — including
    // whether an untouched row came back byte-identical, which a count could never show.
    var saveDirectoryResult: ApiResult<WorkspaceConfigSaveResponse> =
        ApiResult.Success(WorkspaceConfigSaveResponse(success = true))
    var saveRoutingResult: ApiResult<WorkspaceConfigSaveResponse> =
        ApiResult.Success(WorkspaceConfigSaveResponse(success = true))

    val directoryPatches: MutableList<DirectoryPatchRequest> = mutableListOf()
    val routingPatches: MutableList<RoutingRulesRequest> = mutableListOf()

    override suspend fun saveDirectory(
        request: DirectoryPatchRequest,
    ): ApiResult<WorkspaceConfigSaveResponse> {
        directoryPatches += request
        return saveDirectoryResult
    }

    override suspend fun saveRoutingRules(
        request: RoutingRulesRequest,
    ): ApiResult<WorkspaceConfigSaveResponse> {
        routingPatches += request
        return saveRoutingResult
    }

    // ── Knowledge base and messaging (A4b) ───────────────────────────────────
    var knowledgeListResult: ApiResult<KnowledgeListResponse> =
        ApiResult.Success(KnowledgeListResponse(success = true))
    var knowledgeCreateResult: ApiResult<KnowledgeCreateResponse> =
        ApiResult.Success(KnowledgeCreateResponse(success = true))
    var knowledgeDeleteResult: ApiResult<KnowledgeDeleteResponse> =
        ApiResult.Success(KnowledgeDeleteResponse(success = true))
    var knowledgeModeResult: ApiResult<KnowledgeModeResponse> =
        ApiResult.Success(KnowledgeModeResponse(success = true, mode = "internal"))
    var saveKnowledgeModeResult: ApiResult<KnowledgeModeResponse> =
        ApiResult.Success(KnowledgeModeResponse(success = true, mode = "linked"))

    /** ⚠️ Counted so "the list was re-read after the write" is checkable — both writes must. */
    val knowledgeListRequests: MutableList<String> = mutableListOf()

    /** ⛔ One entry here is one embedding run in production. A count is a claim about spend. */
    val knowledgeCreates: MutableList<KnowledgeCreateRequest> = mutableListOf()

    /** (workspaceId, documentId) — the parameter SPELLING is asserted at the HTTP layer. */
    val knowledgeDeletes: MutableList<Pair<String, String>> = mutableListOf()
    val knowledgeModePatches: MutableList<KnowledgeModePatchRequest> = mutableListOf()

    override suspend fun knowledgeDocuments(
        workspaceId: String,
    ): ApiResult<KnowledgeListResponse> {
        knowledgeListRequests += workspaceId
        return knowledgeListResult
    }

    override suspend fun createDocument(
        request: KnowledgeCreateRequest,
    ): ApiResult<KnowledgeCreateResponse> {
        knowledgeCreates += request
        return knowledgeCreateResult
    }

    override suspend fun deleteDocument(
        workspaceId: String,
        documentId: String,
    ): ApiResult<KnowledgeDeleteResponse> {
        knowledgeDeletes += workspaceId to documentId
        return knowledgeDeleteResult
    }

    override suspend fun knowledgeMode(workspaceId: String): ApiResult<KnowledgeModeResponse> =
        knowledgeModeResult

    override suspend fun saveKnowledgeMode(
        request: KnowledgeModePatchRequest,
    ): ApiResult<KnowledgeModeResponse> {
        knowledgeModePatches += request
        return saveKnowledgeModeResult
    }

    // ── Members and rename (A5) ──────────────────────────────────────────────
    var memberListResult: ApiResult<MemberListResponse> =
        ApiResult.Success(MemberListResponse(success = true))
    var addMemberResult: ApiResult<MemberMutationResponse> = ApiResult.Success(
        MemberMutationResponse(
            success = true,
            member = WorkspaceMember(email = "new@example.com", role = "client"),
        ),
    )
    var changeRoleResult: ApiResult<MemberMutationResponse> = ApiResult.Success(
        MemberMutationResponse(
            success = true,
            member = WorkspaceMember(email = "b@example.com", role = "agency"),
        ),
    )

    /** ⚠️ NO `member` KEY, because the DELETE genuinely answers a bare `{success:true}`. */
    var removeMemberResult: ApiResult<MemberMutationResponse> =
        ApiResult.Success(MemberMutationResponse(success = true))
    var renameResult: ApiResult<RenameResponse> =
        ApiResult.Success(RenameResponse(success = true, name = "Renamed"))

    /** ⚠️ Counted so "the roster was re-read after the write" is checkable — every write must. */
    val memberListRequests: MutableList<String> = mutableListOf()
    val memberAdds: MutableList<MemberAddRequest> = mutableListOf()
    val memberRoleChanges: MutableList<MemberRoleRequest> = mutableListOf()

    /** (workspaceId, email) — the DELETE carries both as QUERY parameters and no body. */
    val memberRemovals: MutableList<Pair<String, String>> = mutableListOf()
    val renameRequests: MutableList<WorkspaceRenameRequest> = mutableListOf()

    override suspend fun members(workspaceId: String): ApiResult<MemberListResponse> {
        memberListRequests += workspaceId
        return memberListResult
    }

    override suspend fun addMember(
        request: MemberAddRequest,
    ): ApiResult<MemberMutationResponse> {
        memberAdds += request
        return addMemberResult
    }

    override suspend fun changeMemberRole(
        request: MemberRoleRequest,
    ): ApiResult<MemberMutationResponse> {
        memberRoleChanges += request
        return changeRoleResult
    }

    override suspend fun removeMember(
        workspaceId: String,
        email: String,
    ): ApiResult<MemberMutationResponse> {
        memberRemovals += workspaceId to email
        return removeMemberResult
    }

    override suspend fun renameWorkspace(
        request: WorkspaceRenameRequest,
    ): ApiResult<RenameResponse> {
        renameRequests += request
        return renameResult
    }

    // ── Rooms and meetings ───────────────────────────────────────────────────
    /**
     * ⚠️ THE DEFAULT CARRIES NO GUEST INVITE, which is the VIEWER shape rather than the common
     * one. Deliberate: a test that cares about the invite has to say so, and a test that does not
     * cannot accidentally assert against a link the server would have withheld.
     */
    var roomTokenResult: ApiResult<RoomTokenResponse> = ApiResult.Success(
        RoomTokenResponse(success = true, token = "test-jwt", url = "wss://livekit.test"),
    )
    var meetingsResult: ApiResult<List<MeetingSummary>> = ApiResult.Success(emptyList())
    var meetingDetailResult: ApiResult<MeetingDetail> = ApiResult.Success(MeetingDetail())

    /** Every room name a token was minted for, in order — the `meet_` prefix guard reads this. */
    val roomTokenRequests: MutableList<RoomTokenRequest> = mutableListOf()
    val meetingsRequests: MutableList<String> = mutableListOf()

    /** `<workspaceId>/<meetingId>` per detail read, so the tenant scoping is assertable. */
    val meetingDetailRequests: MutableList<String> = mutableListOf()

    override suspend fun roomToken(request: RoomTokenRequest): ApiResult<RoomTokenResponse> {
        roomTokenRequests += request
        return roomTokenResult
    }

    override suspend fun meetings(workspaceId: String): ApiResult<List<MeetingSummary>> {
        meetingsRequests += workspaceId
        return meetingsResult
    }

    // ── The outbound softphone ───────────────────────────────────────────────
    /**
     * ⚠️ THE DEFAULT IS A REFUSAL, NOT A SUCCESS, WHICH IS THE OPPOSITE OF EVERY OTHER STUB HERE
     * AND IS DELIBERATE. A fake that dialled successfully by default would let a test that never
     * mentions dialling still walk the whole connect path — and this is the one route where "it
     * quietly worked" is the wrong default, because in production it spends money. A test that
     * cares about a placed call has to say so.
     */
    var dialResult: ApiResult<DialResponse> = ApiResult.HttpFailure(
        status = 400,
        message = "no dial stubbed",
    )

    /** Every dial attempted, in order — "did it dial exactly once" is read from this. */
    val dialRequests: MutableList<DialRequest> = mutableListOf()

    override suspend fun dial(request: DialRequest): ApiResult<DialResponse> {
        dialRequests += request
        return dialResult
    }

    override suspend fun meetingDetail(
        workspaceId: String,
        meetingId: String,
    ): ApiResult<MeetingDetail> {
        meetingDetailRequests += "$workspaceId/$meetingId"
        return meetingDetailResult
    }

    // ── The automation monitor ───────────────────────────────────────────────
    // ⚠️ Defaults are WELL-FORMED envelopes, like every sibling: a fake standing in for a healthy
    // server has to affirm `success`, or the repository rejects it as contract drift.
    var workflowsResult: ApiResult<WorkflowListResponse> =
        ApiResult.Success(WorkflowListResponse(success = true))
    var workflowRunsResult: ApiResult<WorkflowRunsResponse> =
        ApiResult.Success(WorkflowRunsResponse(success = true))
    var workflowToggleResult: ApiResult<WorkflowToggleResponse> =
        ApiResult.Success(WorkflowToggleResponse(success = true))

    /**
     * ⛔ A REAL `campaign` OBJECT, NOT THE DTO'S NULL DEFAULT. The repository treats an absent one
     * as contract drift rather than as an unconfigured campaign, so a fake that omitted it would
     * make every test on this screen exercise the drift path.
     */
    var campaignStatusResult: ApiResult<CampaignStatusResponse> =
        ApiResult.Success(CampaignStatusResponse(success = true, campaign = CampaignStatus()))

    /** Which workspace each list read named, so "was it read once" is assertable. */
    val workflowListRequests: MutableList<String> = mutableListOf()

    /** `<workspaceId>/<workflowId>/<limit>/<offset>` per runs read — the paging is read from this. */
    val workflowRunRequests: MutableList<String> = mutableListOf()

    /** Every toggle attempted, so a viewer's gating is assertable as "never even called". */
    val workflowToggles: MutableList<WorkflowToggleRequest> = mutableListOf()
    val campaignStatusRequests: MutableList<String> = mutableListOf()

    /**
     * Every pause/resume attempted, so a viewer's gating is assertable as "never even called" and
     * the merge-only body is assertable as sent.
     */
    val campaignPauses: MutableList<CampaignPauseRequest> = mutableListOf()

    /**
     * ⛔ NULL MEANS "ECHO THE REQUEST BACK", WHICH IS WHAT THE REAL ROUTE DOES. It derives its
     * reply from the object it merged, so a fake returning a FIXED campaign would let a caller
     * that ignored the response — and re-rendered its own optimistic guess — pass every test.
     * Set this only to pin a failure or a deliberately surprising reply.
     */
    var campaignPauseResult: ApiResult<CampaignStatusResponse>? = null

    override suspend fun workflows(workspaceId: String): ApiResult<WorkflowListResponse> {
        workflowListRequests += workspaceId
        return workflowsResult
    }

    override suspend fun workflowRuns(
        workspaceId: String,
        workflowId: String,
        limit: Int,
        offset: Int,
    ): ApiResult<WorkflowRunsResponse> {
        workflowRunRequests += "$workspaceId/$workflowId/$limit/$offset"
        return workflowRunsResult
    }

    override suspend fun setWorkflowActive(
        request: WorkflowToggleRequest,
    ): ApiResult<WorkflowToggleResponse> {
        workflowToggles += request
        return workflowToggleResult
    }

    override suspend fun campaignStatus(workspaceId: String): ApiResult<CampaignStatusResponse> {
        campaignStatusRequests += workspaceId
        return campaignStatusResult
    }

    override suspend fun setCampaignEnabled(
        request: CampaignPauseRequest,
    ): ApiResult<CampaignStatusResponse> {
        campaignPauses += request
        return campaignPauseResult ?: ApiResult.Success(
            CampaignStatusResponse(
                success = true,
                campaign = CampaignStatus(infiniteSdrEnabled = request.infiniteSdrEnabled),
            ),
        )
    }
}

/** A minimal call row for tests that only care about identity and a couple of fields. */
fun testCall(
    id: String = "c1",
    status: String = "completed",
    recordingUrl: String? = null,
) = CallSummary(
    id = id,
    type = "inbound",
    number = "Ada",
    status = status,
    duration = "1m 5s",
    time = "Aug 15, 02:30 PM",
    aiSummary = "Booked an appointment.",
    recordingUrl = recordingUrl,
    hasTranscript = false,
    callerName = "Ada",
    from = "+14165550142",
    direction = "inbound",
    durationRaw = 65,
    summary = "Booked an appointment.",
    createdAt = "2026-08-15T14:30:00.000Z",
)

/**
 * The messaging fake, EXTRACTED FROM [FakeDistrictApi].
 *
 * ⛔ ITS OWN CLASS BECAUSE THE FIVE MESSAGING WRITES PUT THE COMPOSED FAKE OVER detekt's
 * `LargeClass` CEILING, and the healthy answer to a class that has grown too big is
 * another class rather than a raised threshold — the same call `ContractFixtures` and
 * `MembersDialogs` record. [FakeDistrictApi] keeps satisfying `MessagingApi` through interface DELEGATION, so
 * the split changes only where the endpoints live, not which ones the fake covers.
 *
 * ⛔ THE WRITES RECORD THEIR REQUESTS, NOT A COUNT, BECAUSE THE BODY IS THE CONTRACT ON THIS
 * SURFACE. Five operations share ONE path and are told apart only by an `action` string inside the
 * JSON, and the upsert's blank-secret semantics live in which KEYS are present. A fake that only
 * counted calls could not tell "kept the stored auth token" from "sent an empty one", which is the
 * single most consequential difference this screen can produce.
 */
/**
 * The booking-pages fake, EXTRACTED FROM [FakeDistrictApi].
 *
 * ⛔ ITS OWN CLASS BECAUSE THE COMPOSED FAKE IS AT detekt's `LargeClass` CEILING, and the healthy
 * answer to a class that has grown too big is another class rather than a raised threshold — the
 * same call [FakeMessagingApi] records. The composed fake keeps satisfying `SchedulingApi` through
 * interface DELEGATION, so nothing about which endpoints it covers has changed; only where they
 * live.
 *
 * ⛔ NEITHER READ DEFAULT AFFIRMS AN ENVELOPE, BECAUSE NEITHER ROUTE HAS ONE. Every sibling in the
 * composed fake sets `success = true` so a repository does not reject a healthy fake as contract
 * drift; these two answer `{eligible, canManage, tenant}` and `{ok, status, publicHost, error}`, so
 * the equivalent "well-formed server" default is an ELIGIBLE workspace with no tenancy row — the
 * ordinary state of every workspace before anybody presses Enable.
 */
class FakeSchedulingApi : SchedulingApi {

    var schedulingStatusResult: ApiResult<SchedulingStatusResponse> =
        ApiResult.Success(SchedulingStatusResponse(eligible = true, canManage = true))

    /**
     * ⛔ THE DEFAULT IS A REFUSAL, matching the dial fake and for the same class of reason.
     * Provisioning creates a tenancy at a third party and a DNS record at another one; a fake that
     * answered a cheerful 202 by default would let a test walk that path without ever saying it
     * meant to.
     */
    var schedulingEnableResult: ApiResult<SchedulingEnableResponse> =
        ApiResult.HttpFailure(status = 403, message = "no enable stubbed")

    /**
     * ⚠️ A PLAUSIBLE `Location`, NOT A BARE STRING. The real client (`redirectTarget`) refuses
     * anything that is not https, so a fake answering "ok" would hand every test a value
     * production can never produce.
     */
    var schedulingSsoResult: ApiResult<String> =
        ApiResult.Success("https://acme-book.distronode.com/v1/auth/sso?token=stub")

    val schedulingStatusRequests: MutableList<String> = mutableListOf()

    /** Every enable attempted, whole. "Provisioned once, for the right workspace". */
    val schedulingEnables: MutableList<SchedulingEnableRequest> = mutableListOf()

    /** `<workspaceId>|<next>` per hand-off — the `next` is asserted from this. */
    val schedulingSsoRequests: MutableList<String> = mutableListOf()

    /**
     * ⚠️ A URL ON THIS APP'S OWN WEBSITE HOST, for the reason [schedulingSsoResult] is a plausible
     * `Location`: the repository refuses anything that is not https AND on the configured origin,
     * so a fake answering "ok" would put every test that never mentioned a host on the refusal
     * path without saying it meant to.
     */
    var schedulingHandOffResult: ApiResult<SchedulingHandOffResponse> = ApiResult.Success(
        SchedulingHandOffResponse(
            url = "https://www.distronode.com/dashboard/handoff?code=stub&next=%2Fdashboard",
            expiresIn = 60,
        ),
    )

    /** Every dashboard hand-off attempted, whole — the `next` is asserted from this. */
    val schedulingHandOffs: MutableList<SchedulingHandOffRequest> = mutableListOf()

    /**
     * ⚠️ HOLDS THE DASHBOARD MINT OPEN until a test completes it, so a configuration change can land
     * while the request is in flight, which is the window the real 60-second mint leaves open.
     */
    var schedulingHandOffGate: CompletableDeferred<Unit>? = null

    override suspend fun schedulingStatus(
        workspaceId: String,
    ): ApiResult<SchedulingStatusResponse> {
        schedulingStatusRequests += workspaceId
        return schedulingStatusResult
    }

    override suspend fun enableScheduling(
        request: SchedulingEnableRequest,
    ): ApiResult<SchedulingEnableResponse> {
        schedulingEnables += request
        return schedulingEnableResult
    }

    override suspend fun schedulingSsoTarget(
        workspaceId: String,
        next: String,
    ): ApiResult<String> {
        schedulingSsoRequests += "$workspaceId|$next"
        return schedulingSsoResult
    }

    override suspend fun schedulingHandOff(
        request: SchedulingHandOffRequest,
    ): ApiResult<SchedulingHandOffResponse> {
        schedulingHandOffs += request
        schedulingHandOffGate?.await()
        return schedulingHandOffResult
    }
}

class FakeMessagingApi : MessagingApi {

    var messagingResult: ApiResult<MessagingResponse> =
        ApiResult.Success(MessagingResponse(success = true))
    val messagingRequests: MutableList<String> = mutableListOf()

    override suspend fun messaging(workspaceId: String): ApiResult<MessagingResponse> {
        messagingRequests += workspaceId
        return messagingResult
    }

    var saveAccountResult: ApiResult<MessagingAccountSaveResponse> =
        ApiResult.Success(
            MessagingAccountSaveResponse(
                success = true,
                accountId = "acct-new",
                defaultAccountId = "acct-new",
            ),
        )
    var setDefaultResult: ApiResult<MessagingDefaultResponse> =
        ApiResult.Success(MessagingDefaultResponse(success = true, defaultAccountId = "acct-twilio"))
    var setChannelDefaultResult: ApiResult<MessagingChannelDefaultResponse> =
        ApiResult.Success(MessagingChannelDefaultResponse(success = true))
    var deleteAccountResult: ApiResult<MessagingDefaultResponse> =
        ApiResult.Success(MessagingDefaultResponse(success = true))
    var saveCreatorCellResult: ApiResult<MessagingMetaResponse> =
        ApiResult.Success(MessagingMetaResponse(success = true))
    var testCredentialsResult: ApiResult<MessagingTestResponse> =
        ApiResult.Success(MessagingTestResponse(success = true))

    val messagingSaves: MutableList<MessagingAccountRequest> = mutableListOf()
    val messagingDefaults: MutableList<MessagingDefaultRequest> = mutableListOf()
    val messagingChannelDefaults: MutableList<MessagingChannelDefaultRequest> = mutableListOf()

    /** ⛔ One entry is one account gone and its phone-number claims released. */
    val messagingDeletes: MutableList<MessagingDeleteRequest> = mutableListOf()
    val messagingMetaWrites: MutableList<MessagingMetaRequest> = mutableListOf()

    /** ⛔ One entry is one authenticated carrier call from our origin IPs, capped at 10/min. */
    val messagingTests: MutableList<MessagingTestRequest> = mutableListOf()

    override suspend fun saveMessagingAccount(
        request: MessagingAccountRequest,
    ): ApiResult<MessagingAccountSaveResponse> {
        messagingSaves += request
        return saveAccountResult
    }

    override suspend fun setDefaultAccount(
        request: MessagingDefaultRequest,
    ): ApiResult<MessagingDefaultResponse> {
        messagingDefaults += request
        return setDefaultResult
    }

    override suspend fun setChannelDefault(
        request: MessagingChannelDefaultRequest,
    ): ApiResult<MessagingChannelDefaultResponse> {
        messagingChannelDefaults += request
        return setChannelDefaultResult
    }

    override suspend fun deleteMessagingAccount(
        request: MessagingDeleteRequest,
    ): ApiResult<MessagingDefaultResponse> {
        messagingDeletes += request
        return deleteAccountResult
    }

    override suspend fun saveCreatorCell(
        request: MessagingMetaRequest,
    ): ApiResult<MessagingMetaResponse> {
        messagingMetaWrites += request
        return saveCreatorCellResult
    }

    override suspend fun testMessagingCredentials(
        request: MessagingTestRequest,
    ): ApiResult<MessagingTestResponse> {
        messagingTests += request
        return testCredentialsResult
    }
}

/**
 * The inbound-call and push slices of [FakeDistrictApi], as their own object.
 *
 * ⛔ SPLIT OUT BECAUSE `FakeDistrictApi` CROSSED detekt's `LargeClass` CEILING AGAIN — the same
 * answer the messaging slice got, and the healthy one: another class rather than a raised
 * threshold. The behaviour is identical to an inline stub; only the reach changes.
 */
class FakeInboundPushApi : InboundCallApi, PushApi {
    /**
     * ⚠️ THE DEFAULT IS A REFUSAL, MATCHING `FakeDistrictApi.dialResult` AND FOR A RELATED REASON. Answering does
     * not spend money, but it DOES write the server's rendezvous and put a microphone into a live
     * customer conversation — so a test that never mentions answering must not walk that path by
     * accident.
     */
    var answerResult: ApiResult<CallAnswerResponse> = ApiResult.HttpFailure(
        status = 409,
        message = "no answer stubbed",
    )

    /** Every answer attempted, as `callId/workspaceId` — "answered once, for the right call". */
    val answerRequests: MutableList<String> = mutableListOf()

    /**
     * When set, [answerCall] waits for it before returning: an answer round trip still in flight.
     *
     * ⚠️ RECORDED BEFORE THE WAIT, so "the request went out" is assertable while it is held.
     */
    var answerGate: CompletableDeferred<Unit>? = null

    override suspend fun answerCall(
        callId: String,
        request: CallAnswerRequest,
    ): ApiResult<CallAnswerResponse> {
        answerRequests += "$callId/${request.workspaceId}"
        answerGate?.await()
        return answerResult
    }

    /**
     * ⚠️ THE PUSH DEFAULTS **DO** SUCCEED, unlike the two above, and the asymmetry is the point:
     * registration is an idempotent upsert that costs nothing and rings nobody, so the interesting
     * assertions are about ORDER and COUNT rather than about a path being walked by accident.
     */
    var pushRegisterResult: ApiResult<PushRegistrationResponse> =
        ApiResult.Success(PushRegistrationResponse(success = true))

    var pushUnregisterResult: ApiResult<PushRegistrationResponse> =
        ApiResult.Success(PushRegistrationResponse(success = true))

    /** Every token registered, in order. ⚠️ A rotation is two entries, not one. */
    val pushRegisterRequests: MutableList<PushTokenRegisterRequest> = mutableListOf()

    /** How many times this installation asked to be unregistered. */
    var pushUnregisterCount: Int = 0

    override suspend fun registerPushToken(
        request: PushTokenRegisterRequest,
    ): ApiResult<PushRegistrationResponse> {
        pushRegisterRequests += request
        return pushRegisterResult
    }

    override suspend fun unregisterPushToken(): ApiResult<PushRegistrationResponse> {
        pushUnregisterCount += 1
        return pushUnregisterResult
    }
}
