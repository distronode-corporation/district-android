package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * This workspace's own support requests **with Distronode**: raising one, reading it, answering it
 * and closing it.
 *
 * ⛔ THE MIRROR IMAGE OF THE DESK, AND THE TWO MUST NEVER BE DESCRIBED WITH THE SAME NOUN.
 * `district/support/…` is the tenant writing to US; `district/desk/…` is the tenant's own customers
 * writing to THEM. Nothing here may be reused to address the desk — including the reply field,
 * which is `body` here and `message` there.
 *
 * ⛔ ALL FIVE ROUTES ADMIT `agency` AND `client` AND EXCLUDE `viewer`, READS INCLUDED. These
 * payloads are support CORRESPONDENCE rather than operational status, and a read-only seat exists
 * to watch operations. A viewer must not be offered the destination at all rather than being walked
 * into a 403.
 *
 * ⚠️ A WORKSPACE MEMBER SEES THE WHOLE WORKSPACE'S REQUESTS, not only the ones they raised. That is
 * a deliberate product decision on the server (a colleague's open ticket must not become unreachable
 * when they leave), and it is why the role list is the administrative pair rather than every member.
 *
 * ⛔ THERE IS NO REOPEN, ON PURPOSE. The desk workflow exposes no single unambiguous transition back
 * out of `done`, so the server does not offer one and guessing would move a request into a state
 * nobody chose. Replying on a closed request is the supported path.
 *
 * ## What this surface may read, and why it is not the voice rule
 *
 * ⛔ THIS SURFACE RETURNS FULL CONTENT AND THAT IS CORRECT HERE. The neighbouring voice path
 * `/api/internal/support-lookup` deliberately returns STATUS ONLY — no summary, no description, no
 * comment body — because a phone call is authenticated by CALLER ID and caller ID is spoofable.
 * These routes are not that: they run under the operator's own session bearer inside an
 * authenticated app, the workspace is proved by `requireWorkspaceRole`, and withholding the thread
 * would leave the customer unable to read the answer they came for. Do not import the voice rule
 * onto this surface, and do not export this surface's latitude onto that one.
 *
 * ⛔ AND THE IDENTITY IS NEVER TAKEN FROM USER INPUT. Nothing in this file models a requester, an
 * email address or a phone number, and no request DTO here carries one: the server derives the
 * requester from the session and hashes it, and the only caller-supplied scope is the `workspaceId`
 * the role guard then verifies. A `requesterHash` is an HMAC over a normalised identifier computed
 * server-side; it is not on the wire in either direction and must not be added to one.
 *
 * ✅ PINNED BY GENERATED FIXTURES: five `district-support-*.json` files, decoded by
 * `SupportContractFixtureTest`. See the same note on [DeskSettings].
 */
@Serializable
data class SupportMessage(
    val id: String = "",
    /** `agent` (us) or `customer` (them). A plain string so an unknown role still renders. */
    val role: String = "",
    /**
     * ⛔ A DISPLAY LABEL THE ROUTE SYNTHESISES, NOT THE HUMAN WHO WROTE IT. It is the literal
     * "Distronode Support" for an agent message and "You" for a customer one; the underlying
     * `authorName` from the desk is deliberately DROPPED server-side and never reaches this client.
     * Rendering it as a person's name would invent an attribution the payload does not carry.
     */
    val author: String = "",
    val body: String = "",
    val createdAt: String = "",
) {
    val knownRole: SupportMessageRole? get() = SupportMessageRole.fromWire(role)
}

enum class SupportMessageRole(val wire: String) {
    AGENT("agent"),
    CUSTOMER("customer"),
    ;

    companion object {
        fun fromWire(raw: String?): SupportMessageRole? =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

/**
 * One request as the list shows it.
 *
 * ⚠️ [issueKey] IS EXPLICIT NULL WHILE THE REQUEST IS UNFILED, and [statusName] then reads
 * "Received". Both are real states rather than errors: we hold the row, Atlassian does not have it
 * yet, and the request is still addressable by [id].
 */
@Serializable
data class SupportRequestSummary(
    val issueKey: String? = null,
    val id: String = "",
    val subject: String = "",
    /**
     * ⛔ THE DESK'S OWN WORD FOR THE STATE, AND IT MUST BE SHOWN AS SENT. The live workflow is
     * localised, so a client that substituted "Closed" would print English over a status Atlassian
     * spells in another language.
     */
    val statusName: String = "",
    /** `NEW` | `INDETERMINATE` | `DONE` | `PENDING`. [isResolved] is the only thing to derive. */
    val statusCategory: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val filed: Boolean = false,
    val source: String = "",
    val region: String = "",
) {
    val isResolved: Boolean get() = SupportStatusCategory.isResolved(statusCategory)
}

/**
 * One request and its whole conversation.
 *
 * ⚠️ [closeable] IS THE SERVER'S ANSWER AND NOT A ROLE DERIVATION. The desk's workflow either offers
 * no resolving transition or offers several, and in the second case picking one would decide on the
 * customer's behalf whether their request was "done" or "won't do" — so the server declines and says
 * so here. A client that offered Close anyway earns a 409 it could have avoided.
 */
@Serializable
data class SupportRequestDetail(
    val issueKey: String? = null,
    val id: String = "",
    val subject: String = "",
    val statusName: String = "",
    val statusCategory: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val filed: Boolean = false,
    val source: String = "",
    val region: String = "",
    val messages: List<SupportMessage> = emptyList(),
    val closeable: Boolean = false,
) {
    val isResolved: Boolean get() = SupportStatusCategory.isResolved(statusCategory)
}

object SupportStatusCategory {
    const val RESOLVED: String = "DONE"

    fun isResolved(category: String): Boolean = category.equals(RESOLVED, ignoreCase = true)
}

@Serializable
data class SupportRequestListResponse(
    val success: Boolean = false,
    val requests: List<SupportRequestSummary> = emptyList(),
)

@Serializable
data class SupportRequestDetailResponse(
    val success: Boolean = false,
    val request: SupportRequestDetail? = null,
)

/**
 * The create's answer, which has three mutually exclusive shapes sharing no keys.
 *
 * ⚠️ `{success:true, issueKey}` when it was filed, `{success:true, deduplicated:true}` when the
 * idempotency key was replayed, and `{success:true, pending:true}` when we hold the claim row but
 * the vendor call failed. [filing] is the only thing that should read these three.
 */
@Serializable
data class SupportRequestCreateResponse(
    val success: Boolean = false,
    val issueKey: String? = null,
    val deduplicated: Boolean? = null,
    val pending: Boolean? = null,
) {
    val filing: SupportRequestFiling
        get() = when {
            !issueKey.isNullOrEmpty() -> SupportRequestFiling.Filed(issueKey)
            deduplicated == true -> SupportRequestFiling.Deduplicated
            else -> SupportRequestFiling.Pending
        }
}

/**
 * What became of a submitted request.
 *
 * ⚠️ [Pending] IS A SUCCESS, NOT A FAILURE. The claim row is held and a human will see it; the only
 * thing missing is the key to quote. Rendering it as an error would make an operator submit again.
 */
sealed interface SupportRequestFiling {
    data class Filed(val issueKey: String) : SupportRequestFiling
    data object Deduplicated : SupportRequestFiling
    data object Pending : SupportRequestFiling
}

@Serializable
data class SupportReplyResponse(
    val success: Boolean = false,
    val message: SupportMessage? = null,
)

/**
 * ⚠️ CARRIES THE RESOLVED [statusName] AND THE CALLER SHOULD ADOPT IT — see the ⛔ on
 * [SupportRequestSummary.statusName]. It is the desk's own word for the state.
 */
@Serializable
data class SupportCloseResponse(
    val success: Boolean = false,
    val statusName: String = "",
)

/**
 * The kind of request being raised.
 *
 * ⛔ A CLOSED VOCABULARY, AND THE REASON IS NOT VALIDATION HYGIENE. The route maps the short kind
 * onto a Jira request type id server-side precisely so a caller cannot file into an arbitrary type
 * on the desk — one whose portal form we do not populate, which 400s at Atlassian AFTER the local
 * claim row already exists.
 */
enum class SupportRequestKind(val wire: String) {
    PROBLEM("problem"),
    QUESTION("question"),
    SUGGESTION("suggestion"),
    ;

    companion object {
        fun fromWire(raw: String?): SupportRequestKind? =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

/**
 * A request being raised.
 *
 * ⛔ THE PAYLOAD IS EXACTLY `kind`, `subject`, `message` AND THE KEY, AND NOTHING MAY BE ADDED TO
 * IT. This is backed by a real Atlassian service desk, where `requestFieldValues` may only carry
 * the fields the REQUEST TYPE exposes on its portal form and an unknown field is a hard 400 rather
 * than an ignored key — the failure that cost every ticket the platform tried to file
 * (`The field 'labels' is not valid for this request type 'Problem'`). Do not add a field here
 * because it looks available.
 */
data class SupportRequestDraft(
    val kind: SupportRequestKind,
    val subject: String,
    val message: String,
)

/** ⚠️ The route's own bounds, so a form can stop where the server does. */
object SupportBounds {
    const val SUBJECT_MIN: Int = 3
    const val SUBJECT_MAX: Int = 200
    const val MESSAGE_MIN: Int = 1
    const val MESSAGE_MAX: Int = 10_000
}
