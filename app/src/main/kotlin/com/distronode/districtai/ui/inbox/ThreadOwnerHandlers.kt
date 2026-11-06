package com.distronode.districtai.ui.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.l10n.serverText
import com.distronode.districtai.ui.resolve

/** The thread's owner line and picker. See [ThreadOwnerViewModel]. */
data class ThreadOwnerHandlers(
    val state: ThreadOwnerState,
    val onAssign: (String?) -> Unit,
    val onDismissFailure: () -> Unit,
    /** Read the owner again, after [ThreadOwnerState.readFailed]. */
    val onRetry: () -> Unit,
)

/**
 * "Assigned to" and the owner, with the picker for a role that may assign.
 *
 * ⚠️ NOTHING UNTIL THE OWNER IS READ: see [ThreadOwnerState.loaded]. A read that failed says so,
 * with Try again, and never shows "Unassigned" in place of an owner it could not read.
 */
@Composable
internal fun ThreadOwnerRow(owner: ThreadOwnerHandlers) {
    val state = owner.state
    if (!state.loaded) {
        if (state.readFailed) OwnerReadFailed(owner.onRetry)
        return
    }
    var open by remember { mutableStateOf(false) }
    val shown = state.owner?.let { serverText(it) } ?: stringResource(R.string.thread_unassigned)
    ContentContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DistrictTheme.spacing.gutter, vertical = DistrictTheme.spacing.hairline)
                .testTag(THREAD_OWNER_DESCRIPTION),
        ) {
            // ⚠️ ONE THING TO TALKBACK: "Assigned to" and the owner are a single reading. Without a
            // picker the row merges them; with one the button says both, and what a tap does.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (state.canAssign) Modifier else Modifier.semantics(mergeDescendants = true) {},
            ) {
                Eyebrow(
                    stringResource(R.string.thread_assign_label),
                    modifier = if (state.canAssign) Modifier.clearAndSetSemantics {} else Modifier,
                )
                if (state.canAssign) {
                    val spoken = state.owner?.let { stringResource(R.string.inbox_assigned_to, serverText(it)) }
                        ?: stringResource(R.string.thread_unassigned)
                    val change = stringResource(R.string.thread_assign_change)
                    Box {
                        DistrictButton(
                            text = shown,
                            onClick = { open = true },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                            enabled = !state.assigning,
                            modifier = Modifier
                                .testTag(THREAD_OWNER_PICKER_DESCRIPTION)
                                .semantics {
                                    contentDescription = spoken
                                    onClick(label = change) {
                                        open = true
                                        true
                                    }
                                },
                        )
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            OwnerOption(
                                stringResource(R.string.thread_unassigned),
                                THREAD_OWNER_NOBODY_DESCRIPTION,
                                selected = state.owner == null,
                            ) {
                                open = false
                                owner.onAssign(null)
                            }
                            state.members.forEachIndexed { index, email ->
                                OwnerOption(
                                    serverText(email),
                                    THREAD_OWNER_OPTION_DESCRIPTION + index,
                                    selected = email == state.owner,
                                ) {
                                    open = false
                                    owner.onAssign(email)
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DistrictTheme.colors.foreground,
                        modifier = Modifier
                            .padding(start = DistrictTheme.spacing.tight)
                            .testTag(THREAD_OWNER_VALUE_DESCRIPTION),
                    )
                }
            }
            state.failure?.let { failure ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = failure.message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.destructive,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(THREAD_OWNER_FAILURE_DESCRIPTION)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    DistrictButton(
                        text = stringResource(R.string.thread_dismiss),
                        onClick = owner.onDismissFailure,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                    )
                }
            }
        }
    }
}

@Composable
private fun OwnerReadFailed(onRetry: () -> Unit) {
    ContentContainer {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DistrictTheme.spacing.gutter, vertical = DistrictTheme.spacing.hairline)
                .testTag(THREAD_OWNER_READ_FAILED_DESCRIPTION),
        ) {
            Text(
                text = stringResource(R.string.thread_owner_failed),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.weight(1f),
            )
            DistrictButton(
                text = stringResource(R.string.overview_retry),
                onClick = onRetry,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag(THREAD_OWNER_RETRY_DESCRIPTION),
            )
        }
    }
}

/** ⚠️ The current owner reads "selected": the menu draws no mark, and the button above it is closed. */
@Composable
private fun OwnerOption(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        modifier = Modifier
            .testTag(tag)
            .semantics { this.selected = selected },
    )
}

/** Stable handles for tests. Those ending in `-` take the item's index. */
const val THREAD_OWNER_DESCRIPTION: String = "district-thread-owner"
const val THREAD_OWNER_PICKER_DESCRIPTION: String = "district-thread-owner-picker"
const val THREAD_OWNER_VALUE_DESCRIPTION: String = "district-thread-owner-value"
const val THREAD_OWNER_NOBODY_DESCRIPTION: String = "district-thread-owner-nobody"
const val THREAD_OWNER_OPTION_DESCRIPTION: String = "district-thread-owner-option-"
const val THREAD_OWNER_FAILURE_DESCRIPTION: String = "district-thread-owner-failure"
const val THREAD_OWNER_READ_FAILED_DESCRIPTION: String = "district-thread-owner-read-failed"
const val THREAD_OWNER_RETRY_DESCRIPTION: String = "district-thread-owner-retry"
