package com.distronode.districtai.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.AppContainer
import com.distronode.districtai.AppContainerSeams
import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.core.auth.RevokeApi
import com.distronode.districtai.core.auth.RevokeResult
import com.distronode.districtai.core.auth.TokenStore
import com.distronode.districtai.core.designsystem.DistrictLabels
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.*
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApiClient
import com.distronode.districtai.core.network.SchedulingAdminApi
import com.distronode.districtai.core.network.SchedulingAdminEnvelope
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.FakeAccountApi
import com.distronode.districtai.core.network.testing.FakeCallHandlingApi
import com.distronode.districtai.core.network.testing.FakeCarrierAccountApi
import com.distronode.districtai.core.network.testing.FakeContactBlockingApi
import com.distronode.districtai.core.network.testing.FakeDeskApi
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeInboxExtrasApi
import com.distronode.districtai.core.network.testing.FakeNumberProvisioningApi
import com.distronode.districtai.core.network.testing.FakePersonaApi
import com.distronode.districtai.core.network.testing.FakeSupportApi
import com.distronode.districtai.core.network.testing.FakeTelemetryApi
import com.distronode.districtai.core.network.testing.FakeTransport
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import com.distronode.districtai.core.network.live.LiveDependencies
import com.distronode.districtai.ui.dialer.FakeTelecomBridge
import com.distronode.districtai.ui.overview.OverviewUiState
import com.distronode.districtai.ui.overview.OverviewViewModel
import com.distronode.districtai.ui.rooms.FakeCallEngine
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminRoutes
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual and style QA walk (the release gate between QA builds and store submission).
 *
 * Renders every main route, the dialogs reachable by one tap, and the empty and error states, in
 * one configuration per subclass (language x light/dark x font scale), and writes each frame plus
 * a text report (overflow, ellipsis and mid-word break flags per text node) under VQA_OUT.
 *
 * Opt-in: it runs only when VQA_OUT is set, so the ordinary unit-test run skips it.
 * Fixtures: VQA_CONTRACTS_FR (a fictional Quebec overlay) for French, the committed contracts otherwise.
 */
abstract class VisualQaWalkBase(
    private val config: String,
    private val dark: Boolean,
    private val fontScale: Float,
    private val french: Boolean,
) {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private val outRoot = System.getenv("VQA_OUT")
    private val out by lazy { File(outRoot!!, config).apply { mkdirs() } }
    private val report = StringBuilder()

    private val json = DistrictApiClient.DEFAULT_JSON
    private val contracts = File(
        (if (french) System.getenv("VQA_CONTRACTS_FR") else null)
            ?: checkNotNull(System.getProperty("district.contracts.dir")),
    )
    private fun text(name: String) = File(contracts, name).readText()

    private fun <T> fx(ser: KSerializer<T>, name: String, fallback: ApiResult<T>): ApiResult<T> =
        runCatching { ApiResult.Success(json.decodeFromString(ser, text(name))) as ApiResult<T> }
            .getOrElse {
                report.append("FIXTURE\t$name\t${it.javaClass.simpleName}: ${it.message?.take(200)}\n")
                fallback
            }

    private inline fun <reified T> fx(name: String, fallback: ApiResult<T>): ApiResult<T> =
        fx(serializer<T>(), name, fallback)

    private inner class FixtureSchedulingAdminApi : SchedulingAdminApi {
        private val files = mapOf(
            "me.get" to "district-scheduling-me.json",
            "eventTypes.list" to "district-scheduling-event-types.json",
            "eventTypes.get" to "district-scheduling-event-type.json",
            "eventTypes.hosts.get" to "district-scheduling-hosts.json",
            "eventTypes.slots" to "district-scheduling-slots.json",
            "availability.rules.list" to "district-scheduling-rules.json",
            "bookings.list" to "district-scheduling-bookings.json",
            "bookings.answers" to "district-scheduling-booking-answers.json",
            "calendar.status" to "district-scheduling-calendar-status.json",
            "zoom.status" to "district-scheduling-zoom-status.json",
            "users.list" to "district-scheduling-users.json",
            "users.upcomingBookings" to "district-scheduling-user-upcoming.json",
            "teams.list" to "district-scheduling-teams.json",
            "teams.get" to "district-scheduling-team.json",
            "settings.branding.get" to "district-scheduling-branding.json",
            "settings.llm.get" to "district-scheduling-llm.json",
            "apiKeys.list" to "district-scheduling-api-keys.json",
            "oauth.connections.list" to "district-scheduling-oauth-connections.json",
            "webhooks.list" to "district-scheduling-webhooks.json",
            "webhooks.deliveries" to "district-scheduling-webhook-deliveries.json",
        )

        override suspend fun <T> performSchedulingOp(
            workspaceId: String,
            op: SchedulingAdminOp,
            params: JsonObject,
            serializer: KSerializer<T>,
        ): ApiResult<SchedulingAdminEnvelope<T>> {
            val file = files[op.wire]
                ?: return ApiResult.NetworkFailure(java.io.IOException("no fixture for ${op.wire}"))
            return runCatching {
                val body = json.parseToJsonElement(text(file)).jsonObject
                val data: JsonElement = checkNotNull(body["data"])
                ApiResult.Success(SchedulingAdminEnvelope.Data(json.decodeFromJsonElement(serializer, data)))
                    as ApiResult<SchedulingAdminEnvelope<T>>
            }.getOrElse {
                report.append("SCHED\t${op.wire}\t${it.message?.take(200)}\n")
                ApiResult.NetworkFailure(java.io.IOException("decode ${op.wire}"))
            }
        }

        override suspend fun uploadSchedulingImage(
            workspaceId: String,
            target: com.distronode.districtai.core.network.SchedulingAdminUploadTarget,
            fileName: String,
            mimeType: String,
            bytes: ByteArray,
        ): ApiResult<SchedulingAdminEnvelope<SchedulingUploadResult>> =
            ApiResult.NetworkFailure(java.io.IOException("no upload"))
    }

    private val api = FakeDistrictApi().apply {
        workspaceListResult = fx("district-workspace-list.json", workspaceListResult)
        overviewResult = fx("district-overview.json", overviewResult)
        callsResult = fx(ListSerializer(CallSummary.serializer()), "district-calls.json", callsResult)
        detailResult = fx("district-call-detail.json", detailResult)
        transcriptResult = fx("district-call-transcript.json", transcriptResult)
        contactsResult = fx("district-contacts.json", contactsResult)
        contactResult = fx("district-contact-detail.json", contactResult)
        conversationsResult = fx("district-conversations.json", conversationsResult)
        timelineResult = fx("district-timeline.json", timelineResult)
        unreadCountResult = fx("district-messages-unread-count.json", unreadCountResult)
        draftsResult = fx("district-drafts-list.json", draftsResult)
        analyticsResult = fx("district-analytics.json", analyticsResult)
        usageResult = fx("district-usage.json", usageResult)
        usageHistoryResult = fx("district-usage-history.json", usageHistoryResult)
        searchResult = fx("district-numbers-search.json", searchResult)
        ownedResult = fx("district-provider-numbers.json", ownedResult)
        devicesResult = fx("district-devices.json", devicesResult)
        workspaceBillingResult = fx("district-workspace-billing.json", workspaceBillingResult)
        stripeBillingResult = fx("district-billing.json", stripeBillingResult)
        workspaceConfigResult = fx("district-workspace-config.json", workspaceConfigResult)
        knowledgeListResult = fx("district-knowledge.json", knowledgeListResult)
        knowledgeModeResult = fx("district-knowledge-mode.json", knowledgeModeResult)
        memberListResult = fx("district-members.json", memberListResult)
        meetingsResult = fx(ListSerializer(MeetingSummary.serializer()), "district-meetings.json", meetingsResult)
        meetingDetailResult = fx("district-meeting-detail.json", meetingDetailResult)
        workflowsResult = fx("district-workflows.json", workflowsResult)
        workflowRunsResult = fx("district-workflow-runs.json", workflowRunsResult)
        campaignStatusResult = fx("district-campaign-status.json", campaignStatusResult)
        hqPromptResult = fx("district-hq-answer.json", hqPromptResult)
        schedulingApi.schedulingStatusResult =
            fx("district-scheduling-status-ready.json", schedulingApi.schedulingStatusResult)
        messagingApi.messagingResult = fx("district-messaging.json", messagingApi.messagingResult)
    }
    private val desk = FakeDeskApi().apply {
        settingsResult = fx("district-desk-settings.json", settingsResult)
        ticketsResult = fx("district-desk-tickets.json", ticketsResult)
        ticketResult = fx("district-desk-ticket.json", ticketResult)
    }
    private val support = FakeSupportApi().apply {
        listResult = fx("district-support-requests.json", listResult)
        detailResult = fx("district-support-request.json", detailResult)
    }
    private val persona = FakePersonaApi().apply {
        optionsResult = fx("district-persona-options.json", optionsResult)
        repeat(20) { voiceStudioResults.addLast(ApiResult.Success(VoiceStudioFixture.studio)) }
    }
    private val search = FakeInboxExtrasApi().apply {
        threadResult = fx("district-message-thread.json", threadResult)
    }
    private val blocking = FakeContactBlockingApi().apply {
        blockedResult = fx("district-contacts-blocked.json", blockedResult)
    }
    private val numbers = FakeNumberProvisioningApi().apply {
        providerStatusResult = fx("district-provider-status.json", providerStatusResult)
    }
    private val carrier = FakeCarrierAccountApi().apply {
        sipResult = fx("district-sip-trunks.json", sipResult)
        verifyResult = fx("district-verify.json", verifyResult)
    }

    private val container by lazy {
        AppContainer(
            ApplicationProvider.getApplicationContext(),
            seams = AppContainerSeams(
                tokenStore = VqaNoTokens(),
                revokeApi = object : RevokeApi {
                    override suspend fun revoke(refreshToken: String) = RevokeResult.Done
                },
                pushTokenSource = { null },
                districtApi = api,
                deskApi = desk,
                supportApi = support,
                personaApi = persona,
                inboxExtrasApi = search,
                callHandlingApi = FakeCallHandlingApi(),
                contactBlockingApi = blocking,
                numberProvisioningApi = numbers,
                carrierAccountApi = carrier,
                callEngineFactory = CallEngineFactory { FakeCallEngine() },
                telecomBridge = FakeTelecomBridge(),
                schedulingAdminApi = FixtureSchedulingAdminApi(),
                accountApi = FakeAccountApi(),
                liveDependencies = LiveDependencies(minter = FakeTelemetryApi(), transport = FakeTransport().apply { socket() }),
            ),
        )
    }

    private lateinit var nav: NavHostController
    private var shots = 0

    /**
     * Advances the main looper and the compose clock frame by frame. No `waitForIdle`: a screen
     * with an endless animation (a spinner) never idles, and waiting for it to is what made each
     * frame cost seconds.
     */
    private fun pump(millis: Long = 900) {
        val frames = (millis / 16).coerceAtLeast(1)
        repeat(frames.toInt()) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeByFrame()
            if (it % 8 == 0) Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun lastRoot() = run {
        val roots = composeRule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().size
        if (roots > 1) composeRule.onAllNodes(isRoot(), useUnmergedTree = true).onLast()
        else composeRule.onRoot(useUnmergedTree = true)
    }

    /**
     * Every window on screen, drawn bottom to top into one frame, the way the device composes
     * them: the activity, then each dialog or popup over a dimmed copy, placed by its gravity.
     * `captureToImage` on a dialog's own root draws the activity behind it under Robolectric, so a
     * frame with more than one window is composed here instead.
     */
    private fun screenWithWindows(): Bitmap? {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = (global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<android.view.View>)
            .filter { it.isAttachedToWindow && it.visibility == android.view.View.VISIBLE && it.width > 0 && it.height > 0 }
        if (views.size < 2) return null
        val base = views.first()
        val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        views.forEachIndexed { i, v ->
            val lp = v.layoutParams as? android.view.WindowManager.LayoutParams
            if (i > 0 && lp != null && lp.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                canvas.drawColor(android.graphics.Color.argb((lp.dimAmount * 255).toInt(), 0, 0, 0))
            }
            val gravity = lp?.gravity ?: android.view.Gravity.CENTER
            val x = when (gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK) {
                android.view.Gravity.LEFT, android.view.Gravity.START -> lp?.x ?: 0
                android.view.Gravity.RIGHT, android.view.Gravity.END -> base.width - v.width - (lp?.x ?: 0)
                else -> (base.width - v.width) / 2 + (lp?.x ?: 0)
            }
            val y = when (gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK) {
                android.view.Gravity.TOP -> lp?.y ?: 0
                android.view.Gravity.BOTTOM -> base.height - v.height - (lp?.y ?: 0)
                else -> (base.height - v.height) / 2 + (lp?.y ?: 0)
            }
            canvas.save()
            canvas.translate(if (i == 0) 0f else x.toFloat(), if (i == 0) 0f else y.toFloat())
            v.draw(canvas)
            canvas.restore()
        }
        report.append("WINDOWS\t${views.size}\n")
        return bitmap
    }

    private fun save(name: String): Bitmap {
        val root = lastRoot()
        val bitmap = runCatching { screenWithWindows() }.onFailure { report.append("WINDOWS_FAILED\t${it.message}\n") }.getOrNull()
            ?: root.captureToImage().asAndroidBitmap()
        FileOutputStream(File(out, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shots++
        val node = root.fetchSemanticsNode()
        report.append("SCREEN\t$name\troute=${runCatching { nav.currentBackStackEntry?.destination?.route }.getOrNull()}\n")
        walk(node, name, node.boundsInRoot.width, node.boundsInRoot.height)
        return bitmap
    }

    /** The visible frame, then up to [MAX_PAGES] - 1 further pages of the first vertical scroller. */
    private fun capture(name: String) {
        pump()
        var previous = save(name)
        for (page in 2..MAX_PAGES) {
            val scrolled = runCatching {
                val scroller = lastRoot().fetchSemanticsNode().let { firstVerticalScroller(it) } ?: return@runCatching false
                val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
                if (range.value() >= range.maxValue() - 1f) return@runCatching false
                val step = scroller.boundsInRoot.height * 0.85f
                composeRule.runOnUiThread { scroller.config[SemanticsActions.ScrollBy].action?.invoke(0f, step) }
                true
            }.getOrDefault(false)
            if (!scrolled) break
            pump(600)
            val next = save("$name~p$page")
            if (next.sameAs(previous)) { File(out, "$name~p$page.png").delete(); shots--; break }
            previous = next
        }
    }

    private fun firstVerticalScroller(node: SemanticsNode): SemanticsNode? {
        val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        if (range != null && range.maxValue() > 0f && node.config.getOrNull(SemanticsActions.ScrollBy) != null) return node
        for (c in node.children) firstVerticalScroller(c)?.let { return it }
        return null
    }

    private fun walk(node: SemanticsNode, screen: String, width: Float, height: Float) {
        val cfg = node.config
        val texts = cfg.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val edit = cfg.getOrNull(SemanticsProperties.EditableText)?.text
        val desc = cfg.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val tag = cfg.getOrNull(SemanticsProperties.TestTag)
        val layouts = mutableListOf<TextLayoutResult>()
        runCatching { cfg.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) }
        val b = node.boundsInRoot
        val clickable = cfg.getOrNull(SemanticsActions.OnClick) != null
        val flags = buildList {
            layouts.firstOrNull()?.let { l ->
                if (l.didOverflowWidth) add("WIDTH")
                if (l.didOverflowHeight) add("HEIGHT")
                if (l.lineCount > 0 && l.isLineEllipsized(l.lineCount - 1)) add("ELLIPSIS")
                val raw = l.layoutInput.text.text
                for (i in 0 until l.lineCount - 1) {
                    val end = l.getLineEnd(i)
                    if (end in 1 until raw.length && raw[end - 1].isLetterOrDigit() && raw[end].isLetterOrDigit()) {
                        add("MIDWORD@" + raw.substring((end - 6).coerceAtLeast(0), (end + 6).coerceAtMost(raw.length)).replace('\t', ' '))
                    }
                }
                add("lines=${l.lineCount}")
            }
            if (b.right > width + 1f && b.left < width) add("PAST_RIGHT_EDGE")
            if (clickable && b.height > 0f && b.top < height) {
                val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
                val wdp = b.width / density
                val hdp = b.height / density
                if (wdp < 47.5f || hdp < 47.5f) add("SMALL_TARGET=${wdp.toInt()}x${hdp.toInt()}dp")
            }
        }
        for (t in texts) report.append("TEXT\t$screen\t${tag ?: ""}\t${flags.joinToString(",")}\t$t\n")
        if (edit != null) report.append("EDIT\t$screen\t${tag ?: ""}\t\t$edit\n")
        for (d in desc) report.append("DESC\t$screen\t${tag ?: ""}\t${flags.joinToString(",")}\t$d\n")
        if (texts.isEmpty() && desc.isEmpty() && clickable) report.append("CLICK\t$screen\t${tag ?: ""}\t${flags.joinToString(",")}\t\n")
        node.children.forEach { walk(it, screen, width, height) }
    }

    private fun go(route: String) {
        composeRule.runOnUiThread {
            nav.popBackStack(Routes.OVERVIEW, inclusive = false)
            if (route != Routes.OVERVIEW) nav.navigate(route)
        }
        pump(200)
    }

    /** Taps the first node whose test tag equals [tag], or failing that starts with it. */
    private fun tap(tag: String) {
        val exact = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true)
        val nodes = if (exact.fetchSemanticsNodes().isNotEmpty()) exact else composeRule.onAllNodes(
            SemanticsMatcher("tag starts with $tag") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(tag) == true },
            useUnmergedTree = true,
        )
        check(nodes.fetchSemanticsNodes().isNotEmpty()) { "no node tagged $tag" }
        val target = nodes[0]
        runCatching { target.performClick() }.getOrElse { target.performSemanticsAction(SemanticsActions.OnClick) }
        pump(800)
    }

    @Test
    fun walk() {
        org.junit.Assume.assumeTrue("set VQA_OUT to run the visual QA walk", outRoot != null)
        composeRule.mainClock.autoAdvance = false
        val started = System.currentTimeMillis()
        val vm = OverviewViewModel(container.workspaceRepository, container.overviewRepository)
        vm.load()
        val deadline = System.currentTimeMillis() + 5000
        while (vm.state.value !is OverviewUiState.Content && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val state = vm.state.value.takeIf { it is OverviewUiState.Content } ?: NavHostHarness.content()
        report.append("CONFIG\t$config\tdark=$dark\tfontScale=$fontScale\tcontracts=${contracts.path}\n")
        report.append("OVERVIEW_STATE\t${state.javaClass.simpleName}\n")
        val content = state as OverviewUiState.Content
        val ws = content.active?.id ?: "ws-1"
        val role = WorkspaceRole.AGENCY
        var signedOut by mutableStateOf(false)

        composeRule.setContent {
            // The font scale comes from the configuration (@Config fontScale), so Compose reads it
            // through the platform's non-linear converter (Android 14+) as a device does; here it
            // is only checked.
            val base = LocalDensity.current
            check(kotlin.math.abs(base.fontScale - fontScale) < 0.01f) { "fontScale ${base.fontScale}, wanted $fontScale" }
            CompositionLocalProvider(LocalDensity provides base) {
                DistrictTheme(
                    darkTheme = dark,
                    labels = DistrictLabels(back = androidx.compose.ui.res.stringResource(com.distronode.districtai.R.string.top_bar_back)),
                ) {
                    if (signedOut) {
                        SignInScreen(status = null, onSignIn = {})
                    } else {
                        val controller = rememberNavController()
                        nav = controller
                        DistrictNavHost(
                            container = container,
                            overviewViewModel = vm,
                            overviewState = state,
                            sessionEpoch = 0,
                            onSignIn = {},
                            onShowMessage = {},
                            navController = controller,
                        )
                    }
                }
            }
        }
        val firstCall = (api.callsResult as? ApiResult.Success)?.value?.firstOrNull()?.id ?: "call-1"
        val firstContactSummary = (api.contactsResult as? ApiResult.Success)?.value?.contacts?.firstOrNull()
        val firstContact = firstContactSummary?.id ?: "c-1"
        val firstTicket = (desk.ticketsResult as? ApiResult.Success)?.value?.tickets?.firstOrNull()?.id ?: "t-1"
        val firstRequest = (support.listResult as? ApiResult.Success)?.value?.requests?.firstOrNull()?.let { it.issueKey ?: it.id } ?: "r-1"

        var n = 1
        // VQA_ONLY / VQA_SKIP: regexes over the scene name, to re-shoot a subset.
        val only = System.getenv("VQA_ONLY")?.toRegex()
        val skip = System.getenv("VQA_SKIP")?.toRegex()
        fun scene(name: String, route: String?, vararg taps: String) {
            val label = "%03d-%s".format(n++, name)
            if ((only != null && !only.containsMatchIn(name)) || (skip != null && skip.containsMatchIn(name))) return
            runCatching {
                if (route != null) go(route)
                taps.forEach { tap(it) }
                capture(label)
            }.onFailure { report.append("FAILED\t$label\t$route\t${it.javaClass.simpleName}: ${it.message?.take(300)}\n") }
            pump(300)
        }
        fun settings(section: String?) = Routes.workspaceSettings(ws, role, section)

        // Main sections.
        scene("overview", Routes.OVERVIEW)
        scene("overview-workspace-switcher", Routes.OVERVIEW, "district-overview-workspace-switcher")
        scene("calls", Routes.callLog(ws, role))
        scene("call-detail", Routes.callDetail(ws, firstCall, role))
        scene("call-detail-report-dialog", Routes.callDetail(ws, firstCall, role), "district-call-detail-report")
        scene("inbox", Routes.inbox(ws, role))
        scene("inbox-compose-dialog", Routes.inbox(ws, role), "district-inbox-compose-open")
        val threadTitle = firstContactSummary?.name ?: "Contract Test Caller"
        val threadAddr = firstContactSummary?.email ?: "ada@contract.test"
        val threadRoute = Routes.thread(ws, role, "contact:$firstContact", threadAddr, "email", threadTitle)
        scene("thread", threadRoute)
        scene("thread-report-dialog", threadRoute, "district-thread-report")
        scene("thread-block-dialog", threadRoute, "district-thread-block")
        scene("contacts", Routes.contacts(ws, role))
        scene("contacts-add-dialog", Routes.contacts(ws, role), "district-contacts-add")
        scene("contact-detail", Routes.contactDetail(ws, firstContact, role))
        scene("contact-block-dialog", Routes.contactDetail(ws, firstContact, role), "district-contact-block")
        scene("contact-delete-dialog", Routes.contactDetail(ws, firstContact, role), "district-contact-detail-delete")
        scene("blocked", Routes.blocked(ws, role))
        scene("numbers", Routes.marketplace(ws, role))
        scene("numbers-registrations", Routes.marketplace(ws, role), "district-marketplace-tab-registrations")
        scene("numbers-carrier", Routes.marketplace(ws, role), "district-marketplace-tab-carrier")
        scene("analytics", Routes.analytics(ws))
        scene("dialer", Routes.dialer(ws, role))
        scene("rooms", Routes.rooms(ws, role))
        scene("workflows", Routes.workflows(ws, role))
        scene("desk", Routes.desk(ws, role))
        scene("desk-ticket", Routes.deskTicket(ws, role, firstTicket))
        scene("desk-settings", Routes.deskSettings(ws, role))
        scene("hq", Routes.hq(ws, role))
        scene("support", Routes.support(ws, role))
        scene("support-new-dialog", Routes.support(ws, role), "district-support-new")
        scene("support-request", Routes.supportRequest(ws, role, firstRequest))
        scene("billing", Routes.billing(ws, role))
        SchedulingSection.entries.forEach { s ->
            scene("scheduling-${s.pathSegment}", SchedulingAdminRoutes.section(ws, s))
        }
        scene("scheduling-event-type-create-dialog", SchedulingAdminRoutes.section(ws, SchedulingSection.EVENT_TYPES), "district-scheduling-event-types-create")
        scene("scheduling-hours-override-dialog", SchedulingAdminRoutes.section(ws, SchedulingSection.HOURS), "district-scheduling-hours-add-override")
        scene("scheduling-team-create-dialog", SchedulingAdminRoutes.section(ws, SchedulingSection.TEAM), "district-scheduling-team-create")
        scene("scheduling-settings-profile-dialog", SchedulingAdminRoutes.section(ws, SchedulingSection.SETTINGS), "district-scheduling-settings-profile-edit")
        scene("scheduling-developer-key-dialog", SchedulingAdminRoutes.section(ws, SchedulingSection.DEVELOPER), "district-scheduling-developer-key-create")
        // Account and workspace settings.
        scene("settings", Routes.SETTINGS)
        scene("settings-sign-out-dialog", Routes.SETTINGS, "district-settings-sign-out")
        scene("settings-delete-account-dialog", Routes.SETTINGS, "district-settings-delete-account")
        scene("devices", Routes.DEVICES)
        scene("workspace-settings", settings(null))
        scene("workspace-settings-more-menu", settings(null), "district-workspace-settings-more")
        scene("ws-persona", settings(Routes.SECTION_PERSONA))
        scene("ws-persona-preview-dialog", settings(Routes.SECTION_PERSONA), "district-persona-preview-open")
        scene("ws-voice-studio", settings(Routes.SECTION_VOICE_STUDIO))
        scene("ws-capabilities", settings(Routes.SECTION_CAPABILITIES))
        scene("ws-directory", settings(Routes.SECTION_DIRECTORY))
        scene("ws-routing", settings(Routes.SECTION_ROUTING))
        scene("ws-knowledge", settings(Routes.SECTION_KNOWLEDGE))
        scene("ws-knowledge-add-dialog", settings(Routes.SECTION_KNOWLEDGE), "district-knowledge-add")
        scene("ws-messaging", settings(Routes.SECTION_MESSAGING))
        scene("ws-messaging-add-dialog", settings(Routes.SECTION_MESSAGING), "district-messaging-add")
        scene("ws-members", settings(Routes.SECTION_MEMBERS))
        scene("ws-members-add-dialog", settings(Routes.SECTION_MEMBERS), "district-members-add-open")
        scene("ws-calls", settings(Routes.SECTION_CALLS))

        // The live transcript: the same call, in progress, over a socket that opens and waits.
        val doneDetail = api.detailResult
        (doneDetail as? ApiResult.Success)?.let { r ->
            api.detailResult = ApiResult.Success(r.value.copy(call = r.value.call?.copy(status = "in-progress")))
        }
        scene("call-live-transcript", Routes.callDetail(ws, firstCall, role))
        api.detailResult = doneDetail

        // Empty states.
        api.callsResult = ApiResult.Success(emptyList())
        api.contactsResult = ApiResult.Success(ContactListResponse(success = true))
        api.conversationsResult = ApiResult.Success(ConversationsResponse(success = true))
        api.knowledgeListResult = ApiResult.Success(KnowledgeListResponse(success = true))
        api.workflowsResult = ApiResult.Success(WorkflowListResponse(success = true))
        scene("empty-calls", Routes.callLog(ws, role))
        scene("empty-contacts", Routes.contacts(ws, role))
        scene("empty-inbox", Routes.inbox(ws, role))
        scene("empty-knowledge", settings(Routes.SECTION_KNOWLEDGE))
        scene("empty-workflows", Routes.workflows(ws, role))

        // Error states.
        val down = java.io.IOException("offline")
        api.callsResult = ApiResult.NetworkFailure(down)
        api.contactsResult = ApiResult.NetworkFailure(down)
        api.conversationsResult = ApiResult.NetworkFailure(down)
        api.stripeBillingResult = ApiResult.NetworkFailure(down)
        api.workspaceBillingResult = ApiResult.NetworkFailure(down)
        api.analyticsResult = ApiResult.NetworkFailure(down)
        api.detailResult = ApiResult.NetworkFailure(down)
        scene("error-calls", Routes.callLog(ws, role))
        scene("error-call-detail", Routes.callDetail(ws, firstCall, role))
        scene("error-contacts", Routes.contacts(ws, role))
        scene("error-inbox", Routes.inbox(ws, role))
        scene("error-billing", Routes.billing(ws, role))
        scene("error-analytics", Routes.analytics(ws))

        // Signed out.
        composeRule.runOnUiThread { signedOut = true }
        pump()
        runCatching { capture("%03d-sign-in".format(n++)) }
            .onFailure { report.append("FAILED\tsign-in\t\t${it.message?.take(300)}\n") }

        report.append("SHOTS\t$shots\tseconds=${(System.currentTimeMillis() - started) / 1000}\n")
        File(out, "report.tsv").writeText(report.toString())
    }

    private companion object {
        const val MAX_PAGES = 3
    }
}

private class VqaNoTokens : TokenStore {
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

private const val VQA_Q = "w411dp-h731dp"

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "en-rUS-$VQA_Q-notnight-xxhdpi")
class VisualQaEnLight : VisualQaWalkBase("en-US-light", dark = false, fontScale = 1f, french = false)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "en-rUS-$VQA_Q-night-xxhdpi")
class VisualQaEnDark : VisualQaWalkBase("en-US-dark", dark = true, fontScale = 1f, french = false)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "fr-rCA-$VQA_Q-notnight-xxhdpi")
class VisualQaFrLight : VisualQaWalkBase("fr-CA-light", dark = false, fontScale = 1f, french = true)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "fr-rCA-$VQA_Q-night-xxhdpi")
class VisualQaFrDark : VisualQaWalkBase("fr-CA-dark", dark = true, fontScale = 1f, french = true)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "en-rUS-$VQA_Q-notnight-xxhdpi", fontScale = 2f)
class VisualQaEnLarge : VisualQaWalkBase("en-US-light-200", dark = false, fontScale = 2f, french = false)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "fr-rCA-$VQA_Q-notnight-xxhdpi", fontScale = 2f)
class VisualQaFrLarge : VisualQaWalkBase("fr-CA-light-200", dark = false, fontScale = 2f, french = true)
