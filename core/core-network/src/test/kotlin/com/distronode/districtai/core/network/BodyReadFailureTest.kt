package com.distronode.districtai.core.network

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * A response whose status line arrived and whose BODY then failed to read.
 *
 * WHY THIS NEEDS ITS OWN HARNESS. The status and headers come from a real MockWebServer exchange;
 * only the body stream is swapped, by an application interceptor, for one that throws on the
 * first read. A socket dying between the headers and the body is real (a mobile handover does it),
 * but it cannot be staged deterministically over loopback, and a flaky version of this test would
 * be worse than none.
 *
 * WHAT IS AT STAKE. [DistrictApiClient] reads the body on the success path and again on every
 * non-2xx, and both reads are guarded for the same reason: a throw there must become a result,
 * never a crash, and on the error path the status code is already in hand and must still be
 * reported. The three cases below are the three guards.
 */
class BodyReadFailureTest {

    @Serializable
    data class Payload(val value: String)

    private val thingPath = listOf("api", "district", "thing")

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

    /** A client whose every response body throws [failure] on its first read. */
    private fun clientFailingBodiesWith(failure: Exception) = DistrictApiClient(
        baseUrl = server.url("/"),
        httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val real = chain.proceed(chain.request())
                real.body.close()
                real.newBuilder().body(FailingBody(failure)).build()
            }
            .build(),
        tokens = signedInCoordinator(FakeRefreshApi().apply { rotating() }),
    )

    @Test
    fun `a success body that dies mid-read is a network failure carrying the cause`() = runTest {
        val died = IOException("stream reset after headers")
        server.enqueue(MockResponse(code = 200, body = """{"value":"ok"}"""))

        val result = clientFailingBodiesWith(died).get(thingPath, Payload.serializer())

        // The cause is the only place "the socket died mid-body" survives as a diagnosis.
        assertSame(died, (result as ApiResult.NetworkFailure).cause)
    }

    @Test
    fun `an error body that dies mid-read is a network failure, not a crash`() = runTest {
        val died = IOException("stream reset after headers")
        server.enqueue(MockResponse(code = 500, body = """{"success":false,"error":"boom"}"""))

        val result = clientFailingBodiesWith(died).get(thingPath, Payload.serializer())

        assertSame(died, (result as ApiResult.NetworkFailure).cause)
    }

    @Test
    fun `an error body that throws a runtime fault still reports its status and the fault's type`() = runTest {
        // Production wiring cannot currently produce this: the client reads each body once, on the
        // IO dispatcher. A body already consumed, or a read on the main thread, would throw a
        // RuntimeException rather than an IOException, and the client guards against that anyway
        // so a future caller cannot turn it into a crash; this holds that guard. Such a fault does
        // not invalidate the status code, so the 503 is still reported, and the exception's type
        // is carried in the message because HttpFailure has no cause.
        server.enqueue(MockResponse(code = 503, body = """{"success":false,"error":"busy"}"""))

        val result = clientFailingBodiesWith(IllegalStateException("closed"))
            .get(thingPath, Payload.serializer())

        assertEquals(
            ApiResult.HttpFailure(503, "${ApiErrorEnvelope.FALLBACK_MESSAGE} (IllegalStateException)", null),
            result,
        )
    }
}

/** A JSON body whose stream throws [failure] on the first read. */
private class FailingBody(private val failure: Exception) : ResponseBody() {
    override fun contentType(): MediaType = "application/json".toMediaType()

    override fun contentLength(): Long = -1L

    override fun source(): BufferedSource = object : Source {
        override fun read(sink: Buffer, byteCount: Long): Long = throw failure

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }.buffer()
}
