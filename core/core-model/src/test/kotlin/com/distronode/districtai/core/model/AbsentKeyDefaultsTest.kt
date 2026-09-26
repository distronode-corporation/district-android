package com.distronode.districtai.core.model

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a response row means when the server leaves a key out.
 *
 * WHY THIS IS NOT THE FIXTURE TESTS AGAIN. The committed fixtures are real answers and so always
 * carry the keys the route happens to send today. These rows are built in Kotlin with the optional
 * keys left at their defaults, and each case pins two things the fixtures cannot show: that the
 * production encoding of such a row writes no key the server did not send, and that the matching
 * sparse body decodes to exactly the default the rest of the app reads.
 */
class AbsentKeyDefaultsTest {

    @Test
    fun `a transcript read with no body has nothing to render`() {
        val empty = CallTranscriptResponse()

        assertFalse(empty.success)
        assertFalse(empty.hasTranscript)
        WireMirror.assertWire(CallTranscriptResponse.serializer(), empty, "{}")
    }

    @Test
    fun `a push registration answer without its flag reads as not registered`() {
        // ⛔ THE FLAG IS THE WHOLE DIFFERENCE between "push is off for this device" and "we do not
        // know", so its absence must never read as success.
        assertFalse(PushRegistrationResponse().success)
        WireMirror.assertWire(PushRegistrationResponse.serializer(), PushRegistrationResponse(), "{}")
    }

    @Test
    fun `a carrier account and the managed summary decode without their optional keys`() {
        WireMirror.assertWire(
            MessagingAccount.serializer(),
            MessagingAccount(id = "acct-1"),
            """{"id":"acct-1"}""",
        )
        // ⚠️ The route omits `provider` on the managed summary when the stored value is blank.
        val managed = ManagedAccount(phoneNumbers = listOf("+14165550142"))
        WireMirror.assertWire(ManagedAccount.serializer(), managed, """{"phoneNumbers":["+14165550142"]}""")
        assertNull(managed.provider)
    }

    @Test
    fun `an HQ proposal with no arguments confirms with an empty object`() {
        val pending = HqPendingWrite(tool = "sync_calendar", summary = "Sync the calendar now")

        // ⛔ [HqPendingWrite.args] is returned verbatim on confirm, so an absent one must be `{}`
        // rather than null: the confirm body always names the arguments it approves.
        assertEquals(JsonObject(emptyMap()), pending.args)
        WireMirror.assertWire(
            HqPendingWrite.serializer(),
            pending,
            """{"tool":"sync_calendar","summary":"Sync the calendar now"}""",
        )
    }

    @Test
    fun `a workflow's latest run and an unpaid invoice decode from their sparse rows`() {
        WireMirror.assertWire(
            WorkflowLatestRun.serializer(),
            WorkflowLatestRun(status = "success"),
            """{"status":"success"}""",
        )
        // ⚠️ This month's open invoice has no URL and no payment yet; it must still be a row.
        val open = BillingInvoice(id = "in_1", status = "open", created = 1_790_000_000)
        assertEquals(0L, open.amountPaid)
        WireMirror.assertWire(
            BillingInvoice.serializer(),
            open,
            """{"id":"in_1","status":"open","created":1790000000}""",
        )
    }

    @Test
    fun `an empty guest invite and an empty e2ee block decode rather than throw`() {
        // ⛔ A blank key is not usable; the caller refuses to hand it to the SDK. See [E2eeInfo].
        assertTrue(E2eeInfo().key.isBlank())
        WireMirror.assertWire(E2eeInfo.serializer(), E2eeInfo(), "{}")
        WireMirror.assertWire(GuestInvite.serializer(), GuestInvite(sig = "c2ln"), """{"sig":"c2ln"}""")
        assertEquals(0L, GuestInvite(sig = "c2ln").exp)
    }

    @Test
    fun `a sent email row carries no SMS-only key`() {
        val email = SentMessage(
            id = "msg-1",
            messageSid = "pm-1",
            type = "email",
            subject = "Your booking",
            provider = "postmark",
            status = "sent",
        )

        WireMirror.assertWire(
            SentMessage.serializer(),
            email,
            """{"id":"msg-1","messageSid":"pm-1","subject":"Your booking","type":"email",""" +
                """"status":"sent","provider":"postmark"}""",
        )
        assertNull(email.externalId)
        assertNull(email.accountId)
    }
}
