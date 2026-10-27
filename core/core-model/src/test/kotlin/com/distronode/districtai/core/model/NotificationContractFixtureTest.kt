package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three Apple Watch round 2 routes: a person's decline, a message's sender name, and the iOS
 * notification extension's read token.
 *
 * ⛔ A CLASS OF ITS OWN for the reason the push half is one: `ContractFixtureTest` sits at detekt's
 * LargeClass ceiling. The strict decoder comes from [ContractFixtures], so the split cannot leave
 * this half lenient.
 */
class NotificationContractFixtureTest {

    private val json: Json = ContractFixtures.json

    @Test
    fun `a decline answers the bare success envelope`() {
        val response = json.decodeFromString<CallDeclineResponse>(
            ContractFixtures.read("district-call-decline.json"),
        )

        assertEquals(true, response.success)
    }

    @Test
    fun `the sender route answers a display name`() {
        val response = json.decodeFromString<MessageSenderResponse>(
            ContractFixtures.read("district-message-sender.json"),
        )

        assertEquals(true, response.success)
        assertEquals("Avery Contract", response.name)
    }

    @Test
    fun `an unnamed sender decodes as a null name, not as a failure`() {
        // ⛔ THE SHAPE THE ROUTE SENDS FOR A THREAD WITH NO NAMED CONTACT. It never falls back to the
        // address, so null is the ordinary answer and must keep the generic notification title.
        val response = json.decodeFromString<MessageSenderResponse>("""{"success":true,"name":null}""")

        assertEquals(true, response.success)
        assertEquals(null, response.name)
    }

    @Test
    fun `the sender route carries no address`() {
        // ⛔ A PRIVACY PROPERTY PINNED AS A SHAPE: a name for the title, never the number or the
        // email address an unnamed sender would otherwise be shown as.
        val raw = ContractFixtures.read("district-message-sender.json")

        assertFalse(raw.contains("phone"))
        assertFalse(raw.contains("email"))
        assertFalse(raw.contains("address"))
    }

    @Test
    fun `the notify token decodes, though this client never mints one`() {
        val response = json.decodeFromString<NotifyTokenResponse>(
            ContractFixtures.read("district-notify-token.json"),
        )

        assertEquals(true, response.success)
        assertTrue(response.token.isNotBlank())
        assertTrue(response.expiresAt.endsWith("Z"))
    }

    @Test
    fun `the three fixtures survive a round trip in both encodings`() {
        WireMirror.assertMirrors(CallDeclineResponse.serializer(), "district-call-decline.json")
        WireMirror.assertMirrors(MessageSenderResponse.serializer(), "district-message-sender.json")
        WireMirror.assertMirrors(NotifyTokenResponse.serializer(), "district-notify-token.json")
    }

    @Test
    fun `an unmodelled field on the sender response is rejected rather than ignored`() {
        val withExtraField = """{"success":true,"name":"Avery","phone":"+14165550100"}"""

        val failure = runCatching { json.decodeFromString<MessageSenderResponse>(withExtraField) }

        assertTrue(
            "Decoding an unknown key MUST fail. It succeeded, which means ignoreUnknownKeys is " +
                "no longer false and contract drift can now ship silently.",
            failure.isFailure,
        )
    }
}
