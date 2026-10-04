package com.distronode.districtai.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.distronode.districtai.AppContainer
import com.distronode.districtai.AppContainerSeams
import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.core.auth.RevokeApi
import com.distronode.districtai.core.auth.RevokeResult
import com.distronode.districtai.core.auth.TokenStore
import com.distronode.districtai.core.data.Overview
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.network.CallHandlingApi
import com.distronode.districtai.core.model.MarkReadRequest
import com.distronode.districtai.core.model.OverviewMetrics
import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.dialer.FakeTelecomBridge
import com.distronode.districtai.ui.overview.OverviewUiState
import com.distronode.districtai.ui.overview.OverviewViewModel
import com.distronode.districtai.ui.rooms.FakeCallEngine
import kotlinx.coroutines.cancel
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakePersonaApi
import com.distronode.districtai.core.network.testing.FakeCallHandlingApi
import com.distronode.districtai.core.network.testing.FakeInboxExtrasApi
import com.distronode.districtai.core.network.testing.FakeDeskApi
import com.distronode.districtai.core.network.testing.FakeSupportApi

/**
 * [DistrictNavHost] over a container whose every dependency answers from a fake, on the main looper.
 *
 * ⛔ WHAT THIS ADDS TO [DistrictNavHostTest]. That class keeps the real network stack and holds every
 * credential read, so each destination is only ever seen LOADING. What the graph does with an
 * answer (a row tapped, a submitted ticket confirmed, a room
 * joined) needs the destination to have data, and that needs the network faked.
 *
 * ⚠️ DETERMINISTIC BECAUSE NOTHING HERE HOPS A THREAD. The fakes answer synchronously on the
 * ViewModel's own scope, which runs on the main looper, so a result is on screen after the next
 * `waitForIdle` and at no other moment. The two exceptions are the paged lists (the call log and
 * contacts page on `Dispatchers.IO`) and the attachment reader; tests reaching those wait for the
 * row they need before acting on it.
 *
 * ⚠️ THE ACTIVITY-RESULT REGISTRY AND THE CONTEXT ARE REPLACED FOR THE SAME REASON. A permission
 * request or a photo picker would otherwise wait on a system UI Robolectric never draws, and an
 * `ACTION_VIEW` would go to Robolectric's activity stack; here each answers at once and is recorded.
 */
internal class NavHostHarness(private val composeRule: ComposeContentTestRule) {

    val api = CountingDistrictApi()
    val desk = FakeDeskApi()
    val support = FakeSupportApi()
    val persona = FakePersonaApi()
    val search = FakeInboxExtrasApi()
    val callHandling = CountingCallHandlingApi()
    val telecom = FakeTelecomBridge()
    val registry = ScriptedResultRegistry()

    /** Every engine a destination asked for, in order. */
    val engines = mutableListOf<FakeCallEngine>()

    /** Every activity this graph started, through any context it holds. */
    val started = mutableListOf<Intent>()

    /** When set, the next activity start throws this instead of being recorded. */
    var startFailure: RuntimeException? = null

    val messages = mutableListOf<String>()
    var signIns = 0

    val container = AppContainer(
        ApplicationProvider.getApplicationContext(),
        seams = AppContainerSeams(
            tokenStore = EmptyTokenStore(),
            revokeApi = DoneRevokeApi(),
            pushTokenSource = { null },
            districtApi = api,
            deskApi = desk,
            supportApi = support,
            personaApi = persona,
            inboxExtrasApi = search,
            callHandlingApi = callHandling,
            callEngineFactory = CallEngineFactory { FakeCallEngine().also { engines += it } },
            telecomBridge = telecom,
        ),
    )

    val overviewViewModel = OverviewViewModel(container.workspaceRepository, container.overviewRepository)

    var overviewState: OverviewUiState by mutableStateOf(content())
    var sessionEpoch: Int by mutableIntStateOf(0)

    lateinit var navController: NavHostController

    /**
     * @param ownController let the host build its own controller, as the activity does.
     * @param forwarded call the host from a composable that takes the state and the epoch as its own
     *   parameters, as the activity's content does, so the host is handed the caller's own
     *   changed flags rather than the "uncertain" a lambda passes.
     */
    fun render(ownController: Boolean = false, forwarded: Boolean = false) {
        composeRule.setContent {
            val base = LocalContext.current
            val context = remember { LaunchRecordingContext(base, this) }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides registry.owner,
            ) {
                DistrictTheme {
                    if (forwarded) {
                        val controller = rememberNavController()
                        navController = controller
                        ForwardingNavHost(
                            container = container,
                            overviewViewModel = overviewViewModel,
                            overviewState = overviewState as OverviewUiState.Content,
                            sessionEpoch = sessionEpoch,
                            controller = controller,
                            onShowMessage = { messages += it },
                        )
                    } else if (ownController) {
                        DistrictNavHost(
                            container = container,
                            overviewViewModel = overviewViewModel,
                            overviewState = overviewState,
                            sessionEpoch = sessionEpoch,
                            onSignIn = { signIns += 1 },
                            onShowMessage = { messages += it },
                        )
                    } else {
                        val controller = rememberNavController()
                        navController = controller
                        DistrictNavHost(
                            container = container,
                            overviewViewModel = overviewViewModel,
                            overviewState = overviewState,
                            sessionEpoch = sessionEpoch,
                            onSignIn = { signIns += 1 },
                            onShowMessage = { messages += it },
                            navController = controller,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    fun navigate(route: String) {
        composeRule.runOnIdle { navController.navigate(route) }
        composeRule.waitForIdle()
    }

    fun route(): String? = navController.currentBackStackEntry?.destination?.route

    fun argument(name: String): String? = navController.currentBackStackEntry?.arguments?.getString(name)

    /** Advance the session epoch, as a completed login does, and let the destination react. */
    fun bumpEpoch() {
        composeRule.runOnIdle { sessionEpoch += 1 }
        composeRule.waitForIdle()
    }

    fun tap(description: String) {
        composeRule.onNodeWithContentDescription(description).scrolledIntoView().performClick()
        composeRule.waitForIdle()
    }

    fun tapText(text: String) {
        composeRule.onNode(hasText(text)).scrolledIntoView().performClick()
        composeRule.waitForIdle()
    }

    fun back() {
        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.waitForIdle()
    }

    fun awaitDescription(description: String) {
        runCatching {
            awaitMain { composeRule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }
        }.onFailure {
            throw AssertionError(
                "$description never appeared on ${route()}:\n" + composeRule.onRoot().printToString(),
                it,
            )
        }
    }

    fun awaitText(text: String) {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Pump the main looper until [condition] holds, for work that hops to `Dispatchers.IO` and back.
     *
     * ⚠️ `waitUntil` ADVANCES COMPOSE, NOT THE LOOPER'S OTHER WORK; a continuation posted back to the
     * main thread from IO is an ordinary looper message and runs only when the looper is idled.
     */
    fun awaitMain(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            // ⚠️ AND A FRAME: a paged list collects inside composition, on the frame clock.
            composeRule.mainClock.advanceTimeByFrame()
            Thread.yield()
        }
        check(condition()) { "condition not reached within $WAIT_MILLIS ms" }
        composeRule.waitForIdle()
    }

    /** Make `http://` resolve to a browser package, so the Custom Tabs hand-off has somewhere to go. */
    fun installBrowser() {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = BROWSER_PACKAGE
                name = "$BROWSER_PACKAGE.Main"
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addResolveInfoForIntent(probe, info)
    }

    /** Stop the overview's own load, which no test here is about. */
    fun close() {
        overviewViewModel.viewModelScope.cancel()
    }

    companion object {
        const val WAIT_MILLIS = 5_000L
        const val BROWSER_PACKAGE = "com.example.browser"

        val workspace = WorkspaceEntry(id = "ws-1", name = "Alpha Agency", region = "ca", role = "agency")

        fun content(role: WorkspaceRole? = WorkspaceRole.AGENCY, showFinishSetup: Boolean = false) =
            OverviewUiState.Content(
                overview = Overview(
                    workspaceId = "ws-1",
                    role = role,
                    metrics = OverviewMetrics(totalCalls = 12, callsThisWeek = 3, totalContacts = 7, avgDuration = 45),
                    avgDurationLabel = "45s",
                    recentCalls = emptyList(),
                ),
                workspaces = listOf(workspace),
                active = workspace,
                showFinishSetup = showFinishSetup,
            )
    }
}

/**
 * A caller that hands its own parameters on, the shape of the activity's content lambda.
 *
 * ⚠️ THE STATE IS TYPED AS THE CONCRETE `Content`, which Compose knows to be unstable (it holds
 * lists), so the host is told so in its changed flags and compares the state by instance. A lambda
 * caller passing the interface type never tells it that.
 */
@Composable
private fun ForwardingNavHost(
    container: AppContainer,
    overviewViewModel: OverviewViewModel,
    overviewState: OverviewUiState.Content,
    sessionEpoch: Int,
    controller: NavHostController,
    onShowMessage: (String) -> Unit,
) {
    DistrictNavHost(
        container = container,
        overviewViewModel = overviewViewModel,
        overviewState = overviewState,
        sessionEpoch = sessionEpoch,
        onSignIn = {},
        onShowMessage = onShowMessage,
        navController = controller,
    )
}

/**
 * Scroll the node into view when it sits in a scrolling container; a node that does not is already
 * wherever it will ever be, and `performScrollTo` refuses it.
 */
internal fun SemanticsNodeInteraction.scrolledIntoView(): SemanticsNodeInteraction = apply {
    runCatching { performScrollTo() }
}

/**
 * The composition's context, recording every activity start instead of performing it.
 *
 * ⚠️ IT IS ALSO ITS OWN APPLICATION CONTEXT, because [DistrictNavHost] deliberately launches from
 * the application context; a wrapper that handed back the real one would let those starts escape.
 */
private class LaunchRecordingContext(
    base: Context,
    private val harness: NavHostHarness,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun startActivity(intent: Intent) = record(intent)

    override fun startActivity(intent: Intent, options: Bundle?) = record(intent)

    private fun record(intent: Intent) {
        harness.startFailure?.let { throw it }
        harness.started += intent
    }
}

/**
 * An activity-result registry that answers every launch at once.
 *
 * ⚠️ WHY NOT THE ACTIVITY'S OWN: a permission request would open a system dialog Robolectric never
 * answers, and a photo picker would wait on an app that does not exist. Answering synchronously is
 * also what keeps the callback from racing the next assertion.
 */
internal class ScriptedResultRegistry : ActivityResultRegistry() {

    /** Every input a launcher was started with, in order. */
    val launched = mutableListOf<Any?>()

    var permissionGranted = true

    /** What the photo picker returns; null is the user backing out. */
    var pickedUri: Uri? = null

    val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this@ScriptedResultRegistry
    }

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        launched += input
        val result: Any? = when (contract) {
            is ActivityResultContracts.RequestPermission -> permissionGranted
            is ActivityResultContracts.RequestMultiplePermissions ->
                (input as Array<*>).associate { it as String to permissionGranted }
            is ActivityResultContracts.PickVisualMedia -> pickedUri
            else -> throw ActivityNotFoundException("no scripted answer for $contract")
        }
        dispatchResult(requestCode, result)
    }
}

/** A credential store with nothing in it. No fake API reads it; sign-out and the revoke drain do. */
private class EmptyTokenStore : TokenStore {
    override fun read(): PersistedSession? = null
    override fun write(session: PersistedSession) = Unit
    override fun clear() = Unit
    override fun pendingRefreshToken(): String? = null
    override fun markRefreshPending(refreshToken: String) = Unit
    override fun clearRefreshPending() = Unit
    override fun pendingRevokeToken(): String? = null
    override fun markRevokePending(refreshToken: String) = Unit
    override fun clearRevokePending() = Unit
}

/** Nothing to revoke with an empty store; answered locally so sign-out never reaches the network. */
private class DoneRevokeApi : RevokeApi {
    override suspend fun revoke(refreshToken: String): RevokeResult = RevokeResult.Done
}

/** A [FakeDistrictApi] that also counts workspace-list reads, the first call of every overview load. */
internal class CountingDistrictApi : FakeDistrictApi() {
    var workspaceListReads = 0
        private set

    override suspend fun workspaceList() = super.workspaceList().also { workspaceListReads += 1 }

    /** Every mark-read the Inbox sent, so "opening a thread records the read" is assertable. */
    val markReads = mutableListOf<MarkReadRequest>()

    override suspend fun markRead(request: MarkReadRequest) = super.markRead(request).also { markReads += request }

    var callsReads = 0
        private set

    override suspend fun calls(workspaceId: String, limit: Int, offset: Int) =
        super.calls(workspaceId, limit, offset).also { callsReads += 1 }

    var contactsReads = 0
        private set

    override suspend fun contacts(workspaceId: String, limit: Int, offset: Int) =
        super.contacts(workspaceId, limit, offset).also { contactsReads += 1 }

    var conversationsReads = 0
        private set

    override suspend fun conversations(workspaceId: String) =
        super.conversations(workspaceId).also { conversationsReads += 1 }
}

/** A [FakeCallHandlingApi] that also counts the mode read, which is what a reload repeats. */
internal class CountingCallHandlingApi(
    private val delegate: FakeCallHandlingApi = FakeCallHandlingApi(),
) : CallHandlingApi by delegate {
    var handlingReads = 0
        private set

    override suspend fun callHandling(workspaceId: String) =
        delegate.callHandling(workspaceId).also { handlingReads += 1 }
}

/**
 * Run whatever the main looper still holds once the activity has closed.
 *
 * ⛔ WITHOUT THIS, ONE TEST CAN STALL EVERY PAGED LIST IN THE NEXT. Measured: after a thread test
 * that typed and sent a reply, the NEXT class's call log sat on its loading skeleton for the whole
 * wait, although the fake had already answered its one fetch and every IO worker was idle. Draining
 * the main looper after the compose rule's own teardown cured it. The likely mechanism is
 * `AndroidUiDispatcher.Main`, the process-wide dispatcher Paging's presenter delivers on: it posts
 * one handler message and sets a flag until that message runs, and a message still queued when
 * Robolectric clears the looper between tests would leave the flag set for good. So this is the
 * OUTER rule.
 */
internal class MainLooperDrain : ExternalResource() {
    override fun after() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
