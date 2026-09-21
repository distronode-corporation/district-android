package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two CRM mutations, `contacts/update` and `contacts/delete`, read from their generated
 * fixtures.
 *
 * ⛔ BOTH ROUTES WERE UNGATED UNTIL THESE FIXTURES, AND THAT IS HOW A LIVE BUG SURVIVED IN ONE OF
 * THEM. `contacts/update` is a wholesale replace wearing a PATCH, and a client that sent only the
 * changed keys cleared both address columns on every rename. The fixture pins the RESPONSE; the
 * request's replace semantics are pinned on the website side, beside the generator.
 *
 * ⚠️ BOTH BODIES ARE A BARE `{"success": true}` AND DECODE INTO [ContactMutationResponse], whose
 * `id` is populated by `create` only. Asserting the raw key set as well as decoding is what stops a
 * key the server adds from being absorbed quietly by a field that happens to default.
 */
class ContactMutationContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    private fun assertBareSuccess(name: String) {
        assertEquals(
            "$name must be the bare success flag",
            setOf("success"),
            (json.parseToJsonElement(fixture(name)) as JsonObject).keys,
        )
        val response = json.decodeFromString<ContactMutationResponse>(fixture(name))
        assertTrue(response.success)
        // ⚠️ `id` belongs to `create`. An update or delete that started echoing one would be a new
        // contract, not a free addition.
        assertNull(response.id)
    }

    @Test
    fun `the update answers a bare success flag`() {
        assertBareSuccess("district-contact-update.json")
    }

    @Test
    fun `the delete answers the same bare success flag, pinned separately`() {
        // ⚠️ BYTE-IDENTICAL TO THE UPDATE TODAY, AND BOTH ARE DECODED ANYWAY. Two routes, two verbs,
        // two request conventions; the day either grows a field, that one fails on its own.
        assertBareSuccess("district-contact-delete.json")
    }
}
