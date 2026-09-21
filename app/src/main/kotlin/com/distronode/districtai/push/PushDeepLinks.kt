package com.distronode.districtai.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tap on a message notification, waiting for a graph that can act on it.
 *
 * ⛔ A HOLDER EXISTS BECAUSE THE INTENT ARRIVES **BEFORE** ANYTHING CAN NAVIGATE. `MainActivity`
 * receives it in `onCreate`/`onNewIntent`, at which point the navigation graph may not be composed
 * at all and — more to the point — the active workspace is not known: it comes from
 * `GET /api/district/workspace/list` plus `GET /api/district/overview`, which are in flight. So the
 * activity records the intent and the graph consumes it when it has what it needs. Navigating from
 * the activity instead would mean guessing a route from state that has not loaded.
 *
 * ⛔ AND IT IS PROCESS-SCOPED, NOT ACTIVITY-SCOPED. A cold start from a notification is exactly the
 * case where the activity is created, the read starts, and a rotation or a configuration change
 * destroys the activity mid-flight; an activity field would lose the deep link precisely on the path
 * it exists for.
 *
 * ⚠️ ONE PENDING LINK AT A TIME, LAST ONE WINS. Two notifications tapped in quick succession are one
 * intent each, and the second is the one the user is looking at. There is no queue because there is
 * no sensible way to honour the first afterwards — it would navigate away from the screen the user
 * just asked for.
 */
class PushDeepLinks {

    private val _pending = MutableStateFlow<InboxDeepLink?>(null)

    /** ⚠️ Null when there is nothing outstanding, which is almost always. */
    val pending: StateFlow<InboxDeepLink?> = _pending.asStateFlow()

    fun offer(link: InboxDeepLink) {
        _pending.value = link
    }

    /**
     * ⛔ CALLED ON **EVERY** RESOLUTION, INCLUDING THE ONES THAT NAVIGATE NOWHERE. A link for a
     * workspace that is not the active one is dropped (see [inboxDeepLinkDecision]) and must be
     * cleared with it — a pending link that survived would re-fire the moment the user switched to
     * that workspace for their own reasons, minutes later, and yank them into the inbox.
     */
    fun clear() {
        _pending.value = null
    }
}

/**
 * The workspace a message arrived in, and the message.
 *
 * ⛔ THE WORKSPACE ALONE IS NOT ENOUGH TO OPEN THE CONVERSATION. There is no client-side way to
 * turn a message id into a thread key without a request, and that request exists:
 * `GET /api/district/messages/[id]` exchanges the id for the thread selectors (the iOS client
 * opens the conversation the same way). See `resolveMessageDeepLinkRoute`.
 *
 * ⛔ [messageId] IS THE ONLY THING THE PAYLOAD MAY CARRY FOR THIS. `threadKey` is `addr:<address>`
 * whenever the thread has no `Contact` row, i.e. a customer's raw phone number or email address on a
 * lock screen, readable by the OS and by any notification-listener app. Widening the push to save
 * the round trip reintroduces exactly that disclosure.
 *
 * @param messageId null or blank lands on the inbox list. ⚠️ `pushIntentAction` never produces
 *   that (it ignores a message push without an id), so the null case is the resolver's defence
 *   rather than a live path.
 */
data class InboxDeepLink(val workspaceId: String, val messageId: String? = null)

/**
 * What to do with a pending deep link, given what the app currently knows.
 *
 * ⛔ A PURE FUNCTION SO THE THREE CASES ARE TESTABLE, AND BECAUSE `DistrictNavHost` IS ALREADY AT
 * detekt'S CYCLOMATIC CEILING. Lambdas inside a `composable {}` block count toward the enclosing
 * function's complexity, which is why the rooms, dialler and settings destinations are already
 * separate builders; putting a three-way branch inline would push it over for a decision that has
 * nothing to do with navigation plumbing.
 */
internal sealed interface DeepLinkDecision {

    /**
     * The workspace has not resolved yet. ⛔ WAIT RATHER THAN DROP: on a cold start from a
     * notification this is the state for the whole of the first two reads, and dropping here would
     * make the deep link work only when the app was already open — i.e. never in the case it exists
     * for.
     */
    data object Wait : DeepLinkDecision

    /**
     * The link is for a workspace that is not the active one.
     *
     * ⛔ DROPPED RATHER THAN SWITCHED, AND THAT IS A DELIBERATE LIMITATION RATHER THAN A BUG. The
     * active workspace is explicit client state that the user chose; a notification silently
     * re-pointing the whole app at another tenant — mid-task, from a lock screen — is a worse
     * outcome than landing on the overview. The notification did its job: it said something arrived.
     * ⚠️ Switching workspace from a deep link is a product decision, not a gap to fill.
     */
    data object Drop : DeepLinkDecision

    /**
     * The link is for the active workspace: act on it.
     *
     * ⛔ IT CARRIES THE LINK RATHER THAN A FINISHED ROUTE. The destination is the THREAD, and reaching
     * it needs a suspending network exchange, which does not belong in a pure three-way branch. This
     * function decides WHETHER to act; `resolveMessageDeepLinkRoute` decides where.
     */
    data class Go(val link: InboxDeepLink) : DeepLinkDecision
}

/**
 * @param pending the outstanding link, or null.
 * @param workspaceId the ACTIVE workspace, once the overview has resolved one.
 */
internal fun inboxDeepLinkDecision(
    pending: InboxDeepLink?,
    workspaceId: String?,
): DeepLinkDecision = when {
    pending == null -> DeepLinkDecision.Wait
    // ⚠️ NOT YET KNOWN, so nothing can be decided. See [DeepLinkDecision.Wait].
    workspaceId == null -> DeepLinkDecision.Wait
    pending.workspaceId != workspaceId -> DeepLinkDecision.Drop
    else -> DeepLinkDecision.Go(pending)
}
