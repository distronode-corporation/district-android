package com.distronode.districtai.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.Box
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors

/**
 * The ways to confirm it is you: a fresh sign-in at each provider, or the password.
 *
 * ⚠️ MEANT FOR EVERY FRESH RE-AUTHENTICATION (two-step verification here, account deletion next),
 * with the same words, as on iOS.
 */
@Composable
internal fun ReauthMethodsCard(
    intro: String,
    busy: Boolean,
    onProvider: (ReauthProvider) -> Unit,
    onUsePassword: () -> Unit,
    onBack: (() -> Unit)?,
) {
    AccountCard {
        Eyebrow(text = stringResource(R.string.reauth_confirm_it_is_you))
        AccountParagraph(intro)
        ReauthProvider.entries.forEach { provider ->
            DistrictButton(
                text = stringResource(R.string.reauth_continue_with, provider.displayName),
                onClick = @DontMemoize { onProvider(provider) },
                variant = ButtonVariant.Secondary,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag(reauthProviderTag(provider)),
            )
        }
        DistrictButton(
            text = stringResource(R.string.reauth_use_password),
            onClick = onUsePassword,
            variant = ButtonVariant.Secondary,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag(REAUTH_USE_PASSWORD_TAG),
        )
        onBack?.let { AccountBackButton(busy, it) }
        AccountBusy(busy)
    }
}

/** The password, and once the service asks for it, the authenticator code. */
@Composable
internal fun ReauthPasswordCard(
    password: String,
    code: String,
    needsCode: Boolean,
    busy: Boolean,
    canSubmit: Boolean,
    onPassword: (String) -> Unit,
    onCode: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    AccountCard {
        Eyebrow(text = stringResource(R.string.reauth_confirm_it_is_you))
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text(stringResource(R.string.reauth_password_label)) },
            colors = districtFieldColors(),
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag(REAUTH_PASSWORD_TAG),
        )
        if (needsCode) {
            AccountParagraph(stringResource(R.string.reauth_code_prompt))
            // ⚠️ ONE FIELD FOR BOTH KINDS: the service takes an authenticator code or a recovery
            // code in the same place, so the keyboard is not numeric.
            OutlinedTextField(
                value = code,
                onValueChange = onCode,
                label = { Text(stringResource(R.string.reauth_code_placeholder)) },
                colors = districtFieldColors(),
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth().testTag(REAUTH_CODE_TAG),
            )
        }
        DistrictButton(
            text = stringResource(R.string.reauth_continue_button),
            onClick = onSubmit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().testTag(REAUTH_PASSWORD_CONTINUE_TAG),
        )
        AccountBackButton(busy, onBack)
        AccountBusy(busy)
    }
}

/**
 * A card whose rows are spaced apart, as the iOS sheets' cards are. ⚠️ `DistrictCard` itself stacks
 * its rows flush.
 */
@Composable
internal fun AccountCard(content: @Composable ColumnScope.() -> Unit) {
    DistrictCard {
        Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight), content = content)
    }
}

/** A body paragraph inside an account card. */
@Composable
internal fun AccountParagraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.foreground,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One line saying what went wrong, or what the last change did. */
@Composable
internal fun AccountMessage(text: String, failure: Boolean, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (failure) DistrictTheme.colors.destructive else DistrictTheme.colors.success,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
internal fun AccountBackButton(busy: Boolean, onBack: () -> Unit) {
    DistrictButton(
        text = stringResource(R.string.reauth_back),
        onClick = onBack,
        variant = ButtonVariant.Ghost,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag(REAUTH_BACK_TAG),
    )
}

@Composable
internal fun AccountBusy(busy: Boolean) {
    if (busy) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = DistrictTheme.colors.district)
        }
    }
}

internal fun reauthProviderTag(provider: ReauthProvider): String = "district-reauth-provider-${provider.wire}"

internal const val REAUTH_USE_PASSWORD_TAG: String = "district-reauth-use-password"
internal const val REAUTH_PASSWORD_TAG: String = "district-reauth-password"
internal const val REAUTH_CODE_TAG: String = "district-reauth-code"
internal const val REAUTH_PASSWORD_CONTINUE_TAG: String = "district-reauth-password-continue"
internal const val REAUTH_BACK_TAG: String = "district-reauth-back"
