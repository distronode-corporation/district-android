package com.distronode.districtai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.applinks.AppLinkDestination
import com.distronode.districtai.applinks.appLinkDestination
import com.distronode.districtai.auth.CustomTabsLauncher
import com.distronode.districtai.auth.LoginController
import com.distronode.districtai.push.InboxDeepLink
import com.distronode.districtai.push.PushIntentAction
import com.distronode.districtai.push.PushIntents
import com.distronode.districtai.push.pushIntentAction
import com.distronode.districtai.ui.DistrictNavHost
import com.distronode.districtai.ui.incoming.IncomingCallHost
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.SignInScreen
import com.distronode.districtai.ui.SignedOutCause
import com.distronode.districtai.ui.overview.OverviewUiState
import com.distronode.districtai.ui.overview.OverviewViewModel
import com.distronode.districtai.core.designsystem.DistrictTheme
import kotlinx.coroutines.launch

/**
 * Single activity host.
 *
 * ⛔ THIS ACTIVITY IS `singleTask` FOR THE LOGIN CALLBACK — see the manifest. The PKCE callback
 * arrives as a new VIEW intent, and under the default launch mode it would create a second
 * activity instance whose NativeLoginFlow has no pending attempt, failing every login with
 * NoAttemptInProgress. Routing it to the live instance through [onNewIntent] is what keeps the
 * in-memory code verifier reachable.
 *
 * ⚠️ NO NAVIGATION LIBRARY FOR THE SIGN-IN CHOICE, DELIBERATELY. The choice between the sign-in
 * screen and the graph is not navigation — it is a function of auth state, which the overview's own
 * [OverviewUiState.SignedOut] already reports. Modelling a back stack that must never contain the
 * sign-in screen would be more code and a worse invariant.
 *
 * ⛔ "AM I SIGNED IN" IS NOT TRACKED SEPARATELY, ON PURPOSE. The only authority on whether a
 * session is usable is the token coordinator, and it can only answer by trying. A second boolean
 * held here would be a cache of that answer and would disagree with it — most obviously after a
 * server-side revocation, where a stored token exists but no longer works. So the overview loads,
 * and a NEVER_SIGNED_IN result is what renders the sign-in screen.
 *
 * ⛔ AND THERE IS EXACTLY ONE OverviewViewModel, WHICH IS THE FIX FOR AN UNRECOVERABLE BUG. This
 * activity used to build its own "probe" instance to answer that question while [DistrictNavHost]
 * built a SECOND one, from the nav entry's store, for the destination that actually draws. Both ran
 * `init { load() }`, so every cold start issued `workspace/list` + `overview` twice — and `overview`
 * fans out across up to four regional databases, making it the most expensive call in the API.
 * Worse, the login-completion callback was assigned from the composable body and captured the
 * PROBE's instance, so after a mid-session sign-out the user could sign in successfully and the
 * screen they were looking at never changed. With no pull-to-refresh anywhere, killing the app was
 * the only way out. The instance is hoisted here and handed down; sign-in completion is broadcast
 * through [com.distronode.districtai.auth.SessionSignal] so every screen can react, including the
 * paged ones whose Pager had already failed.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as AppContainerOwner).container

    /**
     * ⛔ NOT AN ACTIVITY FIELD ANY MORE, AND NOT A ViewModel EITHER. The status message, the
     * attempt tracker and the exchange's coroutine scope all used to live here and all three were
     * destroyed by a rotation — see [LoginController] for what each one broke.
     */
    private val loginController: LoginController get() = container.loginController

    override fun onCreate(savedInstanceState: Bundle?) {
        // Mandatory rather than cosmetic on API 35+, where edge-to-edge is enforced for apps
        // targeting that level and opting out is no longer available.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            DistrictTheme {
                // ⛔ THE ONE instance, owned by the activity's ViewModelStore so it survives a
                // configuration change, and passed into the graph rather than re-created there.
                val overviewViewModel: OverviewViewModel = viewModel(
                    factory = OverviewViewModel.factory(
                        workspaceRepository = container.workspaceRepository,
                        overviewRepository = container.overviewRepository,
                        setupRepository = container.setupRepository,
                    ),
                )
                // ⛔ collectAsStateWithLifecycle, NOT collectAsState. The plain version keeps
                // collecting while the app is backgrounded, so a screen nobody can see goes on
                // recomposing — and once this screen polls, it would go on issuing requests on a
                // metered connection forever.
                val state by overviewViewModel.state.collectAsStateWithLifecycle()
                val loginStatus by loginController.status.collectAsStateWithLifecycle()
                val sessionEpoch by container.sessionSignal.epoch.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                // ⛔ AN EFFECT, NOT AN ASSIGNMENT IN THE COMPOSABLE BODY. The previous version did
                // `onSignedIn = { viewModel.load() }` inline, which is a write to non-Compose state
                // during composition: re-run on every recomposition, ordered unpredictably against
                // the login it was meant to serve, and invisible to Compose's own tracking.
                OnSessionChanged(sessionEpoch) { overviewViewModel.load() }

                val neverSignedIn = (state as? OverviewUiState.SignedOut)?.cause ==
                    SignedOutCause.NEVER_SIGNED_IN

                // ⛔ A Box SO THE RINGING SCREEN CAN BE DRAWN **OVER** THE GRAPH RATHER THAN
                // INSTEAD OF IT, AND THAT IS NOT A LAYOUT PREFERENCE. Swapping the NavHost out
                // would remove `rememberNavController` from the composition and take the whole back
                // stack with it, so a call answered mid-task would return the user to the overview
                // with everything they were doing gone.
                //
                // ⛔ AND THE CALL IS NOT A NAVIGATION DESTINATION, for the reason `DialerUiState`
                // documents: a destination restored from the back stack after process death re-runs
                // whatever effect put it there, which here would be a ringing screen for a call
                // that died with the process. A live call is derived state, exactly as sign-in is.
                Box(modifier = Modifier.fillMaxSize()) {
                    if (neverSignedIn) {
                        // First run or a sign-out: the ordinary sign-in screen, not an error.
                        // ⛔ Kept OUT of the navigation graph — see DistrictNavHost. Sign-in is
                        // derived state, and putting it in a back stack means owning "never
                        // navigate back into the app after signing out" by hand.
                        SignInScreen(status = loginStatus, onSignIn = ::startLogin)
                    } else {
                        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                            Box(modifier = Modifier.padding(padding)) {
                                DistrictNavHost(
                                    container = container,
                                    overviewViewModel = overviewViewModel,
                                    overviewState = state,
                                    sessionEpoch = sessionEpoch,
                                    onSignIn = ::startLogin,
                                    onShowMessage = { message ->
                                        lifecycleScope.launch {
                                            snackbarHostState.showSnackbar(message)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    // ⚠️ RENDERED IN THE SIGNED-OUT BRANCH TOO — unreachable rather than guarded.
                    // `PushMessageHandler` drops a push on a device with no stored session, so
                    // nothing can put a call here without one; a second check here would be a
                    // second answer to a question that already has one, and the two could disagree.
                    IncomingCallHost(controller = container.incomingCallController)
                }
            }
        }

        // A cold start FROM the callback (the activity was killed while the browser was open)
        // still delivers the URI through the launch intent rather than onNewIntent.
        consumeCallback(intent)
        // ⚠️ AND A COLD START FROM A NOTIFICATION IS THE ORDINARY CASE FOR THIS ONE: a push wakes a
        // killed process, the user taps Answer, and the intent arrives on the launch intent rather
        // than through onNewIntent.
        consumePushIntent(intent)
        // ⚠️ A COLD START IS ALSO THE ORDINARY CASE FOR AN App Link — the user taps a URL in a chat
        // and the process does not exist yet.
        consumeAppLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // ⚠️ Also update the stored intent, so a later getIntent() does not still return the
        // original launch intent. ⛔ On its own this does NOT prevent double-processing, which the
        // old comment here claimed — see consumeCallback for what actually does.
        setIntent(intent)
        consumeCallback(intent)
        consumePushIntent(intent)
        // ⚠️ AND THIS IS THE COMMON PATH RATHER THAN THE EXOTIC ONE: `launchMode="singleTask"` means
        // a link tapped while the app is already running arrives here, not in onCreate.
        consumeAppLink(intent)
    }

    private fun startLogin() {
        val url = loginController.authorizeUrl()
        val opened = CustomTabsLauncher.launch(this, url) !is CustomTabsLauncher.LaunchResult.NoBrowser
        loginController.onBrowserLaunched(opened)
    }

    override fun onPause() {
        super.onPause()
        loginController.onHostPaused()
    }

    override fun onResume() {
        super.onResume()
        loginController.onHostResumed()
    }

    /**
     * Hand a login callback to the controller, exactly once.
     *
     * ⛔ CLEARING `intent.data` IS THE FIX, AND WITHOUT IT A SUCCESSFUL LOGIN REPORTS AS EXPIRED.
     * Nothing marked a callback consumed, so a recreated activity re-ran the launch intent's URI
     * through the exchange. The second attempt finds no pending PKCE attempt — the first consumed
     * it — and returns `NoAttemptInProgress`, which this app words as "That sign-in link has
     * expired". A login that may have fully succeeded therefore ended on a failure message. Rotating
     * during the exchange was enough to trigger it.
     *
     * ⚠️ Cleared BEFORE the controller is called, and only after the scheme check, so an unrelated
     * VIEW intent is left untouched.
     */
    private fun consumeCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        // Only our callback scheme; ignore anything else that resolves here.
        if (uri.scheme != CALLBACK_SCHEME) return
        intent.data = null
        loginController.onCallback(uri)
    }

    /**
     * Act on a notification the user just tapped, exactly once.
     *
     * ⛔ THE EXTRAS ARE REMOVED AFTER READING, FOR THE REASON `consumeCallback` CLEARS `intent.data`.
     * A `singleTask` Activity keeps its launch intent, and a recreation — a rotation, a dark-mode
     * switch — re-delivers it to `onCreate`. Without the clear, rotating during a call would answer
     * it a second time, and rotating on the inbox would yank the user back to it every time.
     *
     * ⛔ THE DECISION ITSELF IS IN [pushIntentAction], WHICH IS PURE AND TESTED. What is left here is
     * reading four extras and dispatching, because an Activity is the one thing this project cannot
     * exercise: it is `singleTask`, it is constructed by the system, and the interesting cases (a
     * cold start from a notification, a stale pending intent) are precisely the ones a test host
     * does not reproduce.
     *
     * ⚠️ `AnswerCall` IS DELEGATED AND NOT VALIDATED HERE. The controller drops an answer request
     * for anything that is not still RINGING, so a stale pending intent for a call that already
     * ended does nothing — which is the correct outcome and is asserted where that rule lives.
     */
    private fun consumePushIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val action = pushIntentAction(
            workspaceId = extras.getString(PushIntents.EXTRA_WORKSPACE_ID),
            messageId = extras.getString(PushIntents.EXTRA_MESSAGE_ID),
            callId = extras.getString(PushIntents.EXTRA_CALL_ID),
            answer = extras.getBoolean(PushIntents.EXTRA_ANSWER, false),
        )
        intent.removeExtra(PushIntents.EXTRA_WORKSPACE_ID)
        intent.removeExtra(PushIntents.EXTRA_MESSAGE_ID)
        intent.removeExtra(PushIntents.EXTRA_CALL_ID)
        intent.removeExtra(PushIntents.EXTRA_ANSWER)
        when (action) {
            null -> Unit
            // ⚠️ RECORDED RATHER THAN NAVIGATED. The active workspace comes from two reads that are
            // still in flight on a cold start, so the graph consumes this when it can act on it.
            is PushIntentAction.OpenInbox -> container.pushDeepLinks.offer(
                InboxDeepLink(action.workspaceId, action.messageId),
            )
            // ⚠️ RECORDED, NOT ANSWERED: answering asks for the microphone first, and the launcher
            // that can ask lives in the Compose tree (`IncomingCallHost`), not here — see the ⛔ on
            // `IncomingCallController.answerRequested` for the lint rule that decided it.
            PushIntentAction.AnswerCall -> container.incomingCallController.requestAnswerFromNotification()
            // ⚠️ NOTHING TO DO: the ringing screen is drawn from the controller's state and is
            // already on screen. Bringing the Activity forward WAS the whole request.
            PushIntentAction.ShowCall -> Unit
        }
    }

    /**
     * Act on a verified App Link, exactly once.
     *
     * ⛔ IT RUNS AFTER [consumeCallback] AND IS SAFE IN EITHER ORDER, WHICH IS WORTH STATING BECAUSE
     * THE LOGIN CALLBACK ARRIVES AS AN `ACTION_VIEW` TOO. `districtai://auth` has host "auth", which
     * is not one of the two claimed hosts, so [appLinkDestination] answers
     * [AppLinkDestination.Ignore] and leaves `intent.data` alone for the callback to read. Collapsing
     * `Ignore` into `OpenInBrowser` would open the login callback in a Custom Tab and break sign-in
     * — see the ⛔ on that interface.
     *
     * ⛔ `intent.data` IS CLEARED FOR THE REASON [consumeCallback] CLEARS IT. A `singleTask` Activity
     * keeps its launch intent and re-delivers it to `onCreate` on every recreation, so without the
     * clear a rotation would re-navigate the user to the linked section every time they turned the
     * phone — including after they had deliberately navigated away.
     *
     * ⛔ AND THE BROWSER HAND-OFF USES [CustomTabsLauncher.launchExternally], NOT `launch`. This is
     * the one call site in the app whose URL the app itself claims, so an implicit ACTION_VIEW would
     * resolve straight back into this method. See the ⛔ in CustomTabsLauncher's header.
     *
     * ⚠️ NO MESSAGE ON A FAILED HAND-OFF, unlike the marketplace and account-deletion call sites.
     * Those are a button the user pressed and is waiting on; this is a URL the OS routed here
     * without being asked, and a snackbar about browsers for a link that would have opened in one
     * anyway explains nothing. Doing nothing leaves the user on the overview, which is where the
     * app was going to put them.
     */
    private fun consumeAppLink(intent: Intent?) {
        val uri = intent?.data ?: return
        when (val destination = appLinkDestination(host = uri.host, path = uri.path)) {
            AppLinkDestination.Ignore -> Unit
            AppLinkDestination.OpenInBrowser -> {
                intent.data = null
                CustomTabsLauncher.launchExternally(this, uri.toString())
            }
            // ⚠️ RECORDED RATHER THAN NAVIGATED, exactly as the push deep link is: the active
            // workspace comes from two reads still in flight on a cold start, so the graph consumes
            // this when it can act on it. See AppLinkDeepLinks.
            is AppLinkDestination.Section -> {
                intent.data = null
                container.appLinkDeepLinks.offer(destination.section)
            }
        }
    }

    private companion object {
        const val CALLBACK_SCHEME = "districtai"
    }
}
