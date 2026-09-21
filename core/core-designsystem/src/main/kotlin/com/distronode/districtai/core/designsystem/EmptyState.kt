package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What a screen shows when it has nothing, and it is NOT an error.
 *
 * ⛔ THERE WAS NO SUCH COMPONENT, WHICH IS WHY AN EMPTY SCREEN READ AS A BROKEN ONE. An empty list
 * rendered as a blank expanse, indistinguishable from a failed load — and a user cannot tell "you
 * have no contacts yet" from "this did not work" by looking at nothing. Every list in this app
 * could reach that state on day one of a new workspace, which is the first thing a new customer
 * sees.
 *
 * ⚠️ Takes an optional action so the empty state can be WHERE the first item is created, rather
 * than a dead end that describes a button somewhere else on the screen.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(DistrictTheme.spacing.header),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = DistrictTheme.colors.foreground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.mutedForeground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        if (action != null) {
            Box(modifier = Modifier.padding(top = DistrictTheme.spacing.section)) { action() }
        }
    }
}

/**
 * A placeholder block for content that is still loading.
 *
 * ⚠️ STATIC, NOT SHIMMERING, ON PURPOSE. An infinite animation is the one thing on screen that
 * never settles; it also defeats screenshot tests and would ignore the reduced-motion preference
 * that the web honours with a full CSS kill-switch. A calm block says "shape known, content
 * pending" without any of that.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .background(DistrictTheme.colors.muted, RoundedCornerShape(SKELETON_RADIUS)),
    )
}

private val SKELETON_RADIUS = 6.dp
