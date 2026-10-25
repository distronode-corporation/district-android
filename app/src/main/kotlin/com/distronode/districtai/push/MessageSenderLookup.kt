package com.distronode.districtai.push

import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.InboxExtrasApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The sender's display name for a message notification, or null.
 *
 * ⛔ FETCHED UNDER THIS APP'S OWN SESSION AFTER THE PUSH ARRIVES, NEVER CARRIED IN IT. The payload
 * stays identifiers only; `GET messages/{id}/sender` answers a name, or `null` for a thread with no
 * named contact, and never the address, so the shade can show "New message · Avery" and still never
 * show a phone number.
 *
 * ⛔ EVERY FAILURE IS NULL, AND NULL IS THE GENERIC TITLE THE APP SHOWED BEFORE. Offline, signed
 * out, a viewer's 403, a 404, a decode failure, a blank name: the notification is still posted,
 * with the old wording. A name is a nicety; a message alert that never appears is a bug.
 *
 * ⛔ BOUNDED BY [timeoutMillis] (4 s, inside the iOS extension's ~5 s budget), because the
 * notification waits for it. A push is handled on the application scope with a ~10 second budget
 * on a backgrounded app, and a lookup that could wait as long as the HTTP client's own 30 second
 * call timeout would let the process be reclaimed before anything was drawn. At the bound the
 * caller gets null and the lookup is cancelled: the request itself is aborted (the client's
 * `await` is cancellable), and one still waiting for its bearer never sends.
 *
 * ⛔ THE BOUND WAITS ON A DEFERRED, NOT ON THE CALL, BECAUSE A TOKEN REFRESH CANNOT BE CANCELLED.
 * A push to a backgrounded app is exactly when the memory-only access token has expired, so this
 * lookup is usually the caller that starts the refresh, and `TokenRefreshCoordinator` runs the
 * rotation in `NonCancellable` (abandoning it half way signs the user out). A `withTimeoutOrNull`
 * around the call itself cancels it at the bound and then WAITS for that rotation to finish, up
 * to OkHttp's 30 s. So the call runs as a [kotlinx.coroutines.Deferred] outside the timeout's
 * job, the timeout only awaits it, and it is cancelled when [name] returns: the rotation carries
 * on to its persisted end for whoever else is waiting on it, and nothing else outlives the bound.
 */
internal class MessageSenderLookup(
    private val api: InboxExtrasApi,
    /** ⚠️ Injectable so a test does not wait four seconds. See the class doc for the value. */
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {

    suspend fun name(workspaceId: String, messageId: String): String? {
        // ⚠️ The caller's context without its Job: the same dispatcher, but not a child, so
        // neither the timeout nor the caller has to join it once it is cancelled.
        val lookup = CoroutineScope(currentCoroutineContext().minusKey(Job)).async {
            when (val result = api.messageSender(workspaceId, messageId)) {
                is ApiResult.Success -> result.value.name?.trim()?.takeIf { result.value.success && it.isNotEmpty() }
                is ApiResult.Failure -> null
            }
        }
        // ⛔ `finally`, so a caller cancelled before the bound (the push's own budget) also stops it.
        return try {
            withTimeoutOrNull(timeoutMillis) { lookup.await() }
        } finally {
            lookup.cancel()
        }
    }

    internal companion object {
        const val TIMEOUT_MILLIS: Long = 4_000L
    }
}
