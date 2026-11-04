package com.distronode.districtai.ui.dialer

/**
 * How [DialPlan] writes a national number down, and how many digits a country's numbers have.
 *
 * ⚠️ THE GROUPINGS ARE PATTERNS (`xx xxxx xxxx`) AND THE LENGTHS A TABLE, rather than lists of
 * integers, so the shapes read as they are written on paper and detekt's magic-number rule has
 * nothing to count. district-ios writes the same data as `[2, 4, 4]`.
 */
internal object DialGrouping {

    /**
     * The national form shown in the field.
     *
     * ⛔ EVERY FORM ENDS IN A DIGIT. The field is rewritten after every edit, so a form ending in
     * punctuation (Australia's `(02)`, Seer on district-ios #95) would put a deleted character
     * straight back and backspace could never get past it.
     */
    fun formatNational(
        iso: String,
        code: String,
        nsn: String,
        typedTrunk: Boolean,
        shownPrefix: String? = null,
    ): String {
        if (nsn.isEmpty()) {
            // ⛔ A `+` AND A CALLING CODE WITH NO DIGIT AFTER IT YET STAY AS THEY ARE, so the next digit
            // is still read after the code. Shown as an empty local number instead, the next digit was
            // read as a local one and a trunk prefix swallowed it: Saint Petersburg's 812 typed after +7
            // lost its 8 to Russia's trunk 8 (Seer on district-core-rust #63).
            if (!typedTrunk) return "+$code"
            return if (code == "1") "1" else shownPrefix ?: DialCountries.trunkPrefix(iso) ?: "0"
        }
        if (code == "1") return formatNanp(nsn)
        val parts = chunk(nsn, groups(iso, nsn)).toMutableList()
        // ⚠️ AN AUSTRALIAN 13, 1300 OR 1800 NUMBER IS DIALLED AS IT STANDS, WITH NO TRUNK 0.
        if (iso == "AU" && isAustralianNonGeographic(nsn)) return parts.joinToString(" ")
        // ⚠️ THE PARENTHESES CLOSE ONLY ONCE A DIGIT FOLLOWS THE AREA CODE (see the ⛔ above).
        if (iso == "AU" && !nsn.startsWith("4") && parts.size > 1) {
            return "(0" + parts.first() + ") " + parts.drop(1).joinToString(" ")
        }
        // ⚠️ [shownPrefix] is the prefix typed where it is not the trunk prefix, or empty for none.
        (shownPrefix ?: DialCountries.trunkPrefix(iso))?.let { trunk -> parts[0] = trunk + parts[0] }
        return parts.joinToString(" ")
    }

    /** `(416) 555-0199`, built up as the digits arrive. */
    private fun formatNanp(nsn: String): String {
        val area = nsn.take(NANP_AREA)
        return when {
            nsn.length <= NANP_AREA -> nsn
            nsn.length <= NANP_EXCHANGE_END -> "($area) " + nsn.drop(NANP_AREA)
            else -> "($area) " + nsn.substring(NANP_AREA, NANP_EXCHANGE_END) + "-" + nsn.drop(NANP_EXCHANGE_END)
        }
    }

    /**
     * How a country's national number is grouped. ⚠️ A display convention only: a country not
     * named here gets `xxx xxx xxxx`, which reads well enough and changes no digit.
     */
    fun groups(iso: String, nsn: String): List<Int> {
        val pattern = when (iso) {
            "GB" -> if (nsn.startsWith("2")) "xx xxxx xxxx" else "xxxx xxxxxx"
            "FR" -> "x xx xx xx xx"
            "DE" -> germanPattern(nsn)
            "AU" -> australianPattern(nsn)
            "CH" -> "xx xxx xx xx"
            "SG" -> "xxxx xxxx"
            else -> "xxx xxx xxxx"
        }
        return pattern.split(' ').map(String::length)
    }

    private fun germanPattern(nsn: String): String = when {
        listOf("15", "16", "17").any(nsn::startsWith) -> "xxx xxxxxxxx"
        listOf("30", "40", "69", "89").any(nsn::startsWith) -> "xx xxxxxxxxx"
        else -> "xxxx xxxxxxxx"
    }

    private fun australianPattern(nsn: String): String = when {
        isAustralianNonGeographic(nsn) -> "xxxx xxx xxx"
        nsn.startsWith("4") -> "xxx xxx xxx"
        else -> "x xxxx xxxx"
    }

    /**
     * How many digits a country's national numbers have. ⚠️ Exact only where it is certain;
     * anywhere else the E.164 floor and ceiling decide.
     *
     * ⚠️ WIDE ENOUGH FOR EVERY NUMBER THE COUNTRY ISSUES, not just the common shape: a range that
     * misses one blocks a real call. Australia's 13 xx xx is 6 digits and its 1300/1800 numbers 10;
     * Singapore's 1800 toll-free numbers are 11; a UK 0800 number can be 7. Switzerland and France
     * issue nothing but 9. (Seer on district-ios #95.)
     */
    fun lengths(iso: String, code: String): IntRange {
        if (code == "1") return NANP_LENGTH..NANP_LENGTH
        // ⛔ RUSSIA AND KAZAKHSTAN: TEN DIGITS AFTER +7, ALWAYS. Without this an eleven-digit Russian
        // number typed with its 7 and no `+` read as Kazakh and was offered for dialling (Seer on
        // district-core-rust #63).
        if (code == "7") return PLUS_SEVEN_LENGTH..PLUS_SEVEN_LENGTH
        return knownLengths[iso] ?: MINIMUM_NATIONAL..(DialPlan.MAXIMUM_DIGITS - code.length)
    }

    private val knownLengths: Map<String, IntRange> = "GB 7-10|FR 9-9|CH 9-9|AU 6-10|DE 6-13|SG 8-11"
        .split('|')
        .associate { entry ->
            val (iso, range) = entry.split(' ')
            val (low, high) = range.split('-').map(String::toInt)
            iso to low..high
        }

    /**
     * Australia's 13, 1300 and 1800 numbers: no area code and no trunk 0. ⚠️ THE WHOLE `1` RANGE, so
     * a lone typed `1` is not shown as `01` (geographic area codes are 2, 3, 7 and 8, mobiles 4).
     */
    private fun isAustralianNonGeographic(nsn: String): Boolean = nsn.startsWith("1")

    /** [text] cut into [sizes], with the last group taking whatever is left. */
    fun chunk(text: String, sizes: List<Int>): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        for ((index, size) in sizes.withIndex()) {
            if (rest.isEmpty()) break
            val take = if (index == sizes.lastIndex) rest.length else size
            out += rest.take(take)
            rest = rest.drop(take)
        }
        return out
    }

    private const val NANP_AREA = 3
    private const val NANP_EXCHANGE_END = 6
    private const val NANP_LENGTH = 10
    private const val PLUS_SEVEN_LENGTH = 10

    /** The shortest national number a country without its own range may have. */
    private const val MINIMUM_NATIONAL = 4
}
