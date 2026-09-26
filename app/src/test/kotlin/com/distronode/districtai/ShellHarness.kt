package com.distronode.districtai

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import com.distronode.districtai.auth.LoginStatus
import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.core.auth.ReauthReason
import com.distronode.districtai.core.auth.RevokeApi
import com.distronode.districtai.core.auth.RevokeResult
import com.distronode.districtai.core.auth.TokenStore
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.OverviewResponse
import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceListResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.dialer.FakeTelecomBridge
import com.distronode.districtai.ui.rooms.FakeCallEngine
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The application every [MainActivity] test runs under.
 *
 * ⛔ NOT [DistrictApplication], BECAUSE ITS CONTAINER CAN ONLY REACH PRODUCTION. Every API it builds
 * is pointed at `ApiEnvironment.baseUrl`, a `BuildConfig` constant, so an activity test on the real
 * application either talks to the live server or sees nothing but "not signed in". This one hands
 * the activity a container whose network, credential store, Telecom bridge and media engine are
 * all fakes, through the same [AppContainerOwner] the real application implements.
 *
 * ⚠️ BUILT LAZILY ON FIRST READ, WHICH IS THE ACTIVITY'S `onCreate`, so a test configures [api]
 * before launching and the container picks up whatever it was told.
 */
internal class ShellTestApplication : Application(), AppContainerOwner {

    val api = TestDistrictApi()
    val telecom = FakeTelecomBridge()
    val store = ShellTokenStore()

    override val container: AppContainer by lazy {
        AppContainer(
            this,
            seams = AppContainerSeams(
                tokenStore = store,
                revokeApi = DoneRevokeApi,
                pushTokenSource = { null },
                districtApi = api,
                telecomBridge = telecom,
                callEngineFactory = CallEngineFactory { FakeCallEngine() },
            ),
        )
    }
}

/**
 * [MainActivity] launched and re-driven through Robolectric's activity controller.
 *
 * ⚠️ A CONTROLLER RATHER THAN `ActivityScenario`, because the activity is `singleTask` and half of
 * what it does arrives through `onNewIntent`, which only the controller can deliver to the live
 * instance. It also drives pause, resume and recreation, which the login tracker depends on.
 */
internal class ShellHarness(private val compose: ComposeTestRule) {

    val app: ShellTestApplication = ApplicationProvider.getApplicationContext()

    private var controller: ActivityController<MainActivity>? = null

    val activity: MainActivity get() = checkNotNull(controller).get()

    val container: AppContainer get() = app.container

    /** What the sign-in screen says right now, as the controller holds it. */
    val loginStatus: LoginStatus? get() = container.loginController.status.value

    /** The overview's own read answers "no credential on this device", as a first run does. */
    fun signedOut() {
        app.api.workspaceListResult = ApiResult.Unauthorized(ReauthReason.NoSession)
    }

    /** The overview's reads answer with one agency workspace, so the graph has data behind it. */
    fun signedIn() {
        app.api.workspaceListResult = ApiResult.Success(
            WorkspaceListResponse(success = true, workspaces = listOf(WORKSPACE)),
        )
        app.api.overviewResult = ApiResult.Success(
            OverviewResponse(success = true, workspaceId = WORKSPACE.id, role = "agency"),
        )
    }

    fun launch(intent: Intent = launchIntent()) {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        settle()
    }

    /** Hand the live instance a new intent, as `singleTask` does for a tap while the app runs. */
    fun deliver(intent: Intent) {
        checkNotNull(controller).newIntent(intent)
        settle()
    }

    /** A browser leg: the activity is covered and then comes back. */
    fun pauseAndResume() {
        checkNotNull(controller).pause().resume()
        settle()
    }

    /** A configuration change: the instance is destroyed and rebuilt with the same launch intent. */
    fun recreate() {
        checkNotNull(controller).recreate()
        settle()
    }

    fun close() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Make `http://` resolve to a browser, so a Custom Tab has somewhere to go. */
    fun installBrowser() {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = BROWSER_PACKAGE
                name = "$BROWSER_PACKAGE.Main"
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(probe, info)
    }

    /** The next activity this one started, or null. */
    fun nextStarted(): Intent? = shadowOf(activity).nextStartedActivity

    fun awaitText(text: String) = await("text '$text'") {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    fun awaitDescription(description: String) = await("description '$description'") {
        compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Pump the main looper until [condition] holds.
     *
     * ⚠️ THE CREDENTIAL AND SELECTION READS HOP TO `Dispatchers.IO` AND BACK, and a continuation
     * posted to the main thread is an ordinary looper message that runs only when the looper is
     * idled. The outcome is fixed; only the moment it lands is not, so this waits for the outcome.
     */
    fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.yield()
        }
        if (!condition()) {
            throw AssertionError("$what never appeared:\n" + compose.onRoot().printToString())
        }
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    fun launchIntent(): Intent = Intent(app, MainActivity::class.java)

    fun viewIntent(uri: String): Intent = launchIntent().setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri))

    fun string(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    companion object {
        const val WAIT_MILLIS = 5_000L
        const val BROWSER_PACKAGE = "com.example.browser"

        val WORKSPACE = WorkspaceEntry(id = "ws-1", name = "Alpha Agency", region = "ca", role = "agency")
    }
}

/**
 * An in-memory credential store.
 *
 * ⚠️ EMPTY BY DEFAULT, AND NO FAKE API READS IT: the overview's answer comes from [TestDistrictApi].
 * What reads it is the push gate ("is anyone signed in on this device"), so a test that needs a
 * push drawn puts a session here.
 */
internal class ShellTokenStore : TokenStore {
    var session: PersistedSession? = null

    override fun read(): PersistedSession? = session
    override fun write(session: PersistedSession) {
        this.session = session
    }
    override fun clear() {
        session = null
    }
    override fun pendingRefreshToken(): String? = null
    override fun markRefreshPending(refreshToken: String) = Unit
    override fun clearRefreshPending() = Unit
    override fun pendingRevokeToken(): String? = null
    override fun markRevokePending(refreshToken: String) = Unit
    override fun clearRevokePending() = Unit
}

/** Answered locally, so nothing under test can reach the revoke route. */
private object DoneRevokeApi : RevokeApi {
    override suspend fun revoke(refreshToken: String): RevokeResult = RevokeResult.Done
}
