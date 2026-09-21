package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.designsystem.Tone

/**
 * Which [Tone] an OUTBOUND message's delivery status wears.
 *
 * ⛔ THIS IS THE ANSWER THE OPERATOR IS LOOKING FOR AFTER A SEND, so it must not be decorative. A
 * message that failed and a message that was delivered rendering in the same grey is the whole reason
 * the call log's statuses got toned; the same argument applies here and the stakes are higher, because
 * a send costs money and a silent failure means the customer never got the reply.
 *
 * ⚠️ FAILS TO [Tone.Neutral] FOR ANYTHING UNMODELLED. The status column carries whatever the provider
 * reported — Twilio, Telnyx and Postmark do not agree on a vocabulary — so painting an unknown value
 * green would assert a delivery this client cannot confirm.
 *
 * ⚠️ Lowercased and trimmed before matching, for the same reason `toneForCallStatus` does it: nothing
 * normalises these columns on write.
 */
fun toneForMessageStatus(status: String?): Tone = when (status?.trim()?.lowercase()) {
    // It reached the handset or the mailbox. The only genuinely good outcome.
    "delivered", "received", "read" -> Tone.Success

    // ⛔ Terminal failures. `undelivered` is the one that matters most: the provider ACCEPTED the
    // message and then could not deliver it, so a client treating "sent" as success would have already
    // told the operator it worked.
    "failed", "undelivered", "bounced", "rejected", "spam" -> Tone.Danger

    // Accepted by the provider, outcome not yet known. ⚠️ Deliberately NOT Success — "queued" and
    // "sent" are the states a message sits in before it fails.
    "queued", "accepted", "sending", "sent", "submitted" -> Tone.District

    else -> Tone.Neutral
}
