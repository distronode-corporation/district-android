package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Support's wire shapes, for the branches no generated fixture carries.
 *
 * ⛔ NOT A CONTRACT-FIXTURE TEST. The server's generator records five `district-support-*.json`
 * fixtures from the real handlers and [SupportContractFixtureTest] decodes them. The JSON here is
 * transcribed from the server's support route handlers and help-desk serializers, and is kept for
 * what the fixtures do not cover: the deduplicated and
 * pending create branches, a localised status name, and the draft's field set. See the ⛔ on
 * `DeskContractShapeTest` for what hand-transcribed JSON does and does not prove.
 *
 * ⛔ NOTHING IN THIS FILE MODELS A REQUESTER, AND THAT IS A CHECKED PROPERTY RATHER THAN AN
 * ACCIDENT OF THE FIXTURES — see `no request type carries an identifying field`. The server derives
 * the requester from the session and hashes it; a `requesterHash`, an email or a phone number on
 * one of these DTOs would be the first step toward a client that takes the answer from its input.
 */
class SupportContractShapeTest {

    private val json: Json = ContractFixtures.json

    @Test
    fun `the list decodes with an unfiled row whose issueKey is explicitly null`() {
        val response = json.decodeFromString<SupportRequestListResponse>(
            """{"success":true,"requests":[
               {"issueKey":"DA-42","id":"row_1","subject":"Calls drop after 30s",
                "statusName":"In Progress","statusCategory":"INDETERMINATE",
                "createdAt":"2026-09-01T09:00:00.000Z","updatedAt":"2026-09-02T09:00:00.000Z",
                "filed":true,"source":"workspace","region":"ca"},
               {"issueKey":null,"id":"row_2","subject":"Billing question",
                "statusName":"Received","statusCategory":"NEW","createdAt":"a","updatedAt":"b",
                "filed":false,"source":"workspace","region":"ca"}]}""",
        )

        assertEquals(2, response.requests.size)
        // ⚠️ AN UNFILED REQUEST IS A STATE, NOT A FAULT: we hold it, Atlassian does not have it yet,
        // and it is still addressable by its own row id.
        assertNull(response.requests[1].issueKey)
        assertFalse(response.requests[1].filed)
        assertEquals("Received", response.requests[1].statusName)
    }

    @Test
    fun `resolution is read from the CATEGORY, never from the localised status name`() {
        // ⛔ THE LIVE WORKFLOW IS LOCALISED. Comparing `statusName` to "Closed" would report a
        // French or German desk's resolved request as open — and the category is exactly the field
        // that exists so a client does not have to.
        val resolved = SupportRequestSummary(statusName = "Terminé", statusCategory = "DONE")
        val open = SupportRequestSummary(statusName = "Closed-ish", statusCategory = "INDETERMINATE")

        assertTrue(resolved.isResolved)
        assertFalse(open.isResolved)
        // ⚠️ Case-insensitive: the category arrives upper-case today, and the comparison must not
        // depend on that continuing.
        assertTrue(SupportStatusCategory.isResolved("done"))
    }

    @Test
    fun `the detail carries the thread and the server's own closeable verdict`() {
        val response = json.decodeFromString<SupportRequestDetailResponse>(
            """{"success":true,"request":{"issueKey":"DA-42","id":"row_1","subject":"Calls drop",
               "statusName":"In Progress","statusCategory":"INDETERMINATE","createdAt":"a",
               "updatedAt":"b","filed":true,"source":"workspace","region":"ca","closeable":true,
               "messages":[{"id":"c1","role":"customer","author":"You","body":"It drops.",
               "createdAt":"a"},{"id":"c2","role":"agent","author":"Distronode Support",
               "body":"Looking now.","createdAt":"b"}]}}""",
        )

        val request = response.request!!
        // ⛔ THE SERVER'S ANSWER, NOT A ROLE DERIVATION. The desk's workflow either offers no
        // resolving transition or offers several, and in the second case picking one would decide
        // on the customer's behalf whether their request was "done" or "won't do".
        assertTrue(request.closeable)
        assertEquals(SupportMessageRole.CUSTOMER, request.messages[0].knownRole)
        assertEquals(SupportMessageRole.AGENT, request.messages[1].knownRole)
        // ⚠️ `author` IS A LABEL THE ROUTE SYNTHESISES, not the human who wrote it. The desk's own
        // `authorName` is dropped server-side and never reaches this client.
        assertEquals("Distronode Support", request.messages[1].author)
    }

    @Test
    fun `the three create outcomes share no keys, and all three are successes`() {
        val filed = json.decodeFromString<SupportRequestCreateResponse>(
            """{"success":true,"issueKey":"DA-43"}""",
        )
        assertEquals(SupportRequestFiling.Filed("DA-43"), filed.filing)

        val deduplicated = json.decodeFromString<SupportRequestCreateResponse>(
            """{"success":true,"deduplicated":true}""",
        )
        assertEquals(SupportRequestFiling.Deduplicated, deduplicated.filing)

        // ⛔ PENDING IS A SUCCESS. We hold the claim row and a human will see it; only the reference
        // to quote is missing. Rendering it as an error is what makes an operator send it twice,
        // which is the one thing the idempotency key exists to prevent.
        val pending = json.decodeFromString<SupportRequestCreateResponse>(
            """{"success":true,"pending":true}""",
        )
        assertEquals(SupportRequestFiling.Pending, pending.filing)
    }

    @Test
    fun `an empty issueKey is treated as pending rather than as a filed request with no key`() {
        // ⚠️ Defensive: `filing` checks for blank as well as null, so a server that ever sent `""`
        // does not produce a "Filed" outcome carrying a reference nobody can quote.
        val response = SupportRequestCreateResponse(success = true, issueKey = "")
        assertEquals(SupportRequestFiling.Pending, response.filing)
    }

    @Test
    fun `the reply answers one message and the close answers the desk's own word`() {
        val reply = json.decodeFromString<SupportReplyResponse>(
            """{"success":true,"message":{"id":"c3","role":"customer","author":"You",
               "body":"Thanks.","createdAt":"c"}}""",
        )
        assertEquals("c3", reply.message!!.id)

        val close = json.decodeFromString<SupportCloseResponse>(
            """{"success":true,"statusName":"Terminé"}""",
        )
        // ⛔ ADOPTED, NEVER SUBSTITUTED. Printing "Closed" here would put English over a status
        // Atlassian spells in another language.
        assertEquals("Terminé", close.statusName)
    }

    @Test
    fun `the kind vocabulary is exactly the route's z-enum`() {
        assertEquals(
            listOf("problem", "question", "suggestion"),
            SupportRequestKind.entries.map { it.wire },
        )
        assertEquals(SupportRequestKind.QUESTION, SupportRequestKind.fromWire("Question"))
        // ⛔ THE ROUTE MAPS THE KIND ONTO A JIRA REQUEST TYPE ID, so an unrecognised one must never
        // become a string this client sends: it would file into a type whose portal form we do not
        // populate, which 400s at Atlassian AFTER the local claim row already exists.
        assertNull(SupportRequestKind.fromWire("incident"))
    }

    @Test
    fun `no request type carries an identifying field`() {
        // ⛔ THE SECURITY PROPERTY, ASSERTED RATHER THAN TRUSTED TO REVIEW. A phone call is
        // authenticated by caller ID and caller ID is spoofable, which is why the VOICE lookup
        // takes no identity argument. This surface is authenticated differently and may read full
        // content — but it must still never accept an identity from its caller, because a
        // parameter here is a lookup key a future screen could populate from anything.
        // ⚠️ A SET, NOT A LIST. `declaredFields` order is unspecified by the JVM spec even though
        // it is declaration order in practice, and this assertion is about WHICH fields exist
        // rather than about their order.
        val draftFields = SupportRequestDraft::class.java.declaredFields.map { it.name }
        assertEquals(setOf("kind", "subject", "message"), draftFields.toSet())

        val forbidden = listOf("requester", "email", "phone", "hash", "userId", "contact")
        draftFields.forEach { field ->
            forbidden.forEach { word ->
                assertFalse(
                    "SupportRequestDraft must carry no identifying field, found: $field",
                    field.lowercase().contains(word),
                )
            }
        }
    }

    @Test
    fun `an unknown key fails the strict decoder`() {
        val thrown = runCatching {
            json.decodeFromString<SupportCloseResponse>(
                """{"success":true,"statusName":"Done","transitionId":"31"}""",
            )
        }.exceptionOrNull()

        assertTrue("an unknown key must throw", thrown != null)
    }
}
