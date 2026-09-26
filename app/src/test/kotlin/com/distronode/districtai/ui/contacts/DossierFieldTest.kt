package com.distronode.districtai.ui.contacts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dossier walk, tested without a screen.
 *
 * ⛔ THIS IS THE ONE PLACE IN THE APP RENDERING DATA THAT NO GATE VALIDATES. The contract fixtures
 * pin the CONTAINER — `intelligence` is a JSON object — and say nothing about what is inside it,
 * because the contents come from a model whose prompt changes. So the only guarantee available is
 * that every shape degrades to something readable rather than to an exception, and that guarantee
 * is checkable here, one shape at a time, without composing anything.
 */
class DossierFieldTest {

    private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        JsonObject(pairs.toMap())

    @Test
    fun `a null blob produces no rows at all`() {
        assertEquals(emptyList<DossierField>(), dossierFields(null))
    }

    @Test
    fun `a string value keeps its content, not its JSON quotes`() {
        // ⛔ `JsonPrimitive.toString()` RE-SERIALISES A STRING WITH ITS QUOTES, so the naive
        // implementation renders every dossier value wrapped in `"`. Numbers and booleans are
        // unaffected, which is precisely what makes the bug survive a spot check.
        val fields = dossierFields(obj("summary" to JsonPrimitive("Wants Thursday.")))

        assertEquals(1, fields.size)
        assertEquals("Wants Thursday.", fields.single().value)
    }

    @Test
    fun `camelCase and snake_case keys both become readable labels`() {
        val fields = dossierFields(
            obj(
                "executiveSummary" to JsonPrimitive("x"),
                "buying_signals" to JsonPrimitive("y"),
            ),
        )

        assertEquals(listOf("Executive summary", "Buying signals"), fields.map { it.label })
    }

    @Test
    fun `numbers and booleans survive as text`() {
        val fields = dossierFields(
            obj("score" to JsonPrimitive(87), "qualified" to JsonPrimitive(true)),
        )

        assertEquals(listOf("87", "true"), fields.map { it.value })
    }

    @Test
    fun `an array of strings becomes one joined row`() {
        val fields = dossierFields(
            obj(
                "topics" to JsonArray(
                    listOf(JsonPrimitive("booking"), JsonPrimitive("pricing")),
                ),
            ),
        )

        assertEquals("booking · pricing", fields.single().value)
        assertEquals("a list of strings is prose, not a shape", false, fields.single().raw)
    }

    @Test
    fun `a nested object becomes a heading with children, one level deep`() {
        val fields = dossierFields(
            obj(
                "firmographics" to obj(
                    "employees" to JsonPrimitive("50-100"),
                    "hq" to JsonPrimitive("Toronto"),
                ),
            ),
        )

        val field = fields.single()
        assertEquals("Firmographics", field.label)
        assertEquals("a heading carries no value of its own", null, field.value)
        assertEquals(listOf("Employees", "Hq"), field.children.map { it.label })
        assertEquals(listOf("50-100", "Toronto"), field.children.map { it.value })
    }

    @Test
    fun `a grandchild object degrades to raw JSON rather than a third card level`() {
        // ⛔ THE DEPTH CAP. Recursing without bound over a model-authored object is unbounded UI:
        // an accordion of nested cards nobody can read and no test can pin. Depth two is where
        // the nesting stops being informative, and the raw fallback keeps the data VISIBLE
        // without pretending to structure it.
        val fields = dossierFields(
            obj("outer" to obj("inner" to obj("deep" to JsonPrimitive("value")))),
        )

        val child = fields.single().children.single()
        assertEquals("Inner", child.label)
        assertTrue("must be flagged as an unrecognised shape", child.raw)
        assertTrue("the data must still be visible", child.value!!.contains("deep"))
    }

    @Test
    fun `an array of objects degrades to raw JSON instead of rendering noise`() {
        val fields = dossierFields(
            obj(
                "competitors" to JsonArray(listOf(obj("name" to JsonPrimitive("Acme")))),
            ),
        )

        val field = fields.single()
        assertTrue(field.raw)
        assertTrue(field.value!!.contains("Acme"))
    }

    @Test
    fun `nulls and empties are dropped rather than rendered as blank rows`() {
        // ⚠️ A row reading "Objections: —" carries no information and costs a line of screen. An
        // absent key and an empty array are both "the model found nothing here".
        val fields = dossierFields(
            obj(
                "objections" to JsonArray(emptyList()),
                "notes" to JsonNull,
                "blank" to JsonPrimitive("   "),
                "empty" to obj(),
                "kept" to JsonPrimitive("real"),
            ),
        )

        assertEquals(listOf("Kept"), fields.map { it.label })
    }

    @Test
    fun `an object whose every child is empty is dropped whole, not left as a bare heading`() {
        val fields = dossierFields(obj("firmographics" to obj("hq" to JsonNull)))

        assertEquals(emptyList<DossierField>(), fields)
    }

    @Test
    fun `key order follows the object, because nothing here knows which keys matter`() {
        val fields = dossierFields(
            obj(
                "zeta" to JsonPrimitive("1"),
                "alpha" to JsonPrimitive("2"),
            ),
        )

        assertEquals(listOf("Zeta", "Alpha"), fields.map { it.label })
    }

    @Test
    fun `a list whose every entry is blank is dropped rather than rendered as an empty row`() {
        val fields = dossierFields(obj("objections" to JsonArray(listOf(JsonPrimitive(""), JsonPrimitive("  ")))))

        assertEquals(emptyList<DossierField>(), fields)
    }

    @Test
    fun `under a heading, blanks and empty containers are dropped while a list is joined`() {
        val fields = dossierFields(
            obj(
                "firmographics" to obj(
                    "hq" to JsonPrimitive("  "),
                    "offices" to JsonArray(listOf(JsonPrimitive("Toronto"), JsonPrimitive("Ottawa"))),
                    "tags" to JsonArray(emptyList()),
                    "parent" to obj(),
                    "size" to JsonPrimitive("50-100"),
                ),
            ),
        )

        val children = fields.single().children
        assertEquals(listOf("Offices", "Size"), children.map { it.label })
        assertEquals("Toronto · Ottawa", children.first().value)
    }

    @Test
    fun `a key made only of separators keeps its own spelling rather than becoming blank`() {
        val fields = dossierFields(obj("__" to JsonPrimitive("x")))

        assertEquals(listOf("__"), fields.map { it.label })
    }
}
