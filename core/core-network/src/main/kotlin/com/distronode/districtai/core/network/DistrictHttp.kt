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
