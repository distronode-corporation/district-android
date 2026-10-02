package com.distronode.districtai.ui.desk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.ui.CenteredState
import com.distronode.districtai.ui.FailureState
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve
import com.distronode.districtai.ui.settings.workspace.rememberUnsavedChangesGuard

/**
 * The desk's settings, and the customer-facing logo.
 *
 * ⛔ THE SAVE BUTTON IS DISABLED ON A CLEAN FORM RATHER THAN SENDING AN EMPTY PATCH. The route
 * answers 400 for a body with no fields, deliberately, because an empty body is always a client
 * bug — so there is nothing useful a save with nothing to say could do.
 *
 * ⛔ THE LOGO IS NOT PART OF THE FORM AND ITS TWO BUTTONS ARE NOT PART OF THE SAVE. `publicLogoUrl`
 * is not a settings parameter and must never become one: the value has to be a URL this platform
 * produced, and a caller-supplied string there would let a member point their own customers' page
 * at any image on the internet with our domain's reputation attached.
 */
@Composable
fun DeskSettingsScreen(
    state: DeskSettingsUiState,
    canUse: Boolean,
    onEnabled: (Boolean) -> Unit,
    onNotify: (Boolean) -> Unit,
    onBrandName: (String) -> Unit,
    onSave: () -> Unit,
    onPickLogo: () -> Unit,
    onRemoveLogo: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    // ⛔ THE SAME GUARD AS THE WORKSPACE-SETTINGS SCREENS. A dirty brand name or toggle used to be
    // dropped silently by a back gesture here while every workspace-settings screen asked first.
    val guardedBack = rememberUnsavedChangesGuard(
        hasUnsavedChanges = (state as? DeskSettingsUiState.Content)?.dirty == true,
        onBack = onBack,
    )

    // ⚠️ NO `modifier` PARAMETER: the one caller (the nav graph) never sized or placed this screen.
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = DESK_SETTINGS_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.desk_settings_title), onBack = guardedBack)
        },
    ) { inset ->
        Box(modifier = inset.fillMaxSize()) {
            // ⚠️ A `when` on the state itself rather than a chain of `is` tests, so the compiler
            // rather than a trailing test proves every state is drawn.
            if (!canUse) {
                SettingsRefused()
            } else {
                when (state) {
                    DeskSettingsUiState.Loading -> SettingsLoading()
                    is DeskSettingsUiState.Failed -> FailureState(
                        failure = state.failure,
                        onRetry = onRetry,
                        onSignIn = onSignIn,
                        description = DESK_SETTINGS_FAILURE_DESCRIPTION,
                    )
                    is DeskSettingsUiState.Content -> SettingsForm(
                        state = state,
                        onEnabled = onEnabled,
                        onNotify = onNotify,
                        onBrandName = onBrandName,
                        onSave = onSave,
                        onPickLogo = onPickLogo,
                        onRemoveLogo = onRemoveLogo,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsForm(
    state: DeskSettingsUiState.Content,
    onEnabled: (Boolean) -> Unit,
    onNotify: (Boolean) -> Unit,
    onBrandName: (String) -> Unit,
    onSave: () -> Unit,
    onPickLogo: () -> Unit,
    onRemoveLogo: () -> Unit,
) {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(DistrictTheme.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            DistrictCard {
                ToggleRow(
                    labelId = R.string.desk_settings_enabled,
                    checked = state.enabled,
                    enabled = !state.saving,
                    modifier = Modifier.semantics { contentDescription = DESK_SETTINGS_ENABLED_DESCRIPTION },
                    onChange = onEnabled,
                )
                ToggleRow(
                    labelId = R.string.desk_settings_notify,
                    checked = state.notifyCustomersByEmail,
                    enabled = !state.saving,
                    modifier = Modifier.semantics { contentDescription = DESK_SETTINGS_NOTIFY_DESCRIPTION },
                    onChange = onNotify,
                )

                OutlinedTextField(
                    value = state.brandName,
                    onValueChange = onBrandName,
                    label = { Text(stringResource(R.string.desk_settings_brand_name)) },
                    enabled = !state.saving,
                    singleLine = true,
                    colors = districtFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = DistrictTheme.spacing.tight)
                        .semantics { contentDescription = DESK_SETTINGS_BRAND_DESCRIPTION },
                )
                // ⛔ SAYS WHAT AN EMPTY BOX DOES. Clearing it is a real write with a real result —
                // the tenant's customers then see the workspace's own name — and an operator who
                // did not know that would read the blank as "nothing set".
                Text(
                    text = stringResource(R.string.desk_settings_brand_name_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                )

                state.saveFailure?.let {
                    InlineError(it, Modifier.semantics { contentDescription = DESK_SETTINGS_SAVE_FAILURE_DESCRIPTION })
                }

                DistrictButton(
                    text = stringResource(
                        if (state.saving) {
                            R.string.desk_settings_saving
                        } else {
                            R.string.desk_settings_save
                        },
                    ),
                    onClick = onSave,
                    // ⛔ A clean form cannot be saved: an empty patch is a 400 (and so is an
                    // over-long brand name). See DeskSettingsUiState.Content.canSave.
                    enabled = state.canSave,
                    modifier = Modifier
                        .padding(top = DistrictTheme.spacing.tight)
                        .semantics { contentDescription = DESK_SETTINGS_SAVE_DESCRIPTION },
                )
            }

            LogoCard(state, onPickLogo, onRemoveLogo)
        }
    }
}

@Composable
private fun LogoCard(
    state: DeskSettingsUiState.Content,
    onPickLogo: () -> Unit,
    onRemoveLogo: () -> Unit,
) {
    DistrictCard(modifier = Modifier.semantics { contentDescription = DESK_LOGO_DESCRIPTION }) {
        Eyebrow(text = stringResource(R.string.desk_logo_title))
        Text(
            text = stringResource(R.string.desk_logo_help),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
        )

        val published = state.stored.publicLogoUrl
        Text(
            // ⚠️ The URL itself, because this screen does not load remote images and showing the
            // address is honest about what is published. There is no placeholder pretending to be
            // the logo.
            text = published ?: stringResource(R.string.desk_logo_none),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.padding(vertical = DistrictTheme.spacing.hairline),
        )

        // ⛔ THE TAKEDOWN'S SECOND HALF, SURFACED RATHER THAN SWALLOWED. A 200 means the column was
        // cleared, which is what takes the image off the customers' page; `objectRemoved: false`
        // means the stored file survived and may still answer its old link.
        if (state.logoObjectRetained) {
            Text(
                text = stringResource(R.string.desk_logo_object_retained),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.warning,
                modifier = Modifier.semantics {
                    contentDescription = DESK_LOGO_RETAINED_DESCRIPTION
                },
            )
        }

        state.logoFailure?.let {
            InlineError(it, Modifier.semantics { contentDescription = DESK_LOGO_FAILURE_DESCRIPTION })
        }

        Row(
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            DistrictButton(
                text = stringResource(
                    when {
                        state.logoBusy -> R.string.desk_logo_busy
                        published != null -> R.string.desk_logo_replace
                        else -> R.string.desk_logo_choose
                    },
                ),
                onClick = onPickLogo,
                enabled = !state.logoBusy,
                modifier = Modifier.semantics {
                    contentDescription = DESK_LOGO_CHOOSE_DESCRIPTION
                },
            )
            if (published != null) {
                DistrictButton(
                    text = stringResource(R.string.desk_logo_remove),
                    onClick = onRemoveLogo,
                    variant = ButtonVariant.Danger,
                    enabled = !state.logoBusy,
                    modifier = Modifier.semantics {
                        contentDescription = DESK_LOGO_REMOVE_DESCRIPTION
                    },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    labelId: Int,
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = DistrictTheme.spacing.hairline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelId),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = modifier,
        )
    }
}

@Composable
private fun InlineError(failure: FailureText, modifier: Modifier) {
    Text(
        text = failure.message.resolve(),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.destructive,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = DistrictTheme.spacing.hairline),
    )
}

@Composable
private fun SettingsLoading() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = DESK_SETTINGS_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_ROWS) { SkeletonBlock(height = SKELETON_HEIGHT) }
        }
    }
}

@Composable
private fun SettingsRefused() {
    CenteredState(DESK_SETTINGS_REFUSED_DESCRIPTION) {
        Text(
            text = stringResource(R.string.desk_viewer_body),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            textAlign = TextAlign.Center,
        )
    }
}

private const val SKELETON_ROWS = 5
private val SKELETON_HEIGHT = 48.dp

const val DESK_SETTINGS_ROOT_DESCRIPTION: String = "district-desk-settings-root"
const val DESK_SETTINGS_LOADING_DESCRIPTION: String = "district-desk-settings-loading"
const val DESK_SETTINGS_FAILURE_DESCRIPTION: String = "district-desk-settings-failure"
const val DESK_SETTINGS_REFUSED_DESCRIPTION: String = "district-desk-settings-refused"
const val DESK_SETTINGS_ENABLED_DESCRIPTION: String = "district-desk-settings-enabled"
const val DESK_SETTINGS_NOTIFY_DESCRIPTION: String = "district-desk-settings-notify"
const val DESK_SETTINGS_BRAND_DESCRIPTION: String = "district-desk-settings-brand"
const val DESK_SETTINGS_SAVE_DESCRIPTION: String = "district-desk-settings-save"
const val DESK_SETTINGS_SAVE_FAILURE_DESCRIPTION: String = "district-desk-settings-save-failure"
const val DESK_LOGO_DESCRIPTION: String = "district-desk-logo"
const val DESK_LOGO_CHOOSE_DESCRIPTION: String = "district-desk-logo-choose"
const val DESK_LOGO_REMOVE_DESCRIPTION: String = "district-desk-logo-remove"
const val DESK_LOGO_FAILURE_DESCRIPTION: String = "district-desk-logo-failure"
const val DESK_LOGO_RETAINED_DESCRIPTION: String = "district-desk-logo-retained"
