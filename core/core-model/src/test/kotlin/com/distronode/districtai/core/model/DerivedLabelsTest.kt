package com.distronode.districtai.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The values the app derives from a row rather than reading off it: a label, a flag, a handle.
 *
 * Each is a small rule a screen relies on without restating, so each is held here against every
 * input shape it has to survive, including the ones no committed fixture happens to carry.
 */
class DerivedLabelsTest {

    private fun contact(name: String = "Ada", socialHandles: JsonObject? = null) = Contact(
        id = "c1",
        workspaceId = "ws-1",
        name = name,
        socialHandles = socialHandles,
        createdAt = "2026-09-20T12:00:00.000Z",
    )

    @Test
    fun `a contact named blank or Unknown has no display name to show`() {
        assertEquals("Ada", contact().displayName)
        assertNull("a blank name is no name", contact(name = "  ").displayName)
        // ⚠️ The voice agent writes the literal "Unknown" for an unidentified caller.
        assertNull("the agent's placeholder is no name", contact(name = "Unknown").displayName)
    }

    @Test
    fun `the LinkedIn handle is read only from a non-blank string under its key`() {
        fun handle(value: JsonElement) =
            contact(socialHandles = JsonObject(mapOf("linkedin" to value))).linkedinHandle

        assertEquals("ada-lovelace", handle(JsonPrimitive("ada-lovelace")))
        assertNull("no column at all", contact().linkedinHandle)
        assertNull(
            "a column without the key",
            contact(socialHandles = JsonObject(mapOf("x" to JsonPrimitive("ada")))).linkedinHandle,
        )
        // ⚠️ The column is untyped JSON, so every non-string shape must read as absent, not throw.
        assertNull("a nested object", handle(JsonObject(emptyMap())))
        assertNull("an array", handle(JsonArray(listOf(JsonPrimitive("ada")))))
        assertNull("an explicit null", handle(JsonNull))
        assertNull("a number", handle(JsonPrimitive(42)))
        assertNull("a blank string", handle(JsonPrimitive(" ")))
    }

    @Test
    fun `a conversation is titled by its contact's name, or by its counterpart when there is none`() {
        val thread = ConversationSummary(threadKey = "contact:c1", counterpart = "+14165550142")

        assertEquals("+14165550142", thread.displayName)
        assertEquals("+14165550142", thread.copy(contactName = "").displayName)
        assertEquals("Ada Lovelace", thread.copy(contactName = "Ada Lovelace").displayName)
    }

    @Test
    fun `only a call with the missed direction is a missed call`() {
        val missed = TimelineEvent(id = "e1", type = "call", direction = "missed")

        assertTrue(missed.isMissedCall)
        assertFalse("an answered call is not missed", missed.copy(direction = "inbound").isMissedCall)
        // ⚠️ `missed` on a message type is not a call event at all.
        assertFalse("a message is never a missed call", missed.copy(type = "sms").isMissedCall)
    }

    @Test
    fun `a directory row missing either its name or its number is incomplete`() {
        val full = DirectoryEntry.newEntry("Night desk", "+14165550100")

        assertFalse(full.incomplete)
        assertTrue("no name", full.with(DirectoryField.NAME, " ").incomplete)
        assertTrue("no number", full.with(DirectoryField.PHONE_NUMBER, "").incomplete)
    }
}
