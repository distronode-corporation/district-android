package com.distronode.districtai.core.auth

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The three unauthenticated native-auth endpoints.
 *
 * ⛔ THESE ARE THE ONLY CALLS IN THE APP THAT CARRY NO BEARER TOKEN. All three sit under
 * the server's `/api/auth/` public prefix precisely because the caller has no usable session:
 * the authority is the PKCE code (exchange) or the refresh token itself (refresh, revoke).
 * They must therefore NOT go through the authenticated OkHttp client:
 * routing them through an interceptor that tries to attach an access token would deadlock,
 * because getting an access token is what these calls are for. `revoke` has a second reason:
 * a sign-out has to work when the access token is already dead.
 *
 * ⚠️ THE DEVICE-MANAGEMENT ROUTES ARE NOT HERE, AND THAT IS THE CORRECT SPLIT.
 * `/api/auth/native/devices`, `.../devices/revoke` and `.../revoke-all` share this path
 * prefix but are `requireAuth` routes taking a bearer, so they ride the ordinary
 * `DistrictApiClient` (which owns token acquisition and the envelope mapping) rather than
 * this class. The prefix is about proxy.ts's middleware, not about authentication.
 *
 * ⛔ STATUS MAPPING IS SECURITY-RELEVANT, NOT PLUMBING. Getting it wrong either signs users out
 * needlessly or, worse, retries a spent refresh token and revokes their whole token family.
 * The mapping below is derived from the routes themselves, not guessed:
 *
 *   | status | route behaviour                                    | mapped to        |
 *   |--------|----------------------------------------------------|------------------|
 *   | 200    | rotated successfully                               | Success          |
 *   | 401    | `invalid_grant` from refresh — credential is dead  | Rejected         |
 *   | 429    | rate-limited BEFORE rotation, token NOT consumed    | RateLimited      |
 *   | 400    | malformed body; token never reached rotation        | RateLimited*     |
 *   | 5xx    | may have rotated before failing — AMBIGUOUS         | TransportFailure |
 *   | IO     | never got an answer — AMBIGUOUS                     | TransportFailure |
 *
 * (*) A 400 means our own request was malformed, which is a client bug rather than a dead
 * credential. It is mapped to the retryable case because the token is provably unspent — the
 * route validates the body before touching `rotateNativeSession`. Treating it as Rejected would
 * sign the user out to punish our own serialisation bug.
 */
class NativeAuthApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RefreshApi, RevokeApi {

    /**
     * ⚠️ `ignoreUnknownKeys = true` HERE, unlike the contract fixtures which use false. This is
     * a live wire-format parser, not a drift detector: a server that adds a field to the token
     * response must not break every existing install's login. Drift detection is the contract
     * harness's job.
     */

    /** Exchange a PKCE authorization code for the first token pair. */
    suspend fun exchangeCode(request: CodeExchangeRequest): CodeExchangeResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("code", request.code)
            put("codeVerifier", request.codeVerifier)
            put("redirectUri", request.redirectUri)
            put("deviceId", request.deviceId)
            request.deviceName?.let { put("deviceName", it) }
            // The server's zod schema is z.enum(["ios","android"]) — anything else is a 400.
            put("platform", "android")
        }

        val response = runCatching { post("/api/auth/native/token", body) }.getOrNull()
            ?: return@withContext CodeExchangeResult.TransportFailure

        when (response.code) {
            HTTP_OK -> response.tokens(json)
                ?.let { CodeExchangeResult.Success(it) }
                ?: CodeExchangeResult.TransportFailure
            // invalid_grant: expired (the code lives 120s), replayed, PKCE mismatch, or a
            // redirect mismatch. All indistinguishable on purpose, and all mean "start over".
            HTTP_BAD_REQUEST -> CodeExchangeResult.Rejected
            HTTP_TOO_MANY_REQUESTS -> CodeExchangeResult.RateLimited
            else -> CodeExchangeResult.TransportFailure
        }
    }

    override suspend fun refresh(refreshToken: String): RefreshResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("refreshToken", refreshToken) }

        // ⛔ A FAILURE THAT NEVER LEFT THE DEVICE IS NOT THE SAME AS ONE THAT MIGHT HAVE ROTATED
        // THE TOKEN. See RefreshResult.NotSent: collapsing them meant an offline app-open burnt
        // the session. The exception type is the only evidence available, and it is sufficient
        // for the connect phase.
        val response = runCatching { post("/api/auth/native/refresh", body) }
            .getOrElse { failure ->
                return@withContext if (failure.isProvablyUnsent()) {
                    RefreshResult.NotSent
                } else {
                    RefreshResult.TransportFailure
                }
            }

        when (response.code) {
            HTTP_OK -> response.tokens(json)
                ?.let { RefreshResult.Success(it) }
                // A 200 we cannot parse is the worst case: the server HAS rotated the token and
                // we cannot read the successor. Ambiguous, so the marker stays set.
                ?: RefreshResult.TransportFailure
            HTTP_UNAUTHORIZED -> RefreshResult.Rejected
            HTTP_TOO_MANY_REQUESTS -> RefreshResult.RateLimited
            // See the table in the class doc: unspent token, so retryable rather than fatal.
            HTTP_BAD_REQUEST -> RefreshResult.RateLimited
            else -> RefreshResult.TransportFailure
        }
    }

    /**
     * End this device's session server-side.
     *
     * ⛔ THE MAPPING IS THE OPPOSITE SHAPE TO [refresh]'S, AND READING IT AS THE SAME THING IS
     * THE MISTAKE TO AVOID. There, a 4xx is fatal to the session and a 5xx is ambiguous; here
     * a 4xx is a SUCCESS and only a 5xx matters. The route's own header explains why: it
     * answers 200 for an unknown token on purpose ("sign-out has one job: end the session and
     * leave the client certain it may discard its credential"), and it split the 503 out
     * specifically so a failed write stops being reported as a completed sign-out.
     *
     *   | status | route behaviour                                     | mapped to  |
     *   |--------|-----------------------------------------------------|------------|
     *   | 200    | revoked, or there was nothing to revoke              | Done       |
     *   | 4xx    | malformed body, or rate-limited (429)                | Done       |
     *   | 503    | the database write THREW — token may still be live  | RetryLater |
     *   | 5xx    | same class of unknown                                | RetryLater |
     *   | IO     | never got an answer                                  | RetryLater |
     *
     * ⚠️ A 4xx IS Done RATHER THAN RetryLater, WHICH IS A DELIBERATE ASYMMETRY WITH [refresh].
     * A 400 means this client sent a body the route refuses, and a 429 means it is being
     * throttled — neither will resolve by being retried with the same token on the next
     * launch, and both leave a credential whose only remaining value is that it might still
     * work. That is real, so it is worth stating what is being traded: the outbox is for
     * "the server could not answer", not for "the server said no". Chasing a 4xx forever
     * would be an outbox entry that can never drain.
     *
     * ⛔ [RevokeResult.RetryLater] DOES NOT MEAN "KEEP THE USER SIGNED IN". The local wipe
     * happens either way — see `AppContainer.signOut`. It means the token must survive into
     * the revoke outbox so a later launch can finish the job.
     */
    override suspend fun revoke(refreshToken: String): RevokeResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("refreshToken", refreshToken) }

        val response = runCatching { post("/api/auth/native/revoke", body) }.getOrNull()
            ?: return@withContext RevokeResult.RetryLater

        // ⚠️ `isProvablyUnsent` is deliberately NOT consulted. It exists so a refresh can tell
        // an unspent token from a possibly-spent one; a revoke has no such distinction —
        // every failure to get an answer means the same thing, which is "try again later".
        if (response.code < HTTP_SERVER_ERROR) RevokeResult.Done else RevokeResult.RetryLater
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    @Throws(IOException::class)
    private fun post(path: String, body: JsonObject): RawResponse {
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            // ⚠️ Read the body INSIDE `use`. An OkHttp response body can be consumed exactly
            // once and is closed with the response; reading it afterwards throws.
            return RawResponse(response.code, response.body.string())
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Named rather than inline, because the mapping these drive is security-relevant and
        // reads better as a table. See the class doc.
        const val HTTP_OK = 200

        /** Refresh: malformed body, token unspent. Exchange: opaque `invalid_grant`. */
        const val HTTP_BAD_REQUEST = 400

        /** Refresh only: the credential is definitively dead. */
        const val HTTP_UNAUTHORIZED = 401

        /** Rate-limited BEFORE any rotation, so the presented token is provably unspent. */
        const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * The 4xx/5xx boundary, used only by [revoke].
         *
         * ⚠️ Below it the server ANSWERED, which for a sign-out is enough — see the table on
         * [revoke]. At or above it the write may not have happened, which is the only case
         * worth carrying into the outbox.
         */
        const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * One HTTP answer, read into memory.
 *
 * ⛔ TOP-LEVEL AND `private` RATHER THAN NESTED, SO THE PURE PARSER BELOW CAN BE TOO. It reads no
 * client state, so it has no reason to count against [NativeAuthApi]'s function budget, which detekt
 * caps at 11.
 */
private class RawResponse(val code: Int, val body: String)

/**
 * ⚠️ `json` IS PASSED RATHER THAN CAPTURED, which is the whole cost of this being top-level. It is
 * a constructor seam on [NativeAuthApi] (`ignoreUnknownKeys = true`, a live wire parser rather than
 * a drift detector), so threading it keeps that one instance authoritative instead of creating a
 * second parser configured differently.
 */
private fun RawResponse.tokens(json: Json): NativeTokens? = runCatching {
    val obj = json.parseToJsonElement(body) as JsonObject
    NativeTokens(
        accessToken = obj.getValue("accessToken").jsonPrimitive.content,
        // ⚠️ MILLISECONDS. The server returns ms and documents why; do not divide.
        accessTokenExpiresAt = obj.getValue("accessTokenExpiresAt").jsonPrimitive.long,
        refreshToken = obj.getValue("refreshToken").jsonPrimitive.content,
        refreshTokenExpiresAt = obj.getValue("refreshTokenExpiresAt").jsonPrimitive.long,
    )
}.getOrNull()

/**
 * The revoke seam, mirroring [RefreshApi].
 *
 * ⚠️ AN INTERFACE RATHER THAN A DIRECT [NativeAuthApi] DEPENDENCY so the sign-out orchestration
 * in `AppContainer` can be driven by a test without a socket. The concrete class is constructed
 * from `ApiEnvironment.baseUrl`, which is a `BuildConfig` constant — there is no way to point it
 * at a MockWebServer, so the seam has to be at the type.
 */
interface RevokeApi {
    suspend fun revoke(refreshToken: String): RevokeResult
}

/**
 * What a sign-out learned about the server.
 *
 * ⛔ TWO CASES AND NOT THREE, WHICH IS THE WHOLE POINT. There is no "Rejected" here: a sign-out
 * cannot fail in a way the user should hear about, because the local wipe is unconditional. The
 * only question this answers is whether the credential still needs chasing.
 */
sealed interface RevokeResult {

    /**
     * The server will not honour that token again — either it just revoked it, or it never
     * knew it, or it refused the request in a way retrying cannot fix.
     *
     * ⚠️ Deliberately does not distinguish those. The route declines to confirm whether a
     * presented token existed (it is unauthenticated by necessity), so the client could not
     * tell them apart even if it wanted to.
     */
    data object Done : RevokeResult

    /**
     * The server could not say. ⛔ The token must be written to the revoke outbox — see
     * [TokenStore.pendingRevokeToken] — because the session may still be live for the rest of
     * its 60-day window.
     */
    data object RetryLater : RevokeResult
}

/** Body of `POST /api/auth/native/token`. */
data class CodeExchangeRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    /** ⚠️ The server requires 8..200 characters. */
    val deviceId: String,
    /** Display only, shown in the settings device list. Server caps at 120 chars. */
    val deviceName: String? = null,
)

sealed interface CodeExchangeResult {
    data class Success(val tokens: NativeTokens) : CodeExchangeResult

    /** The code was expired, replayed, or failed PKCE. Start the login again. */
    data object Rejected : CodeExchangeResult

    /** Rate-limited before the code was spent; the code may still be usable briefly. */
    data object RateLimited : CodeExchangeResult

    data object TransportFailure : CodeExchangeResult
}

// ⚠️ `buildJsonObject` and `put` come from kotlinx.serialization.json and are RUNTIME APIs —
// they need no @Serializable classes and therefore no serialization compiler plugin on this
// module. An earlier version of this file hand-rolled the object construction and its string
// escaping to avoid the plugin, which was both unnecessary and a genuine hazard: a device name
// from Build.MODEL containing a quote or backslash would have produced a malformed body and a
// 400 on every login attempt. Let the library escape.

/**
 * Whether this failure proves the request never reached the network.
 *
 * ⛔ CONSERVATIVE ON PURPOSE: ANYTHING NOT LISTED IS TREATED AS AMBIGUOUS. A false positive here
 * is the dangerous direction — it would clear the pending marker for a request that DID reach the
 * server, letting a later attempt re-present a spent refresh token, which the server reads as
 * theft and answers by revoking the whole token family. A false negative merely costs the
 * re-login this classification exists to avoid.
 *
 * ⚠️ These are all connect-phase failures: name resolution, reaching the host, and completing the
 * TLS handshake all happen before a single request byte is written. [java.net.SocketTimeoutException]
 * is excluded because connect and read timeouts share it.
 *
 * ⚠️ Sound only because `DistrictHttp` sets `retryOnConnectionFailure(false)`. With OkHttp's retry
 * on, a later attempt's connect failure could surface after an earlier attempt had already sent
 * the request, and this exception type would then describe the wrong attempt.
 */
internal fun Throwable.isProvablyUnsent(): Boolean = when (this) {
    is java.net.UnknownHostException -> true
    is java.net.ConnectException -> true
    is java.net.NoRouteToHostException -> true
    is javax.net.ssl.SSLHandshakeException -> true
    else -> false
}
