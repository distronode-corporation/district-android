package com.distronode.districtai.core.network

import com.distronode.districtai.core.auth.AccessToken
import com.distronode.districtai.core.auth.TokenRefreshCoordinator
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The authenticated HTTP client for the District API.
 *
 * ⛔ THE ACCESS TOKEN IS ATTACHED HERE, IN A SUSPEND FUNCTION, NOT IN AN OKHTTP INTERCEPTOR.
 * Acquiring one is a `suspend` call into [TokenRefreshCoordinator] (it may refresh, which is
 * network I/O behind a mutex), and an Interceptor's `intercept` is synchronous — wiring it
 * there would mean `runBlocking` on an OkHttp dispatcher thread, which can deadlock the pool
 * it is running on when the refresh itself needs a connection from that same pool.
 *
 * ⛔ AND NOT AN OKHTTP `Authenticator` EITHER, for a second reason: an Authenticator may only
 * answer "here is another request" or "give up", which cannot express the coordinator's
 * three-way outcome. [AccessToken.RetryLater] must keep the user signed in, and folding it
 * into "give up" would sign out every office whose phones share one NAT the moment the
 * refresh limiter trips.
 *
 * ⚠️ NO COOKIE JAR, DELIBERATELY. The web's active workspace is the httpOnly
 * `distronode_workspace_id` cookie; this client holds nothing and passes `workspaceId`
 * explicitly instead, which the server re-validates against live membership on every call.
 * Adding a jar would introduce ambient tenant state that can silently disagree with what the
 * UI is displaying.
 *
 * ⛔ EVERY PUBLIC FUNCTION HERE IS MAIN-SAFE, AND [io] IS WHAT MAKES THAT TRUE. Nothing in
 * this class used to switch dispatchers, so it all ran on the caller's — and the callers are
 * `viewModelScope` and Paging, both of which are `Dispatchers.Main.immediate`. See [io].
 */
class DistrictApiClient(
    private val baseUrl: HttpUrl,
    private val httpClient: OkHttpClient,
    private val tokens: TokenRefreshCoordinator,
    private val json: Json = DEFAULT_JSON,
    /**
     * Where the request, the body read and the decode actually happen.
     *
     * ⛔ WITHOUT THIS THE APP CRASHES ON ITS TWO BIGGEST SCREENS, AND NOT SUBTLY. `Call.await`
     * resumes on the continuation's own dispatcher, so a `viewModelScope` caller is back on
     * the MAIN thread at the suspension point — and everything after it is main-thread work
     * that has no business being there:
     *
     *   - `response.body.string()` is a BLOCKING SOCKET READ for anything okio has not
     *     already buffered, which in practice is any body over ~8 KiB. Android installs
     *     `PENALTY_DEATH_ON_NETWORK` on the main thread, so that read throws
     *     `NetworkOnMainThreadException` — a RuntimeException, which the old `catch
     *     (IOException)` did not catch, so it KILLED THE PROCESS instead of becoming an
     *     [ApiResult]. Both of the app's list screens clear that threshold on their first
     *     load: the overview embeds 8 calls with transcripts, and the call log's first page
     *     is 75 rows.
     *   - `json.decodeFromString` then parses those 75 rows on the frame clock.
     *   - `TokenRefreshCoordinator.accessToken()` loads the AndroidKeyStore and runs four
     *     AES-GCM decrypts, and its store commits with `commit()` rather than `apply()`.
     *
     * ⚠️ `Dispatchers.IO`, NOT `Default`. This is blocking I/O on a socket and on disk, which
     * is exactly the pool IO is sized for (64 threads); `Default` is sized to the core count
     * and blocking it starves genuine CPU work. The decode rides along on the same dispatcher
     * rather than hopping to `Default` for it — a second context switch per request costs more
     * than the parse saves at these payload sizes.
     *
     * ⚠️ Injected so a test can substitute a dispatcher it can observe or control. See
     * `MainThreadSafetyTest`.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Told about every 2xx that could not be decoded; see [DecodeFailureReporter].
     *
     * ⚠️ Defaulted to [DecodeFailureReporter.NONE] so a test, or any client built without one,
     * reports nothing. The app injects the Sentry-backed one.
     */
    private val decodeFailures: DecodeFailureReporter = DecodeFailureReporter.NONE,
) {

    /**
     * GET the path formed by [segments], decoding a 2xx body with [serializer].
     *
     * @param segments ONE path segment per element, e.g. `["api", "district", "overview"]`.
     *   ⛔ A LIST RATHER THAN A STRING, AND THAT IS A CORRECTNESS FIX, NOT A STYLE CHOICE.
     *   OkHttp's `addPathSegments` SPLITS its argument on "/" instead of encoding it, so
     *   interpolating a value into a path template let that value introduce new segments —
     *   including "..", which OkHttp then RESOLVES. A call id of `a/../../admin` turned
     *   `api/district/calls/{id}/transcript` into `/api/district/admin/transcript`, pointing the
     *   request at an entirely different route. Each element here goes through `addPathSegment`
     *   (singular), which percent-encodes "/" and keeps a value inside its own segment.
     * @param query parameters to append. ⚠️ Entries with a null value are DROPPED rather than
     *   sent empty: the server reads `searchParams.get("workspaceId")` and distinguishes
     *   absent (re-derive the workspace) from present-but-empty, which would fail its id
     *   pattern.
     */
    suspend fun <T> get(
        segments: List<String>,
        serializer: DeserializationStrategy<T>,
        query: Map<String, String?> = emptyMap(),
    ): ApiResult<T> = authorizedCall(
        builder = Request.Builder().url(buildUrl(baseUrl, segments, query)).get(),
        client = httpClient,
    ) { response ->
        if (response.isSuccessful) decodeBody(response, serializer) else null
    }

    /**
     * GET [path] and return the URL it REDIRECTS to, without following it.
     *
     * ⛔ THIS EXISTS BECAUSE `/api/district/calls/{id}/recording` ANSWERS 302, NOT JSON. It
     * resolves a short-lived presigned URL (or the legacy carrier-hosted copy) and redirects, so
     * that recording bytes never proxy through the app server. OkHttp follows redirects by
     * default, which means the ordinary [get] would download the ENTIRE AUDIO FILE into this
     * process just to discover where it lives — on a metered connection, for a file the media
     * player is about to fetch again itself.
     *
     * ⚠️ THE RESULT IS PERISHABLE. A presigned URL expires, so it must be resolved at the moment
     * of playback and never cached or persisted. Treat it as a one-shot handle.
     *
     * ⚠️ A call with no recording at all is a 404 with a JSON body, not a redirect, so the normal
     * error mapping still applies.
     *
     * ⛔ HTTPS ONLY, CHECKED HERE ONCE FOR EVERY CALLER. Each route that answers through this (a
     * call recording, a scheduler recording download, the scheduler sign-in) redirects to a URL
     * that carries its own credential, a presigned signature or a 60-second single-use token, and
     * every caller hands it straight to another app. A plaintext `Location` would put that
     * credential on the wire in clear, and an `intent:`, `content:` or relative one is not a
     * download at all. The routes only ever build absolute https URLs, so anything else is drift
     * (or a proxy rewriting the header) and is reported as [ApiResult.DecodeFailure]: noisy in
     * debug, not retryable, never handed onwards. Pinned by `RecordingRedirectTest`.
     *
     * ⚠️ THE REFUSED VALUE IS NEVER RETURNED OR QUOTED, not even in the preview, for the same
     * reason: it is a credential. And the accepted value is returned VERBATIM rather than as the
     * parsed [HttpUrl], whose canonical re-encoding could change the bytes a signature covers.
     */
    suspend fun redirectTarget(
        segments: List<String>,
        query: Map<String, String?> = emptyMap(),
    ): ApiResult<String> {
        // ⛔ Per-call override on a CLONE of the shared client. `newBuilder()` shares the
        // connection pool and dispatcher, so this costs nothing; mutating the shared client
        // instead would silently stop every other request in the app from following redirects.
        val noRedirects = httpClient.newBuilder().followRedirects(false).build()

        return authorizedCall(
            builder = Request.Builder().url(buildUrl(baseUrl, segments, query)).get(),
            client = noRedirects,
        ) { response ->
            val location = response.header("Location")
            when {
                // Not a redirect (e.g. the 404 for a call with no recording): fall through to the
                // ordinary error mapping by answering null.
                !response.isRedirect -> null
                location.isNullOrBlank() -> ApiResult.HttpFailure(
                    response.code,
                    "The recording location was missing from the server's response.",
                )
                // A 3xx with an https Location IS the success path here, even though it is not a 2xx.
                isHttpsAddress(location) -> ApiResult.Success(location)
                else -> ApiResult.DecodeFailure(
                    cause = IllegalStateException("the redirect target was not an https address"),
                    bodyPreview = REDIRECT_NOT_HTTPS_PREVIEW,
                )
            }
        }
    }

    /**
     * Send a request with a JSON body (or none) and decode a 2xx body with [serializer].
     *
     * ⛔ THIS API HAS NO SINGLE MUTATION CONVENTION, so the method is a parameter rather than being
     * implied. Within the contacts section alone: `create` is POST with a JSON body, `update` is
     * **PATCH** with a JSON body, and `delete` is **DELETE with query parameters and no body**. A
     * helper that assumed POST-with-body would silently not fit two of the three.
     *
     * ⚠️ MUTATIONS ARE NOT RETRIED BEYOND THE ONE 401 REFRESH. That retry re-sends the request, which
     * is safe for these routes because each is idempotent in effect (create is guarded by a unique
     * constraint that answers 409, update and delete are idempotent by nature). ⛔ Before routing a
     * NON-idempotent endpoint through here — anything that spends money, notably `messages/send`,
     * `messages/draft` and `contacts/enrich` — check that a duplicate delivery is acceptable, because
     * a 401 on the first attempt would send it twice.
     */
    suspend fun <T> send(
        method: String,
        segments: List<String>,
        serializer: DeserializationStrategy<T>,
        query: Map<String, String?> = emptyMap(),
        body: JsonElement? = null,
    ): ApiResult<T> {
        val builder = Request.Builder().url(buildUrl(baseUrl, segments, query))
        // ⚠️ An explicit empty body for methods that require one. OkHttp rejects POST/PATCH with a
        // null body, and DELETE must have NO body here because the route reads query parameters.
        //
        // ⛔ A BODYLESS POST (e.g. `closeSupportRequest`, whose route never calls `req.json()`)
        // WOULD OTHERWISE THROW `IllegalArgumentException: method POST must have a request body`
        // from OkHttp's own `Request.Builder`, before a single byte leaves the process. That reads
        // as a broken endpoint rather than as a transport gap, which is why the fallback is here
        // rather than in the caller: a caller-side `JsonObject(emptyMap())` would invent a `{}` body
        // the handler never asked for, and the invented field would be IGNORED rather than rejected.
        //
        // ⚠️ NO CONTENT-TYPE ON THE EMPTY BODY, DELIBERATELY. `toRequestBody(null)` sends
        // `Content-Length: 0` and no `Content-Type`, which is the honest wire representation of
        // "there is no body". Tagging zero bytes as `application/json` would be a lie a future
        // reader could act on.
        val requestBody = body?.let { json.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON_MEDIA_TYPE) }
            ?: EMPTY_BODY.takeIf { method in METHODS_REQUIRING_BODY }
        builder.method(method, requestBody)

        // ⛔ THE SAME `authorizedCall` THE READ USES, not a second branch here: one dispatcher hop,
        // one place the bearer is attached.
        return authorizedCall(builder = builder, client = httpClient) { response ->
            if (response.isSuccessful) decodeBody(response, serializer) else null
        }
    }

    /**
     * POST a `multipart/form-data` body: text fields plus one file part.
     *
     * ⛔ THE BYTES ARE HELD IN MEMORY, AND THAT IS WHAT MAKES THE 401 RETRY SAFE. `authorizedCall`
     * re-sends the SAME [Request.Builder] after a refresh, so the body must be readable twice.
     * `ByteArray.toRequestBody` is repeatable — it re-writes the array on every `writeTo` — whereas
     * streaming from a `ContentResolver` InputStream is one-shot and would have silently uploaded a
     * ZERO-BYTE file on the retry, which the server accepts as a 400 ("between 1 byte and 5MB")
     * rather than as the transport bug it is. The 5MB server cap bounds the cost of holding it.
     *
     * ⛔ AND IT IS DELIBERATELY NOT ROUTED THROUGH [send]. That helper encodes a JSON body from a
     * `JsonElement`; a multipart body is not JSON and cannot be expressed as one. Sharing the
     * function by making the body parameter a `RequestBody` was considered and rejected — the
     * JSON media type and `explicitNulls` reasoning on [send] would then apply to a call it does
     * not describe.
     *
     * @param fields text form fields, e.g. `workspaceId`. ⚠️ Sent as parts, NOT as query
     *   parameters: the media route reads `workspaceId` off `req.formData()`, and a query
     *   parameter would leave it undefined and answer the guard's 400.
     * @param query ⛔ THE OTHER HALF OF THAT TRAP, AND THE THREE ROUTES DISAGREE WITH EACH OTHER.
     *   `messages/media` reads `workspaceId` off `req.formData()`; `desk/logo` reads it off
     *   `new URL(req.url)` and carries NO form fields at all; and the scheduling-admin image upload
     *   reads it off the QUERY STRING on purpose, because the workspace has to be known before
     *   `req.formData()` so `requireWorkspaceRole` can run ahead of a multipart parse of a body up
     *   to Cloudflare's 100 MB, or an anonymous client can spend a 4-vCPU origin's CPU on a request
     *   that was always going to be refused. So the scope has to be expressible in either place, and
     *   a part list copied from one route to another leaves `requireWorkspaceRole` with null while
     *   the URL looks entirely correct. Defaulted to empty, so the media call is unchanged.
     * @param fileName the part's filename. ⚠️ Carried for the server's benefit only — the route
     *   stores the mime type and the byte length and never reads it — but omitting it makes the
     *   part a plain field rather than a file, and `file instanceof File` then fails.
     */
    suspend fun <T> sendMultipart(
        segments: List<String>,
        serializer: DeserializationStrategy<T>,
        fields: Map<String, String>,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
        query: Map<String, String?> = emptyMap(),
    ): ApiResult<T> {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (name, value) -> multipart.addFormDataPart(name, value) }
        multipart.addFormDataPart(
            FILE_PART_NAME,
            fileName,
            // ⚠️ `toMediaTypeOrNull`, not `toMediaType`: a device that hands back a malformed
            // content type must not throw out of a suspend function that has no catch above it.
            // A null media type sends the part without one, and the server's own allowlist then
            // rejects it with a message the operator can act on.
            bytes.toRequestBody(contentType.toMediaTypeOrNull()),
        )

        val builder = Request.Builder()
            .url(buildUrl(baseUrl, segments, query))
            .post(multipart.build())

        // ⚠️ A clone with a call timeout scaled to the body; see [forUpload].
        return authorizedCall(builder = builder, client = httpClient.forUpload(bytes.size.toLong())) { response ->
            if (response.isSuccessful) decodeBody(response, serializer) else null
        }
    }

    /**
     * Execute a request with a bearer token, retrying ONCE if the server rejects it.
     *
     * ⛔ EXACTLY ONE RETRY, AND THE BOUND MATTERS. A 401 on a token the coordinator believed
     * fresh means the session was revoked server-side (password change, deletion) or the
     * token was invalidated out from under us. Refreshing and retrying recovers the
     * recoverable case; retrying in a loop would hammer the refresh endpoint, trip its rate
     * limit, and convert a clean "please sign in again" into a 429 storm — and the refresh
     * limiter is shared per user, so it would degrade that user's other devices too.
     *
     * @param handle inspects a response that was NOT a retryable 401. Returning null means "this
     *   is not a success for me", and the ordinary failure mapping takes over — which is how the
     *   redirect path lets a 404 be treated as a 404 rather than a missing Location header.
     */
    private suspend fun <T> authorizedCall(
        builder: Request.Builder,
        client: OkHttpClient,
        handle: (Response) -> ApiResult<T>?,
    ): ApiResult<T> = withContext(io) {
        // ⛔ THE ONE PLACE THE DISPATCHER IS SWITCHED, ON PURPOSE. Every request in this class
        // funnels through here, so wrapping the three public entry points instead would be three
        // chances to forget — and the thing that gets forgotten crashes the process rather than
        // failing a test. Token acquisition, the body read and the decode are all inside it.
        //
        // ⛔ IT MUST BE `withContext`, NOT `run`. `run` is a plain inline lambda: it changes
        // NOTHING about the dispatcher, compiles without warning, and leaves every comment on
        // this class describing a switch that is not happening. That exact substitution shipped
        // once and was invisible — `withContext` stayed imported and unused, the crash it was
        // meant to fix was still live, and only a thread-name assertion caught it. If you are
        // reading this because a test is asserting on a thread name, that is why it exists.
        performAuthorizedCall(builder, client, handle)
    }

    private suspend fun <T> performAuthorizedCall(
        builder: Request.Builder,
        client: OkHttpClient,
        handle: (Response) -> ApiResult<T>?,
    ): ApiResult<T> {
        var refreshedOnce = false
        while (true) {
            val token = when (val outcome = tokens.accessToken()) {
                is AccessToken.Available -> outcome.token
                // Session intact, refresh throttled, request never sent. Not a sign-out.
                AccessToken.RetryLater -> return ApiResult.RateLimited(
                    message = REFRESH_THROTTLED_MESSAGE,
                    refreshThrottled = true,
                )
                is AccessToken.ReauthRequired -> return ApiResult.Unauthorized(outcome.reason)
            }

            val response = try {
                client.newCall(builder.header("Authorization", "Bearer $token").build()).await()
            } catch (e: IOException) {
                // ⚠️ Carries the cause rather than swallowing it: "offline" and "TLS rejected"
                // need different diagnostics, and this is the only place that distinction exists.
                return ApiResult.NetworkFailure(e)
            }

            if (response.code == HTTP_UNAUTHORIZED && !refreshedOnce) {
                response.close()
                // Only takes effect if this token is still the cached one. If another caller
                // already replaced it, the next pass simply picks up the successor.
                tokens.invalidateAccessToken(token)
                refreshedOnce = true
                continue
            }

            response.use { return handle(it) ?: mapFailure(it) }
        }
    }

    /**
     * Turn a 2xx into a value.
     *
     * ⛔ NOTHING HERE MAY THROW. This runs inside [authorizedCall]'s `withContext`, and the only
     * caller above it is a ViewModel or a Paging load — neither of which has a catch. Every
     * outcome has to become an [ApiResult] or the process dies. That is not hypothetical: the
     * body read below is a blocking socket read, and before the [io] switch existed it threw
     * `NetworkOnMainThreadException` past the old `catch (IOException)` and killed the app on
     * the overview and the call log. Hence the deliberately wide `RuntimeException` arm.
     *
     * ⚠️ `Error` IS DELIBERATELY NOT CAUGHT. An `OutOfMemoryError` on a huge body, or a
     * `StackOverflowError` on pathologically nested JSON, is not something a retry button can
     * fix and swallowing it into a "response we did not understand" would hide it from crash
     * reporting while leaving the process in an unknown state.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> decodeBody(
        response: Response,
        serializer: DeserializationStrategy<T>,
    ): ApiResult<T> {
        val body = response.body

        // ⛔ IS THIS EVEN JSON? ASKED BEFORE PARSING, BECAUSE THE ANSWER CHANGES WHAT THE USER IS
        // TOLD. A captive portal — hotel, airport, conference wifi — answers 200 with an HTML
        // login page for every request the app makes. Handing that to the parser produced
        // [ApiResult.DecodeFailure], which renders as "this version of the app does not
        // understand the response; updating should fix it" with no retry offered: a working app
        // told to reinstall itself because the user had not signed in to the wifi. `parseError`
        // a few lines below has anticipated non-JSON bodies on the ERROR path all along; this is
        // the same reasoning finally applied to the success path.
        //
        // ⚠️ Only fires on a content type that is PRESENT and not JSON. See
        // [NonJsonResponseException] for why an absent header must stay decodable.
        // OkHttp 5's Response.body is never null (an empty body stands in), so no null check here.
        val contentType = body.contentType()
        if (contentType != null && !contentType.isJsonLike()) {
            return ApiResult.NetworkFailure(
                NonJsonResponseException(contentType.toString(), response.code),
            )
        }

        // ⛔ READ THE BODY EXACTLY ONCE. An OkHttp body is a one-shot stream; reading it a second
        // time throws. Held in a var so the decode's failure preview can still quote it.
        var text = ""
        return try {
            text = body.string()
            ApiResult.Success(json.decodeFromString(serializer, text))
        } catch (e: IOException) {
            // ⚠️ Carries the cause: "socket died mid-body" and "TLS rejected" need different
            // diagnostics, and this is the only place that distinction survives.
            ApiResult.NetworkFailure(e)
        } catch (e: RuntimeException) {
            // Two unrelated things land here, and both must become a result rather than a crash:
            //   - kotlinx.serialization's SerializationException (an IllegalArgumentException
            //     subtype) for a genuine shape mismatch, which is what this case means;
            //   - a threading fault such as NetworkOnMainThreadException, which is a bug in the
            //     dispatcher wiring above rather than contract drift. Reporting it as a decode
            //     failure is the wrong MESSAGE but the right SEVERITY — it is visible, it is not
            //     silently swallowed, and it does not take the process with it.
            //
            // ⛔ BOTH ARE REPORTED, because the user cannot tell anyone which one it was: the type
            // and the path only, never [text]. See [ResponseDecodeFailure] for why not the cause.
            decodeFailures.reportSafely(e, serializer.descriptor.serialName, response.request.url.encodedPath)
            ApiResult.DecodeFailure(e, text.take(BODY_PREVIEW_CHARS))
        }
    }

    /**
     * Turn a non-2xx into a typed failure.
     *
     * ⚠️ THE BODY READ HERE IS EXACTLY AS DANGEROUS AS [decodeBody]'S and is guarded the same way,
     * because this path runs on EVERY non-2xx — a fix that covered only the success path would
     * have moved the crash to the error path rather than removing it. The status code is already
     * in hand by this point, so any failure to read the body still has something true to report.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun mapFailure(response: Response): ApiResult<Nothing> {
        val body = try {
            // Never null in OkHttp 5 (see decodeBody), so there is no absent body to default.
            response.body.string()
        } catch (e: IOException) {
            return ApiResult.NetworkFailure(e)
        } catch (e: RuntimeException) {
            // A threading fault (NetworkOnMainThreadException) or a body already consumed. Neither
            // may take the process with it, and neither invalidates the status code — so the status
            // is still reported rather than being lost to the throw.
            //
            // ⚠️ The exception TYPE is carried in the message, the same way `parseError` does it
            // just below. `ApiResult.HttpFailure` has nowhere to put a cause, and the type name is
            // the one fact that distinguishes "we ran on the wrong thread" from "the socket died"
            // when this shows up in a report.
            return ApiResult.HttpFailure(
                response.code,
                "${ApiErrorEnvelope.FALLBACK_MESSAGE} (${e.javaClass.simpleName})",
                null,
            )
        }

        val envelope = parseError(body)
        val message = envelope.error?.takeIf { it.isNotBlank() } ?: ApiErrorEnvelope.FALLBACK_MESSAGE

        return when {
            response.code == HTTP_UNAUTHORIZED -> ApiResult.Unauthorized(reason = null)
            response.code == HTTP_FORBIDDEN -> ApiResult.Forbidden(message)
            response.code == HTTP_NOT_FOUND -> ApiResult.NotFound(message)
            response.code == HTTP_TOO_MANY_REQUESTS -> ApiResult.RateLimited(message)
            // Checked on the CODE, not on the status alone: a plain 503 from an edge proxy is an
            // ordinary outage, while this one carries which regions were unreachable and means
            // the answer would have been incomplete.
            envelope.code == ApiErrorEnvelope.CODE_REGIONS_DEGRADED -> ApiResult.RegionsDegraded(
                message = message,
                degradedRegions = envelope.degradedRegions,
            )
            else -> ApiResult.HttpFailure(response.code, message, envelope.code)
        }
    }

    /**
     * Decode an error body, tolerating anything.
     *
     * ⚠️ A non-JSON body is EXPECTED here, not exceptional: an edge 502, a captive portal
     * interception, or a proxy timeout all answer HTML. Falling back to an empty envelope
     * keeps the status-code mapping working when the body is useless, which is exactly when
     * the status code is all there is.
     */
    private fun parseError(body: String): ApiErrorEnvelope =
        try {
            if (body.isBlank()) {
                ApiErrorEnvelope()
            } else {
                json.decodeFromString(ApiErrorEnvelope.serializer(), body)
            }
        } catch (e: IllegalArgumentException) {
            // Reason retained in the returned message rather than rethrown: the status code is
            // still meaningful and losing it to a parse failure would be worse.
            ApiErrorEnvelope(error = "${ApiErrorEnvelope.FALLBACK_MESSAGE} (${e.javaClass.simpleName})")
        }

    companion object {
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** Enough of a body to identify a shape mismatch, short enough not to log a transcript. */
        private const val BODY_PREVIEW_CHARS = 512

        /** ⛔ The SHAPE of a refused redirect, never its value; see [redirectTarget]. */
        private const val REDIRECT_NOT_HTTPS_PREVIEW = "RedirectTarget{scheme!=https}"

        private const val HTTPS_PREFIX = "https://"

        /**
         * ⚠️ BOTH TESTS, BECAUSE EACH ALONE LETS SOMETHING THROUGH. The prefix is what pins the
         * scheme to https and nothing else (the parse accepts `http`); the parse is what refuses a
         * prefix with no usable host behind it, such as a bare `https://`.
         */
        private fun isHttpsAddress(location: String): Boolean =
            location.startsWith(HTTPS_PREFIX, ignoreCase = true) && location.toHttpUrlOrNull() != null

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * The methods OkHttp refuses to build with a null body.
         *
         * ⚠️ THE SET IS OKHTTP'S RULE, NOT HTTP'S. `okhttp3.internal.http.HttpMethod` is what
         * actually decides, and it is internal, so the list is spelled out here rather than
         * reached into. GET, HEAD and DELETE are absent on purpose: DELETE on this client carries
         * its scope in the query string, and giving it a zero-length body would be a change to
         * every existing delete for the benefit of none.
         */
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")

        /**
         * ⚠️ SHARED AND STATELESS. A `ByteArray.toRequestBody` re-writes its array on every
         * `writeTo`, so one instance is safe across calls AND across `authorizedCall`'s
         * refresh-and-retry, which re-sends the same builder.
         */
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /**
         * ⛔ THE SERVER READS `form.get("file")` AND NOTHING ELSE. A part named `image`, `upload`
         * or `attachment` is not an error — the route simply does not find a file and answers
         * 400 "Missing file field", which reads like a client that sent no body at all.
         */
        private const val FILE_PART_NAME = "file"

        private const val REFRESH_THROTTLED_MESSAGE =
            "Too many sign-in attempts from this network. Please try again shortly."

        /**
         * ⛔ `ignoreUnknownKeys = true` HERE IS CORRECT AND IS NOT A WEAKENING OF THE CONTRACT
         * GATE. The strictness lives in the TEST: ContractFixtureTest decodes the committed
         * fixtures with `ignoreUnknownKeys = false`, so a field added server-side reds CI. The
         * SHIPPED parser is deliberately lenient so that same addition — deployed to
         * production before a user updates the app — degrades to "the app ignores a field it
         * does not know" instead of "every response fails to parse on already-installed
         * builds". Strict in the gate, lenient in the field; do not swap them.
         *
         * ⚠️ `coerceInputValues` is deliberately NOT set. It would silently substitute a
         * default when a non-nullable field arrives null, which is precisely the drift the
         * fixtures exist to surface.
         */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            // The API omits keys rather than sending explicit nulls in places; DTO defaults
            // cover those.
            explicitNulls = false
        }
    }
}

/**
 * Bridge OkHttp's callback API to a cancellable coroutine.
 *
 * ⚠️ USES `enqueue`, NOT `execute()`, AND THE SURROUNDING `withContext` DOES NOT MAKE THAT
 * INTERCHANGEABLE. [DistrictApiClient.io] exists so the body read and the decode are off the
 * main thread; it does nothing for cancellation. Only `enqueue` propagates coroutine
 * cancellation to the socket — when a screen closes mid-request, `invokeOnCancellation`
 * aborts the call instead of leaving it to complete and be discarded. `execute()` inside the
 * same `withContext` would block an IO thread until the server answered a request nobody is
 * waiting for, and on mobile that is the difference between dropping a request and paying for
 * it on a metered connection. Do not "simplify" this now that a dispatcher switch is present.
 */
/**
 * Append [segments] and [query] to [base].
 *
 * ⛔ addPathSegment (SINGULAR) for every element. The plural form SPLITS on "/", which is what
 * allowed a value to inject new segments and traverse with ".." — a call id of `a/../../admin`
 * turned `api/district/calls/{id}/transcript` into `/api/district/admin/transcript`, pointing the
 * request at an entirely different route. Never swap this for the plural form "to simplify".
 *
 * ⚠️ A TOP-LEVEL FUNCTION rather than a member, for the reason [MediaType.isJsonLike] is one: it
 * reads no state of [DistrictApiClient] beyond the base URL it is handed, and that class sits on
 * detekt's function ceiling. Moving a pure helper out is the honest answer to that; raising the
 * threshold would be the other one.
 */
private fun buildUrl(
    base: HttpUrl,
    segments: List<String>,
    query: Map<String, String?>,
): HttpUrl = base.newBuilder()
    .apply {
        segments.forEach { addPathSegment(it) }
        query.forEach { (name, value) ->
            if (value != null) addQueryParameter(name, value)
        }
    }
    .build()

/**
 * Whether a media type is one this client can hand to the JSON parser.
 *
 * ⚠️ MATCHED ON THE SUBTYPE, NOT ON THE FULL STRING. `application/json` is what the API sends, but
 * `+json` structured suffixes (`application/problem+json`) and the occasional `text/json` from a
 * proxy are all still JSON, and rejecting them would invent a captive portal where there is none.
 * Parameters like `; charset=utf-8` are already parsed off by OkHttp's [MediaType].
 *
 * ⚠️ A TOP-LEVEL EXTENSION rather than a member, because it reads no state of [DistrictApiClient]
 * and the class sits on detekt's function ceiling. Moving a pure helper out is the honest answer
 * to that; raising the threshold would be the other one.
 */
private fun MediaType.isJsonLike(): Boolean =
    subtype.equals(JSON_SUBTYPE, ignoreCase = true) ||
        subtype.endsWith(JSON_SUFFIX, ignoreCase = true)

private const val JSON_SUBTYPE = "json"

/** Structured suffix, per RFC 6839 — `application/problem+json` and friends. */
private const val JSON_SUFFIX = "+json"

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                // A cancelled call also reports failure; resuming an already-cancelled
                // continuation would throw IllegalStateException from inside OkHttp's thread.
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        },
    )
}
