package com.distronode.districtai.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.AppContainer
import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.core.auth.RevokeApi
import com.distronode.districtai.core.auth.RevokeResult
import com.distronode.districtai.core.auth.TokenStore
import com.distronode.districtai.core.data.Overview
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.OverviewMetrics
import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.analytics.ANALYTICS_ROOT_DESCRIPTION
import com.distronode.districtai.ui.billing.BILLING_ROOT_DESCRIPTION
import com.distronode.districtai.ui.calls.CALL_DETAIL_ROOT_DESCRIPTION
import com.distronode.districtai.ui.calls.CALL_LOG_ROOT_DESCRIPTION
import com.distronode.districtai.ui.contacts.CONTACTS_ROOT_DESCRIPTION
import com.distronode.districtai.ui.contacts.CONTACT_DETAIL_ROOT_DESCRIPTION
import com.distronode.districtai.ui.desk.DESK_ROOT_DESCRIPTION
import com.distronode.districtai.ui.desk.DESK_SETTINGS_ROOT_DESCRIPTION
import com.distronode.districtai.ui.desk.DESK_TICKET_ROOT_DESCRIPTION
import com.distronode.districtai.ui.devices.DEVICES_ROOT_DESCRIPTION
import com.distronode.districtai.ui.dialer.DIALER_ROOT_DESCRIPTION
import com.distronode.districtai.ui.hq.HQ_ROOT_DESCRIPTION
import com.distronode.districtai.ui.inbox.INBOX_ROOT_DESCRIPTION
import com.distronode.districtai.ui.inbox.THREAD_ROOT_DESCRIPTION
import com.distronode.districtai.ui.marketplace.MARKETPLACE_ROOT_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_ANALYTICS_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_BILLING_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_CALL_LOG_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_CONTACTS_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_DESK_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_DIALER_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_HQ_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_MARKETPLACE_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_ROOMS_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_SCHEDULING_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_SETTINGS_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_SUPPORT_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_WORKFLOWS_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_OPEN_WORKSPACE_SETTINGS_DESCRIPTION
import com.distronode.districtai.ui.overview.OVERVIEW_ROOT_DESCRIPTION
import com.distronode.districtai.ui.overview.OverviewUiState
import com.distronode.districtai.ui.overview.OverviewViewModel
import com.distronode.districtai.ui.rooms.ROOMS_ROOT_DESCRIPTION
import com.distronode.districtai.ui.scheduling.SCHEDULING_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.SETTINGS_DEVICES_DESCRIPTION
import com.distronode.districtai.ui.settings.SETTINGS_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.SETTINGS_SIGN_OUT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.CALL_HANDLING_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.CAPABILITIES_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.DIRECTORY_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.KNOWLEDGE_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.MEMBERS_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.MESSAGING_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.ROUTING_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_CALLS_ROW_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_DIRECTORY_ROW_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_MEMBERS_ROW_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_MESSAGING_ROW_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_NUMBERS_ROW_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_ROOT_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_ROUTING_ROW_DESCRIPTION
import com.distronode.districtai.ui.support.SUPPORT_REQUEST_ROOT_DESCRIPTION
import com.distronode.districtai.ui.support.SUPPORT_ROOT_DESCRIPTION
import com.distronode.districtai.ui.workflows.WORKFLOWS_ROOT_DESCRIPTION
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The signed-in navigation graph, driven through its real destinations.
 *
 * ⛔ WHAT THIS PROVES THAT THE SCREEN TESTS CANNOT. Every screen is tested on its own with plain
 * callbacks, so nothing there notices a route template whose argument names disagree with the
 * builder that fills them, a tile wired to the wrong destination, or a destination whose
 * ViewModel reads an argument the route never carries. Those failures only exist in
 * `DistrictNavHost`, and this class exercises it: routes built by [Routes] are navigated to,
 * the destination's own screen must draw, and the arguments it parsed are read back off the
 * back stack.
 *
 * ⚠️ A REAL [AppContainer] WITH NO SESSION, AND THAT IS WHAT KEEPS THIS OFF THE NETWORK. Its API
 * client is built from `ApiEnvironment.baseUrl`, so a stored session would refresh against
 * production. With an empty token store the coordinator answers `NoSession` before any request
 * is built, so every destination's load fails locally and the screens draw their signed-out
 * states. The push and revoke seams are stubbed for the same reason `AppContainerTest` stubs
 * them.
 *
 * ⚠️ THE STORE HOLDS EVERY READ UNTIL A TEST RELEASES IT, AND THAT IS WHAT MAKES THIS CLASS'S
 * COVERAGE REPEATABLE. The coordinator reads the store on `Dispatchers.IO`, so with an answering
 * store each destination's `NoSession` result raced the navigation that follows it: whether a
 * screen was ever recomposed from its loading state, and in what order two loads landed, varied
 * from run to run, and so did the covered line and branch counts (measured: tens of branches
 * between two runs of this class alone). Held, every destination is observed in its loading
 * state and nothing else, whatever the thread timing. The two tests that need the signed-out
 * answer release the reads explicitly, after the screen has settled. See [HeldCredentialReads].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DistrictNavHostTest {

    private val heldReads = HeldCredentialReads()

    private val composeRule = createComposeRule()

    /**
     * ⚠️ [heldReads] IS THE OUTER RULE, so the reads it holds are released only after the compose
     * rule has closed the activity, which cancels every destination's ViewModel scope. A read
     * released earlier would resume a load into whatever test happens to be running next.
     */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(heldReads).around(composeRule)

    private lateinit var container: AppContainer
    private lateinit var overviewViewModel: OverviewViewModel
    private lateinit var navController: NavHostController
    private var signIns = 0
    private val messages = mutableListOf<String>()

    private val workspace = WorkspaceEntry(id = "ws-1", name = "Alpha Agency", region = "ca", role = "agency")

    private fun content(role: WorkspaceRole? = WorkspaceRole.AGENCY) = OverviewUiState.Content(
        overview = Overview(
            workspaceId = "ws-1",
            role = role,
            metrics = OverviewMetrics(totalCalls = 12, callsThisWeek = 3, totalContacts = 7, avgDuration = 45),
            avgDurationLabel = "45s",
            recentCalls = emptyList(),
        ),
        workspaces = listOf(workspace),
        active = workspace,
    )

    @Before
    fun setUp() {
        container = AppContainer(
            ApplicationProvider.getApplicationContext(),
            tokenStore = SignedOutTokenStore(heldReads),
            revokeApi = NoRevokeApi(),
            pushApi = FakeInboundPushApi(),
            pushTokenSource = { null },
        )
        overviewViewModel = OverviewViewModel(container.workspaceRepository, container.overviewRepository)
    }

    /**
     * The overview's own load is not part of what this class proves, and it is not in a
     * ViewModelStore that the activity's teardown would clear, so its scope is cancelled here.
     */
    @After
    fun tearDown() {
        overviewViewModel.viewModelScope.cancel()
    }

    /**
     * Let the held reads answer, once the screen under test has settled.
     *
     * The overview's load is cancelled first for the reason [tearDown] gives: left running, it would
     * finish at an arbitrary point relative to the assertions that follow.
     */
    private fun answerReads() {
        composeRule.waitForIdle()
        overviewViewModel.viewModelScope.cancel()
        heldReads.release()
    }

    private fun render(state: OverviewUiState = content()) {
        composeRule.setContent {
            DistrictTheme {
                val controller = rememberNavController()
                navController = controller
                DistrictNavHost(
                    container = container,
                    overviewViewModel = overviewViewModel,
                    overviewState = state,
                    sessionEpoch = 0,
                    onSignIn = { signIns += 1 },
                    onShowMessage = { messages += it },
                    navController = controller,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun navigate(route: String) {
        composeRule.runOnIdle { navController.navigate(route) }
        composeRule.waitForIdle()
    }

    private fun back() {
        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.waitForIdle()
    }

    private fun route(): String? = navController.currentBackStackEntry?.destination?.route

    private fun argument(name: String): String? = navController.currentBackStackEntry?.arguments?.getString(name)

    private fun assertShown(description: String) {
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    /**
     * Tap [description] on the overview, scrolling its list to it first.
     *
     * ⚠️ ALWAYS SCROLLED, NOT ONLY WHEN MISSING. The list is lazy and composes a little beyond the
     * viewport, so a tile can exist in the tree while sitting below the fold, where a click lands
     * on nothing. The account entry is the exception: it is in the app bar, outside the list.
     */
    private fun tapOverviewTile(description: String) {
        if (description != OVERVIEW_OPEN_SETTINGS_DESCRIPTION) {
            composeRule.onNode(hasScrollToNodeAction())
                .performScrollToNode(hasContentDescription(description))
        }
        composeRule.onNodeWithContentDescription(description).performClick()
        composeRule.waitForIdle()
    }

    /**
     * Pump the main looper until [condition] holds.
     *
     * ⚠️ `waitUntil` ADVANCES COMPOSE, NOT THE LOOPER'S OTHER WORK. The container's own scope runs
     * on the main dispatcher and hops to IO in between, so its continuations are ordinary looper
     * messages that only run when the looper is idled.
     */
    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MILLIS)
        }
        assertTrue("condition not reached within $WAIT_MILLIS ms", condition())
    }

    // ── The start destination and the bar ──────────────────────────────────────────────────

    @Test
    fun `the overview is the start destination and the bar appears once a workspace resolves`() {
        render()

        assertEquals(Routes.OVERVIEW, route())
        assertShown(OVERVIEW_ROOT_DESCRIPTION)
        listOf(
            NAV_OVERVIEW_DESCRIPTION,
            NAV_INBOX_DESCRIPTION,
            NAV_CALLS_DESCRIPTION,
            NAV_CONTACTS_DESCRIPTION,
            NAV_ACCOUNT_DESCRIPTION,
        ).forEach(::assertShown)
    }

    @Test
    fun `no bar is drawn before a workspace resolves, because its tabs would have nowhere to go`() {
        render(OverviewUiState.Loading)

        assertEquals(Routes.OVERVIEW, route())
        composeRule.onNodeWithContentDescription(NAV_INBOX_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(NAV_ACCOUNT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the tabs open the active workspace's destinations without growing the back stack`() {
        render()

        composeRule.onNodeWithContentDescription(NAV_INBOX_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(Routes.INBOX, route())
        assertEquals("ws-1", argument(ARG_WORKSPACE_ID))
        assertEquals("agency", argument(ARG_ROLE))
        assertShown(INBOX_ROOT_DESCRIPTION)

        composeRule.onNodeWithContentDescription(NAV_CALLS_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(Routes.CALL_LOG, route())
        assertEquals("ws-1", argument(ARG_WORKSPACE_ID))
        assertShown(CALL_LOG_ROOT_DESCRIPTION)
        // ⛔ A TAB IS NOT A DRILL-DOWN: the inbox tab was popped rather than stacked under the call
        // log, so back from any tab leaves the app instead of walking through the tabs visited.
        assertEquals(Routes.OVERVIEW, navController.previousBackStackEntry?.destination?.route)

        composeRule.onNodeWithContentDescription(NAV_CONTACTS_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(Routes.CONTACTS, route())
        assertEquals("agency", argument(ARG_ROLE))
        assertShown(CONTACTS_ROOT_DESCRIPTION)

        composeRule.onNodeWithContentDescription(NAV_ACCOUNT_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(Routes.SETTINGS, route())
        assertShown(SETTINGS_ROOT_DESCRIPTION)
        assertEquals(Routes.OVERVIEW, navController.previousBackStackEntry?.destination?.route)

        composeRule.onNodeWithContentDescription(NAV_OVERVIEW_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(Routes.OVERVIEW, route())
        assertShown(OVERVIEW_ROOT_DESCRIPTION)
    }

    // ── The overview's tiles ───────────────────────────────────────────────────────────────

    @Test
    fun `every overview tile opens its own destination for the active workspace`() {
        render()

        val tiles = listOf(
            OVERVIEW_OPEN_CALL_LOG_DESCRIPTION to (Routes.CALL_LOG to CALL_LOG_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_CONTACTS_DESCRIPTION to (Routes.CONTACTS to CONTACTS_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_HQ_DESCRIPTION to (Routes.HQ to HQ_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_ANALYTICS_DESCRIPTION to (Routes.ANALYTICS to ANALYTICS_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_MARKETPLACE_DESCRIPTION to (Routes.MARKETPLACE to MARKETPLACE_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_BILLING_DESCRIPTION to (Routes.BILLING to BILLING_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_ROOMS_DESCRIPTION to (Routes.ROOMS to ROOMS_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_WORKFLOWS_DESCRIPTION to (Routes.WORKFLOWS to WORKFLOWS_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_SCHEDULING_DESCRIPTION to (Routes.SCHEDULING to SCHEDULING_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_DIALER_DESCRIPTION to (Routes.DIALER to DIALER_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_DESK_DESCRIPTION to (Routes.DESK to DESK_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_SUPPORT_DESCRIPTION to (Routes.SUPPORT to SUPPORT_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_WORKSPACE_SETTINGS_DESCRIPTION to
                (Routes.WORKSPACE_SETTINGS to WORKSPACE_SETTINGS_ROOT_DESCRIPTION),
            OVERVIEW_OPEN_SETTINGS_DESCRIPTION to (Routes.SETTINGS to SETTINGS_ROOT_DESCRIPTION),
        )

        tiles.forEach { (tile, expected) ->
            val (template, root) = expected
            tapOverviewTile(tile)

            assertEquals(tile, template, route())
            assertShown(root)
            if (template.startsWith("workspace/")) {
                assertEquals("$tile must carry the ACTIVE workspace", "ws-1", argument(ARG_WORKSPACE_ID))
            }
            // ⚠️ Every role-carrying route gets the overview's effective role, never the list's.
            if ("{role}" in template) assertEquals(tile, "agency", argument(ARG_ROLE))

            back()
            assertEquals("back from $tile returns to the overview", Routes.OVERVIEW, route())
        }
    }

    // ── Deeper destinations ────────────────────────────────────────────────────────────────

    @Test
    fun `every drill-down route resolves to its own screen with the ids it was given`() {
        render()
        val role = WorkspaceRole.AGENCY

        data class Case(val route: String, val template: String, val root: String, val args: Map<String, String>)

        listOf(
            Case(
                Routes.callDetail("ws-1", "call-9"),
                Routes.CALL_DETAIL,
                CALL_DETAIL_ROOT_DESCRIPTION,
                mapOf(ARG_WORKSPACE_ID to "ws-1", ARG_CALL_ID to "call-9"),
            ),
            Case(
                Routes.contactDetail("ws-1", "ct-4", role),
                Routes.CONTACT_DETAIL,
                CONTACT_DETAIL_ROOT_DESCRIPTION,
                mapOf(ARG_WORKSPACE_ID to "ws-1", ARG_CONTACT_ID to "ct-4", ARG_ROLE to "agency"),
            ),
            Case(
                Routes.deskTicket("ws-1", role, "ticket-uuid-1"),
                Routes.DESK_TICKET,
                DESK_TICKET_ROOT_DESCRIPTION,
                mapOf(ARG_WORKSPACE_ID to "ws-1", ARG_TICKET_ID to "ticket-uuid-1"),
            ),
            Case(
                Routes.deskSettings("ws-1", role),
                Routes.DESK_SETTINGS,
                DESK_SETTINGS_ROOT_DESCRIPTION,
                mapOf(ARG_WORKSPACE_ID to "ws-1", ARG_ROLE to "agency"),
            ),
            Case(
                Routes.supportRequest("ws-1", role, "DA-42"),
                Routes.SUPPORT_REQUEST,
                SUPPORT_REQUEST_ROOT_DESCRIPTION,
                mapOf(ARG_WORKSPACE_ID to "ws-1", ARG_REQUEST_KEY to "DA-42"),
            ),
            Case(Routes.DEVICES, Routes.DEVICES, DEVICES_ROOT_DESCRIPTION, emptyMap()),
        ).forEach { case ->
            navigate(case.route)

            assertEquals(case.route, case.template, route())
            assertShown(case.root)
            case.args.forEach { (name, value) -> assertEquals("${case.route} $name", value, argument(name)) }
        }
    }

    @Test
    fun `an id with a slash stays one argument`() {
        // ⛔ THE BUILDERS ENCODE, SO A SLASH CANNOT ADDRESS ANOTHER DESTINATION. The decoded value
        // arrives back intact on the destination that was asked for.
        render()

        navigate(Routes.callDetail("ws-1", "a/b"))

        assertEquals(Routes.CALL_DETAIL, route())
        assertEquals("a/b", argument(ARG_CALL_ID))
    }

    @Test
    fun `a thread carries its reply target and title as optional query arguments`() {
        render()

        navigate(
            Routes.thread(
                workspaceId = "ws-1",
                role = WorkspaceRole.CLIENT,
                threadKey = "contact:ct-4",
                replyTo = "+14165550100",
                replyChannel = "sms",
                title = "Ada Lovelace",
            ),
        )

        assertEquals(Routes.THREAD, route())
        assertShown(THREAD_ROOT_DESCRIPTION)
        assertEquals("contact:ct-4", argument(ARG_THREAD_KEY))
        assertEquals("+14165550100", argument(ARG_REPLY_TO))
        assertEquals("sms", argument(ARG_REPLY_CHANNEL))
        assertEquals("client", argument(ARG_ROLE))
        // The conversation's display name is the title, rather than the generic section name.
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
    }

    @Test
    fun `a thread opened without a title is titled by its address`() {
        // ⚠️ A thread with no Contact row is keyed by its ADDRESS, and that address is a better
        // title than the section name when the list did not pass one.
        render()

        navigate(Routes.thread(workspaceId = "ws-1", role = WorkspaceRole.CLIENT, threadKey = "addr:+14165550199"))

        assertEquals(Routes.THREAD, route())
        assertNull(argument(ARG_THREAD_TITLE))
        composeRule.onNodeWithText("+14165550199").assertIsDisplayed()
    }

    // ── Workspace settings ─────────────────────────────────────────────────────────────────

    @Test
    fun `the workspace settings hub opens each form under its own path`() {
        render()
        navigate(Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY))
        assertShown(WORKSPACE_SETTINGS_ROOT_DESCRIPTION)

        listOf(
            WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION to
                (Routes.WORKSPACE_SETTINGS_CAPABILITIES to CAPABILITIES_ROOT_DESCRIPTION),
            WORKSPACE_SETTINGS_DIRECTORY_ROW_DESCRIPTION to
                (Routes.WORKSPACE_SETTINGS_DIRECTORY to DIRECTORY_ROOT_DESCRIPTION),
            WORKSPACE_SETTINGS_ROUTING_ROW_DESCRIPTION to
                (Routes.WORKSPACE_SETTINGS_ROUTING to ROUTING_ROOT_DESCRIPTION),
            WORKSPACE_SETTINGS_CALLS_ROW_DESCRIPTION to
                (Routes.WORKSPACE_SETTINGS_CALLS to CALL_HANDLING_ROOT_DESCRIPTION),
            WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION to
                (Routes.WORKSPACE_SETTINGS_KNOWLEDGE to KNOWLEDGE_ROOT_DESCRIPTION),
            WORKSPACE_SETTINGS_MESSAGING_ROW_DESCRIPTION to
                (Routes.WORKSPACE_SETTINGS_MESSAGING to MESSAGING_ROOT_DESCRIPTION),
            WORKSPACE_SETTINGS_MEMBERS_ROW_DESCRIPTION to
                (Routes.WORKSPACE_SETTINGS_MEMBERS to MEMBERS_ROOT_DESCRIPTION),
            // ⚠️ The numbers row opens the EXISTING marketplace destination, not a second view.
            WORKSPACE_SETTINGS_NUMBERS_ROW_DESCRIPTION to (Routes.MARKETPLACE to MARKETPLACE_ROOT_DESCRIPTION),
        ).forEach { (row, expected) ->
            val (template, root) = expected
            // ⚠️ The hub scrolls, and the lower rows start below the fold on a phone-sized window.
            composeRule.onNodeWithContentDescription(row).performScrollTo().performClick()
            composeRule.waitForIdle()

            assertEquals(row, template, route())
            assertShown(root)
            assertEquals(row, "ws-1", argument(ARG_WORKSPACE_ID))
            assertEquals(row, "agency", argument(ARG_ROLE))

            back()
            assertEquals("back from $row lands on the hub", Routes.WORKSPACE_SETTINGS, route())
        }
    }

    @Test
    fun `a viewer's hub offers only the rows its role can use`() {
        // ⛔ THE ROLE SEGMENT IS A REAL GATE ON THE HUB: a viewer reaches it, and the persona,
        // capability, directory, routing and member rows are simply not there.
        render()
        navigate(Routes.workspaceSettings("ws-1", WorkspaceRole.VIEWER))

        assertShown(WORKSPACE_SETTINGS_ROOT_DESCRIPTION)
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_MEMBERS_ROW_DESCRIPTION).assertDoesNotExist()
    }

    // ── The account screen ─────────────────────────────────────────────────────────────────

    @Test
    fun `the account screen opens the device list and back returns to it`() {
        render()
        navigate(Routes.SETTINGS)

        composeRule.onNodeWithContentDescription(SETTINGS_DEVICES_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        assertEquals(Routes.DEVICES, route())
        assertShown(DEVICES_ROOT_DESCRIPTION)

        back()
        assertEquals(Routes.SETTINGS, route())
    }

    @Test
    fun `signing out from the account screen ends the session through the container`() {
        // ⛔ THE ROW IS WIRED TO `AppContainer.signOut`, whose last step advances the session
        // epoch. That advance is what swaps the whole graph for the sign-in screen.
        render()
        navigate(Routes.SETTINGS)
        answerReads()
        val before = container.sessionSignal.epoch.value

        composeRule.onNodeWithContentDescription(SETTINGS_SIGN_OUT_DESCRIPTION).performClick()

        awaitMain { container.sessionSignal.epoch.value > before }
        assertEquals(before + 1, container.sessionSignal.epoch.value)
    }

    // ── The signed-out states reach the host ───────────────────────────────────────────────

    @Test
    fun `a signed-out call log offers sign-in, and the tap reaches the host`() {
        // ⛔ THE CALL LOG IS PAGED, AND ITS SIGN-IN BUTTON IS THE ONLY WAY OUT OF A TERMINAL PAGING
        // ERROR. With no session the first page fails as `Unauthorized(NoSession)`, and the
        // screen must hand the tap to the host rather than swallow it.
        render()
        navigate(Routes.callLog("ws-1"))
        answerReads()

        val signIn = "Sign in"
        composeRule.waitUntil(timeoutMillis = WAIT_MILLIS) {
            composeRule.onAllNodes(hasText(signIn)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(signIn).performClick()

        assertEquals(1, signIns)
        assertTrue("nothing else should have been reported: $messages", messages.isEmpty())
    }

    private companion object {
        /** Loads run on the IO dispatcher, so their results reach the screen after the main looper idles. */
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}

/**
 * A credential store with nothing in it.
 *
 * ⛔ EMPTY IS THE WHOLE POINT. The container's API client is built against production, and an empty
 * store is what makes every request fail as `NoSession` before a socket is opened.
 *
 * ⚠️ Its reads wait on [reads]; see the class doc of [DistrictNavHostTest] for why.
 */
private class SignedOutTokenStore(private val reads: HeldCredentialReads) : TokenStore {
    override fun read(): PersistedSession? {
        reads.await()
        return null
    }

    override fun write(session: PersistedSession) = Unit
    override fun clear() = Unit
    override fun pendingRefreshToken(): String? = null
    override fun markRefreshPending(refreshToken: String) = Unit
    override fun clearRefreshPending() = Unit
    override fun pendingRevokeToken(): String? = null
    override fun markRevokePending(refreshToken: String) = Unit
    override fun clearRevokePending() = Unit
}

/**
 * Holds every credential read until [release], then lets them all through.
 *
 * The reads happen on `Dispatchers.IO` threads, which block here rather than suspending: that is
 * what keeps the coordinator's `NoSession` answer from reaching a screen at a moment decided by
 * thread scheduling. A test holds at most a few dozen reads (one per load it navigates through),
 * well inside the IO pool, and [after] releases whatever is still held once the test is over.
 */
private class HeldCredentialReads : ExternalResource() {
    private val lock = Object()
    private var held = true

    fun await() {
        synchronized(lock) {
            while (held) lock.wait()
        }
    }

    fun release() {
        synchronized(lock) {
            held = false
            lock.notifyAll()
        }
    }

    override fun after() = release()
}

/** Nothing to revoke with an empty store; answered locally so sign-out never reaches the network. */
private class NoRevokeApi : RevokeApi {
    override suspend fun revoke(refreshToken: String): RevokeResult = RevokeResult.Done
}
