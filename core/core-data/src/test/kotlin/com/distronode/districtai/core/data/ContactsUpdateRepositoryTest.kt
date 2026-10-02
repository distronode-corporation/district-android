package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactMutationResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * `ContactsRepository.update`: the request shape that renaming a contact depends on.
 *
 * ⛔ THIS TEST EXISTS BECAUSE RENAMING A CONTACT WAS BROKEN IN PRODUCTION, AND THE COMMENT ON THE
 * CODE ASSERTED THE OPPOSITE. `contacts/update` is a wholesale REPLACE wearing a PATCH: the handler
 * writes `name || "Unknown"`, nulls `phoneNumber` and `email` when they are absent, and forces
 * `socialHandles`, `latestContextSummary`, `budget`, `timeline` and `website` unconditionally. The
 * repository sent only the CHANGED keys, so every rename cleared both address columns, and the route
 * refuses a contact with neither: **400 "A contact needs a phone number or an email address"**, about
 * two fields the operator had not touched.
 */
class ContactsUpdateRepositoryTest {

    private val contact = Contact(
        id = "c1",
        workspaceId = "ws-1",
        name = "Ada Lovelace",
        phoneNumber = "+14165550142",
        email = "ada@contract.test",
        socialHandles = JsonObject(mapOf("linkedin" to JsonPrimitive("in/ada"))),
        latestContextSummary = "Asked about Thursday.",
        budget = "5000",
        timeline = "Q4",
        website = "https://ada.test",
        createdAt = "2026-08-01T00:00:00.000Z",
    )

    private fun api() = FakeDistrictApi().apply {
        mutationResult = ApiResult.Success(ContactMutationResponse(success = true))
    }

    @Test
    fun `a rename carries every other column back, unchanged`() = runTest {
        val api = api()

        val result = ContactsRepository(api).update("ws-1", contact, name = "Ada L")

        assertTrue(result is ApiResult.Success)
        val sent = api.contactUpdates.single()
        assertEquals("Ada L", sent.name)
        // ⛔ THE TWO ADDRESS COLUMNS ARE THE BUG. Omitting both is the 400 that made renaming
        // impossible; omitting either is a silent deletion.
        assertEquals("+14165550142", sent.phoneNumber)
        assertEquals("ada@contract.test", sent.email)
        // ⚠️ THE FIVE NOBODY IS EDITING, for the same reason: the route overwrites them whether or not
        // they are sent, so omitting one is a deletion rather than a no-op.
        assertEquals("in/ada", sent.linkedin)
        assertEquals("Asked about Thursday.", sent.contextSummary)
        assertEquals("5000", sent.budget)
        assertEquals("Q4", sent.timeline)
        assertEquals("https://ada.test", sent.website)
    }

    @Test
    fun `the workspace and contact ids come from the call and the row`() = runTest {
        val api = api()

        ContactsRepository(api).update("ws-1", contact, name = "Ada L")

        val sent = api.contactUpdates.single()
        // ⛔ `workspaceId` IS MANDATORY ON THIS ROUTE: without it Prisma dropped the tenant filter.
        assertEquals("ws-1", sent.workspaceId)
        // ⚠️ Taken from the contact rather than passed separately, so one contact cannot be written
        // with another's id.
        assertEquals("c1", sent.contactId)
    }

    @Test
    fun `editing the phone number keeps the email, and the reverse`() = runTest {
        val api = api()
        val repository = ContactsRepository(api)

        repository.update("ws-1", contact, phoneNumber = "+14165550150")
        repository.update("ws-1", contact, email = "ada@new.test")

        val phoneEdit = api.contactUpdates[0]
        assertEquals("+14165550150", phoneEdit.phoneNumber)
        assertEquals("ada@contract.test", phoneEdit.email)
        assertEquals("Ada Lovelace", phoneEdit.name)

        val emailEdit = api.contactUpdates[1]
        assertEquals("ada@new.test", emailEdit.email)
        assertEquals("+14165550142", emailEdit.phoneNumber)
    }

    @Test
    fun `a field is sent as null only when the caller clears it`() = runTest {
        val api = api()

        ContactsRepository(api).update("ws-1", contact, email = null)

        val sent = api.contactUpdates.single()
        assertNull(sent.email)
        assertEquals("+14165550142", sent.phoneNumber)
        assertEquals("Ada Lovelace", sent.name)
        assertEquals("in/ada", sent.linkedin)
    }

    @Test
    fun `a column the row holds as null goes back as null, not an invented empty string`() = runTest {
        // ⚠️ An absent column and a cleared one are the same thing to this route, so a phone-only
        // contact round-trips correctly by omission. Pinned so nobody "fixes" it with an empty-string
        // sentinel, which the create route's own migration deliberately removed.
        val phoneOnly = contact.copy(email = null, socialHandles = null, budget = null)
        val api = api()

        ContactsRepository(api).update("ws-1", phoneOnly, name = "Ada L")

        val sent = api.contactUpdates.single()
        assertNull(sent.email)
        assertNull(sent.linkedin)
        assertNull(sent.budget)
        assertEquals("+14165550142", sent.phoneNumber)
    }

    @Test
    fun `a non-string linkedin value reads as absent rather than crashing the edit`() = runTest {
        // ⚠️ `socialHandles` is `Json?` with no server-side shape, so a row can hold an object there.
        val odd = contact.copy(
            socialHandles = JsonObject(mapOf("linkedin" to JsonObject(mapOf("url" to JsonPrimitive("x"))))),
        )
        val api = api()

        ContactsRepository(api).update("ws-1", odd, name = "Ada L")

        assertNull(api.contactUpdates.single().linkedin)
    }

    @Test
    fun `a 200 that does not affirm success is not a saved contact`() = runTest {
        // ⚠️ `{success}` IS THE ENTIRE PAYLOAD of this route, so the flag is all there is to check.
        val api = api().apply {
            mutationResult = ApiResult.Success(ContactMutationResponse(success = false))
        }

        val result = ContactsRepository(api).update("ws-1", contact, name = "Ada L")

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a 409 is surfaced rather than swallowed`() = runTest {
        // ⚠️ One contact per phone and per lowercased email per workspace, so editing onto an address
        // another contact holds is a DUPLICATE, which the operator can act on.
        val api = api().apply {
            mutationResult = ApiResult.HttpFailure(
                status = 409,
                message = "Another contact in this workspace already uses this phone number.",
            )
        }

        val result = ContactsRepository(api).update("ws-1", contact, name = "Ada L")

        assertEquals(409, (result as ApiResult.HttpFailure).status)
    }
}
