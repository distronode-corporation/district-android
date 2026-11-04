package com.distronode.districtai.ui.dialer

import com.distronode.districtai.l10n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dialler design A's number reading: the country on the button, the local form in the field, and
 * the E.164 number that is sent. Case for case with district-ios `DialPlanTests` (#95, #96).
 *
 * ⛔ THE SWITZERLAND CASE IS HERE BECAUSE IT WAS A BILLED CALL. A Canadian mobile typed as
 * `+4165550123` is `+41`, Switzerland; the flag has to move there, and nothing may be sent until the
 * line under the field states the whole number.
 *
 * ⚠️ PLAIN JVM, NO ROBOLECTRIC: [DialPlan] is pure, and the country names come from the JDK's CLDR
 * data, the same source Android's ICU reads.
 */
class DialPlanTest {

    private val en = DialCountryNames.localeFor(AppLanguage.EN)
    private val fr = DialCountryNames.localeFor(AppLanguage.FR)

    /** Type [keys] one at a time from [start], the way the screen feeds the field back. */
    private fun typeKeys(keys: String, start: String?): Pair<String, String?> {
        var field = ""
        var country = start
        for (key in keys) {
            val reading = DialPlan.read(field + key, country)
            country = reading.iso ?: country
            field = reading.national
        }
        return field to country
    }

    // ── Local numbers ────────────────────────────────────────────────────────

    @Test
    fun `a Canadian number typed locally formats and dials as E164`() {
        val reading = DialPlan.read("4165550199", "CA")
        assertEquals(DialPlan.Status.READY, reading.status)
        assertEquals("CA", reading.iso)
        assertEquals("(416) 555-0199", reading.national)
        assertEquals("+1 416 555 0199", reading.international)
        assertEquals("+14165550199", reading.e164)
    }

    /** ⚠️ THE FIELD REWRITES ITSELF AS DIGITS ARRIVE, so each partial form must keep every digit. */
    @Test
    fun `formatting as you type keeps every digit`() {
        assertEquals("(416) 555-0199", typeKeys("4165550199", "CA").first)
        assertEquals("416", DialPlan.read("416", "CA").national)
        assertEquals("(416) 5", DialPlan.read("4165", "CA").national)
        assertEquals("(416) 555-0", DialPlan.read("4165550", "CA").national)
    }

    @Test
    fun `a number still being typed is not dialable`() {
        val reading = DialPlan.read("416555", "CA")
        assertEquals(DialPlan.Status.TYPING, reading.status)
        assertNull(reading.e164)
    }

    @Test
    fun `the North American trunk one is dropped`() {
        val reading = DialPlan.read("1 416 555 0199", "CA")
        assertEquals("+14165550199", reading.e164)
        assertEquals("(416) 555-0199", reading.national)
        assertEquals("1", DialPlan.read("1", "CA").national)
    }

    /** ⚠️ A +1 NUMBER IS READ BY ITS AREA CODE. */
    @Test
    fun `a plus one area code flips Canada, the United States and Jamaica`() {
        assertEquals("US", DialPlan.read("2065550142", "CA").iso)
        assertEquals("CA", DialPlan.read("4165550199", "US").iso)
        assertEquals("JM", DialPlan.read("8765550100", "US").iso)
    }

    @Test
    fun `a London number typed locally drops its trunk zero`() {
        val reading = DialPlan.read("020 7946 0958", "GB")
        assertEquals(DialPlan.Status.READY, reading.status)
        assertEquals("020 7946 0958", reading.national)
        assertEquals("+442079460958", reading.e164)
        assertEquals("a lone trunk 0 stays on screen", "0", DialPlan.read("0", "GB").national)
    }

    @Test
    fun `too many digits is refused`() {
        val reading = DialPlan.read("41655501990", "CA")
        assertEquals(DialPlan.Status.TOO_LONG, reading.status)
        assertNull(reading.e164)
    }

    /** ⚠️ EVERY NUMBER A COUNTRY ISSUES MUST BE DIALABLE, not only its common shape. */
    @Test
    fun `non-geographic numbers are dialable`() {
        val australia = DialPlan.read("1300 123 456", "AU")
        assertEquals("1300 123 456", australia.national)
        assertEquals("+611300123456", australia.e164)
        assertEquals("+61131234", DialPlan.read("13 12 34", "AU").e164)
        assertEquals("+6518001234567", DialPlan.read("1800 123 4567", "SG").e164)
        assertEquals("+448001111", DialPlan.read("0800 1111", "GB").e164)
        assertEquals("(02) 1234 5678", DialPlan.read("02 1234 5678", "AU").national)
        assertEquals("0491 570 156", DialPlan.read("0491570156", "AU").national)
    }

    /** ⛔ BACKSPACE ALWAYS REMOVES A DIGIT: no form may end in punctuation. */
    @Test
    fun `deleting the last character always removes a digit`() {
        val numbers = listOf(
            "CA" to "4165550199", "GB" to "02079460958", "GB" to "01632960001", "FR" to "0639980123",
            "DE" to "030123456", "DE" to "015112345678", "DE" to "0221123456", "AU" to "0212345678",
            "AU" to "0491570156", "AU" to "1300123456", "CH" to "0441234567", "SG" to "61234567",
            "IT" to "0612345678", "JP" to "0312345678", "RU" to "84951234567", "HU" to "0612345678",
        )
        for ((country, digits) in numbers) {
            var field = ""
            for (digit in digits) {
                field = DialPlan.read(field + digit, country).national
                val last = field.lastOrNull() ?: continue
                assertTrue("$country: \"$field\" ends in punctuation", last.isDigit())
                val before = field.count(Char::isDigit)
                val after = DialPlan.read(field.dropLast(1), country).national
                assertTrue("$country: backspace on \"$field\"", after.count(Char::isDigit) < before)
            }
        }
    }

    /** ⛔ A TRUNK PREFIX THAT IS NOT `0` IS DROPPED TOO: kept, `8 495…` would send `+7 849…`. */
    @Test
    fun `trunk prefixes other than zero are dropped`() {
        val moscow = DialPlan.read("8 495 123 4567", "RU")
        assertEquals("+74951234567", moscow.e164)
        assertEquals("8495 123 4567", moscow.national)
        assertEquals("+3612345678", DialPlan.read("06 1 234 5678", "HU").e164)
        assertEquals("0", DialPlan.read("0", "HU").national)
        assertEquals("+375171234567", DialPlan.read("8 017 123 4567", "BY").e164)
    }

    /** ⚠️ A +1 TOLL-FREE NUMBER NAMES NO COUNTRY, so the +1 country on screen stays. */
    @Test
    fun `North American toll free keeps the country on screen`() {
        assertEquals("CA", DialPlan.read("8005550100", "CA").iso)
        assertEquals("CA", DialPlan.read("+1 888 555 0100", "CA").iso)
        assertEquals("US", DialPlan.read("+1 888 555 0100", null).iso)
        assertEquals("before an area code the +1 country stays", "CA", DialPlan.read("41", "CA").iso)
    }

    // ── Numbers that name their own country ──────────────────────────────────

    @Test
    fun `a pasted plus forty four number moves to the United Kingdom`() {
        val reading = DialPlan.read("+44 20 7946 0958", "CA")
        assertEquals("GB", reading.iso)
        assertEquals(DialPlan.Status.READY, reading.status)
        assertEquals("020 7946 0958", reading.national)
        assertEquals("+44 20 7946 0958", reading.international)
        assertEquals("+442079460958", reading.e164)
        assertTrue(reading.namedItsCountry)
    }

    @Test
    fun `a signature style trunk zero after the code is dropped`() {
        assertEquals("+442079460958", DialPlan.read("+44 (0)20 7946 0958", null).e164)
    }

    /**
     * ⛔ THE BILLED CALL. `+41` is Switzerland whatever the workspace's country; with eight digits
     * after the code it is not even a whole Swiss number, so nothing can be sent.
     */
    @Test
    fun `the Switzerland misdial moves the flag and sends nothing`() {
        val reading = DialPlan.read("+4165550123", "CA")
        assertEquals("CH", reading.iso)
        assertEquals("41", reading.callingCode)
        assertEquals("65550123", reading.nationalNumber)
        assertEquals(DialPlan.Status.TYPING, reading.status)
        assertNull(reading.e164)
        assertNull("no Rings line for a number that cannot be sent", destinationLine(reading))
        // And one more digit makes it a whole Swiss number, said in full before Call.
        val whole = DialPlan.read("+41655501234", "CA")
        assertEquals("+41 65 550 12 34", whole.international)
        assertEquals("CH" to "+41 65 550 12 34", destinationLine(whole))
    }

    @Test
    fun `the North American exit code stands for plus`() {
        val reading = DialPlan.read("011 44 20 7946 0958", "CA")
        assertEquals("GB", reading.iso)
        assertEquals("+442079460958", reading.e164)
        assertEquals("GB", DialPlan.read("011 44 20 7946 0958", null).iso)
    }

    @Test
    fun `the international zero zero stands for plus outside North America`() {
        assertEquals("+33639980123", DialPlan.read("0033 6 39 98 01 23", "GB").e164)
        assertEquals("FR", DialPlan.read("0033639980123", "FR").iso)
    }

    /** ⚠️ UNDER A +1 FLAG `00` IS NOT AN EXIT CODE. */
    @Test
    fun `zero zero is not an exit code under a plus one flag`() {
        assertNotEquals("+442079460958", DialPlan.read("0044 20 7946 0958", "CA").e164)
    }

    @Test
    fun `plus seven is Kazakhstan for a six or seven and Russia otherwise`() {
        assertEquals("KZ", DialPlan.read("+7 701 234 5678", null).iso)
        assertEquals("KZ", DialPlan.read("+7 601 234 5678", null).iso)
        assertEquals("RU", DialPlan.read("+7 495 123 4567", null).iso)
    }

    /**
     * ⛔ TYPED ONE KEY AT A TIME, +7 701 … IS KAZAKHSTAN TOO (district-ios #96). `+7` moves the flag
     * to Russia and the field drops to the local form, so the rest arrives as a national number under
     * Russia; it must be read again by its first digit, as a paste is.
     */
    @Test
    fun `plus seven typed key by key reaches Kazakhstan`() {
        val (field, country) = typeKeys("+77012345678", "CA")
        val typed = DialPlan.read(field, country)
        assertEquals("KZ", typed.iso)
        assertEquals("+77012345678", typed.e164)
        val pasted = DialPlan.read("+7 701 234 5678", "CA")
        assertEquals("typed and pasted read alike", pasted.iso, typed.iso)
        assertEquals(pasted.national, typed.national)
        assertEquals("RU", DialPlan.read("84951234567", "KZ").iso)
        assertEquals("KZ", DialPlan.read("87012345678", "RU").iso)
        // ⚠️ Before the deciding digit, the +7 country on screen stays.
        assertEquals("KZ", DialPlan.read("8", "KZ").iso)
        assertEquals("RU", DialPlan.read("8", "RU").iso)
        assertEquals("KZ", DialPlan.read("+7", "KZ").iso)
        assertEquals("RU", DialPlan.read("+7", "CA").iso)
    }

    /** ⚠️ THE SAME FOR +1: typed key by key from a non-+1 flag it follows the area code. */
    @Test
    fun `plus one typed key by key follows the area code`() {
        for ((keys, iso) in listOf("+14165550199" to "CA", "+18765550199" to "JM", "+12065550199" to "US")) {
            val (field, country) = typeKeys(keys, "GB")
            assertEquals(keys, iso, DialPlan.read(field, country).iso)
        }
    }

    /** ⚠️ AN UNKNOWN CODE IS NAMED AS UNKNOWN AND STAYS DIALABLE. */
    @Test
    fun `an unknown code stays dialable without a name`() {
        val reading = DialPlan.read("+990 1234 5678", "CA")
        assertNull(reading.iso)
        assertEquals(DialPlan.Status.READY, reading.status)
        assertEquals("+99012345678", reading.e164)
        assertEquals(null to "+99012345678", destinationLine(reading))
        // Said once the code has had its three digits, while still being typed. ⚠️ Not +999: that is
        // the UK's emergency number, and is refused as one.
        assertEquals(null to "+990", destinationLine(DialPlan.read("+990", "CA")))
        assertNull(destinationLine(DialPlan.read("+99", "CA")))
        assertEquals(DialPlan.Status.TOO_LONG, DialPlan.read("+990 1234 5678 90123", "CA").status)
    }

    @Test
    fun `a plus alone is still being typed`() {
        val reading = DialPlan.read("+", "CA")
        assertEquals(DialPlan.Status.TYPING, reading.status)
        assertEquals("+", reading.national)
        assertNull(reading.iso)
    }

    @Test
    fun `a calling code alone shows the code and an empty field`() {
        val reading = DialPlan.read("+44", "CA")
        assertEquals("GB", reading.iso)
        assertEquals("", reading.national)
        assertEquals("+44", reading.international)
    }

    // ── Emergency numbers ────────────────────────────────────────────────────

    /** ⛔ REFUSED IN EVERY COUNTRY AND WITH NONE, and before anything asks for a country. */
    @Test
    fun `emergency numbers are refused whatever the country`() {
        val cases = listOf(
            "911" to "CA",
            "911" to null,
            "112" to "FR",
            "999" to "GB",
            "000" to "AU",
            "1911" to "US",
            "+1911" to "CA",
        )
        for ((number, country) in cases) {
            val reading = DialPlan.read(number, country)
            assertEquals("$number in $country", DialPlan.Status.EMERGENCY, reading.status)
            assertNull(reading.e164)
        }
    }

    /** ⚠️ WHOLE NUMBERS ONLY: a North American number beginning 911 is ordinary. */
    @Test
    fun `a number that starts with an emergency code is ordinary`() {
        assertEquals(DialPlan.Status.READY, DialPlan.read("+1 911 555 0100", null).status)
        assertEquals(DialPlan.Status.TYPING, DialPlan.read("9115", "CA").status)
    }

    // ── No home country ──────────────────────────────────────────────────────

    @Test
    fun `digits with no country ask for one`() {
        val reading = DialPlan.read("4165550199", null)
        assertEquals(DialPlan.Status.NEEDS_COUNTRY, reading.status)
        assertNull(reading.e164)
        assertNull(reading.iso)
    }

    @Test
    fun `a plus number works with no country`() {
        val reading = DialPlan.read("+33 6 39 98 01 23", null)
        assertEquals("FR", reading.iso)
        assertEquals("06 39 98 01 23", reading.national)
        assertEquals("+33639980123", reading.e164)
    }

    @Test
    fun `empty is empty`() {
        assertEquals(DialPlan.Status.EMPTY, DialPlan.read("", "CA").status)
        assertEquals(DialPlan.Status.EMPTY, DialPlan.read("", null).status)
        assertEquals(DialPlan.Status.EMPTY, DialPlan.read(" -()", "CA").status)
    }

    // ── Countries ────────────────────────────────────────────────────────────

    @Test
    fun `flags are regional indicator pairs`() {
        assertEquals("🇨🇦", DialCountries.flag("CA"))
        assertEquals("🇩🇪", DialCountries.flag("de"))
    }

    @Test
    fun `every country has a flag, a code and a name`() {
        assertTrue(DialCountries.countries.size > 200)
        val isos = DialCountries.countries.map { it.iso }
        assertEquals("a country listed twice", isos.size, isos.toSet().size)
        for (country in DialCountries.countries) {
            assertEquals(2, country.iso.length)
            assertTrue(country.iso, country.code.all(Char::isDigit))
            assertNotEquals("no name for ${country.iso}", country.iso, DialCountryNames.name(country.iso, en))
        }
    }

    /** ⚠️ CALLING CODES ARE PREFIX-FREE, which the longest-match reading relies on. */
    @Test
    fun `no calling code is a prefix of another`() {
        val codes = DialCountries.countries.map { it.code }.toSet()
        for (code in codes) {
            for (other in codes) {
                if (other != code) assertFalse("+$code shadows +$other", other.startsWith(code))
            }
        }
    }

    @Test
    fun `every country reads back to itself or its code's first country`() {
        for (country in DialCountries.countries.filter { it.code != "1" && it.code != "7" }) {
            val iso = DialPlan.read("+" + country.code + "12345678", null).iso
            assertEquals(country.iso, country.code, DialCountries.callingCode(iso.orEmpty()))
        }
    }

    @Test
    fun `search finds by name, code and calling code`() {
        assertEquals(listOf("DE"), DialCountryNames.search("germ", en))
        assertEquals(listOf("DE"), DialCountryNames.search("allem", fr))
        assertTrue(DialCountryNames.search("+49", en).contains("DE"))
        assertTrue(DialCountryNames.search("de", en).contains("DE"))
        assertTrue(DialCountryNames.search("quebec zzz", en).isEmpty())
        assertEquals(DialCountryNames.sorted(en), DialCountryNames.search("  ", en))
    }

    /**
     * ⛔ RANKED, BECAUSE DONE PICKS THE FIRST ROW (district-ios #96): `us` listed Australia and Austria
     * before the United States.
     */
    @Test
    fun `search ranks code, then name start, then calling code, then contains`() {
        val us = DialCountryNames.search("us", en)
        assertEquals("US", us.first())
        assertTrue(us.indexOf("US") < us.indexOf("AU"))
        assertEquals("CA", DialCountryNames.search("ca", en).first())
        assertEquals("CA", DialCountryNames.search("can", en).first())
        val ger = DialCountryNames.search("ger", en)
        assertTrue(ger.indexOf("DE") in 0 until ger.indexOf("DZ"))
        // The countries sharing a calling code stay alphabetical among themselves.
        val plusOne = DialCountryNames.search("+1", en)
        assertTrue(plusOne.contains("CA") && plusOne.contains("US"))
        assertEquals(DialCountryNames.sorted(en).filter(plusOne::contains), plusOne)
        // An exact ISO code first, then names starting with it: "de" is Germany, then Denmark.
        val de = DialCountryNames.search("de", en)
        assertEquals(listOf("DE", "DK"), de.take(2))
    }

    /** ⚠️ ACCENTS ARE IGNORED, so "etats" finds the United States in French. */
    @Test
    fun `search ignores accents and case`() {
        assertTrue(DialCountryNames.search("ETATS-UNIS", fr).contains("US"))
        assertTrue(DialCountryNames.search("etats", fr).contains("US"))
    }

    @Test
    fun `the list is alphabetical in the app's language`() {
        assertEquals("AF", DialCountryNames.sorted(en).first())
        val french = DialCountryNames.sorted(fr)
        assertTrue("Allemagne sorts before Andorre in French", french.indexOf("DE") < french.indexOf("AD"))
        assertEquals("Allemagne", DialCountryNames.name("DE", fr))
        assertEquals("Germany", DialCountryNames.name("DE", en))
    }

    @Test
    fun `suggested is home, then current, then Canada and the United States, once each`() {
        assertEquals(listOf("FR", "GB", "CA", "US"), DialCountryNames.suggested(home = "FR", current = "GB"))
        assertEquals(listOf("CA", "US"), DialCountryNames.suggested(home = "CA", current = "CA"))
        assertEquals(listOf("CA", "US"), DialCountryNames.suggested(home = null, current = null))
    }

    /** ⛔ ONLY RANGES NOBODY CAN BE REACHED ON, and each one reads back as its own country. */
    @Test
    fun `the placeholder examples are the web's, and each reads in its own country`() {
        assertEquals("(416) 555-0199", DialCountryNames.nationalExample("CA"))
        assertEquals("020 7946 0958", DialCountryNames.nationalExample("GB"))
        assertEquals("", DialCountryNames.nationalExample("JP"))
        assertEquals("", DialCountryNames.nationalExample(null))
        for (iso in listOf("CA", "US", "GB")) {
            val example = DialCountryNames.nationalExample(iso)
            assertEquals(iso, example, DialPlan.read(example, iso).national)
            assertEquals(iso, iso, DialPlan.read(example, iso).iso)
        }
    }

    // ── The home country ─────────────────────────────────────────────────────

    @Test
    fun `the home country is where most of the numbers are`() {
        assertEquals("CA", DialCountries.homeCountry(listOf("+14165550100", "+12065550100", "+16475550100")))
        assertEquals("a tie goes first", "GB", DialCountries.homeCountry(listOf("+442079460100", "+14165550100")))
        assertNull(DialCountries.homeCountry(emptyList()))
        assertNull(DialCountries.homeCountry(listOf("not a number")))
        // ⚠️ TOLL-FREE NUMBERS DO NOT VOTE.
        assertEquals("CA", DialCountries.homeCountry(listOf("+18005550100", "+18885550100", "+14165550100")))
        assertNull(DialCountries.homeCountry(listOf("+18005550100")))
    }
}
