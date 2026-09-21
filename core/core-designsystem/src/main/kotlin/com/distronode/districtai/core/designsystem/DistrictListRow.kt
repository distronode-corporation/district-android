package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One tappable row: optional leading slot, title, optional subtitle, optional trailing slot.
 *
 * ⛔ THE TRAILING SLOT SITS NEXT TO THE TEXT, NOT AT THE SCREEN EDGE, AND THAT ONLY WORKS INSIDE
 * A [ContentContainer]. The old rows were full-bleed, so on the 1920px surface this app actually
 * runs on, each row's status ended up roughly 1700px from the name it described with an empty grey
 * desert between them. Capping the width and giving the row a trailing slot are two halves of one
 * fix; neither works alone.
 *
 * ⚠️ 56dp minimum, not Material's 48dp touch-target floor. Two lines of text need more than 48dp
 * before they start to feel wedged in, and every row here has a subtitle.
 */
@Composable
fun DistrictListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = DistrictTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(
                horizontal = DistrictTheme.spacing.gutter,
                vertical = DistrictTheme.spacing.row,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        if (leading != null) {
            leading()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.foreground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.mutedForeground,
                    modifier = Modifier.padding(top = SUBTITLE_GAP),
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * The list separator.
 *
 * ⚠️ Inset to the text column so it does not cut across the leading avatar — a full-bleed rule
 * under a circle reads as a strikethrough.
 */
@Composable
fun DistrictRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = DistrictTheme.colors.border,
        modifier = modifier.padding(start = DistrictTheme.spacing.gutter),
    )
}

/**
 * Initials in a tinted circle.
 *
 * ⚠️ EXISTS TO GIVE A LIST ROW A LEFT ANCHOR. Rows of bare text separated by rules read as a
 * spreadsheet; a consistent leading element gives the eye a column to travel down. It is also the
 * cheapest possible avatar — no image loading, so no placeholder or error states, and nothing to
 * fake in a test.
 *
 * ⚠️ Falls back to a single glyph rather than rendering empty. An unnamed contact is common — the
 * voice agent writes "Unknown" for an unidentified caller — and an empty circle looks like a
 * loading failure rather than an absence of data.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.District,
) {
    val initials = name.trim()
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString(separator = "")
        .ifEmpty { "·" }

    Box(
        modifier = modifier.size(AVATAR_SIZE).background(tone.fill(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelMedium,
            color = tone.ink(),
        )
    }
}

private val ROW_MIN_HEIGHT = 56.dp
private val SUBTITLE_GAP = 2.dp
private val AVATAR_SIZE = 36.dp
