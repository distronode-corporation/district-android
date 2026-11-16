package com.distronode.districtai.ui.setup

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The business facts the receptionist answers from, read out of the stored JSON into the forms step 1
 * and step 4 edit, and written back.
 *
 * ⛔ THE LIMITS ARE THE SERVICE'S (`BUSINESS_FACTS_LIMITS`), so a save this refuses is one the route
 * would refuse too. The route validates again.
 *
 * ⚠️ A SECTION PRESENT IN A PATCH REPLACES THE STORED ONE WHOLE, a list is sent in full, and an
 * explicit null clears it; an absent section is left alone.
 */
@Suppress("TooManyFunctions")
object SetupFacts {
    const val ADDRESS_LINE = 200
    const val ADDRESS_PART = 100
    const val POSTAL_CODE = 20
    const val INTERVALS_PER_DAY = 4
    const val SERVICES = 50
    const val SERVICE_NAME = 120
    const val SERVICE_PRICE = 40
    const val FAQS = 50
    const val FAQ_QUESTION = 300
    const val FAQ_ANSWER = 1000

    // ── Reading ─────────────────────────────────────────────────────────────────

    fun services(facts: JsonObject?): List<SetupServiceDraft> = rows(facts, "services").map { row ->
        SetupServiceDraft(
            name = row.str("name").orEmpty(),
            price = row.str("price").orEmpty(),
            enabled = row["enabled"] == JsonPrimitive(true),
        )
    }

    fun faqs(facts: JsonObject?): List<SetupFaqDraft> = rows(facts, "faqs").map { row ->
        SetupFaqDraft(
            question = row.str("question").orEmpty(),
            answer = row.str("answer").orEmpty(),
            enabled = row["enabled"] == JsonPrimitive(true),
        )
    }

    fun address(facts: JsonObject?): SetupAddressDraft {
        val address = facts?.obj("address")
        return SetupAddressDraft(
            line1 = address?.str("line1").orEmpty(),
            city = address?.str("city").orEmpty(),
            region = address?.str("region").orEmpty(),
            postalCode = address?.str("postalCode").orEmpty(),
            country = address?.str("country").orEmpty(),
        )
    }

    /** Every weekday, [SetupDayState.NOT_SET] where the stored hours say nothing. */
    fun hours(weeklyHours: JsonObject?): Map<SetupWeekday, SetupDayDraft> = SetupWeekday.entries.associateWith { day ->
        val stored = weeklyHours?.get(day.wire)
        when {
            stored is JsonPrimitive && stored.isString && stored.content == CLOSED ->
                SetupDayDraft(SetupDayState.CLOSED)
            stored is JsonArray && stored.isNotEmpty() -> SetupDayDraft(
                state = SetupDayState.OPEN,
                intervals = stored.filterIsInstance<JsonObject>().mapNotNull { row ->
                    val open = SetupInterval.minutes(row.str("open"))
                    val close = SetupInterval.minutes(row.str("close"))
                    if (open != null && close != null) SetupInterval(open, close) else null
                },
            )
            else -> SetupDayDraft(SetupDayState.NOT_SET)
        }
    }

    /** The web's "what you do" line: the answer to the question step 1 writes, in either language. */
    fun what(facts: JsonObject?): String =
        rows(facts, "faqs").firstOrNull { it.str("question") in SetupWire.whatQuestions }?.str("answer").orEmpty()

    /** The object rows of a stored list, every key they carry. */
    fun rows(facts: JsonObject?, key: String): List<JsonObject> =
        (facts?.get(key) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    // ── Writing ─────────────────────────────────────────────────────────────────

    fun json(services: List<SetupServiceDraft>): JsonElement =
        if (services.isEmpty()) {
            JsonNull
        } else {
            JsonArray(
                services.map { service ->
                    buildJsonObject {
                        put("name", service.name.trim())
                        if (service.price.isNotBlank()) put("price", service.price.trim())
                        put("enabled", service.enabled)
                    }
                },
            )
        }

    fun jsonFaqs(faqs: List<SetupFaqDraft>): JsonElement =
        if (faqs.isEmpty()) {
            JsonNull
        } else {
            JsonArray(
                faqs.map { faq ->
                    buildJsonObject {
                        put("question", faq.question.trim())
                        put("answer", faq.answer.trim())
                        put("enabled", faq.enabled)
                    }
                },
            )
        }

    /** ⚠️ AN EMPTY ADDRESS IS A CLEAR (null), and a blank optional part is left out, never sent as "". */
    fun json(address: SetupAddressDraft): JsonElement =
        if (address.isEmpty) {
            JsonNull
        } else {
            buildJsonObject {
                if (address.line1.isNotBlank()) put("line1", address.line1.trim())
                put("city", address.city.trim())
                if (address.region.isNotBlank()) put("region", address.region.trim())
                if (address.postalCode.isNotBlank()) put("postalCode", address.postalCode.trim())
                put("country", address.country.trim().uppercase())
            }
        }

    /**
     * ⚠️ A DAY LEFT "NOT SET" IS ABSENT, WHICH IS NOT "CLOSED": the receptionist says nothing about an
     * absent day. No day set at all clears the section.
     */
    fun jsonHours(hours: Map<SetupWeekday, SetupDayDraft>): JsonElement {
        val out = buildJsonObject {
            SetupWeekday.entries.forEach { day ->
                val draft = hours[day]
                when (draft?.state) {
                    SetupDayState.CLOSED -> put(day.wire, CLOSED)
                    SetupDayState.OPEN -> put(
                        day.wire,
                        JsonArray(
                            draft.intervals.sortedBy { it.open }.map { interval ->
                                buildJsonObject {
                                    put("open", SetupInterval.text(interval.open))
                                    put("close", SetupInterval.text(interval.close))
                                }
                            },
                        ),
                    )
                    else -> Unit
                }
            }
        }
        return if (out.isEmpty()) JsonNull else out
    }

    /**
     * Appends [incoming] rows to [current] by a case-insensitive [key], skipping duplicates, up to [max]
     * (the web's `appendUnique`).
     *
     * ⛔ THE STORED ROWS GO BACK AS THEY CAME, every key they carry, because the section is replaced
     * whole; and the incoming ones keep the lookup's `enabled: false`.
     */
    fun appending(incoming: List<JsonObject>, current: List<JsonObject>, key: String, max: Int): List<JsonObject> {
        val normalise = { row: JsonObject -> row.str(key).orEmpty().trim().lowercase() }
        val seen = current.map(normalise).toMutableSet()
        val out = current.toMutableList()
        for (row in incoming) {
            if (out.size >= max) break
            if (seen.add(normalise(row))) out += row
        }
        return out
    }

    // ── Checking ────────────────────────────────────────────────────────────────

    fun isValid(service: SetupServiceDraft): Boolean {
        val name = service.name.trim()
        return name.isNotEmpty() && name.length <= SERVICE_NAME && service.price.trim().length <= SERVICE_PRICE
    }

    fun isValid(faq: SetupFaqDraft): Boolean {
        val question = faq.question.trim()
        val answer = faq.answer.trim()
        return question.isNotEmpty() && question.length <= FAQ_QUESTION &&
            answer.isNotEmpty() && answer.length <= FAQ_ANSWER
    }

    /** Empty, or at least a city and a two-letter country code, each within its limit. */
    fun isValid(address: SetupAddressDraft): Boolean =
        address.isEmpty || (
            address.city.isNotBlank() &&
                COUNTRY_CODE.matches(address.country.trim().uppercase()) &&
                address.line1.trim().length <= ADDRESS_LINE &&
                address.city.trim().length <= ADDRESS_PART &&
                address.region.trim().length <= ADDRESS_PART &&
                address.postalCode.trim().length <= POSTAL_CODE
            )

    /** One to four periods, each closing after it opens, none overlapping (`DayHoursSchema`). */
    fun isValid(day: SetupDayDraft): Boolean {
        if (day.state != SetupDayState.OPEN) return true
        if (day.intervals.isEmpty() || day.intervals.size > INTERVALS_PER_DAY) return false
        val sorted = day.intervals.sortedBy { it.open }
        return sorted.all { it.close > it.open } &&
            sorted.zipWithNext().all { (earlier, later) -> later.open >= earlier.close }
    }

    private const val CLOSED = "closed"
    private val COUNTRY_CODE = Regex("^[A-Z]{2}$")
}

/** A source of row ids that survive edits, so a list row keeps its identity while it is typed into. */
private val rowIds = AtomicLong()

internal fun nextRowId(): Long = rowIds.incrementAndGet()

/** One service row in step 4. ⚠️ [id] is the row's identity on screen only; it is never sent. */
data class SetupServiceDraft(
    val name: String,
    val price: String,
    val enabled: Boolean,
    val id: Long = nextRowId(),
)

/** One question row in step 4. ⚠️ [id] is the row's identity on screen only; it is never sent. */
data class SetupFaqDraft(
    val question: String,
    val answer: String,
    val enabled: Boolean,
    val id: Long = nextRowId(),
)

data class SetupAddressDraft(
    val line1: String = "",
    val city: String = "",
    val region: String = "",
    val postalCode: String = "",
    val country: String = "",
) {
    val isEmpty: Boolean get() = listOf(line1, city, region, postalCode, country).all { it.isBlank() }
}

/** `WEEKDAYS`, Monday first, as the service keys them. */
enum class SetupWeekday(val wire: String) {
    MON("mon"),
    TUE("tue"),
    WED("wed"),
    THU("thu"),
    FRI("fri"),
    SAT("sat"),
    SUN("sun"),
}

enum class SetupDayState { NOT_SET, CLOSED, OPEN }

data class SetupDayDraft(val state: SetupDayState, val intervals: List<SetupInterval> = emptyList())

/** One opening period, in minutes after midnight. [close] may be 1440 ("24:00"). */
data class SetupInterval(val open: Int, val close: Int, val id: Long = nextRowId()) {
    companion object {
        const val MINUTES_PER_HOUR = 60
        const val MIDNIGHT = 24 * MINUTES_PER_HOUR

        fun standard(): SetupInterval = SetupInterval(open = 9 * MINUTES_PER_HOUR, close = 17 * MINUTES_PER_HOUR)

        /** "09:30" is 570; "24:00" is 1440; anything else null. */
        fun minutes(text: String?): Int? = when {
            text == null -> null
            text == "24:00" -> MIDNIGHT
            CLOCK.matches(text) -> text.take(2).toInt() * MINUTES_PER_HOUR + text.takeLast(2).toInt()
            else -> null
        }

        fun text(minutes: Int): String =
            "%02d:%02d".format(java.util.Locale.ROOT, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)

        private val CLOCK = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    }
}
