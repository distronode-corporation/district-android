package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fillWhatsAppTemplate] against the semantics of the website's `template-fill.ts`, case by case.
 *
 * ⛔ THE EXPECTED VALUES ARE WHAT NODE ANSWERS, not what Kotlin's own `trim()` or `\s` would. Each
 * whitespace case below is one where the two disagree, which is the bug this port must not have.
 * Characters are built from code points so no editor can swap one for another.
 */
class WhatsAppTemplateFillTest {

    private fun ch(code: Int): String = code.toChar().toString()

    private val nbsp = ch(0xA0)
    private val bom = ch(0xFEFF)
    private val ideographic = ch(0x3000)
    private val lineSeparator = ch(0x2028)
    private val enQuad = ch(0x2000)
    private val hairSpace = ch(0x200A)

    @Test
    fun `numbered placeholders take their values in order`() {
        assertEquals(
            "Hi Ada, this is a reminder of your appointment on Thursday.",
            fillWhatsAppTemplate(
                "Hi {{1}}, this is a reminder of your appointment on {{2}}.",
                listOf("Ada", "Thursday"),
            ),
        )
    }

    @Test
    fun `spaces inside the braces still match, and the whole placeholder is replaced`() {
        assertEquals("Hi Ada!", fillWhatsAppTemplate("Hi {{ 1 }}!", listOf("Ada")))
        assertEquals("Hi Ada!", fillWhatsAppTemplate("Hi {{\t1\n}}!", listOf("Ada")))
    }

    @Test
    fun `JavaScript whitespace inside the braces matches where Java's would not`() {
        listOf(nbsp, bom, ideographic, lineSeparator, enQuad, hairSpace).forEach { space ->
            assertEquals("Hi Ada", fillWhatsAppTemplate("Hi {{${space}1$space}}", listOf("Ada")))
        }
    }

    @Test
    fun `a value is trimmed by JavaScript's rules, and a value of only whitespace keeps the placeholder`() {
        assertEquals("Hi Ada", fillWhatsAppTemplate("Hi {{1}}", listOf("$nbsp$bom Ada$ideographic")))
        assertEquals("Hi {{1}}", fillWhatsAppTemplate("Hi {{1}}", listOf("$nbsp $bom")))
        assertEquals("Hi {{ 1 }}", fillWhatsAppTemplate("Hi {{ 1 }}", listOf("")))
    }

    @Test
    fun `NEL is not JavaScript whitespace, so it survives the trim`() {
        val nel = ch(0x85)
        assertEquals("Hi ${nel}Ada", fillWhatsAppTemplate("Hi {{1}}", listOf("${nel}Ada")))
    }

    @Test
    fun `zero, a missing value and a huge index keep the placeholder`() {
        assertEquals("{{0}} {{3}}", fillWhatsAppTemplate("{{0}} {{3}}", listOf("a", "b")))
        // ⛔ Past Int: must not wrap round into a real index.
        assertEquals("{{4294967297}}", fillWhatsAppTemplate("{{4294967297}}", listOf("a", "b")))
        assertEquals("{{99999999999999999999}}", fillWhatsAppTemplate("{{99999999999999999999}}", listOf("a")))
    }

    @Test
    fun `leading zeros name the same variable, as Number does`() {
        assertEquals("a b", fillWhatsAppTemplate("{{01}} {{0002}}", listOf("a", "b")))
    }

    @Test
    fun `named and malformed placeholders are left alone`() {
        val body = "Bonjour {{first_name}}, {{}} {{ }} {1} {{1} {{1a}} {{-1}} {{ 1 2 }}"
        assertEquals(body, fillWhatsAppTemplate(body, listOf("x")))
    }

    @Test
    fun `a placeholder cut off at the end of the body is text`() {
        listOf("Hi {{12", "Hi {{ 1 ", "Hi {{1}").forEach { assertEquals(it, fillWhatsAppTemplate(it, listOf("x"))) }
    }

    @Test
    fun `digits are ASCII only`() {
        val arabicOne = ch(0x0661)
        assertEquals("{{$arabicOne}}", fillWhatsAppTemplate("{{$arabicOne}}", listOf("x")))
    }

    @Test
    fun `an extra brace is text, and the match starts where JavaScript's would`() {
        assertEquals("{x}", fillWhatsAppTemplate("{{{1}}}", listOf("x")))
        assertEquals("{{x", fillWhatsAppTemplate("{{{{1}}", listOf("x")))
    }

    @Test
    fun `non-ASCII text and values pass through untouched`() {
        assertEquals(
            "Bonjour Zoé, à jeudi 😀",
            fillWhatsAppTemplate("Bonjour {{1}}, à {{2}} 😀", listOf("Zoé", "jeudi")),
        )
    }

    @Test
    fun `a body with no placeholder is returned as it is`() {
        assertEquals("", fillWhatsAppTemplate("", emptyList()))
        assertEquals("Hello from Acme", fillWhatsAppTemplate("Hello from Acme", emptyList()))
    }

    @Test
    fun `jsTrim removes JavaScript whitespace from both ends only`() {
        assertEquals("a${nbsp}b", jsTrim("$bom${nbsp}a${nbsp}b$lineSeparator\t"))
    }

    @Test
    fun `only approved, numbered templates of no sender or this one are sendable`() {
        val good = WhatsAppTemplate(id = "t1", status = "approved", body = "Hi {{1}}", variableCount = 1)
        val templates = listOf(
            good,
            good.copy(id = "mine", senderId = "s1"),
            good.copy(id = "other", senderId = "s2"),
            good.copy(id = "pending", status = "pending"),
            good.copy(id = "named", variableCount = null),
            good.copy(id = "negative", variableCount = -1),
            good.copy(id = "blank", body = "$nbsp \n"),
            good.copy(id = ""),
            good.copy(id = "none", body = "Hello", variableCount = 0),
        )

        assertEquals(listOf("t1", "mine", "none"), sendableWhatsAppTemplates(templates, "s1").map { it.id })
    }
}
