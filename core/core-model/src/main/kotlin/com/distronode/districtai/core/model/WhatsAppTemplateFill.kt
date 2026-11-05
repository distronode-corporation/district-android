package com.distronode.districtai.core.model

/**
 * A WhatsApp template body with its numbered placeholders filled: an exact port of the website's
 * `lib/whatsapp/template-fill.ts`, which the send route also uses for the text it stores.
 *
 * Each `{{n}}` (spaces allowed inside the braces) becomes `values[n - 1]`, trimmed, when that is not
 * empty; an unfilled one, `{{0}}`, or an index past [values] stays as written, so the preview shows
 * what is still missing. A named placeholder (`{{first_name}}`) is not a match and is left alone.
 *
 * ⛔ JAVASCRIPT'S WHITESPACE, NOT JAVA'S. The web matches `\s` and calls `String.prototype.trim`,
 * both of which include NBSP, U+FEFF, U+2028/2029, U+3000 and the U+2000 block; Kotlin's `trim()`
 * and a Java regex `\s` do not. A port on Java's rules would preview `{{ 1 }}` with an NBSP as
 * unfilled while the server stores it filled. [JS_WHITESPACE] is that set, and the scan below is
 * hand-written so no regex flavour can disagree with it. Digits are ASCII only, as JS `\d` is.
 *
 * ⚠️ A NUMBER TOO LARGE FOR AN `Int` KEEPS ITS PLACEHOLDER, as JS indexing past the array does;
 * it must never wrap round into a real index.
 */
fun fillWhatsAppTemplate(body: String, values: List<String>): String {
    val out = StringBuilder(body.length)
    var i = 0
    while (i < body.length) {
        val end = placeholderEnd(body, i)
        if (end < 0) {
            out.append(body[i])
            i += 1
            continue
        }
        val placeholder = body.substring(i, end)
        val digits = placeholder.filter { it in '0'..'9' }
        val value = digits.toIntOrNull()?.let { values.getOrNull(it - 1) }?.let(::jsTrim)
        out.append(if (value.isNullOrEmpty()) placeholder else value)
        i = end
    }
    return out.toString()
}

/**
 * The templates the composer may offer for [senderId]: the web's `sendableWhatsAppTemplates`.
 *
 * Approved, tied to no sender or to this one, with an id, a body that is not blank (by JS trim) and
 * numbered variables (`variableCount` an integer of zero or more; null means named ones).
 */
fun sendableWhatsAppTemplates(templates: List<WhatsAppTemplate>, senderId: String): List<WhatsAppTemplate> =
    templates.filter { template ->
        val count = template.variableCount
        template.status == TEMPLATE_APPROVED &&
            (template.senderId == null || template.senderId == senderId) &&
            template.id.isNotEmpty() &&
            jsTrim(template.body).isNotEmpty() &&
            count != null &&
            count >= 0
    }

/** `String.prototype.trim`: [JS_WHITESPACE] off both ends. */
fun jsTrim(value: String): String = value.trim { it in JS_WHITESPACE }

/** The only template status the send route takes. */
private const val TEMPLATE_APPROVED = "approved"

/**
 * JavaScript's `\s`: the WhiteSpace and LineTerminator code points of ECMA-262 (tab, LF, VT, FF, CR,
 * space, NBSP, U+1680, U+2000 to U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, U+FEFF).
 * ⚠️ Escapes, never the characters themselves, so no editor can turn one into another invisibly.
 */
private const val JS_WHITESPACE_CHARS: String =
    "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A" +
        "\u2028\u2029\u202F\u205F\u3000\uFEFF"

private val JS_WHITESPACE: Set<Char> = JS_WHITESPACE_CHARS.toSet()

/**
 * Where a `{{ digits }}` that starts at [start] ends (exclusive), or -1 when none starts there.
 *
 * ⚠️ The same answer as the regex `\{\{\s*(\d+)\s*\}\}` tried at [start]: whitespace and digits
 * are disjoint, so the greedy match never needs to back off and one forward pass decides it.
 */
private fun placeholderEnd(body: String, start: Int): Int {
    if (!body.startsWith("{{", start)) return -1
    var j = skipSpace(body, start + 2)
    val digitsStart = j
    while (j < body.length && body[j] in '0'..'9') j += 1
    if (j == digitsStart) return -1
    j = skipSpace(body, j)
    return if (body.startsWith("}}", j)) j + 2 else -1
}

private fun skipSpace(body: String, from: Int): Int {
    var j = from
    while (j < body.length && body[j] in JS_WHITESPACE) j += 1
    return j
}
