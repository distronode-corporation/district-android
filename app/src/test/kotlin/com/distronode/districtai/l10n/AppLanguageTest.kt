package com.distronode.districtai.l10n

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.resolve
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The language switch, wire labels and server error sentences.
 *
 * ⚠️ THE FRENCH BRANCHES ARE DRIVEN WITH AN EXPLICIT LANGUAGE. These tests run on an English (en-US)
 * device, so French resolution is asserted on what it CHOOSES: which catalog entry, read here in its
 * English, and never the server's literal. Which language a French device resolves to is
 * ResolvedLanguageTest's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AppLanguageTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Each (expected, block) pair of one test, composed in ONE setContent (one per test), then compared. */
    private fun assertRendered(vararg cases: Pair<String, @Composable () -> String>) {
        val actual = arrayOfNulls<String>(cases.size)
        composeRule.setContent { cases.forEachIndexed { i, (_, block) -> actual[i] = block() } }
        composeRule.waitForIdle()
        cases.forEachIndexed { i, (expected, _) -> assertEquals("case $i", expected, actual[i]) }
    }

    @Test
    fun `an English device reads English`() {
        assertRendered("EN" to { appLanguage().name })
        assertEquals(AppLanguage.FR, languageOf("fr"))
        assertEquals(AppLanguage.EN, languageOf("en"))
        assertEquals(AppLanguage.EN, languageOf(""))
    }

    @Test
    fun `a known wire value reads as the portal labels it, and an unknown one passes through`() {
        assertRendered(
            "Completed" to { wireLabel(WireCategory.CALL_STATUS, "completed") },
            "queued" to { wireLabel(WireCategory.CALL_STATUS, "queued") },
            "CA" to { wireLabel(WireCategory.REGION, "ca") },
            "MARS" to { wireLabel(WireCategory.REGION, "mars") },
            "Total Dials" to { wireLabel(WireCategory.FUNNEL_STAGE, "Total Dials") },
        )
    }

    @Test
    fun `a server placeholder reads as its label, in English the words the app always showed, and real text as sent`() {
        assertRendered(
            str(R.string.wire_placeholder_unknown_caller) to { serverText("Unknown Caller") },
            "Unknown Caller" to { serverText("Unknown Caller") },
            "Voice session completed." to { serverText("Voice session completed.") },
            "Test call" to { serverText("Test call") },
            "Manual edit update." to { serverText("Manual edit update.") },
            "Ada Lovelace" to { serverText("Ada Lovelace") },
            "" to { serverText("") },
        )
        // ⛔ What French reads is chosen by the label, so each stand-in has one and real text has none.
        assertEquals(R.string.wire_placeholder_unknown_caller, placeholderLabel("Unknown Caller"))
        assertEquals(R.string.wire_placeholder_customer, placeholderLabel("Customer"))
        assertEquals(R.string.wire_placeholder_no_summary_available, placeholderLabel("No summary available."))
        assertEquals(R.string.wire_placeholder_ai_call_completed, placeholderLabel("AI call completed."))
        assertEquals(R.string.wire_placeholder_unknown, placeholderLabel("Unknown"))
        assertEquals(R.string.wire_placeholder_you, placeholderLabel("You"))
        assertEquals(R.string.wire_placeholder_distronode_support, placeholderLabel("Distronode Support"))
        assertEquals(null, placeholderLabel("Ada Lovelace"))
        assertEquals(null, placeholderLabel("unknown caller"))
    }

    @Test
    fun `in English the server's own sentence is shown, code or no code`() {
        assertRendered(
            "Contact already exists" to { serverSentence("Contact already exists", "forbidden") },
            "Contact already exists" to { serverSentence("Contact already exists", null) },
        )
    }

    @Test
    fun `in French a known code picks the catalog's sentence, never the server's English`() {
        val fr = AppLanguage.FR
        assertRendered(
            str(R.string.api_error_rate_limited) to { serverSentence("Slow down", "rate_limited", language = fr) },
            str(R.string.api_error_upper_internal_error) to {
                serverSentence("Boom", "INTERNAL_ERROR", language = fr)
            },
        )
    }

    @Test
    fun `a list-bearing code takes its items, or its list-free sentence when there are none`() {
        val fr = AppLanguage.FR
        assertRendered(
            str(R.string.api_error_invalid_params, "email, phone") to {
                serverSentence("Bad", "invalid_params", listOf("email", "phone"), language = fr)
            },
            str(R.string.api_error_invalid_params_without_list) to {
                serverSentence("Bad", "invalid_params", language = fr)
            },
        )
    }

    @Test
    fun `an unknown code or no code falls back to the screen's sentence, else the catalog's generic one`() {
        val fr = AppLanguage.FR
        val fallback = R.string.scheduling_enable_failed_fallback
        assertRendered(
            str(R.string.api_error_unknown) to { serverSentence("x", "not_a_code", language = fr) },
            str(R.string.api_error_unknown) to { serverSentence("x", null, language = fr) },
            str(fallback) to { serverSentence("x", null, fallback = fallback, language = fr) },
        )
    }

    @Test
    fun `a server UiText resolves through the server sentence and still exposes its literal`() {
        val text = UiText.Server("Contact already exists", code = "forbidden")
        assertRendered("Contact already exists" to { text.resolve() })
        assertEquals("Contact already exists", text.literalOrNull)
    }
}
