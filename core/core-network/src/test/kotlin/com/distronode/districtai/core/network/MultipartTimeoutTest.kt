package com.distronode.districtai.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The upload's call timeout, against a real socket.
 *
 * ⛔ ONE SHARED CALL TIMEOUT USED TO COVER A 5 MB MULTIPART BODY AS WELL AS A JSON READ, so an
 * attachment on an uplink below about 1.4 Mbit/s timed out every time and the retry timed out the
 * same way. The shared client here gets a deliberately tiny budget that a slow answer overruns: an
 * ordinary read must still trip it, and the upload must not.
 */
class MultipartTimeoutTest {

    @Serializable
    data class Payload(val value: String)

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

    private val shared = OkHttpClient.Builder()
        .callTimeout(SHARED_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    private fun client() = DistrictApiClient(
        baseUrl = server.url("/"),
        httpClient = shared,
        tokens = signedInCoordinator(FakeRefreshApi().apply { rotating() }),
    )

    private fun slowAnswer() = MockResponse.Builder()
        .code(200)
        .body("""{"value":"ok"}""")
        .headersDelay(SERVER_DELAY_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    @Test
    fun `an upload outlasts the shared call timeout that an ordinary read still trips`() = runTest {
        server.enqueue(slowAnswer())
        server.enqueue(slowAnswer())
        val api = client()

        val read = api.get(listOf("thing"), Payload.serializer())
        val upload = api.sendMultipart(
            segments = listOf("upload"),
            serializer = Payload.serializer(),
            fields = emptyMap(),
            fileName = "roof.png",
            contentType = "image/png",
            bytes = ByteArray(UPLOAD_BYTES),
        )

        assertTrue("the shared budget still bounds a read, got $read", read is ApiResult.NetworkFailure)
        assertEquals(ApiResult.Success(Payload("ok")), upload)
    }

    @Test
    fun `the upload budget grows with the payload and leaves the shared client alone`() {
        val upload = shared.forUpload(UPLOAD_BYTES.toLong())

        assertEquals(SHARED_TIMEOUT_MILLIS.toInt(), shared.callTimeoutMillis)
        assertTrue(upload.callTimeoutMillis > shared.callTimeoutMillis)
        assertTrue(
            "a bigger body gets a bigger budget",
            shared.forUpload(UPLOAD_BYTES * 2L).callTimeoutMillis > upload.callTimeoutMillis,
        )
    }

    @Test
    fun `a client with no call timeout keeps having none`() {
        // ⚠️ Zero is OkHttp's "unbounded", so scaling it would INVENT a limit that was never set.
        assertEquals(0, OkHttpClient().forUpload(UPLOAD_BYTES.toLong()).callTimeoutMillis)
    }

    private companion object {
        const val SHARED_TIMEOUT_MILLIS = 500L
        const val SERVER_DELAY_MILLIS = 1_000L

        /** 128 KiB: a few seconds of budget at the assumed floor rate, far above the delay. */
        const val UPLOAD_BYTES = 128 * 1024
    }
}
