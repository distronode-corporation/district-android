package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup wizard's half of the contract gate: `GET /api/district/setup`.
 *
 * ⛔ A SEPARATE CLASS, like `BillingContractFixtureTest`, because `ContractFixtureTest` sits at
 * detekt's LargeClass ceiling. The strict decoder is shared through [ContractFixtures].
 *
 * ⚠️ ONE FIXTURE, AND THE OTHER BRANCHES OF [DistrictSetupResponse.needsWebSetup] ARE BUILT HERE.
 * The fixture is the real serialised body of a ca workspace mid-wizard; a pre-wizard workspace
 * (null progress) and a finished one (completedAt set) are the same shape with one value changed,
 * so they are derived from the fixture text rather than hand-written, which keeps them honest.
 */
class SetupContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private val fixture: String = ContractFixtures.read("district-setup.json")

    @Test
    fun `the setup read decodes with every key modelled`() {
        val response = json.decodeFromString<DistrictSetupResponse>(fixture)

        assertEquals("ca", response.region)
        assertEquals("VoicePro", response.tier)
        assertEquals(3, response.includedNumbers)
        assertEquals(1, response.numbersHeld)
        assertNull("a fresh ca workspace has no business facts yet", response.businessFacts)

        val progress = response.setupProgress
        assertNotNull("the fixture is a workspace IN the wizard", progress)
        assertEquals(SETUP_STEP_DONE, progress!!.steps.business)
        assertEquals(SETUP_STEP_DONE, progress.steps.number)
        assertEquals(SETUP_STEP_TODO, progress.steps.receptionist)
        assertEquals(SETUP_STEP_TODO, progress.steps.golive)
        assertEquals("2026-09-23T15:04:05.000Z", progress.paidAt)
        // ⛔ ABSENT, NOT NULL, ON THE WIRE. The server omits `completedAt` until setup finishes,
        // and the DTO's default is what makes "absent" read as "not finished".
        assertNull(progress.completedAt)
    }

    @Test
    fun `an owner mid-wizard is offered the web`() {
        assertTrue(json.decodeFromString<DistrictSetupResponse>(fixture).needsWebSetup)
    }

    @Test
    fun `a workspace from before the wizard is never offered it`() {
        val preWizard = fixture.replace(Regex("\"setupProgress\": \\{[\\s\\S]*?\\n  \\},"), "\"setupProgress\": null,")
        val response = json.decodeFromString<DistrictSetupResponse>(preWizard)

        assertNull("the derivation must actually null the progress", response.setupProgress)
        assertFalse(response.needsWebSetup)
    }

    @Test
    fun `a finished wizard is not offered again`() {
        val finished = fixture.replace(
            "\"paidAt\": \"2026-09-23T15:04:05.000Z\"",
            "\"paidAt\": \"2026-09-23T15:04:05.000Z\",\n    \"completedAt\": \"2026-09-24T09:00:00.000Z\"",
        )
        val response = json.decodeFromString<DistrictSetupResponse>(finished)

        assertEquals("2026-09-24T09:00:00.000Z", response.setupProgress?.completedAt)
        assertFalse(response.needsWebSetup)
    }
}
