package com.distronode.districtai.ui.scheduling.admin.eventtypes

import java.text.Normalizer

/**
 * The booking page's address, derived from the name nobody was asked to type; ported from
 * district-ios's `SchedulingEventTypeSlug`, which is the web's `slugFromName` / `uniqueSlug`.
 *
 * ⛔ THE OPERATOR NEVER ENTERS A SLUG, ON ANY CLIENT. It is the last segment of a public URL and
 * `eventTypes.patch` cannot change it (the schema's `slug` ADDRESSES the row), so a field for it
 * would be one typo away from a permanent one.
 *
 * ⛔ NFKD, THEN THE COMBINING MARKS, THEN THE WEB'S OWN REGEXES IN THE WEB'S ORDER. The cut to 200
 * can put a hyphen back on the end, which the fork refuses, so the trailing trim runs again after it.
 */
object SchedulingEventTypeSlug {
    /** What an empty result becomes. The fork requires at least one character. */
    const val FALLBACK: String = "event-type"

    private const val MAX_LENGTH = 200
    private const val LAST_SUFFIX = 999
    private val COMBINING_MARKS = Regex("[\\u0300-\\u036f]")
    private val NOT_SLUG = Regex("[^a-z0-9]+")
    private val EDGE_HYPHENS = Regex("^-+|-+$")
    private val TRAILING_HYPHENS = Regex("-+$")

    fun fromName(name: String): String {
        val folded = COMBINING_MARKS.replace(Normalizer.normalize(name, Normalizer.Form.NFKD), "")
        val slug = EDGE_HYPHENS.replace(NOT_SLUG.replace(folded.lowercase(), "-"), "").take(MAX_LENGTH)
        return TRAILING_HYPHENS.replace(slug, "").ifEmpty { FALLBACK }
    }

    /**
     * A slug no row on screen is using.
     *
     * ⛔ [taken] IS WHAT THIS CLIENT HAS READ, NOT WHAT THE TENANCY HOLDS: another member can take the
     * slug between the read and the save, and the fork's own check settles that. ⚠️ The `-2 … -999`
     * walk and the millisecond escape after it are the web's; [nowMillis] lets a test reach the escape.
     */
    fun unique(name: String, taken: List<String>, nowMillis: () -> Long = System::currentTimeMillis): String {
        val base = fromName(name)
        val used = taken.toSet()
        if (base !in used) return base
        return (2..LAST_SUFFIX).map { "$base-$it" }.firstOrNull { it !in used } ?: "$base-${nowMillis()}"
    }
}
