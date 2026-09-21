package com.distronode.districtai.push

import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.model.MessageThreadTarget
import com.distronode.districtai.core.network.ApiResult

/**
 * Turn the message id a push carried into the destination the notification promised.
 *
 * ⛔ THIS IS A NETWORK CALL AND THERE IS NO CLIENT-SIDE ALTERNATIVE. The payload carries identifiers
 * only, because a notification is readable by the operating system and by any notification-listener
 * app, and the value that would let the app skip this round trip, `threadKey`, is exactly the one
 * that can never be sent: it is `addr:<address>` for every thread with no `Contact` row, a customer's
 * raw phone number or email address. `GET /api/district/messages/[id]` does the exchange under this
 * app's own bearer instead (see [MessageSearchRepository.threadFor]).
 *
 * ⛔ AND IT NEVER FAILS TO A DEAD END. If the exchange cannot answer (offline, a 404 for a message
 * deleted between the push and the tap, a 409 for a row with no addressable counterpart, a 403 for a
 * member removed since, a session that expired), the operator still lands on the workspace's INBOX,
 * which is where every message tap landed before this existed and where the message is the newest
 * thing. ⚠️ This is not a failure rendered as an absence: nothing is reported as empty, the
 * destination is degraded from a thread to the list that contains it, and the list is a real screen.
 *
 * @param inboxRoute where to land when the id could not be exchanged. Takes the workspace only.
 * @param threadRoute where to land when it could.
 */
internal suspend fun resolveMessageDeepLinkRoute(
    repository: MessageSearchRepository,
    link: InboxDeepLink,
    inboxRoute: (String) -> String,
    threadRoute: (MessageThreadTarget) -> String,
): String {
    // ⚠️ NO ID, NO REQUEST. A blank id would address the route's collection path, not a message.
    val messageId = link.messageId?.takeIf { it.isNotBlank() } ?: return inboxRoute(link.workspaceId)
    return when (val result = repository.threadFor(link.workspaceId, messageId)) {
        is ApiResult.Success -> threadRoute(result.value)
        is ApiResult.Failure -> inboxRoute(link.workspaceId)
    }
}
