package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactDetailResponse
import com.distronode.districtai.core.model.ContactMutationResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.CreateContactRequest
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading, creating and deleting one contact.
 *
 * ⛔ EVERY RESPONSE HERE DEFAULTS `success` TO FALSE, so a structurally empty 200 decodes. What this
 * pins is that such a body, or an explicit refusal, never becomes a contact, an id or a deletion,
 * and that a blank form box reaches the wire as an ABSENT key rather than an empty string, which the
 * route's phone and email checks would refuse.
 */
class ContactsCrudRepositoryTest {

    private val created = mutableListOf<CreateContactRequest>()

    private val api = object : FakeDistrictApi() {
        override suspend fun createContact(request: CreateContactRequest): ApiResult<ContactMutationResponse> {
            created += request
            return mutationResult
        }
    }

    private val repository = ContactsRepository(api)

    private val contact = Contact(
        id = "c1",
        workspaceId = "ws-1",
        name = "Ada",
        createdAt = "2026-09-20T12:00:00.000Z",
    )

    private val offline = ApiResult.NetworkFailure(IOException("offline"))

    @Test
    fun `a create sends blank phone and email as absent, and a filled one as typed`() = runTest {
        api.mutationResult = ApiResult.Success(ContactMutationResponse(success = true, id = "c9"))

        assertEquals(ApiResult.Success("c9"), repository.create("ws-1", "Ada", phoneNumber = " ", email = ""))
        repository.create("ws-1", "Ada", phoneNumber = "+14165550142", email = null)
        repository.create("ws-1", "Ada", phoneNumber = null, email = "ada@example.com")

        assertNull(created[0].phoneNumber)
        assertNull(created[0].email)
        assertEquals("+14165550142", created[1].phoneNumber)
        assertNull(created[1].email)
        assertNull(created[2].phoneNumber)
        assertEquals("ada@example.com", created[2].email)
    }

    @Test
    fun `a create that is refused, or affirmed with no id, is drift, and a failure passes through`() = runTest {
        api.mutationResult = ApiResult.Success(ContactMutationResponse(success = false, id = "c9"))
        assertTrue(repository.create("ws-1", "Ada", "+14165550142", null) is ApiResult.DecodeFailure)

        api.mutationResult = ApiResult.Success(ContactMutationResponse(success = true))
        assertTrue(repository.create("ws-1", "Ada", "+14165550142", null) is ApiResult.DecodeFailure)

        api.mutationResult = offline
        assertEquals(offline, repository.create("ws-1", "Ada", "+14165550142", null))
    }

    @Test
    fun `a detail is the affirmed contact, and anything short of that is drift or passes through`() = runTest {
        api.contactResult = ApiResult.Success(ContactDetailResponse(success = true, contact = contact))
        assertEquals(ApiResult.Success(contact), repository.detail("ws-1", "c1"))

        api.contactResult = ApiResult.Success(ContactDetailResponse(success = false, contact = contact))
        assertTrue(repository.detail("ws-1", "c1") is ApiResult.DecodeFailure)

        api.contactResult = ApiResult.Success(ContactDetailResponse(success = true))
        assertTrue(repository.detail("ws-1", "c1") is ApiResult.DecodeFailure)

        api.contactResult = offline
        assertEquals(offline, repository.detail("ws-1", "c1"))
    }

    @Test
    fun `a delete is done when affirmed or already gone, and not when refused or failed`() = runTest {
        api.mutationResult = ApiResult.Success(ContactMutationResponse(success = true))
        assertEquals(ApiResult.Success(Unit), repository.delete("ws-1", "c1"))

        // ⚠️ Already absent is the end state the caller wanted.
        api.mutationResult = ApiResult.NotFound("Contact not found")
        assertEquals(ApiResult.Success(Unit), repository.delete("ws-1", "c1"))

        api.mutationResult = ApiResult.Success(ContactMutationResponse(success = false))
        assertTrue(repository.delete("ws-1", "c1") is ApiResult.DecodeFailure)

        api.mutationResult = offline
        assertEquals(offline, repository.delete("ws-1", "c1"))
    }
}
