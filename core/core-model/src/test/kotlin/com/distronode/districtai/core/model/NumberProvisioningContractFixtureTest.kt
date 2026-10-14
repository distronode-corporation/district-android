package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything about a number except buying it, and the carrier account behind it, read from the
 * fixtures the monorepo generates from the real route handlers.
 *
 * ⛔ THESE TYPES WERE WRITTEN FROM THE ROUTE SOURCE BEFORE ANY FIXTURE EXISTED, pinned only by the
 * request tests in :core:core-network. This file is where they meet real server output for the
 * first time. Every fixture goes through [WireMirror.assertMirrors]: strict decode, then both
 * encodings held against the bytes the server sent.
 *
 * ⚠️ THE NULL BRANCHES ARE THE POINT of the second fixture of each pair: an invalid lookup with
 * nothing known about the number, a SIP trunk whose carrier resources were never recorded, a
 * workspace with no carrier at all.
 */
class NumberProvisioningContractFixtureTest {

    // ── Configure and release ────────────────────────────────────────────────

    @Test
    fun `configure and a clean release answer a bare success`() {
        val configure =
            WireMirror.assertMirrors(NumberConfigureResponse.serializer(), "district-number-configure.json")
        assertTrue(configure.success)

        val release = WireMirror.assertMirrors(NumberReleaseResponse.serializer(), "district-number-release.json")
        assertTrue(release.success)
        assertNull("a clean release carries no warnings key", release.warnings)
    }

    @Test
    fun `a release that left something behind is still a success, and says what`() {
        // ⛔ NOT A FAILURE. The number is gone; showing this as an error would invite a second
        // release of a number the workspace no longer holds.
        val release =
            WireMirror.assertMirrors(NumberReleaseResponse.serializer(), "district-number-release-warnings.json")

        assertTrue(release.success)
        assertEquals(
            listOf("The number was released, but its monthly charge could not be ended."),
            release.warnings,
        )
    }

    // ── Lookup ───────────────────────────────────────────────────────────────

    @Test
    fun `a valid lookup carries what the carrier knows`() {
        val lookup = WireMirror.assertMirrors(NumberLookupResponse.serializer(), "district-number-lookup.json")

        assertTrue(lookup.success)
        assertTrue(lookup.info.valid)
        assertEquals("+14165550142", lookup.info.phoneNumber)
        assertEquals("CA", lookup.info.country)
        assertEquals("mobile", lookup.info.type)
        assertEquals("Example Mobile", lookup.info.carrier)
    }

    @Test
    fun `an invalid lookup is still a billed success, with nothing known about the number`() {
        val lookup =
            WireMirror.assertMirrors(NumberLookupResponse.serializer(), "district-number-lookup-invalid.json")

        assertTrue("an invalid number is an answer, not a failure", lookup.success)
        assertFalse(lookup.info.valid)
        assertEquals("+15555550100", lookup.info.phoneNumber)
        assertNull(lookup.info.country)
        assertNull(lookup.info.type)
        assertNull(lookup.info.carrier)
    }

    // ── Provider status ──────────────────────────────────────────────────────

    @Test
    fun `provider status reports each carrier, managed, connected and refused`() {
        val status =
            WireMirror.assertMirrors(ProviderStatusResponse.serializer(), "district-provider-status.json")
        val providers = status.providers.orEmpty()

        assertTrue(status.connected)
        assertNull("the multi-carrier answer sends no single provider key", status.provider)
        assertEquals(listOf("telnyx", "twilio"), status.connectedProviders)
        assertEquals(listOf("sinch"), status.refusedProviders)

        val telnyx = providers.getValue("telnyx")
        assertEquals(true, telnyx.managed)
        // ⛔ A MANAGED ACCOUNT IS DISTRONODE'S: none of its details reach the workspace.
        assertNull(telnyx.accountName)
        assertNull(telnyx.balance)

        val twilio = providers.getValue("twilio")
        assertEquals("Contract Test Ltd", twilio.accountName)
        assertEquals("active", twilio.status)
        assertEquals("12.50", twilio.balance)
        assertEquals("USD", twilio.currency)
        assertEquals(1, twilio.numberCount)

        val sinch = providers.getValue("sinch")
        assertFalse(sinch.connected)
        assertEquals("Invalid credentials", sinch.error)
    }

    @Test
    fun `a workspace with no carrier answers not connected, with a null provider`() {
        val status =
            WireMirror.assertMirrors(ProviderStatusResponse.serializer(), "district-provider-status-none.json")

        assertFalse(status.connected)
        assertNull(status.provider)
        assertNull(status.providers)
        assertTrue(status.connectedProviders.isEmpty())
        assertTrue(status.refusedProviders.isEmpty())
    }

    // ── SIP trunks ───────────────────────────────────────────────────────────

    @Test
    fun `the SIP list carries a recorded trunk and one whose carrier resources were never recorded`() {
        val list = WireMirror.assertMirrors(SipTrunksResponse.serializer(), "district-sip-trunks.json")

        assertTrue(list.success)
        assertEquals(2, list.trunks.size)

        val main = list.trunks[0]
        assertEquals("mbi_sip_contract", main.id)
        assertEquals("Main office", main.name)
        assertEquals("contract-office.sip.twilio.com", main.domain)
        assertEquals(listOf("203.0.113.10", "198.51.100.0/24"), main.ipAccessControlList)
        assertEquals("SD_contract", main.domainSid)
        assertEquals("AL_contract", main.ipAclSid)
        assertEquals("active", main.status)

        val legacy = list.trunks[1]
        assertEquals("mbi_sip_legacy", legacy.id)
        assertTrue(legacy.ipAccessControlList.isEmpty())
        assertNull(legacy.domainSid)
        assertNull(legacy.ipAclSid)
    }

    @Test
    fun `a created SIP trunk comes back whole`() {
        val created = WireMirror.assertMirrors(SipTrunkCreatedResponse.serializer(), "district-sip-trunk-create.json")

        assertTrue(created.success)
        assertEquals("mbi_sip_contract", created.trunk.id)
        assertEquals("contract-office.sip.twilio.com", created.trunk.domain)
        assertEquals("SD_contract", created.trunk.domainSid)
        assertEquals("AL_contract", created.trunk.ipAclSid)
    }

    // ── Verify, A2P 10DLC and toll-free verification ─────────────────────────

    @Test
    fun `the passcode sender reads and turns on with its service, and turns off without one`() {
        val read = WireMirror.assertMirrors(VerifyServiceResponse.serializer(), "district-verify.json")
        assertTrue(read.success)
        assertTrue(read.enabled)
        assertEquals("VA_contract", read.verifyServiceSid)

        val enabled = WireMirror.assertMirrors(VerifyServiceResponse.serializer(), "district-verify-enable.json")
        assertTrue(enabled.enabled)
        assertEquals("VA_contract", enabled.verifyServiceSid)

        val disabled = WireMirror.assertMirrors(VerifyServiceResponse.serializer(), "district-verify-disable.json")
        assertTrue(disabled.success)
        assertFalse(disabled.enabled)
        assertNull("a disabled sender has no service", disabled.verifyServiceSid)
    }

    @Test
    fun `the A2P registration answers its status and the three carrier ids`() {
        val a2p = WireMirror.assertMirrors(A2PRegistrationResponse.serializer(), "district-a2p-registration.json")

        assertTrue(a2p.success)
        assertEquals("IN_PROGRESS", a2p.status)
        assertEquals("BN_contract", a2p.brandSid)
        assertEquals("MG_contract", a2p.messagingServiceSid)
        assertEquals("QE_contract", a2p.campaignSid)
    }

    @Test
    fun `the toll-free submission answers its status and its id`() {
        val tfv =
            WireMirror.assertMirrors(TollFreeVerificationResponse.serializer(), "district-tfv-submission.json")

        assertTrue(tfv.success)
        assertEquals("PENDING_REVIEW", tfv.status)
        assertEquals("HH_contract", tfv.tfvSid)
    }
}
