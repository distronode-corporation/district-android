package com.distronode.districtai.ui.dialer

/**
 * Reading what the operator types into the dialler, in a country: dialler design A.
 *
 * ⛔ THE COUNTRY IS ALWAYS ON SCREEN BEFORE THE CALL, AND THAT IS WHAT MAKES IT SAFE TO BUILD THE
 * NUMBER HERE. The billed call this screen exists to prevent was a Canadian mobile typed as
 * `+4165550123`, which is `+41`, SWITZERLAND. Until design A the app sent the text exactly as typed
 * and showed a grouped copy beside it. Now the number is typed the local way under a flag and
 * calling code the operator can see, and the line under the field says "Rings Canada · +1 416 555
 * 0199" before Call: the whole international number is disclosed, so nothing is sent that was not
 * shown.
 *
 * ⚠️ THE SERVER STILL NORMALISES ITS OWN COPY. What leaves here is E.164 (`+`, the calling code,
 * the national number), which `normalizePhoneNumber` passes through unchanged, so the Do-Not-Call
 * check and the dial run against the number that was shown.
 *
 * ⛔ PURE. A function of the text and a country, with no I/O and no clock, so every rule is pinned
 * by `DialPlanTest` without a device.
 *
 * ⚠️ A PORT OF district-ios `DialPlan.swift` (PR #95, with PR #96's +7 fix), KEPT IN STEP WITH IT
 * RULE FOR RULE. Not libphonenumber: that is a multi-megabyte dependency and a second opinion about
 * numbers, and its metadata would disagree with the iOS, Mac and web dialers in exactly the cases
 * this file pins.
 */
object DialPlan {

    /** What one entry is, in the order the screen acts on it. */
    enum class Status {
        /** Nothing typed. */
        EMPTY,

        /** A number on its way: too few digits yet, or a `+` code not finished. */
        TYPING,

        /** Dialable: [Reading.e164] is set. */
        READY,

        /** An emergency short code. ⛔ Refused: the phone's own dialler is the remedy. */
        EMERGENCY,

        /** More digits than the country's numbers have, or than E.164 allows. */
        TOO_LONG,

        /** Digits with no country to read them in and no `+`. */
        NEEDS_COUNTRY,
    }

    /** One entry, read. */
    data class Reading(
        val status: Status,
        /** The country the number rings, or null: none chosen, or a `+` code no listed country uses. */
        val iso: String?,
        /** The calling code, without `+`, or empty. */
        val callingCode: String = "",
        /** The national significant number: no trunk `0` or `1`, no calling code. */
        val nationalNumber: String = "",
        /** What the field shows: the local form, as it would be dialled in [iso]. */
        val national: String = "",
        /** The number as the world dials it, grouped: `+1 416 555 0199`. */
        val international: String = "",
        /** True when the entry named its own country (`+`, `011`, `00`). */
        val namedItsCountry: Boolean = false,
        /** What is sent to the dial API. Set exactly when [status] is [Status.READY]. */
        val e164: String? = null,
    )

    /** The E.164 floor, and the server's own: the dial route rejects fewer than 8 after normalising. */
    const val MINIMUM_DIGITS = 8

    /** The E.164 ceiling. */
    const val MAXIMUM_DIGITS = 15

    /** The longest calling code in [DialCountries.countries]. */
    const val MAXIMUM_CALLING_CODE_DIGITS = 3

    /**
     * Short codes that must never be treated as a number to validate.
     *
     * ⚠️ district-core-swift's `DialEntry.emergencyNumbers`, word for word: `112` and `911` are the
     * two every handset must recognise (3GPP TS 22.101 §10.1), the rest the widely deployed national
     * codes. ⛔ INCOMPLETE AND SAFE: a code this misses is still under [MINIMUM_DIGITS], so it is
     * never dialable; adding one improves the message, omitting one never places a call.
     *
     * ⚠️ MATCHED WHOLE, NEVER AS A PREFIX: `+1 911 555 0100` is an ordinary number.
     */
    val emergencyNumbers: Set<String> = setOf(
        "000", "110", "111", "112", "113", "115", "117", "118", "119", "911", "999",
    )

    /**
     * ⚠️ ASCII ONLY, as the server's `\d` is. `Char.isDigit` is true for Arabic-Indic digits too, and
     * the two sides would disagree about what counts as a digit.
     */
    fun isDigit(character: Char): Boolean = character in '0'..'9'

    /**
     * Read [raw] in [country] (an ISO 3166 code, null when none is chosen).
     *
     * ⚠️ THE EMERGENCY TEST COMES FIRST, BEFORE ANY COUNTRY: `911` must reach the hand-off sentence,
     * never "choose a country".
     *
     * ⛔ WHAT THE FIELD SHOWS ([Reading.national]) IS READ AGAIN ON THE NEXT EDIT, SO IT MUST READ BACK
     * TO THE SAME NUMBER (Seer on district-core-rust #63). `+1 1 416 555 0199` pasted was shown as
     * `(141) 655-50199`, and reading that took its leading `1` as the trunk prefix: `416 555 0199`, a
     * whole Canadian number with Call on. A local form that would not read back is shown in its
     * international form, `+114165550199`, which does.
     */
    fun read(raw: String, country: String?): Reading {
        val reading = readEntry(raw, country)
        // ⚠️ NOT WHILE THE NATIONAL NUMBER IS EMPTY: `+44` alone shows an empty field under the flag,
        // and there is no number yet to read back.
        if (reading.status == Status.EMERGENCY || reading.national.startsWith("+")) return reading
        if (reading.callingCode.isEmpty() || reading.nationalNumber.isEmpty()) return reading
        val again = readEntry(reading.national, reading.iso)
        val same = again.callingCode == reading.callingCode && again.nationalNumber == reading.nationalNumber
        return if (same) reading else reading.copy(national = "+" + reading.callingCode + reading.nationalNumber)
    }

    private fun readEntry(raw: String, country: String?): Reading {
        val compact = raw.filter { it == '+' || isDigit(it) }
        var plus = compact.startsWith("+")
        var digits = compact.filter(::isDigit)
        if (isEmergency(compact)) {
            return Reading(Status.EMERGENCY, country, national = digits, international = digits)
        }
        if (!plus) {
            dropExitCode(digits, country)?.let { rest ->
                digits = rest
                plus = true
            }
        }
        if (digits.isEmpty()) {
            val shown = if (plus) "+" else ""
            return Reading(
                status = if (plus) Status.TYPING else Status.EMPTY,
                iso = if (plus) null else country,
                national = shown,
                international = shown,
                namedItsCountry = plus,
            )
        }
        return if (plus) readInternational(digits, country) else readNational(digits, country)
    }

    /**
     * Whether the entry is an emergency short code, bare or after a `+` or `+1`.
     *
     * ⚠️ THE `+1` SHAPE IS CHECKED BECAUSE THE OLD HINT CREATED IT: "start with the country code"
     * plus `911` is `+1911`.
     */
    private fun isEmergency(compact: String): Boolean {
        val digits = compact.removePrefix("+")
        if (digits in emergencyNumbers) return true
        return digits.startsWith("1") && digits.drop(1) in emergencyNumbers
    }

    /**
     * `011` from a North American country, `00` from anywhere else, is the exit code that stands
     * for `+`. ⚠️ With no country chosen either one is taken, because there is no national number to
     * confuse it with.
     */
    private fun dropExitCode(digits: String, country: String?): String? {
        val nanp = country?.let(DialCountries::isNanp) ?: false
        return when {
            (nanp || country == null) && digits.startsWith("011") -> digits.drop("011".length)
            !nanp && digits.startsWith("00") -> digits.drop("00".length)
            else -> null
        }
    }

    private fun readNational(digits: String, country: String?): Reading {
        val code = country?.let(DialCountries::callingCode)
        if (country == null || code == null) {
            return Reading(Status.NEEDS_COUNTRY, null, national = digits, international = digits)
        }
        var nsn = digits
        var typedTrunk = false
        val trunk = DialCountries.trunkPrefix(country)
        if (code == "1" && nsn.startsWith("1")) {
            nsn = nsn.drop(1)
            typedTrunk = true
        } else if (trunk != null) {
            // ⚠️ HALF OF A TWO-DIGIT TRUNK (Hungary's `06`) IS SHOWN AS TYPED, not read as a number.
            if (nsn.length < trunk.length && trunk.startsWith(nsn)) {
                return Reading(Status.TYPING, country, callingCode = code, national = nsn, international = "+$code")
            }
            // ⚠️ AND THE OTHER PREFIXES libphonenumber STRIPS, longest first
            // ([DialCountries.nationalPrefixes]): Lithuania's old `8`, Belarus's `80`.
            DialCountries.nationalPrefixes(country).firstOrNull { nsn.startsWith(it) }?.let { typed ->
                nsn = nsn.drop(typed.length)
                typedTrunk = true
            }
        }
        // ⚠️ A CODE SHARED BY SEVERAL COUNTRIES IS READ AGAIN AS EACH DIGIT ARRIVES, exactly as a
        // pasted `+` number is: +1 by its area code (416 is Canada, 206 the US, 876 Jamaica), +7 by its
        // first digit. Typed one key at a time, `+7` moves the flag to Russia and the rest arrives here
        // as a national number; read only in the country on the button, `701…` would stay "Rings
        // Russia" for a Kazakh number (Seer on district-macos #91, fixed in district-ios #96).
        val iso = countryForCode(code, nsn, first = country, current = country)
        return finish(iso, code, nsn, typedTrunk, plus = false)
    }

    private fun readInternational(digits: String, country: String?): Reading {
        val longest = minOf(MAXIMUM_CALLING_CODE_DIGITS, digits.length)
        for (length in longest downTo 1) {
            val code = digits.take(length)
            val first = DialCountries.firstCountryForCode(code) ?: continue
            var nsn = digits.drop(length)
            val iso = countryForCode(code, nsn, first, current = country)
            // ⚠️ "+44 (0)20 …" PASTED FROM A SIGNATURE: the trunk 0 is not dialled after a code.
            if (iso in DialCountries.trunkZero && nsn.startsWith("0")) nsn = nsn.drop(1)
            return finish(iso, code, nsn, typedTrunk = false, plus = true)
        }
        // ⚠️ AN UNKNOWN CODE IS SAID PLAINLY AND STAYS DIALABLE: this client is not the authority on
        // which codes exist.
        val shown = "+$digits"
        val status = when {
            digits.length > MAXIMUM_DIGITS -> Status.TOO_LONG
            digits.length >= MINIMUM_DIGITS -> Status.READY
            else -> Status.TYPING
        }
        return Reading(
            status = status,
            iso = null,
            national = shown,
            international = shown,
            namedItsCountry = true,
            e164 = shown.takeIf { status == Status.READY },
        )
    }

    /**
     * Which country a calling code rings for [nsn]: the code's [first] country, except for a code
     * several countries share, which is decided by the digits after it.
     */
    private fun countryForCode(code: String, nsn: String, first: String, current: String?): String = when (code) {
        "1" -> DialCountries.nanpCountry(nsn, fallback = current)
        // ⚠️ +7 IS RUSSIA AND KAZAKHSTAN; Kazakh numbers start 6 or 7. Before that digit the +7
        // country on screen stays, as a +1 one does before its area code.
        "7" -> when {
            nsn.isEmpty() -> if (current == "KZ") "KZ" else "RU"
            nsn.startsWith("6") || nsn.startsWith("7") -> "KZ"
            else -> "RU"
        }
        else -> first
    }

    private fun finish(iso: String, code: String, nsn: String, typedTrunk: Boolean, plus: Boolean): Reading {
        val range = DialGrouping.lengths(iso, code)
        val total = code.length + nsn.length
        val status = when {
            total > MAXIMUM_DIGITS || nsn.length > range.last -> Status.TOO_LONG
            nsn.length >= range.first && total >= MINIMUM_DIGITS -> Status.READY
            else -> Status.TYPING
        }
        val international = if (nsn.isEmpty()) {
            "+$code"
        } else {
            "+$code " + DialGrouping.chunk(nsn, DialGrouping.groups(iso, nsn)).joinToString(" ")
        }
        return Reading(
            status = status,
            iso = iso,
            callingCode = code,
            nationalNumber = nsn,
            national = DialGrouping.formatNational(iso, code, nsn, typedTrunk),
            international = international,
            namedItsCountry = plus,
            e164 = "+$code$nsn".takeIf { status == Status.READY },
        )
    }
}
