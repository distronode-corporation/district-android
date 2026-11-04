package com.distronode.districtai.ui.dialer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Two readings Seer found in district-core-rust #63's port of this plan, checked here.
 *
 * ⛔ WHAT THE FIELD SHOWS IS READ AGAIN ON THE NEXT EDIT, so it must read back to the number it came
 * from: `+1 1 416 555 0199` pasted was shown as `(141) 655-50199`, and reading that again took its
 * leading `1` as the trunk prefix, a whole Canadian number with Call on.
 *
 * ⛔ EVERY COUNTRY WHOSE NUMBERS CARRY A TRUNK `0` DROPS IT: `0412 123 4567` typed in Venezuela sent
 * `+58 0412…`, a number that does not exist. The list is libphonenumber's national prefix `0`.
 */
class DialPlanReadBackTest {

    @Test
    fun `a pasted +1 1 number keeps its international form`() {
        val pasted = DialPlan.read("+1 1 416 555 0199", "CA")
        assertEquals(DialPlan.Status.TOO_LONG, pasted.status)
        assertNull(pasted.e164)
        assertEquals("+114165550199", pasted.national)
        val again = DialPlan.read(pasted.national, pasted.iso)
        assertEquals(DialPlan.Status.TOO_LONG, again.status)
        assertNull("never a callable Canadian number", again.e164)
        assertEquals(pasted.national, again.national)
    }

    @Test
    fun `the field after a paste leaves Call off`() {
        val state = DialerUiState(canDial = true, country = "CA", homeCountry = "CA").typed("+1 1 416 555 0199")
        assertEquals("+114165550199", state.entry)
        assertNull(state.reading.e164)
        assertEquals(false, state.canPlaceCall)
        // The control: a whole number in the same state is callable, so the line above is not vacuous.
        assertEquals(true, DialerUiState(canDial = true, country = "CA").typed("4165550199").canPlaceCall)
    }

    @Test
    fun `every ordinary reading reads back alike`() {
        val cases = listOf(
            "4165550199" to "CA", "020 7946 0958" to "GB", "8 495 123 4567" to "RU", "1300 123 456" to "AU",
            "06 1 234 5678" to "HU", "+1 800 555 0100" to "CA", "+7 701 123 4567" to null, "0412 123 4567" to "VE",
            "+44" to null, "1" to "CA",
        )
        for ((raw, country) in cases) {
            val first = DialPlan.read(raw, country)
            val second = DialPlan.read(first.national, first.iso ?: country)
            assertEquals("$raw in $country", first.e164, second.e164)
            assertEquals("$raw in $country", first.national, second.national)
        }
    }

    @Test
    fun `every trunk-zero country libphonenumber names is listed`() {
        // libphonenumber-js 1.13.14 metadata: every country of DialCountries.countries whose national
        // prefix is `0`. The same list is in district-ios, district-macos and district-core-rust.
        val expected = setOf(
            "AE", "AF", "AL", "AM", "AR", "AT", "AU", "AZ", "BA", "BD", "BE", "BG", "BO", "BR", "CD", "CH",
            "CN", "CO", "CU", "DE", "DZ", "EC", "EG", "ER", "ET", "FI", "FR", "GB", "GE", "GF", "GH", "GP",
            "HR", "ID", "IE", "IL", "IN", "IQ", "IR", "JO", "JP", "KE", "KG", "KH", "KI", "KP", "KR", "LA",
            "LB", "LI", "LK", "LR", "LT", "LY", "MA", "MC", "MD", "ME", "MG", "MK", "MM", "MN", "MQ", "MW",
            "MY", "NA", "NG", "NL", "NP", "NZ", "PE", "PH", "PK", "PM", "PS", "PY", "RE", "RO", "RS", "RW",
            "SA", "SD", "SE", "SI", "SK", "SL", "SO", "SS", "SY", "TH", "TR", "TW", "TZ", "UA", "UG", "UY",
            "VE", "VN", "XK", "YE", "ZA", "ZM", "ZW",
        )
        assertEquals(expected, DialCountries.trunkZero)
    }

    @Test
    fun `Venezuela, Ecuador and Uruguay drop their trunk zero`() {
        assertEquals("+584121234567", DialPlan.read("0412 123 4567", "VE").e164)
        assertEquals("+584121234567", DialPlan.read("+58 0412 123 4567", null).e164)
        assertEquals("+593991234567", DialPlan.read("099 123 4567", "EC").e164)
        assertEquals("+59899123456", DialPlan.read("099 123 456", "UY").e164)
        assertEquals("0412 123 4567", DialPlan.read("0412 123 4567", "VE").national)
    }
}
