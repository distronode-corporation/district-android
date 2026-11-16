package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.NativeReauthAction
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the account-security client puts on the wire: the same routes, bodies and action names as
 * district-core-swift's `nativeReauth*` and `mfa*` endpoints.
 *
 * ⛔ AND THE ONE 401 THAT IS NOT THE SESSION. `reauth/password` answers 401 `MFA_REQUIRED` to a
 * correct password on an account with two-step verification; read as the session's 401 it would
 * send the password twice and lose the code.
 */
class AccountSecurityRequestTest {

    private lateinit var server: MockWebServer
    private lateinit var refreshApi: FakeRefreshApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        refreshApi = FakeRefreshApi().apply { rotating() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(): AccountSecurityApi = HttpAccountSecurityApi(testApiClient(server, refreshApi))

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    private fun bodyOf(recorded: mockwebserver3.RecordedRequest): JsonObject =
        Json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject

    private val proof = """{"success":true,"action":"2fa-change","expiresInSeconds":300}"""

    @Test
    fun `the sso exchange posts the four camelCase keys`() = runTest {
        server.enqueue(ok(proof))

        val result = api().nativeReauthSso(NativeReauthAction.TWO_FACTOR_CHANGE, "c-1", "v-1", "districtai://auth")

        assertTrue(result is ApiResult.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/auth/native/reauth/sso", recorded.url.encodedPath)
        assertEquals(
            JsonObject(
                mapOf(
                    "action" to JsonPrimitive("2fa-change"),
                    "code" to JsonPrimitive("c-1"),
                    "codeVerifier" to JsonPrimitive("v-1"),
                    "redirectUri" to JsonPrimitive("districtai://auth"),
                ),
            ),
            bodyOf(recorded),
        )
    }

    @Test
    fun `the password proof leaves the code out until there is one`() = runTest {
        server.enqueue(ok(proof))
        server.enqueue(ok(proof))

        api().nativeReauthPassword(NativeReauthAction.TWO_FACTOR_CHANGE, "pw", null)
        api().nativeReauthPassword(NativeReauthAction.DELETE_ACCOUNT, "pw", "123456")

        val first = server.takeRequest()
        assertEquals("/api/auth/native/reauth/password", first.url.encodedPath)
        assertEquals(setOf("action", "password"), bodyOf(first).keys)
        val second = bodyOf(server.takeRequest())
        assertEquals(JsonPrimitive("delete-account"), second["action"])
        assertEquals(JsonPrimitive("123456"), second["code"])
    }

    @Test
    fun `MFA_REQUIRED is the request's answer, not the session's, and the password goes once`() = runTest {
        server.enqueue(
            MockResponse(
                code = 401,
                body = """{"error":"Enter the code from your authenticator app.","code":"MFA_REQUIRED"}""",
            ),
        )

        val result = api().nativeReauthPassword(NativeReauthAction.TWO_FACTOR_CHANGE, "pw", null)

        assertEquals(
            ApiResult.HttpFailure(401, "Enter the code from your authenticator app.", "MFA_REQUIRED"),
            result,
        )
        assertEquals("the password is sent once", 1, server.requestCount)
    }

    @Test
    fun `a challenge named only in the error-code header is the request's answer too`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"error":"Enter the code from your authenticator app."}""")
                .addHeader(ApiErrorEnvelope.CODE_HEADER, "MFA_REQUIRED")
                .build(),
        )

        val result = api().nativeReauthPassword(NativeReauthAction.TWO_FACTOR_CHANGE, "pw", null)

        assertEquals(
            ApiResult.HttpFailure(401, "Enter the code from your authenticator app.", "MFA_REQUIRED"),
            result,
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `any other 401 on the password route is still the session's`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"Unauthorized","code":"unauthorized"}"""))
        server.enqueue(MockResponse(code = 401, body = """{"error":"Unauthorized","code":"unauthorized"}"""))

        val result = api().nativeReauthPassword(NativeReauthAction.TWO_FACTOR_CHANGE, "pw", null)

        assertEquals(ApiResult.Unauthorized(reason = null), result)
        assertEquals("refreshed and retried once, as every route", 2, server.requestCount)
    }

    @Test
    fun `a 401 whose body cannot be read is the session's, never a crash`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"x","code":"MFA_REQUIRED"}"""))
        server.enqueue(MockResponse(code = 401, body = """{"error":"x","code":"MFA_REQUIRED"}"""))
        val client = DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val real = chain.proceed(chain.request())
                    real.body.close()
                    real.newBuilder().body(UnreadableBody()).build()
                }
                .build(),
            tokens = signedInCoordinator(refreshApi),
        )

        val result = HttpAccountSecurityApi(client).nativeReauthPassword(NativeReauthAction.DELETE_ACCOUNT, "pw", null)

        assertTrue(result is ApiResult.NetworkFailure)
    }

    @Test
    fun `a challenge code on another route is still the session's 401`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"x","code":"MFA_REQUIRED"}"""))
        server.enqueue(MockResponse(code = 401, body = """{"error":"x","code":"MFA_REQUIRED"}"""))

        val result = api().mfaDisable()

        assertEquals(ApiResult.Unauthorized(reason = null), result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the status is a GET and the three changes are bodyless or carry the totp only`() = runTest {
        server.enqueue(ok("""{"enrolled":false,"enrolledAt":null,"backupCodesRemaining":0,"canDisable":true}"""))
        server.enqueue(
            ok(
                """{"secret":"AAAABBBBCCCCDDDD","otpauthUri":"otpauth://totp/x",""" +
                    """"qrDataUrl":"data:image/png;base64,AA=="}"""
            )
        )
        server.enqueue(ok("""{"enrolled":true,"backupCodes":["aaaa-bbbb"]}"""))
        server.enqueue(ok("""{"enrolled":false}"""))

        val status = api().mfaStatus()
        api().mfaEnroll()
        api().mfaConfirm("654321")
        api().mfaDisable()

        assertTrue(status is ApiResult.Success)
        val read = server.takeRequest()
        assertEquals("GET", read.method)
        assertEquals("/api/auth/mfa", read.url.encodedPath)

        val enroll = server.takeRequest()
        assertEquals("POST", enroll.method)
        assertEquals("/api/auth/mfa/enroll", enroll.url.encodedPath)
        assertTrue("no body: the route reads none", enroll.body?.utf8().isNullOrEmpty())

        val confirm = server.takeRequest()
        assertEquals("POST", confirm.method)
        assertEquals("/api/auth/mfa/confirm", confirm.url.encodedPath)
        assertEquals(JsonObject(mapOf("totp" to JsonPrimitive("654321"))), bodyOf(confirm))

        val disable = server.takeRequest()
        assertEquals("POST", disable.method)
        assertEquals("/api/auth/mfa/disable", disable.url.encodedPath)
        assertTrue("no body and no password: the proof stands in for it", disable.body?.utf8().isNullOrEmpty())
    }
}

/** A response body that fails on its first read, as a socket dying after the headers does. */
private class UnreadableBody : ResponseBody() {
    override fun contentType(): MediaType = "application/json".toMediaType()

    override fun contentLength(): Long = -1L

    override fun source(): BufferedSource = object : Source {
        override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("socket closed")

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }.buffer()
}
