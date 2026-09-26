package com.distronode.districtai.ui.support

import com.distronode.districtai.core.model.SupportBounds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's local gate, held to the route's own bounds.
 *
 * ⚠️ Hostile lengths on purpose: a draft the route would refuse with a 400 must not be offered as
 * submittable, or the operator gets a round trip to learn what the client already knew.
 */
class SupportComposeStateTest {

    private val valid = SupportComposeState(subject = "Calls drop", message = "Every call.", idempotencyKey = "k")

    @Test
    fun `a subject at the route's maximum is submittable and one over is not`() {
        assertTrue(valid.copy(subject = "a".repeat(SupportBounds.SUBJECT_MAX)).submittable)
        assertFalse(valid.copy(subject = "a".repeat(SupportBounds.SUBJECT_MAX + 1)).submittable)
    }

    @Test
    fun `a message at the route's maximum is submittable and one over is not`() {
        assertTrue(valid.copy(message = "m".repeat(SupportBounds.MESSAGE_MAX)).submittable)
        assertFalse(valid.copy(message = "m".repeat(SupportBounds.MESSAGE_MAX + 1)).submittable)
    }

    @Test
    fun `lengths are measured after trimming, so padding cannot smuggle a short subject through`() {
        assertFalse(valid.copy(subject = "  ab  ").submittable)
        assertFalse(valid.copy(message = "   ").submittable)
    }

    @Test
    fun `a draft already submitting is not submittable again`() {
        assertFalse(valid.copy(submitting = true).submittable)
    }
}
