// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.hq.voice

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictRadioRow
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.HqVoiceSettings
import com.distronode.districtai.ui.FailureState
import com.distronode.districtai.ui.resolve

/** One choice: the service's wire value, and its label (a string resource, or the value as itself). */
data class HqVoiceOption(val value: String, @param:StringRes val label: Int? = null)

/** The choices, the service's values in its order (`lib/district/hq-voice-settings.ts`), as iOS lists them. */
object HqVoiceOptions {
    /** ⚠️ The voices' own names, the same in both languages. */
    val voices: List<HqVoiceOption> = listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede").map { HqVoiceOption(it) }

    val lengths: List<HqVoiceOption> = listOf(
        HqVoiceOption("brief", R.string.hq_voice_length_brief),
        HqVoiceOption("normal", R.string.hq_voice_length_normal),
        HqVoiceOption("detailed", R.string.hq_voice_length_detailed),
    )

    val paces: List<HqVoiceOption> = listOf(
        HqVoiceOption("slower", R.string.hq_voice_pace_slower),
        HqVoiceOption("normal", R.string.hq_voice_pace_normal),
        HqVoiceOption("faster", R.string.hq_voice_pace_faster),
    )

    val languages: List<HqVoiceOption> = listOf(
        HqVoiceOption("portal", R.string.hq_voice_language_portal),
        HqVoiceOption("en", R.string.hq_voice_language_en),
        HqVoiceOption("fr", R.string.hq_voice_language_fr),
    )

    val micModes: List<HqVoiceOption> = listOf(
        HqVoiceOption("open", R.string.hq_voice_mic_open),
        HqVoiceOption("push", R.string.hq_voice_mic_push),
    )

    fun of(setting: HqVoiceSetting): List<HqVoiceOption> = when (setting) {
        HqVoiceSetting.VOICE -> voices
        HqVoiceSetting.LENGTH -> lengths
        HqVoiceSetting.PACE -> paces
        HqVoiceSetting.LANGUAGE -> languages
        HqVoiceSetting.MIC_MODE -> micModes
    }

    /** The list, with a value this build does not know added as itself. */
    fun including(value: String, options: List<HqVoiceOption>): List<HqVoiceOption> =
        if (options.any { it.value == value }) options else options + HqVoiceOption(value)
}

/**
 * The member's HQ voice settings: the voice, the answer length, the pace, the language and how the
 * microphone behaves. Opened from the voice bar.
 */
@Composable
fun HqVoiceSettingsScreen(
    state: HqVoiceSettingsUiState,
    onEdit: (HqVoiceSetting, String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    DistrictScaffold(
        modifier = Modifier.testTag(HQ_VOICE_SETTINGS_ROOT_DESCRIPTION),
        topBar = { DistrictTopBar(title = stringResource(R.string.hq_voice_settings), onBack = onBack) },
    ) { inset ->
        val draft = state.draft
        val loadFailure = state.loadFailure
        when {
            draft != null -> Form(draft, state, onEdit, onSave, inset)
            loadFailure != null -> FailureState(
                failure = loadFailure,
                onRetry = onRetry,
                onSignIn = onSignIn,
                description = HQ_VOICE_SETTINGS_FAILURE_DESCRIPTION,
                modifier = inset,
            )
            else -> Box(modifier = inset.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = DistrictTheme.colors.district)
            }
        }
    }
}

@Composable
private fun Form(
    draft: HqVoiceSettings,
    state: HqVoiceSettingsUiState,
    onEdit: (HqVoiceSetting, String) -> Unit,
    onSave: () -> Unit,
    inset: Modifier,
) {
    ContentContainer(modifier = inset.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(DistrictTheme.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            val enabled = !state.isSaving
            HqVoiceSetting.entries.forEach { setting ->
                Group(setting, draft.valueOf(setting), enabled, onEdit)
            }
            DistrictButton(
                text = stringResource(R.string.hq_voice_settings_save),
                onClick = onSave,
                enabled = state.isDirty && !state.isSaving,
                modifier = Modifier.testTag(HQ_VOICE_SETTINGS_SAVE_DESCRIPTION),
            )
            if (state.isSaved) {
                Text(
                    text = stringResource(R.string.hq_voice_settings_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                    modifier = Modifier.testTag(HQ_VOICE_SETTINGS_SAVED_DESCRIPTION),
                )
            }
            state.saveFailure?.let { failure ->
                Text(
                    text = failure.message.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.destructive,
                    modifier = Modifier.testTag(HQ_VOICE_SETTINGS_SAVE_FAILURE_DESCRIPTION),
                )
            }
        }
    }
}

/** ⚠️ THE TITLE ABOVE ITS CHOICES, NOT BESIDE THEM: side by side, the French values wrapped (iOS). */
@Composable
private fun Group(
    setting: HqVoiceSetting,
    value: String,
    enabled: Boolean,
    onEdit: (HqVoiceSetting, String) -> Unit,
) {
    // ⚠️ ONE SELECTABLE GROUP PER SETTING, so TalkBack reads its choices as one exclusive set.
    DistrictCard(modifier = Modifier.selectableGroup().testTag(hqVoiceSettingTag(setting))) {
        Eyebrow(stringResource(setting.title()))
        HqVoiceOptions.including(value, HqVoiceOptions.of(setting)).forEach { option ->
            DistrictRadioRow(
                label = option.label?.let { stringResource(it) } ?: option.value,
                selected = option.value == value,
                onClick = { onEdit(setting, option.value) },
                enabled = enabled,
                modifier = Modifier.testTag(hqVoiceOptionTag(setting, option.value)),
            )
        }
    }
}

@StringRes
private fun HqVoiceSetting.title(): Int = when (this) {
    HqVoiceSetting.VOICE -> R.string.hq_voice_setting_voice
    HqVoiceSetting.LENGTH -> R.string.hq_voice_setting_length
    HqVoiceSetting.PACE -> R.string.hq_voice_setting_pace
    HqVoiceSetting.LANGUAGE -> R.string.hq_voice_setting_language
    HqVoiceSetting.MIC_MODE -> R.string.hq_voice_setting_mic
}

private fun HqVoiceSettings.valueOf(setting: HqVoiceSetting): String = when (setting) {
    HqVoiceSetting.VOICE -> voice
    HqVoiceSetting.LENGTH -> length
    HqVoiceSetting.PACE -> pace
    HqVoiceSetting.LANGUAGE -> language
    HqVoiceSetting.MIC_MODE -> micMode
}

const val HQ_VOICE_SETTINGS_ROOT_DESCRIPTION: String = "district-hq-voice-settings-root"
const val HQ_VOICE_SETTINGS_FAILURE_DESCRIPTION: String = "district-hq-voice-settings-failure"
const val HQ_VOICE_SETTINGS_SAVE_DESCRIPTION: String = "district-hq-voice-settings-save"
const val HQ_VOICE_SETTINGS_SAVED_DESCRIPTION: String = "district-hq-voice-settings-saved"
const val HQ_VOICE_SETTINGS_SAVE_FAILURE_DESCRIPTION: String = "district-hq-voice-settings-save-failure"

fun hqVoiceSettingTag(setting: HqVoiceSetting): String = "district-hq-voice-setting-${setting.name.lowercase()}"

fun hqVoiceOptionTag(setting: HqVoiceSetting, value: String): String =
    "district-hq-voice-setting-${setting.name.lowercase()}-$value"
