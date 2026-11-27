// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup wizard's writes and step reads, decoded from the server's own fixtures with unknown keys
 * refused: the facts PATCH, the website lookup and its refusal, the test call, the forwarding check
 * and its start, and the integrations read for an owner and for a viewer.
 *
 * ⛔ A SEPARATE CLASS, like [SetupContractFixtureTest], because `ContractFixtureTest` sits at
 * detekt's LargeClass ceiling. The strict decoder is shared through [ContractFixtures].
 */
class SetupWizardContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private inline fun <reified T> decode(name: String): T = json.decodeFromString(ContractFixtures.read(name))

    @Test
    fun `the facts patch answers the whole setup state after the write`() {
        val response = decode<DistrictSetupResponse>("district-setup-patch.json")

        assertEquals(SETUP_STEP_DONE, response.setupProgress?.steps?.receptionist)
        assertEquals(SETUP_STEP_TODO, response.setupProgress?.steps?.callers)
        val facts = assertNotNullAnd(response.businessFacts)
        assertEquals("Toronto", facts["address"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        assertEquals("closed", facts["weeklyHours"]!!.jsonObject["sun"]!!.jsonPrimitive.content)
        assertEquals(1, response.numbersHeld)
    }

    @Test
    fun `the website lookup is a draft whose every item is switched off`() {
        val response = decode<SetupLookupResponse>("district-setup-lookup.json")

        assertEquals(2, response.draft.services.size)
        assertEquals(SetupDraftService(name = "Cleaning", price = "\$120", enabled = false), response.draft.services[0])
        assertNull("a service with no price on the page has none", response.draft.services[1].price)
        assertEquals("Do you take new patients?", response.draft.faqs.single().question)
        assertTrue(response.draft.services.none { it.enabled } && response.draft.faqs.none { it.enabled })
        assertEquals(1, response.draft.dropped)
        assertEquals("ON", response.draft.address!!["region"]!!.jsonPrimitive.content)
        assertEquals(1, response.draft.weeklyHours!!["mon"]!!.jsonArray.size)
        assertEquals(SetupLookupSource(kind = "website", url = "https://example.com/"), response.source)
    }

    @Test
    fun `an unreadable website carries its reason`() {
        val refusal = decode<SetupLookupRefusal>("district-setup-lookup-unreadable.json")

        assertEquals("website_unreadable", refusal.code)
        assertEquals("host_unavailable", refusal.reason)
        assertEquals("That website could not be read.", refusal.error)
    }

    @Test
    fun `the test call answers the call's id`() {
        val response = decode<SetupTestCallResponse>("district-setup-test-call.json")

        assertTrue(response.success)
        assertEquals("CAabababababababababababababababab", response.callId)
    }

    @Test
    fun `a finished forwarding check says where the call arrived`() {
        val response = decode<ForwardingCheckResponse>("district-forwarding-check.json")

        assertTrue(response.available)
        assertEquals(4, response.attemptsLeft)
        val check = assertNotNullAnd(response.forwardingCheck)
        assertTrue(check.hasPassed)
        assertFalse(check.isPending)
        assertEquals(ForwardingCheckMode.UNANSWERED, check.mode)
        assertEquals("+14165550100", check.arrivedOn)
        assertNull(check.failure)
        assertNull(check.supersededAt)
    }

    @Test
    fun `a started forwarding check is pending and has not arrived`() {
        val response = decode<ForwardingCheckResponse>("district-forwarding-check-start.json")

        val check = assertNotNullAnd(response.forwardingCheck)
        assertTrue(check.isPending)
        assertFalse(check.hasPassed)
        assertEquals("+14165550142", check.verifierNumber)
        assertNull(check.arrivedOn)
    }

    @Test
    fun `the owner's integrations read names the calendar in use`() {
        val response = decode<ConnectionsResponse>("district-connections.json")

        assertEquals(
            listOf("google-calendar", "microsoft-calendar", "azure-ad"),
            response.connections.map(WorkspaceConnection::provider),
        )
        assertEquals("error", response.connections[1].status)
        assertEquals(CalendarResolution("google", "preference-unavailable-fallback"), response.calendar)
    }

    @Test
    fun `a viewer's integrations read has no calendar resolution`() {
        val response = decode<ConnectionsResponse>("district-connections-viewer.json")

        assertEquals(3, response.connections.size)
        assertNull(response.calendar)
    }

    @Test
    fun `the forwarding modes are the two the start route accepts`() {
        assertEquals(listOf("all", "unanswered"), ForwardingCheckMode.MODES)
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
