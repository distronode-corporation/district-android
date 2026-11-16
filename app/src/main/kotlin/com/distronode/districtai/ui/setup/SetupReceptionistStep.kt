package com.distronode.districtai.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.ui.resolve
import com.distronode.districtai.ui.settings.workspace.ConfigLoadFailure
import com.distronode.districtai.ui.settings.workspace.ConfigSkeleton

/** Step 3, "Your receptionist". See [SetupReceptionistModel]. */
@Composable
internal fun SetupReceptionistStep(model: SetupReceptionistModel) {
    when (val load = model.load) {
        SetupStepLoad.Loading -> ConfigSkeleton()
        is SetupStepLoad.Failed -> ConfigLoadFailure(failure = load.failure, onRetry = @DontMemoize { model.load() })
        SetupStepLoad.Ready -> ReceptionistForm(model)
    }
}

@Composable
private fun ReceptionistForm(model: SetupReceptionistModel) {
    SetupCard {
        SetupField(
            label = stringResource(R.string.setup_receptionist_name_label),
            value = model.name,
            onValueChange = @DontMemoize { model.editName(it) },
            tag = ReceptionistTags.NAME,
            error = stringResource(
                R.string.setup_receptionist_name_required
            ).takeIf { model.showErrors && model.nameMissing },
            enabled = !model.isSaving,
        )
        SetupField(
            label = stringResource(R.string.setup_receptionist_greeting_label),
            value = model.greeting,
            onValueChange = @DontMemoize { model.editGreeting(it) },
            tag = ReceptionistTags.GREETING,
            hint = stringResource(R.string.setup_receptionist_greeting_hint),
            error = stringResource(R.string.setup_receptionist_greeting_required)
                .takeIf { model.showErrors && model.greetingMissing },
            multiline = true,
            enabled = !model.isSaving,
        )
        if (model.voiceGroups.isEmpty()) {
            SetupNote(
                stringResource(R.string.setup_receptionist_voice_elsewhere),
                modifier = Modifier.testTag(ReceptionistTags.VOICE)
            )
        } else {
            SetupPicker(
                label = stringResource(R.string.setup_receptionist_voice_label),
                placeholder = stringResource(R.string.setup_receptionist_voice_label),
                selected = model.voice.orEmpty(),
                options = model.voiceGroups.flatMap { it.options }.map { SetupOption(it.value, it.label) },
                tag = ReceptionistTags.VOICE,
                onSelect = @DontMemoize { model.voice = it },
                enabled = !model.isSaving,
            )
        }
        if (model.disclosureIsMandatory) {
            SetupNote(
                text = stringResource(R.string.setup_receptionist_disclosure_always) + " " +
                    stringResource(R.string.setup_receptionist_disclosure_required),
                modifier = Modifier.testTag(ReceptionistTags.DISCLOSURE),
            )
        }
        model.saveError?.let {
            SetupErrorLine(
                it.message.resolve(),
                modifier = Modifier.testTag(ReceptionistTags.SAVE_ERROR)
            )
        }
        SetupPrimaryButton(
            title = stringResource(R.string.setup_save_and_continue),
            busy = model.isSaving,
            tag = ReceptionistTags.SAVE,
            onClick = @DontMemoize { model.save() },
        )
    }
}

internal object ReceptionistTags {
    const val NAME = "district-setup-receptionist-name"
    const val GREETING = "district-setup-receptionist-greeting"
    const val VOICE = "district-setup-receptionist-voice"
    const val DISCLOSURE = "district-setup-receptionist-disclosure"
    const val SAVE_ERROR = "district-setup-receptionist-save-error"
    const val SAVE = "district-setup-receptionist-save"
}
