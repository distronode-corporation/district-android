package com.distronode.districtai.ui.scheduling.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.resolve

/**
 * A scheduling write in a dialog: a title, what the write will do, its fields, how it went, and the
 * confirm and cancel buttons. The Android shape of district-ios's `SchedulingWriteSheet`.
 *
 * ⛔ THE DIALOG CANNOT BE DISMISSED WHILE THE WRITE IS ON ITS WAY, and the confirm button is
 * disabled for the same time, so one press is one request. ⚠️ It stays open after a success, with
 * the sentence that says what happened, exactly as the reference's sheet does; Cancel closes it.
 *
 * @param destructive draws the confirm button in the danger style (cancel, delete, archive).
 */
@Composable
fun SchedulingWriteDialog(
    title: String,
    subtitle: String?,
    confirmLabel: String,
    cancelLabel: String,
    state: WriteState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    tag: String,
    confirmTag: String,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    onRetry: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val working = state.isWorking
    AlertDialog(
        // ⚠️ Back and an outside tap are switched off while the write is on its way, rather than
        // checked in a lambda, so there is one place that decides it.
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnBackPress = !working, dismissOnClickOutside = !working),
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        modifier = Modifier.testTag(tag),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
            ) {
                subtitle?.let { SchedulingHint(it) }
                content()
                SchedulingWriteOutcome(state = state, onRetry = onRetry)
            }
        },
        confirmButton = {
            DistrictButton(
                text = if (working) stringResource(R.string.scheduling_admin_common_saving) else confirmLabel,
                onClick = onConfirm,
                variant = if (destructive) ButtonVariant.Danger else ButtonVariant.Primary,
                enabled = confirmEnabled && !working,
                modifier = Modifier.testTag(confirmTag),
            )
        },
        dismissButton = {
            DistrictButton(
                text = cancelLabel,
                onClick = onCancel,
                variant = ButtonVariant.Ghost,
                enabled = !working,
                modifier = Modifier.testTag(SCHEDULING_WRITE_CANCEL_TAG),
            )
        },
    )
}

/**
 * "Are you sure?" before a remove, with nothing to fill in: revoking a key, deleting a webhook,
 * disconnecting a calendar. The Android shape of district-ios's `confirmationDialog`s.
 */
@Composable
fun SchedulingConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    tag: String,
    confirmTag: String,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        modifier = Modifier.testTag(tag),
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            DistrictButton(
                text = confirmLabel,
                onClick = onConfirm,
                variant = ButtonVariant.Danger,
                modifier = Modifier.testTag(confirmTag),
            )
        },
        dismissButton = {
            DistrictButton(
                text = cancelLabel,
                onClick = onCancel,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.testTag(SCHEDULING_WRITE_CANCEL_TAG),
            )
        },
    )
}

/** How a write ended, under its form: the confirmation, or the failure with Retry where it helps. */
@Composable
fun SchedulingWriteOutcome(state: WriteState, onRetry: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    when (state) {
        is WriteState.Done -> SchedulingNoticeLine(state.message, onDismiss)
        is WriteState.Failed -> SchedulingFailureLine(state.failure, onRetry, onDismiss)
        else -> Unit
    }
}

/** A success sentence, in the success colour. */
@Composable
fun SchedulingNoticeLine(message: UiText, onDismiss: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth().testTag(SCHEDULING_WRITE_NOTICE_TAG)) {
        Text(
            text = message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.success,
        )
        onDismiss?.let { DismissButton(it) }
    }
}

/** A failure sentence, with Retry when the failure is retryable and a retry was offered. */
@Composable
fun SchedulingFailureLine(failure: FailureText, onRetry: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth().testTag(SCHEDULING_WRITE_FAILURE_TAG)) {
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            if (failure.retryable && onRetry != null) {
                DistrictButton(
                    text = stringResource(R.string.scheduling_admin_common_retry),
                    onClick = onRetry,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag(SCHEDULING_WRITE_RETRY_TAG),
                )
            }
            onDismiss?.let { DismissButton(it) }
        }
    }
}

/** A line in the danger colour under a field, for a value the form refused before sending. */
@Composable
fun SchedulingRejection(message: String?) {
    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
            modifier = Modifier.fillMaxWidth().testTag(SCHEDULING_WRITE_REJECTION_TAG),
        )
    }
}

@Composable
private fun DismissButton(onDismiss: () -> Unit) {
    DistrictButton(
        text = stringResource(R.string.scheduling_admin_common_dismiss),
        onClick = onDismiss,
        variant = ButtonVariant.Ghost,
        size = ButtonSize.Sm,
        modifier = Modifier.testTag(SCHEDULING_WRITE_DISMISS_TAG),
    )
}

/** Stable handles for tests. */
const val SCHEDULING_WRITE_CANCEL_TAG: String = "district-scheduling-write-cancel"
const val SCHEDULING_WRITE_NOTICE_TAG: String = "district-scheduling-write-notice"
const val SCHEDULING_WRITE_FAILURE_TAG: String = "district-scheduling-write-failure"
const val SCHEDULING_WRITE_RETRY_TAG: String = "district-scheduling-write-retry"
const val SCHEDULING_WRITE_DISMISS_TAG: String = "district-scheduling-write-dismiss"
const val SCHEDULING_WRITE_REJECTION_TAG: String = "district-scheduling-write-rejection"
