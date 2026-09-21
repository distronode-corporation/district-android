package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.MessageSearchResponse
import com.distronode.districtai.core.model.MessageThreadResponse

/**
 * The two Inbox reads that address a MESSAGE rather than a thread.
 *
 * ⛔ THEY BELONG ON `InboxApi` AND ARE HERE ONLY BECAUSE `DistrictApi.kt` IS THE ONE GUARANTEED
 * MERGE CONFLICT while several people are adding endpoints. See [ExtraPaths]. Folding them in is a
 * mechanical move when that stops being true.
 *
 * ⚠️ BOTH ADMIT `viewer`, like every other Inbox read and unlike every Inbox write.
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
}
