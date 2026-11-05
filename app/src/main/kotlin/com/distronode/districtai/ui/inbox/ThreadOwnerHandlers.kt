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
)

/**
 * "Assigned to" and the owner, with the picker for a role that may assign.
 *
 * ⚠️ NOTHING UNTIL THE OWNER IS READ: see [ThreadOwnerState.loaded].
 */
@Composable
internal fun ThreadOwnerRow(owner: ThreadOwnerHandlers) {
    val state = owner.state
    if (!state.loaded) return
    var open by remember { mutableStateOf(false) }
    val shown = state.owner?.let { serverText(it) } ?: stringResource(R.string.thread_unassigned)
    ContentContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DistrictTheme.spacing.gutter, vertical = DistrictTheme.spacing.hairline)
                .testTag(THREAD_OWNER_DESCRIPTION),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(stringResource(R.string.thread_assign_label))
                if (state.canAssign) {
                    Box {
                        DistrictButton(
                            text = shown,
                            onClick = { open = true },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                            enabled = !state.assigning,
                            modifier = Modifier.testTag(THREAD_OWNER_PICKER_DESCRIPTION),
                        )
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            OwnerOption(stringResource(R.string.thread_unassigned), THREAD_OWNER_NOBODY_DESCRIPTION) {
                                open = false
                                owner.onAssign(null)
                            }
                            state.members.forEachIndexed { index, email ->
                                OwnerOption(serverText(email), THREAD_OWNER_OPTION_DESCRIPTION + index) {
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
                            .testTag(THREAD_OWNER_FAILURE_DESCRIPTION),
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
private fun OwnerOption(label: String, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, modifier = Modifier.testTag(tag))
}

/** Stable handles for tests. Those ending in `-` take the item's index. */
const val THREAD_OWNER_DESCRIPTION: String = "district-thread-owner"
const val THREAD_OWNER_PICKER_DESCRIPTION: String = "district-thread-owner-picker"
const val THREAD_OWNER_VALUE_DESCRIPTION: String = "district-thread-owner-value"
const val THREAD_OWNER_NOBODY_DESCRIPTION: String = "district-thread-owner-nobody"
const val THREAD_OWNER_OPTION_DESCRIPTION: String = "district-thread-owner-option-"
const val THREAD_OWNER_FAILURE_DESCRIPTION: String = "district-thread-owner-failure"
