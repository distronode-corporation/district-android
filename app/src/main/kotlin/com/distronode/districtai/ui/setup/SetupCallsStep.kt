package com.distronode.districtai.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.ui.resolve
import com.distronode.districtai.ui.settings.workspace.ConfigLoadFailure
import com.distronode.districtai.ui.settings.workspace.ConfigSkeleton

/** Step 5, "Calls, people and bookings". See [SetupCallsModel]. */
@Composable
internal fun SetupCallsStep(model: SetupCallsModel, actions: SetupActions) {
    when (val load = model.load) {
        SetupStepLoad.Loading -> ConfigSkeleton()
        is SetupStepLoad.Failed -> ConfigLoadFailure(failure = load.failure, onRetry = @DontMemoize { model.load() })
        SetupStepLoad.Ready -> {
            CallsAnswering(model)
            SetupCard(heading = stringResource(R.string.setup_calls_transfer_header)) {
                val number = model.transferNumber
                SetupNote(
                    text = if (number != null) {
                        stringResource(R.string.setup_calls_transfer_current, number)
                    } else {
                        stringResource(R.string.setup_calls_transfer_none)
                    },
                    modifier = Modifier.testTag(CallsTags.TRANSFER),
                )
                SetupNote(stringResource(R.string.setup_calls_transfer_where), small = true)
                SetupHandOffButton(
                    stringResource(R.string.setup_open_skills),
                    SetupHandOff.SKILLS,
                    actions,
                    CallsTags.OPEN_SKILLS
                )
            }
            SetupCard(heading = stringResource(R.string.setup_calls_bookings_header)) {
                val calendar = model.calendarName
                SetupNote(
                    text = if (calendar != null) {
                        stringResource(R.string.setup_calls_bookings_connected, calendar)
                    } else {
                        stringResource(R.string.setup_calls_bookings_none)
                    },
                    modifier = Modifier.testTag(CallsTags.BOOKINGS),
                )
                SetupNote(stringResource(R.string.setup_calls_bookings_where), small = true)
                SetupHandOffButton(
                    stringResource(R.string.setup_open_integrations),
                    SetupHandOff.INTEGRATIONS,
                    actions,
                    CallsTags.OPEN_INTEGRATIONS,
                )
            }
            if (model.profilesOffered) CallsProfiles(model)
            model.error?.let { SetupErrorLine(it.message.resolve(), modifier = Modifier.testTag(CallsTags.ERROR)) }
            SetupPrimaryButton(
                title = stringResource(R.string.setup_continue),
                busy = model.isContinuing,
                tag = CallsTags.CONTINUE,
                onClick = @DontMemoize { model.saveAndContinue() },
            )
        }
    }
}

@Composable
private fun CallsAnswering(model: SetupCallsModel) {
    SetupCard(heading = stringResource(R.string.setup_calls_answer_header)) {
        SetupNote(stringResource(R.string.setup_calls_answer_description), small = true)
        model.modes.forEach { mode ->
            SetupChoiceRow(
                title = stringResource(modeTitle(mode)),
                detail = stringResource(modeDetail(mode)),
                selected = model.mode == mode,
                enabled = !model.isSavingAnswering,
                tag = SetupTags.choice("mode", mode),
                onClick = @DontMemoize { model.selectMode(mode) },
            )
        }
        if (model.mode == CallHandling.APP_FIRST) {
            SetupNote(stringResource(R.string.setup_calls_app_needed), small = true)
            RingStepper(model)
        }
        SetupNote(stringResource(R.string.setup_calls_keeping_line_note), small = true)
        if (model.answeringSaved && !model.answeringChanged) {
            SetupNote(stringResource(R.string.setup_saved), modifier = Modifier.testTag(CallsTags.SAVED))
        }
        DistrictButton(
            text = stringResource(
                if (model.isSavingAnswering) R.string.setup_saving else R.string.setup_calls_save_answering
            ),
            onClick = @DontMemoize { model.saveAnswering() },
            variant = ButtonVariant.Secondary,
            enabled = model.answeringChanged && !model.isSavingAnswering,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CallsTags.SAVE_ANSWERING),
        )
    }
}

/** How long the app rings, a second at a time between the route's bounds. */
@Composable
private fun RingStepper(model: SetupCallsModel) {
    val enabled = !model.isSavingAnswering
    // ⚠️ THE SIGNS ARE WHAT IS DRAWN; TalkBack reads the words.
    val fewer = stringResource(R.string.setup_calls_ring_fewer)
    val more = stringResource(R.string.setup_calls_ring_more)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Text(
            text = stringResource(
                R.string.setup_calls_ring_value,
                stringResource(R.string.setup_calls_ring_label),
                stringResource(R.string.setup_calls_ring_seconds, model.ringSeconds),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier
                .weight(1f)
                .testTag(CallsTags.RING),
        )
        DistrictButton(
            text = "−",
            onClick = @DontMemoize { model.selectRing(model.ringSeconds - 1) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Sm,
            enabled = enabled && model.ringSeconds > CallHandling.MINIMUM_RING_SECONDS,
            modifier = Modifier
                .semantics { contentDescription = fewer }
                .testTag(CallsTags.RING_DOWN),
        )
        DistrictButton(
            text = "+",
            onClick = @DontMemoize { model.selectRing(model.ringSeconds + 1) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Sm,
            enabled = enabled && model.ringSeconds < CallHandling.MAXIMUM_RING_SECONDS,
            modifier = Modifier
                .semantics { contentDescription = more }
                .testTag(CallsTags.RING_UP),
        )
    }
}

@Composable
private fun CallsProfiles(model: SetupCallsModel) {
    SetupCard(heading = stringResource(R.string.setup_calls_profiles_header)) {
        SetupSwitchRow(
            label = stringResource(R.string.setup_calls_profiles_toggle),
            checked = model.profilesOn,
            tag = CallsTags.PROFILES,
            onChange = @DontMemoize { model.setProfiles(it) },
            enabled = !model.isSavingProfiles,
        )
        SetupNote(stringResource(R.string.setup_calls_profiles_body), small = true)
        SetupNote(
            stringResource(
                if (model.profilesOn) R.string.setup_calls_profiles_on else R.string.setup_calls_profiles_off
            ),
        )
    }
}

internal fun modeTitle(mode: String): Int = when (mode) {
    CallHandling.APP_FIRST -> R.string.setup_calls_app_first
    CallHandling.AI_THEN_APP -> R.string.setup_calls_ai_then_app
    else -> R.string.setup_calls_ai_first
}

private fun modeDetail(mode: String): Int = when (mode) {
    CallHandling.APP_FIRST -> R.string.setup_calls_app_first_description
    CallHandling.AI_THEN_APP -> R.string.setup_calls_ai_then_app_description
    else -> R.string.setup_calls_ai_first_description
}

internal object CallsTags {
    const val TRANSFER = "district-setup-calls-transfer"
    const val OPEN_SKILLS = "district-setup-calls-open-skills"
    const val BOOKINGS = "district-setup-calls-bookings"
    const val OPEN_INTEGRATIONS = "district-setup-calls-open-integrations"
    const val ERROR = "district-setup-calls-error"
    const val CONTINUE = "district-setup-calls-continue"
    const val SAVED = "district-setup-calls-saved"
    const val SAVE_ANSWERING = "district-setup-calls-save-answering"
    const val RING = "district-setup-calls-ring"
    const val RING_DOWN = "district-setup-calls-ring-down"
    const val RING_UP = "district-setup-calls-ring-up"
    const val PROFILES = "district-setup-calls-profiles"
}
