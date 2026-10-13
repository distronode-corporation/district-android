package com.distronode.districtai.l10n

import com.distronode.districtai.l10n.DistrictFormat.DateStyle
import com.distronode.districtai.l10n.DistrictFormat.Display
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DistrictFormat] against `contracts/i18n/format-vectors.json`, every vector, both languages.
 *
 * ⛔ THE FILE IS THE WEBSITE'S, BYTE FOR BYTE, AND THE ASSERTION IS EXACT. A vector that does not
 * match is a French or English screen that reads differently from the portal; the remedy is this
 * module, never the vector. Each category also asserts it checked at least one vector, so a renamed
 * function or category cannot leave a whole category untested.
 */
class DistrictFormatTest {

    private val categories: Map<String, List<JsonObject>> by lazy {
        val dir = System.getProperty("district.contracts.dir")
        assertTrue("district.contracts.dir is not set", !dir.isNullOrBlank())
        val root = Json.parseToJsonElement(File(dir, "i18n/format-vectors.json").readText()).jsonObject
        root.getValue("categories").jsonArray.associate { c ->
            val obj = c.jsonObject
            obj.getValue("category").jsonPrimitive.content to
                (obj["vectors"]?.jsonArray?.map { it.jsonObject } ?: emptyList())
        }
    }

    private fun vectors(category: String): List<JsonObject> =
        categories.getValue(category).also { assertTrue("no $category vectors", it.isNotEmpty()) }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun lang(tag: String) = if (tag == "fr") AppLanguage.FR else AppLanguage.EN

    private fun both(v: JsonObject, render: (AppLanguage) -> String) {
        assertEquals(v.toString(), v.str("en"), render(AppLanguage.EN))
        assertEquals(v.toString(), v.str("fr"), render(AppLanguage.FR))
    }

    @Test
    fun `money matches every vector`() {
        for (v in vectors("money")) {
            val language = lang(v.str("locale"))
            val options = v["options"]?.jsonObject ?: JsonObject(emptyMap())
            val actual = when (v.str("fn")) {
                "formatMoney" -> DistrictFormat.money(
                    minorUnits = v.getValue("minorUnits").jsonPrimitive.long,
                    currency = v.str("currency"),
                    language = language,
                    display = options["display"]?.let { Display.valueOf(it.jsonPrimitive.content.uppercase()) }
                        ?: Display.SYMBOL,
                    disambiguateDollar = options["disambiguateDollar"]?.jsonPrimitive?.boolean ?: false,
                )
                "formatMoneyOrBare" -> {
                    assertEquals(JsonNull, v["currency"] as JsonElement)
                    DistrictFormat.moneyBare(v.getValue("minorUnits").jsonPrimitive.long, language)
                }
                "formatIsoAmount" -> DistrictFormat.isoAmount(
                    BigDecimal(v.getValue("major").jsonPrimitive.content),
                    v.str("isoCode"),
                    language,
                )
                else -> error("unknown money function in $v")
            }
            assertEquals(v.toString(), v.str("output"), actual)
        }
    }

    @Test
    fun `dates match every vector`() {
        for (v in vectors("dates")) {
            val instant = Instant.parse(v.str("input"))
            val zone = ZoneId.of(v.str("timezone"))
            val style = DateStyle.valueOf(v.str("style").uppercase())
            both(v) { DistrictFormat.date(instant, zone, style, it) }
        }
    }

    @Test
    fun `times and date-times match every vector`() {
        for (v in vectors("times")) {
            val instant = Instant.parse(v.str("input"))
            val zone = ZoneId.of(v.str("timezone"))
            when (v.str("fn")) {
                "formatTime" -> both(v) { DistrictFormat.time(instant, zone, it) }
                "formatDateTime" -> both(v) { DistrictFormat.dateTime(instant, zone, it) }
                else -> error("unknown time function in $v")
            }
        }
    }

    @Test
    fun `durations match every vector`() {
        for (v in vectors("durations")) {
            val seconds = v.getValue("seconds").jsonPrimitive.int
            when (v.str("fn")) {
                "formatMinutesSeconds" -> both(v) { DistrictFormat.minutesSeconds(seconds, it) }
                "formatCallDuration" ->
                    assertEquals(v.toString(), v.str("output"), DistrictFormat.callDuration(seconds))
                else -> error("unknown duration function in $v")
            }
        }
    }

    @Test
    fun `numbers and percentages match every vector`() {
        for (v in vectors("numbers")) {
            both(v) { DistrictFormat.number(v.getValue("value").jsonPrimitive.double, it) }
        }
        for (v in vectors("percentages")) {
            both(v) { DistrictFormat.percent(v.getValue("ratio").jsonPrimitive.double, it) }
        }
    }

    @Test
    fun `money in a currency with no symbol here is written with its code`() {
        assertEquals("GBP${DistrictFormat.NBSP}1,204.25", DistrictFormat.money(120_425, "gbp", AppLanguage.EN))
        assertEquals(
            "1${DistrictFormat.NBSP}204,25${DistrictFormat.NBSP}GBP",
            DistrictFormat.money(120_425, "gbp", AppLanguage.FR),
        )
    }

    @Test
    fun `a negative amount carries its sign before the symbol in English and before the number in French`() {
        assertEquals("-$12.50", DistrictFormat.money(-1_250, "usd", AppLanguage.EN))
        assertEquals(
            "-USD${DistrictFormat.NBSP}12.50",
            DistrictFormat.money(-1_250, "usd", AppLanguage.EN, Display.CODE),
        )
        assertEquals("-12,50${DistrictFormat.NBSP}€", DistrictFormat.money(-1_250, "eur", AppLanguage.FR))
        assertEquals("-12.50", DistrictFormat.moneyBare(-1_250, AppLanguage.EN))
    }

    @Test
    fun `a month and day read as the medium date without its year`() {
        assertEquals("Aug 9", DistrictFormat.monthDay(9, 8, AppLanguage.EN))
        assertEquals("9 août", DistrictFormat.monthDay(9, 8, AppLanguage.FR))
        assertEquals("1er sept.", DistrictFormat.monthDay(1, 9, AppLanguage.FR))
    }

    @Test
    fun `a list reads as one phrase, with the serial comma in English only`() {
        assertEquals("", DistrictFormat.list(emptyList(), AppLanguage.EN))
        assertEquals("A", DistrictFormat.list(listOf("A"), AppLanguage.FR))
        assertEquals("A and B", DistrictFormat.list(listOf("A", "B"), AppLanguage.EN))
        assertEquals("A et B", DistrictFormat.list(listOf("A", "B"), AppLanguage.FR))
        assertEquals("A, B, and C", DistrictFormat.list(listOf("A", "B", "C"), AppLanguage.EN))
        assertEquals("A, B et C", DistrictFormat.list(listOf("A", "B", "C"), AppLanguage.FR))
    }

    @Test
    fun `the lists and plurals categories carry no vectors yet`() {
        // ⚠️ Pinned so the day the web adds them, this test fails and asks for them to be checked.
        assertEquals(emptyList<JsonObject>(), categories.getValue("lists"))
        assertEquals(emptyList<JsonObject>(), categories.getValue("plurals"))
    }
}
