// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.setup

import com.distronode.districtai.l10n.AppLanguage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The business facts read into forms and written back, within the service's own limits. */
class SetupFactsTest {

    private val facts = json(
        """
        {"timezone":"America/Toronto",
         "address":{"line1":"1 Rue","city":"Gatineau","region":"QC","postalCode":"J8X 1A1","country":"CA"},
         "weeklyHours":{"mon":[{"open":"09:00","close":"17:00"},{"open":"bad","close":"18:00"}],"sun":"closed","tue":[],
                        "wed":"open"},
         "services":[{"name":"Cleaning","price":"${'$'}120","enabled":true},
                     {"name":"Whitening","enabled":false},"junk"],
         "faqs":[{"question":"What does the business do?","answer":"Dentistry","enabled":true},
                 {"question":"Parking?","answer":"Yes","enabled":"yes"}]}
        """,
    )

    @Test
    fun `the stored facts read into the forms, strictly`() {
        assertEquals(
            listOf(Triple("Cleaning", "\$120", true), Triple("Whitening", "", false)),
            SetupFacts.services(facts).map { Triple(it.name, it.price, it.enabled) },
        )
        // ⛔ ONLY `true` IS ON: a drafted question stays off whatever else it carries.
        assertEquals(listOf(true, false), SetupFacts.faqs(facts).map { it.enabled })
        assertEquals(SetupAddressDraft("1 Rue", "Gatineau", "QC", "J8X 1A1", "CA"), SetupFacts.address(facts))
        assertEquals(SetupAddressDraft(), SetupFacts.address(null))
        assertEquals("Dentistry", SetupFacts.what(facts))
        assertEquals("", SetupFacts.what(null))
        assertTrue(SetupFacts.services(null).isEmpty())
    }

    @Test
    fun `hours read per day, closed and not set kept apart, unreadable periods dropped`() {
        val hours = SetupFacts.hours(facts["weeklyHours"] as JsonObject)
        assertEquals(SetupDayState.OPEN, hours.getValue(SetupWeekday.MON).state)
        assertEquals(listOf(540 to 1020), hours.getValue(SetupWeekday.MON).intervals.map { it.open to it.close })
        assertEquals(SetupDayState.CLOSED, hours.getValue(SetupWeekday.SUN).state)
        assertEquals(SetupDayState.NOT_SET, hours.getValue(SetupWeekday.TUE).state)
        assertEquals(SetupDayState.NOT_SET, hours.getValue(SetupWeekday.WED).state)
        assertEquals(SetupDayState.NOT_SET, SetupFacts.hours(null).getValue(SetupWeekday.FRI).state)
    }

    @Test
    fun `a row missing a field reads it as empty, and a day or period of another shape is dropped`() {
        val odd = json(
            """
            {"services":[{"price":"5"}],
             "faqs":[{"enabled":true}],
             "weeklyHours":{"thu":9,"fri":[{"open":"09:00","close":"late"}]}}
            """,
        )
        assertEquals(listOf("" to "5"), SetupFacts.services(odd).map { it.name to it.price })
        assertEquals(listOf("" to ""), SetupFacts.faqs(odd).map { it.question to it.answer })

        val hours = SetupFacts.hours(odd["weeklyHours"] as JsonObject)
        assertEquals(SetupDayState.NOT_SET, hours.getValue(SetupWeekday.THU).state)
        // An open time with no readable close is no period at all, never one that runs to midnight.
        assertEquals(SetupDayState.OPEN, hours.getValue(SetupWeekday.FRI).state)
        assertTrue(hours.getValue(SetupWeekday.FRI).intervals.isEmpty())

        // A row without the key counts as the blank key: the second keyless row is a duplicate.
        val keyless = json("""{"price":"5"}""")
        assertEquals(listOf(keyless), SetupFacts.appending(listOf(keyless, keyless), emptyList(), "name", 5))
    }

    @Test
    fun `an empty section is a clear and a blank optional part is left out`() {
        assertEquals(JsonNull, SetupFacts.json(emptyList<SetupServiceDraft>()))
        assertEquals(JsonNull, SetupFacts.jsonFaqs(emptyList()))
        assertEquals(JsonNull, SetupFacts.json(SetupAddressDraft()))
        assertEquals(JsonNull, SetupFacts.jsonHours(SetupFacts.hours(null)))
        assertEquals(
            json("""{"v":[{"name":"A","price":"5","enabled":true},{"name":"B","enabled":false}]}"""),
            wrap(SetupFacts.json(listOf(SetupServiceDraft(" A ", " 5 ", true), SetupServiceDraft("B", " ", false)))),
        )
        assertEquals(
            json("""{"v":[{"question":"Q","answer":"A","enabled":false}]}"""),
            wrap(SetupFacts.jsonFaqs(listOf(SetupFaqDraft(" Q ", " A ", false)))),
        )
        assertEquals(
            json("""{"city":"Lyon","country":"FR"}"""),
            SetupFacts.json(SetupAddressDraft(city = " Lyon ", country = "fr")),
        )
        assertEquals(
            json("""{"line1":"1 Rue","city":"Gatineau","region":"QC","postalCode":"J8X","country":"CA"}"""),
            SetupFacts.json(SetupAddressDraft("1 Rue", "Gatineau", "QC", "J8X", "CA")),
        )
    }

    private fun wrap(value: kotlinx.serialization.json.JsonElement): JsonObject = buildJsonObject { put("v", value) }

    @Test
    fun `hours write sorted, a not-set day absent, a closed day as closed`() {
        val hours = mapOf(
            SetupWeekday.MON to SetupDayDraft(
                SetupDayState.OPEN,
                listOf(SetupInterval(780, 1440), SetupInterval(540, 720)),
            ),
            SetupWeekday.TUE to SetupDayDraft(SetupDayState.NOT_SET),
            SetupWeekday.SUN to SetupDayDraft(SetupDayState.CLOSED),
        )
        assertEquals(
            json("""{"mon":[{"open":"09:00","close":"12:00"},{"open":"13:00","close":"24:00"}],"sun":"closed"}"""),
            SetupFacts.jsonHours(hours),
        )
    }

    @Test
    fun `appending skips duplicates by key, case-insensitively, up to the cap`() {
        val current = listOf(json("""{"name":"Cleaning","extra":1}"""))
        val incoming = listOf(
            """{"name":" cleaning "}""",
            """{"name":"Whitening"}""",
            """{"name":"Braces"}"""
        ).map(::json)
        assertEquals(
            listOf("Cleaning", "Whitening"),
            SetupFacts.appending(incoming, current, "name", max = 2).map { it.str("name") },
        )
        assertEquals(1, SetupFacts.appending(incoming, current, "name", max = 2)[0]["extra"].toString().toInt())
    }

    @Test
    fun `rows are checked against the service's limits`() {
        assertTrue(SetupFacts.isValid(SetupServiceDraft("Cut", "", true)))
        assertFalse(SetupFacts.isValid(SetupServiceDraft(" ", "", true)))
        assertFalse(SetupFacts.isValid(SetupServiceDraft("x".repeat(121), "", true)))
        assertFalse(SetupFacts.isValid(SetupServiceDraft("Cut", "x".repeat(41), true)))
        assertTrue(SetupFacts.isValid(SetupFaqDraft("Q", "A", true)))
        assertFalse(SetupFacts.isValid(SetupFaqDraft("", "A", true)))
        assertFalse(SetupFacts.isValid(SetupFaqDraft("x".repeat(301), "A", true)))
        assertFalse(SetupFacts.isValid(SetupFaqDraft("Q", "", true)))
        assertFalse(SetupFacts.isValid(SetupFaqDraft("Q", "x".repeat(1001), true)))
    }

    @Test
    fun `an address is empty, or a city and a two-letter country within the limits`() {
        assertTrue(SetupFacts.isValid(SetupAddressDraft()))
        assertTrue(SetupFacts.isValid(SetupAddressDraft(city = "Lyon", country = "fr")))
        assertFalse(SetupFacts.isValid(SetupAddressDraft(city = " ", country = "FR")))
        assertFalse(SetupFacts.isValid(SetupAddressDraft(city = "Lyon", country = "FRA")))
        assertFalse(SetupFacts.isValid(SetupAddressDraft(line1 = "x".repeat(201), city = "Lyon", country = "FR")))
        assertFalse(SetupFacts.isValid(SetupAddressDraft(city = "x".repeat(101), country = "FR")))
        assertFalse(SetupFacts.isValid(SetupAddressDraft(city = "Lyon", region = "x".repeat(101), country = "FR")))
        assertFalse(SetupFacts.isValid(SetupAddressDraft(city = "Lyon", postalCode = "x".repeat(21), country = "FR")))
    }

    @Test
    fun `a day is one to four periods, each closing after it opens, none overlapping`() {
        fun open(vararg periods: Pair<Int, Int>) =
            SetupDayDraft(SetupDayState.OPEN, periods.map { SetupInterval(it.first, it.second) })
        assertTrue(SetupFacts.isValid(SetupDayDraft(SetupDayState.CLOSED)))
        assertTrue(SetupFacts.isValid(open(540 to 720, 720 to 1020)))
        assertFalse(SetupFacts.isValid(open()))
        assertFalse(SetupFacts.isValid(open(1 to 2, 3 to 4, 5 to 6, 7 to 8, 9 to 10)))
        assertFalse(SetupFacts.isValid(open(720 to 540)))
        assertFalse(SetupFacts.isValid(open(540 to 800, 700 to 1020)))
    }

    @Test
    fun `clock times read and write as the service keys them`() {
        assertEquals(570, SetupInterval.minutes("09:30"))
        assertEquals(1440, SetupInterval.minutes("24:00"))
        assertNull(SetupInterval.minutes("24:30"))
        assertNull(SetupInterval.minutes(null))
        assertEquals("09:05", SetupInterval.text(545))
        assertEquals("24:00", SetupInterval.text(1440))
        assertEquals(540 to 1020, SetupInterval.standard().let { it.open to it.close })
        assertTrue(SetupAddressDraft(line1 = " ").isEmpty)
        assertNull(json("""{"a":1}""").str("a"))
        assertNull(json("""{"a":"x"}""").obj("a"))
    }

    @Test
    fun `places imply a zone, and multi-zone places say so`() {
        assertEquals(SetupZone("America/Toronto", multiZone = true), SetupPlaces.zone("CA", "on"))
        assertNull(SetupPlaces.zone("CA", ""))
        assertEquals(SetupZone("Europe/Paris"), SetupPlaces.zone("FR", ""))
        assertNull(SetupPlaces.zone("ZZ", ""))
        assertEquals("CA", SetupPlaces.defaultCountry("ca"))
        assertEquals("US", SetupPlaces.defaultCountry("us"))
        assertEquals("", SetupPlaces.defaultCountry("eu"))
        assertEquals(listOf("CA", "US"), SetupPlaces.countries.take(2))
        assertEquals(41, SetupPlaces.countries.size)
        assertNull(SetupPlaces.subdivisions("FR"))
        assertEquals(51, SetupPlaces.subdivisions("US")!!.size)
    }

    @Test
    fun `step 4 offers the country's zones and the stored one, once each, sorted`() {
        assertEquals(listOf("Asia/Tokyo", "Europe/Paris"), SetupPlaces.zones("FR", including = "Asia/Tokyo"))
        assertEquals(listOf("Europe/Paris"), SetupPlaces.zones("FR", including = "Europe/Paris"))
        assertEquals(listOf("Europe/Paris"), SetupPlaces.zones("FR", including = ""))
        assertTrue(SetupPlaces.zones("CA", including = "").contains("America/Halifax"))
        assertEquals(SetupPlaces.zones("CA", including = "").distinct(), SetupPlaces.zones("CA", including = ""))
    }

    @Test
    fun `names come in the app's language`() {
        val bc = SetupPlaces.provinces.first { it.code == "BC" }
        assertEquals("British Columbia", bc.name(AppLanguage.EN))
        assertEquals("Colombie-Britannique", bc.name(AppLanguage.FR))
        assertEquals("Germany", SetupPlaces.countryName("DE", AppLanguage.EN))
        assertEquals("Allemagne", SetupPlaces.countryName("DE", AppLanguage.FR))
    }
}
