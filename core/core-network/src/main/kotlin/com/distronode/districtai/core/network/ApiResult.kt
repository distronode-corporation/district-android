package com.distronode.districtai.core.network

import com.distronode.districtai.core.auth.ReauthReason
import java.io.IOException

/**
 * The outcome of one API call.
 *
 * ⛔ WHY THIS IS NOT `Result<T>` OR A NULLABLE RETURN. The failures this API produces are not
 * interchangeable, and collapsing them loses information the UI must act on differently:
 * [Unauthorized] means route to login, [RateLimited] means keep the session and wait,
 * [RegionsDegraded] means the answer is INCOMPLETE and must never be drawn as an empty
 * account, and [Forbidden] means this role genuinely cannot do this. A single "it failed"
 * would force every screen to guess, and the cheapest wrong guess — treat failure as empty —
 * is the one that already caused a real incident on the web side.
 *
 * ⚠️ EVERY FAILURE HERE IS A NORMALISED VIEW OF THREE DIFFERENT SERVER ENVELOPES. The same
 * logical error arrives shaped three ways depending on which layer produced it — a route's
 * own handler answers `{success:false,error}`, the shared auth guard answers a bare
 * `{error}`, and the newer helpers answer `{error,code}`. Callers should never parse a body
 * themselves; see [ApiErrorEnvelope].
 */
sealed interface ApiResult<out T> {

    data class Success<out T>(val value: T) : ApiResult<T>

    /** Marker for everything that is not a 2xx with a decodable body. */
    sealed interface Failure : ApiResult<Nothing>

    /**
     * The session is gone and cannot be recovered without signing in again.
     *
     * Raised either because the token coordinator refused to produce a token (with a
     * [reason]) or because the server rejected a freshly-refreshed one — a 401 that survived
     * one retry, which is what a server-side revocation looks like: a password change makes
     * `isSessionRevoked` true and every request 401s with a signature-valid token.
     *
     * ⚠️ [ReauthReason.InterruptedRefresh] is NOT a security event, even though it signs the
     * user out. It means the process died mid-refresh, so the stored token is presumed spent
     * and re-sending it would trigger a family revocation and a misleading replay warning in
     * the server log. Word the prompt as a routine re-login.
     */
    data class Unauthorized(val reason: ReauthReason?) : Failure

    /**
     * HTTP 403. This role is not admitted to this route — in practice a `viewer` attempting
     * a mutation, which the server excludes from every write.
     *
     * ⚠️ Reaching this is not necessarily a bug in the UI's role gating. The role can change
     * server-side between the list being fetched and the action being taken.
     */
    data class Forbidden(val message: String) : Failure

    /**
     * HTTP 404.
     *
     * ⚠️ INCLUDES "User has no workspace", which the auth guard answers with 404 rather than
     * 403 — so this is not only "wrong URL". An account with no workspace at all is a
     * distinct, legitimate state that needs its own screen.
     */
    data class NotFound(val message: String) : Failure

    /**
     * HTTP 429, or a refresh that was itself throttled.
     *
     * ⛔ THE SESSION IS INTACT. DO NOT SIGN THE USER OUT. The server rate-limits BEFORE it
     * rotates a refresh token, so a throttled refresh provably never consumed the
     * credential. Its own comment sizes those limits to tolerate "several devices behind one
     * NAT" — an office where staff phones share an egress IP is exactly when this fires, and
     * signing them out would be the wrong response to the product working as designed.
     *
     * @param refreshThrottled true when the throttling stopped the token refresh rather than
     *   the endpoint itself, i.e. the request was never sent. Same user-facing handling
     *   ("try again shortly"); worth distinguishing in logs because the remedies differ.
     */
    data class RateLimited(val message: String, val refreshThrottled: Boolean = false) : Failure

    /**
     * HTTP 503 with `code: "REGIONS_DEGRADED"` — one or more regional databases did not
     * answer, so the request could not be completed.
     *
     * ⛔ ITS OWN CASE SPECIFICALLY SO IT CANNOT BE RENDERED AS AN EMPTY RESULT. "We could not
     * look" and "there is nothing" are different answers, and showing the second when the
     * first is true reads to the user as account loss, and can route a subscribed customer
     * to a checkout page. The account is unchanged; retrying later is the correct advice.
     */
    data class RegionsDegraded(
        val message: String,
        val degradedRegions: List<String>,
    ) : Failure

    /** Any other non-2xx: a 400-class client error, or a 5xx. */
    data class HttpFailure(
        val status: Int,
        val message: String,
        /** The machine-readable `code`, when the response carried one. */
        val code: String? = null,
    ) : Failure

    /**
     * The request never got an answer: no connectivity, DNS, timeout, TLS.
     *
     * ⚠️ ALSO CARRIES "SOMETHING ELSE ANSWERED INSTEAD OF DISTRICT AI" — see
     * [NonJsonResponseException]. That is a connectivity fault wearing an HTTP 200, so it
     * belongs here rather than in [DecodeFailure]; inspect [cause] to tell them apart.
     */
    data class NetworkFailure(val cause: IOException) : Failure

    /**
     * A 2xx whose body did not match the DTO.
     *
     * ⛔ THIS MEANS CONTRACT DRIFT, AND IT SHOULD BE NOISY IN DEBUG BUILDS. It is the failure
     * the committed contract fixtures exist to make impossible, so seeing one in the field
     * means a server shape changed without the fixture and DTO being regenerated together.
     * Distinguishing it from [NetworkFailure] is the point: "you are offline" is the wrong
     * message and would hide a shipped incompatibility behind a retry button.
     *
     * @param bodyPreview a TRUNCATED prefix of the body, for diagnostics. ⚠️ Deliberately
     *   short and never logged wholesale — these responses carry customer call transcripts
     *   and contact details.
     */
    data class DecodeFailure(val cause: Throwable, val bodyPreview: String) : Failure
}

/**
 * A 2xx that did not come from District AI at all — the body was not JSON.
 *
 * ⛔ THIS IS WHAT A CAPTIVE PORTAL LOOKS LIKE, AND IT USED TO BE REPORTED AS
 * [ApiResult.DecodeFailure] — i.e. as "update the app". Hotel, airport and conference wifi
 * intercepts the request and answers 200 with an HTML login page, on EVERY screen at once.
 * `DecodeFailure` renders "District AI sent a response this version of the app does not
 * understand. Updating the app should fix it." and offers no retry, so the user was told to
 * reinstall a working app to fix a network they had not signed in to yet. The remedy is to
 * open a browser and accept the portal, which is connectivity advice.
 *
 * ⛔ AN [IOException] SUBTYPE DELIBERATELY, SO IT TRAVELS AS [ApiResult.NetworkFailure]. The
 * alternative — a new `ApiResult` case — is the shape this genuinely wants, but every
 * exhaustive `when` over the sealed hierarchy lives in a different module, so adding one is a
 * cross-module break. Reusing `NetworkFailure` keeps [ApiResult.DecodeFailure] meaning
 * exactly one thing (real contract drift, worth being noisy about) while giving this case
 * connectivity-shaped handling for free. The type is public so a caller that wants the
 * distinction can still recover it from `NetworkFailure.cause`.
 *
 * ⚠️ AN ABSENT `Content-Type` IS NOT EVIDENCE OF THIS AND MUST NOT BE TREATED AS SUCH. The
 * check that produces this only fires on a content type that is PRESENT and not a JSON
 * subtype. Every District route answers through `NextResponse.json`, so a real response
 * always carries one; a portal always says `text/html`. A stripped or absent header is
 * ambiguous, and failing closed on it would break a working app behind a header-mangling
 * proxy in exchange for nothing.
 *
 * @param contentType the offending media type, as the server sent it.
 * @param status the HTTP status it arrived with — 200 in the portal case, which is why the
 *   status alone could not have caught this.
 */
class NonJsonResponseException(
    val contentType: String,
    val status: Int,
) : IOException("Expected a JSON body but the response was $contentType (HTTP $status)")

// No `valueOrNull()` shortcut: it had no caller, because every call site branches on the failure.
