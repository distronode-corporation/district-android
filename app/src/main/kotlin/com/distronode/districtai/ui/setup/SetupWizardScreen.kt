// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("TooManyFunctions")

package com.distronode.districtai.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.SETUP_STEP_SKIPPED
import com.distronode.districtai.ui.InlineFailure
import com.distronode.districtai.ui.resolve

/**
 * The setup wizard: the web's six steps, in its order and with its words, most of them run here (see
 * [SetupWizardViewModel]).
 *
 * ⚠️ A DESTINATION OF ITS OWN, ENTERED FROM THE OVERVIEW'S CARD, and nothing deeper: where the person
 * left off is the service's to say ([SetupFlow.firstOpen]), so the wizard never restores a step from
 * a back stack. System back closes it, as Finish later does, except while a write is in flight.
 *
 * ⛔ THE SHELL KEEPS THE THREE ACTIONS THAT MEAN THE SAME ON EVERY STEP: Finish later, Back, Skip. A
 * step's own primary action is a save whose shape only the step knows, so each step draws it.
 */
@Composable
fun SetupWizardScreen(wizard: SetupWizardViewModel, actions: SetupActions, onClose: () -> Unit) {
    // ⚠️ NO LEAVING MID-WRITE: the answer would land on a screen nobody sees. Otherwise back is
    // Finish later, so the overview reads the card again either way.
    BackHandler { if (!wizard.isMoving) onClose() }
    val scroll = rememberScrollState()
    // ⚠️ A NEW STEP OPENS AT ITS TOP, not wherever the last one was scrolled to.
    LaunchedEffect(wizard.active) { scroll.scrollTo(0) }
    DistrictScaffold(
        modifier = Modifier.testTag(SetupTags.ROOT),
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.setup_title),
                actions = {
                    DistrictButton(
                        text = stringResource(R.string.setup_finish_later),
                        onClick = onClose,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                        enabled = !wizard.isMoving,
                        modifier = Modifier.testTag(SetupTags.FINISH_LATER),
                    )
                },
            )
        },
    ) { inset ->
        Column(modifier = inset.fillMaxSize().verticalScroll(scroll)) {
            ContentContainer(contentPadding = PaddingValues(DistrictTheme.spacing.gutter)) {
                Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.section)) {
                    SetupContent(wizard, actions, onClose)
                }
            }
            Spacer(modifier = Modifier.height(DistrictTheme.spacing.header))
        }
    }
}

@Composable
private fun SetupContent(wizard: SetupWizardViewModel, actions: SetupActions, onClose: () -> Unit) {
    when (val phase = wizard.phase) {
        SetupPhase.Loading -> SetupNote(
            text = stringResource(R.string.setup_loading),
            modifier = Modifier.testTag(SetupTags.LOADING),
        )
        is SetupPhase.Refused -> SetupEnding(
            title = stringResource(refusalTitle(phase.refusal)),
            body = stringResource(refusalBody(phase.refusal)),
            tag = SetupTags.REFUSED,
            onClose = onClose,
        )
        is SetupPhase.Failed -> InlineFailure(
            title = null,
            failure = phase.failure,
            onRetry = @DontMemoize { wizard.load() },
            description = SetupTags.FAILED,
            retryDescription = SetupTags.RETRY,
        )
        SetupPhase.Finished -> SetupEnding(
            title = stringResource(R.string.setup_finished_title),
            body = stringResource(R.string.setup_finished_body),
            tag = SetupTags.FINISHED,
            onClose = onClose,
        )
        SetupPhase.Ready -> {
            SetupProgressHeader(wizard)
            SetupActiveStep(wizard, actions)
            wizard.stepError?.let {
                SetupErrorLine(it.message.resolve(), modifier = Modifier.testTag(SetupTags.STEP_ERROR))
            }
            SetupFooter(wizard)
        }
    }
}

@Composable
private fun SetupActiveStep(wizard: SetupWizardViewModel, actions: SetupActions) {
    Column(
        modifier = Modifier.testTag(SetupTags.step(wizard.active)),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.section),
    ) {
        when (val model = wizard.stepModel) {
            is SetupBusinessModel -> SetupBusinessStep(model, actions)
            is SetupReceptionistModel -> SetupReceptionistStep(model)
            is SetupCallersModel -> SetupCallersStep(model)
            is SetupCallsModel -> SetupCallsStep(model, actions)
            is SetupGoLiveModel -> SetupGoLiveStep(model, wizard)
            else -> SetupNumberStep(wizard, actions)
        }
    }
}

@Composable
private fun SetupEnding(title: String, body: String, tag: String, onClose: () -> Unit) {
    Box(modifier = Modifier.testTag(tag)) {
        EmptyState(
            title = title,
            body = body,
            action = {
                DistrictButton(
                    text = stringResource(R.string.setup_done),
                    onClick = onClose,
                    modifier = Modifier.testTag(SetupTags.DONE),
                )
            },
        )
    }
}

/** Back and Skip, the two moves every step shares. */
@Composable
private fun SetupFooter(wizard: SetupWizardViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        if (SetupFlow.previous(wizard.active) != null) {
            DistrictButton(
                text = stringResource(R.string.setup_back),
                onClick = @DontMemoize { wizard.back() },
                variant = ButtonVariant.Secondary,
                enabled = !wizard.isMoving,
                modifier = Modifier.testTag(SetupTags.BACK),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        DistrictButton(
            text = stringResource(R.string.setup_skip_step),
            onClick = @DontMemoize { wizard.skip() },
            variant = ButtonVariant.Ghost,
            enabled = !wizard.isMoving,
            modifier = Modifier.testTag(SetupTags.SKIP),
        )
    }
}

/**
 * "Step n of 6", a segment per step, and the step's title and description.
 *
 * ⚠️ THE SEGMENTS ARE ONE ACCESSIBILITY NODE whose description is the sentence, and each step's state
 * is said in words, never only by its colour.
 */
@Composable
private fun SetupProgressHeader(wizard: SetupWizardViewModel) {
    val position = wizard.active.ordinal + 1
    val total = SetupFlow.steps.size
    val words = mutableListOf<String>()
    for (step in SetupFlow.steps) {
        words += stringResource(
            R.string.setup_progress_step_state,
            stringResource(stepTitle(step)),
            stringResource(stepWord(wizard, step)),
        )
    }
    // ⚠️ THE PUNCTUATION IS EACH LANGUAGE'S OWN (a space before the colon in French), from the catalog.
    val spoken = stringResource(
        R.string.setup_progress_sentence,
        stringResource(R.string.setup_progress_label),
        words.joinToString(", "),
    )
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = spoken }
                .testTag(SetupTags.PROGRESS),
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
        ) {
            SetupFlow.steps.forEach { step ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(segmentColor(wizard, step), RoundedCornerShape(2.dp)),
                )
            }
        }
        Text(
            text = stringResource(R.string.setup_step_of, position, total),
            style = MaterialTheme.typography.labelMedium,
            color = DistrictTheme.colors.mutedForeground,
        )
        Text(
            text = stringResource(stepTitle(wizard.active)),
            style = MaterialTheme.typography.titleLarge,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.semantics { heading() },
        )
        SetupNote(stringResource(stepDescription(wizard.active)))
    }
}

@Composable
private fun segmentColor(wizard: SetupWizardViewModel, step: SetupStep): Color {
    val colors = DistrictTheme.colors
    return when {
        step == wizard.active -> colors.district
        wizard.state(step) == SETUP_STEP_DONE -> colors.success
        wizard.state(step) == SETUP_STEP_SKIPPED -> colors.mutedForeground.copy(alpha = 0.5f)
        else -> colors.border
    }
}

private fun stepWord(wizard: SetupWizardViewModel, step: SetupStep): Int = when {
    step == wizard.active -> R.string.setup_step_current
    wizard.state(step) == SETUP_STEP_DONE -> R.string.setup_step_done
    wizard.state(step) == SETUP_STEP_SKIPPED -> R.string.setup_step_skipped
    else -> R.string.setup_step_todo
}

private fun refusalTitle(refusal: SetupRefusal): Int = when (refusal) {
    SetupRefusal.NOT_OWNER -> R.string.setup_not_owner_title
    SetupRefusal.NOT_AVAILABLE -> R.string.setup_not_available_title
}

private fun refusalBody(refusal: SetupRefusal): Int = when (refusal) {
    SetupRefusal.NOT_OWNER -> R.string.setup_not_owner_body
    SetupRefusal.NOT_AVAILABLE -> R.string.setup_not_available_body
}

internal fun stepTitle(step: SetupStep): Int = when (step) {
    SetupStep.BUSINESS -> R.string.setup_business_title
    SetupStep.NUMBER -> R.string.setup_number_title
    SetupStep.RECEPTIONIST -> R.string.setup_receptionist_title
    SetupStep.CALLERS -> R.string.setup_callers_title
    SetupStep.CALLS -> R.string.setup_calls_title
    SetupStep.GOLIVE -> R.string.setup_golive_title
}

private fun stepDescription(step: SetupStep): Int = when (step) {
    SetupStep.BUSINESS -> R.string.setup_business_description
    SetupStep.NUMBER -> R.string.setup_number_description
    SetupStep.RECEPTIONIST -> R.string.setup_receptionist_description
    SetupStep.CALLERS -> R.string.setup_callers_description
    SetupStep.CALLS -> R.string.setup_calls_description
    SetupStep.GOLIVE -> R.string.setup_golive_description
}

/** Stable handles for tests. ⚠️ Test tags, never content descriptions, which TalkBack reads aloud. */
object SetupTags {
    const val ROOT = "district-setup"
    const val FINISH_LATER = "district-setup-finish-later"
    const val LOADING = "district-setup-loading"
    const val REFUSED = "district-setup-refused"
    const val FAILED = "district-setup-failed"
    const val RETRY = "district-setup-retry"
    const val FINISHED = "district-setup-finished"
    const val DONE = "district-setup-done"
    const val PROGRESS = "district-setup-progress"
    const val STEP_ERROR = "district-setup-step-error"
    const val BACK = "district-setup-back"
    const val SKIP = "district-setup-skip"

    fun step(step: SetupStep): String = "district-setup-step-${step.wire}"

    fun choice(group: String, value: String): String = "district-setup-choice-$group-$value"
}
