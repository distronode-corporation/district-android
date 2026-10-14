package com.distronode.districtai.ui.scheduling.admin.eventtypes

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingEventTypeChanges
import com.distronode.districtai.core.data.SchedulingEventTypeDraft
import com.distronode.districtai.core.data.SchedulingQuestionChanges
import com.distronode.districtai.core.data.SchedulingQuestionDraft
import com.distronode.districtai.core.model.SchedulingEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure halves of the event-type writes: the slug, the forms and their rules. */
class SchedulingEventTypeFormTest {

    @Test
    fun `the slug is the web's slug`() {
        assertEquals("phone-consultation", SchedulingEventTypeSlug.fromName("Phone consultation"))
        assertEquals("cafe-creme", SchedulingEventTypeSlug.fromName("  Café  Crème  "))
        assertEquals("30-min-intro", SchedulingEventTypeSlug.fromName("30 min / intro!!"))
        assertEquals("office-hours", SchedulingEventTypeSlug.fromName("Oﬃce hours"))
        assertEquals(SchedulingEventTypeSlug.FALLBACK, SchedulingEventTypeSlug.fromName("!!"))
        assertEquals(SchedulingEventTypeSlug.FALLBACK, SchedulingEventTypeSlug.fromName(""))
    }

    @Test
    fun `the cut to 200 cannot leave a trailing hyphen`() {
        val slug = SchedulingEventTypeSlug.fromName("a".repeat(199) + " tail")
        assertEquals(199, slug.length)
        assertFalse(slug.endsWith("-"))
    }

    @Test
    fun `a collision with a row on screen walks to the next number, then to the clock`() {
        val taken = listOf("phone-consultation", "phone-consultation-2")
        assertEquals("phone-consultation-3", SchedulingEventTypeSlug.unique("Phone consultation", taken))
        assertEquals("intro", SchedulingEventTypeSlug.unique("Intro", taken))
        val all = listOf("intro") + (2..999).map { "intro-$it" }
        assertEquals("intro-42", SchedulingEventTypeSlug.unique("Intro", all) { 42L })
    }

    @Test
    fun `location types classify their value and keep a stored one in the choices`() {
        assertEquals(SchedulingLocationType.PHONE, SchedulingLocationType.known("phone"))
        assertNull(SchedulingLocationType.known("zoom"))
        assertNull(SchedulingLocationType.known(null))
        val offered = listOf("livekit", "phone", "in_person", "link")
        assertEquals(offered, SchedulingLocationType.choices(null))
        assertEquals(offered, SchedulingLocationType.choices("link"))
        assertEquals(offered + "google_meet", SchedulingLocationType.choices("google_meet"))
        assertNull(SchedulingEventTypeForm(location = "livekit").locationField)
        assertNull(SchedulingEventTypeForm(location = "zoom").locationField)
        assertEquals(
            R.string.scheduling_admin_event_types_address,
            SchedulingEventTypeForm(location = "in_person").locationField,
        )
    }

    @Test
    fun `a create needs a name and a whole duration, and sends four keys`() {
        val blank = SchedulingEventTypeForm(name = "  ")
        assertEquals(R.string.scheduling_admin_event_types_name_missing, blank.createError)
        assertNull(blank.createDraft("x"))
        val badDuration = SchedulingEventTypeForm(name = "Intro", duration = "0")
        assertEquals(R.string.scheduling_admin_event_types_create_duration_invalid, badDuration.createError)
        assertNull(badDuration.createDraft("x"))
        val form = SchedulingEventTypeForm(name = " Phone consultation ", duration = " 45 ", location = "phone")
        assertNull(form.createError)
        assertEquals(
            SchedulingEventTypeDraft(
                slug = "phone-consultation",
                name = "Phone consultation",
                durationMinutes = 45,
                locationType = "phone",
            ),
            form.createDraft("phone-consultation"),
        )
    }

    @Test
    fun `an edit refuses the first fault and otherwise patches every field`() {
        val form = SchedulingEventTypeForm.from(fullEventType())
        assertNull(form.editError)
        assertEquals(R.string.scheduling_admin_event_types_name_missing, form.copy(name = "").editError)
        val minute = R.string.scheduling_admin_event_types_at_least_one_minute
        assertEquals(minute, form.copy(duration = "x").editError)
        assertEquals(minute, form.copy(interval = "0").editError)
        val zero = R.string.scheduling_admin_event_types_at_least_zero
        assertEquals(zero, form.copy(bufferBefore = "-1").editError)
        assertEquals(zero, form.copy(maxActiveBookings = "").editError)
        assertNull(form.copy(name = "").changes())
        assertEquals(
            SchedulingEventTypeChanges(
                name = "Intro call",
                description = "A first conversation.",
                durationMinutes = 30,
                slotIntervalMinutes = 15,
                locationType = "in_person",
                locationValue = "1 Main St",
                isActive = true,
                isPublic = false,
                showTakenSlots = true,
                bufferBeforeMinutes = 5,
                bufferAfterMinutes = 10,
                minNoticeMinutes = 60,
                maxFutureDays = 30,
                maxActiveBookings = 2,
            ),
            form.changes(),
        )
    }

    @Test
    fun `an absent flag opens as on and an absent interval as the duration`() {
        val sparse = SchedulingEventType(id = "et", slug = "s", name = "S", durationMinutes = 20)
        assertEquals(
            SchedulingEventTypeForm(
                name = "S",
                duration = "20",
                interval = "20",
                location = "livekit",
                isActive = true,
                isPublic = true,
                showTakenSlots = false,
            ),
            SchedulingEventTypeForm.from(sparse),
        )
        assertFalse(SchedulingEventTypeForm.from(sparse.copy(isActive = false)).isActive)
        assertNull(SchedulingEventTypeForm.whole("1.5", atLeast = 0))
        assertEquals(7, SchedulingEventTypeForm.whole(" 7 ", atLeast = 0))
    }

    @Test
    fun `a question needs a label, options for a select and a whole position`() {
        val text = SchedulingQuestionForm(label = " Company ")
        assertNull(text.error)
        assertNull(text.wireOptions)
        assertEquals(SchedulingQuestionDraft("Company", "text", false, options = null, position = 0), text.draft())
        assertEquals(SchedulingQuestionChanges("Company", "text", null, false, 0), text.changes())
        assertEquals(R.string.scheduling_admin_event_types_question_label_missing, text.copy(label = "").error)
        assertNull(text.copy(label = "").draft())
        assertNull(text.copy(label = "").changes())
        val select = text.copy(type = "select")
        assertEquals(R.string.scheduling_admin_event_types_question_needs_option, select.error)
        assertEquals(listOf("A"), select.copy(options = listOf("A")).wireOptions)
        val badPosition = text.copy(position = "-1")
        assertEquals(R.string.scheduling_admin_event_types_question_position_invalid, badPosition.error)
        assertNull(badPosition.draft())
    }

    @Test
    fun `options are added trimmed, once, and removed`() {
        val form = SchedulingQuestionForm(type = "select", optionDraft = " Morning ").withOption()
        assertEquals(listOf("Morning"), form.options)
        assertEquals("", form.optionDraft)
        assertEquals(listOf("Morning"), form.copy(optionDraft = "Morning").withOption().options)
        assertEquals(listOf("Morning"), form.copy(optionDraft = "  ").withOption().options)
        assertEquals(emptyList<String>(), form.withoutOption("Morning").options)
    }

    @Test
    fun `a new question opens at the end, an edited one as stored, and hosts start required`() {
        assertEquals("3", SchedulingQuestionForm.adding(3).position)
        val stored = schedulingQuestion(type = "select", options = listOf("A"), required = true, position = 2)
        assertEquals(
            SchedulingQuestionForm("q1", "Company", "select", listOf("A"), required = true, position = "2"),
            SchedulingQuestionForm.editing(stored),
        )
        assertEquals(emptyList<String>(), SchedulingQuestionForm.editing(schedulingQuestion()).options)
        assertEquals(
            SchedulingHostRow("u1", "Ana Lima", "u1@example.com", "required", "0"),
            SchedulingHostRow.from(schedulingHost()),
        )
        assertEquals(
            SchedulingHostRow("u2", "", "u2@example.com", "required", "0"),
            SchedulingHostRow.added(schedulerUser("u2")),
        )
    }
}
