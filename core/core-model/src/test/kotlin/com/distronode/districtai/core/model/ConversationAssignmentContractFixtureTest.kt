package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Inbox thread-assignment read, against `district-conversation-assignments.json`.
 *
 * A class of its own for the reason [MemberContractFixtureTest] gives: `ContractFixtureTest` is at
 * detekt's LargeClass ceiling. The strict decoder is shared through [ContractFixtures].
 */
class ConversationAssignmentContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private val fixtureName = "district-conversation-assignments.json"

    @Test
    fun `the assignment list decodes`() {
        val response = json.decodeFromString<ConversationAssignmentsResponse>(ContractFixtures.read(fixtureName))

        assertEquals(true, response.success)
        assertEquals(1, response.assignments.size)
        val row = response.assignments[0]
        assertEquals("contact:contact_contract_1", row.threadKey)
        assertEquals("teammate@contract.test", row.assigneeEmail)
        // ⚠️ An ISO-8601 STRING. This module owns no date parsing.
        assertEquals("2026-10-29T12:00:00.000Z", row.assignedAt)
    }

    @Test
    fun `an assignment joins the conversation list on the same thread key`() {
        // ⛔ THE JOIN IS THE CONTRACT. The assignment list carries no thread of its own, so a key
        // that stopped matching the conversation list's would leave every owner unattached.
        val assigned = json.decodeFromString<ConversationAssignmentsResponse>(ContractFixtures.read(fixtureName))
            .assignments
            .map { it.threadKey }
        val threads = json.decodeFromString<ConversationsResponse>(ContractFixtures.read("district-conversations.json"))
            .conversations
            .map { it.threadKey }

        assertTrue("every assigned thread key must be one the conversation list carries", threads.containsAll(assigned))
    }

    @Test
    fun `who made the assignment stays off the wire`() {
        // Asserted against the RAW text, because a decoded object cannot show a key the DTO does
        // not model.
        val raw = ContractFixtures.read(fixtureName)

        assertTrue("the assigner is stored server-side and never sent", !raw.contains("assignedBy"))
        assertTrue("an id on the wire would invite a client to key on it", !raw.contains("\"id\""))
    }

    @Test
    fun `the assignment DTOs compare by value, field by field`() {
        val row = ConversationAssignment("contact:c1", "a@example.com", "2026-10-29T12:00:00.000Z")
        assertEquals(row, row.copy())
        listOf(
            row.copy(threadKey = "addr:+15550100"),
            row.copy(assigneeEmail = "b@example.com"),
            row.copy(assignedAt = null),
        ).forEach { assertTrue("each field must participate in equality", row != it) }

        val list = ConversationAssignmentsResponse(success = true, assignments = listOf(row))
        assertEquals(list, list.copy())
        assertTrue(list != list.copy(success = false))
        assertTrue(list != list.copy(assignments = emptyList()))
    }
}
