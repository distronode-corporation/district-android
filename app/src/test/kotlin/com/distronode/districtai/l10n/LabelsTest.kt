package com.distronode.districtai.l10n

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.model.MESSAGING_CHANNELS
import com.distronode.districtai.core.model.ROUTING_FIELDS
import com.distronode.districtai.core.model.ROUTING_OPERATORS
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.contacts.dossierHeading
import com.distronode.districtai.ui.contacts.dossierHeadingLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The labels for values the server sends raw: the wire vocabulary's new categories and the app's own
 * tables for values the vocabulary lacks.
 *
 * ⚠️ IN ENGLISH, because these tests run on an English (en-US) device (see AppLanguageTest). Each
 * case asserts what the label CHOOSES: a known value its own entry, an unknown one the generic label
 * with the value in it (which reads as the value in English and "Valeur inconnue : …" in French).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class LabelsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun assertRendered(vararg cases: Pair<String, @Composable () -> String>) {
        val actual = arrayOfNulls<String>(cases.size)
        composeRule.setContent { cases.forEachIndexed { i, (_, block) -> actual[i] = block() } }
        composeRule.waitForIdle()
        cases.forEachIndexed { i, (expected, _) -> assertEquals("case $i", expected, actual[i]) }
    }

    private fun unknown(value: String) = str(R.string.labels_unknown_value, value)

    @Composable
    private fun invoice(status: String) = wireLabel(WireCategory.BILLING_INVOICE_STATUS, status)

    @Test
    fun `the raw values the French QA found read through the wire vocabulary, unknown ones included`() {
        assertRendered(
            str(R.string.wire_message_delivery_status_delivered) to {
                wireLabel(WireCategory.MESSAGE_DELIVERY_STATUS, "delivered")
            },
            str(R.string.wire_message_delivery_status_unknown, "bounced") to {
                wireLabel(WireCategory.MESSAGE_DELIVERY_STATUS, "bounced")
            },
            str(R.string.wire_billing_invoice_status_paid) to { invoice("paid") },
            str(R.string.wire_billing_invoice_status_open) to { invoice("open") },
            str(R.string.wire_meeting_status_in_progress) to { wireLabel(WireCategory.MEETING_STATUS, "in-progress") },
            str(R.string.wire_meeting_status_completed) to { wireLabel(WireCategory.MEETING_STATUS, "completed") },
            str(R.string.wire_workflow_run_status_success) to {
                wireLabel(WireCategory.WORKFLOW_RUN_STATUS, "success")
            },
            str(R.string.wire_knowledge_document_status_ready) to {
                wireLabel(WireCategory.KNOWLEDGE_DOCUMENT_STATUS, "ready")
            },
            str(R.string.wire_knowledge_document_status_processing) to {
                wireLabel(WireCategory.KNOWLEDGE_DOCUMENT_STATUS, "processing")
            },
            str(R.string.wire_number_provider_twilio) to { wireLabel(WireCategory.NUMBER_PROVIDER, "twilio") },
            str(R.string.wire_number_status_active) to { wireLabel(WireCategory.NUMBER_STATUS, "active") },

            str(R.string.wire_messaging_carrier_telnyx) to { wireLabel(WireCategory.MESSAGING_CARRIER, "telnyx") },
            str(R.string.wire_messaging_credential_source_byok) to {
                wireLabel(WireCategory.MESSAGING_CREDENTIAL_SOURCE, "byok")
            },
            str(R.string.wire_messaging_credential_source_managed) to {
                wireLabel(WireCategory.MESSAGING_CREDENTIAL_SOURCE, "managed")
            },
        )
        // English is the portal's label since 3.0.1 (visual QA A-S7); an unknown value still passes through.
        assertEquals("Delivered", str(R.string.wire_message_delivery_status_delivered))
        assertEquals("bounced", str(R.string.wire_message_delivery_status_unknown, "bounced"))
    }

    @Test
    fun `a value spelled unknown has its own label, not the unknown-value label missing its argument`() {
        // ⛔ It used to map to the category's unknown label, which takes an argument, so a call whose
        // status is `unknown` read "%1$s".
        assertRendered(
            "Unknown" to { wireLabel(WireCategory.CALL_STATUS, "unknown") },
            "unknown" to { wireLabel(WireCategory.NUMBER_PROVIDER, "unknown") },
        )
        assertEquals(R.string.wire_call_status_value_unknown, WireCategory.CALL_STATUS.labels["unknown"])
    }

    @Test
    fun `a device platform is its product name, and an unknown one passes through`() {
        assertRendered(
            "Android" to { devicePlatformLabel("android") },
            "iOS" to { devicePlatformLabel("ios") },
            "Linux" to { devicePlatformLabel("linux") },
            "macOS" to { devicePlatformLabel("macos") },
            "Windows" to { devicePlatformLabel("windows") },
            unknown("palm") to { devicePlatformLabel("palm") },
        )
        assertEquals("palm", unknown("palm"))
    }

    @Test
    fun `an inbox channel chip, a messaging channel and a support status read as the portal names them`() {
        assertRendered(
            "Email" to { inboxChannelLabel("email") },
            "SMS" to { inboxChannelLabel("sms") },
            "WhatsApp" to { inboxChannelLabel("whatsapp") },
            "Voicemail" to { inboxChannelLabel("voicemail") },
            unknown("RCS") to { inboxChannelLabel("rcs") },
            // The portal's channel names since 3.0.1 (visual QA AC4), no longer the raw value.
            "SMS" to { messagingChannelLabel("sms") },
            "Voice" to { messagingChannelLabel("voice") },
            "WhatsApp" to { messagingChannelLabel("whatsapp") },
            unknown("rcs") to { messagingChannelLabel("rcs") },
            str(R.string.messaging_channel_title, "SMS") to {
                stringResource(R.string.messaging_channel_title, messagingChannelLabel("sms"))
            },
            str(R.string.support_status_received) to { stringResource(supportStatusLabel("Received", "PENDING")) },
            str(R.string.support_status_in_progress) to {
                stringResource(supportStatusLabel("In Progress", "INDETERMINATE"))
            },
        )
        assertEquals(setOf("sms", "email", "whatsapp", "voicemail"), INBOX_CHANNEL_LABELS.keys)
        assertEquals(R.string.messaging_channel_sms, MESSAGING_CHANNEL_LABELS["sms"])
        assertEquals(MESSAGING_CHANNELS.toSet(), MESSAGING_CHANNEL_LABELS.keys)
    }

    @Test
    fun `every routing field and comparison has a label, and a stored value outside the lists passes through`() {
        assertEquals(ROUTING_FIELDS.toSet(), ROUTING_FIELD_LABELS.keys)
        assertEquals(ROUTING_OPERATORS.toSet(), ROUTING_OPERATOR_LABELS.keys)
        assertRendered(
            // The portal's labels since 3.0.1 (visual QA AC4).
            "Industry" to { valueLabel(ROUTING_FIELD_LABELS, "industry") },
            "Decision Maker (true/false)" to { valueLabel(ROUTING_FIELD_LABELS, "isDecisionMaker") },
            "Contains" to { valueLabel(ROUTING_OPERATOR_LABELS, "contains") },
            "Equals" to { valueLabel(ROUTING_OPERATOR_LABELS, "equals") },
            unknown("revenue") to { valueLabel(ROUTING_FIELD_LABELS, "revenue") },
        )
    }

    @Test
    fun `a dossier heading is the drawer's label for a vocabulary key, and any other key is shown as generated`() {
        assertRendered(
            "Executive summary" to { dossierHeading("executiveSummary") },
            "Caller intel" to { dossierHeading("callerIntel") },
            "Summary" to { dossierHeading("summary") },
            "Next best action" to { dossierHeading("next_best_action") },
            "Buying signals" to { dossierHeading("buyingSignals") },
        )
        assertEquals(R.string.wire_contact_research_key_executive_summary, dossierHeadingLabel("executiveSummary"))
        // ⛔ A key the model wrote has no label at all, so no language reads it as an unknown value
        // (French: « Valeur inconnue : Summary »); a unit-test build has no French to show it, so the
        // choice itself is pinned.
        assertNull(dossierHeadingLabel("summary"))
        assertNull(dossierHeadingLabel("buyingSignals"))
    }

    @Test
    fun `a category with its own fallback reads that word for a value it does not list, in both languages`() {
        // ⛔ The vocabulary's `fallback`, before the generic label: four categories carry one.
        assertRendered(
            "Transfer attempted but not connected" to { wireLabel(WireCategory.TRANSFER_REASON, "asked for a human") },
            "Unknown" to { wireLabel(WireCategory.DESK_TICKET_STATUS, "archived") },
            "Unknown" to { wireLabel(WireCategory.KNOWLEDGE_DOCUMENT_STATUS, "queued") },
            // Telnyx passes its own status through ("in-use"), so the vocabulary cannot list them all.
            "Active" to { wireLabel(WireCategory.NUMBER_STATUS, "in-use") },
            // A listed value still reads as itself.
            str(R.string.wire_number_status_suspended) to { wireLabel(WireCategory.NUMBER_STATUS, "suspended") },
        )
        val withFallback = WireCategory.entries.filter { !it.unknownTakesValue }.toSet()
        assertEquals(
            setOf(
                WireCategory.TRANSFER_REASON,
                WireCategory.DESK_TICKET_STATUS,
                WireCategory.KNOWLEDGE_DOCUMENT_STATUS,
                WireCategory.NUMBER_STATUS,
            ),
            withFallback,
        )
    }

    @Test
    fun `a category without a fallback names the value it does not know`() {
        assertRendered(
            str(R.string.wire_meeting_status_unknown, "paused") to { wireLabel(WireCategory.MEETING_STATUS, "paused") },
            "paused" to { wireLabel(WireCategory.MEETING_STATUS, "paused") },
        )
    }

    @Test
    fun `every desk status the app knows has its own label, matched whatever the desk's case or spacing`() {
        // ⛔ 3.0.1: the desk's raw word reached French screens ("In Progress", "Done").
        val expected = mapOf(
            "Received" to R.string.support_status_received,
            "Open" to R.string.support_status_open,
            "New" to R.string.support_status_open,
            "To Do" to R.string.support_status_open,
            "In Progress" to R.string.support_status_in_progress,
            "Work in progress" to R.string.support_status_in_progress,
            "Under review" to R.string.support_status_in_progress,
            "Escalated" to R.string.support_status_escalated,
            "Waiting for customer" to R.string.support_status_waiting_for_customer,
            "Waiting for reply" to R.string.support_status_waiting_for_customer,
            "Waiting for support" to R.string.support_status_waiting_for_support,
            "Pending" to R.string.support_status_pending,
            "Done" to R.string.support_status_done,
            "Completed" to R.string.support_status_done,
            "Resolved" to R.string.support_status_resolved,
            "Closed" to R.string.support_status_closed,
            "Canceled" to R.string.support_status_canceled,
            "Cancelled" to R.string.support_status_canceled,
            "Declined" to R.string.support_status_declined,
            "Won't do" to R.string.support_status_declined,
        )
        assertEquals(expected.size, SUPPORT_STATUS_LABELS.size)
        expected.forEach { (word, label) ->
            // The category disagrees on purpose: a word the app knows wins over the bucket.
            assertEquals(word, label, supportStatusLabel(word, "NEW"))
            assertEquals(word, label, supportStatusLabel("  ${word.uppercase()} ", "NEW"))
        }
    }

    @Test
    fun `a word the app does not know reads as its category, and nothing at all as unknown`() {
        // ⚠️ THE LIVE DESK HAS ANSWERED IN CHINESE: the category is the one language-independent signal.
        assertEquals(R.string.support_status_open, supportStatusLabel("新建", "NEW"))
        assertEquals(R.string.support_status_in_progress, supportStatusLabel("Waiting on vendor", "indeterminate"))
        assertEquals(R.string.support_status_closed, supportStatusLabel("完成", "DONE"))
        assertEquals(R.string.support_status_received, supportStatusLabel("", "PENDING"))
        assertEquals(R.string.support_status_unknown, supportStatusLabel("Triage", "unknown"))
        assertEquals(R.string.support_status_unknown, supportStatusLabel("", ""))
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `a French screen shows the desk's English status in French`() {
        assertRendered(
            "En cours" to { stringResource(supportStatusLabel("In Progress", "INDETERMINATE")) },
            "Terminée" to { stringResource(supportStatusLabel("Done", "DONE")) },
            "État inconnu" to { stringResource(supportStatusLabel("Triage", "unknown")) },
            "Reçue" to { stringResource(supportStatusLabel("Received", "PENDING")) },
        )
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `a French inbox chip names every channel the server writes, voicemail included`() {
        assertRendered(
            "Courriel" to { inboxChannelLabel("email") },
            "SMS" to { inboxChannelLabel("sms") },
            "WhatsApp" to { inboxChannelLabel("whatsapp") },
            "Boîte vocale" to { inboxChannelLabel("voicemail") },
            "Valeur inconnue\u00A0: RCS" to { inboxChannelLabel("rcs") },
        )
    }
}
