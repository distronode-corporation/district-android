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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * ⛔ A LABEL IS NEVER SPLIT MID-WORD. Five items share a phone's width, so a label gets about 60dp,
 * and at `labelLarge` English "Overview" did not fit: it was drawn "Overvie / w", and French "Vue
 * d'ensemble" and "Boîte de réception" split twice each (W4 French QA). The label wraps at its
 * spaces and steps its size down, no lower than `labelSmall`, until its longest word fits on one
 * line; see [WholeWordAutoSize].
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
            // ⚠️ `hairline`, not `row`: at 12dp a side the label kept barely 52dp of a 76dp item. The
            // active fill is drawn before this padding, so it still spans the whole item.
            .padding(horizontal = DistrictTheme.spacing.hairline, vertical = DistrictTheme.spacing.tight)
            .semantics {
                this.selected = selected
            }
            .then(if (description != null) Modifier.testTag(description) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val floor = MaterialTheme.typography.labelSmall.fontSize
        val style = MaterialTheme.typography.labelLarge
        Text(
            text = label,
            style = style,
            autoSize = remember(style.fontSize, floor) { WholeWordAutoSize(max = style.fontSize, floor = floor) },
            // ⚠️ `mutedForeground` when inactive, not `foreground`. Four equally-loud labels give the
            // eye nothing to land on, and the active one stops reading as active.
            color = if (selected) colors.district else colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The largest size from [max] down to [floor], a point at a time, at which no line break falls
 * inside a word.
 *
 * ⚠️ WORDS, NOT THE WHOLE LABEL. A two-word label may wrap at its space, which keeps it large; what
 * must not happen is one word wider than the item, because the line breaker then splits it. Below
 * the floor the label is left to wrap, which is still better than unreadable text.
 *
 * ⚠️ AN AUTO-SIZE, NOT A MEASUREMENT IN COMPOSITION. It runs inside the text's own layout, so the bar
 * needs no `BoxWithConstraints`. A version that measured inside one left five of the app's
 * navigation-graph tests waiting out their timeouts on paged lists.
 */
internal class WholeWordAutoSize(private val max: TextUnit, private val floor: TextUnit) : TextAutoSize {

    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        var size = max
        while (size.value - 1f >= floor.value && performLayout(constraints, text, size).splitsAWord()) {
            size = (size.value - 1f).sp
        }
        return size
    }

    override fun equals(other: Any?): Boolean =
        other is WholeWordAutoSize && other.max == max && other.floor == floor

    override fun hashCode(): Int = 31 * max.hashCode() + floor.hashCode()
}

/** Whether any line of this layout ends between two letters or digits of the same word. */
internal fun TextLayoutResult.splitsAWord(): Boolean {
    val text = layoutInput.text.text
    return (0 until lineCount - 1).any { line ->
        val end = getLineEnd(line)
        end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
    }
}

private val BAR_MIN_HEIGHT = 48.dp
private val ITEM_MIN_HEIGHT = 44.dp
private val ITEM_RADIUS = 8.dp
