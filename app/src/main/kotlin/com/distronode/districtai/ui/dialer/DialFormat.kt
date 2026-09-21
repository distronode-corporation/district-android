package com.distronode.districtai.ui.dialer

/**
 * Make a number being typed readable, for DISPLAY ONLY.
 *
 * ⛔ THE RESULT NEVER TRAVELS AND MUST NEVER BE FED BACK INTO THE ENTRY. The server runs its own
 * `normalizePhoneNumber` and then checks DNC, resolves the Contact and dials against THAT one form,
 * so a client that rewrote the operator's text would be shipping a second canonicaliser whose
 * disagreements are invisible until a call reaches a number the compliance check never saw. This
 * function only groups characters that are already there — it adds spaces and removes nothing —
 * which is why it cannot change what is dialled even if it is wrong.
 *
 * ⚠️ IT IS ALSO NOT A REWRITE OF THE FIELD. Formatting text as someone types moves their cursor and
 * eats their edits; the rooms lobby makes the same call about a room name, showing the normalised
 * form BESIDE the field rather than in it.
 *
 * ⚠️ GROUPING IS DELIBERATELY NAIVE AND ANCHORED ON THE **LAST TEN DIGITS**, which is a NANP
 * assumption stated rather than hidden. This product's market is Canada and the United States, so
 * a subscriber number is ten digits and anything before it is a country code — "+14165550100"
 * reads as "+1 416 555 0100" the way an operator expects, while a left-to-right chunker would
 * render the same number "+141 655 50100" and look broken. Elsewhere it degrades gracefully
 * (a UK number becomes "+44 207 946 0958", which is not how the UK groups but is still readable),
 * and it is DISPLAY ONLY, so being wrong about a country's convention costs legibility and nothing
 * else. A real libphonenumber-style formatter would be a large dependency and a second opinion
 * about numbers, which is precisely what this file exists to avoid.
 *
 * ⚠️ A number with no digits at all comes back unchanged, because there is nothing to group and
 * blanking a field somebody is still typing into would be worse than leaving it alone.
 */
internal fun formatDialEntry(raw: String): String {
    val plus = if (raw.startsWith("+")) "+" else ""
    // ⚠️ Read the DIGITS rather than the raw string: the operator may have typed their own spaces
    // or brackets, and grouping around those would produce two competing layouts in one field.
    val digits = raw.filter { it.isDigit() }
    if (digits.isEmpty()) return raw
    if (digits.length < SUBSCRIBER) {
        // Still being typed, or a short number: plain groups of three, left to right. There is no
        // subscriber number to anchor on yet, and guessing one would make the grouping jump around
        // as each digit lands.
        return plus + digits.chunked(GROUP).joinToString(" ")
    }
    // ⚠️ `country` IS EMPTY FOR A BARE TEN-DIGIT NUMBER, which is the ordinary way a NANP number is
    // written down. It is filtered out rather than joined as an empty group, or the result would
    // start with a space.
    val country = digits.dropLast(SUBSCRIBER)
    val subscriber = digits.takeLast(SUBSCRIBER)
    val groups = listOf(
        country,
        subscriber.take(GROUP),
        subscriber.drop(GROUP).take(GROUP),
        subscriber.takeLast(SUBSCRIBER - GROUP * 2),
    )
    return plus + groups.filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * A call's elapsed time, as a person reads a call timer.
 *
 * ⛔ `mm:ss` UNTIL AN HOUR, THEN `h:mm:ss`, AND THE MINUTES ARE NOT TRUNCATED AT SIXTY. A formatter
 * that showed `65:03` past the hour would be readable but wrong-looking on exactly the calls that
 * cost the most, and one that showed `05:03` would be wrong. The platform permits calls up to an
 * hour and the token is minted for seventy minutes, so the third field is reachable rather than
 * theoretical.
 *
 * ⚠️ IT IS THIS APP'S OWN MEASUREMENT AND NOT THE BILLED DURATION. The `Call` row's duration is
 * written by the carrier's webhooks against the carrier's own answer time; this counts from the
 * moment the SIP participant appeared in the room, which is as close as this client can observe.
 * The two will differ by a fraction of a second and the screen must not present this as an invoice.
 *
 * ⚠️ A NEGATIVE INPUT IS CLAMPED TO ZERO rather than rendered as `-1:-1`. Nothing should produce
 * one, which is exactly why it is not worth a crash.
 */
internal fun formatCallDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / SECONDS_PER_HOUR
    val minutes = (safe % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = safe % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "$hours:${minutes.pad()}:${seconds.pad()}"
    } else {
        "${minutes.pad()}:${seconds.pad()}"
    }
}

/** ⚠️ `padStart`, not `String.format`: the latter is locale-sensitive and a timer is not. */
private fun Int.pad(): String = toString().padStart(2, '0')

/** ⚠️ Named because detekt counts a bare 3 in an expression as a magic number. */
private const val GROUP = 3

/**
 * The length of a NANP subscriber number, area code included.
 *
 * ⚠️ AN ASSUMPTION ABOUT THIS PRODUCT'S MARKET, WRITTEN DOWN RATHER THAN INFERRED. It decides only
 * where spaces go; see [formatDialEntry] for why being wrong about it elsewhere is a legibility
 * cost and never a dialling one.
 */
private const val SUBSCRIBER = 10
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
