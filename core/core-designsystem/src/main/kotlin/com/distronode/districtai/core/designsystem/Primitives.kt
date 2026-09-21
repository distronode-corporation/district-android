package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Panels, headers and the width container: the port of the web dashboard's UI primitives.
 *
 * ⛔ THE WEB FILE IS "the single source of truth for buttons, panels, badges and status
 * indicators", because without it every surface re-implements these inline and they drift.
 * These are the same components reading the same tokens, so the two clients drift together or
 * not at all.
 *
 * ⚠️ EVERY PRIMITIVE TAKES STRINGS, ENUMS AND LAMBDAS — never a DTO. The moment one accepts a
 * `Call` it stops being reusable and becomes a screen. That is also why this module depends on no
 * other project module.
 */

/**
 * Centres content and caps its width.
 *
 * ⛔ THE SINGLE BIGGEST LAYOUT DEFECT THIS FIXES. Every screen was full-bleed, and on the
 * 1920×1048 surface this app actually runs on that put each row's trailing metadata — the
 * "completed" status, the "Building dossier…" note — roughly 1700px from the name it belonged to,
 * with an empty grey desert between. On a portrait phone the bug is invisible, which is exactly
 * why it survived: nothing in a phone-shaped emulator shows it.
 *
 * ⚠️ 720dp, NOT the web's `max-w-[120rem]` (1920px). That figure works there because the web fills
 * the width with a 16-column grid and a sidebar; this app is a single column of list rows, and a
 * single column stops being readable long before 1920px. 720dp is wide enough for a two-line row
 * with a trailing badge and narrow enough that the eye can travel it.
 */
@Composable
fun ContentContainer(
    modifier: Modifier = Modifier,
    maxWidth: Dp = CONTENT_MAX_WIDTH,
    /**
     * Inset applied INSIDE the width cap.
     *
     * ⚠️ Here rather than on [modifier] on purpose. Padding on the modifier is applied OUTSIDE the
     * `widthIn`, so it would shrink the centred column instead of insetting its contents — the
     * content would end up narrower than [maxWidth] and still hard against the text edge.
     */
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /**
     * ⚠️ Exposed so a screen does not have to nest its own `Column` just to get row spacing. That
     * nesting is what it looks like when a container is missing a parameter, and it costs a layout
     * node on every row of every list.
     */
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * The small uppercase mono label. The most recognisable typographic element of the brand.
 *
 * ⚠️ UPPERCASES ITS INPUT, because CSS `text-transform: uppercase` has no `TextStyle` equivalent in
 * Compose. Callers pass sentence-case text — which keeps the string resources readable and
 * translatable — and get the brand treatment.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DistrictTheme.colors.mutedForeground,
) {
    Text(
        text = text.uppercase(),
        style = DistrictTheme.text.eyebrow,
        color = color,
        modifier = modifier,
    )
}

/**
 * A panel. 12dp radius, one calm 1px border, no shadow.
 *
 * ⛔ A BORDER RATHER THAN AN ELEVATION SHADOW, DELIBERATELY. The web's own note is that the system
 * uses "calm borders, single indigo accent, no neon glow"; on a near-black background a Material
 * shadow is invisible anyway, so elevation would communicate nothing while costing a layer.
 */
@Composable
fun DistrictCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DistrictTheme.colors
    val shape = RoundedCornerShape(CARD_RADIUS)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.card, shape)
            .border(1.dp, colors.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(DistrictTheme.spacing.card),
        content = content,
    )
}

/**
 * The overview's headline numbers: an eyebrow label, a large value, a quiet caption.
 *
 * ⚠️ Replaces four hand-rolled boxes whose label was bold small text — which read as a heading
 * competing with the number rather than annotating it. The eyebrow demotes the label so the value
 * is unambiguously the subject of the card.
 */
@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    DistrictCard(modifier = modifier) {
        Eyebrow(label)
        Text(
            text = value,
            style = DistrictTheme.text.metric,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
            )
        }
    }
}

/**
 * The standard surface title block: sentence-case title, optional indigo accent word, muted
 * subtitle, actions slot, and a bottom rule.
 *
 * ⚠️ The accent word is ONE `AnnotatedString`, not two `Text`s in a `Row`. Two would not wrap as a
 * single sentence, so a long title would break mid-phrase and strand the accent on its own line.
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    accent: String? = null,
    subtitle: String? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = DistrictTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildAnnotatedString {
                        append(title)
                        if (accent != null) {
                            append(" ")
                            withStyle(SpanStyle(color = colors.district)) { append(accent) }
                        }
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.foreground,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.mutedForeground,
                        modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
                    )
                }
            }
            if (actions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
                    content = actions,
                )
            }
        }
        HorizontalDivider(
            color = colors.border,
            modifier = Modifier.padding(top = DistrictTheme.spacing.section),
        )
    }
}

private val CONTENT_MAX_WIDTH = 720.dp
private val CARD_RADIUS = 12.dp
