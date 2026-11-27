// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.ForwardingCheckMode
import com.distronode.districtai.core.model.ForwardingCheckResponse
import com.distronode.districtai.ui.resolve

/** Step 6, "Test and go live". See [SetupGoLiveModel]. */
@Composable
internal fun SetupGoLiveStep(model: SetupGoLiveModel, wizard: SetupWizardViewModel) {
    GoLiveTestCall(model)
    // The card is drawn from the response it was decided on, so nothing inside it re-reads a nullable.
    val forwarding = model.forwarding
    if (forwarding?.available == true) GoLiveForwarding(model, forwarding)
    SetupCard(heading = stringResource(R.string.setup_golive_live_header)) {
        model.summary?.let { summary ->
            Column(modifier = Modifier.testTag(GoLiveTags.SUMMARY)) {
                DistrictListRow(
                    title = stringResource(R.string.setup_golive_greeting_label),
                    subtitle = summary.greeting.ifEmpty { stringResource(R.string.setup_golive_no_greeting) },
                )
                DistrictListRow(
                    title = stringResource(R.string.setup_golive_answers_label),
                    subtitle = answers(summary)
                )
            }
        }
        SetupPrimaryButton(
            title = stringResource(R.string.setup_golive_finish),
            busy = wizard.isCompleting,
            tag = GoLiveTags.FINISH,
            onClick = @DontMemoize { model.finish() },
        )
    }
}

@Composable
private fun answers(summary: SetupGoLiveSummary): String = when (summary.mode) {
    CallHandling.APP_FIRST -> stringResource(R.string.setup_golive_answers_app_first, summary.ringSeconds)
    CallHandling.AI_THEN_APP -> stringResource(R.string.setup_golive_answers_ai_then_app)
    else -> stringResource(R.string.setup_calls_ai_first)
}

@Composable
private fun GoLiveTestCall(model: SetupGoLiveModel) {
    SetupCard(heading = stringResource(R.string.setup_golive_test_header)) {
        SetupNote(stringResource(R.string.setup_golive_test_description), small = true)
        if (model.hasNumber) {
            SetupField(
                label = stringResource(R.string.setup_golive_mobile_label),
                value = model.mobile,
                onValueChange = @DontMemoize { model.mobile = it },
                tag = GoLiveTags.MOBILE,
                error = stringResource(R.string.setup_golive_mobile_error).takeIf { model.mobileError },
                placeholder = PHONE_EXAMPLE,
                keyboard = KeyboardType.Phone,
            )
            Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
                Eyebrow(stringResource(R.string.setup_golive_consent_heading))
                SetupNote(stringResource(R.string.setup_golive_consent_wording), small = true)
            }
            SetupPrimaryButton(
                title = stringResource(R.string.setup_golive_call_me),
                busy = model.isCalling,
                enabled = model.mobile.isNotBlank(),
                tag = GoLiveTags.CALL_ME,
                onClick = @DontMemoize { model.callMe() },
            )
            when (val status = model.callStatus) {
                SetupCallStatus.Calling ->
                    SetupNote(
                        stringResource(R.string.setup_golive_calling),
                        modifier = Modifier.testTag(GoLiveTags.CALL_STATUS)
                    )
                is SetupCallStatus.Failed ->
                    SetupErrorLine(
                        status.failure.message.resolve(),
                        modifier = Modifier.testTag(GoLiveTags.CALL_STATUS)
                    )
                null -> Unit
            }
        } else {
            SetupNote(
                stringResource(R.string.setup_golive_no_number_yet),
                modifier = Modifier.testTag(GoLiveTags.NO_NUMBER)
            )
        }
    }
}

@Composable
private fun GoLiveForwarding(model: SetupGoLiveModel, forwarding: ForwardingCheckResponse) {
    SetupCard(heading = stringResource(R.string.setup_golive_keeping_header)) {
        SetupNote(stringResource(R.string.setup_golive_keeping_description), small = true)
        Eyebrow(stringResource(R.string.setup_golive_check_header))
        SetupNote(stringResource(R.string.setup_golive_check_description), small = true)
        SetupField(
            label = stringResource(R.string.setup_golive_business_line_label),
            value = model.businessLine,
            onValueChange = @DontMemoize { model.businessLine = it },
            tag = GoLiveTags.BUSINESS_LINE,
            error = stringResource(R.string.setup_golive_business_line_error).takeIf { model.lineError },
            placeholder = PHONE_EXAMPLE,
            keyboard = KeyboardType.Phone,
        )
        Eyebrow(stringResource(R.string.setup_golive_mode_label))
        ForwardingCheckMode.MODES.forEach { mode ->
            SetupChoiceRow(
                title = stringResource(forwardingModeTitle(mode)),
                selected = model.mode == mode,
                enabled = !model.isPolling,
                tag = SetupTags.choice("forwarding", mode),
                onClick = @DontMemoize { model.mode = mode },
            )
        }
        if (model.mode == ForwardingCheckMode.UNANSWERED) {
            SetupNote(stringResource(R.string.setup_golive_unanswered_note), small = true)
        }
        val verifier = forwarding.forwardingCheck?.verifierNumber.orEmpty()
        if (verifier.isNotEmpty() && model.isPolling) {
            SetupNote(
                stringResource(R.string.setup_golive_expect_call, verifier),
                modifier = Modifier.testTag(GoLiveTags.EXPECT)
            )
        }
        SetupPrimaryButton(
            title = stringResource(R.string.setup_golive_start_check),
            // ⚠️ BUSY ONLY WHILE STARTING: a poll runs for minutes and saves nothing, so the button keeps
            // its own title then. A pending check is read again on a tap, which needs no line and no
            // check left today.
            busy = model.isStartingCheck,
            enabled = !model.isPolling &&
                (model.checkIsPending || (model.businessLine.isNotBlank() && forwarding.attemptsLeft != 0)),
            tag = GoLiveTags.START_CHECK,
            onClick = @DontMemoize { model.startCheck() },
        )
        CheckStatus(model, forwarding.attemptsLeft)
    }
}

@Composable
private fun CheckStatus(model: SetupGoLiveModel, attemptsLeft: Int) {
    Column(
        modifier = Modifier.testTag(GoLiveTags.CHECK_STATUS),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
    ) {
        val error = model.checkError
        val sentence = model.checkSentence
        if (error != null) {
            SetupErrorLine(error.message.resolve())
        } else if (sentence != null) {
            SetupNote(stringResource(checkSentence(sentence)))
        }
        SetupNote(
            text = if (attemptsLeft == 1) {
                stringResource(R.string.setup_golive_attempts_left_one)
            } else {
                stringResource(R.string.setup_golive_attempts_left, attemptsLeft)
            },
            small = true,
        )
    }
}

private fun forwardingModeTitle(mode: String): Int =
    if (mode == ForwardingCheckMode.ALL) R.string.setup_golive_mode_all else R.string.setup_golive_mode_unanswered

private fun checkSentence(sentence: SetupCheckSentence): Int = when (sentence) {
    SetupCheckSentence.PENDING -> R.string.setup_golive_check_pending
    SetupCheckSentence.STILL_PENDING -> R.string.setup_golive_check_still_pending
    SetupCheckSentence.PASSED -> R.string.setup_golive_check_passed
    SetupCheckSentence.DIAL_FAILED -> R.string.setup_golive_check_dial_failed
    SetupCheckSentence.FAILED -> R.string.setup_golive_check_failed
}

/** ⚠️ A NUMBER, NOT COPY: the same example in both languages, as the web's field shows it. */
private const val PHONE_EXAMPLE = "+14165550123"

internal object GoLiveTags {
    const val MOBILE = "district-setup-golive-mobile"
    const val CALL_ME = "district-setup-golive-call-me"
    const val CALL_STATUS = "district-setup-golive-call-status"
    const val NO_NUMBER = "district-setup-golive-no-number"
    const val BUSINESS_LINE = "district-setup-golive-business-line"
    const val EXPECT = "district-setup-golive-expect"
    const val START_CHECK = "district-setup-golive-start-check"
    const val CHECK_STATUS = "district-setup-golive-check-status"
    const val SUMMARY = "district-setup-golive-summary"
    const val FINISH = "district-setup-golive-finish"
}
