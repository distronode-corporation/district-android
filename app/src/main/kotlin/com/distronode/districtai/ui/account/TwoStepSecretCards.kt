// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.ui.resolve

/**
 * The new secret: its QR code, the key to type by hand, and the code that confirms it.
 *
 * ⛔ NOTHING HERE LOGS, PERSISTS OR SHARES THE SECRET. Copy writes it to this device's clipboard,
 * marked sensitive, for two minutes ([SensitiveClipboard]); Open hands the `otpauth://` link to an
 * authenticator app on this device; there is no share sheet.
 */
@Composable
internal fun TwoStepScanCard(state: TwoStepUiState, enrollment: TwoStepEnrollment, actions: TwoStepActions) {
    val context = LocalContext.current
    // ⚠️ `remember`, NOT `rememberSaveable`: the key is never written into saved instance state.
    var copied by remember { mutableStateOf(false) }
    var openFailed by rememberSaveable { mutableStateOf(false) }
    AccountCard {
        Eyebrow(text = stringResource(R.string.two_step_scan_title))
        AccountParagraph(stringResource(R.string.two_step_scan_intro))
        TwoStepQrCode(enrollment.qrPngBase64)
        Text(
            text = stringResource(R.string.two_step_key_label),
            style = MaterialTheme.typography.labelLarge,
            color = DistrictTheme.colors.foreground,
        )
        TwoStepSecretText(groupedKey(enrollment.secret), TWO_STEP_SECRET_TAG)
        DistrictButton(
            text = stringResource(if (copied) R.string.two_step_copied else R.string.two_step_copy_key),
            onClick = @DontMemoize {
                SensitiveClipboard.copy(context, enrollment.secret)
                copied = true
            },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(TWO_STEP_COPY_KEY_TAG),
        )
        DistrictButton(
            text = stringResource(R.string.two_step_open_authenticator),
            onClick = @DontMemoize { openFailed = !openAuthenticator(context, enrollment.otpauthUri) },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(TWO_STEP_OPEN_TAG),
        )
        if (openFailed) AccountParagraph(stringResource(R.string.two_step_open_failed))
        AccountParagraph(stringResource(R.string.two_step_code_prompt))
        OutlinedTextField(
            value = state.totp,
            onValueChange = @DontMemoize { actions.onField(TwoStepField.TOTP, it) },
            label = { Text(stringResource(R.string.reauth_code_placeholder)) },
            colors = districtFieldColors(),
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().testTag(TWO_STEP_TOTP_TAG),
        )
        state.failure?.let { AccountMessage(it.resolve(), failure = true, tag = TWO_STEP_MESSAGE_TAG) }
        DistrictButton(
            text = stringResource(R.string.two_step_confirm_button),
            onClick = @DontMemoize { actions.confirm() },
            enabled = state.canConfirm,
            modifier = Modifier.fillMaxWidth().testTag(TWO_STEP_CONFIRM_TAG),
        )
        AccountBackButton(state.busy, @DontMemoize { actions.back() })
        AccountBusy(state.busy)
    }
}

/** The recovery codes, this once, with Copy all and the warning that they are not shown again. */
@Composable
internal fun TwoStepCodesCard(state: TwoStepUiState, actions: TwoStepActions) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val joined = state.recoveryCodes.joinToString("\n")
    AccountCard {
        Eyebrow(text = stringResource(R.string.two_step_codes_title))
        Text(
            text = stringResource(R.string.two_step_codes_warning),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
            modifier = Modifier.fillMaxWidth(),
        )
        TwoStepSecretText(joined, TWO_STEP_CODES_TAG)
        DistrictButton(
            text = stringResource(if (copied) R.string.two_step_copied else R.string.two_step_copy_all),
            onClick = @DontMemoize {
                SensitiveClipboard.copy(context, joined)
                copied = true
            },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth().testTag(TWO_STEP_COPY_CODES_TAG),
        )
        DistrictButton(
            text = stringResource(R.string.two_step_codes_done),
            onClick = @DontMemoize { actions.finishRecoveryCodes() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag(TWO_STEP_CODES_DONE_TAG),
        )
        AccountBusy(state.busy)
    }
}

/**
 * The service's QR code, drawn nearest-neighbour on a white quiet zone.
 *
 * ⚠️ NEAREST-NEIGHBOUR: a smoothed QR code blurs its modules and scans worse. ⚠️ WHITE IN BOTH THEMES:
 * the service draws dark on light, and a dark surround would leave it without its quiet zone.
 */
@Composable
private fun TwoStepQrCode(pngBase64: String?) {
    val bitmap = remember(pngBase64) { pngBase64?.let(::decodePng) }
    if (bitmap == null) {
        AccountParagraph(stringResource(R.string.two_step_qr_unreadable))
        return
    }
    val label = stringResource(R.string.two_step_qr_label)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            filterQuality = FilterQuality.None,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = QR_MAX_WIDTH)
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(DistrictTheme.spacing.tight))
                .padding(DistrictTheme.spacing.tight)
                .semantics { contentDescription = label }
                .testTag(TWO_STEP_QR_TAG),
        )
    }
}

/**
 * A secret in a monospaced box.
 *
 * ⛔ NOT SELECTABLE: the system's selection copy would put the secret on the clipboard unmarked and
 * for good, around [SensitiveClipboard]. The Copy buttons are the way to copy it.
 */
@Composable
private fun TwoStepSecretText(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = DistrictTheme.colors.foreground,
        modifier = Modifier
            .fillMaxWidth()
            .background(DistrictTheme.colors.muted, RoundedCornerShape(DistrictTheme.spacing.tight))
            .padding(DistrictTheme.spacing.tight)
            .testTag(tag),
    )
}

/** The PNG as a bitmap, or null when it is not one. */
internal fun decodePng(base64: String): ImageBitmap? {
    val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

/**
 * Hands the `otpauth://` link to an authenticator app on this device. Answers whether one took it.
 * ⚠️ In a new task: the person is going to another app, and comes back to this one by its own task.
 */
internal fun openAuthenticator(context: Context, otpauthUri: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, otpauthUri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

private val QR_MAX_WIDTH = 220.dp

const val TWO_STEP_SECRET_TAG: String = "district-two-step-secret"
const val TWO_STEP_COPY_KEY_TAG: String = "district-two-step-copy-key"
const val TWO_STEP_OPEN_TAG: String = "district-two-step-open"
const val TWO_STEP_TOTP_TAG: String = "district-two-step-totp"
const val TWO_STEP_CONFIRM_TAG: String = "district-two-step-confirm"
const val TWO_STEP_QR_TAG: String = "district-two-step-qr"
const val TWO_STEP_CODES_TAG: String = "district-two-step-codes"
const val TWO_STEP_COPY_CODES_TAG: String = "district-two-step-copy-codes"
const val TWO_STEP_CODES_DONE_TAG: String = "district-two-step-codes-done"
