package com.distronode.districtai.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What [DistrictApiClient] reports when a 2xx does not decode, against a real socket.
 *
 * ⛔ THE PRIVACY ASSERTIONS ARE THE POINT. A drifted response can carry a transcript, and the
 * parser's own exception message quotes its input, so each test checks that the body reaches
 * neither the report's message nor a cause.
 */
class DecodeFailureReportingTest {

    @Serializable
    data class Payload(val value: String)

    private lateinit var server: MockWebServer
    private val reports = mutableListOf<ResponseDecodeFailure>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(reporter: DecodeFailureReporter = DecodeFailureReporter { reports += it }) =
        testApiClient(server, decodeFailures = reporter)

    @Test
    fun `a shape mismatch is reported by type and path, never by its body`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"value":{"transcript":"call me at 4165550142"}}"""))

        val result = client().get(
            segments = listOf("api", "district", "calls", "c1", "transcript"),
            serializer = Payload.serializer(),
            query = mapOf("address" to "+14165550142"),
        )

        assertTrue(result is ApiResult.DecodeFailure)
        // The hazard is real: the parser's own message quotes the number from the body.
        assertTrue((result as ApiResult.DecodeFailure).cause.message.orEmpty().contains("4165550142"))
        val report = reports.single()
        assertEquals(Payload.serializer().descriptor.serialName, report.serializer)
        assertEquals("/api/district/calls/c1/transcript", report.path)
        assertNull("the parser's exception quotes the body, so it is not attached", report.cause)
        val message = report.message.orEmpty()
        assertTrue(message.contains("SerializationException") || message.contains("JsonDecodingException"))
        assertFalse(message.contains("4165550142"))
        assertTrue("the frames survive for grouping", report.stackTrace.isNotEmpty())
    }

    @Test
    fun `a decoded body and a non-JSON page are not drift and report nothing`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"value":"ok"}"""))
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.headersOf("Content-Type", "text/html"),
                body = "<html>sign in to the wifi</html>",
            ),
        )
        val client = client()

        client.get(listOf("thing"), Payload.serializer())
        client.get(listOf("thing"), Payload.serializer())

        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a reporter that throws does not turn a decode failure into a crash`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"value":42}"""))

        val result = client(reporter = { error("reporter broke") })
            .get(listOf("thing"), Payload.serializer())

        assertTrue("got $result", result is ApiResult.DecodeFailure)
    }
}
