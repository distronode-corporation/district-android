package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native authenticator step (server S152, 2026-10-06): the Apple leg's `MFA_REQUIRED` answer
 * and the grant `POST /api/auth/native/mfa` trades the ticket for.
 *
 * ⛔ A SEPARATE CLASS, LIKE `DeviceContractFixtureTest`, so no contract class grows past detekt's
 * LargeClass ceiling. Both fixtures go through [WireMirror.assertMirrors]: each decodes with
 * `ignoreUnknownKeys = false` AND writes back every key it read.
 */
class NativeMfaContractFixtureTest {

    private val json: Json = ContractFixtures.json

    @Test
    fun `the Apple MFA step carries the discriminator and a ticket`() {
        val response = WireMirror.assertMirrors(
            NativeMfaRequiredResponse.serializer(),
            "district-native-apple-mfa-required.json",
        )

        // ⛔ A client keys on `error`, so a regeneration that renamed it must fail here.
        assertEquals(NATIVE_MFA_REQUIRED_ERROR, response.error)
        assertEquals("MFA_REQUIRED", response.code)
        assertTrue("the ticket must not be blank", response.mfaTicket.isNotBlank())
        // The route's own floor (z.string().min(16)) on the ticket it later accepts.
        assertTrue("the ticket must be redeemable at native/mfa", response.mfaTicket.length >= 16)
        assertTrue("the expiry must be ISO 8601 UTC", response.mfaTicketExpiresAt.endsWith("Z"))
    }

    @Test
    fun `the MFA step answers with the unchanged five-key grant`() {
        val grant = WireMirror.assertMirrors(NativeTokenResponse.serializer(), "district-native-mfa.json")

        assertEquals("Bearer", grant.tokenType)
        assertTrue("the access token must not be blank", grant.accessToken.isNotBlank())
        assertTrue("the refresh token must not be blank", grant.refreshToken.isNotBlank())
        // ⛔ Milliseconds, not seconds.
        assertTrue("milliseconds, not seconds", grant.accessTokenExpiresAt > 1_000_000_000_000L)
        assertTrue(
            "the refresh token must outlive the access token",
            grant.refreshTokenExpiresAt > grant.accessTokenExpiresAt,
        )
    }

    @Test
    fun `an MFA answer without its ticket is refused rather than defaulted`() {
        // ⚠️ No defaults on purpose: a blank ticket would be posted and lost, and the person would
        // see a wrong-code error for a code they typed correctly.
        val withoutTicket = """{"error":"mfa_required","code":"MFA_REQUIRED","message":"m",""" +
            """"mfaTicketExpiresAt":"2026-08-15T14:35:00.000Z"}"""

        val failure = runCatching { json.decodeFromString<NativeMfaRequiredResponse>(withoutTicket) }
        assertTrue("a missing mfaTicket must fail to decode", failure.isFailure)
    }

    @Test
    fun `an unmodelled field on the grant is rejected rather than ignored`() {
        val withExtraField = """{"tokenType":"Bearer","accessToken":"a","accessTokenExpiresAt":1,""" +
            """"refreshToken":"r","refreshTokenExpiresAt":2,"brandNewServerField":"boom"}"""

        val failure = runCatching { json.decodeFromString<NativeTokenResponse>(withExtraField) }
        assertTrue(
            "Decoding an unknown key MUST fail; ignoreUnknownKeys is no longer false.",
            failure.isFailure,
        )
    }
}
