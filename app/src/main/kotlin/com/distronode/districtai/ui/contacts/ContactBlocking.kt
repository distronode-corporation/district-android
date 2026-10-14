package com.distronode.districtai.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve

/**
 * The "Blocked" badge. ⚠️ `Danger`, because a blocked caller is a choice somebody made that hides
 * content from this screen, not metadata.
 */
@Composable
fun BlockedBadge(modifier: Modifier = Modifier) {
    DistrictBadge(text = stringResource(R.string.contact_block_badge), tone = Tone.Danger, modifier = modifier)
}

/**
 * The block or unblock control, with its confirmation.
 *
 * ⛔ CONFIRMED IN BOTH DIRECTIONS: a mis-tapped block is a conversation that silently vanishes, and a
 * mis-tapped unblock is content silently coming back. The prompt says what blocking does and that
 * it can be undone, and does not claim the caller can no longer phone.
 */
@Composable
fun BlockControl(blocked: Boolean, enabled: Boolean, onSetBlocked: (Boolean) -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { confirming = true },
        enabled = enabled,
        modifier = Modifier.testTag(if (blocked) UNBLOCK_TAG else BLOCK_TAG),
    ) {
        Text(stringResource(if (blocked) R.string.contact_block_unblock else R.string.contact_block_block))
    }
    if (confirming) {
        BlockConfirmDialog(
            blocked = blocked,
            onConfirm = {
                confirming = false
                onSetBlocked(!blocked)
            },
            onDismiss = { confirming = false },
        )
    }
}

/** The confirmation for a block ([blocked] false) or an unblock ([blocked] true). */
@Composable
fun BlockConfirmDialog(blocked: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(
                stringResource(
                    if (blocked) R.string.contact_block_unblock_prompt else R.string.contact_block_block_prompt
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(BLOCK_CONFIRM_TAG)) {
                Text(
                    stringResource(
                        if (blocked) R.string.contact_block_confirm_unblock else R.string.contact_block_confirm_block,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.contact_block_cancel)) }
        },
    )
}

/**
 * A block write's failure, beside what the screen shows. ⛔ BESIDE, NOT INSTEAD OF: the contact or
 * thread on screen is still good.
 */
@Composable
fun BlockFailureNotice(failure: FailureText, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(BLOCK_FAILURE_TAG),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
    ) {
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.contact_block_dismiss)) }
    }
}

/**
 * What a screen needs to show one caller's block state and control, bundled so a screen takes one
 * parameter for it. Null on a screen built without blocking (a test about the rest of it).
 */
data class BlockUi(
    val blocked: Boolean,
    val busy: Boolean,
    val failure: FailureText?,
    val onSetBlocked: (Boolean) -> Unit,
    val onDismissFailure: () -> Unit,
)

/** Stable handles for tests. */
const val BLOCK_TAG: String = "district-contact-block"
const val UNBLOCK_TAG: String = "district-contact-unblock"
const val BLOCK_CONFIRM_TAG: String = "district-contact-block-confirm"
const val BLOCK_FAILURE_TAG: String = "district-contact-block-failure"
const val BLOCKED_BADGE_TAG: String = "district-contact-blocked-badge"
