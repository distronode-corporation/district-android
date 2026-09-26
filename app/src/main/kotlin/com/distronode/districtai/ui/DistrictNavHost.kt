package com.distronode.districtai.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.LazyPagingItems
import com.distronode.districtai.ApiEnvironment
import com.distronode.districtai.AppContainer
import com.distronode.districtai.R
import com.distronode.districtai.ui.analytics.AnalyticsScreen
import com.distronode.districtai.ui.analytics.AnalyticsViewModel
import com.distronode.districtai.ui.billing.BillingScreen
import com.distronode.districtai.ui.billing.BillingViewModel
import com.distronode.districtai.ui.calls.CallDetailScreen
import com.distronode.districtai.ui.dialer.DialerHandlers
import com.distronode.districtai.ui.dialer.DialerScreen
import com.distronode.districtai.ui.dialer.DialerViewModel
import com.distronode.districtai.ui.calls.CallDetailViewModel
import com.distronode.districtai.ui.calls.CallLogScreen
import com.distronode.districtai.ui.calls.CallLogViewModel
import com.distronode.districtai.ui.contacts.ContactDetailScreen
import com.distronode.districtai.ui.contacts.ContactDetailViewModel
import com.distronode.districtai.ui.contacts.ContactsScreen
import com.distronode.districtai.ui.contacts.ContactsViewModel
import com.distronode.districtai.ui.devices.DevicesScreen
import com.distronode.districtai.ui.devices.DevicesViewModel
import com.distronode.districtai.ui.desk.DeskComposeSheet
import com.distronode.districtai.ui.desk.DeskScreen
import com.distronode.districtai.ui.desk.DeskSettingsScreen
import com.distronode.districtai.ui.desk.DeskSettingsViewModel
import com.distronode.districtai.ui.desk.DeskTicketScreen
import com.distronode.districtai.ui.desk.DeskTicketViewModel
import com.distronode.districtai.ui.desk.DeskViewModel
import com.distronode.districtai.ui.support.SupportComposeDialog
import com.distronode.districtai.ui.support.SupportRequestScreen
import com.distronode.districtai.ui.support.SupportRequestViewModel
import com.distronode.districtai.ui.support.SupportScreen
import com.distronode.districtai.ui.support.SupportSubmitOutcome
import com.distronode.districtai.ui.support.SupportViewModel
import com.distronode.districtai.ui.hq.HqScreen
import com.distronode.districtai.ui.hq.HqViewModel
import com.distronode.districtai.ui.inbox.ComposerHandlers
import com.distronode.districtai.ui.inbox.InboxScreen
import com.distronode.districtai.ui.inbox.InboxViewModel
import com.distronode.districtai.ui.marketplace.MarketplaceScreen
import com.distronode.districtai.ui.marketplace.MarketplaceViewModel
import com.distronode.districtai.ui.marketplace.marketplaceWebUrl
import com.distronode.districtai.ui.inbox.ThreadScreen
import com.distronode.districtai.ui.inbox.ThreadTarget
import com.distronode.districtai.ui.inbox.ThreadViewModel
import com.distronode.districtai.ui.workflows.CampaignCallbacks
import com.distronode.districtai.ui.workflows.WorkflowsScreen
import com.distronode.districtai.ui.workflows.WorkflowsViewModel
import com.distronode.districtai.ui.overview.OverviewScreen
import com.distronode.districtai.ui.overview.OverviewUiState
import com.distronode.districtai.ui.overview.OverviewViewModel
import com.distronode.districtai.ui.overview.setupWebUrl
import com.distronode.districtai.ui.rooms.ActiveRoomScreen
import com.distronode.districtai.ui.rooms.ActiveRoomViewModel
import com.distronode.districtai.ui.rooms.RoomControls
import com.distronode.districtai.ui.rooms.RoomsLobbyScreen
import com.distronode.districtai.ui.rooms.RoomsLobbyViewModel
import com.distronode.districtai.ui.scheduling.schedulingDestination
import com.distronode.districtai.ui.settings.ACCOUNT_DELETION_URL
import com.distronode.districtai.ui.settings.SettingsScreen
import com.distronode.districtai.ui.settings.workspace.CallHandlingScreen
import com.distronode.districtai.ui.settings.workspace.CallHandlingViewModel
import com.distronode.districtai.ui.settings.workspace.CapabilitiesScreen
import com.distronode.districtai.ui.settings.workspace.CapabilitiesViewModel
import com.distronode.districtai.ui.settings.workspace.DirectoryEditorScreen
import com.distronode.districtai.ui.settings.workspace.DirectoryEditorViewModel
import com.distronode.districtai.ui.settings.workspace.KnowledgeScreen
import com.distronode.districtai.ui.settings.workspace.KnowledgeViewModel
import com.distronode.districtai.ui.settings.workspace.MembersScreen
import com.distronode.districtai.ui.settings.workspace.MembersViewModel
import com.distronode.districtai.ui.settings.workspace.MessagingScreen
import com.distronode.districtai.ui.settings.workspace.MessagingViewModel
import com.distronode.districtai.ui.settings.workspace.PersonaFormScreen
import com.distronode.districtai.ui.settings.workspace.PersonaPreviewHost
import com.distronode.districtai.ui.settings.workspace.PersonaFormViewModel
import com.distronode.districtai.ui.settings.workspace.RoutingRulesScreen
import com.distronode.districtai.ui.settings.workspace.RoutingRulesViewModel
import com.distronode.districtai.ui.settings.workspace.WorkspaceSettingsScreen
import com.distronode.districtai.auth.CustomTabsLauncher
import com.distronode.districtai.core.designsystem.DistrictNavBar
import com.distronode.districtai.core.designsystem.DistrictNavItem
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.displayName
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation

/**
 * The signed-in navigation graph.
 *
 * ⛔ THE SIGN-IN SCREEN IS DELIBERATELY NOT A DESTINATION HERE. Whether a session exists is not a
 * navigation question — it is derived state, reported by the overview's own
 * [OverviewUiState.SignedOut]. Putting sign-in in the back stack means owning the rule "you may
 * never navigate back into the app after signing out", which is exactly the kind of invariant a
 * back stack quietly violates. The activity chooses between this graph and the sign-in screen.
 *
 * ⛔ THE OVERVIEW ViewModel IS PASSED IN, NEVER CREATED HERE, AND THAT IS A BUG FIX. This graph
 * used to build its own from the nav entry's ViewModelStore while the activity built a second one
 * to decide whether to show the graph at all. Two instances, both running `init { load() }`, meant
 * every cold start issued `workspace/list` + `overview` twice — `overview` fans out across up to
 * four regional databases — and the activity's login-completion callback reloaded the instance
 * NOBODY WAS LOOKING AT. A user whose session expired mid-use could sign in successfully and watch
 * the screen keep saying "Your session has ended", with no pull-to-refresh anywhere and no way out
 * but killing the app.
 *
 * ⚠️ EVERY WORKSPACE-SCOPED DESTINATION CARRIES THE WORKSPACE ID IN ITS ROUTE. Reading the active
 * workspace from a shared holder would be shorter, but a destination restored after process death
 * would then render against whatever the holder happened to contain — or nothing. Carrying it in the
 * route makes each destination self-describing, which is also what a push deep link will need.
 *
 * @param overviewState the hoisted ViewModel's state, already collected by the activity so the gate
 *   and the screen can never disagree about it.
 * @param sessionEpoch see [com.distronode.districtai.auth.SessionSignal]. Every destination watches
 *   it so a completed login re-triggers the load or `retry()` that the visible screen needs.
 * @param onShowMessage surface a transient message. Passed in rather than owning a SnackbarHost so
 *   this graph does not also have to own a Scaffold.
 */
/**
 * ⚠️ `CyclomaticComplexity` IS SUPPRESSED, AND THE METRIC IS MEASURING THE WRONG THING HERE. This
 * function is a route table: its score is a COUNT OF DESTINATIONS, not branching logic, and it
 * rose because the desk, support, persona-preview and call-handling screens landed, each adding
 * one `composable` entry. Splitting it to satisfy the number would scatter one navigation graph
 * across several files and make a missing route harder to see, which is the failure this file
 * exists to prevent. The ceiling stays on for every other function in the module.
 */
@Suppress("CyclomaticComplexMethod")
@Composable
fun DistrictNavHost(
    container: AppContainer,
    overviewViewModel: OverviewViewModel,
    overviewState: OverviewUiState,
    sessionEpoch: Int,
    onSignIn: () -> Unit,
    onShowMessage: (String) -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // ⛔ THE APPLICATION CONTEXT, NOT THE ACTIVITY'S. `LocalContext.current` in a composable is the
    // Activity, and handing that to a callback the ViewModel holds across a suspending request means
    // a rotation mid-request calls startActivity on a DESTROYED Activity. The application context is
    // process-scoped and cannot go stale.
    //
    // ⚠️ LAUNCHING FROM THIS CONTEXT IS LEGAL ONLY BECAUSE FLAG_ACTIVITY_NEW_TASK IS SET, in
    // `CustomTabsLauncher.newTaskIntent` and on the Custom Tabs intent itself; see the ⛔ there.
    // Without it `startActivity` throws `AndroidRuntimeException`, which is not an
    // ActivityNotFoundException, so the launcher's catches would not see it and the process would
    // die. Reachable only from the account-deletion hand-off below, because the other call site
    // passes an Activity.
    val context = LocalContext.current.applicationContext

    // ⛔ THE NAV BAR LIVES OUTSIDE THE NavHost, WHICH IS THE ONLY PLACE IT CAN. Inside, it would be
    // rebuilt per destination and animate in and out on every transition — and each screen would have
    // to remember to draw it, which is how an app ends up with no menu at all.
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    // ⚠️ Derived from the OVERVIEW's state, not from the current route's arguments. The bar must be
    // able to reach a workspace-scoped destination FROM the account screen, which carries no
    // workspace in its route — see the ⚠️ on Routes.SETTINGS.
    val content = overviewState as? OverviewUiState.Content
    val workspaceId = content?.active?.id
    val role = content?.overview?.role

    // ⚠️ SHOWN ONLY ON THE FOUR TOP-LEVEL DESTINATIONS. A detail screen is a drill-down: it owns its
    // own back affordance, and a lateral jump from one would strand a half-read record behind a back
    // stack the user did not build.
    val topLevel = route == Routes.OVERVIEW ||
        route == Routes.INBOX ||
        route == Routes.SETTINGS ||
        route == Routes.CALL_LOG ||
        route == Routes.CONTACTS


    // ⚠️ OUTSIDE THE NavHost, LIKE THE NAV BAR, BECAUSE IT MUST OUTLIVE A DESTINATION. A message
    // notification tapped while a detail screen is open resolves here and navigates; an effect
    // inside a destination would be torn down by the navigation it caused. See PushDeepLinkEffect.
    PushDeepLinkEffect(
        deepLinks = container.pushDeepLinks,
        messageSearch = container.messageSearchRepository,
        workspaceId = workspaceId,
        role = role,
        navController = navController,
    )

    // ⚠️ THE SAME PLACE AND FOR THE SAME REASON AS THE PUSH EFFECT ABOVE — outside the NavHost, so it
    // outlives the destination it navigates away from. ⛔ TWO EFFECTS RATHER THAN ONE because the
    // two sources carry different things and drop for different reasons; see AppLinkDeepLinks. They
    // cannot race meaningfully: the user tapped one thing, and each is last-one-wins.
    AppLinkDeepLinkEffect(
        deepLinks = container.appLinkDeepLinks,
        workspaceId = workspaceId,
        role = role,
        navController = navController,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            NavHost(navController = navController, startDestination = Routes.OVERVIEW) {
                composable(Routes.OVERVIEW) {
                    // ⚠️ THE FIRST POST-SIGN-IN LANDING, which is where the notifications permission
                    // is asked for — the graph is composed only once a session exists. See
                    // NotificationsPermissionEffect for why not at launch.
                    NotificationsPermissionEffect()
                    val setupBrowserMissing = stringResource(R.string.settings_browser_missing)
                    OverviewScreen(
                        state = overviewState,
                        onRetry = {
                            overviewViewModel.load(refreshing = overviewState is OverviewUiState.Content)
                        },
                        onSignIn = onSignIn,
                        onSelectWorkspace = overviewViewModel::selectWorkspace,
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenCallLog = { workspaceId ->
                            navController.navigate(Routes.callLog(workspaceId))
                        },
                        onOpenContacts = { workspaceId, role ->
                            // ⚠️ The role travels IN THE ROUTE so the destination stays self-describing after
                            // process death. Reading it from a shared holder would leave a restored contacts
                            // screen with no idea whether to offer editing — and the safe default (offer
                            // nothing) would silently strip an operator's controls after a kill.
                            navController.navigate(Routes.contacts(workspaceId, role))
                        },
                        onOpenHq = { workspaceId, role ->
                            navController.navigate(Routes.hq(workspaceId, role))
                        },
                        // ⚠️ NO ROLE IN THE CALLBACK, unlike every sibling above. Both routes
                        // behind this screen are reads that admit agency, client AND viewer, so
                        // there is nothing on it to gate — and carrying a role that decides
                        // nothing would imply one exists.
                        onOpenAnalytics = { workspaceId ->
                            navController.navigate(Routes.analytics(workspaceId))
                        },
                        // ⚠️ CARRIES A ROLE despite both of its routes admitting viewers — it
                        // decides the read-only caption's wording, not access. See
                        // Routes.MARKETPLACE.
                        onOpenMarketplace = { workspaceId, role ->
                            navController.navigate(Routes.marketplace(workspaceId, role))
                        },
                        // ⚠️ FROM HERE RATHER THAN FROM SETTINGS, AND THAT IS THE ARCHITECTURAL
                        // CHOICE RATHER THAN A LAYOUT ONE. Billing is workspace-scoped; Settings
                        // and Devices are deliberately account-scoped and carry no workspace in
                        // their routes. See the ⛔ on Routes.BILLING.
                        onOpenBilling = { workspaceId, role ->
                            navController.navigate(Routes.billing(workspaceId, role))
                        },
                        // ⛔ THE ROLE HERE IS A REAL GATE, NOT WORDING. Every route behind this
                        // hub — including the config READ — excludes `viewer` server-side, so the
                        // OverviewScreen offers this entry only when the effective role admits
                        // mutation. Navigating a viewer here would land them on a 403.
                        // ⚠️ Carries the OVERVIEW's role, the effective one for this caller:
                        // support access can grant "agency" with no membership row, and the
                        // workspace list would understate that. It gates nothing in the lobby;
                        // it is what the lobby hands to the room.
                        onOpenRooms = { workspaceId, role ->
                            navController.navigate(Routes.rooms(workspaceId, role))
                        },
                        // ⛔ THE ROLE IS A REAL GATE, LIKE WORKSPACE SETTINGS AND UNLIKE THE
                        // ROOMS ENTRY BESIDE IT. The dial route excludes `viewer`, so the
                        // OverviewScreen offers this only when the effective role admits
                        // mutation — navigating a viewer here would land them on a screen whose
                        // one action 403s.
                        onOpenDialer = { workspaceId, role ->
                            navController.navigate(Routes.dialer(workspaceId, role))
                        },
                        // ⚠️ CARRIES A ROLE, AND IT IS A PARTIAL GATE — the third kind on this
                        // screen. Analytics carries none (nothing gated), workspace settings and
                        // the dialler are hidden outright (every route 403s a viewer), and this
                        // one is offered to every role because three of its four routes admit
                        // viewers. The role decides only whether the per-workflow switch works.
                        onOpenWorkflows = { workspaceId, role ->
                            navController.navigate(Routes.workflows(workspaceId, role))
                        },
                        // ⛔ NO ROLE AT ALL, WHICH IS A FOURTH KIND ON THIS SCREEN AND THE ONLY
                        // ONE WHERE THE SERVER ANSWERS THE QUESTION A ROLE WOULD HAVE. The status
                        // route sends `canManage` on every read, so the Enable button is drawn
                        // from that; passing a role would be a second, weaker copy that fails
                        // CLOSED and would hide the button from an owner. Analytics also carries
                        // none, but for the weaker reason that nothing behind it is gated at all.
                        onOpenScheduling = { workspaceId ->
                            navController.navigate(Routes.scheduling(workspaceId))
                        },
                        onOpenWorkspaceSettings = { workspaceId, role ->
                            navController.navigate(Routes.workspaceSettings(workspaceId, role))
                        },
                        // ⛔ THE ROLE IS A REAL GATE ON BOTH, READS INCLUDED, and the OverviewScreen
                        // offers them only when the effective role admits mutation. ⚠️ Mirror
                        // images, not a pair: the desk is the workspace's customers' tickets, support
                        // is the workspace's requests with Distronode. Named, never positional.
                        onOpenDesk = { workspaceId, role ->
                            navController.navigate(Routes.desk(workspaceId, role))
                        },
                        onOpenSupport = { workspaceId, role ->
                            navController.navigate(Routes.support(workspaceId, role))
                        },
                        // ⛔ `openInBrowser`, i.e. `launchExternally`, AND NEVER AN IMPLICIT
                        // INTENT: this app is a verified App Link handler for
                        // /dashboard/district, so a plain ACTION_VIEW would land back on this
                        // very screen. Setup runs on the web; see FinishSetupCard.
                        onFinishSetup = {
                            openInBrowser(
                                context,
                                setupWebUrl(ApiEnvironment.baseUrl),
                                setupBrowserMissing,
                                onShowMessage,
                            )
                        },
                    )
                }

                composable(Routes.SETTINGS) {
                    // ⛔ RESOLVED HERE, NOT INSIDE THE CALLBACK (lint's
                    // `LocalContextGetResourceValueCall`). `context.getString(...)` from a
                    // composable reads the resource through a Context captured at composition time,
                    // so it does NOT participate in Compose's own configuration tracking: a locale
                    // or font-scale change recomposes the screen but this string stays as it was
                    // resolved under the previous configuration. `stringResource` is a
                    // composition-local read and updates with it.
                    val browserMissing = stringResource(R.string.settings_browser_missing)
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        // ⚠️ No popBackStack: signing out advances the session epoch, the hoisted overview
                        // re-reads to NEVER_SIGNED_IN, and the activity's gate replaces this whole graph
                        // with the sign-in screen. Popping first would briefly show the overview mid-teardown.
                        onSignOut = container::signOut,
                        onOpenDevices = { navController.navigate(Routes.DEVICES) },
                        onDeleteAccount = {
                            // ⚠️ The SAME hand-off as sign-in, so deletion opens in the system browser
                            // with the user's real session — a WebView could neither authenticate them
                            // nor be trusted to.
                            openInBrowser(context, ACCOUNT_DELETION_URL, browserMissing, onShowMessage)
                        },
                    )
                }

                composable(Routes.DEVICES) {
                    val viewModel: DevicesViewModel = viewModel(
                        // ⚠️ UNKEYED, unlike every workspace-scoped screen. Devices belong to the
                        // ACCOUNT, so there is no tenant to key on — and an account change tears the
                        // whole graph down through the session gate rather than reusing this store.
                        factory = DevicesViewModel.factory(
                            repository = container.devicesRepository,
                            // ⚠️ THE ID, NOT THE `DeviceIdentity`. Reading it can WRITE (it mints and
                            // commits a UUID on first call), so the container reads it once per process
                            // and a per-navigation ViewModel never does.
                            thisDeviceId = container.deviceId,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    // ⚠️ An idempotent GET, so replaying it on a session change is unconditionally
                    // safe — unlike HQ, where the same hook must never replay a confirm.
                    OnSessionChanged(sessionEpoch) { viewModel.load() }

                    DevicesScreen(
                        state = state,
                        thisDeviceId = container.deviceId,
                        onBack = { navController.popBackStack() },
                        onRetry = viewModel::load,
                        // ⛔ THE LOCAL SIGN-OUT IS WIRED HERE, NOT REACHED FOR FROM THE SCREEN OR THE
                        // ViewModel. Revoking this device (or every device) ends this installation's
                        // session server-side, and the local half — Keystore wipe, selection clear,
                        // epoch bump, in that order — is `AppContainer`'s and runs on the process
                        // scope it owns. Same shape as `onSignIn` and as the contact detail's
                        // `onDelete { popBackStack() }`.
                        //
                        // ⚠️ NO popBackStack ALONGSIDE IT, for the reason the settings screen states:
                        // the epoch advance replaces this whole graph with the sign-in screen, and
                        // popping first would briefly show the overview mid-teardown.
                        onRevokeDevice = { deviceId ->
                            viewModel.revokeDevice(deviceId, container::signOut)
                        },
                        onRevokeAll = { viewModel.revokeAllDevices(container::signOut) },
                        onDismissNotices = viewModel::dismissNotices,
                    )
                }

                composable(Routes.CALL_LOG) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val viewModel: CallLogViewModel = viewModel(
                        // ⚠️ Keyed on the workspace so the ViewModelStore holds one instance per tenant.
                        // Without the key, switching workspace would reuse an instance whose cached pages
                        // and deduplication set belong to the previous one.
                        key = "call-log-$workspaceId",
                        factory = CallLogViewModel.factory(container.callsRepository, workspaceId),
                    )
                    val calls = viewModel.calls.collectAsLazyPagingItems()

                    // ⛔ WITHOUT THIS, A PAGED SCREEN THAT FAILED ON `Unauthorized` STAYS FAILED FOREVER.
                    // Its Paging error is terminal until something calls retry(), and `onSignIn` had no
                    // completion path — so a successful login left the list showing "Your session has
                    // ended" behind a Sign in button that had already done its job.
                    OnSessionChanged(sessionEpoch, calls::retry)

                    CallLogScreen(
                        calls = calls,
                        onOpenCall = { callId ->
                            navController.navigate(Routes.callDetail(workspaceId, callId))
                        },
                        onSignIn = onSignIn,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.INBOX) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: InboxViewModel = viewModel(
                        factory = InboxViewModel.Factory(
                            repository = container.inboxRepository,
                            composer = container.composerRepository,
                            search = container.messageSearchRepository,
                            workspaceId = workspaceId,
                            role = role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
                    OnSessionChanged(sessionEpoch) { viewModel.load() }
                    InboxScreen(
                        state = state,
                        onOpenThread = { thread ->
                            // ⚠️ Mark read OPTIMISTICALLY, before navigating. Opening the thread is the read,
                            // and waiting on the write would delay the screen to serve bookkeeping.
                            viewModel.markThreadRead(thread.contactId, thread.counterpart)
                            // ⛔ The reply address and channel are resolved HERE, from the
                            // conversation, because this is the only screen that has them. See
                            // ConversationSummary.replyTarget.
                            val target = thread.replyTarget
                            navController.navigate(
                                Routes.thread(
                                    workspaceId = workspaceId,
                                    role = role,
                                    threadKey = thread.threadKey,
                                    replyTo = target?.to,
                                    replyChannel = target?.channel,
                                    title = thread.displayName,
                                ),
                            )
                        },
                        onRetry = { viewModel.load(refreshing = true) },
                        onBack = { navController.popBackStack() },
                        searchState = searchState,
                        onSearchQueryChanged = viewModel::onSearchQueryChanged,
                        onOpenHit = { hit ->
                            // ⛔ THE SAME BOOKKEEPING WRITE THE CONVERSATION LIST DOES. Opening a
                            // thread IS the read whichever list it was opened from, and nothing in
                            // the thread screen records one — so without this every thread opened
                            // from search would stay permanently unread.
                            viewModel.markThreadRead(hit.contactId, hit.counterpart)
                            // ⛔ THE REPLY TARGET COMES FROM THE LOADED CONVERSATION, NEVER FROM THE
                            // HIT'S OWN `kind`. A conversation carries the server's canSms/canEmail;
                            // deriving a channel from a matching message's type would offer SMS to a
                            // customer who has only ever emailed. ⚠️ A hit in a thread outside the
                            // loaded window therefore opens READ-ONLY, which is the truthful outcome
                            // rather than a gap.
                            val target = viewModel.conversationFor(hit.threadKey)?.replyTarget
                            navController.navigate(
                                Routes.thread(
                                    workspaceId = workspaceId,
                                    role = role,
                                    threadKey = hit.threadKey,
                                    replyTo = target?.to,
                                    replyChannel = target?.channel,
                                    title = hit.displayName,
                                ),
                            )
                        },
                    )
                }

                composable(
                    Routes.THREAD,
                    arguments = listOf(
                        navArgument(ARG_REPLY_TO) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(ARG_REPLY_CHANNEL) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(ARG_THREAD_TITLE) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val threadKey = entry.pathArgument(ARG_THREAD_KEY)
                    val selector = parseThreadKey(threadKey)
                    val replyTo = entry.routeArguments().getString(ARG_REPLY_TO)?.takeIf { it.isNotBlank() }
                    val replyChannel =
                        entry.routeArguments().getString(ARG_REPLY_CHANNEL)?.takeIf { it.isNotBlank() }
                    val threadTitle =
                        entry.routeArguments().getString(ARG_THREAD_TITLE)?.takeIf { it.isNotBlank() }
                    val context = LocalContext.current
                    val viewModel: ThreadViewModel = viewModel(
                        factory = ThreadViewModel.Factory(
                            repository = container.inboxRepository,
                            composer = container.composerRepository,
                            target = ThreadTarget(
                                workspaceId = workspaceId,
                                contactId = selector.contactId,
                                address = selector.address,
                                threadKey = threadKey,
                                // ⚠️ SMS only as the fallback for a target with no channel
                                // recorded. The channel normally arrives resolved, paired with the
                                // address it belongs to — see ConversationSummary.replyTarget.
                                replyTarget = replyTo?.let {
                                    ReplyTarget(it, replyChannel ?: CHANNEL_SMS)
                                },
                            ),
                            role = role,
                            attachmentReader = container.attachmentReader(context),
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val composerText by viewModel.composerText.collectAsStateWithLifecycle()
                    // ⛔ THE PICKER LAUNCHER LIVES HERE, NOT IN ThreadScreen.
                    // rememberLauncherForActivityResult needs an ActivityResultRegistryOwner,
                    // which a plain `createComposeRule` test host does not provide — putting it in
                    // the screen would make every ThreadScreen test crash on composition. The
                    // screen takes a plain `() -> Unit` instead and stays testable.
                    //
                    // ⚠️ PickVisualMedia REQUESTS NO PERMISSION. It returns a one-shot read grant
                    // for exactly the item chosen, which is why this app declares no
                    // READ_MEDIA_IMAGES and cannot enumerate the gallery.
                    val picker = rememberLauncherForActivityResult(
                        ActivityResultContracts.PickVisualMedia(),
                    ) { uri -> uri?.let { viewModel.attach(it.toString()) } }
                    OnSessionChanged(sessionEpoch, viewModel::load)
                    ThreadScreen(
                        // ⚠️ The conversation's display name (contact name, else the counterpart)
                        // is preferred: a contact-keyed thread has no address to fall back to, and
                        // titling it "Inbox" told the operator nothing about who they were writing to.
                        title = threadTitle
                            ?: selector.address
                            ?: stringResource(R.string.inbox_title),
                        state = state,
                        canReply = viewModel.canReply,
                        onBack = { navController.popBackStack() },
                        onSend = viewModel::send,
                        onRetry = viewModel::load,
                        onDismissSendFailure = viewModel::dismissSendFailure,
                        onLoadOlder = viewModel::loadOlder,
                        composer = ComposerHandlers(
                            text = composerText,
                            onTextChange = viewModel::onComposerChange,
                            canAttach = viewModel.canAttach,
                            onAttach = {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            onRemoveAttachment = viewModel::removeAttachment,
                            onGenerateDraft = viewModel::generateDraft,
                            imageLoader = container.mediaImageLoader,
                            // ⚠️ The system browser, not an in-app viewer — the URL is anonymous
                            // and the browser already does zoom, save and share correctly.
                            onOpenMedia = { url -> openAttachment(context, url) },
                        ),
                    )
                }

                composable(Routes.CONTACTS) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    // ⚠️ Parsed through fromWire, which fails CLOSED to null for anything unrecognised — so a
                    // corrupted or renamed role offers no mutations rather than defaulting to permissive.
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: ContactsViewModel = viewModel(
                        key = "contacts-$workspaceId",
                        factory = ContactsViewModel.factory(container.contactsRepository, workspaceId, role),
                    )
                    val contacts: LazyPagingItems<com.distronode.districtai.core.model.Contact> =
                        viewModel.contacts.collectAsLazyPagingItems()

                    val createState by viewModel.createState.collectAsStateWithLifecycle()

                    // See the ⛔ on the call log: the same terminal-Paging-error trap.
                    OnSessionChanged(sessionEpoch, contacts::retry)

                    ContactsScreen(
                        contacts = contacts,
                        canMutate = viewModel.canMutate,
                        onOpenContact = { contactId ->
                            navController.navigate(Routes.contactDetail(workspaceId, contactId, role))
                        },
                        onSignIn = onSignIn,
                        createState = createState,
                        onCreate = viewModel::create,
                        onCreateHandled = viewModel::clearCreateState,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.HQ) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    // ⚠️ fromWire fails CLOSED to null, so a corrupted role offers no confirm
                    // control. A viewer never sees one anyway — the server declines their writes
                    // before a proposal exists — but the UI must not be the thing that decides that.
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: HqViewModel = viewModel(
                        // ⚠️ Keyed on the workspace: the transcript belongs to ONE tenant, and an
                        // unkeyed instance would carry one workspace's conversation — and its
                        // pending write — into the next.
                        key = "hq-$workspaceId",
                        factory = HqViewModel.factory(container.hqRepository, workspaceId, role),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val messages by viewModel.messages.collectAsStateWithLifecycle()

                    // ⛔ See the ⛔ on HqViewModel.retryOrNoop: this replays a failed PROMPT and
                    // never a failed confirm, because a confirm is a write that may already have
                    // taken effect.
                    OnSessionChanged(sessionEpoch, viewModel::retryOrNoop)

                    HqScreen(
                        state = state,
                        messages = messages,
                        canConfirm = viewModel.canConfirm,
                        onSend = viewModel::ask,
                        onConfirm = viewModel::confirmPending,
                        onDismiss = viewModel::dismissPending,
                        onRetry = viewModel::retry,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.ANALYTICS) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val viewModel: AnalyticsViewModel = viewModel(
                        // ⚠️ Keyed on the workspace: the figures and the selected window belong
                        // to ONE tenant, and an unkeyed instance would show the previous
                        // workspace's aggregate under the new workspace's name until the reload
                        // landed.
                        key = "analytics-$workspaceId",
                        factory = AnalyticsViewModel.factory(
                            container.analyticsRepository,
                            workspaceId,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    // ⛔ Both reads are idempotent GETs, so replaying them on a session change is
                    // unconditionally safe — unlike HQ, where the same hook must never replay a
                    // confirm. See HqViewModel.retryOrNoop.
                    OnSessionChanged(sessionEpoch, viewModel::load)

                    AnalyticsScreen(
                        state = state,
                        onSelectRange = viewModel::selectRange,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.MARKETPLACE) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    // ⚠️ Not a gate — both routes admit viewers. The role only decides the
                    // wording of the read-only caption. See the ⚠️ on Routes.MARKETPLACE.
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: MarketplaceViewModel = viewModel(
                        // ⚠️ Keyed on the workspace: an unkeyed instance would show the previous
                        // tenant's phone numbers under the new tenant's name until the reload
                        // landed — and a phone number is exactly the kind of value an operator
                        // would act on before noticing.
                        key = "marketplace-$workspaceId",
                        factory = MarketplaceViewModel.factory(
                            container.numbersRepository,
                            workspaceId,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    // ⛔ Reloads the OWNED list only. Both reads are idempotent GETs so replaying
                    // either is safe, but re-running a search the operator typed would replace
                    // results they are reading with a fresh set under no visible trigger.
                    OnSessionChanged(sessionEpoch, viewModel::load)

                    // ⛔ RESOLVED HERE, NOT INSIDE THE CALLBACK — the `LocalContextGetResourceValueCall`
                    // rule this file already documents on the settings screen. `context.getString`
                    // from a composable reads through a Context captured at composition time and
                    // does not update with a locale or font-scale change.
                    val browserMissing = stringResource(R.string.settings_browser_missing)

                    MarketplaceScreen(
                        state = state,
                        role = role,
                        onSelectTab = viewModel::selectTab,
                        onUpdateForm = viewModel::updateForm,
                        onSearch = viewModel::search,
                        onRetryOwned = viewModel::load,
                        // ⛔ A LINK OUT, NOT A PURCHASE. Buying a number stays on the web by an
                        // owner decision — see `marketplaceWebUrl`, which carries the Play
                        // Payments reasoning. The SAME launcher as sign-in, account deletion and
                        // the Stripe invoice: the destination is authenticated, so it needs the
                        // user's real browser session.
                        onOpenWeb = {
                            openInBrowser(
                                context,
                                marketplaceWebUrl(ApiEnvironment.baseUrl),
                                browserMissing,
                                onShowMessage,
                            )
                        },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.WORKFLOWS) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    // ⚠️ A PARTIAL GATE, unlike every neighbour here. The list, the run history
                    // and the campaign status all admit viewers; only the PATCH that flips a
                    // workflow excludes them. So the screen opens for every role and the switch
                    // is what the role decides. See the ⚠️ on Routes.WORKFLOWS.
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: WorkflowsViewModel = viewModel(
                        // ⚠️ Keyed on the workspace AND the role: the role is a constructor
                        // argument that decides whether the switch works at all, so an instance
                        // retained across a role change would keep the previous answer. Keyed on
                        // the workspace for the reason every sibling is — an unkeyed instance
                        // would show the previous tenant's automation under the new tenant's name
                        // until the reload landed, and "your follow-ups are off" is exactly the
                        // kind of claim somebody acts on before noticing.
                        key = "workflows-$workspaceId-${role?.name}",
                        factory = WorkflowsViewModel.factory(
                            container.workflowsRepository,
                            workspaceId,
                            role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    // ⛔ Reloads BOTH reads and clears the cached run history. Every read behind
                    // this screen is an idempotent GET, so replaying them is unconditionally safe
                    // — and after a session change the cached history could belong to another
                    // account, which is why load() drops it rather than keeping it.
                    OnSessionChanged(sessionEpoch, viewModel::load)

                    WorkflowsScreen(
                        state = state,
                        canToggle = viewModel.canToggle,
                        onRetry = viewModel::load,
                        onToggleExpanded = viewModel::toggleExpanded,
                        onLoadMoreRuns = viewModel::loadMoreRuns,
                        onSetActive = viewModel::setActive,
                        onDismissToggleFailure = viewModel::dismissToggleFailure,
                        // ⚠️ The ViewModel owns the role guard as well as the screen — see the ⛔
                        // on WorkflowsViewModel.requestCampaignChange. Passing the methods rather
                        // than re-deriving anything here keeps one answer to "may this caller
                        // pause".
                        campaign = CampaignCallbacks(
                            onRequestChange = viewModel::requestCampaignChange,
                            onDismissConfirm = viewModel::dismissCampaignConfirm,
                            onConfirm = viewModel::confirmCampaignChange,
                        ),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.BILLING) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    // ⚠️ Not a gate — the workspace route admits viewers and `/api/billing` is
                    // caller-scoped. The role only decides the wording of the read-only caption.
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: BillingViewModel = viewModel(
                        // ⚠️ Keyed on the workspace: the plan, the cap and the month's minutes
                        // belong to ONE tenant, and an unkeyed instance would show the previous
                        // workspace's plan under the new workspace's name until the reload landed.
                        // On a billing screen that is a wrong claim about what someone is paying.
                        key = "billing-$workspaceId",
                        factory = BillingViewModel.factory(container.billingRepository, workspaceId),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val browserMissing = stringResource(R.string.settings_browser_missing)

                    // ⛔ Both reads are idempotent GETs — there is no write anywhere behind this
                    // screen — so replaying them on a session change is unconditionally safe.
                    OnSessionChanged(sessionEpoch, viewModel::load)

                    BillingScreen(
                        state = state,
                        role = role,
                        onRetry = viewModel::load,
                        // ⚠️ THE SAME LAUNCHER AS SIGN-IN AND ACCOUNT DELETION, deliberately. A
                        // Stripe hosted invoice is an authenticated page: a Custom Tab carries the
                        // user's real browser session, and a WebView could neither do that nor be
                        // trusted to. The URL comes from the server's own `hosted_invoice_url` and
                        // is never constructed here.
                        onOpenInvoice = { url ->
                            openInBrowser(context, url, browserMissing, onShowMessage)
                        },
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── Rooms ─────────────────────────────────────────────────────
                //
                // ⛔ IN THEIR OWN BUILDERS, NOT INLINE, AND THAT IS A detekt CEILING WORTH
                // RESPECTING RATHER THAN SUPPRESSING. `DistrictNavHost` sits at the
                // CyclomaticComplexMethod threshold (15) and these two destinations pushed it
                // over — every `?.let`, every `if` and every lambda in a `composable {}` block
                // counts toward the ENCLOSING function. The rooms pair is also the only place in
                // this graph that owns a live media session, so it is a real seam rather than an
                // arbitrary cut.
                roomsLobbyDestination(container, navController, sessionEpoch)
                activeRoomDestination(container, navController, context)
                dialerDestination(container, navController, sessionEpoch)

                // ⛔ EXTRACTED FOR THE SAME COMPLEXITY REASON AS THE THREE ABOVE, AND THEN OUT OF
                // THIS FILE ENTIRELY — it lives in `ui/scheduling/SchedulingDestination.kt`. This
                // file is AT detekt's per-file `TooManyFunctions` ceiling (11, and the rule fires
                // at the threshold rather than above it), so a further top-level builder here
                // fails the build, while inlining the destination fails `CyclomaticComplexMethod`
                // on `DistrictNavHost` instead. See that file's header for the full reasoning.
                schedulingDestination(container, navController, sessionEpoch, context, onShowMessage)

                // ── Workspace settings ────────────────────────────────────────
                //
                // ⛔ THE THREE DESTINATIONS BEHIND THE ONE PART OF THIS APP THAT CAN DESTROY A
                // WORKSPACE'S CONFIGURATION. `workspace/tools` replaces `toolConfig.allowedTools`
                // wholesale, so every form here is built on a successful `workspace/config` read
                // and renders retry-only when that read fails. The ViewModels enforce it; these
                // blocks only wire them.
                //
                // ⚠️ EACH FORM OWNS ITS OWN ViewModel AND ITS OWN LOAD rather than sharing one
                // hoisted at the hub. A shared instance would hand two screens one copy of a value
                // that is the INPUT TO A WHOLESALE REPLACE, and the second screen would be saving
                // from a baseline it never read.

                composable(Routes.WORKSPACE_SETTINGS) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    // ⛔ THE ROLE IS A REAL GATE ON THIS HUB, WHICH VIEWERS CAN OPEN. `fromWire`
                    // fails CLOSED to null and
                    // `allowsMutation()` answers false for null, so a corrupted or renamed role
                    // segment shows the viewer's two rows rather than all eight.
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    WorkspaceSettingsScreen(
                        canMutate = role.allowsMutation(),
                        onOpenPersona = {
                            navController.navigate(
                                Routes.workspaceSettings(workspaceId, role, Routes.SECTION_PERSONA),
                            )
                        },
                        onOpenCapabilities = {
                            navController.navigate(
                                Routes.workspaceSettings(
                                    workspaceId,
                                    role,
                                    Routes.SECTION_CAPABILITIES,
                                ),
                            )
                        },
                        // ⚠️ THE EXISTING MARKETPLACE SCREEN, NOT A SECOND NUMBERS VIEW. It is
                        // already built and already read-only; a duplicate under settings would be
                        // a second place for that boundary to drift.
                        onOpenNumbers = {
                            navController.navigate(Routes.marketplace(workspaceId, role))
                        },
                        onOpenSection = { section ->
                            navController.navigate(
                                Routes.workspaceSettings(workspaceId, role, section),
                            )
                        },
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.WORKSPACE_SETTINGS_DIRECTORY) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val viewModel: DirectoryEditorViewModel = viewModel(
                        // ⚠️ Keyed on the workspace, and here it is as load-bearing as on the
                        // capabilities screen: the draft is the array a save REPLACES, so a reused
                        // instance could write one tenant's transfer directory onto another.
                        key = "workspace-directory-$workspaceId",
                        factory = DirectoryEditorViewModel.factory(
                            container.workspaceConfigRepository,
                            workspaceId,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    // ⛔ REPLAYS THE LOAD, NEVER THE SAVE — and the load DISCARDS the draft, which
                    // is the safe direction: a session change means the client's belief about the
                    // stored array is no longer something it can vouch for.
                    OnSessionChanged(sessionEpoch, viewModel::load)

                    DirectoryEditorScreen(
                        state = state,
                        onEditNew = viewModel::editNewEntry,
                        onAdd = viewModel::addEntry,
                        onEdit = viewModel::edit,
                        onRemove = viewModel::remove,
                        onSave = viewModel::save,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.WORKSPACE_SETTINGS_ROUTING) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val viewModel: RoutingRulesViewModel = viewModel(
                        key = "workspace-routing-$workspaceId",
                        factory = RoutingRulesViewModel.factory(
                            container.workspaceConfigRepository,
                            workspaceId,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    OnSessionChanged(sessionEpoch, viewModel::load)

                    RoutingRulesScreen(
                        state = state,
                        onAdd = viewModel::addRule,
                        onEdit = viewModel::edit,
                        onRemove = viewModel::remove,
                        onSave = viewModel::save,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.WORKSPACE_SETTINGS_KNOWLEDGE) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    // ⛔ A REAL GATE. The two READS admit `viewer` and all three
                    // writes exclude one — the add buys an embedding run, the delete cascades
                    // chunks, and the mode switch is a data-residency change — so a viewer gets the
                    // document list and the mode with no affordance to change either.
                    // ⚠️ `fromWire` fails CLOSED, so a corrupted role offers no write.
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: KnowledgeViewModel = viewModel(
                        // ⚠️ Keyed on the workspace AND the role: the role is a constructor
                        // argument, so an instance retained across a role change would keep the
                        // previous answer about what may be edited. Same reasoning as the
                        // workflows screen.
                        key = "workspace-knowledge-$workspaceId-${role?.name}",
                        factory = KnowledgeViewModel.factory(
                            container.knowledgeRepository,
                            workspaceId,
                            role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    // ⛔ THE LOAD IS TWO IDEMPOTENT GETs, so replaying it is safe — but it must
                    // never replay the ADD, which buys an embedding run per call.
                    OnSessionChanged(sessionEpoch, viewModel::load)

                    KnowledgeScreen(
                        state = state,
                        onEditTitle = viewModel::editTitle,
                        onEditContent = viewModel::editContent,
                        onAdd = viewModel::addDocument,
                        onDelete = viewModel::deleteDocument,
                        onSelectMode = viewModel::setMode,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.WORKSPACE_SETTINGS_MESSAGING) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    // ⛔ A REAL GATE. The GET admits `viewer` and all five writes exclude one, so a
                    // viewer sees a read-only render with no controls.
                    // ⚠️ `fromWire` fails CLOSED, so a corrupted role offers no control.
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: MessagingViewModel = viewModel(
                        // ⚠️ Keyed on the workspace: an unkeyed instance would show the previous
                        // tenant's sending identities under this one's name until the reload
                        // landed, and a phone number is exactly what an operator acts on before
                        // noticing. Keyed on the ROLE too, because it is a constructor argument and
                        // a retained instance would keep the previous answer about what may be
                        // edited — on the one screen where that answer decides whether a delete
                        // button that frees phone numbers is drawn.
                        key = "workspace-messaging-$workspaceId-${role?.name}",
                        factory = MessagingViewModel.factory(
                            container.messagingRepository,
                            workspaceId,
                            role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    // ⛔ REPLAYS THE READ, NEVER A WRITE. `load` is an idempotent GET and it also
                    // DISCARDS any open draft, which is the safe direction: a replayed create would
                    // mint a second carrier account, and a replayed delete would release phone
                    // numbers a second time.
                    OnSessionChanged(sessionEpoch, viewModel::load)

                    MessagingScreen(
                        state = state,
                        onStartEditing = viewModel::startEditing,
                        onEditDraft = viewModel::editDraft,
                        onSaveAccount = viewModel::saveAccount,
                        onTestCredentials = viewModel::testCredentials,
                        onSetDefault = viewModel::setDefault,
                        onSetChannelDefault = viewModel::setChannelDefault,
                        // ⚠️ The confirm dialog lives in the screen; this is what it calls.
                        onDelete = viewModel::deleteAccount,
                        onEditCreatorCell = viewModel::editCreatorCell,
                        onSaveCreatorCell = viewModel::saveCreatorCell,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.WORKSPACE_SETTINGS_MEMBERS) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    // ⛔ THE ROLE IS A REAL GATE HERE, AND IT GATES TWO THINGS AT DIFFERENT
                    // WIDTHS: membership writes are agency-only, the rename admits client too.
                    // `fromWire` fails closed to null, so a corrupted segment offers neither.
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: MembersViewModel = viewModel(
                        // ⚠️ Keyed on the workspace: a roster is the one list where showing the
                        // previous tenant's rows under this tenant's name would invite an operator
                        // to remove a colleague from a workspace they were not looking at.
                        key = "workspace-members-$workspaceId",
                        factory = MembersViewModel.factory(
                            container.membersRepository,
                            workspaceId,
                            role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    // ⛔ REPLAYS THE ROSTER READ, NEVER A WRITE. The read is an idempotent GET; a
                    // replayed add would re-attempt a membership change the operator may already
                    // have seen land, and a replayed removal would be worse. Same rule
                    // HqViewModel.retryOrNoop states for a confirm.
                    OnSessionChanged(sessionEpoch, viewModel::load)

                    MembersScreen(
                        state = state,
                        onEditEmail = viewModel::editEmail,
                        onEditRole = viewModel::editRole,
                        onAdd = viewModel::addMember,
                        onChangeRole = viewModel::changeRole,
                        // ⚠️ The confirm dialog lives in the screen; this is what it calls.
                        onRemove = viewModel::removeMember,
                        onEditName = viewModel::editName,
                        onRename = viewModel::rename,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.WORKSPACE_SETTINGS_CALLS) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    // ⛔ A REAL GATE, AND A PARTIAL ONE. Both reads admit `viewer` and both PATCHes
                    // exclude one, so the screen renders for every role and withholds the controls.
                    // ⚠️ `fromWire` fails CLOSED, so a corrupted role offers no write.
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: CallHandlingViewModel = viewModel(
                        // ⚠️ Keyed on the workspace AND the role: the role is a constructor
                        // argument, so an instance retained across a role change would keep the
                        // permissions it was built with.
                        key = "workspace-calls-$workspaceId-${role?.name.orEmpty()}",
                        factory = CallHandlingViewModel.factory(
                            container.callHandlingRepository,
                            workspaceId,
                            role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    OnSessionChanged(sessionEpoch, viewModel::load)

                    CallHandlingScreen(
                        state = state,
                        onSelectMode = viewModel::selectMode,
                        onSelectRingSeconds = viewModel::selectRingSeconds,
                        onSave = viewModel::save,
                        onSetAvailability = viewModel::setAvailability,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.WORKSPACE_SETTINGS_PERSONA) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val viewModel: PersonaFormViewModel = viewModel(
                        // ⚠️ Keyed on the workspace: the draft and the loaded baseline both belong
                        // to ONE tenant, and an unkeyed instance would carry a half-typed greeting
                        // — and the allowlist it was hydrated against — into the next workspace.
                        key = "workspace-persona-$workspaceId",
                        factory = PersonaFormViewModel.factory(
                            container.workspaceConfigRepository,
                            container.personaOptionsRepository,
                            workspaceId,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    // ⛔ REPLAYS THE LOAD, NEVER THE SAVE. The load is an idempotent GET; a replayed
                    // save would re-send a draft the operator may already have seen land. Same rule
                    // HqViewModel.retryOrNoop states for a confirm.
                    OnSessionChanged(sessionEpoch, viewModel::load)

                    PersonaPreviewHost(
                        repository = container.personaOptionsRepository,
                        engineFactory = container.callEngineFactory,
                        workspaceId = workspaceId,
                        // ⛔ READ AT THE TAP, NOT AT COMPOSITION. An audition is of the form as it
                        // stands when the button is pressed; a value captured earlier would go
                        // stale the moment the next character was typed.
                        formProvider = { state.previewForm() },
                    ) { openPreview ->
                        PersonaFormScreen(
                            state = state,
                            onEdit = viewModel::edit,
                            onSave = viewModel::save,
                            onRetry = viewModel::load,
                            onBack = { navController.popBackStack() },
                            onSelectEngine = viewModel::selectEngine,
                            onSelectLanguage = viewModel::selectLanguage,
                            onUpdateEngineValues = viewModel::updateValues,
                            onPreview = openPreview,
                        )
                    }
                }

                composable(Routes.WORKSPACE_SETTINGS_CAPABILITIES) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val viewModel: CapabilitiesViewModel = viewModel(
                        // ⚠️ Keyed on the workspace, and here it is stronger than a nicety: the
                        // cached baseline is the array a save REPLACES, so a reused instance could
                        // write one tenant's allowlist onto another.
                        key = "workspace-capabilities-$workspaceId",
                        factory = CapabilitiesViewModel.factory(
                            container.workspaceConfigRepository,
                            workspaceId,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    OnSessionChanged(sessionEpoch, viewModel::load)

                    CapabilitiesScreen(
                        state = state,
                        onToggleTool = viewModel::toggleTool,
                        onSaveTools = viewModel::saveTools,
                        onToggleEnrichment = viewModel::toggleEnrichment,
                        onSaveEnrichment = viewModel::saveEnrichment,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.CONTACT_DETAIL) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val contactId = entry.pathArgument(ARG_CONTACT_ID)
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: ContactDetailViewModel = viewModel(
                        key = "contact-detail-$workspaceId-$contactId",
                        factory = ContactDetailViewModel.factory(
                            repository = container.contactsRepository,
                            workspaceId = workspaceId,
                            contactId = contactId,
                            role = role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    OnSessionChanged(sessionEpoch, viewModel::load)

                    ContactDetailScreen(
                        state = state,
                        canMutate = viewModel.canMutate,
                        onBack = { navController.popBackStack() },
                        onRetry = viewModel::load,
                        onRename = viewModel::rename,
                        // ⚠️ Pops on success, so a deleted contact cannot be left on screen showing a row that
                        // no longer exists. The list re-reads on return because its Pager is invalidated by the
                        // new ViewModel instance on next entry.
                        onDelete = { viewModel.delete { navController.popBackStack() } },
                        onDismissMutationFailure = viewModel::clearMutationFailure,
                        // ⛔ NO CONFIRMATION WRAPPER HERE, unlike delete and clear. Enrichment is
                        // additive and reversible (clear-intel undoes it), and its guard is that
                        // the control is only offered when the contact is genuinely enrichable —
                        // see ContactDetailViewModel.enrich, which refuses a second run anyway.
                        onEnrich = viewModel::enrich,
                        // ⚠️ The confirm dialog lives in the screen; this is what it calls.
                        onClearIntel = viewModel::clearIntel,
                    )
                }

                composable(Routes.CALL_DETAIL) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val callId = entry.pathArgument(ARG_CALL_ID)
                    val viewModel: CallDetailViewModel = viewModel(
                        key = "call-detail-$workspaceId-$callId",
                        factory = CallDetailViewModel.factory(
                            repository = container.callsRepository,
                            workspaceId = workspaceId,
                            callId = callId,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val noPlayer = stringResource(R.string.call_detail_recording_no_player)
                    val launchFailed = stringResource(R.string.call_detail_recording_launch_failed)

                    OnSessionChanged(sessionEpoch, viewModel::load)

                    CallDetailScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onRetry = viewModel::load,
                        onShowTranscript = viewModel::loadTranscript,
                        onPlayRecording = {
                            viewModel.resolveRecording { url ->
                                // ⚠️ HANDED OFF, NOT PLAYED IN-APP. The URL is a short-lived presigned link
                                // resolved moments ago, so it goes straight to whatever app can play it and
                                // is never stored.
                                playRecording(
                                    context = context,
                                    url = url,
                                    onNoPlayer = { onShowMessage(noPlayer) },
                                    onOtherFailure = { onShowMessage(launchFailed) },
                                )
                            }
                        },
                    )
                }

                // ══ District Desk ══════════════════════════════════════════════════════════
                // ⛔ EVERY DESK AND SUPPORT ROUTE EXCLUDES `viewer`, READS INCLUDED, which is
                // unusual on this API — most reads here widen to every role and only the writes
                // narrow. So the ROLE travels in the path for the same reason it does on the
                // Inbox and HQ, but it gates the whole destination rather than one control, and
                // `fromWire` fails CLOSED: a corrupted segment shows the refusal, never the queue.
                composable(Routes.DESK) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: DeskViewModel = viewModel(
                        // ⚠️ Keyed on the workspace: the queue and the half-typed ticket belong to
                        // ONE tenant, and an unkeyed instance would carry one workspace's customer
                        // correspondence into the next.
                        key = "desk-$workspaceId",
                        factory = DeskViewModel.factory(container.deskRepository, workspaceId, role),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val compose by viewModel.compose.collectAsStateWithLifecycle()
                    val submitted by viewModel.submitted.collectAsStateWithLifecycle()
                    var composing by rememberSaveable { mutableStateOf(false) }

                    OnSessionChanged(sessionEpoch, viewModel::retryOrNoop)

                    // ⚠️ A CONFIRMATION RATHER THAN A NAVIGATION. A deduplicated submit carries no
                    // ticket at all, so there is nothing to open — and the two cases need different
                    // sentences, which is why the reference is empty rather than absent.
                    submitted?.let { reference ->
                        val message = if (reference.isEmpty()) {
                            stringResource(R.string.desk_created_duplicate)
                        } else {
                            stringResource(R.string.desk_created, reference)
                        }
                        LaunchedEffect(reference) {
                            composing = false
                            onShowMessage(message)
                            viewModel.acknowledge()
                        }
                    }

                    DeskScreen(
                        state = state,
                        canUse = viewModel.canUse,
                        onOpenTicket = { ticket ->
                            navController.navigate(Routes.deskTicket(workspaceId, role, ticket.id))
                        },
                        onFilter = viewModel::filterBy,
                        onCompose = { composing = true },
                        onEnable = viewModel::enableDesk,
                        onSettings = {
                            navController.navigate(Routes.deskSettings(workspaceId, role))
                        },
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )

                    if (composing) {
                        DeskComposeSheet(
                            state = compose,
                            onSubject = viewModel::editSubject,
                            onMessage = viewModel::editMessage,
                            onRequesterName = viewModel::editRequesterName,
                            onRequesterEmail = viewModel::editRequesterEmail,
                            onRequesterPhone = viewModel::editRequesterPhone,
                            onSubmit = viewModel::submit,
                            onDismiss = {
                                composing = false
                                viewModel.discardDraft()
                            },
                        )
                    }
                }

                composable(Routes.DESK_TICKET) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    // ⚠️ THE TICKET'S UUID, not its `T-n` display reference. The reference is per
                    // workspace and is a string the server formats; only the id addresses the route.
                    val ticketId = entry.pathArgument(ARG_TICKET_ID)
                    val viewModel: DeskTicketViewModel = viewModel(
                        key = "desk-ticket-$workspaceId-$ticketId",
                        factory = DeskTicketViewModel.factory(
                            repository = container.deskRepository,
                            workspaceId = workspaceId,
                            ticketId = ticketId,
                            role = role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val draft by viewModel.draft.collectAsStateWithLifecycle()

                    // ⛔ THE READ ONLY. A reply may already have been posted when the session
                    // expired, and replaying it would put a second message in the customer's thread.
                    OnSessionChanged(sessionEpoch, viewModel::retryOrNoop)

                    DeskTicketScreen(
                        state = state,
                        draft = draft,
                        canUse = viewModel.canUse,
                        onDraftChange = viewModel::editDraft,
                        onSend = viewModel::send,
                        onSetStatus = viewModel::setStatus,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(Routes.DESK_SETTINGS) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: DeskSettingsViewModel = viewModel(
                        key = "desk-settings-$workspaceId",
                        factory = DeskSettingsViewModel.factory(
                            repository = container.deskRepository,
                            attachments = container.attachmentReader(context),
                            workspaceId = workspaceId,
                            role = role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    // ⚠️ THE SAME PICKER THE COMPOSER USES, and it requests no permission: it
                    // returns a one-shot read grant for exactly the item chosen, which is why this
                    // app declares no READ_MEDIA_IMAGES and cannot enumerate the gallery.
                    val picker = rememberLauncherForActivityResult(
                        ActivityResultContracts.PickVisualMedia(),
                    ) { uri -> uri?.let { viewModel.uploadLogo(it.toString()) } }

                    OnSessionChanged(sessionEpoch, viewModel::retryOrNoop)

                    DeskSettingsScreen(
                        state = state,
                        canUse = viewModel.canUse,
                        onEnabled = viewModel::setEnabled,
                        onNotify = viewModel::setNotify,
                        onBrandName = viewModel::editBrandName,
                        onSave = viewModel::save,
                        onPickLogo = {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onRemoveLogo = viewModel::deleteLogo,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }

                // ══ Support (this workspace's requests with Distronode) ════════════════════
                composable(Routes.SUPPORT) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val viewModel: SupportViewModel = viewModel(
                        // ⚠️ Keyed on the workspace. It also holds the draft's IDEMPOTENCY KEY,
                        // which must not follow the operator into another tenant — a key reused
                        // across workspaces would still deduplicate, silently swallowing the second
                        // workspace's first request.
                        key = "support-$workspaceId",
                        factory = SupportViewModel.factory(
                            container.supportRepository,
                            workspaceId,
                            role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val compose by viewModel.compose.collectAsStateWithLifecycle()
                    val submitted by viewModel.submitted.collectAsStateWithLifecycle()
                    var composing by rememberSaveable { mutableStateOf(false) }

                    OnSessionChanged(sessionEpoch, viewModel::retryOrNoop)

                    // ⛔ ALL THREE OUTCOMES ARE SUCCESSES, PENDING INCLUDED. We hold the claim row
                    // and a human will see it; only the reference to quote is missing. Showing it
                    // as an error is what makes people send the same request twice.
                    submitted?.let { outcome ->
                        // ⚠️ THE RESOURCE IS CHOSEN FIRST AND RESOLVED ONCE. Only FILED carries an
                        // issue key and it always does (see SupportSubmitted), so the key list is
                        // empty for the other two and their strings take no argument. Resolving
                        // inside each arm needed an `orEmpty()` for a null FILED key that cannot
                        // occur.
                        val messageRes = when (outcome.outcome) {
                            SupportSubmitOutcome.FILED -> R.string.support_filed
                            SupportSubmitOutcome.DEDUPLICATED -> R.string.support_duplicate
                            SupportSubmitOutcome.PENDING -> R.string.support_pending
                        }
                        val message = stringResource(messageRes, *listOfNotNull(outcome.issueKey).toTypedArray())
                        LaunchedEffect(outcome) {
                            composing = false
                            onShowMessage(message)
                            viewModel.acknowledge()
                        }
                    }

                    SupportScreen(
                        state = state,
                        canUse = viewModel.canUse,
                        onOpenRequest = { request ->
                            // ⛔ ADDRESSED BY `issueKey` WHEN THERE IS ONE, ELSE BY OUR OWN ROW id.
                            // The route resolves both, which is exactly what makes a request that
                            // has not been filed yet reachable at all.
                            navController.navigate(
                                Routes.supportRequest(
                                    workspaceId,
                                    role,
                                    request.issueKey ?: request.id,
                                ),
                            )
                        },
                        onCompose = { composing = true },
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )

                    if (composing) {
                        SupportComposeDialog(
                            state = compose,
                            onKind = viewModel::setKind,
                            onSubject = viewModel::editSubject,
                            onMessage = viewModel::editMessage,
                            onSubmit = viewModel::submit,
                            onDismiss = {
                                composing = false
                                viewModel.discardDraft()
                            },
                        )
                    }
                }

                composable(Routes.SUPPORT_REQUEST) { entry ->
                    val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
                    val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
                    val requestKey = entry.pathArgument(ARG_REQUEST_KEY)
                    val viewModel: SupportRequestViewModel = viewModel(
                        key = "support-request-$workspaceId-$requestKey",
                        factory = SupportRequestViewModel.factory(
                            repository = container.supportRepository,
                            workspaceId = workspaceId,
                            key = requestKey,
                            role = role,
                        ),
                    )
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val draft by viewModel.draft.collectAsStateWithLifecycle()
                    val closedAs by viewModel.closedAs.collectAsStateWithLifecycle()

                    // ⛔ THE READ ONLY. Neither write here is idempotent: a replayed reply leaves a
                    // second PUBLIC comment in the customer's thread and a replayed close leaves a
                    // second "Closed at the requester's request by …" beside it.
                    OnSessionChanged(sessionEpoch, viewModel::retryOrNoop)

                    closedAs?.let { statusName ->
                        // ⚠️ THE DESK'S OWN WORD, which is localised — substituting "Closed" would
                        // print English over a status Atlassian spells in another language.
                        val message = stringResource(R.string.support_closed, statusName)
                        LaunchedEffect(statusName) {
                            onShowMessage(message)
                            viewModel.acknowledgeClosed()
                        }
                    }

                    SupportRequestScreen(
                        state = state,
                        draft = draft,
                        canUse = viewModel.canUse,
                        onDraftChange = viewModel::editDraft,
                        onSend = viewModel::send,
                        onClose = viewModel::close,
                        onRetry = viewModel::load,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        // ⚠️ And only once a workspace has resolved: before that the two workspace-scoped tabs have
        // nowhere to go, and a tab that does nothing is worse than an absent one. Tested inline so
        // the id is smart-cast inside: the tabs below cannot be built without one.
        if (topLevel && workspaceId != null) {
            DistrictNavBar {
                DistrictNavItem(
                    label = stringResource(R.string.nav_overview),
                    selected = route == Routes.OVERVIEW,
                    description = NAV_OVERVIEW_DESCRIPTION,
                    onClick = { navController.navigateTopLevel(Routes.OVERVIEW) },
                )
                DistrictNavItem(
                    label = stringResource(R.string.nav_inbox),
                    selected = route == Routes.INBOX,
                    description = NAV_INBOX_DESCRIPTION,
                    onClick = {
                        navController.navigateTopLevel(Routes.inbox(workspaceId, role))
                    },
                )
                DistrictNavItem(
                    label = stringResource(R.string.nav_calls),
                    selected = route == Routes.CALL_LOG,
                    description = NAV_CALLS_DESCRIPTION,
                    onClick = { navController.navigateTopLevel(Routes.callLog(workspaceId)) },
                )
                DistrictNavItem(
                    label = stringResource(R.string.nav_contacts),
                    selected = route == Routes.CONTACTS,
                    description = NAV_CONTACTS_DESCRIPTION,
                    onClick = {
                        navController.navigateTopLevel(Routes.contacts(workspaceId, role))
                    },
                )
                DistrictNavItem(
                    label = stringResource(R.string.nav_account),
                    selected = route == Routes.SETTINGS,
                    description = NAV_ACCOUNT_DESCRIPTION,
                    onClick = { navController.navigateTopLevel(Routes.SETTINGS) },
                )
            }
        }
    }
}

/**
 * Navigate to a top-level destination.
 *
 * ⛔ WITHOUT `popUpTo` THE BACK STACK GROWS WITH EVERY TAP. Overview -> Calls -> Contacts ->
 * Overview would leave four entries, so the system back gesture walks backwards through a history
 * the user experienced as switching tabs rather than as going deeper. Popping to the start
 * destination makes back mean "leave the app" from any tab, which is what a tab bar implies.
 *
 * ⚠️ `saveState`/`restoreState` so a tab keeps its scroll position AND its Paging state. Without
 * them, returning to the call log refetches page one and loses the user's place — on a 258-call log
 * that is the whole difference between a tab bar and a set of links.
 *
 * ⚠️ `launchSingleTop` because tapping the CURRENT tab must not push a second copy of it.
 */
private fun NavHostController.navigateTopLevel(destination: String) {
    navigate(destination) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val NAV_OVERVIEW_DESCRIPTION: String = "district-nav-overview"
const val NAV_INBOX_DESCRIPTION: String = "district-nav-inbox"
const val NAV_CALLS_DESCRIPTION: String = "district-nav-calls"
const val NAV_CONTACTS_DESCRIPTION: String = "district-nav-contacts"
const val NAV_ACCOUNT_DESCRIPTION: String = "district-nav-account"

/**
 * Hand a resolved recording URL to whatever app can play it.
 *
 * ⛔ A device with nothing able to play audio THROWS rather than doing nothing, and an unhandled
 * ActivityNotFoundException here crashes the app.
 *
 * ⛔ AND EVERY OTHER THROWABLE IS REPORTED TOO, NOT DISCARDED IN SILENCE. Inspecting only
 * `ActivityNotFoundException` would drop the rest of `runCatching`'s result on the floor, so a
 * SecurityException from a restrictive player, or a background-activity-start refusal, would make
 * the button do literally nothing: no message, no log, no way for the user to tell a broken
 * recording from a broken app. Both branches say something.
 */
/**
 * Open an attachment in whatever the device uses for https.
 *
 * ⛔ NEW_TASK IS REQUIRED. `context` here is the application context (see the ⛔ in
 * [DistrictNavHost]), and starting an activity from a non-activity context without it throws —
 * the same trap [playRecording] documents.
 *
 * ⚠️ NO FAILURE CHANNEL, UNLIKE [playRecording], AND THE ASYMMETRY IS DELIBERATE. That one opens
 * an audio URL, for which a device may genuinely have no handler; this opens an ordinary https
 * URL, which every device with a browser resolves. Threading an error state back into the thread
 * for a case that needs a device with no browser at all would cost a ViewModel entry point for a
 * failure nobody has seen. `runCatching` still keeps it from crashing the app.
 */
private fun openAttachment(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Hand an authenticated URL to the system browser, reporting the one outcome the user must be told
 * about.
 *
 * ⛔ EXTRACTED BECAUSE THE `if` COUNTED, WHICH IS THE SAME detekt CEILING [roomsLobbyDestination]
 * documents. `DistrictNavHost` sits at the CyclomaticComplexMethod threshold (15) and every branch
 * inside a `composable {}` lambda counts toward the ENCLOSING function — so the third copy of this
 * three-line block, added for the marketplace hand-off, failed the build. Three identical branches
 * were also three chances for one call site to drop the report.
 *
 * ⛔ ALWAYS A SYSTEM BROWSER, NEVER A WEBVIEW. Every caller's destination is authenticated — the
 * account-deletion page, a Stripe hosted invoice, the web number marketplace — so the flow needs
 * the user's real browser session, which this app cannot supply and must not intercept. See
 * [CustomTabsLauncher].
 *
 * ⛔ `launchExternally`, NOT `launch`, AND THE MARKETPLACE IS WHY. With App Links this app is a
 * verified handler for `https://[www.]distronode.com/dashboard/district...`, and
 * `marketplaceWebUrl` builds exactly such a URL — so an IMPLICIT ACTION_VIEW for it resolves back
 * into this app, onto the marketplace screen the user just pressed "open on the web" from. The
 * hand-off exists *because* buying a number has to stay off the phone under Play's Payments policy,
 * so a loop back into the app is not a cosmetic bug: it makes the web purchase unreachable.
 * `launchExternally` pins a browser package, which an App Link cannot intercept. The other two
 * destinations are not claimed by this app and are unaffected either way.
 *
 * @param browserMissingMessage ⚠️ RESOLVED BY THE CALLER AT COMPOSITION TIME, not looked up here.
 *   `context.getString` from inside a callback reads through a Context captured at composition and
 *   does not update with a locale or font-scale change — the `LocalContextGetResourceValueCall`
 *   lint error this file used to carry.
 */
private fun openInBrowser(
    context: Context,
    url: String,
    browserMissingMessage: String,
    onShowMessage: (String) -> Unit,
) {
    // ⚠️ Only the NO-BROWSER case is reported. A Custom Tab and a plain browser are both a working
    // hand-off; the third outcome is the one the user can neither retry past nor understand.
    if (CustomTabsLauncher.launchExternally(context, url) is CustomTabsLauncher.LaunchResult.NoBrowser) {
        onShowMessage(browserMissingMessage)
    }
}

/**
 * The rooms lobby.
 *
 * ⛔ ITS OWN BUILDER, NOT INLINE IN [DistrictNavHost], AND THAT IS A detekt CEILING WORTH
 * RESPECTING RATHER THAN SUPPRESSING. Lambdas inside a `composable {}` block count toward the
 * ENCLOSING function's cyclomatic complexity, and that function was already at the threshold; the
 * rooms pair pushed it over. The cut is also along a real seam — these are the only destinations
 * in this graph that own a live media session.
 */
private fun NavGraphBuilder.roomsLobbyDestination(
    container: AppContainer,
    navController: NavHostController,
    sessionEpoch: Int,
) {
    composable(Routes.ROOMS) { entry ->
        val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
        // ⚠️ Not a gate on this screen — all three roles may read meetings. It is
        // carried so the room it launches has one. See the ⚠️ on Routes.ROOMS.
        val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
        val viewModel: RoomsLobbyViewModel = viewModel(
            // ⚠️ Keyed on the workspace: an unkeyed instance would show the previous
            // tenant's meeting minutes under the new tenant's name until the reload
            // landed, and a meeting summary is exactly the kind of thing somebody
            // reads before noticing whose it is.
            key = "rooms-$workspaceId",
            factory = RoomsLobbyViewModel.factory(
                container.meetingsRepository,
                workspaceId,
            ),
        )
        val state by viewModel.state.collectAsStateWithLifecycle()

        // ⚠️ The history read is an idempotent GET, so replaying it on a session change
        // is unconditionally safe. The typed room name is deliberately NOT reset with
        // it — that is the user's input, not server state.
        OnSessionChanged(sessionEpoch) { viewModel.load(refreshing = true) }

        RoomsLobbyScreen(
            state = state,
            onRoomNameChange = viewModel::onRoomNameChange,
            onJoin = {
                // ⛔ THE NAME IS MINTED BY THE ViewModel THROUGH MeetRoomName, AND THE
                // NULL CASE IS A REFUSAL RATHER THAN A FALLBACK. An empty suffix would
                // produce `meet_<ws>_`, which the server rejects as a malformed room —
                // a 400 that reads as a server fault for what is really an empty field.
                viewModel.roomNameToJoin()?.let { roomName ->
                    navController.navigate(
                        Routes.activeRoom(workspaceId, role, roomName),
                    )
                }
            },
            onRejoin = { roomName ->
                viewModel.rejoinName(roomName)?.let {
                    navController.navigate(Routes.activeRoom(workspaceId, role, it))
                }
            },
            onOpenMeeting = viewModel::openMeeting,
            onCloseMeeting = viewModel::closeMeeting,
            onRetry = viewModel::load,
            onBack = { navController.popBackStack() },
        )
    }
}

/**
 * One live room.
 *
 * ⛔ EVERYTHING ELSE IN THIS GRAPH IS A READ OR A FORM; THIS DESTINATION HOLDS A SOCKET, THE
 * MICROPHONE AND THE CAMERA for as long as it is on the back stack. That is why its ViewModel is
 * keyed on the ROOM NAME — a different room is a different engine, never a re-pointed one, since
 * one engine owns one connection and the device's audio focus — and why leaving is an explicit
 * action that pops only after the disconnect has resolved.
 */
private fun NavGraphBuilder.activeRoomDestination(
    container: AppContainer,
    navController: NavHostController,
    context: Context,
) {
    composable(Routes.ACTIVE_ROOM) { entry ->
        // ⚠️ NO `workspaceId` IS READ HERE, even though the route carries one. It is already
        // inside the room name — which is the copy the SERVER parses back out to decide who may
        // join — so reading it separately would produce a value nothing uses and that the next
        // reader would assume was authoritative. See ActiveRoomViewModel.roomName.
        //
        // ⚠️ The role IS parsed, through fromWire, which fails CLOSED to null — so a corrupted
        // role publishes nothing rather than defaulting to a permissive one.
        val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
        val roomName = entry.pathArgument(ARG_ROOM_NAME)
        val viewModel: ActiveRoomViewModel = viewModel(
            // ⛔ KEYED ON THE ROOM, NOT THE WORKSPACE. An unkeyed or workspace-keyed
            // instance would hand a second room the FIRST room's engine, which is
            // already connected somewhere else — one socket, two rooms, and the audio
            // focus belongs to whichever connected last.
            key = "room-$roomName",
            factory = ActiveRoomViewModel.factory(
                engineFactory = container.callEngineFactory,
                repository = container.meetingsRepository,
                roomName = roomName,
                role = role,
                // ⚠️ The guest path the server returns is a PATH; the origin it hangs
                // off is this app's single base URL. See ApiEnvironment for why there
                // is only one.
                webOrigin = ApiEnvironment.baseUrl,
            ),
        )
        val state by viewModel.state.collectAsStateWithLifecycle()

        // ⛔ THE PERMISSION LAUNCHER LIVES HERE, NOT IN ActiveRoomScreen, for the same
        // reason the photo picker does: rememberLauncherForActivityResult needs an
        // ActivityResultRegistryOwner, which a plain `createComposeRule` test host does
        // not provide — putting it in the screen would make every screen test crash on
        // composition. The screen takes plain state and lambdas and stays testable.
        //
        // ⚠️ RequestMultiplePermissions INVOKES ITS CALLBACK EVEN WHEN EVERYTHING IS
        // ALREADY GRANTED, returning immediately with no UI. That is what makes this one
        // path rather than two, and it is why the connect is driven from the callback.
        val permissions = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { granted ->
            viewModel.onPermissionsResult(
                microphoneGranted = granted[Manifest.permission.RECORD_AUDIO] == true,
                cameraGranted = granted[Manifest.permission.CAMERA] == true,
            )
        }
        // ⛔ `Unit` AS THE KEY, SO THIS FIRES ONCE PER DESTINATION AND NOT PER
        // RECOMPOSITION. Re-launching would re-enter onPermissionsResult, and although
        // that call is idempotent, a permission request replayed on every frame is a
        // dialog the user cannot dismiss.
        LaunchedEffect(Unit) {
            permissions.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
            )
        }

        ActiveRoomScreen(
            state = state,
            roomName = roomName,
            controls = RoomControls(
                onToggleMicrophone = viewModel::toggleMicrophone,
                onToggleCamera = viewModel::toggleCamera,
                onFlipCamera = viewModel::flipCamera,
                onToggleSpeaker = viewModel::toggleSpeaker,
            ),
            // ⛔ POPS ONLY AFTER THE DISCONNECT RESOLVES. Popping first would clear the
            // ViewModel and route the teardown through onCleared, which exists for the
            // case where the user never pressed anything — making the ordinary exit
            // depend on the emergency one.
            onLeave = { viewModel.leave { navController.popBackStack() } },
            onShareInvite = { link -> shareInvite(context, link) },
        )
    }
}

/**
 * The outbound softphone.
 *
 * ⛔ ITS OWN BUILDER FOR THE REASON THE ROOMS PAIR HAVE ONE: lambdas inside a `composable {}` block
 * count toward the ENCLOSING function's cyclomatic complexity, and [DistrictNavHost] is already at
 * detekt's ceiling. The cut is along a real seam too — this and the rooms destinations are the only
 * ones in this graph that hold a live media session.
 *
 * ⛔ THE PERMISSION LAUNCHER IS KEYED ON A COUNTER, NOT FIRED ON ENTRY, AND BOTH HALVES MATTER.
 * Firing on entry would show a microphone dialog to somebody who has only opened the dialler, which
 * is the request users deny permanently; keying the effect on a BOOLEAN would leave the second dial
 * unable to re-ask, because a flag already `true` produces no state change and `LaunchedEffect` would
 * not re-run. The counter increments per tap, so every dial asks exactly once.
 */
private fun NavGraphBuilder.dialerDestination(
    container: AppContainer,
    navController: NavHostController,
    sessionEpoch: Int,
) {
    composable(Routes.DIALER) { entry ->
        val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
        // ⚠️ Parsed through fromWire, which fails CLOSED to null — so a corrupted role cannot dial.
        val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
        val viewModel: DialerViewModel = viewModel(
            // ⚠️ Keyed on the workspace: the call-back list and the number that would be dialled
            // both belong to one tenant, and an unkeyed instance would offer the previous
            // workspace's callers under the new one's name.
            key = "dialer-$workspaceId",
            factory = DialerViewModel.factory(
                dialRepository = container.dialRepository,
                callsRepository = container.callsRepository,
                // ⛔ ENDING A CALL LOCALLY IS NOT A HANG-UP. Without this the carrier leg outlives
                // the app's own teardown and goes on billing — see CallControlRepository.
                callControl = container.callControlRepository,
                engineFactory = container.callEngineFactory,
                telecom = container.telecomBridge,
                workspaceId = workspaceId,
                role = role,
            ),
        )
        val state by viewModel.state.collectAsStateWithLifecycle()

        // ⚠️ The call-back list is an idempotent GET, so replaying it on a session change is
        // unconditionally safe. ⛔ Nothing here replays a DIAL, and nothing may: that route places
        // a call, and a session-change hook that re-sent it would ring somebody because a token
        // was refreshed.
        OnSessionChanged(sessionEpoch) { viewModel.load() }

        // ⛔ THE LAUNCHER LIVES HERE, NOT IN THE SCREEN, for the reason the room's does:
        // rememberLauncherForActivityResult needs an ActivityResultRegistryOwner, which a plain
        // `createComposeRule` test host does not provide — putting it in the screen would make
        // every screen test crash on composition.
        val microphone = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> viewModel.onMicrophonePermissionResult(granted) }
        LaunchedEffect(state.microphoneRequest) {
            // ⚠️ Zero is the initial value and means "nobody has asked to dial", so the effect's
            // first run on entry must not launch anything.
            if (state.microphoneRequest > 0) microphone.launch(Manifest.permission.RECORD_AUDIO)
        }

        DialerScreen(
            state = state,
            handlers = DialerHandlers(
                onEntryChange = viewModel::onEntryChange,
                // ⛔ THE SAME HANDLER AS THE FIELD, DELIBERATELY. Tapping a call-back row FILLS the
                // number in; it does not dial. One tap must never place a call, least of all from a
                // scrolling list where a mis-scroll lands on a row.
                onCallBack = viewModel::onEntryChange,
                onDial = viewModel::onDial,
                onToggleMicrophone = viewModel::toggleMicrophone,
                onToggleSpeaker = viewModel::toggleSpeaker,
                // ⚠️ NO POP. Unlike the room's `leave`, hanging up stays on this destination and
                // shows the duration — the operator opened the dialler to make calls, and a screen
                // that vanished would answer "how long was that" with nothing and put them a
                // navigation away from the next call.
                onHangUp = { viewModel.hangUp {} },
                onDismissEndedCall = viewModel::clearEndedCall,
                // ⛔ BACK GOES THROUGH THE ViewModel'S TEARDOWN BY POPPING, NOT AROUND IT. Popping
                // clears the ViewModel, whose `onCleared` disconnects on the scope that outlives
                // it — the emergency path, which is exactly right for an exit the operator made
                // without hanging up.
                onBack = { navController.popBackStack() },
            ),
        )
    }
}

/**
 * Hand a guest invite to the system share sheet.
 *
 * ⛔ THE SHARE SHEET, NOT THE CLIPBOARD, AND NOT AN IN-APP PICKER. The link is a twelve-hour
 * transferable capability that grants PUBLISH rights in the room, so where it goes is a decision the
 * person sharing it must make explicitly — a silent copy-to-clipboard puts it somewhere any app can
 * read, and this app has no business enumerating messaging targets itself.
 *
 * ⚠️ NEW_TASK because `context` is the application context deliberately (see the ⛔ in
 * DistrictNavHost); starting an activity from a non-activity context without it throws. `runCatching`
 * because a device with nothing that accepts text/plain is possible, if unlikely, and losing the
 * share is better than losing the meeting.
 */
private fun shareInvite(context: Context, link: String) {
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link),
                null,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun playRecording(
    context: Context,
    url: String,
    onNoPlayer: () -> Unit,
    onOtherFailure: () -> Unit,
) {
    val launch = runCatching {
        context.startActivity(
            // ⚠️ NEW_TASK is required: `context` is the application context deliberately (see the
            // ⛔ in DistrictNavHost) and starting an activity from a non-activity context without
            // this flag throws.
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    when (launch.exceptionOrNull()) {
        null -> Unit
        is ActivityNotFoundException -> onNoPlayer()
        else -> onOtherFailure()
    }
}

/**
 * Route templates and builders.
 *
 * ⚠️ Ids are URL path arguments, so a value containing a slash would address a different route.
 * Both are server-generated cuids, but they are encoded on the way in for the same reason the API
 * client encodes path segments: a value must not be able to change where it points. The templates
 * are plain literals because a `const val` cannot contain a string template.
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED, DELIBERATELY AND WITH A NARROW JUSTIFICATION. detekt caps an
 * object at 11 and this one crossed it when workspace settings landed. The rule exists to catch a
 * class that has grown several responsibilities; this is a LOOKUP TABLE of pure string builders
 * with exactly one responsibility, and its size is a function of how many destinations the product
 * has rather than of how much any one of them does. The alternatives were both worse: splitting it
 * would put the template and its builder in two places, where a rename can silently desynchronise
 * them (the failure mode is a 404 at runtime, which nothing type-checks), and folding builders into
 * each other would hide the encoding this doc block exists to guarantee. If this ever stops being a
 * table of one-liners, the suppression should go rather than be widened — `workspaceSettings` is
 * already the one entry taking a parameter that selects a sub-path, and a second of those is the
 * signal that this needs real structure.
 */
@Suppress("TooManyFunctions")
object Routes {
    const val OVERVIEW = "overview"

    /**
     * ⚠️ NOT workspace-scoped. Sign-out and account deletion are properties of the ACCOUNT, not of
     * a tenant, so this destination must stay reachable when no workspace resolves at all — which is
     * exactly the state a Play reviewer's fresh account is in.
     */
    const val SETTINGS = "settings"

    /**
     * The account's signed-in devices.
     *
     * ⚠️ NOT WORKSPACE-SCOPED, FOR THE SAME REASON [SETTINGS] IS NOT — and here it is stronger
     * than a convention. A native session belongs to a USER: none of the three routes behind
     * this screen takes a `workspaceId` and none could, because the scope comes from the
     * verified session. Putting a workspace in this route would encode a tenant into a
     * destination whose data has none, and would make the screen unreachable in exactly the
     * state someone needs it most — an account whose workspaces have not resolved.
     */
    const val DEVICES = "devices"

    const val CALL_LOG = "workspace/{workspaceId}/calls"
    const val CALL_DETAIL = "workspace/{workspaceId}/calls/{callId}"
    const val INBOX = "workspace/{workspaceId}/inbox/{role}"

    /**
     * ⚠️ The thread selector is a SINGLE segment carrying either `contact:<id>` or `addr:<address>`,
     * which is the server's own `threadKey` form. Two optional segments would make the route ambiguous
     * when only the second is present, and a thread with no Contact row genuinely has no id.
     */
    const val THREAD = "workspace/{workspaceId}/inbox/{role}/{threadKey}" +
        "?$ARG_REPLY_TO={$ARG_REPLY_TO}&$ARG_REPLY_CHANNEL={$ARG_REPLY_CHANNEL}" +
        "&$ARG_THREAD_TITLE={$ARG_THREAD_TITLE}"
    const val CONTACTS = "workspace/{workspaceId}/contacts/{role}"
    const val CONTACT_DETAIL = "workspace/{workspaceId}/contacts/{role}/{contactId}"

    /**
     * District HQ.
     *
     * ⚠️ Carries the role for the same reason contacts and the Inbox do: the confirm control is
     * role-gated, and a destination restored after process death has to be able to decide that for
     * itself rather than inheriting whatever a shared holder happens to contain.
     */
    const val HQ = "workspace/{workspaceId}/hq/{role}"

    /**
     * Analytics.
     *
     * ⚠️ CARRIES NO ROLE, WHICH IS THE ONLY WORKSPACE-SCOPED DRILL-DOWN THAT DOES NOT. Contacts,
     * the Inbox and HQ all encode one because each has a control the server refuses to a viewer;
     * this screen has none — both of its routes admit agency, client and viewer alike. Adding a
     * role here would put a value in the URL that decides nothing, which is worse than an absence
     * because the next reader has to establish that it is unused.
     */
    const val ANALYTICS = "workspace/{workspaceId}/analytics"

    /**
     * The phone-number marketplace.
     *
     * ⚠️ CARRIES A ROLE EVEN THOUGH BOTH OF ITS ROUTES ADMIT `viewer` — the opposite call from
     * [ANALYTICS], and for a reason that is worth stating so it does not get "tidied" away. There
     * is nothing here to GATE, but there is something to WORD: the read-only caption tells an
     * agency or client member to make the change on the web dashboard, and tells a viewer to ask
     * someone who can. Sending a viewer to a dashboard that will also refuse them is worse than
     * saying nothing, so the role has to survive process death with the destination.
     */
    const val MARKETPLACE = "workspace/{workspaceId}/numbers/{role}"

    /**
     * Billing.
     *
     * ⛔ WORKSPACE-SCOPED, EVEN THOUGH IT WOULD READ MORE NATURALLY AS A SETTINGS ROW. A plan, an
     * overage cap and a month's metered minutes are all properties of ONE TENANT — the route behind
     * them takes a `workspaceId` and answers 404 without one — while [SETTINGS] and [DEVICES] are
     * deliberately account-scoped so they stay reachable when no workspace resolves at all. Putting
     * this behind Settings would either encode a tenant into a destination whose siblings have
     * none, or leave the screen unable to name the workspace it is reporting on. It is reached from
     * the overview tiles instead, where workspace context already exists.
     *
     * ⚠️ CARRIES A ROLE EVEN THOUGH THE ROUTE ADMITS `viewer` — the same call as [MARKETPLACE] and
     * for the same reason. Nothing here is gated; the role decides what the read-only caption SAYS,
     * since telling a viewer to change the plan on the web dashboard sends them somewhere that will
     * also refuse them.
     */
    const val BILLING = "workspace/{workspaceId}/billing/{role}"

    /**
     * The workspace settings hub, and its two form sections.
     *
     * ⛔ WORKSPACE-SCOPED RATHER THAN A ROW UNDER [SETTINGS], AND THAT IS SCOPE RATHER THAN LAYOUT
     * — the same call [BILLING] makes. [SETTINGS] and [DEVICES] carry no workspace ON PURPOSE
     * (sign-out and device management belong to a USER and must stay reachable when no workspace
     * resolves at all), so a tenant-scoped section cannot hang off them without encoding a
     * workspace into a destination whose siblings have none.
     *
     * ⛔ AND THE ROLE HERE IS A REAL GATE, UNLIKE [MARKETPLACE] AND [BILLING] WHERE IT ONLY
     * DECIDES WORDING. Every route behind these three — INCLUDING the config read — excludes
     * `viewer` server-side, which is unusual on this surface and deliberate: the payload carries
     * staff transfer numbers and the operator's own prompt. The overview hides the entry for a
     * viewer, and the role travels in the path so a destination restored after process death can
     * still decide that for itself.
     *
     * ⚠️ THE TWO SECTIONS ARE CHILDREN OF THE HUB'S PATH, so a back press from a form lands on the
     * hub rather than on the overview — which is what a hub is for.
     */
    const val WORKSPACE_SETTINGS = "workspace/{workspaceId}/settings/{role}"
    const val WORKSPACE_SETTINGS_PERSONA = "workspace/{workspaceId}/settings/{role}/persona"
    const val WORKSPACE_SETTINGS_CAPABILITIES =
        "workspace/{workspaceId}/settings/{role}/capabilities"

    /**
     * The two destructive-array editors.
     *
     * ⛔ SIBLING SECTIONS RATHER THAN A SHARED "EDITORS" DESTINATION, because each one loads its
     * own configuration and each one's save REPLACES a different stored array. A destination that
     * hosted both would hand two editors one copy of a value that is the input to a wholesale
     * replace, and the second would be saving from a baseline it never read — the same reasoning
     * that keeps the persona and capability forms apart.
     */
    const val WORKSPACE_SETTINGS_DIRECTORY = "workspace/{workspaceId}/settings/{role}/directory"
    const val WORKSPACE_SETTINGS_ROUTING = "workspace/{workspaceId}/settings/{role}/routing"

    /**
     * ⚠️ THEIR ROUTES ADMIT `viewer` WHILE THE HUB DOES NOT, so these two are reachable only
     * because the hub in front of them is not. That asymmetry is the server's — nothing on the
     * knowledge or messaging surface carries a staff transfer number — and it is recorded here so
     * that opening the hub to viewers later is a decision about the PERSONA and CAPABILITY forms,
     * which genuinely 403, rather than about these.
     */
    const val WORKSPACE_SETTINGS_KNOWLEDGE = "workspace/{workspaceId}/settings/{role}/knowledge"
    const val WORKSPACE_SETTINGS_MESSAGING = "workspace/{workspaceId}/settings/{role}/messaging"

    /**
     * Who answers an inbound call, and whether this person is rung.
     *
     * ⚠️ THE SAME ASYMMETRY THE TWO ABOVE CARRY, AND FOR A SHARPER REASON. Both routes behind this
     * screen ADMIT `viewer` — the handling mode explains a call list a viewer can already see, and
     * the availability read answers a viewer `false` with `reason: "role"` rather than refusing —
     * while both PATCHes exclude one. So the destination is reachable for every role and the screen
     * itself withholds the controls, rather than the hub withholding the screen.
     */
    const val WORKSPACE_SETTINGS_CALLS = "workspace/{workspaceId}/settings/{role}/calls"

    /**
     * Membership, and the workspace's display name.
     *
     * ⛔ THE ROLE IN THIS PATH IS A GATE FOR TWO DIFFERENT THINGS AT TWO DIFFERENT WIDTHS, WHICH
     * IS UNIQUE ON THIS LIST. The membership WRITES are agency-only — the narrowest guard in the
     * API, because these rows are what `getWorkspaceRole` answers from — while the rename admits
     * agency and client. `WorkspaceRole.canMutate` is therefore the wrong gate for the first and
     * the right one for the second, exactly the case its own KDoc warns about. The destination
     * derives both from this segment.
     *
     * ⚠️ AND ITS READ ADMITS `viewer` WHILE THE HUB IN FRONT OF IT DOES NOT — the same asymmetry
     * [WORKSPACE_SETTINGS_KNOWLEDGE] and [WORKSPACE_SETTINGS_MESSAGING] carry. A viewer is kept
     * out of a roster the server would happily serve them, because the hub is gated on the
     * persona and capability forms, which genuinely 403. Recorded here so that opening the hub to
     * viewers later is a decision about those two forms rather than about this one.
     */
    const val WORKSPACE_SETTINGS_MEMBERS = "workspace/{workspaceId}/settings/{role}/members"

    /**
     * Multi-party rooms: the lobby, and one live room.
     *
     * ⛔ THE LIVE ROOM CARRIES THE **FULL** `meet_<workspaceId>_<suffix>` NAME AS ONE SEGMENT, NOT
     * THE SUFFIX. The room name is what the token is minted for and what the server parses the
     * workspace back out of, so a destination restored after process death must hold the exact
     * string rather than a recipe for rebuilding it — and rebuilding it is precisely where a
     * `video_` name (a billable avatar session, one character away) could be produced by mistake.
     * [com.distronode.districtai.core.model.MeetRoomName] is the only thing that mints one.
     *
     * ⚠️ AND THE ROLE IS IN THE PATH BECAUSE IT DECIDES WHAT THE ROOM OFFERS. A viewer's token
     * carries `canPublish:false`, so the microphone and camera controls are disabled — a destination
     * restored after a kill has to be able to decide that for itself rather than inheriting whatever
     * a shared holder happens to contain. It fails CLOSED: an unrecognised value is no publishing.
     *
     * ⚠️ THE LOBBY CARRIES A ROLE TOO, and here it decides nothing about the LIST (all three roles
     * may read meetings) — it is there because every join from the lobby has to hand one to the
     * room. Carrying it in the lobby's own path is what makes that survive process death.
     */
    const val ROOMS = "workspace/{workspaceId}/rooms/{role}"
    const val ACTIVE_ROOM = "workspace/{workspaceId}/rooms/{role}/{$ARG_ROOM_NAME}"

    /**
     * The outbound softphone.
     *
     * ⛔ ONE DESTINATION FOR BOTH THE KEYPAD AND THE LIVE CALL, AND THE ABSENCE OF AN `IN_CALL`
     * ROUTE IS A SAFETY PROPERTY RATHER THAN A SIMPLIFICATION. A call destination reached by
     * navigation is restored from the back stack after process death, and the effect that starts
     * the call runs again — placing a SECOND billable call to the same person, with no user
     * action, to replace one that died with the process. Nothing in a route or a ViewModel can
     * distinguish a restored entry from a fresh one, because both are restored together. Holding
     * the call in the ViewModel's state means a killed process comes back to an idle keypad, which
     * is the truth: the socket died and the call is over. See DialerUiState.
     *
     * ⛔ AND THE ROLE IS A REAL GATE HERE, NOT WORDING. `POST /api/district/calls/dial` excludes
     * `viewer`, so a viewer reaching this destination would meet a 403 on the one thing it does.
     * The Overview hides the entry; the screen states why for anything that arrives another way.
     * It fails CLOSED: an unrecognised value is no dialling.
     */
    const val DIALER = "workspace/{workspaceId}/dialer/{role}"

    /**
     * The automation monitor.
     *
     * ⛔ A TOP-LEVEL WORKSPACE DESTINATION RATHER THAN A `settings/{role}/...` SECTION, AND THE
     * DISTINCTION IS PURPOSE RATHER THAN SUBJECT. Every section under [WORKSPACE_SETTINGS] is a
     * FORM whose save replaces stored configuration, and the hub in front of them excludes
     * `viewer` from every route including the read. This is a MONITOR — three of its four routes
     * admit viewers, nothing on it is authored — so filing it there would have hidden "is the
     * follow-up automation running" behind a gate built for the persona editor, from the role most
     * likely to be asked to check.
     *
     * ⚠️ CARRIES A ROLE, AND IT IS A PARTIAL GATE rather than either of the two established cases.
     * Unlike [ANALYTICS] (no role at all, nothing gated) and unlike [WORKSPACE_SETTINGS] (the whole
     * destination hidden), the SCREEN is reachable by every role and only the per-workflow switch
     * is disabled — the toggle route excludes `viewer` while the list, the runs and the campaign
     * status do not. The role therefore has to survive process death with the destination, or a
     * restored screen would have to guess whether to offer a control that 403s.
     */
    const val WORKFLOWS = "workspace/{workspaceId}/workflows/{role}"

    /**
     * The workspace's booking page.
     *
     * ⛔ IT CARRIES NO ROLE, AND IT IS THE ONLY WORKSPACE-SCOPED DESTINATION HERE WHERE THAT IS A
     * SAFETY PROPERTY RATHER THAN AN ABSENCE. [ANALYTICS] omits one because nothing behind it is
     * gated; this one omits it because the SERVER sends the answer. `GET
     * /api/district/scheduling/status` returns `canManage` on every read — the route's own comment
     * says the client is "told rather than left to re-derive it from a role string" — so a `{role}`
     * segment here would be a second, weaker copy of a fact already in hand. And it would fail the
     * wrong way: [WorkspaceRole.fromWire] fails closed to null, so a corrupted or renamed segment
     * would hide the Enable button from an OWNER the server would have admitted, on the one screen
     * where that button is the whole point.
     *
     * ⛔ A TOP-LEVEL WORKSPACE DESTINATION RATHER THAN A `settings/{role}/…` SECTION, for the
     * reason [WORKFLOWS] is one and then some. Every section under [WORKSPACE_SETTINGS] is a FORM
     * whose save replaces stored configuration, and the hub in front of them excludes `viewer` from
     * every route including the read; the scheduling status route admits `viewer` explicitly. iOS
     * reaches this screen from BOTH its overview and its settings hub and documents the split the
     * same way — the destination is its own, and the hub merely links to it.
     */
    const val SCHEDULING = "workspace/{workspaceId}/scheduling"

    /**
     * District Desk: the tenant's OWN customers' tickets, and the queue's settings.
     *
     * ⛔ THE ROLE IS A REAL GATE AND IT GATES THE **READ**, WHICH IS UNUSUAL ENOUGH TO BE THE ONLY
     * reason these three carry one. Every desk route is `["agency","client"]` and excludes `viewer`
     * — including the ticket LIST — because the payloads carry a customer's name, email address and
     * phone number in the clear plus the correspondence about them, and a viewer seat exists to
     * watch operations, which is different in kind. So this is not the [MARKETPLACE] case (a role
     * that only decides wording) nor the [WORKFLOWS] case (a role that disables one control): the
     * whole destination is refused, the entry is hidden rather than captioned, and the role has to
     * survive process death with the destination so a restored screen can decide that for itself.
     * [WorkspaceRole.fromWire] fails CLOSED, so a corrupted segment shows the refusal.
     *
     * ⛔ [DESK_TICKET] CARRIES THE TICKET'S **UUID**, NOT ITS `T-n` DISPLAY REFERENCE. See
     * [ARG_TICKET_ID]: the reference is a per-workspace counter the server formats for humans and
     * addresses nothing.
     *
     * ⚠️ [DESK_SETTINGS] IS A CHILD OF THE QUEUE'S PATH, so a back press from the form lands on the
     * queue rather than on the overview — the same shape [WORKSPACE_SETTINGS]'s sections use.
     */
    const val DESK = "workspace/{workspaceId}/desk/{role}"
    const val DESK_TICKET = "workspace/{workspaceId}/desk/{role}/ticket/{$ARG_TICKET_ID}"
    const val DESK_SETTINGS = "workspace/{workspaceId}/desk/{role}/settings"

    /**
     * Support: this workspace's own requests **with Distronode**.
     *
     * ⛔ THE MIRROR IMAGE OF [DESK] AND THE TWO MUST NEVER SHARE A PATH OR A LABEL. `desk` is the
     * tenant's customers writing to THEM; `support` is the tenant writing to US. The web sidebar
     * carries the same instruction beside its two entries, because a two-word label cannot hold the
     * distinction on its own.
     *
     * ⛔ THE ROLE GATES THE READ HERE TOO, for the reason stated on [DESK]: all five routes are the
     * administrative pair and support CORRESPONDENCE is not operational status.
     *
     * ⛔ [SUPPORT_REQUEST]'S SEGMENT IS EITHER THE JIRA ISSUE KEY OR OUR OWN ROW id, AND BOTH MUST
     * KEEP RESOLVING. A request we hold but have not filed yet has NO issue key, so a route that
     * accepted only the key would make exactly the requests a customer is most anxious about
     * unreachable. See [ARG_REQUEST_KEY].
     */
    const val SUPPORT = "workspace/{workspaceId}/support/{role}"
    const val SUPPORT_REQUEST = "workspace/{workspaceId}/support/{role}/{$ARG_REQUEST_KEY}"

    /** The section segments [workspaceSettings] appends. Matched to the templates above. */
    const val SECTION_PERSONA = "persona"
    const val SECTION_CAPABILITIES = "capabilities"
    const val SECTION_DIRECTORY = "directory"
    const val SECTION_ROUTING = "routing"
    const val SECTION_KNOWLEDGE = "knowledge"
    const val SECTION_MESSAGING = "messaging"
    const val SECTION_MEMBERS = "members"
    const val SECTION_CALLS = "calls"

    fun callLog(workspaceId: String) = "workspace/${Uri.encode(workspaceId)}/calls"

    /**
     * The hub, or one of its sections.
     *
     * ⛔ ONE BUILDER TAKING A SECTION RATHER THAN THREE FUNCTIONS, AND THE REASON IS A LINT
     * CEILING WORTH RESPECTING RATHER THAN RAISING. detekt's `TooManyFunctions` caps this object
     * at 11, which three separate builders would breach — and the honest reading is that they
     * ARE one route with an optional tail, since the sections are children of the hub's path.
     *
     * ⚠️ A null role becomes the literal "none", which [WorkspaceRole.fromWire] rejects — so a
     * corrupted role fails closed to no privileges, the same as every sibling here.
     */
    fun workspaceSettings(
        workspaceId: String,
        role: WorkspaceRole?,
        section: String? = null,
    ): String {
        val base = "workspace/${Uri.encode(workspaceId)}/settings/" +
            Uri.encode(role?.name?.lowercase() ?: "none")
        return if (section == null) base else "$base/${Uri.encode(section)}"
    }

    /** ⚠️ A null role becomes the literal "none", which [WorkspaceRole.fromWire] rejects. */
    fun billing(workspaceId: String, role: WorkspaceRole?) =
        "workspace/${Uri.encode(workspaceId)}/billing/" +
            Uri.encode(role?.name?.lowercase() ?: "none")

    fun analytics(workspaceId: String) = "workspace/${Uri.encode(workspaceId)}/analytics"

    /** ⚠️ A null role becomes the literal "none", which [WorkspaceRole.fromWire] rejects. */
    fun marketplace(workspaceId: String, role: WorkspaceRole?) =
        "workspace/${Uri.encode(workspaceId)}/numbers/" +
            Uri.encode(role?.name?.lowercase() ?: "none")

    /**
     * ⚠️ The role is encoded into the path so the destination survives process death. A null role
     * becomes the literal "none", which [WorkspaceRole.fromWire] does not recognise and therefore
     * fails closed to no privileges — the safe direction.
     */
    fun inbox(workspaceId: String, role: WorkspaceRole?) =
        "workspace/${Uri.encode(workspaceId)}/inbox/${Uri.encode(role?.name?.lowercase() ?: "none")}"

    /**
     * ⚠️ THE REPLY TARGET TRAVELS AS QUERY PARAMETERS, NOT AS PATH SEGMENTS. The thread selector has
     * to stay one segment (see the ⚠️ on [THREAD]), and these three are genuinely optional: a thread
     * the server marked unsendable has no target at all, and the destination must still open so the
     * history is readable. Query args also survive process death with the rest of the back stack.
     *
     * ⛔ [replyTo] IS AN ADDRESS THE SERVER CHOSE TO EXPOSE, never the thread's `contact:<id>`
     * identity. See `ConversationSummary.replyTarget`.
     */
    fun thread(
        workspaceId: String,
        role: WorkspaceRole?,
        threadKey: String,
        replyTo: String? = null,
        replyChannel: String? = null,
        title: String? = null,
    ): String {
        val base = inbox(workspaceId, role) + "/" + Uri.encode(threadKey)
        val query = buildList {
            if (!replyTo.isNullOrBlank()) add("$ARG_REPLY_TO=${Uri.encode(replyTo)}")
            if (!replyChannel.isNullOrBlank()) add("$ARG_REPLY_CHANNEL=${Uri.encode(replyChannel)}")
            if (!title.isNullOrBlank()) add("$ARG_THREAD_TITLE=${Uri.encode(title)}")
        }
        return if (query.isEmpty()) base else base + "?" + query.joinToString("&")
    }

    fun contacts(workspaceId: String, role: WorkspaceRole?) =
        "workspace/${Uri.encode(workspaceId)}/contacts/${Uri.encode(role?.name?.lowercase() ?: "none")}"

    fun contactDetail(workspaceId: String, contactId: String, role: WorkspaceRole?) =
        contacts(workspaceId, role) + "/" + Uri.encode(contactId)

    /** ⚠️ A null role becomes the literal "none", which [WorkspaceRole.fromWire] rejects. */
    fun hq(workspaceId: String, role: WorkspaceRole?) =
        "workspace/${Uri.encode(workspaceId)}/hq/${Uri.encode(role?.name?.lowercase() ?: "none")}"

    fun callDetail(workspaceId: String, callId: String) =
        "workspace/${Uri.encode(workspaceId)}/calls/${Uri.encode(callId)}"

    /** ⚠️ A null role becomes the literal "none", which [WorkspaceRole.fromWire] rejects. */
    fun rooms(workspaceId: String, role: WorkspaceRole?) =
        "workspace/${Uri.encode(workspaceId)}/rooms/${Uri.encode(role?.name?.lowercase() ?: "none")}"

    /**
     * ⛔ [roomName] IS ENCODED AS ONE SEGMENT. It contains underscores and a workspace id, and it
     * arrives from [com.distronode.districtai.core.model.MeetRoomName] rather than from user text —
     * but a value that could change WHERE a route points is exactly what the encoding in this object
     * exists for, and this one is the value a media token is minted against.
     */
    fun activeRoom(workspaceId: String, role: WorkspaceRole?, roomName: String) =
        rooms(workspaceId, role) + "/" + Uri.encode(roomName)

    /** ⚠️ A null role becomes the literal "none", which [WorkspaceRole.fromWire] rejects. */
    fun dialer(workspaceId: String, role: WorkspaceRole?) =
        "workspace/${Uri.encode(workspaceId)}/dialer/" +
            Uri.encode(role?.name?.lowercase() ?: "none")

    /**
     * ⚠️ A null role becomes the literal "none", which [WorkspaceRole.fromWire] rejects — so a
     * corrupted role fails closed to a screen that reads but cannot toggle, which is exactly the
     * viewer experience and therefore the safe direction. See [WORKFLOWS].
     */
    fun workflows(workspaceId: String, role: WorkspaceRole?) =
        "workspace/${Uri.encode(workspaceId)}/workflows/" +
            Uri.encode(role?.name?.lowercase() ?: "none")

    /**
     * ⚠️ NO ROLE PARAMETER AT ALL, which is deliberate rather than an oversight and is why this is
     * a one-argument builder in an object full of two-argument ones. The server's `canManage`
     * decides whether Enable is drawn; see the ⛔ on [SCHEDULING].
     */
    fun scheduling(workspaceId: String) = "workspace/${Uri.encode(workspaceId)}/scheduling"

    /**
     * The desk queue, one of its tickets, or its settings.
     *
     * ⛔ ONE BUILDER WITH AN OPTIONAL TAIL RATHER THAN THREE, THE SAME CALL [workspaceSettings]
     * MAKES AND FOR THE SAME REASON: detekt caps this object's function count, and the sections
     * genuinely ARE children of the queue's path. The doc block on this object says a SECOND
     * parameterised builder is the signal that the table needs real structure — this is that
     * second one, so treat it as the last.
     *
     * ⚠️ A null role becomes the literal "none", which [WorkspaceRole.fromWire] rejects, so a
     * corrupted role fails closed to the refusal screen rather than to the queue.
     */
    fun desk(workspaceId: String, role: WorkspaceRole?, tail: String? = null): String {
        val base = "workspace/${Uri.encode(workspaceId)}/desk/" +
            Uri.encode(role?.name?.lowercase() ?: "none")
        return if (tail == null) base else "$base/$tail"
    }

    /** ⚠️ The ticket's UUID — see [ARG_TICKET_ID]. */
    fun deskTicket(workspaceId: String, role: WorkspaceRole?, ticketId: String) =
        desk(workspaceId, role, "ticket/${Uri.encode(ticketId)}")

    fun deskSettings(workspaceId: String, role: WorkspaceRole?) =
        desk(workspaceId, role, "settings")

    /** ⚠️ A null role becomes the literal "none", which [WorkspaceRole.fromWire] rejects. */
    fun support(workspaceId: String, role: WorkspaceRole?) =
        "workspace/${Uri.encode(workspaceId)}/support/" +
            Uri.encode(role?.name?.lowercase() ?: "none")

    /**
     * ⛔ [key] IS THE ISSUE KEY **OR** OUR OWN ROW id, AND THE CALLER PICKS. `issueKey` is null
     * while a request is unfiled, so a caller must fall back to the row id rather than passing an
     * empty segment — which would address the LIST route instead and open the wrong screen.
     */
    fun supportRequest(workspaceId: String, role: WorkspaceRole?, key: String) =
        support(workspaceId, role) + "/${Uri.encode(key)}"
}

internal const val ARG_WORKSPACE_ID = "workspaceId"
internal const val ARG_CALL_ID = "callId"
internal const val ARG_CONTACT_ID = "contactId"
internal const val ARG_ROLE = "role"

/**
 * ⚠️ THE DESK TICKET'S UUID, NOT ITS `T-n` DISPLAY REFERENCE. The reference is a per-workspace
 * counter the server formats for humans; only the id addresses the route, and a screen that put the
 * reference here would 404 on every ticket.
 */
internal const val ARG_TICKET_ID = "ticketId"

/**
 * ⚠️ A SUPPORT REQUEST'S ADDRESS, WHICH IS EITHER THE JIRA ISSUE KEY (`DA-42`) OR OUR OWN ROW id.
 * The route resolves both, deliberately — that is what makes a request which has not been filed
 * with Atlassian yet reachable at all.
 */
internal const val ARG_REQUEST_KEY = "requestKey"
internal const val ARG_ROOM_NAME = "roomName"

/**
 * The Inbox thread selector.
 *
 * ⚠️ Carries the server's own `threadKey`, so the prefixes below are its vocabulary rather than this
 * client's invention. Changing them here silently sends the wrong selector and returns an empty
 * thread — which reads as data loss, not as a routing bug.
 */
internal const val ARG_THREAD_KEY = "threadKey"
internal const val THREAD_CONTACT_PREFIX = "contact:"
internal const val THREAD_ADDRESS_PREFIX = "addr:"

/**
 * The resolved reply destination, carried from the Inbox list to the thread.
 *
 * ⚠️ These are read from the CONVERSATION, which is the only place the server publishes a thread's
 * sendable addresses and its `canSms`/`canEmail` flags. The timeline endpoint returns events only,
 * so a thread opened without them cannot re-derive a recipient and correctly offers no reply box.
 */
internal const val ARG_REPLY_TO = "replyTo"
internal const val ARG_REPLY_CHANNEL = "replyChannel"
internal const val ARG_THREAD_TITLE = "title"

/**
 * Which selector the timeline endpoint should be asked with.
 *
 * ⚠️ EXACTLY ONE OF THESE IS NON-NULL FOR A WELL-FORMED KEY, and both are null for a malformed one.
 * That is deliberate: the server takes `contactId` OR `phoneNumber` and answers 400 for neither, so
 * "I could not tell" has to be representable rather than guessed at.
 */
internal data class ThreadSelector(val contactId: String?, val address: String?)

/**
 * Split the server's `threadKey` back into the selector the timeline endpoint wants.
 *
 * ⛔ THE SERVER'S OWN IDENTITY IS PARSED, NEVER RE-DERIVED. `contact:<id>` means the counterpart
 * resolved to a Contact and the id is the precise selector; `addr:<address>` means it did not, and
 * the address IS the identity. Guessing which one a bare string is would send the wrong selector and
 * return an empty thread — which reads to a user as their history being gone, not as a bug.
 *
 * ⛔ EXTRACTED FROM THE `composable(Routes.THREAD)` BLOCK SO IT CAN BE TESTED AT ALL. Inline, this
 * was three lines of string surgery reachable only by rendering the whole navigation graph, and the
 * awkward inputs — an email address containing no prefix, a `+`-prefixed phone number that survived
 * URL encoding, an empty argument after process death — could not be exercised. It is the kind of
 * code whose failure mode is silent and whose cost is a thread that looks empty.
 *
 * ⚠️ Deliberately does NOT reject an unknown prefix by throwing. A future server-side prefix would
 * then crash the app on a thread it merely could not open; both-null degrades to an empty thread with
 * a retry instead.
 */
internal fun parseThreadKey(threadKey: String): ThreadSelector = ThreadSelector(
    contactId = threadKey.removePrefix(THREAD_CONTACT_PREFIX)
        .takeIf { threadKey.startsWith(THREAD_CONTACT_PREFIX) },
    address = threadKey.removePrefix(THREAD_ADDRESS_PREFIX)
        .takeIf { threadKey.startsWith(THREAD_ADDRESS_PREFIX) },
)
