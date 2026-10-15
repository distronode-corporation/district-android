package com.distronode.districtai.ui.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictIcons
import com.distronode.districtai.core.designsystem.DistrictTopBarAction
import com.distronode.districtai.ui.FailureText

/**
 * The thread's Report and Block controls (App Store Guideline 1.2 on iOS; Play's user-generated
 * content policy here).
 *
 * ⛔ IN THE APP BAR, behind one "Manage" control, because a thread is a timeline with a composer
 * pinned to the bottom and has no other stable place a reviewer can find without scrolling.
 *
 * ⛔ A BLOCK CLOSES THE THREAD, A REPORT DOES NOT: blocking takes the thread out of the inbox at once,
 * and staying on it would leave the operator on something they cannot get back to.
 */
data class ThreadModeration(
    val onReport: () -> Unit,
    /** Runs after the confirmation. */
    val onBlock: () -> Unit,
    val busy: Boolean,
    val failure: FailureText?,
    val onDismissFailure: () -> Unit,
)

@Composable
internal fun ModerationMenu(moderation: ThreadModeration, onBlock: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DistrictTopBarAction(
            icon = DistrictIcons.MoreVert,
            label = stringResource(R.string.contact_block_manage),
            onClick = { open = true },
            enabled = !moderation.busy,
            modifier = Modifier.testTag(THREAD_MANAGE_DESCRIPTION),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.report_action_conversation)) },
                onClick = {
                    open = false
                    moderation.onReport()
                },
                modifier = Modifier.testTag(THREAD_REPORT_DESCRIPTION),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.contact_block_block)) },
                onClick = {
                    open = false
                    onBlock()
                },
                modifier = Modifier.testTag(THREAD_BLOCK_DESCRIPTION),
            )
        }
    }
}
