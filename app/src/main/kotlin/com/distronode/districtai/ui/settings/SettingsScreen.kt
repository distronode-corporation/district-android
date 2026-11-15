package com.distronode.districtai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.Eyebrow

/**
 * The account surface: sign out, the devices, two-step verification, and start account deletion.
 *
 * ⛔ THIS SCREEN EXISTS BECAUSE PLAY REQUIRES IT, NOT BECAUSE THE PRODUCT ASKED FOR IT. Google's
 * User Data policy requires an app with accounts to offer an in-app route to initiate account
 * deletion, and reviewers check for sign-out in the same pass. This screen is the only UI route to
 * sign-out (`TokenRefreshCoordinator.forget()`), so without it a signed-in user has no way to stop
 * being signed in. An upload without both is a rejection, not a nit.
 *
 * ⚠️ FOUR ROWS, EACH ONE REAL. No notification channels or theme choice: anything like that would be
 * a placeholder. The device list earned its row by being the only place an account's live sessions
 * are visible (a `deviceId` is client-generated and opaque, so the two revoke routes are otherwise
 * unreachable), and two-step verification by being the account's own security setting, which the
 * iOS app also offers.
 *
 * ⚠️ DEVICES SITS IN THE SESSION CARD, NOT THE ACCOUNT ONE. Signing another device out ends a
 * session; the account card's only row starts an irreversible deletion, and grouping a routine
 * action beside it would borrow that weight.
 *
 * ⚠️ DELETION OPENS A SCREEN, NOT A BUTTON THAT DELETES. The screen says what goes and what stays,
 * asks for a fresh re-authentication and the typed email address, and only its last button deletes
 * (as in the iOS app). The service enforces every step.
 *
 * ⚠️ THE DELETION ROW IS NOT PAINTED RED. It is destructive, but it does not delete anything on
 * tap — it opens the deletion screen. Colouring it `destructive` would promise irreversibility one
 * tap early and make the row look like it had already failed, which is the same reason
 * `ButtonVariant.Danger` is a tinted outline rather than a solid fill. The caption carries the
 * warning instead.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenTwoStep: () -> Unit,
) {
    DistrictScaffold(
        modifier = Modifier.testTag(SETTINGS_ROOT_DESCRIPTION),
        topBar = {
            DistrictTopBar(title = stringResource(R.string.settings_title), onBack = onBack)
        },
    ) { inset ->
        // ⛔ SCROLLABLE, AND THAT IS A PLAY-REVIEW REQUIREMENT RATHER THAN POLISH. With a fixed
        // height, a phone in landscape or at a large font scale can push the ACCOUNT card (whose
        // only row is the mandatory account-deletion entry point) off the bottom, where a reviewer
        // cannot reach it and neither can a user. An upload missing that route is a rejection, not a
        // nit, so the container has to grow rather than clip.
        ContentContainer(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.padding(DistrictTheme.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.section),
            ) {
                DistrictCard {
                    Eyebrow(text = stringResource(R.string.settings_section_session))
                    ActionRow(
                        label = stringResource(R.string.settings_sign_out),
                        caption = stringResource(R.string.settings_sign_out_caption),
                        description = SETTINGS_SIGN_OUT_DESCRIPTION,
                        onClick = onSignOut,
                    )
                    ActionRow(
                        label = stringResource(R.string.settings_devices),
                        caption = stringResource(R.string.settings_devices_caption),
                        description = SETTINGS_DEVICES_DESCRIPTION,
                        onClick = onOpenDevices,
                    )
                }

                DistrictCard {
                    Eyebrow(text = stringResource(R.string.settings_section_account))
                    ActionRow(
                        label = stringResource(R.string.settings_delete_account),
                        caption = stringResource(R.string.settings_delete_account_caption),
                        description = SETTINGS_DELETE_ACCOUNT_DESCRIPTION,
                        onClick = onDeleteAccount,
                    )
                }

                // ⚠️ AFTER THE ACCOUNT CARD, so the Play-mandated deletion row keeps its place.
                DistrictCard {
                    Eyebrow(text = stringResource(R.string.settings_section_security))
                    ActionRow(
                        label = stringResource(R.string.settings_two_step),
                        caption = stringResource(R.string.settings_two_step_caption),
                        description = SETTINGS_TWO_STEP_DESCRIPTION,
                        onClick = onOpenTwoStep,
                    )
                }
            }
        }
    }
}

/**
 * ⚠️ The whole row is the target, and the test tag is on the row rather than on the label,
 * so a screen reader announces one actionable item instead of two unrelated texts.
 */
@Composable
private fun ActionRow(
    label: String,
    caption: String,
    description: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = DistrictTheme.spacing.row)
            .testTag(description),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = DistrictTheme.colors.foreground,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
        )
    }
}

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val SETTINGS_ROOT_DESCRIPTION: String = "district-settings-root"
const val SETTINGS_SIGN_OUT_DESCRIPTION: String = "district-settings-sign-out"
const val SETTINGS_DEVICES_DESCRIPTION: String = "district-settings-devices"
const val SETTINGS_DELETE_ACCOUNT_DESCRIPTION: String = "district-settings-delete-account"
const val SETTINGS_TWO_STEP_DESCRIPTION: String = "district-settings-two-step"

/**
 * The live, public deletion page.
 *
 * ⚠️ THE APP NO LONGER OPENS IT (deletion happens in the app), and it stays as the URL declared in
 * the store listing's data-deletion field, which must resolve for a Play reviewer with no session and
 * no app build config. Kept here, beside the row, so the two stay the same literal.
 */
const val ACCOUNT_DELETION_URL: String = "https://www.distronode.com/privacy/account-deletion"

// ⚠️ INTERNAL RATHER THAN PRIVATE so `SettingsScreenTest` can render it: a preview that stopped
// composing would break Android Studio's renderer without failing anything else.
@Preview(showBackground = true)
@Composable
internal fun SettingsScreenPreview() {
    DistrictTheme {
        SettingsScreen(onBack = {}, onSignOut = {}, onDeleteAccount = {}, onOpenDevices = {}, onOpenTwoStep = {})
    }
}
