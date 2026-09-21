package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.designsystem.Tone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⛔ THIS MAPPING IS THE ANSWER AN OPERATOR IS LOOKING FOR AFTER PAYING TO SEND A MESSAGE, which is why
 * it is pinned rather than eyeballed. The stakes are higher than the call log's: a send costs money, and
 * a failure painted green means the operator believes a customer got a reply they never received.
 */
class MessageToneTest {

    @Test
    fun `only genuine delivery is green`() {
        listOf("delivered", "received", "read").forEach {
            assertEquals(it, Tone.Success, toneForMessageStatus(it))
        }
    }

    @Test
    fun `undelivered is red, and that is the case that matters most`() {
        // ⛔ The provider ACCEPTED the message and then failed to deliver it. A client that treated
        // "sent" as success has already told the operator it worked by the time this arrives.
        assertEquals(Tone.Danger, toneForMessageStatus("undelivered"))
        listOf("failed", "bounced", "rejected", "spam").forEach {
            assertEquals(it, Tone.Danger, toneForMessageStatus(it))
        }
    }

    @Test
    fun `in-flight states are the accent, never success`() {
        // ⚠️ "queued" and "sent" are the states a message sits in BEFORE it fails. Colouring them green
        // would report a delivery that has not happened yet.
        listOf("queued", "accepted", "sending", "sent", "submitted").forEach {
            assertEquals(it, Tone.District, toneForMessageStatus(it))
        }
    }

    @Test
    fun `an unmodelled provider status is neutral rather than a guess`() {
        // Twilio, Telnyx and Postmark do not share a vocabulary, so an unknown value is expected.
        assertEquals(Tone.Neutral, toneForMessageStatus("carrier_thought_about_it"))
        assertEquals(Tone.Neutral, toneForMessageStatus(""))
        assertEquals(Tone.Neutral, toneForMessageStatus(null))
    }

    @Test
    fun `matching is case and whitespace insensitive`() {
        // ⚠️ Nothing normalises these columns on write — the same reason the call-status mapper trims.
        assertEquals(Tone.Success, toneForMessageStatus("DELIVERED"))
        assertEquals(Tone.Danger, toneForMessageStatus("  Undelivered "))
    }
}
