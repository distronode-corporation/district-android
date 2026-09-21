package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The app bar this app did not have.
 *
 * ⛔ THERE WAS NO NAVIGATION CHROME OF ANY KIND, ON ANY SCREEN. The call log and the contacts
 * list began at the bare top edge, under the status bar, with no title and no back affordance —
 * so a user two levels deep had only the system back gesture and nothing telling them where they
 * were. That is not a styling gap; it is a missing structural element, and it is the reason the
 * screens read as fragments rather than as an app.
 *
 * ⚠️ NOT Material's `TopAppBar`. That component brings its own 64dp metrics, title style and
 * scroll-behaviour surface colours, all of which would have to be overridden back to the web's
 * — which is a 56dp bar on `--surface` with a single hairline rule beneath. Composing it from a
 * Row is fewer moving parts than fighting the defaults.
 */
@Composable
fun DistrictTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = DistrictTheme.colors
    Box(modifier = modifier.fillMaxWidth().background(colors.surface)) {
        ContentContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = DistrictTheme.spacing.tight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
            ) {
                if (onBack != null) {
                    DistrictButton(
                        text = "Back",
                        onClick = onBack,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                        modifier = Modifier.semantics { contentDescription = TOP_BAR_BACK_DESCRIPTION },
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.foreground,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = DistrictTheme.spacing.tight),
                )
                if (actions != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                        content = actions,
                    )
                }
            }
        }
        HorizontalDivider(
            color = colors.border,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/**
 * The page shell: background, optional app bar, and a width-capped content column.
 *
 * ⚠️ Uses Material's [Scaffold] for its window-inset and snackbar plumbing, then paints its own
 * background — Scaffold's default `containerColor` is `MaterialTheme.colorScheme.background`,
 * which is already ours, but stating it means a Material default change cannot silently repaint
 * every screen.
 */
@Composable
fun DistrictScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DistrictTheme.colors.background,
        contentColor = DistrictTheme.colors.foreground,
        topBar = { if (topBar != null) topBar() },
    ) { insets ->
        content(Modifier.padding(insets))
    }
}

/** Stable handle for tests; a literal duplicated in a test drifts silently. */
const val TOP_BAR_BACK_DESCRIPTION: String = "district-top-bar-back"
