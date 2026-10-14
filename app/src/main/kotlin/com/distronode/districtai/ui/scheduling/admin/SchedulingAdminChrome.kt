package com.distronode.districtai.ui.scheduling.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.InlineFailure

/**
 * The frame every scheduling section draws in: a top bar with Back and Refresh, and one scrolling
 * column of cards.
 *
 * ⚠️ REFRESH IS A BUTTON, NOT A PULL GESTURE, which is how the booking-page screen before it and the
 * rest of this app re-read; district-ios's pull-to-refresh has no counterpart here.
 */
@Composable
fun SchedulingSectionScaffold(
    title: String,
    rootTag: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DistrictScaffold(
        modifier = Modifier.testTag(rootTag),
        topBar = {
            DistrictTopBar(
                title = title,
                onBack = onBack,
                actions = {
                    DistrictButton(
                        text = stringResource(R.string.scheduling_admin_common_refresh),
                        onClick = onRefresh,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                        modifier = Modifier.testTag(SCHEDULING_ADMIN_REFRESH_TAG),
                    )
                },
            )
        },
    ) { inset ->
        ContentContainer(modifier = inset.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.padding(DistrictTheme.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.section),
                content = content,
            )
        }
    }
}

/** A card with an optional eyebrow. */
@Composable
fun SchedulingCard(
    eyebrow: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DistrictCard(modifier = modifier) {
        eyebrow?.let { Eyebrow(it, modifier = Modifier.padding(bottom = DistrictTheme.spacing.tight)) }
        Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight), content = content)
    }
}

/** A label over its value, as a read-only fact. */
@Composable
fun SchedulingReadOnlyRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = DistrictTheme.colors.mutedForeground,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
        )
    }
}

/** A section's first read, on its way. */
@Composable
fun SchedulingSectionLoading(tag: String, rows: Int = LOADING_ROWS) {
    Column(
        modifier = Modifier.testTag(tag),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        repeat(rows) { SkeletonBlock(height = DistrictTheme.spacing.header) }
    }
}

/**
 * A read that failed, with Retry where retrying could help.
 *
 * ⛔ A FAILURE, NEVER AN EMPTY LIST: "this tenancy has no bookings" and "the scheduler is down" must
 * not look alike.
 */
@Composable
fun SchedulingSectionFailure(failure: FailureText, onRetry: () -> Unit, tag: String) {
    InlineFailure(title = null, failure = failure, onRetry = onRetry, description = tag)
}

/** A section whose read worked and found nothing. */
@Composable
fun SchedulingEmptyState(title: String, body: String, tag: String) {
    DistrictCard(modifier = Modifier.testTag(tag)) {
        EmptyState(title = title, body = body)
    }
}

/** How a status label is coloured, after district-core-swift's `SchedulingStatusKind`. */
enum class SchedulingStatusKind(val tone: Tone) {
    SUCCESS(Tone.Success),
    ERROR(Tone.Danger),
    INFO(Tone.Info),
    STOPPED(Tone.Neutral),
}

/** A status as a badge. */
@Composable
fun SchedulingStatusBadge(label: String, kind: SchedulingStatusKind) {
    DistrictBadge(text = label, tone = kind.tone)
}

/** A muted line under a field or a list. */
@Composable
fun SchedulingHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val LOADING_ROWS = 3

/** The Refresh button every section's top bar carries. */
const val SCHEDULING_ADMIN_REFRESH_TAG: String = "district-scheduling-admin-refresh"

/**
 * A section this build has not drawn yet: its frame and nothing else.
 *
 * ⚠️ A STACKING SEAM, NOT A FEATURE. The sections land one pull request at a time and each replaces
 * its use of this; it exists so the routes compile in between.
 */
@Composable
fun SchedulingPendingSection(title: String, onBack: () -> Unit) {
    SchedulingSectionScaffold(
        title = title,
        rootTag = SCHEDULING_PENDING_SECTION_TAG,
        onBack = onBack,
        onRefresh = {},
    ) {}
}

const val SCHEDULING_PENDING_SECTION_TAG: String = "district-scheduling-pending-section"
