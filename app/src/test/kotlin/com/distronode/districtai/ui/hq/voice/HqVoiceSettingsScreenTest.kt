package com.distronode.districtai.ui.hq.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.model.HqVoiceSettings
import com.distronode.districtai.core.network.testing.FakeHqVoiceApi
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.ThemeFlip
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The voice settings screen, drawn in every state. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class HqVoiceSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── The screen ───────────────────────────────────────────────────────────────────────────────

    private val theme = ThemeFlip(composeRule)
    private val calls = mutableListOf<String>()

    private fun render(state: () -> HqVoiceSettingsUiState) {
        theme.setContent {
            HqVoiceSettingsScreen(
                state = state(),
                onEdit = { setting, value -> calls += "${setting.name}=$value" },
                onSave = { calls += "save" },
                onRetry = { calls += "retry" },
                onSignIn = { calls += "sign-in" },
                onBack = { calls += "back" },
            )
        }
    }

    @Test
    fun `the five settings are offered, the member's choice selected, a newer value kept as itself`() {
        val settings = HqVoiceSettings("Zephyr", "normal", "normal", "portal", "open")
        render { HqVoiceSettingsUiState(draft = settings, loaded = settings) }

        HqVoiceSetting.entries.forEach { composeRule.onNodeWithTag(hqVoiceSettingTag(it)).assertExists() }
        composeRule.onNodeWithTag(hqVoiceOptionTag(HqVoiceSetting.VOICE, "Zephyr")).assertIsSelected()
        composeRule.onNodeWithTag(hqVoiceOptionTag(HqVoiceSetting.LANGUAGE, "portal")).assertIsSelected()
        composeRule.onNodeWithTag(HQ_VOICE_SETTINGS_SAVE_DESCRIPTION).performScrollTo().assertIsNotEnabled()

        composeRule.onNodeWithTag(hqVoiceOptionTag(HqVoiceSetting.MIC_MODE, "push")).performScrollTo().performClick()
        composeRule.onNodeWithTag(hqVoiceOptionTag(HqVoiceSetting.VOICE, "Puck")).performScrollTo().performClick()
        theme.flip()

        assertEquals(listOf("MIC_MODE=push", "VOICE=Puck"), calls)
    }

    @Test
    fun `a dirty draft saves, a saved one says so, and a failed save shows its sentence`() {
        val loaded = FakeHqVoiceApi.SETTINGS
        var state by mutableStateOf(HqVoiceSettingsUiState(draft = loaded.copy(pace = "faster"), loaded = loaded))
        render { state }
        composeRule.onNodeWithTag(HQ_VOICE_SETTINGS_SAVE_DESCRIPTION).performScrollTo().assertIsEnabled().performClick()

        state = HqVoiceSettingsUiState(draft = loaded, loaded = loaded, isSaved = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_SETTINGS_SAVED_DESCRIPTION).performScrollTo().assertIsDisplayed()

        state = HqVoiceSettingsUiState(
            draft = loaded.copy(pace = "faster"),
            loaded = loaded,
            saveFailure = FailureText(UiText.Literal("Could not save.")),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_SETTINGS_SAVE_FAILURE_DESCRIPTION).performScrollTo().assertIsDisplayed()

        state = state.copy(isSaving = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_SETTINGS_SAVE_DESCRIPTION).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(
            hqVoiceOptionTag(HqVoiceSetting.PACE, "slower")
        ).performScrollTo().assertIsNotEnabled()

        assertEquals(listOf("save"), calls)
    }

    @Test
    fun `before the read the screen waits, and a failed read offers a retry`() {
        var state by mutableStateOf(HqVoiceSettingsUiState())
        render { state }
        composeRule.onNodeWithText(str(R.string.hq_voice_settings)).assertIsDisplayed()
        composeRule.onNodeWithTag(HQ_VOICE_SETTINGS_FAILURE_DESCRIPTION).assertDoesNotExist()

        state = HqVoiceSettingsUiState(loadFailure = FailureText(UiText.Literal("Offline.")))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HQ_VOICE_SETTINGS_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()

        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `the options are the service's values in its order`() {
        assertEquals(listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede"), HqVoiceOptions.voices.map { it.value })
        assertEquals(listOf("brief", "normal", "detailed"), HqVoiceOptions.lengths.map { it.value })
        assertEquals(listOf("slower", "normal", "faster"), HqVoiceOptions.paces.map { it.value })
        assertEquals(listOf("portal", "en", "fr"), HqVoiceOptions.languages.map { it.value })
        assertEquals(listOf("open", "push"), HqVoiceOptions.micModes.map { it.value })
        assertEquals(HqVoiceOptions.micModes, HqVoiceOptions.including("open", HqVoiceOptions.micModes))
        assertEquals(HqVoiceOption("walkie"), HqVoiceOptions.including("walkie", HqVoiceOptions.micModes).last())
    }
}
