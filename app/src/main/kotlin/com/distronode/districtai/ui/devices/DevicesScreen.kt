package com.distronode.districtai.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.model.NativeDevice
import com.distronode.districtai.ui.InlineFailure
import com.distronode.districtai.ui.resolve

/**
 * Every install signed in to this account, and the two ways to end one.
 *
 * ⛔ BOTH DESTRUCTIVE ACTIONS ARE CONFIRMED, AND THEY ARE NOT EQUALLY DESTRUCTIVE. Signing out
 * one device is recoverable by signing back in on it; "sign out everywhere" includes the phone
 * in the user's hand and logs them out mid-tap. Neither is a mis-tap away, and the two dialogs
 * say different things because a shared confirmation would understate the second.
 *
 * ⛔ THE ROW FOR THIS DEVICE IS MARKED, AND THAT MARKING IS SAFETY RATHER THAN POLISH. Device
 * names are client-supplied free text, so two identical handsets on one account produce two
 * identical-looking rows; without the marker, "sign out the one that is not mine" is a coin
 * flip, and losing it means signing out the phone you are holding while the lost one stays live.
 * The marker is derived from the opaque installation id, never from the name.
 *
 * ⚠️ "Last active" IS DELIBERATELY VAGUE, AND THE CAPTION UNDERSTATES ON PURPOSE. The server
 * stamps `lastUsedAt` only when a refresh token ROTATES, so the value trails real use by up to
 * about ten minutes and stops moving entirely on a device that is signed in but unopened.
 * Calling it "last used" would be a claim the data cannot support.
 *
 * ⚠️ AN EMPTY LIST IS AN EXPLANATORY EMPTY STATE, NOT AN ERROR. The server hides a chain that is
 * mid-rotation, so a single-device account genuinely sees zero rows for a moment while perfectly
 * signed in. See [DevicesListState.Ready].
 */
@Composable
fun DevicesScreen(
    state: DevicesUiState,
    thisDeviceId: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onRevokeAll: () -> Unit,
    onDismissNotices: () -> Unit,
) {
    // ⚠️ Held as the device being confirmed rather than as a boolean, so the dialog can name
    // what it is about to end and so a stale confirmation cannot apply to a different row.
    var confirming: NativeDevice? by remember { mutableStateOf(null) }
    var confirmingAll by remember { mutableStateOf(false) }

    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = DEVICES_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.devices_title), onBack = onBack)
        },
    ) { inset ->
        ContentContainer(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.padding(DistrictTheme.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.section),
            ) {
                state.mutationFailure?.let { DeviceNotice(it.message.resolve(), true, onDismissNotices) }
                if (state.nothingRevoked) {
                    DeviceNotice(
                        message = stringResource(R.string.devices_nothing_revoked),
                        destructive = false,
                        onDismiss = onDismissNotices,
                    )
                }

                when (val list = state.devices) {
                    DevicesListState.Loading -> LoadingRows()
                    is DevicesListState.Ready -> DeviceList(
                        devices = list.devices,
                        thisDeviceId = thisDeviceId,
                        busy = state.busy,
                        onRequestRevoke = { confirming = it },
                        onRequestRevokeAll = { confirmingAll = true },
                    )
                    is DevicesListState.Failed -> InlineFailure(
                        title = stringResource(R.string.devices_failed),
                        failure = list.failure,
                        onRetry = onRetry,
                        description = DEVICES_LIST_FAILURE_DESCRIPTION,
                    )
                }
            }
        }
    }

    confirming?.let { device ->
        val thisDevice = device.deviceId == thisDeviceId
        ConfirmDialog(
            // ⚠️ Signing out THIS device ends the session in the user's hand, which the generic
            // wording would not warn about.
            text = if (thisDevice) {
                stringResource(R.string.devices_confirm_this_device)
            } else {
                stringResource(R.string.devices_confirm_device)
            },
            confirmLabel = stringResource(R.string.devices_confirm_sign_out),
            description = DEVICES_CONFIRM_DEVICE_DESCRIPTION,
            onConfirm = {
                confirming = null
                onRevokeDevice(device.deviceId)
            },
            onDismiss = { confirming = null },
        )
    }

    if (confirmingAll) {
        ConfirmDialog(
            text = stringResource(R.string.devices_confirm_all),
            confirmLabel = stringResource(R.string.devices_confirm_sign_out),
            description = DEVICES_CONFIRM_ALL_DESCRIPTION,
            onConfirm = {
                confirmingAll = false
                onRevokeAll()
            },
            onDismiss = { confirmingAll = false },
        )
    }
}

@Composable
private fun LoadingRows() {
    Column(
        modifier = Modifier.semantics { contentDescription = DEVICES_LOADING_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        repeat(SKELETON_ROWS) { SkeletonBlock(height = DistrictTheme.spacing.header) }
    }
}

/**
 * ⚠️ THE "sign out everywhere" CONTROL IS RENDERED EVEN WHEN THE LIST IS EMPTY. An empty list can
 * mean a chain is mid-rotation rather than that nothing is signed in, and this is the control
 * someone reaches for when they believe a device is live that the list is not showing.
 */
@Composable
private fun DeviceList(
    devices: List<NativeDevice>,
    thisDeviceId: String,
    busy: Boolean,
    onRequestRevoke: (NativeDevice) -> Unit,
    onRequestRevokeAll: () -> Unit,
) {
    if (devices.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.devices_empty_title),
            body = stringResource(R.string.devices_empty_body),
            modifier = Modifier.semantics { contentDescription = DEVICES_EMPTY_DESCRIPTION },
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row)) {
            devices.forEach { device ->
                DeviceCard(
                    device = device,
                    thisDevice = device.deviceId == thisDeviceId,
                    busy = busy,
                    onRequestRevoke = { onRequestRevoke(device) },
                )
            }
        }
    }

    DistrictButton(
        text = stringResource(R.string.devices_sign_out_all),
        onClick = onRequestRevokeAll,
        // ⚠️ Danger is a tinted outline in this system rather than a solid fill, so it reads as
        // "this one is destructive" without looking like the screen's primary action.
        variant = ButtonVariant.Danger,
        size = ButtonSize.Sm,
        enabled = !busy,
        modifier = Modifier
            .padding(top = DistrictTheme.spacing.row)
            .semantics { contentDescription = DEVICES_REVOKE_ALL_DESCRIPTION },
    )
}

@Composable
private fun DeviceCard(
    device: NativeDevice,
    thisDevice: Boolean,
    busy: Boolean,
    onRequestRevoke: () -> Unit,
) {
    DistrictCard(
        modifier = Modifier.semantics {
            contentDescription = deviceRowDescription(device.deviceId)
        },
    ) {
        // ⚠️ The eyebrow is the marker, and it is the ONLY place "this device" is asserted —
        // derived from the installation id, never from the name. See the ⛔ on the screen.
        if (thisDevice) {
            Eyebrow(
                text = stringResource(R.string.devices_this_device),
                color = DistrictTheme.colors.district,
                modifier = Modifier.semantics { contentDescription = DEVICES_THIS_DEVICE_DESCRIPTION },
            )
        }
        Text(
            // ⚠️ An unnamed device gets a neutral placeholder rather than its raw id: the id is
            // an opaque UUID that means nothing to a person, and showing it invites them to
            // treat it as an identifier they should recognise.
            text = device.deviceName?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.devices_unnamed),
            style = MaterialTheme.typography.titleSmall,
            color = DistrictTheme.colors.foreground,
        )
        Text(
            text = device.platform.ifBlank { stringResource(R.string.devices_unknown_platform) },
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
        )
        Text(
            // ⛔ "Last active", never "last used" — see the ⚠️ on the screen. A device that has
            // not refreshed yet has no value at all, which is every device for its first ten
            // minutes, so the absent case gets its own sentence rather than an empty line.
            text = device.lastUsedAt?.let { stringResource(R.string.devices_last_active, it) }
                ?: stringResource(R.string.devices_last_active_never),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
        )
        DistrictButton(
            text = stringResource(R.string.devices_sign_out_device),
            onClick = onRequestRevoke,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            enabled = !busy,
            modifier = Modifier
                .padding(top = DistrictTheme.spacing.tight)
                .semantics { contentDescription = revokeRowDescription(device.deviceId) },
        )
    }
}

/**
 * ⚠️ ONE COMPONENT FOR BOTH NOTICES, TONED BY A FLAG. A failed revoke and a `revoked: 0` look
 * alike on screen and are not alike in meaning — the second is not an error at all — so the
 * colour is the only thing that differs and the wording carries the rest.
 */
@Composable
private fun DeviceNotice(message: String, destructive: Boolean, onDismiss: () -> Unit) {
    DistrictCard(
        modifier = Modifier.semantics {
            contentDescription =
                if (destructive) DEVICES_FAILURE_DESCRIPTION else DEVICES_NOTICE_DESCRIPTION
        },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (destructive) {
                DistrictTheme.colors.destructive
            } else {
                DistrictTheme.colors.mutedForeground
            },
        )
        DistrictButton(
            text = stringResource(R.string.devices_dismiss),
            onClick = onDismiss,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
    }
}

@Composable
private fun ConfirmDialog(
    text: String,
    confirmLabel: String,
    description: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.contact_detail_cancel))
            }
        },
    )
}

private const val SKELETON_ROWS = 3

/**
 * Per-row test handles, derived in one place so a row and its test cannot drift apart.
 *
 * ⚠️ Keyed on the DEVICE ID rather than on the list index, because the list reorders on every
 * re-read (newest first, and a rotation moves a row) — an index-keyed handle would silently
 * start addressing a different device.
 */
internal fun deviceRowDescription(deviceId: String): String = "district-devices-row-$deviceId"

internal fun revokeRowDescription(deviceId: String): String = "district-devices-revoke-$deviceId"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val DEVICES_ROOT_DESCRIPTION: String = "district-devices-root"
const val DEVICES_LOADING_DESCRIPTION: String = "district-devices-loading"
const val DEVICES_EMPTY_DESCRIPTION: String = "district-devices-empty"
const val DEVICES_THIS_DEVICE_DESCRIPTION: String = "district-devices-this-device"
const val DEVICES_REVOKE_ALL_DESCRIPTION: String = "district-devices-revoke-all"
const val DEVICES_CONFIRM_DEVICE_DESCRIPTION: String = "district-devices-confirm-device"
const val DEVICES_CONFIRM_ALL_DESCRIPTION: String = "district-devices-confirm-all"
const val DEVICES_LIST_FAILURE_DESCRIPTION: String = "district-devices-list-failure"
const val DEVICES_FAILURE_DESCRIPTION: String = "district-devices-failure"
const val DEVICES_NOTICE_DESCRIPTION: String = "district-devices-notice"

// ⚠️ INTERNAL RATHER THAN PRIVATE so `DevicesScreenTest` can render it: a preview that stopped
// composing would break Android Studio's renderer without failing anything else.
@Preview(showBackground = true)
@Composable
internal fun DevicesScreenPreview() {
    DistrictTheme {
        DevicesScreen(
            state = DevicesUiState(
                devices = DevicesListState.Ready(
                    listOf(
                        NativeDevice(
                            deviceId = "device-this-one",
                            deviceName = "Google Pixel 9",
                            platform = "android",
                            lastUsedAt = "2026-08-17T09:15:00.000Z",
                            createdAt = "2026-08-01T09:15:00.000Z",
                        ),
                        NativeDevice(
                            deviceId = "device-other",
                            platform = "ios",
                            createdAt = "2026-07-20T11:00:00.000Z",
                        ),
                    ),
                ),
            ),
            thisDeviceId = "device-this-one",
            onBack = {},
            onRetry = {},
            onRevokeDevice = {},
            onRevokeAll = {},
            onDismissNotices = {},
        )
    }
}
