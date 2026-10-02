package com.distronode.districtai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow

/**
 * How a failed read is drawn: [FailureState] for a whole screen, [InlineFailure] for one card area
 * of a screen whose other parts may have loaded, and [PagedAppendFailure] for a list's footer.
 *
 * ⛔ ONE COPY, BECAUSE THE COPIES HAD ALREADY DRIFTED ON WHAT A FAILURE OFFERS. This was written out
 * by hand in about twenty screens, and only the call log and contacts list offered "sign in again"
 * for a dead session or named the regions that could not be reached. Every other screen showed the
 * signed-out sentence with no button at all, because a signed-out failure is not retryable. The
 * wording comes from [FailureText]; what the user can DO about it comes from here.
 *
 * ⚠️ `onSignIn` IS NULLABLE ON PURPOSE. A screen that has no sign-in callback wired passes null
 * rather than inventing navigation, and then a signed-out failure offers nothing, exactly as before.
 * Each screen still passes its own `description` so its semantics tests keep their handle.
 */

/**
 * The centred, width-capped column every full-screen non-list state sits in.
 *
 * ⚠️ [modifier] is for the scaffold's inset; the [description] handle goes on the inner column so
 * it covers exactly what is drawn.
 */
@Composable
fun CenteredState(
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ContentContainer(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DistrictTheme.spacing.section)
                .described(description),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * A whole screen that could not load.
 *
 * @param onSignIn null when this screen has no sign-in route wired; see the file note.
 * @param title an eyebrow above the message, for a screen whose failure would otherwise not say
 *   what failed.
 * @param retryDescription a test handle for the retry button, for screens whose tests tap it by
 *   handle rather than by its text.
 */
@Composable
fun FailureState(
    failure: FailureText,
    onRetry: () -> Unit,
    onSignIn: (() -> Unit)?,
    description: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    retryDescription: String? = null,
) {
    CenteredState(description = description, modifier = modifier) {
        title?.let { Eyebrow(text = it, modifier = Modifier.padding(bottom = DistrictTheme.spacing.tight)) }
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            textAlign = TextAlign.Center,
        )
        DegradedRegions(failure, TextAlign.Center)
        FailureAction(
            failure = failure,
            onRetry = onRetry,
            onSignIn = onSignIn,
            retryDescription = retryDescription,
            compact = false,
            modifier = Modifier.padding(top = DistrictTheme.spacing.section),
        )
    }
}

/**
 * One card area's own failure.
 *
 * ⚠️ A CARD, NOT A WHOLE-SCREEN STATE. The rest of the screen may have loaded fine, and replacing
 * everything with one message would discard a correct answer already on screen.
 *
 * @param title the eyebrow naming what failed, or null where the surrounding UI already says it.
 * @param modifier placement only (a caller's horizontal gutter, say); the handle is [description].
 */
@Composable
fun InlineFailure(
    title: String?,
    failure: FailureText,
    onRetry: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    onSignIn: (() -> Unit)? = null,
    retryDescription: String? = null,
) {
    DistrictCard(modifier = modifier.described(description)) {
        title?.let { Eyebrow(it) }
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        DegradedRegions(failure, TextAlign.Start)
        FailureAction(
            failure = failure,
            onRetry = onRetry,
            onSignIn = onSignIn,
            retryDescription = retryDescription,
            compact = true,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
    }
}

/**
 * A paged list's footer when the NEXT page failed.
 *
 * ⛔ A FOOTER, NEVER A SCREEN. The rows above it loaded and are still correct; a failed extra page
 * must not replace them with a full-screen error.
 */
@Composable
fun PagedAppendFailure(
    failure: FailureText,
    onRetry: () -> Unit,
    onSignIn: (() -> Unit)?,
    description: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(DistrictTheme.spacing.gutter)
            .described(description),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
        DegradedRegions(failure, TextAlign.Center)
        FailureAction(
            failure = failure,
            onRetry = onRetry,
            onSignIn = onSignIn,
            retryDescription = null,
            compact = false,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
    }
}

/**
 * ⛔ NAMES THE REGIONS, because "we could not reach eu" is an answer and a generic failure is not.
 * Drawn only for a [FailureText] that carries them.
 */
@Composable
private fun DegradedRegions(failure: FailureText, align: TextAlign) {
    if (failure.degradedRegions.isEmpty()) return
    Text(
        text = stringResource(R.string.overview_degraded_regions, failure.degradedRegions.joinToString(", ")),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        textAlign = align,
        modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
    )
}

@Composable
private fun FailureAction(
    failure: FailureText,
    onRetry: () -> Unit,
    onSignIn: (() -> Unit)?,
    retryDescription: String?,
    compact: Boolean,
    modifier: Modifier,
) {
    when {
        // A dead session cannot be retried; the only way forward is signing in.
        failure.signedOutCause != null && onSignIn != null -> FailureButton(
            text = stringResource(R.string.overview_sign_in_again),
            onClick = onSignIn,
            compact = compact,
            modifier = modifier,
        )
        // ⚠️ Only offered when retrying could actually work. A contract mismatch or a role refusal
        // produces the identical failure on every attempt, and a button that cannot succeed is
        // worse than no button.
        failure.retryable -> FailureButton(
            text = stringResource(R.string.overview_retry),
            onClick = onRetry,
            compact = compact,
            modifier = if (retryDescription == null) modifier else modifier.described(retryDescription),
        )
        else -> Unit
    }
}

/**
 * The test handle, as a plain extension rather than an inline `semantics { }` in each composable.
 *
 * ⚠️ NOT A COMPOSABLE, ON PURPOSE. Inside a composable, a lambda that captures a parameter is
 * memoised against that parameter, which emits change-tracking branches no caller exercises (a
 * handle is a constant). Building the modifier here allocates the same lambda without them.
 */
private fun Modifier.described(description: String): Modifier = semantics { contentDescription = description }

/** ⚠️ Compact is the card family's quiet ghost button; the full-screen family keeps the primary. */
@Composable
private fun FailureButton(text: String, onClick: () -> Unit, compact: Boolean, modifier: Modifier) {
    DistrictButton(
        text = text,
        onClick = onClick,
        variant = if (compact) ButtonVariant.Ghost else ButtonVariant.Primary,
        size = if (compact) ButtonSize.Sm else ButtonSize.Md,
        modifier = modifier,
    )
}
