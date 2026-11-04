package com.distronode.districtai.ui.dialer

import com.distronode.districtai.l10n.AppLanguage
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * The country list as the operator reads it: localized names, the alphabetical order of the app's
 * language, the search, the Suggested group, and the example number in the empty field.
 *
 * ⚠️ NAMES COME FROM THE PLATFORM'S LOCALE DATA (CLDR), as on iOS, not from a catalog: two hundred
 * country names typed into `l10n/android.toml` would be two hundred strings to keep in step with
 * a table the system already maintains in both languages.
 */
object DialCountryNames {

    /** The locale country names are written in: Canadian French or Canadian English. */
    fun localeFor(language: AppLanguage): Locale =
        if (language == AppLanguage.FR) Locale.CANADA_FRENCH else Locale.forLanguageTag("en-CA")

    /** The country's name in [locale], or its code when the platform has none. */
    fun name(iso: String, locale: Locale): String =
        Locale.Builder().setRegion(iso).build().getDisplayCountry(locale).ifBlank { iso }

    /** Every country, alphabetical in [locale]. */
    fun sorted(locale: Locale): List<String> {
        val collator = Collator.getInstance(locale)
        return DialCountries.countries
            .map { it.iso to name(it.iso, locale) }
            .sortedWith { lhs, rhs -> collator.compare(lhs.second, rhs.second) }
            .map { it.first }
    }

    /**
     * The countries a search matches: by name (accents and case ignored), by ISO code, or by
     * calling code, with or without the `+`.
     *
     * ⚠️ RANKED, THEN ALPHABETICAL WITHIN A RANK: the ISO code exactly, a name that starts with the
     * query, a calling code, then a name that merely contains it. Done picks the first row, so
     * alphabetical alone made `us` pick Australia, ahead of Austria and the United States (Seer on
     * district-macos #91, fixed in district-ios #96).
     */
    fun search(query: String, locale: Locale, all: List<String> = sorted(locale)): List<String> {
        val needle = fold(query.trim())
        if (needle.isEmpty()) return all
        val digits = needle.removePrefix("+")
        val byCode = digits.isNotEmpty() && digits.all(DialPlan::isDigit)
        val ranks = List(RANKS) { mutableListOf<String>() }
        for (iso in all) {
            val folded = fold(name(iso, locale))
            val rank = when {
                iso.lowercase(Locale.ROOT) == needle -> 0
                folded.startsWith(needle) -> 1
                byCode && DialCountries.callingCode(iso)?.startsWith(digits) == true -> 2
                needle in folded -> RANKS - 1
                else -> null
            }
            rank?.let { ranks[it] += iso }
        }
        return ranks.flatten()
    }

    /**
     * The Suggested group: the workspace's home country, the one on screen, then Canada and the
     * United States, each once.
     */
    fun suggested(home: String?, current: String?): List<String> =
        listOfNotNull(home, current, "CA", "US").distinct()

    /**
     * An example NATIONAL number for the empty field, or "" for a country with none.
     *
     * ⛔ THE WEB DIALLER'S TABLE (`nationalExampleFor` in the website's `number-country.ts`), AND
     * ONLY RANGES NOBODY CAN BE REACHED ON, because a placeholder is occasionally dialled: NANP
     * 555-0100 to 555-0199 is reserved for fiction, Ofcom keeps 020 7946 0000 to 0999 for drama, and
     * the others are not working subscriber numbers. ⚠️ NOT libphonenumber's example numbers: those
     * are real-looking numbers in live ranges.
     */
    fun nationalExample(iso: String?): String = nationalExamples[iso].orEmpty()

    private val nationalExamples: Map<String, String> = mapOf(
        "CA" to "(416) 555-0199",
        "US" to "(206) 555-0142",
        "GB" to "020 7946 0958",
        "EE" to "6000 0000",
        "DE" to "030 0000000",
        "FR" to "01 00 00 00 00",
    )

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Exact code, name prefix, calling code, name contains. */
    private const val RANKS = 4
}
