package com.distronode.districtai.core.auth

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The failure mappings [NativeAuthApiTest] does not reach: every way the code exchange can fail
 * without a verdict, the refresh's 400, and the classification that decides whether a failed
 * refresh may have spent the token.
 *
 * WHY THE CLASSIFICATION IS PINNED TYPE BY TYPE. [isProvablyUnsent] answering true for a request
 * that DID reach the server clears the pending marker for a token the server may have rotated, and
 * the next attempt replays it: the server reads that as theft and revokes the whole token family.
 * So every connect-phase type is pinned as unsent, and everything off that list (a timeout, which
 * connect and read share; any other I/O or TLS failure) is pinned as ambiguous, which is the
 * conservative direction the function documents.
 */
class NativeAuthApiAmbiguityTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(client: OkHttpClient = OkHttpClient()) =
        NativeAuthApi(baseUrl = server.url("/").toString().trimEnd('/'), client = client)

    private val request = CodeExchangeRequest("code", "v".repeat(43), "districtai://auth", "device-abcdefgh")

    // ── Code exchange ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a code exchange that never reached a server is a transport failure, not a rejection`() = runTest {
        // A rejection tells the user their login failed; an unreachable server is a retry.
        // The client is built first: it reads the server's URL, which is not asked of a closed server.
        val api = api()
        server.close()

        assertEquals(CodeExchangeResult.TransportFailure, api.exchangeCode(request))
    }

    @Test
    fun `a code exchange answered 200 with no readable tokens is a transport failure`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"tokenType":"Bearer"}"""))

        assertEquals(CodeExchangeResult.TransportFailure, api().exchangeCode(request))
    }

    @Test
    fun `a code exchange answered 5xx is a transport failure, because the code may still be good`() = runTest {
        server.enqueue(MockResponse(code = 503, body = """{"error":"unavailable"}"""))

        assertEquals(CodeExchangeResult.TransportFailure, api().exchangeCode(request))
    }

    // ── Refresh ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a refresh answered 400 is retryable, because the token was not spent`() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"error":"invalid_request"}"""))

        assertEquals(RefreshResult.RateLimited, api().refresh("refresh-r0"))
    }

    @Test
    fun `a refresh that failed after it may have left the device is ambiguous, not unsent`() = runTest {
        // A read timeout happens after the request bytes were written, so the server may have
        // rotated the token. The failure is raised inside the real OkHttp call chain.
        val timingOut = OkHttpClient.Builder()
            .addInterceptor { throw SocketTimeoutException("timeout") }
            .build()

        assertEquals(RefreshResult.TransportFailure, api(timingOut).refresh("refresh-r0"))
    }

    // ── The classification itself ──────────────────────────────────────────────────────────

    @Test
    fun `only connect-phase failures count as provably unsent`() {
        listOf(
            UnknownHostException("no such host"),
            ConnectException("refused"),
            NoRouteToHostException("no route"),
            SSLHandshakeException("handshake failed"),
        ).forEach { failure ->
            assertTrue("${failure.javaClass.simpleName} never leaves the device", failure.isProvablyUnsent())
        }

        listOf(
            SocketTimeoutException("connect or read, indistinguishably"),
            SSLPeerUnverifiedException("hostname mismatch"),
            IOException("stream reset"),
            IllegalStateException("anything else"),
        ).forEach { failure ->
            assertFalse("${failure.javaClass.simpleName} is off the list, so ambiguous", failure.isProvablyUnsent())
        }
    }
}
