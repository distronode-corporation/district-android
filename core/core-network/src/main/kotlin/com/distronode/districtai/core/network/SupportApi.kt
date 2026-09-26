package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.SupportCloseResponse
import com.distronode.districtai.core.model.SupportReplyResponse
import com.distronode.districtai.core.model.SupportRequestCreateResponse
import com.distronode.districtai.core.model.SupportRequestDetailResponse
import com.distronode.districtai.core.model.SupportRequestDraft
import com.distronode.districtai.core.model.SupportRequestListResponse

/**
 * This workspace's own support requests **with Distronode**.
 *
 * ⛔ NOT THE DESK. See the ⛔ on `SupportRequestSummary`: `district/support/…` is the tenant writing
 * to US, `district/desk/…` is the tenant's customers writing to THEM, and the two reply routes take
 * differently-named fields (`body` here, `message` there). Nothing in [DeskApi] may be reused here.
 *
 * ⛔ ALL FIVE CARRY `workspaceId` AS A QUERY PARAMETER and all five are `["agency","client"]`,
 * excluding `viewer` on the READS as well as the writes. A viewer must not be offered the
 * destination at all rather than being walked into a 403.
 *
 * ⛔ NO CALLER-SUPPLIED IDENTITY CROSSES THIS INTERFACE, AND NONE MAY BE ADDED. The requester is
 * derived from the session server-side and hashed; the only scope this client names is the
 * `workspaceId` that `requireWorkspaceRole` then verifies against the caller's own membership.
 * There is no parameter here for an email address, a phone number or a requester hash, and adding
 * one would turn an authenticated read into a lookup tool that takes the answer from its input.
 */
interface SupportApi {

    /**
     * Every request this workspace has raised, newest first.
     *
     * ⛔ AN EMPTY LIST IS A REAL ANSWER AND MUST NEVER RENDER AS A FAILURE — and on this surface the
     * inverse is the expensive one: the web swallowed every list failure into `[]` and told a
     * customer with three open tickets that they had none, so they stopped chasing and nobody here
     * ever saw the request. The envelope check in `SupportRepository` is what keeps the two apart.
     *
     * ⚠️ CAPPED SERVER-SIDE AT 100 ROWS AND NOT PAGED. There is no cursor and no `limit` to send.
     */
    suspend fun supportRequests(workspaceId: String): ApiResult<SupportRequestListResponse>

    /**
     * Raise a new request.
     *
     * @param idempotencyKey ⛔ **MINTED ONCE PER COMPOSED DRAFT, NEVER PER ATTEMPT**, and that is
     *   what makes this the one write on this surface a caller may repeat. The server claims the key
     *   before it calls Atlassian and answers a re-used one with `deduplicated:true`, so a retry
     *   carrying the SAME key collapses onto the first request; a retry that mints a fresh one puts
     *   a second ticket in a human's queue. Optional because an older client that omits it still
     *   works, just without that protection.
     *
     * ⚠️ RATE LIMITED 10/HOUR PER **WORKSPACE**, not per caller, plus a durable 5/day bound per
     * requester hash that survives a Redis outage. A refusal is a 429 carrying the server's own
     * sentence, which names the remedy (reply on an existing request) and should be shown verbatim.
     *
     * ⚠️ AN UNCONFIGURED DESK IS A **503**, NOT A 500, and its sentence points at the public form as
     * a PATH (`/support/report`) rather than a host, because Canada's canonical host is
     * distronode.ca. Show it as sent.
     */
    suspend fun createSupportRequest(
        workspaceId: String,
        draft: SupportRequestDraft,
        // No default: every caller passes this explicitly, so a default could never be taken.
        idempotencyKey: String?,
    ): ApiResult<SupportRequestCreateResponse>

    /**
     * One request and its whole conversation.
     *
     * ⛔ **404, NEVER 403, FOR ANOTHER WORKSPACE'S REQUEST**, and the client must not try to
     * distinguish them. The server answers "no such request", "not this workspace's request" and
     * "erased" identically so that a sequential key like `DA-41` cannot be probed by anyone with a
     * session and a loop. A client that reported the three differently would rebuild the oracle the
     * server declined to offer.
     *
     * ⚠️ IT REFRESHES FROM ATLASSIAN ON READ rather than serving the local mirror, so it is slower
     * than the list and a vendor outage degrades to the mirror instead of failing. Nothing may poll
     * it.
     *
     * @param key the Jira issue key (`DA-42`) **or** our own row id. Both resolve, which is what
     *   makes a request that has not been filed yet addressable at all.
     */
    suspend fun supportRequest(
        workspaceId: String,
        key: String,
    ): ApiResult<SupportRequestDetailResponse>

    /**
     * Answer on a request.
     *
     * ⛔ **THE FIELD IS `body`.** The desk's reply route one family over spells the same idea
     * `message`, and transposing them is a silent 400 on a screen whose whole job is to deliver a
     * sentence to a human.
     *
     * ⛔ NOT IDEMPOTENT AND NEVER RETRIED AUTOMATICALLY. The reply is posted as a PUBLIC Jira
     * comment, so a repeat leaves a second copy in the customer's own thread and notifies the agent
     * twice.
     *
     * ⚠️ **409 MEANS THE REQUEST IS STILL BEING OPENED**, which is a state rather than a fault: we
     * hold it, it has no Atlassian thread yet, and accepting the reply would silently drop the one
     * message the customer wanted us to see. It deserves its own sentence, shown verbatim.
     */
    suspend fun replyToSupportRequest(
        workspaceId: String,
        key: String,
        body: String,
    ): ApiResult<SupportReplyResponse>

    /**
     * Close a request because the customer says it is resolved.
     *
     * ⛔ **NO BODY AT ALL**, and the workspace travels in the QUERY. The handler never calls
     * `req.json()`, so a body would be ignored; sending one would be a client inventing a contract.
     *
     * ⛔ NOT IDEMPOTENT, AND THE REASON IS THE ORDER THE SERVER WORKS IN. It resolves the
     * done-category transition, then posts a PUBLIC audit comment naming who asked, then applies it
     * — the comment first, deliberately, so the attribution survives a transition that fails. A
     * repeat that still finds a transition leaves a SECOND "Closed at the requester's request by …"
     * in the customer's own thread. ⚠️ A repeat against an already-resolved request is harmless (no
     * transition is found, so it 409s before commenting) — but which of the two a retry lands on is
     * exactly what an ambiguous failure does not tell us. Never auto-retry this.
     *
     * ⚠️ **409 `not-closeable` IS AN ANSWER, NOT AN ERROR.** The desk's workflow either offers no
     * resolving transition or offers several, and in the second case picking one would decide on the
     * customer's behalf whether their request was "done" or "won't do".
     */
    suspend fun closeSupportRequest(
        workspaceId: String,
        key: String,
    ): ApiResult<SupportCloseResponse>
}
