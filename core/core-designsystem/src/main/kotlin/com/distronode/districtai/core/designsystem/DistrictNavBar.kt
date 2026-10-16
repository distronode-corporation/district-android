package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * longer greps; it is quoted here as history, not as a live string), and a Contacts button BELOW
 * THE FOLD at the very bottom. So two of the app's three main surfaces were invisible until you
 * scrolled a screen you had no reason to scroll, and once you were on one there was no way to cross
 * to the other without going back first.
 *
 * ⛔ AN ICON AND A SHORT LABEL PER DESTINATION, 80dp TALL, as Material's navigation bar. Until 3.0.1
 * the bar was text only with 44dp items, and at 200% font its labels broke mid-word ("Overv / iew",
 * « Récep / tion ») because a word had nowhere to go (visual QA A-S2, A-S6). The icon carries the
 * destination when the label has to shrink, the item is a 64dp target, and the label keeps whole
 * words on at most two lines (see [WholeWordAutoSize]).
 *
 * ⚠️ A ROW ON `--surface` WITH ONE HAIRLINE RULE, not Material's `NavigationBar`. That component
 * brings its own window insets, an elevation tint and a fixed label style, all of which would need
 * overriding back to this, the same reason [DistrictTopBar] is not a `TopAppBar`. The metrics (80dp,
 * a 64x32dp indicator pill behind a 24dp icon) are Material's.
 *
 * ⚠️ Width-capped like everything else, so on a 1920px surface the items sit under the content they
 * navigate to rather than spreading to the screen edges.
 */
@Composable
fun DistrictNavBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    DistrictNavBar(modifier = modifier, labelSizes = remember { NavLabelSizes() }, content = content)
}

/** The bar with the shared label size held by the caller, so a test can read what each label settled on. */
@Composable
internal fun DistrictNavBar(
    modifier: Modifier,
    labelSizes: NavLabelSizes,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = DistrictTheme.colors
    CompositionLocalProvider(LocalNavLabelSizes provides labelSizes) {
        Column(modifier = modifier.fillMaxWidth().background(colors.surface)) {
            HorizontalDivider(color = colors.border)
            ContentContainer(
                contentPadding = PaddingValues(
                    horizontal = DistrictTheme.spacing.tight,
                    vertical = DistrictTheme.spacing.tight,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = ITEM_MIN_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                    verticalAlignment = Alignment.Top,
                    content = content,
                )
            }
        }
    }
}

/**
 * ⛔ ONE LABEL SIZE FOR THE WHOLE BAR. Each label used to settle on its own size, so at 200% font the
 * bar mixed sizes (« Réception » and « Contacts » small beside a large « Aperçu »). Every item registers
 * its label here, and every label's fit is tried against ALL the bar's labels at each size, so each
 * settles on the same size: the largest at which every label fits. The items share one width (each has
 * weight 1), which is what makes one label's constraints valid for another.
 */
@Stable
internal class NavLabelSizes {
    /** The bar's labels, in the order their items joined. */
    val labels = mutableStateListOf<String>()

    private val settled = mutableMapOf<String, TextUnit>()

    fun join(label: String) {
        labels.add(label)
    }

    fun leave(label: String) {
        labels.remove(label)
        settled.remove(label)
    }

    /** Records the size a label's layout settled on; read by tests. */
    fun settle(label: String, size: TextUnit) {
        settled[label] = size
    }

    fun settled(): Map<String, TextUnit> = settled.toMap()
}

/** ⚠️ No default: a [DistrictNavItem] only exists inside a [DistrictNavBar], which provides one. */
private val LocalNavLabelSizes = staticCompositionLocalOf<NavLabelSizes> {
    error("DistrictNavItem outside a DistrictNavBar")
}

/**
 * One destination in the [DistrictNavBar].
 *
 * ⛔ A LABEL IS NEVER SPLIT MID-WORD. Five items share a phone's width, so a label gets about 70dp.
 * English "Overview" once drew "Overvie / w", and French "Vue d'ensemble" and "Boîte de réception"
 * split twice each (W4 French QA); at 200% font every label did (3.0.1 visual QA). The label wraps at
 * its spaces onto at most two lines and steps its size down, no lower than [LABEL_FLOOR] (a size in
 * dp, so the floor holds at any font scale), until its longest word fits; see [WholeWordAutoSize].
 *
 * ⚠️ `selected` IS SET IN SEMANTICS (a `selectable` with the tab role), not just painted. A screen
 * reader has to be able to say which destination is current; colour alone communicates nothing to
 * it, and colour alone is also the one cue a colour-blind user may not get, which is why the active
 * item also fills the pill behind its icon.
 */
@Composable
fun RowScope.DistrictNavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val colors = DistrictTheme.colors
    val ink = if (selected) colors.district else colors.mutedForeground
    Column(
        modifier = modifier
            .weight(1f)
            .heightIn(min = ITEM_MIN_HEIGHT)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .then(if (description != null) Modifier.testTag(description) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = INDICATOR_WIDTH, height = INDICATOR_HEIGHT)
                .background(
                    // The web's `bg-district/12` active fill; transparent when inactive.
                    color = if (selected) colors.district.copy(alpha = ACCENT_CONTAINER_ALPHA) else Color.Transparent,
                    shape = RoundedCornerShape(INDICATOR_HEIGHT / 2),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = ink)
        }
        val style = MaterialTheme.typography.labelMedium
        val floor = with(LocalDensity.current) { LABEL_FLOOR.toSp() }
        val sizes = LocalNavLabelSizes.current
        DisposableEffect(sizes, label) {
            sizes.join(label)
            onDispose { sizes.leave(label) }
        }
        // The bar's other labels, which this one must fit beside at the same size.
        val others = sizes.labels.filter { it != label }
        val measurer = rememberTextMeasurer()
        val together = remember(others, measurer, style) {
            others.takeIf { it.isNotEmpty() }?.let { Together(it, measurer, style, LABEL_MAX_LINES) }
        }
        // ⚠️ KEYED ON THE OTHER LABELS: a Text whose `autoSize` changes keeps its settled size (Compose
        // does not measure it again), so a new set of labels gets a new Text.
        key(others) {
            Text(
                text = label,
                style = style,
                autoSize = remember(style.fontSize, floor, together) {
                    WholeWordAutoSize(max = style.fontSize, floor = floor, together = together)
                },
                onTextLayout = { sizes.settle(label, it.layoutInput.style.fontSize) },
                // ⚠️ `mutedForeground` when inactive, not `foreground`. Five equally-loud labels give the
                // eye nothing to land on, and the active one stops reading as active.
                color = ink,
                textAlign = TextAlign.Center,
                maxLines = LABEL_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
            )
        }
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
internal class WholeWordAutoSize(
    private val max: TextUnit,
    private val floor: TextUnit,
    private val together: Together? = null,
) : TextAutoSize {

    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        var size = max
        while (size.value - 1f >= floor.value &&
            (performLayout(constraints, text, size).doesNotFit() || together?.anyDoesNotFit(constraints, size) == true)
        ) {
            size = (size.value - 1f).sp
        }
        return size
    }

    override fun equals(other: Any?): Boolean =
        other is WholeWordAutoSize && other.max == max && other.floor == floor && other.together == together

    override fun hashCode(): Int = (31 * max.hashCode() + floor.hashCode()) * 31 + together.hashCode()
}

/**
 * The other labels a size must also fit, measured with the label's own style and line limit.
 *
 * ⚠️ A TEXT MEASURER, NOT `performLayout`: the auto-size scope lays out its own node's text whatever
 * text it is handed, so the other labels have to be measured apart.
 */
internal data class Together(
    val labels: List<String>,
    val measurer: TextMeasurer,
    val style: TextStyle,
    val maxLines: Int,
) {
    fun anyDoesNotFit(constraints: Constraints, size: TextUnit): Boolean = labels.any {
        measurer.measure(
            text = it,
            style = style.copy(fontSize = size),
            maxLines = maxLines,
            constraints = constraints,
        ).doesNotFit()
    }
}

/** Whether this layout splits a word, or has more lines than its `maxLines` let it draw. */
internal fun TextLayoutResult.doesNotFit(): Boolean = splitsAWord() || hasVisualOverflow

/** Whether any line of this layout ends between two letters or digits of the same word. */
internal fun TextLayoutResult.splitsAWord(): Boolean {
    val text = layoutInput.text.text
    return (0 until lineCount - 1).any { line ->
        val end = getLineEnd(line)
        end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
    }
}

private val ITEM_MIN_HEIGHT = 64.dp
private val INDICATOR_WIDTH = 64.dp
private val INDICATOR_HEIGHT = 32.dp
private const val LABEL_MAX_LINES = 2

/** The smallest a label gets, in dp so a 200% font scale cannot push it past the item. */
private val LABEL_FLOOR = 10.dp
