package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.l10n.appLanguage
import com.distronode.districtai.ui.scheduling.admin.SchedulingTextField
import java.util.Locale

// A time of day in the working-hours and override EDIT forms: shown the way the language writes it,
// read back from anything a person is likely to type, and stored as the scheduler's `HH:mm`.
//
// ⛔ THE WIRE IS ALWAYS ZERO-PADDED `HH:mm`. The scheduler refuses "9:00" (`admin-ops.ts`), so every
// accepted spelling is rewritten to "09:00" before it is compared, diffed or sent.
//
// ⚠️ THE SAME RULES AS THE CORES (district-core-swift, district-core-rust): French shows « 9 h »,
// « 9 h 30 », « 17 h » and reads « 9 h 30 », "9h30", "9:30" and "09:30"; English shows the wire value
// as it always has, and reads the same forms plus "9:30 am" / "5 p.m.". Android has no core, so the
// app carries them.

/** What a typed time of day reads as. */
sealed interface SchedulingClockInput {
    /** A real time, as the scheduler stores it ("09:30"). */
    data class Valid(val wire: String) : SchedulingClockInput

    /** Not a time at all ("9-30", "nine", "9"). */
    data object Malformed : SchedulingClockInput

    /** Shaped like a time, but no clock shows it ("25:00", "9 h 75", "13 pm"). */
    data object OutOfRange : SchedulingClockInput
}

/** Read [text] as a time of day. Spaces may be plain, no-break (U+00A0) or narrow no-break (U+202F). */
fun readSchedulingClock(text: String): SchedulingClockInput {
    val normalized = text.trim().replace('\u00A0', ' ').replace('\u202F', ' ').lowercase(Locale.ROOT)
    val match = CLOCK_INPUT.matchEntire(normalized) ?: return SchedulingClockInput.Malformed
    val groups = match.groupValues
    val hourText = groups[GROUP_HOUR]
    val colon = groups[GROUP_COLON]
    val colonMinutes = groups[GROUP_COLON_MINUTES]
    val h = groups[GROUP_H]
    val hMinutes = groups[GROUP_H_MINUTES]
    val meridiem = groups[GROUP_MERIDIEM]
    val hasSeparator = colon.isNotEmpty() || h.isNotEmpty()
    // "9" alone is not a time; "9 am" is. "9 h pm" mixes two systems and is not one either.
    if (!hasSeparator && meridiem.isEmpty()) return SchedulingClockInput.Malformed
    if (h.isNotEmpty() && meridiem.isNotEmpty()) return SchedulingClockInput.Malformed
    var hour = hourText.toInt()
    val minute = (colonMinutes.ifEmpty { hMinutes }).ifEmpty { "0" }.toInt()
    if (meridiem.isNotEmpty()) {
        if (hour !in 1..HALF_DAY) return SchedulingClockInput.OutOfRange
        hour = hour % HALF_DAY + if (meridiem == "p") HALF_DAY else 0
    }
    if (hour > LAST_HOUR || minute > LAST_MINUTE) return SchedulingClockInput.OutOfRange
    return SchedulingClockInput.Valid("%02d:%02d".format(Locale.ROOT, hour, minute))
}

/** [value] as the scheduler stores it when it reads as a time, else unchanged (so it stays refused). */
fun schedulingWireTime(value: String): String =
    (readSchedulingClock(value) as? SchedulingClockInput.Valid)?.wire ?: value

/**
 * Why [value] cannot be sent as a time, or null when it can.
 *
 * ⚠️ ONE ERROR FOR BOTH, as in district-core-swift: "25:00" and « 9 h 5 » are refused with the same
 * sentence, whose example shows what a time looks like.
 */
fun schedulingClockError(value: String): SchedulingRangeError? = when (readSchedulingClock(value)) {
    is SchedulingClockInput.Valid -> null
    SchedulingClockInput.Malformed, SchedulingClockInput.OutOfRange -> SchedulingRangeError.FORMAT
}

/**
 * A stored time as the edit field shows it: « 9 h 30 » in French, the wire value as always in
 * English. Anything that does not read as a time is shown as it is.
 *
 * ⛔ THE FRENCH SPACES ARE PLAIN U+0020, byte for byte the website's format vectors
 * (`contracts/i18n/format-vectors.json`: "9 h", "0 h 05"), as every other French time in the app
 * ([DistrictFormat.frenchClock]) and the cores write them.
 */
fun schedulingClockFieldText(wire: String, language: AppLanguage): String {
    if (language == AppLanguage.EN) return wire
    val valid = readSchedulingClock(wire) as? SchedulingClockInput.Valid ?: return wire
    return DistrictFormat.frenchClock(valid.wire.take(2).toInt(), valid.wire.takeLast(2).toInt())
}

/**
 * A time-of-day text field bound to a wire value.
 *
 * ⚠️ THE FIELD KEEPS WHAT WAS TYPED, THE FORM KEEPS THE WIRE. Each keystroke hands [onWireChange] the
 * `HH:mm` reading when there is one (so "9 h 3" → "9 h 30" becomes "09:30" without the text jumping
 * under the cursor), or the raw text, which the form then refuses. When [wire] changes from outside
 * (a copy to weekdays, a new window) and no longer matches the typed text, the field shows [wire].
 */
@Composable
fun SchedulingClockField(
    label: String,
    wire: String,
    onWireChange: (String) -> Unit,
    tag: String,
    enabled: Boolean,
) {
    val language = appLanguage()
    var typed by remember { mutableStateOf(schedulingClockFieldText(wire, language)) }
    val shown = if (schedulingWireTime(typed) == wire) typed else schedulingClockFieldText(wire, language)
    SchedulingTextField(
        label = label,
        value = shown,
        onValueChange = { text ->
            typed = text
            onWireChange(schedulingWireTime(text))
        },
        tag = tag,
        enabled = enabled,
    )
}

private const val HALF_DAY = 12
private const val GROUP_HOUR = 1
private const val GROUP_COLON = 2
private const val GROUP_COLON_MINUTES = 3
private const val GROUP_H = 4
private const val GROUP_H_MINUTES = 5
private const val GROUP_MERIDIEM = 6
private const val LAST_HOUR = 23
private const val LAST_MINUTE = 59

/**
 * Hour; then ":" and two-digit minutes, or "h" and optional two-digit minutes, or neither; then an
 * optional am/pm. ASCII digits only, as the scheduler's own `\d` is in JavaScript.
 */
private val CLOCK_INPUT =
    Regex("""([0-9]{1,2}) *(?:(:) *([0-9]{2})|(h) *([0-9]{2})?)? *(?:([ap])\.? *m\.?)?""")
