package com.distronode.districtai.core.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The app's single OkHttpClient.
 *
 * ⛔ SHARE ONE INSTANCE ACROSS AUTH AND API CALLS. An OkHttpClient owns a connection pool and
 * a thread pool; constructing one per call site (which the login screen originally did, with a
 * bare `OkHttpClient()`) means no connection reuse, a fresh TLS handshake per request, and
 * several idle thread pools. On a phone that is measurable in both latency and battery.
 */
object DistrictHttp {

    /**
     * ⛔ A CALL TIMEOUT IS SET DELIBERATELY, BECAUSE OKHTTP HAS NONE BY DEFAULT. The connect,
     * read and write timeouts each bound one PHASE, so a response that trickles bytes just
     * inside the read timeout indefinitely never trips any of them — the request simply never
     * finishes. `callTimeout` bounds the whole thing, which is what a UI waiting on a spinner
     * actually needs.
     *
     * ⚠️ A multipart upload runs on a clone with this budget scaled to its body; see [forUpload].
     */
    private const val CALL_TIMEOUT_SECONDS = 30L

    /**
     * Kept below [CALL_TIMEOUT_SECONDS] so a stalled phase fails with a phase-specific error
     * rather than the generic call timeout, which is more useful in a crash report.
     */
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // ⛔ RETRY IS OFF, AND THE COMMENT HERE USED TO ARGUE THE OPPOSITE ON TWO FALSE PREMISES.
        // It said retry was "safe here only because every route this client calls is a GET" and
        // that OkHttp "does not resend a request that reached the server". Both were wrong:
        //
        //   1. This is the app's ONE client, shared by design (see above), so it carries the
        //      refresh POST and the code-exchange POST from core-auth (AppContainer wires
        //      NativeAuthApi to this very instance) and every mutation in DistrictApiClient.send,
        //      including the billable messages/send.
        //   2. OkHttp's RetryAndFollowUpInterceptor.recover() bails out on a post-send failure
        //      only for a ONE-SHOT request body. Ours are byte-array backed, so they are not
        //      one-shot, and a connection reset AFTER the request was fully transmitted is
        //      retried — silently, below every layer that could know about it.
        //
        // Together those meant a fully-sent refresh whose connection died before the response
        // was re-sent automatically: two presentations of one refresh token inside a single
        // `refreshApi.refresh()` call, which the coordinator's durable pending-marker cannot see
        // because it never learns a second request happened. The server treats that as theft and
        // revokes the whole token family — every device signed out. The same mechanism re-POSTs
        // messages/send, turning one tap into two billed SMS.
        //
        // ⚠️ The cost of false is that a genuinely transient connection failure now surfaces as an
        // error instead of being retried invisibly. That is the right trade for this app: every
        // screen already owns a retry affordance (pull-to-refresh, the Retry button, Paging's
        // retry), so a GET is one tap from recovery, whereas a replayed POST is unrecoverable.
        .retryOnConnectionFailure(false)
        .build()
}

/**
 * A clone of this client whose call timeout also covers sending [bytes] at a slow uplink.
 *
 * ⛔ THE SHARED [DistrictHttp] BUDGET IS SIZED FOR A JSON READ, NOT FOR A 5 MB BODY. Every multipart
 * route (MMS attachments, the desk logo, scheduling images) accepts up to 5 MiB, and 5 MiB in 30
 * seconds needs about 1.4 Mbit/s of uplink. Below that the upload timed out every time, and the
 * retry timed out identically, so a photo could not be attached on a weak cellular signal at all.
 *
 * ⚠️ SCALED, NOT LIFTED. Removing the call timeout for uploads would bring back the trickle the
 * shared one exists to stop (see [DistrictHttp]); the write timeout bounds only one stalled write,
 * not a body crawling just above it. So the shared budget stays and gains one second per
 * [UPLOAD_FLOOR_BYTES_PER_SECOND] of body: about three minutes more for the 5 MiB cap.
 *
 * ⚠️ A PER-CALL CLONE, the way `redirectTarget` builds its no-redirects client. `newBuilder()`
 * shares the connection pool and dispatcher, and every other request keeps the shared budget.
 *
 * ⚠️ ZERO STAYS ZERO. It is OkHttp's "no call timeout", and scaling it would invent a limit the
 * caller never set.
 */
internal fun OkHttpClient.forUpload(bytes: Long): OkHttpClient {
    if (callTimeoutMillis == 0) return this
    val extraMillis = bytes * MILLIS_PER_SECOND / UPLOAD_FLOOR_BYTES_PER_SECOND
    return newBuilder()
        .callTimeout(callTimeoutMillis + extraMillis, TimeUnit.MILLISECONDS)
        .build()
}

/** The slowest uplink an upload is budgeted for: 32 KiB/s, about 256 kbit/s. */
private const val UPLOAD_FLOOR_BYTES_PER_SECOND = 32L * 1024

private const val MILLIS_PER_SECOND = 1_000L
