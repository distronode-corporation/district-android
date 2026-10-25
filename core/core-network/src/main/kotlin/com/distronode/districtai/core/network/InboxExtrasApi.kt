package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.MessageSearchResponse
import com.distronode.districtai.core.model.MessageSenderResponse
import com.distronode.districtai.core.model.MessageThreadResponse

/**
 * The three Inbox reads that address a MESSAGE rather than a thread.
 *
 * ⛔ THEY BELONG ON `InboxApi` AND ARE HERE ONLY BECAUSE `DistrictApi.kt` IS THE ONE GUARANTEED
 * MERGE CONFLICT while several people are adding endpoints. See [ExtraPaths]. Folding them in is a
 * mechanical move when that stops being true.
 *
 * ⚠️ [searchMessages] AND [messageThread] ADMIT `viewer`, like every other Inbox read and unlike every
 * Inbox write. [messageSender] does not; see its own note.
 */
interface InboxExtrasApi {

    /**
     * Full-content search across the whole `Message` table for the workspace.
     *
     * ⛔ NOT THE CONVERSATION LIST FILTERED. That list scans a bounded window of recent messages
     * and groups them; this route queries every row, body AND email subject, case-insensitive. A
     * client that filtered the loaded conversations instead would silently answer "no matches" for
     * messages the workspace definitely has — which is the failure `partial` exists to caption in
     * the first place.
     *
     * @param query sent as `q`. ⚠️ The server TRIMS it before measuring its two-character floor, so
     *   a caller mirroring that floor must measure the trimmed value or it will send requests the
     *   route answers empty.
     */
    suspend fun searchMessages(workspaceId: String, query: String): ApiResult<MessageSearchResponse>

    /**
     * One message id exchanged for the thread it belongs to.
     *
     * ⛔ THE RESOLVER A MESSAGE PUSH CANNOT DO WITHOUT, and the reason the push payload carries a
     * `messageId` rather than a `threadKey`: a threadKey is `addr:<address>` whenever the thread
     * has no `Contact` row, which would put a customer's phone number on a lock screen.
     *
     * ⚠️ THE WORKSPACE IS A QUERY PARAMETER AND IS WORTH SENDING EVEN THOUGH THE ROUTE WOULD FALL
     * BACK. Omitted, the guard picks the caller's active workspace, which on a multi-tenant account
     * is a different tenant from the one the push named — and the answer would then 404 for a
     * message that exists.
     */
    suspend fun messageThread(workspaceId: String, messageId: String): ApiResult<MessageThreadResponse>

    /**
     * The display name of the message's sender, for a notification's title.
     *
     * ⛔ READ UNDER THIS APP'S OWN SESSION, WHEN A MESSAGE PUSH ARRIVES, AND NEVER CARRIED IN THE
     * PUSH. The payload stays identifiers only; the name is fetched by a request attributable to the
     * signed-in person, and the route answers `name: null` rather than an address when the thread
     * has no named contact, so a number or an email address never reaches the lock screen.
     *
     * ⚠️ `agency` AND `client` ONLY: a viewer gets a 403, and the notification keeps its generic
     * title. The server no longer pushes message alerts to viewers, so that is a race, not a path.
     *
     * @param messageId a PATH SEGMENT arriving from a push, percent-encoded like [messageThread]'s.
     */
    suspend fun messageSender(workspaceId: String, messageId: String): ApiResult<MessageSenderResponse>
}
