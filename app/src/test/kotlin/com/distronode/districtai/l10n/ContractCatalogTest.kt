package com.distronode.districtai.l10n

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The generated error-code and wire-label tables against `contracts/i18n`, through the real resources.
 *
 * ⚠️ scripts/l10n-contracts.py --check (CI) proves the generated FILES are current; this proves what
 * they produce at run time: every code the server can send resolves, and its English resource is the
 * contract's sentence, character for character.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ContractCatalogTest {

    private fun contract(name: String): JsonObject {
        val dir = System.getProperty("district.contracts.dir")
        assertTrue("district.contracts.dir is not set", !dir.isNullOrBlank())
        return Json.parseToJsonElement(File(dir, "i18n/$name").readText()).jsonObject
    }

    @Test
    fun `every error code resolves to the contract's English sentence`() {
        val root = contract("api-errors.json")
        val placeholder = root.getValue("itemsPlaceholder").jsonPrimitive.content
        val codes = root.getValue("codes").jsonObject
        assertEquals(codes.keys, API_ERROR_SENTENCES.keys)
        for ((code, entry) in codes) {
            val en = entry.jsonObject.getValue("en").jsonPrimitive.content
            val id = API_ERROR_SENTENCES.getValue(code)
            if (placeholder in en) {
                assertEquals(code, en.replace(placeholder, "a, b"), str(id, "a, b"))
                val listFree = entry.jsonObject.getValue("enWithoutList").jsonPrimitive.content
                assertEquals(code, listFree, str(API_ERROR_SENTENCES_WITHOUT_LIST.getValue(code)))
            } else {
                assertEquals(code, en, str(id))
            }
        }
        assertEquals(root.getValue("listSeparator").jsonPrimitive.content, API_ERROR_LIST_SEPARATOR)
        val unknown = codes.getValue("unknown").jsonObject.getValue("en").jsonPrimitive.content
        assertEquals(unknown, str(API_ERROR_FALLBACK))
    }

    @Test
    fun `every vocabulary value of a displayed category has a label`() {
        val categories = contract("wire-vocabulary.json").getValue("categories").jsonObject
        val byName = mapOf(
            "callStatus" to WireCategory.CALL_STATUS,
            "sentiment" to WireCategory.SENTIMENT,
            "disposition" to WireCategory.DISPOSITION,
            "transferStatus" to WireCategory.TRANSFER_STATUS,
            "transferReason" to WireCategory.TRANSFER_REASON,
            "funnelStage" to WireCategory.FUNNEL_STAGE,
            "analyticsSentiment" to WireCategory.ANALYTICS_SENTIMENT,
            "region" to WireCategory.REGION,
        )
        assertEquals(WireCategory.entries.toSet(), byName.values.toSet())
        for ((name, category) in byName) {
            val values = categories.getValue(name).jsonObject.getValue("values").jsonArray
                .map { it.jsonObject.getValue("value").jsonPrimitive.content }
            assertEquals(name, values.toSet(), category.labels.keys)
        }
    }

    @Test
    fun `the placeholder tables are the vocabulary's placeholders and their earlier spellings`() {
        val placeholders = contract("wire-vocabulary.json").getValue("placeholders").jsonArray.map { it.jsonObject }
        assertEquals(placeholders.map { it.getValue("value").jsonPrimitive.content }.toSet(), WIRE_PLACEHOLDERS)
        val aliases = placeholders.filter { "aliases" in it }.associate { placeholder ->
            placeholder.getValue("value").jsonPrimitive.content to
                placeholder.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }
        }
        assertTrue(aliases.isNotEmpty())
        assertEquals(aliases, WIRE_PLACEHOLDER_ALIASES)
    }
}
