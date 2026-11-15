package com.distronode.districtai.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.model.MfaStatusResponse
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.l10n.appLanguage
import com.distronode.districtai.ui.resolve
import java.time.Instant
import java.time.ZoneId

/**
 * The two-step verification screen: whether it is on, turning it on with an authenticator app, the
 * recovery codes once, and turning it off. See [TwoStepViewModel].
 *
 * ⛔ THE SECRET AND THE CODES ARE `FLAG_SECURE` ([SecureWindow]): no screenshot, no recording and a
 * blank app-switcher thumbnail while either is on screen.
 *
 * ⚠️ NO WAY OUT MID-REQUEST, NOR PAST THE CODES: they are never shown again, so Back does nothing on
 * that step until "I have saved them". On the steps of a change Back goes one step back.
 */
@Composable
fun TwoStepScreen(state: TwoStepUiState, actions: TwoStepActions, onBack: () -> Unit) {
    val locked = state.busy || state.step == TwoStepStep.RecoveryCodes
    val stepsBack = state.step is TwoStepStep.Reauth ||
        state.step is TwoStepStep.Password ||
        state.step == TwoStepStep.Scan
    BackHandler(enabled = locked || stepsBack) { if (!locked) actions.back() }
    SecureWindow(secure = state.holdsSecret)

    DistrictScaffold(
        modifier = Modifier.testTag(TWO_STEP_ROOT_TAG),
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.two_step_title),
                onBack = if (locked) null else onBack,
            )
        },
    ) { inset ->
        ContentContainer(modifier = inset.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.padding(DistrictTheme.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.section),
            ) {
                TwoStepContent(state, actions)
            }
        }
    }
}

@Composable
private fun TwoStepContent(state: TwoStepUiState, actions: TwoStepActions) {
    when (val step = state.step) {
        TwoStepStep.Loading -> DistrictCard { SkeletonBlock(height = DistrictTheme.spacing.header) }
        TwoStepStep.LoadFailed -> {
            TwoStepMessages(state)
            DistrictButton(
                text = stringResource(R.string.two_step_retry),
                onClick = @DontMemoize { actions.load() },
                variant = ButtonVariant.Secondary,
                enabled = !state.busy,
                modifier = Modifier.testTag(TWO_STEP_RETRY_TAG),
            )
        }
        TwoStepStep.Overview -> {
            TwoStepMessages(state)
            state.status?.let { TwoStepStatusCard(it, state.signsInWithoutPassword, state.busy, actions) }
        }
        is TwoStepStep.Reauth -> {
            TwoStepMessages(state)
            ReauthMethodsCard(
                intro = stringResource(
                    if (step.intent == TwoStepIntent.TURN_ON) {
                        R.string.two_step_reauth_intro_on
                    } else {
                        R.string.two_step_reauth_intro_off
                    },
                ),
                busy = state.busy,
                onProvider = @DontMemoize { actions.reauthenticate(it) },
                onUsePassword = @DontMemoize { actions.usePassword() },
                onBack = @DontMemoize { actions.back() },
            )
        }
        is TwoStepStep.Password -> {
            TwoStepMessages(state)
            ReauthPasswordCard(
                password = state.password,
                code = state.code,
                needsCode = step.needsCode,
                busy = state.busy,
                canSubmit = state.canSubmitPassword,
                onPassword = @DontMemoize { actions.onField(TwoStepField.PASSWORD, it) },
                onCode = @DontMemoize { actions.onField(TwoStepField.CODE, it) },
                onSubmit = @DontMemoize { actions.submitPassword() },
                onBack = @DontMemoize { actions.back() },
            )
        }
        // ⚠️ NO MESSAGE LINE ABOVE THE CARD HERE: the card is taller than a phone, so a refusal drawn
        // above it is off screen when Turn on is tapped. The card draws it by the field.
        TwoStepStep.Scan -> state.enrollment?.let { TwoStepScanCard(state, it, actions) }
        TwoStepStep.RecoveryCodes -> TwoStepCodesCard(state, actions)
    }
}

/** The notice of the last change, then the failure, each as one line. */
@Composable
private fun TwoStepMessages(state: TwoStepUiState) {
    state.notice?.let { AccountMessage(it.resolve(), failure = false, tag = TWO_STEP_NOTICE_TAG) }
    state.failure?.let { AccountMessage(it.resolve(), failure = true, tag = TWO_STEP_MESSAGE_TAG) }
}

@Composable
private fun TwoStepStatusCard(
    status: MfaStatusResponse,
    signsInWithoutPassword: Boolean,
    busy: Boolean,
    actions: TwoStepActions,
) {
    AccountCard {
        Text(
            text = stringResource(if (status.enrolled) R.string.two_step_status_on else R.string.two_step_status_off),
            style = MaterialTheme.typography.titleMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.testTag(TWO_STEP_STATUS_TAG),
        )
        if (status.enrolled) {
            status.enrolledAt?.let { AccountParagraph(stringResource(R.string.two_step_on_since, enrolledDate(it))) }
            AccountParagraph(
                if (status.backupCodesRemaining == 0) {
                    stringResource(R.string.two_step_codes_none)
                } else {
                    pluralStringResource(
                        R.plurals.two_step_codes_left,
                        status.backupCodesRemaining,
                        status.backupCodesRemaining
                    )
                },
            )
            if (signsInWithoutPassword) AccountParagraph(stringResource(R.string.two_step_no_password_note))
            DistrictButton(
                text = stringResource(R.string.two_step_turn_off),
                onClick = @DontMemoize { actions.begin(TwoStepIntent.TURN_OFF) },
                variant = ButtonVariant.Danger,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag(TWO_STEP_TURN_OFF_TAG),
            )
        } else {
            AccountParagraph(stringResource(R.string.two_step_off_explain))
            DistrictButton(
                text = stringResource(R.string.two_step_turn_on),
                onClick = @DontMemoize { actions.begin(TwoStepIntent.TURN_ON) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag(TWO_STEP_TURN_ON_TAG),
            )
        }
        AccountBusy(busy)
    }
}

/**
 * When the factor was turned on, as a long date in the reader's language and zone. ⚠️ A value that
 * is not an instant is shown as sent rather than dropped.
 */
@Composable
private fun enrolledDate(iso: String): String {
    val language = appLanguage()
    return runCatching { Instant.parse(iso) }
        .map { DistrictFormat.date(it, ZoneId.systemDefault(), DistrictFormat.DateStyle.LONG, language) }
        .getOrDefault(iso)
}

const val TWO_STEP_ROOT_TAG: String = "district-two-step-root"
const val TWO_STEP_STATUS_TAG: String = "district-two-step-status"
const val TWO_STEP_TURN_ON_TAG: String = "district-two-step-turn-on"
const val TWO_STEP_TURN_OFF_TAG: String = "district-two-step-turn-off"
const val TWO_STEP_RETRY_TAG: String = "district-two-step-retry"
const val TWO_STEP_NOTICE_TAG: String = "district-two-step-notice"
const val TWO_STEP_MESSAGE_TAG: String = "district-two-step-message"
