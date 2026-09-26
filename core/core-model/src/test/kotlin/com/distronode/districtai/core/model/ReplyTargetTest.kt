package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ConversationSummary.replyTarget] — who a reply actually goes to, and on what channel.
 *
 * ⛔ THE THREAD'S OWN IDENTITY IS NOT A RECIPIENT. Using it as one makes a `contact:<id>` thread ask
 * the server to text a cuid. The server hands `to` straight to the carrier, so that fails at the
 * provider and reads as an inbox that cannot reply, in the majority case, since the server folds
 * every counterpart that resolves to a Contact into a contact-keyed thread.
 */
class ReplyTargetTest {

    private fun conversation(
        counterpart: String = "+14165550142",
        contactPhone: String? = null,
        contactEmail: String? = null,
        canSms: Boolean = false,
        canEmail: Boolean = false,
    ) = ConversationSummary(
        threadKey = "contact:c1",
        counterpart = counterpart,
        contactId = "c1",
        contactPhone = contactPhone,
        contactEmail = contactEmail,
        canSms = canSms,
        canEmail = canEmail,
    )

    @Test
    fun `a contact's phone is the recipient, not the contact id`() {
        val target = conversation(contactPhone = "+14165550150", canSms = true).replyTarget

        assertEquals("+14165550150", target?.to)
        assertEquals(CHANNEL_SMS, target?.channel)
    }

    @Test
    fun `an email-only thread replies by email, not by sms`() {
        // ⛔ The channel used to be hardcoded to sms, which sent an email address to the carrier.
        val target = conversation(
            counterpart = "ada@example.com",
            contactEmail = "ada@example.com",
            canEmail = true,
        ).replyTarget

        assertEquals("ada@example.com", target?.to)
        assertEquals(CHANNEL_EMAIL, target?.channel)
    }

    @Test
    fun `sms wins when both channels are available`() {
        val target = conversation(
            contactPhone = "+14165550150",
            contactEmail = "ada@example.com",
            canSms = true,
            canEmail = true,
        ).replyTarget

        assertEquals(CHANNEL_SMS, target?.channel)
    }

    @Test
    fun `a thread the server marked unsendable has no target`() {
        // ⛔ canSms/canEmail are SERVER-DECIDED. Both false means there is nothing to reply on,
        // even though an address is sitting right there in the counterpart.
        assertNull(conversation(contactPhone = "+14165550150").replyTarget)
    }

    @Test
    fun `an address-keyed thread falls back to its counterpart`() {
        // A counterpart that never resolved to a Contact carries no contactPhone, but the address
        // IS the identity and is perfectly sendable.
        val target = conversation(counterpart = "+14165550159", canSms = true).replyTarget

        assertEquals("+14165550159", target?.to)
        assertEquals(CHANNEL_SMS, target?.channel)
    }

    @Test
    fun `an email counterpart is never offered as an sms recipient`() {
        // The shape guard that matters: canSms with only an email in hand must not pair that email
        // with the carrier branch, which does no address-shape validation of its own.
        val target = conversation(counterpart = "ada@example.com", canSms = true).replyTarget

        assertNull(target)
    }

    @Test
    fun `an address-keyed email thread falls back to its counterpart`() {
        // The email mirror of the sms fallback: no Contact row, so no contactEmail, but the address
        // the thread is keyed by is a real mailbox.
        val target = conversation(counterpart = "grace@example.com", canEmail = true).replyTarget

        assertEquals("grace@example.com", target?.to)
        assertEquals(CHANNEL_EMAIL, target?.channel)
    }

    @Test
    fun `a blank contact address is skipped in favour of the counterpart`() {
        // A Contact row can hold an empty string rather than null; that is not an address.
        val sms = conversation(counterpart = "+14165550159", contactPhone = "  ", canSms = true).replyTarget
        val email = conversation(counterpart = "grace@example.com", contactEmail = "", canEmail = true).replyTarget

        assertEquals("+14165550159", sms?.to)
        assertEquals("grace@example.com", email?.to)
    }

    @Test
    fun `a phone counterpart is never offered as an email recipient`() {
        // The shape guard in the other direction: canEmail with only a phone number in hand has
        // nothing to send to, and a blank counterpart is nothing on either channel.
        assertNull(conversation(counterpart = "+14165550159", canEmail = true).replyTarget)
        assertNull(conversation(counterpart = "", canSms = true, canEmail = true).replyTarget)
    }
}
