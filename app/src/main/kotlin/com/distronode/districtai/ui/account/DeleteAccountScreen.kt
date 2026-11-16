package com.distronode.districtai.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.ui.resolve

/**
 * The account deletion screen: what is deleted and kept, a fresh re-authentication, the typed email,
 * then the deletion. See [DeleteAccountViewModel].
 *
 * ⛔ AFTER A DELETION THE SCREEN DOES NOT CLOSE ITSELF. `AppContainer.finishDeletedAccount` signs out
 * locally, and the activity's session gate swaps the whole graph, this screen with it, for the
 * sign-in screen, which says the account was deleted.
 *
 * ⚠️ NO WAY OUT MID-REQUEST: the answer would land on a screen nobody sees.
 */
@Composable
fun DeleteAccountScreen(state: DeleteAccountUiState, actions: DeleteAccountActions, onBack: () -> Unit) {
    BackHandler(
        enabled = state.busy || state.step is DeleteAccountStep.Password,
        onBack = @DontMemoize { if (!state.busy) actions.back() },
    )
    DistrictScaffold(
        modifier = Modifier.testTag(DELETE_ACCOUNT_ROOT_TAG),
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.delete_account_title),
                onBack = if (state.busy) null else onBack,
            )
        },
    ) { inset ->
        ContentContainer(modifier = inset.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.padding(DistrictTheme.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.section),
            ) {
                DeleteAccountContent(state, actions)
            }
        }
    }
}

@Composable
private fun DeleteAccountContent(state: DeleteAccountUiState, actions: DeleteAccountActions) {
    when (val step = state.step) {
        DeleteAccountStep.Explain -> {
            DeleteAccountWarningCard()
            DeleteAccountFailureLine(state.failure)
            ReauthMethodsCard(
                intro = stringResource(R.string.delete_account_confirm_intro),
                busy = state.busy,
                onProvider = @DontMemoize { actions.reauthenticate(it) },
                onUsePassword = @DontMemoize { actions.usePassword() },
                onBack = null,
            )
        }
        is DeleteAccountStep.Password -> {
            DeleteAccountFailureLine(state.failure)
            ReauthPasswordCard(
                password = state.password,
                code = state.code,
                needsCode = step.needsCode,
                busy = state.busy,
                canSubmit = state.canSubmitPassword,
                onPassword = @DontMemoize { actions.onField(DeleteAccountField.PASSWORD, it) },
                onCode = @DontMemoize { actions.onField(DeleteAccountField.CODE, it) },
                onSubmit = @DontMemoize { actions.submitPassword() },
                onBack = @DontMemoize { actions.back() },
            )
        }
        DeleteAccountStep.Confirm -> {
            DeleteAccountFailureLine(state.failure)
            DeleteAccountConfirmCard(state, actions)
        }
    }
}

@Composable
private fun DeleteAccountWarningCard() {
    AccountCard {
        Text(
            text = stringResource(R.string.delete_account_cannot_be_undone),
            style = MaterialTheme.typography.titleMedium,
            color = DistrictTheme.colors.destructive,
        )
        AccountParagraph(stringResource(R.string.delete_account_what_is_deleted))
        AccountParagraph(stringResource(R.string.delete_account_subscriptions_cancelled))
        AccountParagraph(stringResource(R.string.delete_account_what_is_kept))
    }
}

@Composable
private fun DeleteAccountConfirmCard(state: DeleteAccountUiState, actions: DeleteAccountActions) {
    AccountCard {
        Eyebrow(text = stringResource(R.string.delete_account_last_step))
        AccountParagraph(stringResource(R.string.delete_account_type_email))
        OutlinedTextField(
            value = state.confirmation,
            onValueChange = @DontMemoize { actions.onField(DeleteAccountField.EMAIL, it) },
            label = { Text(stringResource(R.string.delete_account_email_label)) },
            colors = districtFieldColors(),
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag(DELETE_ACCOUNT_EMAIL_TAG),
        )
        DistrictButton(
            text = stringResource(if (state.busy) R.string.delete_account_deleting else R.string.delete_account_submit),
            onClick = @DontMemoize { actions.delete() },
            variant = ButtonVariant.Danger,
            enabled = state.canDelete,
            modifier = Modifier.fillMaxWidth().testTag(DELETE_ACCOUNT_SUBMIT_TAG),
        )
        AccountBusy(state.busy)
    }
}

/** A refusal: its headline, when it has one, above what went wrong and what to do. */
@Composable
private fun DeleteAccountFailureLine(failure: DeleteAccountFailure?) {
    failure ?: return
    Column(modifier = Modifier.fillMaxWidth().testTag(DELETE_ACCOUNT_MESSAGE_TAG)) {
        failure.title?.let {
            Text(
                text = it.resolve(),
                style = MaterialTheme.typography.labelLarge,
                color = DistrictTheme.colors.destructive,
            )
        }
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
        )
    }
}

const val DELETE_ACCOUNT_ROOT_TAG: String = "district-delete-account-root"
const val DELETE_ACCOUNT_EMAIL_TAG: String = "district-delete-account-email"
const val DELETE_ACCOUNT_SUBMIT_TAG: String = "district-delete-account-submit"
const val DELETE_ACCOUNT_MESSAGE_TAG: String = "district-delete-account-message"
