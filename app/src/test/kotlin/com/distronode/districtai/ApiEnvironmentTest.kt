package com.distronode.districtai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM unit test — no Android framework, no Robolectric.
 *
 * These assertions are not ceremony. Each one pins a property that something later
 * in the build genuinely depends on, and each would fail silently and confusingly
 * if it broke.
 */
class ApiEnvironmentTest {

    @Test
    fun `base url is https`() {
        // Cleartext would be blocked at runtime anyway (no networkSecurityConfig
        // opt-in), but the failure surfaces as an opaque connection error deep in
        // OkHttp rather than as a wrong constant.
        assertTrue(
            "API base URL must be https, was ${ApiEnvironment.baseUrl}",
            ApiEnvironment.baseUrl.startsWith("https://"),
        )
    }

    @Test
    fun `base url has no trailing slash`() {
        // Retrofit joins a base URL and a relative path without collapsing a double
        // slash, and `https://host//api/district/calls` is a 404 that looks exactly
        // like a missing route.
        assertFalse(
            "API base URL must not end in '/', was ${ApiEnvironment.baseUrl}",
            ApiEnvironment.baseUrl.endsWith("/"),
        )
    }

    @Test
    fun `pkce redirect uri matches the server allowlist entry exactly`() {
        // ⛔ The server's NATIVE_REDIRECT_ALLOWLIST is a literal-equality check and
        // its own comment forbids relaxing it to a prefix or regex. A mismatch here
        // fails as an opaque `invalid_grant`, indistinguishable from a replayed
        // code, so this test is the only cheap place the drift gets caught.
        assertEquals("districtai://auth", ApiEnvironment.PKCE_REDIRECT_URI)
    }
}
