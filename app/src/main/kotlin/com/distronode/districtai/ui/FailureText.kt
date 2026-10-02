package com.distronode.districtai.ui

import com.distronode.districtai.R
import com.distronode.districtai.core.auth.ReauthReason
import com.distronode.districtai.core.data.PagedLoadException
import com.distronode.districtai.core.network.ApiResult

/**
 * What to tell the user about a failed request.
 *
 * ⛔ ONE MAPPING, SHARED BY EVERY SCREEN, ON PURPOSE. The rule this enforces is that a failure is
 * never rendered as an absence of data — "we could not look" and "there is nothing" are different
 * answers, and showing the second when the first is true reads as account loss. That rule was
 * learned the expensive way on the web, where an unreachable region produced an empty workspace
 * list and routed a paying customer to a checkout page. Keeping the mapping in one place is what
 * stops the next screen re-deriving it and getting it wrong.
 *
 * ⚠️ Each screen still decides its own STATE — a 404 means "this account has no workspace" on the
 * overview and "this workspace is gone" in the call log — but the wording and the retryability come
 * from here.
 */
data class FailureText(
    /**
     * ⚠️ A [UiText], NOT A `String`. This mapper runs in ViewModels, which have no `Context`, so
     * holding rendered text meant every message the CLIENT authors was hardcoded English —
     * untranslatable in an app that declares `supportsRtl="true"`. Ours are now
     * [UiText.Resource]; the server's own copy stays [UiText.Literal]. See [UiText].
     */
    val message: UiText,
    /**
     * Non-empty only when the cause was specifically unreachable regions, so a screen can name them
     * instead of showing a generic failure.
     */
    val degradedRegions: List<String> = emptyList(),
    /**
     * Non-null when the session is gone and the only way forward is signing in again.
     *
     * ⚠️ Carries WHY, because the wording differs and the difference matters: a first run must not
     * say "again", and the routine process-death case must not sound like a security incident.
     */
    val signedOutCause: SignedOutCause? = null,
    /**
     * Whether offering "try again" is honest.
     *
     * ⚠️ False for a contract mismatch and for a role refusal — retrying either one produces the
     * identical failure, and a button that cannot work is worse than no button.
     */
    val retryable: Boolean = true,
)

/** Map an API failure to user-facing text. See [FailureText]. */
fun ApiResult.Failure.toFailureText(): FailureText = when (this) {
    is ApiResult.Unauthorized -> unauthorizedText(reason)

    // ⛔ Its own branch specifically so it cannot be rendered as an empty result. The account is
    // unchanged and the read simply did not complete.
    is ApiResult.RegionsDegraded -> FailureText(
        message = UiText.Resource(R.string.failure_regions_degraded),
        degradedRegions = degradedRegions,
    )

    // ⛔ SESSION INTACT. The server rate-limits BEFORE rotating a refresh token, so nothing was
    // spent, and its limits are sized to tolerate several devices behind one NAT — an office
    // sharing an egress IP is exactly when this fires. Signing them out would be the wrong
    // response to the product working as designed.
    is ApiResult.RateLimited -> FailureText(message = UiText.Literal(message))

    // ⚠️ Not retryable: the role will not change because the user pressed a button again. It can
    // change server-side, but presenting "try again" implies this attempt was unlucky.
    is ApiResult.Forbidden -> FailureText(message = UiText.Literal(message), retryable = false)

    is ApiResult.NotFound -> FailureText(message = UiText.Literal(message), retryable = false)

    is ApiResult.NetworkFailure -> FailureText(message = UiText.Resource(R.string.failure_offline))

    // ⚠️ Retryable: a 5xx shows a generic server-error string, a 4xx shows the server's own
    // sentence (see httpFailureText).
    is ApiResult.HttpFailure -> httpFailureText()

    // ⛔ CONTRACT DRIFT, NOT CONNECTIVITY. "Check your connection" would be both wrong and
    // unactionable — retrying can never fix a response shape this build cannot parse — so the
    // message points at updating the app and no retry is offered.
    is ApiResult.DecodeFailure -> FailureText(
        message = UiText.Resource(R.string.failure_unexpected_response),
        retryable = false,
    )
}

/**
 * A 401, or a coordinator that refused to produce a token.
 *
 * ⛔ EVERY VALUE OF [ReauthReason] IS A SIGN-OUT, AND ONLY THE WORDING DIFFERS. Everything
 * downstream keys on [FailureText.signedOutCause] being non-null to tear the graph down and show
 * the sign-in screen, so the cause is never null here.
 *
 * ⚠️ NOT RETRYABLE. Retrying re-sends a credential that is gone; the remedy is signing in.
 */
private fun unauthorizedText(reason: ReauthReason?): FailureText {
    val cause = when (reason) {
        ReauthReason.NoSession -> SignedOutCause.NEVER_SIGNED_IN
        // Process death mid-refresh, or a refresh whose response was lost — the next attempt
        // reports InterruptedRefresh because the marker is intentionally still set. Signs the
        // user out but is NOT a security event.
        ReauthReason.InterruptedRefresh,
        ReauthReason.RefreshUnreachable,
        -> SignedOutCause.ROUTINE
        // A definite refusal, an elapsed 60-day window, or a bare 401 from the API with no
        // reason attached (reason == null).
        ReauthReason.RefreshRejected,
        ReauthReason.RefreshTokenExpired,
        null,
        -> SignedOutCause.SESSION_INVALID
    }

    return FailureText(
        message = UiText.Resource(R.string.failure_signed_out),
        signedOutCause = cause,
        retryable = false,
    )
}

/**
 * ⛔ A 5xx BODY IS NOT SHOWABLE, AND THIS IS THE ONE LEAK IN THE MAPPING. Rendering
 * `HttpFailure.message` verbatim was fine for a 4xx, where the server authors a sentence for the
 * user ("Contact already exists"), and a data leak for a 5xx: the contacts routes return the RAW
 * exception message on an unhandled error, so a Postgres constraint name or a Prisma stack
 * fragment reaches a customer's screen — internal schema detail, in an alarming register, with
 * nothing they can act on.
 *
 * ⚠️ The route family is named in prose rather than as a glob on purpose: Kotlin block comments
 * NEST, so a slash-star sequence inside a KDoc opens an inner comment whose close then belongs to
 * it, and the rest of the file silently becomes commented out. It surfaces as "unclosed comment"
 * a hundred lines away from the cause.
 *
 * ⚠️ The 4xx branch is intentionally NOT generic. The server's own wording there is more specific
 * than anything the client could infer from a status code, and losing it would make a 409 duplicate
 * indistinguishable from a validation refusal.
 */
private fun ApiResult.HttpFailure.httpFailureText(): FailureText = FailureText(
    message = if (status >= HTTP_SERVER_ERROR) {
        UiText.Resource(R.string.failure_server)
    } else {
        UiText.Literal(message)
    },
)

/**
 * Recover the real failure from Paging's `Throwable`-shaped error channel.
 *
 * ⚠️ Paging's `LoadResult.Error` can only carry a Throwable, so `OffsetPagingSource` wraps the
 * [ApiResult] in a [PagedLoadException]. Anything that is NOT one came from Paging itself rather than
 * from the API, and is reported as a generic retryable failure rather than with a message invented
 * here.
 *
 * Shared by every paged screen so the "never render a failure as an absence of data" rule has one
 * home — see [FailureText].
 *
 * @param fallbackMessage ⚠️ A [UiText] so the caller can pass a resource. Screens used to resolve a
 *   `String` first, which meant a paged screen's fallback copy could only be English.
 */
fun Throwable.toPagedFailure(fallbackMessage: UiText): FailureText =
    (this as? PagedLoadException)?.failure?.toFailureText()
        ?: FailureText(message = fallbackMessage)

/** The 4xx/5xx boundary. Below it the server's wording is for the user; at or above it, it is not. */
private const val HTTP_SERVER_ERROR = 500
