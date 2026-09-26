package com.distronode.districtai.ui.workflows

import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The workflow vocabulary: which trigger gets which label, and which run status gets which tone.
 *
 * The trigger column is free text server-side, validated on write against a list that has already
 * grown once. So two things are pinned here: every trigger this build knows has its OWN label (the
 * three call-end triggers in particular are not synonyms, and a workflow on all three runs twice),
 * and a trigger it does not know gets NO label, which is what makes the caller draw the raw string
 * instead of pretending the automation does not exist.
 */
class WorkflowVocabularyTest {

    @Test
    fun `every known trigger has its own label`() {
        val labels = mapOf(
            TRIGGER_CALL_ENDED to R.string.workflow_trigger_call_ended,
            TRIGGER_CALL_ENDED_ANSWERED to R.string.workflow_trigger_call_ended_answered,
            TRIGGER_CALL_ENDED_UNANSWERED to R.string.workflow_trigger_call_ended_unanswered,
            TRIGGER_NEGATIVE_SENTIMENT to R.string.workflow_trigger_negative_sentiment,
            TRIGGER_POSITIVE_SENTIMENT to R.string.workflow_trigger_positive_sentiment,
            TRIGGER_NEUTRAL_SENTIMENT to R.string.workflow_trigger_neutral_sentiment,
            TRIGGER_INTENT_DETECTED to R.string.workflow_trigger_intent_detected,
            TRIGGER_SMS_RECEIVED to R.string.workflow_trigger_sms_received,
            TRIGGER_CONTACT_CREATED to R.string.workflow_trigger_contact_created,
            TRIGGER_DNC_REGISTERED to R.string.workflow_trigger_dnc_registered,
        )

        labels.forEach { (trigger, label) -> assertEquals(trigger, label, triggerLabelRes(trigger)) }
        assertEquals("no two triggers share a label", labels.size, labels.values.toSet().size)
    }

    @Test
    fun `a trigger this build has never heard of gets no label`() {
        assertNull(triggerLabelRes("appointment_booked"))
        assertNull(triggerLabelRes(""))
        // Matched exactly: a near miss is still unknown, not silently mapped to a neighbour.
        assertNull(triggerLabelRes("CALL_ENDED"))
    }

    @Test
    fun `a partial run is a warning, and an unknown status is neutral rather than an error`() {
        assertEquals(Tone.Success, runTone(RUN_STATUS_SUCCESS))
        assertEquals(Tone.Warning, runTone(RUN_STATUS_PARTIAL))
        assertEquals(Tone.Danger, runTone(RUN_STATUS_FAILED))
        // A skipped run is the workflow working: its conditions were not met.
        assertEquals(Tone.Neutral, runTone(RUN_STATUS_SKIPPED))
        assertEquals(Tone.Neutral, runTone("queued"))
    }

    @Test
    fun `an action outcome is toned the same way one level down`() {
        assertEquals(Tone.Success, outcomeTone(OUTCOME_OK))
        assertEquals(Tone.Danger, outcomeTone(OUTCOME_FAILED))
        assertEquals(Tone.Neutral, outcomeTone(OUTCOME_SKIPPED))
        assertEquals(Tone.Neutral, outcomeTone("retrying"))
    }
}
