package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two-step verification settings and the fresh re-authentication before them, decoded strictly
 * from the service's own fixtures.
 *
 * ⛔ AND NEITHER SECRET PRINTS. The enrolment's three fields are the authenticator secret and the
 * confirmation's codes are credentials, so `toString` (what a log line or a crash report would
 * print) carries none of them.
 */
class TwoStepSettingsContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    @Test
    fun `the status decodes, on with its date and its count`() {
        val status = json.decodeFromString<MfaStatusResponse>(fixture("district-mfa-status.json"))

        assertTrue(status.enrolled)
        assertEquals("2026-10-01T12:00:00.000Z", status.enrolledAt)
        assertEquals(7, status.backupCodesRemaining)
        assertTrue(status.canDisable)
    }

    @Test
    fun `a status that is off carries no date`() {
        val status = json.decodeFromString<MfaStatusResponse>(
            """{"enrolled":false,"enrolledAt":null,"backupCodesRemaining":0,"canDisable":false}""",
        )

        assertFalse(status.enrolled)
        assertNull(status.enrolledAt)
    }

    @Test
    fun `the enrolment decodes and never prints its secret`() {
        val enrollment = json.decodeFromString<MfaEnrollResponse>(fixture("district-mfa-enroll.json"))

        assertTrue(enrollment.otpauthUri.startsWith("otpauth://totp/"))
        assertTrue(enrollment.qrDataUrl.startsWith("data:image/png;base64,"))
        assertTrue(enrollment.otpauthUri.contains(enrollment.secret))
        val printed = enrollment.toString()
        assertFalse(printed.contains(enrollment.secret))
        assertFalse(printed.contains("otpauth://"))
        assertFalse(printed.contains("data:image"))
    }

    @Test
    fun `the confirmation decodes and prints only how many codes it carries`() {
        val confirmed = json.decodeFromString<MfaConfirmResponse>(fixture("district-mfa-confirm.json"))

        assertTrue(confirmed.enrolled)
        assertEquals(listOf("aaaa-bbbb", "cccc-dddd"), confirmed.backupCodes)
        assertEquals("MfaConfirmResponse(enrolled=true, backupCodes=<2 redacted>)", confirmed.toString())
    }

    @Test
    fun `turning it off decodes`() {
        val disabled = json.decodeFromString<MfaDisableResponse>(fixture("district-mfa-disable.json"))

        assertFalse(disabled.enrolled)
    }

    @Test
    fun `a re-authentication proof decodes with the action it is for`() {
        val proof = json.decodeFromString<NativeReauthResponse>(fixture("district-native-reauth.json"))

        assertTrue(proof.success)
        assertEquals(NativeReauthAction.DELETE_ACCOUNT.wire, proof.action)
        assertEquals(300, proof.expiresInSeconds)
    }

    @Test
    fun `each fixture writes back what it decoded`() {
        WireMirror.assertMirrors(MfaStatusResponse.serializer(), "district-mfa-status.json")
        WireMirror.assertMirrors(MfaEnrollResponse.serializer(), "district-mfa-enroll.json")
        WireMirror.assertMirrors(MfaConfirmResponse.serializer(), "district-mfa-confirm.json")
        WireMirror.assertMirrors(MfaDisableResponse.serializer(), "district-mfa-disable.json")
        WireMirror.assertMirrors(NativeReauthResponse.serializer(), "district-native-reauth.json")
    }

    @Test
    fun `the two actions are the service's own names`() {
        assertEquals(
            listOf("delete-account", "2fa-change"),
            NativeReauthAction.entries.map { it.wire },
        )
    }
}
