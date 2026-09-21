package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The persistent navigation bar.
 *
 * ⛔ THIS APP HAD NO MENU OF ANY KIND, AND THAT WAS THE LAST STRUCTURAL GAP. Seven screens existed
 * and the only way to reach a top-level one was an inline link on the overview: "Account" in the app
 * bar, "View all call logs" mid-page (that link is now labelled "Call Logs", so the old wording no
 * longer greps — it is quoted here as history, not as a live string), and a Contacts button BELOW
 * THE FOLD at the very bottom. So
 * two of the app's three main surfaces were invisible until you scrolled a screen you had no reason
 * to scroll, and once you were on one there was no way to cross to the other without going back
 * first. A user asking "where is everything?" was reading the app correctly.
 *
 * ⚠️ TEXT ONLY, NO ICONS, AND THAT IS ON-BRAND RATHER THAN A SHORTCUT. The web's own navigation —
 * both the marketing nav and the dashboard sidebar — is text links; the sidebar's active state is
 * `bg-district/12 text-district rounded-lg`, which is exactly what [DistrictNavItem] draws. It also
 * avoids pulling in `material-icons-extended` for four glyphs, and sidesteps inventing iconography
 * the design system does not have.
 *
 * ⚠️ A ROW ON `--surface` WITH ONE HAIRLINE RULE, not Material's `NavigationBar`. That component
 * brings 80dp metrics, its own indicator pill shape and an elevation tint, all of which would need
 * overriding back to this — the same reason [DistrictTopBar] is not a `TopAppBar`.
 *
 * ⚠️ Width-capped like everything else, so on a 1920px surface the items sit under the content they
 * navigate to rather than spreading to the screen edges.
 */
@Composable
fun DistrictNavBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = DistrictTheme.colors
    Column(modifier = modifier.fillMaxWidth().background(colors.surface)) {
        HorizontalDivider(color = colors.border)
        ContentContainer(
            contentPadding = PaddingValues(
                horizontal = DistrictTheme.spacing.tight,
                vertical = DistrictTheme.spacing.tight,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = BAR_MIN_HEIGHT),
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/**
 * One destination in the [DistrictNavBar].
 *
 * ⚠️ `selected` IS SET IN SEMANTICS, not just painted. A screen reader has to be able to say which
 * destination is current; colour alone communicates nothing to it, and colour alone is also the one
 * cue a colour-blind user may not get — which is why the active item changes its BACKGROUND as well
 * as its ink.
 */
@Composable
fun RowScope.DistrictNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val colors = DistrictTheme.colors
    Box(
        modifier = modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .background(
                // The web's `bg-district/12` active fill; transparent when inactive.
                color = if (selected) {
                    colors.district.copy(alpha = ACCENT_CONTAINER_ALPHA)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(ITEM_RADIUS),
            )
            .heightIn(min = ITEM_MIN_HEIGHT)
            .padding(horizontal = DistrictTheme.spacing.row, vertical = DistrictTheme.spacing.tight)
            .semantics {
                this.selected = selected
                if (description != null) contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            // ⚠️ `mutedForeground` when inactive, not `foreground`. Four equally-loud labels give the
            // eye nothing to land on, and the active one stops reading as active.
            color = if (selected) colors.district else colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
    }
}

private val BAR_MIN_HEIGHT = 48.dp
private val ITEM_MIN_HEIGHT = 44.dp
private val ITEM_RADIUS = 8.dp
