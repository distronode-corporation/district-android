package com.distronode.districtai.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.push.DeepLinkDecision
import com.distronode.districtai.push.PushDeepLinks
import com.distronode.districtai.push.inboxDeepLinkDecision
import com.distronode.districtai.push.resolveMessageDeepLinkRoute
import kotlinx.coroutines.launch

/**
 * Ask for `POST_NOTIFICATIONS`, once, at the first screen a signed-in user sees.
 *
 * ⛔ AT THE OVERVIEW AND NOT AT LAUNCH, WHICH IS THE SAME RULE THE MICROPHONE FOLLOWS AT THE ROOM
 * AND AT THE DIALLER. A permission dialog with no visible reason is the one users deny permanently,
 * and on Android 13+ a permanent denial cannot be re-requested — the app can only send the user to
 * Settings. The overview is reached only after a session exists, so the reason is on screen behind
 * the dialog: this is the product they have just signed in to.
 *
 * ⛔ THE RESULT IS DELIBERATELY IGNORED, AND THAT IS NOT LAZINESS. A denial changes nothing this
 * code can act on: `notify` becomes a silent no-op, the push still arrives, the Telecom connection
 * still rings and the call is still answerable from the OS's own call surfaces. Branching on it
 * would mean drawing a "notifications are off" state on the dashboard of a business app, which is
 * the platform's job in Settings and not this screen's.
 *
 * ⚠️ API 33+ ONLY. Below that the permission does not exist and `launch` would fail on a permission
 * the manifest merger does not even keep. The check reads `Build.VERSION.SDK_INT` rather than a
 * feature probe because that is what the platform gates on.
 *
 * ⛔ THE LAUNCHER IS REMEMBERED UNCONDITIONALLY AND ONLY THE **LAUNCH** IS GATED. Calling
 * `rememberLauncherForActivityResult` inside an `if` makes the composable's remember slots differ
 * between compositions, which is the classic way a Compose function corrupts its own state — even
 * when the condition is constant for the process, as it is here.
 *
 * ⚠️ `LaunchedEffect(Unit)` SO IT FIRES ONCE PER ENTRY TO THE DESTINATION rather than per
 * recomposition. `RequestPermission` invokes its callback immediately when the permission is already
 * held, with no UI, so a repeat entry costs nothing visible — but a request replayed on every frame
 * would be a dialog the user cannot dismiss.
 */
@Composable
internal fun NotificationsPermissionEffect() {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* See the ⛔ above: a denial needs no handling. */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Navigate to the THREAD a message notification named, once the app knows enough to.
 *
 * ⛔ THE THREAD, NOT THE INBOX LIST. A message push carries `{workspaceId, messageId}` and
 * nothing else, so the id is exchanged for a thread by `GET /api/district/messages/[id]` under
 * this app's own bearer. That call is why this effect suspends rather than being a pure branch:
 * see `resolveMessageDeepLinkRoute`, which also owns the fallback to the inbox list when the
 * exchange fails.
 *
 * ⛔ IT LIVES OUTSIDE `DistrictNavHost` FOR THE REASON THE ROOMS, DIALLER AND SETTINGS DESTINATIONS
 * DO: lambdas inside that function count toward its cyclomatic complexity, which is already at
 * detekt's ceiling. The DECISION is further out still, in `inboxDeepLinkDecision`, which is pure and
 * tested — what remains here is an effect that cannot be unit-tested because it navigates.
 *
 * ⛔ THE PENDING LINK IS CLEARED ON EVERY RESOLUTION INCLUDING [DeepLinkDecision.Drop]. A link for a
 * workspace that is not the active one is not honoured (switching tenants from a notification is a
 * product decision nobody has made), and one left pending would re-fire the moment the user switched
 * to that workspace for their own reasons, minutes later, and yank them into the inbox.
 *
 * ⚠️ CLEARED **BEFORE** THE RESOLVER RUNS, not after. The exchange is a network round trip, and a
 * link still pending across it would re-enter this effect on the next recomposition and spend a
 * second request for the same tap.
 *
 * ⛔ AND THEREFORE THE EXCHANGE RUNS IN [rememberCoroutineScope], NOT IN THE `LaunchedEffect`.
 * Clearing the link changes that effect's `pending` key, so the recomposition it triggers CANCELS the
 * effect's own coroutine: a resolve suspended inside it would die mid-request and the tap would
 * navigate nowhere at all. The remembered scope belongs to this composable, which sits outside the
 * NavHost, so it outlives both the key change and the navigation it causes.
 *
 * @param messageSearch used for the id-to-thread exchange only.
 * @param role carried into the route because the inbox and the thread both gate their writes on it.
 *   ⚠️ Null is a role the server did not recognise; `Routes.inbox` encodes it as "none" and the
 *   screens fail closed on it.
 */
@Composable
internal fun PushDeepLinkEffect(
    deepLinks: PushDeepLinks,
    messageSearch: MessageSearchRepository,
    workspaceId: String?,
    role: WorkspaceRole?,
    navController: NavHostController,
) {
    val pending by deepLinks.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(pending, workspaceId, role) {
        when (val decision = inboxDeepLinkDecision(pending, workspaceId)) {
            // ⚠️ The workspace has not resolved yet. On a cold start from a notification this is the
            // state for the whole of the first two reads, so waiting is what makes the deep link
            // work at all in the case it exists for.
            DeepLinkDecision.Wait -> Unit
            DeepLinkDecision.Drop -> deepLinks.clear()
            is DeepLinkDecision.Go -> {
                deepLinks.clear()
                val link = decision.link
                scope.launch {
                    val route = resolveMessageDeepLinkRoute(
                        repository = messageSearch,
                        link = link,
                        inboxRoute = { Routes.inbox(it, role) },
                        // ⛔ THE REPLY TARGET COMES FROM THE RESOLVER, NOT FROM THE THREAD KEY.
                        // Sending `contact:<id>` as `to` would dispatch an SMS to a cuid; the route
                        // answers the unwrapped address and the reply channel for this hand-off.
                        // ⚠️ No title: iOS passes none either, and the route's counterpart is an
                        // address rather than the display name the list would show.
                        threadRoute = { thread ->
                            Routes.thread(
                                workspaceId = link.workspaceId,
                                role = role,
                                threadKey = thread.threadKey,
                                replyTo = thread.counterpart,
                                replyChannel = thread.channel,
                            )
                        },
                    )
                    navController.navigate(route)
                }
            }
        }
    }
}
