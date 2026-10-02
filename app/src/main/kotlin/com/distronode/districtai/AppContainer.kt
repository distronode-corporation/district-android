package com.distronode.districtai

import android.content.Context
import com.distronode.districtai.applinks.AppLinkDeepLinks
import com.distronode.districtai.auth.DeviceIdentity
import com.distronode.districtai.auth.LoginController
import com.distronode.districtai.auth.SessionSignal
import com.distronode.districtai.core.auth.KeystoreTokenStore
import com.distronode.districtai.core.auth.NativeAuthApi
import com.distronode.districtai.core.auth.NativeLoginFlow
import com.distronode.districtai.core.auth.RevokeApi
import com.distronode.districtai.core.auth.RevokeResult
import com.distronode.districtai.core.auth.TokenRefreshCoordinator
import com.distronode.districtai.core.auth.TokenStore
import com.distronode.districtai.core.data.AnalyticsRepository
import com.distronode.districtai.core.data.BillingRepository
import com.distronode.districtai.core.data.CallControlRepository
import com.distronode.districtai.core.data.CallHandlingRepository
import com.distronode.districtai.core.data.DeskRepository
import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.data.PersonaOptionsRepository
import com.distronode.districtai.core.data.DevicesRepository
import com.distronode.districtai.core.data.DialRepository
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.ContactsRepository
import com.distronode.districtai.core.data.HqRepository
import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.KnowledgeRepository
import com.distronode.districtai.core.data.MeetingsRepository
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.data.MessagingRepository
import com.distronode.districtai.core.data.NumbersRepository
import com.distronode.districtai.core.data.OverviewRepository
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.SetupRepository
import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.call.AndroidForegroundCallHost
import com.distronode.districtai.call.InboundCallSessionFactory
import com.distronode.districtai.call.IncomingCallController
import com.distronode.districtai.call.IncomingCallSurfaces
import com.distronode.districtai.core.data.InboundCallRepository
import com.distronode.districtai.core.data.PreferencesWorkspaceSelectionStore
import com.distronode.districtai.core.data.PushTokenRepository
import com.distronode.districtai.push.AndroidPushNotifier
import com.distronode.districtai.push.FirebasePushTokenSource
import com.distronode.districtai.push.PushDeepLinks
import com.distronode.districtai.push.PushMessageHandler
import com.distronode.districtai.push.PushNotifier
import com.distronode.districtai.push.PushRegistrar
import com.distronode.districtai.core.data.WorkflowsRepository
import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.data.WorkspaceRepository
import com.distronode.districtai.core.network.CallHandlingApi
import com.distronode.districtai.core.network.DistrictApi
import com.distronode.districtai.core.network.DistrictApiClient
import com.distronode.districtai.core.network.InboxExtrasApi
import com.distronode.districtai.core.network.PersonaApi
import com.distronode.districtai.core.network.DistrictHttp
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.media.LiveKitCallEngine
import com.distronode.districtai.core.network.HttpCallControlApi
import com.distronode.districtai.core.network.HttpCallHandlingApi
import com.distronode.districtai.core.network.HttpDeskApi
import com.distronode.districtai.core.network.HttpDistrictApi
import com.distronode.districtai.core.network.HttpInboxExtrasApi
import com.distronode.districtai.core.network.HttpPersonaApi
import com.distronode.districtai.core.network.HttpSetupApi
import com.distronode.districtai.core.network.HttpSupportApi
import com.distronode.districtai.telecom.AndroidTelecomBridge
import com.distronode.districtai.telecom.TelecomBridge
import com.distronode.districtai.ui.inbox.AttachmentReader
import com.distronode.districtai.ui.inbox.ContentResolverAttachmentReader
import com.distronode.districtai.ui.inbox.MediaImageLoader
import com.distronode.districtai.ui.inbox.OkHttpMediaImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The app's object graph, wired by hand.
 *
 * ⚠️ NO HILT YET, AND THIS IS A DELIBERATE DEFERRAL RATHER THAN AN OVERSIGHT — the plan named it
 * for this task. Hilt earns its place when construction becomes hard: many scopes, many
 * consumers, generated factories worth the annotation processor. Right now there are seven
 * objects, one scope (the process), and each is constructed exactly once, so Hilt would add a
 * KSP processor and a layer of generated indirection to replace the twenty readable lines below.
 * Revisit when feature modules start needing their own scoped graphs — the constructor
 * injection here is already Hilt-shaped, so that migration is mechanical.
 *
 * ⛔ ONE OkHttpClient FOR THE WHOLE APP. It owns a connection pool and a thread pool, so a
 * per-call-site instance means no connection reuse and a fresh TLS handshake on every request.
 * The login screen originally constructed its own bare `OkHttpClient()`; this is where that was
 * consolidated, and the auth API and the district API now share one pool — which matters most on
 * exactly the path that uses both, since a token refresh and the request that needed it go to
 * the same host.
 *
 * ⛔ AND ONE TokenRefreshCoordinator, WHICH IS NOT NEGOTIABLE. It holds the access token in
 * memory and serialises refreshes behind a mutex; a second instance would have its own mutex and
 * its own idea of the current token, so two of them can present the SAME refresh token
 * concurrently. The server treats a re-presented refresh token as theft and revokes the entire
 * token family, signing the user out everywhere. The single-flight guarantee is only a guarantee
 * if there is one instance.
 */
class AppContainer(
    context: Context,
    /**
     * The scope for work that MUST NOT be cancelled by a configuration change or an activity
     * finishing.
     *
     * ⛔ EXISTS FOR THE TOKEN EXCHANGE SPECIFICALLY. Running it in the activity's `lifecycleScope`
     * meant a rotation between the server rotating the authorization code and the coordinator
     * adopting the result left the code spent server-side and the tokens nowhere. `SupervisorJob`
     * so one failed login cannot cancel a later one; `Main.immediate` because the work it launches
     * dispatches its own IO and the state it writes is read by Compose.
     */
    private val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /**
     * What a test puts in place of the credential store, the network, the push token and the
     * media engine. See [AppContainerSeams]; production passes nothing and gets the real ones.
     */
    seams: AppContainerSeams = AppContainerSeams(),
) {

    private val appContext = context.applicationContext

    private val httpClient = DistrictHttp.client()

    private val authApi = NativeAuthApi(baseUrl = ApiEnvironment.baseUrl, client = httpClient)

    /**
     * ⛔ HELD AS A FIELD RATHER THAN CONSTRUCTED INLINE INTO THE COORDINATOR, because
     * [signOut] has to read the refresh token BEFORE the coordinator wipes it and has to write
     * the revoke outbox afterwards. Still exactly one instance: two would each hold their own
     * `SharedPreferences` editor and could interleave a write with a wipe.
     */
    private val tokenStore: TokenStore = seams.tokenStore ?: KeystoreTokenStore(appContext)

    private val revokeApi: RevokeApi = seams.revokeApi ?: authApi

    /** See the ⛔ above: exactly one, for the process lifetime. */
    val tokenCoordinator: TokenRefreshCoordinator = TokenRefreshCoordinator(
        store = this.tokenStore,
        refreshApi = authApi,
    )

    /**
     * ⛔ ONE CLIENT FOR EVERY API INTERFACE, INCLUDING THE ONES OUTSIDE [HttpDistrictApi]. It owns
     * the bearer and its acquisition, the single-shot 401 refresh-and-retry and the dispatcher
     * hop. A second instance would be a second place any of those could
     * diverge, and two of them racing a refresh is exactly what `TokenRefreshCoordinator`'s mutex
     * exists to prevent. Extracted to a val when desk, support, persona, call handling and the
     * inbox/call extras landed as their own interfaces beside [HttpDistrictApi].
     */
    private val apiClient = DistrictApiClient(
        // ⚠️ ApiEnvironment.baseUrl has no trailing slash, and OkHttp needs one on a base
        // URL or the last path segment is treated as a file name and replaced rather than
        // appended to. Added here rather than in ApiEnvironment because the string is also
        // used for non-OkHttp purposes.
        baseUrl = "${ApiEnvironment.baseUrl}/".toHttpUrl(),
        httpClient = httpClient,
        tokens = tokenCoordinator,
    )

    private val districtApi: DistrictApi = seams.districtApi ?: HttpDistrictApi(apiClient)

    /**
     * ⚠️ SEVERAL SMALL INTERFACES BESIDE [HttpDistrictApi] RATHER THAN MORE SECTIONS OF IT, for a
     * process reason rather than an architectural one: they were built in parallel, and a shared
     * 85-method interface is the one file four authors cannot edit at once. They share [apiClient],
     * so nothing about authentication or error mapping differs between them.
     */
    private val personaApi: PersonaApi = seams.personaApi ?: HttpPersonaApi(apiClient)
    private val callHandlingApi: CallHandlingApi = seams.callHandlingApi ?: HttpCallHandlingApi(apiClient)
    private val inboxExtrasApi: InboxExtrasApi = seams.inboxExtrasApi ?: HttpInboxExtrasApi(apiClient)
    private val callControlApi = HttpCallControlApi(apiClient)

    val workspaceRepository: WorkspaceRepository = WorkspaceRepository(
        api = districtApi,
        selectionStore = PreferencesWorkspaceSelectionStore(appContext),
    )

    val overviewRepository: OverviewRepository = OverviewRepository(districtApi)

    val callsRepository: CallsRepository = CallsRepository(districtApi)

    val contactsRepository: ContactsRepository = ContactsRepository(districtApi)

    /**
     * The unified Inbox.
     *
     * ⚠️ NOT PAGED, so unlike the calls and contacts repositories this one holds no Pager and can be
     * constructed as plainly as it looks. See [InboxRepository] for why paging is the wrong tool here.
     */
    val inboxRepository: InboxRepository = InboxRepository(districtApi)

    /**
     * The reply composer's attachments, saved drafts and AI generator.
     *
     * ⚠️ SEPARATE FROM [inboxRepository] ON PURPOSE — see [ComposerRepository]. One of its calls
     * spends a Vertex generation per invocation, and keeping it out of the Inbox's own repository
     * means a screen that only lists conversations has no way to reach it.
     */
    val composerRepository: ComposerRepository = ComposerRepository(districtApi)

    /**
     * Decodes inbound attachment thumbnails.
     *
     * ⛔ ON THE SHARED [httpClient], NOT A NEW ONE, AND NOT THROUGH [DistrictApiClient]. Sharing the
     * client reuses the connection pool and the TLS session for a host the app is already talking
     * to; going through the API client would attach the bearer token, and `/api/media/<uuid>` is an
     * ANONYMOUS capability URL that never asked for one.
     *
     * ⚠️ ONE INSTANCE, because it owns the bitmap cache. A per-screen loader would decode the same
     * thumbnail again on every navigation.
     */
    val mediaImageLoader: MediaImageLoader = OkHttpMediaImageLoader(httpClient)

    /**
     * Reads a picked image's bytes.
     *
     * ⚠️ A FUNCTION RATHER THAN A FIELD, taking the caller's `Context`. The composable that owns the
     * picker has the activity context in hand and the ContentResolver is a per-context object; a
     * field built from [appContext] would work today and quietly stop working if the read grant is
     * ever scoped to the activity that requested it.
     */
    fun attachmentReader(context: Context): AttachmentReader =
        ContentResolverAttachmentReader(context.contentResolver)

    /**
     * District Desk: the tenant's own customers' tickets.
     *
     * ⛔ OVER ITS OWN `DeskApi` RATHER THAN [districtApi], AND THAT IS DELIBERATE STRUCTURE RATHER
     * THAN AN OVERSIGHT. `DistrictApi` is one delegated interface per section precisely because a
     * single implementation of all of it exceeded detekt's function ceiling two sections in; the
     * desk's nine endpoints are their own section and share the same [DistrictApiClient], so they
     * share its token handling, its 401 retry and its connection pool.
     *
     * ⚠️ STATELESS AND CACHES NOTHING. It mints its own idempotency key per create, which is per
     * submit — see [DeskRepository].
     */
    val deskRepository: DeskRepository = DeskRepository(seams.deskApi ?: HttpDeskApi(apiClient))

    /**
     * This workspace's own support requests WITH DISTRONODE.
     *
     * ⛔ NOT THE DESK, AND THE TWO MUST NEVER BE COLLAPSED INTO ONE REPOSITORY. `district/support/…`
     * is the tenant writing to US; `district/desk/…` is the tenant's own customers writing to THEM.
     * Their reply routes are one word apart on the wire (`body` here, `message` there), so a shared
     * abstraction would make a silent 400 the natural outcome of a copy-paste.
     *
     * ⛔ NOTHING IT SENDS IDENTIFIES THE REQUESTER. The workspace is the only scope on the wire and
     * the server derives and hashes the requester from the session — see [SupportRepository].
     */
    val supportRepository: SupportRepository = SupportRepository(seams.supportApi ?: HttpSupportApi(apiClient))

    /**
     * Whether the active workspace's owner still has web setup to finish, for the overview's
     * "Finish setting up on the web" card. Read-only; the wizard itself runs on the web.
     */
    val setupRepository: SetupRepository = SetupRepository(HttpSetupApi(apiClient))

    /**
     * District HQ, the agentic console.
     *
     * ⚠️ STATELESS, so nothing is cached here and there is no reason for this to be anything but a
     * plain construction: the server holds no conversation and the transcript lives in the
     * ViewModel that is displaying it.
     */
    val hqRepository: HqRepository = HqRepository(districtApi)

    /**
     * Telephony analytics and metered usage.
     *
     * ⚠️ HOLDS NO STATE AND CACHES NOTHING, so like the others this is a plain construction. Worth
     * saying because the temptation is real here: analytics is the most expensive read in the app
     * (a full-window aggregate over the Call table) and caching it at process scope would be the
     * obvious optimisation. It would also mean an operator who switched workspace, waited, and
     * came back would be reading figures from before whatever they had just changed — on a screen
     * whose entire purpose is to state the current numbers.
     */
    val analyticsRepository: AnalyticsRepository = AnalyticsRepository(districtApi)

    /**
     * The read-only phone-number marketplace.
     *
     * ⚠️ NO CACHE, AND HERE THAT IS LOAD-BEARING RATHER THAN MERELY TIDY: carrier inventory
     * changes minute to minute, so a process-scoped cache would offer an operator a number that
     * was sold while they were reading about it. See [NumbersRepository].
     */
    val numbersRepository: NumbersRepository = NumbersRepository(districtApi)

    /**
     * The account's signed-in devices.
     *
     * ⚠️ NO CACHE, AND HERE THAT IS SAFETY RATHER THAN TIDINESS. This list is what an operator
     * consults after losing a phone, and a stale row would either hide a device that is still
     * live or show one already revoked — both are wrong answers to "is my lost phone still
     * signed in".
     */
    val devicesRepository: DevicesRepository = DevicesRepository(districtApi)

    /**
     * Billing, read-only.
     *
     * ⚠️ NO CACHE, AND HERE IT IS THE SAME REASONING AS ANALYTICS RATHER THAN AS DEVICES: a plan,
     * a subscription status and an overage cap are the numbers an operator opens this screen to
     * check BECAUSE something looks wrong, and a process-scoped copy would answer with the state
     * from before whatever they just changed on the web.
     *
     * ⛔ THIS REPOSITORY HAS NO WRITES AND MUST NOT GAIN ANY. `POST /api/billing` cancels
     * subscriptions and changes plans; offering either in-app breaches Google Play's Payments
     * policy. See [BillingRepository].
     */
    val billingRepository: BillingRepository = BillingRepository(districtApi)

    /**
     * Workspace settings: the read every mutation form hydrates from, and the two safe writes.
     *
     * ⛔ NO CACHE, AND HERE IT IS THE STRONGEST CASE ON THIS LIST. Three of this surface's save
     * routes replace their stored value WHOLESALE rather than merging it, so this value is not
     * only what a screen displays — it is what a save is BUILT FROM. A process-scoped copy would
     * let a phone write back a configuration that the web replaced in between, and the write would
     * succeed. See [WorkspaceConfigRepository].
     */
    val workspaceConfigRepository: WorkspaceConfigRepository = WorkspaceConfigRepository(districtApi)

    /**
     * ⛔ SEPARATE FROM [workspaceConfigRepository] EVEN THOUGH ONE SCREEN USES BOTH. That one's
     * contract is "load, then save, because every write replaces a stored array"; neither method
     * here reads or writes stored configuration — the options call is a catalogue and the preview
     * persists nothing.
     */
    val personaOptionsRepository: PersonaOptionsRepository = PersonaOptionsRepository(personaApi)

    val callHandlingRepository: CallHandlingRepository = CallHandlingRepository(callHandlingApi)

    val messageSearchRepository: MessageSearchRepository = MessageSearchRepository(inboxExtrasApi)

    val callControlRepository: CallControlRepository = CallControlRepository(callControlApi)

    /**
     * The knowledge base.
     *
     * ⛔ SEPARATE FROM [workspaceConfigRepository] BECAUSE THE ROLE CONTRACT DIFFERS: every call on
     * that one excludes `viewer` server-side, including the read, while these reads admit them. See
     * [KnowledgeRepository].
     *
     * ⚠️ NO CACHE. The document list is what an operator checks after an upload, and a stale copy
     * would show a document that failed to ingest or hide one that just did.
     */
    val knowledgeRepository: KnowledgeRepository = KnowledgeRepository(districtApi)

    /**
     * The workspace's carrier accounts, read only.
     *
     * ⛔ THIS REPOSITORY HAS NO WRITES AND MUST NOT GAIN ANY — the route's PATCH takes plaintext
     * carrier credentials and its delete releases phone-number claims. Same shape of decision as
     * [billingRepository], for a different reason. See [MessagingRepository].
     */
    val messagingRepository: MessagingRepository = MessagingRepository(districtApi)

    /**
     * Workspace membership, and the workspace's display name.
     *
     * ⛔ THE ONLY REPOSITORY HERE WHOSE WRITES ARE AGENCY-ONLY. Every other mutation in this app
     * admits `["agency","client"]`; these three admit `agency` alone, because membership is what
     * every other permission check is derived from. ⚠️ Its rename is wider (agency or client) and
     * its roster read is wider still (viewers too), so "can this user write here" is a per-call
     * question rather than one flag — see [MembersRepository].
     *
     * ⚠️ NO CACHE, for the reason [devicesRepository] has none: a roster is what someone consults
     * before removing an ex-colleague's access, and a stale row answers that question with a value
     * from before they asked.
     */
    val membersRepository: MembersRepository = MembersRepository(districtApi)

    /**
     * Multi-party `meet_` rooms: the join credential, and the meetings the Companion writes up.
     *
     * ⚠️ NO CACHE, AND FOR ONE OF ITS THREE CALLS THAT IS NOT MERELY TIDY. A room token is a signed
     * ~30-minute capability and the guest invite minted beside it is a transferable twelve-hour
     * one; caching either would hand a later joiner a credential minted for an earlier session. See
     * [MeetingsRepository].
     */
    val meetingsRepository: MeetingsRepository = MeetingsRepository(districtApi)

    /**
     * Placing one outbound call.
     *
     * ⛔ SEPARATE FROM [callsRepository], WHICH READS THE LOG, AND THE SPLIT IS DELIBERATE RATHER
     * THAN TIDY: this is the only repository in this graph that spends money and rings a
     * telephone. A screen holding the call log has no business being able to place a call, and
     * keeping them apart means it structurally cannot.
     *
     * ⚠️ NO CACHE AND NO RETRY, AND HERE "no retry" IS THE STRONGER HALF. `POST calls/dial` is not
     * idempotent — the row is written and the carrier instructed before the response exists — so a
     * re-send is a second call to the same person, billed again. See [DialRepository].
     */
    val dialRepository: DialRepository = DialRepository(districtApi)

    /**
     * The automation monitor: the workflow list, one workflow's run history, the SDR campaign
     * status, and the one write that turns a workflow on or off.
     *
     * ⛔ SEPARATE FROM [workspaceConfigRepository] EVEN THOUGH BOTH SOUND LIKE "SETTINGS", AND THE
     * ROLE CONTRACT IS WHY — the same split [knowledgeRepository] is documented with. Every call
     * on the config repository excludes `viewer` INCLUDING the read, because its payload carries
     * staff transfer numbers and the operator's own prompt. Three of the four calls here admit
     * viewers, and the fourth is the only one that does not. Merging them would file a
     * viewer-readable monitor behind a viewer-excluded interface, and the next reader would have
     * no way to tell which half of it they were looking at.
     *
     * ⚠️ NO CACHE, deliberately: this exists to answer "is the automation working right now",
     * which is the question a stale answer is worst at. See [WorkflowsRepository].
     */
    val workflowsRepository: WorkflowsRepository = WorkflowsRepository(districtApi)

    /**
     * The workspace's booking pages.
     *
     * ⛔ ITS OWN REPOSITORY RATHER THAN A METHOD ON [workspaceConfigRepository], AND THE ROLE
     * CONTRACT IS WHY — the same split [knowledgeRepository] and [workflowsRepository] are
     * documented with. Every call on the config repository excludes `viewer` INCLUDING the read;
     * the scheduling STATUS route admits every role and reports `canManage` instead, which is a
     * different shape of guard and one the UI is told rather than allowed to derive.
     *
     * ⛔ AND ITS THREE CALLS DO NOT SHARE A FAILURE MODE. The status read is idempotent; `enable`
     * reaches two third parties and is capped at five an hour per workspace; the SSO call mints a
     * 60-second single-use credential and its 302 must never be followed. Nothing here is retried
     * or polled — see [SchedulingRepository].
     *
     * ⚠️ NO CACHE, for the reason [devicesRepository] has none: this screen answers "is my booking
     * page up right now", which is the question a stale answer is worst at.
     */
    val schedulingRepository: SchedulingRepository = SchedulingRepository(
        api = districtApi,
        // ⛔ THE ORIGIN IS PASSED IN RATHER THAN DEFAULTED INSIDE THE REPOSITORY. The dashboard
        // hand-off answers a URL carrying a live single-use session code, and the repository
        // refuses one that is not on this build's own origin — a check that defaulted to a
        // hardcoded host would keep passing on a build pointed somewhere else, which is a gate that
        // succeeds when it is misconfigured. `ApiEnvironment.baseUrl` is the single source.
        webOrigin = ApiEnvironment.baseUrl,
    )

    /**
     * What the softphone tells the OS about the call it is on.
     *
     * ⛔ ONE INSTANCE, BECAUSE TELECOM'S VIEW OF THIS APP IS PROCESS-WIDE. The self-managed
     * `PhoneAccount` is registered per process and there is at most one live `Connection`; a
     * second bridge would be a second registration racing the first, and two objects each
     * believing they own the connection. It holds no call state of its own — the ViewModel does —
     * so process scope costs nothing.
     *
     * ⚠️ AN INTERFACE AT THE CALL SITE so a ViewModel test never touches `TelecomManager`, which
     * Robolectric does not model and which can throw depending on device state. See
     * [TelecomBridge].
     */
    val telecomBridge: TelecomBridge = seams.telecomBridge ?: AndroidTelecomBridge(appContext)

    /**
     * Builds the one media engine a room ViewModel owns.
     *
     * ⛔ A FACTORY RATHER THAN AN ENGINE, AND THE SPLIT OF RESPONSIBILITIES IS THE POINT. This
     * container supplies the [appContext] — process-scoped, so it cannot go stale the way an
     * Activity would — while the CALLER supplies the coroutine scope, because the engine's event
     * collection has to die with the session's owner rather than with the process. An engine held
     * as a field here would be one engine for the app's whole life: its socket and its audio focus
     * would outlive every screen that used it, and two rooms in one session would share one.
     *
     * ⛔ AND THE SCOPE IS NOT `viewModelScope`. `ActiveRoomViewModel` passes a scope it owns
     * precisely so the disconnect can still run after `onCleared` — see [CallEngineFactory].
     *
     * ⚠️ `LiveKitCallEngine` IS THE ONLY `io.livekit` REFERENCE REACHABLE FROM THIS MODULE, and it
     * is reachable only because core-media exports the class itself. Its SDK dependency is
     * `implementation`, so nothing here can name a LiveKit type.
     */
    val callEngineFactory: CallEngineFactory = seams.callEngineFactory
        ?: CallEngineFactory { scope -> LiveKitCallEngine(appContext, scope) }

    /**
     * This installation's push registration.
     *
     * ⚠️ NO CACHE AND NO STATE. It is two calls; everything with a rule attached — when to register,
     * when to withdraw, how long a sign-out may wait for it — lives in [pushRegistrar].
     */
    private val pushTokenRepository: PushTokenRepository =
        PushTokenRepository(seams.pushApi ?: districtApi)

    /**
     * When push is registered and withdrawn.
     *
     * ⛔ CONSTRUCTED EAGERLY RATHER THAN LAZILY, BECAUSE THE FCM SERVICE REACHES IT FROM A COLD
     * PROCESS. `onNewToken` can arrive before any screen exists, and a lazy field would then be
     * built on whatever thread the SDK handed the service — which is fine here (this constructs two
     * plain objects) but stops being fine the moment anything in the chain touches disk.
     *
     * ⚠️ ON [appScope], WHICH OUTLIVES THE SERVICE THAT TRIGGERS IT. `FirebaseMessagingService` is
     * torn down as soon as its callback returns.
     */
    internal val pushRegistrar: PushRegistrar = PushRegistrar(
        repository = pushTokenRepository,
        tokens = seams.pushTokenSource ?: FirebasePushTokenSource(),
        scope = appScope,
    )

    /**
     * What the push layer may draw.
     *
     * ⚠️ `by lazy` BECAUSE ITS CONSTRUCTOR CREATES NOTIFICATION CHANNELS, which is a binder call.
     * Cheap, but it is work on the path [DistrictApplication] documents as latency-sensitive, and
     * nothing needs a channel until something is about to be posted.
     */
    private val pushNotifier: PushNotifier by lazy { AndroidPushNotifier(appContext) }

    /**
     * Answering a call this device is ringing on.
     *
     * ⛔ SEPARATE FROM [dialRepository], WHICH PLACES ONE, AND THE SPLIT IS THE SERVER'S OWN. The
     * answer route exists apart from `calls/token` because folding a third case into that branch is
     * how a supervisor token was once minted for a primary participant — the agent unsubscribed the
     * human's microphone and greeted somebody it could not hear.
     */
    private val inboundCallRepository: InboundCallRepository = InboundCallRepository(districtApi)

    /**
     * The inbound call state machine.
     *
     * ⛔ PROCESS-SCOPED, LIKE THE TELECOM REGISTRY AND FOR THE SAME REASON. A ring arrives at a
     * `FirebaseMessagingService`, possibly into a cold process with no Activity at all, and has to
     * survive until an Activity exists to draw it. A ViewModel-scoped owner would be constructed
     * after the event it needs to have received.
     *
     * ⚠️ `by lazy` so a cold start that is not a push does not build a media factory and a
     * notification channel it will never use.
     */
    internal val incomingCallController: IncomingCallController by lazy {
        IncomingCallController(
            repository = inboundCallRepository,
            surfaces = IncomingCallSurfaces(
                telecom = telecomBridge,
                notifier = pushNotifier,
                sessions = InboundCallSessionFactory(callEngineFactory, telecomBridge),
                foreground = AndroidForegroundCallHost(appContext),
            ),
            scope = appScope,
        )
    }

    /**
     * ⚠️ HELD HERE RATHER THAN IN THE ACTIVITY because the intent arrives before the graph can act
     * on it, and a rotation during the first load would otherwise lose it. See [PushDeepLinks].
     */
    val pushDeepLinks: PushDeepLinks = PushDeepLinks()

    /**
     * A verified App Link waiting for a graph that can act on it.
     *
     * ⚠️ HELD HERE FOR THE REASON [pushDeepLinks] IS, AND IT IS A SEPARATE HOLDER ON PURPOSE — see
     * [AppLinkDeepLinks] for why the two are not merged. ⛔ It is NOT `by lazy`: `MainActivity`
     * writes to it from `onCreate` on the cold-start path this feature exists for, so a lazy holder
     * would simply be constructed there anyway, one frame later, with nothing gained.
     */
    val appLinkDeepLinks: AppLinkDeepLinks = AppLinkDeepLinks()

    /**
     * What a delivered push does.
     *
     * ⚠️ `by lazy`, and its `signedIn` seam is a function rather than a captured boolean: "am I
     * signed in" is a Keystore read plus an AES-GCM decrypt, and a cached answer would be wrong
     * exactly after a sign-out — which is the case this gate exists for.
     */
    private val pushMessageHandler: PushMessageHandler by lazy {
        PushMessageHandler(
            signedIn = ::hasStoredSession,
            notifier = pushNotifier,
            onIncomingCall = { event ->
                incomingCallController.onIncomingCall(event.workspaceId, event.callId)
            },
        )
    }

    /**
     * Act on a push FCM just delivered.
     *
     * ⛔ THE LAUNCH HAPPENS HERE RATHER THAN IN THE SERVICE, ON [appScope]. `FirebaseMessagingService`
     * is destroyed as soon as `onMessageReceived` returns, so a coroutine scoped to it would be
     * cancelled mid-flight — and on the incoming-call path the work it cancels is what decides
     * whether a caller reaches a human.
     *
     * ⚠️ FIRE AND FORGET BY CONSTRUCTION: the handler swallows nothing, but every path inside it
     * ends in a notification or a Telecom call rather than in a value anyone reads.
     */
    fun onPushReceived(data: Map<String, String>) {
        appScope.launch { pushMessageHandler.handle(data) }
    }

    /**
     * Is there a credential on this device at all?
     *
     * ⛔ THE STORE, NOT A FLAG, AND NOT THE COORDINATOR. A boolean held in memory would be wrong
     * after a sign-out that happened in another process lifetime, and asking the coordinator would
     * mean attempting a REFRESH — a network call, on a push delivery, for a question that is
     * answerable from disk. What this cannot tell you is whether the session still works
     * server-side; it does not need to, because the only thing gated on it is whether to DRAW
     * something, and everything that acts re-authenticates anyway.
     *
     * ⚠️ On [Dispatchers.IO]: an AndroidKeyStore load plus AES-GCM decrypts, reached from whatever
     * thread the FCM SDK handed the service.
     */
    private suspend fun hasStoredSession(): Boolean =
        withContext(Dispatchers.IO) { this@AppContainer.tokenStore.read() != null }

    /**
     * ⛔ A SINGLE INSTANCE, because it holds the pending PKCE code verifier IN MEMORY. A second
     * instance created for the callback would have no attempt in progress and every login would
     * fail with `NoAttemptInProgress` — the same failure mode the activity's `singleTask` launch
     * mode exists to prevent from the other direction.
     */
    private val loginFlow: NativeLoginFlow by lazy {
        NativeLoginFlow(
            baseUrl = ApiEnvironment.baseUrl,
            redirectUri = ApiEnvironment.PKCE_REDIRECT_URI,
            api = authApi,
            coordinator = tokenCoordinator,
            deviceIdProvider = deviceIdentity::deviceId,
            deviceNameProvider = deviceIdentity::deviceName,
        )
    }

    /**
     * ⛔ HOISTED OUT OF [loginFlow] SO THE DEVICE LIST CAN COMPARE AGAINST IT. It was
     * constructed inside that lazy block; a second instance would be harmless (the id is
     * persisted, so both would read the same value) but it would also mean the FIRST read
     * happened wherever the second construction did, and this one is read from composition.
     */
    private val deviceIdentity = DeviceIdentity(appContext)

    /**
     * This installation's opaque id, as sent on token exchange.
     *
     * ⛔ THE ONLY THING THAT IDENTIFIES "THIS PHONE" IN THE DEVICE LIST. Nothing in a device row
     * is trustworthy for that: `deviceName` is client-supplied free text and two identical
     * handsets on one account produce identical rows. Highlighting the wrong row would invite
     * someone to sign out the device they are holding while their lost phone stays live.
     *
     * ⚠️ `by lazy` BECAUSE THE FIRST READ CAN WRITE. `DeviceIdentity.deviceId()` generates and
     * `commit()`s a UUID on first call, and this is read from composition — memoising it keeps
     * that to at most one synchronous preferences write per process, on a file this app writes
     * once in its lifetime.
     */
    val deviceId: String by lazy { deviceIdentity.deviceId() }

    /**
     * ⛔ PROCESS-SCOPED BECAUSE A LOGIN IS NOT SCOPED TO A SCREEN. Every screen needs to know a
     * login landed — the one that failed on `Unauthorized` most of all — and the login completes
     * while the activity may not exist. See [SessionSignal] for why this is an epoch rather than
     * an event or a boolean.
     */
    val sessionSignal: SessionSignal = SessionSignal()

    /**
     * ⛔ ONE INSTANCE, FOR THE SAME REASON AS [loginFlow]: it owns the in-flight attempt's
     * lifecycle state and the scope its token exchange runs in. A second one would have a fresh
     * [com.distronode.districtai.auth.LoginAttemptTracker], which is precisely the rotation bug it
     * was created to fix.
     */
    val loginController: LoginController by lazy {
        LoginController(
            loginFlow = loginFlow,
            sessionSignal = sessionSignal,
            scope = appScope,
            // ⛔ THE ONE PLACE "A SESSION JUST STARTED" IS UNAMBIGUOUS. Driving this from
            // [sessionSignal] instead would also fire on sign-OUT, registering a push token with no
            // bearer — a 401 against a 20/min ceiling, and a device row still pointed at the account
            // that just left.
            onSignedIn = pushRegistrar::onSignedIn,
        )
    }

    /**
     * ⛔ THE REVOKE OUTBOX IS DRAINED AT CONSTRUCTION, WHICH IS THE ONLY HOOK THAT ALWAYS RUNS.
     * A sign-out whose `POST /api/auth/native/revoke` could not reach the server leaves the
     * refresh token in the store's outbox; something has to come back for it, and every
     * alternative trigger is conditional on state the signed-out user no longer has. Hanging it
     * off `loginController` would only fire when someone signs in again; hanging it off the
     * coordinator's first `ensureFreshToken` would only fire when there IS a session, which is
     * exactly what a signed-out device does not have — the token would sit there until the next
     * sign-in, which may never come. Construction happens on every cold start, signed in or not.
     *
     * ⚠️ COSTS NOTHING IN THE NORMAL CASE: one store read, off the main thread, that returns
     * null. No network call is made unless there is genuinely a token to chase.
     */
    init {
        appScope.launch { drainPendingRevoke() }
    }

    /**
     * Sign out: end the session on the server, then locally.
     *
     * ⛔ THE SERVER IS ASKED FIRST AND THE LOCAL WIPE HAPPENS ANYWAY. Those are two separate
     * decisions and both are deliberate. First, because the refresh token is the credential the
     * revoke route authenticates with, so it has to be read before `forget()` destroys it —
     * afterwards there is nothing left to revoke WITH. Anyway, because the user asked to sign
     * out now: a device left looking signed in because a server could not be reached is the
     * worse of the two failures, and it is the one the user can see.
     *
     * ⛔ SO A FAILED REVOKE IS NOT DROPPED, IT IS DEFERRED. `POST /api/auth/native/revoke`
     * answers 503 when its database write threw, and the route's header states the client's
     * obligation plainly: keep the credential and try again, because the server may honour that
     * refresh token for the rest of its 60-day life. Discarding it would strand a live
     * credential nobody is tracking — the exact fail-open that route was changed to stop
     * reporting as success. It goes into the store's revoke outbox instead, and
     * [drainPendingRevoke] finishes the job on a later launch.
     *
     * ⚠️ THE OUTBOX WRITE PRECEDES `forget()`, AND `KeystoreTokenStore.clear()` DELIBERATELY
     * SPARES IT. Writing afterwards would leave a window in which a process death loses the
     * only record of the token; writing before only works because the wipe carries the slot
     * across. See the ⛔ on `KeystoreTokenStore.wipe`.
     *
     * ⚠️ The workspace selection goes too. It is not a credential, but leaving it means the next
     * user of this device lands in the previous user's workspace by default — the selection is
     * re-validated server-side, so this is tidiness rather than a hole, but a shared phone makes
     * it visible tidiness.
     */
    fun signOut() {
        // ⛔ [appScope], NOT the caller's. `forget()` suspends because the Keystore wipe is disk
        // I/O, and a sign-out interrupted midway is the worst of both states: the in-memory access
        // token is already gone but the refresh token is still on disk, so the app looks signed out
        // and silently signs itself back in on the next launch. The process scope means only
        // process death can interrupt it, and process death takes the in-memory half with it.
        //
        // ⚠️ NON-SUSPENDING ON PURPOSE, which is normally a smell. The caller is a Compose click
        // callback in the settings screen, and the alternative — a suspend function collected
        // through the UI — would tie the wipe's completion to a composition that the epoch bump
        // below is about to tear down. The scope is owned and documented, so this is not a hidden
        // unstructured launch.
        appScope.launch {
            // ⛔ FIRST, BEFORE THE REVOKE, BECAUSE IT NEEDS THE CREDENTIAL THE REVOKE ENDS.
            // `POST /api/district/devices/unregister` authenticates with the ACCESS token, so once
            // the refresh token has been revoked and `forget()` has wiped the store there is nothing
            // left to make this call with — and the row would stay registered until FCM eventually
            // reported the token gone, which on a shared phone means the previous account's
            // notifications keep arriving.
            //
            // ⛔ AND IT CANNOT BREAK THE REVOKE'S 503-RETRY CONTRACT, WHICH IS WHY IT IS BOUNDED
            // RATHER THAN AWAITED INDEFINITELY. `PushRegistrar.unregister` gives up after five
            // seconds and answers false; OkHttp's own call timeout is thirty, which would hold a
            // sign-out the user pressed — and, worse, delay the revoke that is the security-relevant
            // half. Failure is ignored entirely: the sign-out proceeds, exactly as it does when the
            // revoke itself cannot be delivered.
            pushRegistrar.unregister()
            revokeCurrentSession()
            tokenCoordinator.forget()
            workspaceRepository.clearSelection()
            loginController.clearStatus()
            // Advances the epoch, so the hoisted overview re-reads, resolves to NEVER_SIGNED_IN,
            // and the activity's gate swaps the graph for the sign-in screen. See [SessionSignal].
            //
            // ⛔ LAST, AND AFTER `forget()` HAS RETURNED. The gate reacts to this by rendering the
            // sign-in screen; firing it first would race the wipe against a fresh login attempt
            // writing new tokens, and `store.clear()` would then delete the NEW refresh token.
            sessionSignal.onSessionChanged()
        }
    }

    /**
     * Ask the server to end this device's session, deferring the token on failure.
     *
     * ⚠️ A DEVICE WITH NO STORED SESSION IS A NO-OP, NOT AN ERROR. `signOut` is reachable from a
     * settings screen the user can reach in states where the store is empty — after a Keystore
     * failure, or a session the coordinator already cleared — and there is nothing to revoke
     * with. The rest of the sign-out still runs.
     */
    private suspend fun revokeCurrentSession() {
        // ⚠️ Off the main thread: this is an AndroidKeyStore load plus AES-GCM decrypts, on the
        // dispatcher a Compose click callback handed us.
        val refreshToken = withContext(Dispatchers.IO) { tokenStore.read() }?.refreshToken ?: return

        when (revokeApi.revoke(refreshToken)) {
            RevokeResult.Done -> Unit
            RevokeResult.RetryLater ->
                withContext(Dispatchers.IO) { tokenStore.markRevokePending(refreshToken) }
        }
    }

    /**
     * Finish a sign-out whose revoke never reached the server.
     *
     * ⚠️ ON `RetryLater` THE ENTRY IS LEFT EXACTLY AS IT WAS. There is no attempt counter and no
     * backoff: the drain runs once per process start, which is already the slowest retry
     * schedule available, and a counter would only add a way for the entry to be discarded while
     * the credential is still live.
     *
     * ⚠️ A STALE ENTRY CANNOT HARM A LATER SESSION. `revokeNativeSession` matches on the
     * presented token's hash alone — not on its family, not on the user — so draining an entry
     * written before a re-login revokes exactly the one dead row it names.
     */
    internal suspend fun drainPendingRevoke() {
        val pending = withContext(Dispatchers.IO) { tokenStore.pendingRevokeToken() } ?: return

        if (revokeApi.revoke(pending) == RevokeResult.Done) {
            withContext(Dispatchers.IO) { tokenStore.clearRevokePending() }
        }
    }
}
