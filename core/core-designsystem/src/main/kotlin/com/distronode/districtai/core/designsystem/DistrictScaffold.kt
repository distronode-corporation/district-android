package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The app bar this app did not have.
 *
 * ⛔ THERE WAS NO NAVIGATION CHROME OF ANY KIND, ON ANY SCREEN. The call log and the contacts
 * list began at the bare top edge, under the status bar, with no title and no back affordance,
 * so a user two levels deep had only the system back gesture and nothing telling them where they
 * were. That is not a styling gap; it is a missing structural element, and it is the reason the
 * screens read as fragments rather than as an app.
 *
 * ⛔ ONE LINE OF TITLE, AND ICONS FOR BACK AND THE ACTIONS. The 3.0.1 visual QA (A-S6, AB1, AC1) found
 * the word "Back" and text actions ("Desk settings", "New message") squeezing the title at 200% font
 * until a French title ran one letter per line down the whole screen and pushed the content, an error
 * message and its retry off it. The back control is now the arrow (its label is its content
 * description), every action is a 48dp [DistrictTopBarAction], and the title ellipsizes on one line,
 * so the bar cannot grow past the one line Material's top app bar has.
 *
 * ⚠️ NOT Material's `TopAppBar`. That component brings its own 64dp metrics, title style and
 * scroll-behaviour surface colours, all of which would have to be overridden back to the web's,
 * which is a 56dp bar on `--surface` with a single hairline rule beneath. Composing it from a
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
                    .padding(horizontal = DistrictTheme.spacing.hairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    DistrictTopBarAction(
                        icon = DistrictIcons.Back,
                        label = DistrictTheme.labels.back,
                        onClick = onBack,
                        modifier = Modifier.testTag(TOP_BAR_BACK_DESCRIPTION),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = DistrictTheme.spacing.row),
                )
                if (actions != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
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
 * One icon action in a [DistrictTopBar] (and the bar's back arrow).
 *
 * ⚠️ [label] IS THE CONTENT DESCRIPTION, so a screen reader still says "Desk settings" where the
 * word used to be drawn, and a long press shows it as a tooltip for a sighted user who does not
 * know the glyph. Material's [IconButton] gives the 48dp target and the ripple.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistrictTopBarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(imageVector = icon, contentDescription = label, tint = DistrictTheme.colors.foreground)
        }
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
