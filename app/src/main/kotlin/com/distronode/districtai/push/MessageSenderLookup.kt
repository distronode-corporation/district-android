package com.distronode.districtai.push

import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.InboxExtrasApi
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
 * call timeout would let the process be reclaimed before anything was drawn. The request is
 * cancelled at the bound (the client's `await` is cancellable), not left running.
 */
internal class MessageSenderLookup(
    private val api: InboxExtrasApi,
    /** ⚠️ Injectable so a test does not wait four seconds. See the class doc for the value. */
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {

    suspend fun name(workspaceId: String, messageId: String): String? =
        withTimeoutOrNull(timeoutMillis) {
            when (val result = api.messageSender(workspaceId, messageId)) {
                is ApiResult.Success -> result.value.name?.trim()?.takeIf { result.value.success && it.isNotEmpty() }
                is ApiResult.Failure -> null
            }
        }

    internal companion object {
        const val TIMEOUT_MILLIS: Long = 4_000L
    }
}
