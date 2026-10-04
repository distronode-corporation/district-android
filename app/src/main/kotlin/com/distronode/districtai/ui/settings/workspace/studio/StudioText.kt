package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.VoiceStudioLabels
import kotlin.math.roundToLong

/**
 * Text the client builds itself, from the server's own templates (`labels.meterAbout`,
 * `meterAtLeast`, `meterNone`, `basedOnOne`, `basedOnMany`) and grouping value.
 *
 * ⛔ NO APP-ENGLISH AND NO DEVICE LOCALE. The templates arrive in the reader's PORTAL locale, and
 * a number is written as plain digits with `labels.numberGrouping` between groups of three from
 * the right, which reproduces the server's `Intl` output in both locales. Each placeholder is
 * replaced once, as literal text (no regex, no format strings).
 */
internal object StudioText {

    private const val GROUP = 3

    /** A whole-millisecond total, grouped: `1,234` in English, `1 234` (no-break space) in French. */
    fun grouped(ms: Double, grouping: String): String {
        val digits = ms.roundToLong().toString()
        val head = digits.length % GROUP
        val groups = buildList {
            if (head > 0) add(digits.substring(0, head))
            var start = head
            while (start < digits.length) {
                add(digits.substring(start, start + GROUP))
                start += GROUP
            }
        }
        return groups.joinToString(grouping)
    }

    /**
     * A bare measured median the server sent without a sentence: the grouped number followed by
     * whatever follows `{ms}` in `meterAbout` (the unit and its no-break space), never an app unit.
     */
    fun millis(ms: Double, labels: VoiceStudioLabels): String =
        grouped(ms, labels.numberGrouping) + labels.meterAbout.substringAfter("{ms}")

    /** The unsaved-edit meter's headline (the web's `meterTotal`). */
    fun meter(headline: MeterHeadline, labels: VoiceStudioLabels): String =
        if (headline is MeterHeadline.Server) {
            headline.text
        } else if (headline is MeterHeadline.Local) {
            val template = if (headline.atLeast) labels.meterAtLeast else labels.meterAbout
            template.replaceFirst("{ms}", grouped(headline.ms, labels.numberGrouping))
        } else {
            labels.meterNone
        }

    /** "Based on {recipe}, {n} changes.", or null at no change. [changes] is plain digits, ungrouped. */
    fun basedOn(labels: VoiceStudioLabels, recipe: String, changes: Int): String? = when {
        changes <= 0 -> null
        changes == 1 -> labels.basedOnOne.replaceFirst("{recipe}", recipe)
        // `{n}` first: a recipe name is inserted last, so text inside it is never read as a placeholder.
        else -> labels.basedOnMany.replaceFirst("{n}", changes.toString()).replaceFirst("{recipe}", recipe)
    }
}
