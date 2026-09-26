package com.distronode.districtai.ui.settings.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.ui.resolve

/**
 * ⛔ WHAT A WORKSPACE-SETTINGS SCREEN SHOWS WHEN IT COULD NOT READ THE CONFIGURATION, AND IT IS
 * THE WHOLE POINT OF THIS PACKAGE. A retry, and nothing else. No fields, no toggles, no save.
 *
 * Three of this surface's save routes replace their stored value WHOLESALE rather than merging
 * it, so a form rendered from nothing and then saved does not save nothing: it deletes the
 * transfer directory the voice agent routes live callers through, or the agent's tool allowlist.
 * The web never had to think about this — its settings page is a server component that hydrates
 * every form from the row during render — which is exactly why a native client has to be
 * explicit about it. `PersonaFormScreenTest` and `CapabilitiesScreenTest` both assert the ABSENCE
 * of every editable handle in this state; that absence is the feature.
 */
@Composable
internal fun ConfigLoadFailure(
    failure: com.distronode.districtai.ui.FailureText,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(DistrictTheme.spacing.gutter)
            .semantics { contentDescription = WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(stringResource(R.string.workspace_settings_load_failed))
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
        )
        // ⚠️ Only when retrying could work. A signed-out or role-refused failure repeats
        // identically, and a button that cannot help is worse than none.
        if (failure.retryable) {
            DistrictButton(
                text = stringResource(R.string.overview_retry),
                onClick = onRetry,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.semantics {
                    contentDescription = WORKSPACE_SETTINGS_RETRY_DESCRIPTION
                },
            )
        }
    }
}

/**
 * The banner after a save.
 *
 * ⛔ [SaveState.SavedButStale] IS ITS OWN WORDING AND IS NOT DESTRUCTIVE-COLOURED. The write
 * landed; only the read back failed. Drawing it as an error would invite a second save from state
 * the client can no longer vouch for, which on this surface means re-sending a wholesale-replace
 * array built from a stale baseline. It says "saved, but we could not re-read it" and offers a
 * re-read rather than a re-save.
 */
@Composable
internal fun SaveNotice(state: SaveState, description: String) {
    val text: String
    val tone = when (state) {
        SaveState.Saved -> {
            text = stringResource(R.string.workspace_settings_saved)
            DistrictTheme.colors.foreground
        }
        is SaveState.SavedButStale -> {
            text = stringResource(R.string.workspace_settings_saved_stale)
            DistrictTheme.colors.mutedForeground
        }
        is SaveState.Failed -> {
            text = state.failure.message.resolve()
            DistrictTheme.colors.destructive
        }
        SaveState.Idle, SaveState.Saving -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = tone,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/**
 * Back with pending edits.
 *
 * ⛔ CHEAP, AND IT PREVENTS THE ONE LOSS THIS APP CANNOT UNDO ON THIS SCREEN. A persona is
 * customer-authored prose and a capability list is a deliberate configuration; leaving either
 * behind with a system back gesture and no warning throws away work with no trace. `CreateContact`
 * learned the same lesson the other way (rememberSaveable, because a rotation ate a typed name).
 */
@Composable
internal fun UnsavedChangesDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.workspace_settings_unsaved_title)) },
        text = { Text(stringResource(R.string.workspace_settings_unsaved_body)) },
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                modifier = Modifier.semantics {
                    contentDescription = WORKSPACE_SETTINGS_DISCARD_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.workspace_settings_discard))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = WORKSPACE_SETTINGS_KEEP_EDITING_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.workspace_settings_keep_editing))
            }
        },
    )
}

/**
 * ⛔ THE READ SUCCEEDED AND THE VALUE STILL CANNOT BE EDITED, WHICH IS A THIRD STATE AND NOT A
 * FAILURE. `callDirectory` and `routingRules` are Prisma `Json` columns that were written with no
 * validation at all until their save routes gained schemas, so a stored array whose elements are
 * not objects genuinely exists. This client cannot rebuild such a value losslessly, and a
 * wholesale-replace save built around the parts it CAN model would delete the rest — so the editor
 * is withheld entirely.
 *
 * ⚠️ NO RETRY BUTTON, DELIBERATELY. Nothing about the request failed; retrying it returns the same
 * value. The fix is on the web dashboard, and the body says so.
 */
@Composable
internal fun NotEditableNotice(title: String, body: String, description: String) {
    Column(
        modifier = Modifier
            .padding(DistrictTheme.spacing.gutter)
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(title)
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
        )
    }
}

/** ⚠️ Skeletons rather than a blank expanse, so a slow read does not read as an empty workspace. */
@Composable
internal fun ConfigSkeleton() {
    Column(
        modifier = Modifier
            .padding(DistrictTheme.spacing.gutter)
            .semantics { contentDescription = WORKSPACE_SETTINGS_LOADING_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        repeat(SKELETON_ROWS) { SkeletonBlock(height = DistrictTheme.spacing.header) }
    }
}

/**
 * Token colours for a Material text field.
 *
 * ⚠️ THE SAME REASON `CreateContactDialog` HAS ITS OWN COPY: Material's default
 * `OutlinedTextField` draws its container and placeholder greys from the Material baseline rather
 * than from `--muted`/`--muted-foreground`, so a field left on the defaults is subtly the wrong
 * panel on a correctly-themed screen. Not hoisted into core-designsystem in this package because
 * that is a shared-component decision with three existing call sites to migrate.
 */
@Composable
internal fun districtFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = DistrictTheme.colors.muted,
    unfocusedContainerColor = DistrictTheme.colors.muted,
    disabledContainerColor = DistrictTheme.colors.muted,
    focusedBorderColor = DistrictTheme.colors.district,
    unfocusedBorderColor = DistrictTheme.colors.border,
    focusedTextColor = DistrictTheme.colors.foreground,
    unfocusedTextColor = DistrictTheme.colors.foreground,
    disabledTextColor = DistrictTheme.colors.mutedForeground,
    cursorColor = DistrictTheme.colors.district,
)

/**
 * One labelled box, on the tokens rather than on Material's baseline.
 *
 * ⚠️ SHARED BY THE THREE EDITORS IN THIS PACKAGE. `PersonaFormScreen` keeps its own wrapper because
 * its call sites also pass a `PersonaField` and the shared name would read ambiguously there; the
 * colours come from [districtFieldColors] in both cases, which is the part that must not diverge.
 */
@Composable
internal fun SettingsTextField(
    value: String,
    label: String,
    description: String,
    enabled: Boolean,
    singleLine: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Eyebrow(label) },
        colors = districtFieldColors(),
        singleLine = singleLine,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
    )
}

private const val SKELETON_ROWS = 3

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val WORKSPACE_SETTINGS_LOADING_DESCRIPTION: String = "district-workspace-settings-loading"
const val WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION: String =
    "district-workspace-settings-load-failure"
const val WORKSPACE_SETTINGS_RETRY_DESCRIPTION: String = "district-workspace-settings-retry"
const val WORKSPACE_SETTINGS_DISCARD_DESCRIPTION: String = "district-workspace-settings-discard"
const val WORKSPACE_SETTINGS_KEEP_EDITING_DESCRIPTION: String =
    "district-workspace-settings-keep-editing"
