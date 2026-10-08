package com.distronode.districtai.core.auth

import java.io.File
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The live token parser in [NativeAuthApi], fed the SERVER's recorded grant.
 *
 * ⛔ WHY THIS IS NOT THE SAME CHECK AS `NativeMfaContractFixtureTest`. That one holds a strict DTO
 * to the fixture; this one proves the parser installed apps actually run reads it. Every native
 * route that signs a device in (`token`, `apple` and, since server S152, `mfa`) answers with one
 * five-key body built by one server function, and `district-native-mfa.json` is that body. The
 * hand-written bodies in [NativeAuthApiTest] would keep passing against a server that renamed a
 * key; this fixture would not.
 */
class NativeGrantFixtureTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NativeAuthApi

    private val grant: String by lazy {
        val dir = System.getProperty("district.contracts.dir")
        assertTrue(
            "district.contracts.dir is unset; configured in core/core-auth/build.gradle.kts.",
            !dir.isNullOrBlank(),
        )
        val file = File(dir!!, "district-native-mfa.json")
        assertTrue("missing grant fixture ${file.absolutePath}", file.isFile)
        file.readText()
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = NativeAuthApi(baseUrl = server.url("/").toString().trimEnd('/'), client = OkHttpClient())
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the code exchange reads the recorded grant with millisecond expiries intact`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(grant).build())

        val result = api.exchangeCode(
            CodeExchangeRequest("c", "v".repeat(43), "districtai://auth", "device-abcdefgh"),
        )

        assertTrue("the recorded grant must parse, was $result", result is CodeExchangeResult.Success)
        val tokens = (result as CodeExchangeResult.Success).tokens
        assertEquals("access-contract-token", tokens.accessToken)
        assertEquals(1_786_804_800_000L, tokens.accessTokenExpiresAt)
        assertEquals("refresh-contract-token", tokens.refreshToken)
        assertEquals(1_791_988_200_000L, tokens.refreshTokenExpiresAt)
    }
}
