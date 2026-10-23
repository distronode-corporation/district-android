package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The working-hours and override edit forms' time of day: shown « 9 h 30 » in French and as stored
 * in English, read from every spelling a person types, refused out of range, and always stored as
 * the scheduler's zero-padded `HH:mm`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SchedulingClockTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun valid(wire: String) = SchedulingClockInput.Valid(wire)

    @Test
    fun `every French and English spelling reads as HH mm`() {
        val cases = mapOf(
            // French
            "9 h" to "09:00",
            "9 h 30" to "09:30",
            "9h30" to "09:30",
            "9h" to "09:00",
            "9 H 30" to "09:30",
            "17 h" to "17:00",
            "0 h 15" to "00:15",
            "9\u00A0h\u00A030" to "09:30",
            "9\u202Fh\u202F30" to "09:30",
            // Colon forms, both languages
            "9:30" to "09:30",
            "09:30" to "09:30",
            "09:00" to "09:00",
            " 17:00 " to "17:00",
            "23:59" to "23:59",
            // English
            "9:30 am" to "09:30",
            "9:30 AM" to "09:30",
            "5 pm" to "17:00",
            "5pm" to "17:00",
            "5 p.m." to "17:00",
            "12 am" to "00:00",
            "12:15 pm" to "12:15",
        )
        cases.forEach { (typed, wire) -> assertEquals(typed, valid(wire), readSchedulingClock(typed)) }
    }

    @Test
    fun `what is not a time is malformed, and a time no clock shows is out of range`() {
        listOf("", "9", "nine", "9-30", "09:3", "09:000", "0a:00", "9 h 3", "123:00", "9 h pm", "9:30 xm")
            .forEach { assertEquals(it, SchedulingClockInput.Malformed, readSchedulingClock(it)) }
        listOf("24:00", "25:00", "9:60", "9 h 75", "24 h", "13 pm", "0 am")
            .forEach { assertEquals(it, SchedulingClockInput.OutOfRange, readSchedulingClock(it)) }
        assertEquals(SchedulingRangeError.FORMAT, schedulingClockError("9-30"))
        assertEquals(SchedulingRangeError.RANGE, schedulingClockError("25:00"))
        assertNull(schedulingClockError("9 h 30"))
    }

    @Test
    fun `the wire is HH mm, and what does not read is left for the form to refuse`() {
        assertEquals("09:30", schedulingWireTime("9 h 30"))
        assertEquals("17:00", schedulingWireTime("17 h"))
        assertEquals("9-30", schedulingWireTime("9-30"))
        assertEquals("25:00", schedulingWireTime("25:00"))
    }

    @Test
    fun `French shows the French clock, English shows the stored value unchanged`() {
        assertEquals("9 h", schedulingClockFieldText("09:00", AppLanguage.FR))
        assertEquals("9 h 30", schedulingClockFieldText("09:30", AppLanguage.FR))
        assertEquals("17 h", schedulingClockFieldText("17:00", AppLanguage.FR))
        assertEquals("0 h 15", schedulingClockFieldText("00:15", AppLanguage.FR))
        assertEquals("25:00", schedulingClockFieldText("25:00", AppLanguage.FR))
        // ⛔ The exact codepoint: a plain space (U+0020), never U+00A0 or U+202F.
        assertEquals(listOf(0x39, 0x20, 0x68), schedulingClockFieldText("09:00", AppLanguage.FR).map { it.code })
        assertEquals("09:00", schedulingClockFieldText("09:00", AppLanguage.EN))
        assertEquals("09:30", schedulingClockFieldText("09:30", AppLanguage.EN))
        assertEquals("17:00", schedulingClockFieldText("17:00", AppLanguage.EN))
    }

    @Test
    fun `a French field writes every time vector byte for byte, and reads it back`() {
        // ⛔ contracts/i18n/format-vectors.json is the website's, vendored byte for byte: «0 h 05»,
        // «9 h», «13 h 30» with PLAIN spaces. The field must show what every other French screen does.
        val dir = System.getProperty("district.contracts.dir")
        assertTrue("district.contracts.dir is not set", !dir.isNullOrBlank())
        val root = Json.parseToJsonElement(File(dir, "i18n/format-vectors.json").readText()).jsonObject
        val times = root.getValue("categories").jsonArray.map { it.jsonObject }
            .single { it.getValue("category").jsonPrimitive.content == "times" }
            .getValue("vectors").jsonArray.map { it.jsonObject }
            .filter { it.getValue("fn").jsonPrimitive.content == "formatTime" }
        assertTrue("no formatTime vectors", times.isNotEmpty())
        times.forEach { v ->
            val local = v.getValue("local").jsonPrimitive.content
            val french = v.getValue("fr").jsonPrimitive.content
            assertEquals(v.toString(), french, schedulingClockFieldText(local, AppLanguage.FR))
            assertEquals(v.toString(), valid(local), readSchedulingClock(french))
        }
    }

    private var wire by mutableStateOf("09:00")

    private fun showField() {
        composeRule.setContent {
            DistrictTheme {
                SchedulingClockField(
                    label = "Start",
                    wire = wire,
                    onWireChange = { wire = it },
                    tag = TAG,
                    enabled = true,
                )
            }
        }
    }

    private fun fieldShows(text: String) {
        composeRule.onNodeWithTag(TAG).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)),
        )
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `a French field shows 9 h, keeps what was typed, and stores HH mm`() {
        showField()
        fieldShows("9 h")
        composeRule.onNodeWithTag(TAG).performTextReplacement("9 h 30")
        fieldShows("9 h 30")
        composeRule.runOnIdle { assertEquals("09:30", wire) }
        composeRule.onNodeWithTag(TAG).performTextReplacement("9h45")
        composeRule.runOnIdle { assertEquals("09:45", wire) }
        composeRule.onNodeWithTag(TAG).performTextReplacement("9 h 7")
        fieldShows("9 h 7")
        composeRule.runOnIdle { assertEquals("9 h 7", wire) }
        // Changed from outside (a copy to weekdays): the field follows.
        composeRule.runOnIdle { wire = "17:00" }
        fieldShows("17 h")
    }

    @Test
    fun `an English field shows the stored value as before, and stores HH mm`() {
        showField()
        fieldShows("09:00")
        composeRule.onNodeWithTag(TAG).performTextReplacement("9:30")
        fieldShows("9:30")
        composeRule.runOnIdle { assertEquals("09:30", wire) }
        composeRule.runOnIdle { wire = "17:00" }
        fieldShows("17:00")
    }

    private companion object {
        const val TAG = "clock"
    }
}
