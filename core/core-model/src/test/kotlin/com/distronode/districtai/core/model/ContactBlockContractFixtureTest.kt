package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocking a caller, unblocking one, and the block list, read from their generated fixtures.
 *
 * ⚠️ [HandBuiltWireTest] pinned these shapes by hand before the fixtures existed; this file holds
 * the same types against real server output, through [WireMirror.assertMirrors].
 */
class ContactBlockContractFixtureTest {

    @Test
    fun `a block answers the contact with the moment it was blocked`() {
        val block = WireMirror.assertMirrors(ContactBlockResponse.serializer(), "district-contact-block.json")

        assertTrue(block.success)
        assertEquals("contact_contract_1", block.contactId)
        assertEquals("Contract Test Caller", block.name)
        assertEquals("+14165551234", block.phoneNumber)
        assertEquals("2026-08-15T14:20:00.000Z", block.blockedAt)
    }

    @Test
    fun `an unblock answers the same contact with a null blockedAt`() {
        // ⛔ THE NULL IS THE ONLY FIELD THAT SAYS WHICH WAY THE WRITE WENT.
        val unblock = WireMirror.assertMirrors(ContactBlockResponse.serializer(), "district-contact-unblock.json")

        assertTrue(unblock.success)
        assertEquals("contact_contract_1", unblock.contactId)
        assertEquals("+14165551234", unblock.phoneNumber)
        assertNull(unblock.blockedAt)
    }

    @Test
    fun `the block list carries a caller with no number`() {
        val list = WireMirror.assertMirrors(BlockedContactsResponse.serializer(), "district-contacts-blocked.json")

        assertTrue(list.success)
        assertEquals(listOf("contact_contract_1", "contact_contract_2"), list.blocked.map { it.contactId })
        assertEquals("+14165551234", list.blocked[0].phoneNumber)
        assertEquals("2026-08-15T14:20:00.000Z", list.blocked[0].blockedAt)

        // ⚠️ A CONTACT WITHOUT A NUMBER CAN BE BLOCKED, so a row must render with none.
        assertEquals("Unknown", list.blocked[1].name)
        assertNull(list.blocked[1].phoneNumber)
        assertEquals("2026-08-14T09:00:00.000Z", list.blocked[1].blockedAt)
    }
}
