package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The semantic tones. Mirrors the web's `BadgeTone`.
 *
 * ⚠️ `District` IS THE ACCENT TONE, NOT A SUCCESS TONE. The web keeps them distinct because a
 * workspace being SELECTED and an operation having SUCCEEDED are different facts that happen to
 * both be positive. Using District for success would make "active" and "worked" indistinguishable
 * at a glance, which is the whole thing a tone is for.
 */
enum class Tone { Neutral, District, Success, Warning, Danger, Info }

/**
 * The 12%-alpha container fill — the web's `bg-district/12`, `bg-success/12` and friends.
 *
 * ⚠️ [Tone.Neutral] is the exception and uses the SOLID `muted` token rather than a tint. On the
 * web that is `bg-muted`, deliberately: a 12% tint of a grey on a near-black background is
 * invisible, so the neutral badge would lose its shape entirely.
 *
 * ⚠️ TAKES THE PALETTE RATHER THAN READING IT, and is not `@Composable`. A composable `when` wraps
 * each arm in a group, which leaves the compiler's no-match `throw` looking like a reachable branch;
 * a plain function keeps it the ordinary exhaustive-enum `when` it is.
 */
internal fun Tone.fill(colors: DistrictColors): Color = when (this) {
    Tone.Neutral -> colors.muted
    Tone.District -> colors.district.copy(alpha = ACCENT_CONTAINER_ALPHA)
    Tone.Success -> colors.success.copy(alpha = ACCENT_CONTAINER_ALPHA)
    Tone.Warning -> colors.warning.copy(alpha = ACCENT_CONTAINER_ALPHA)
    Tone.Danger -> colors.destructive.copy(alpha = ACCENT_CONTAINER_ALPHA)
    Tone.Info -> colors.info.copy(alpha = ACCENT_CONTAINER_ALPHA)
}

/** The solid ink drawn on [fill]. Takes the palette for the same reason. */
internal fun Tone.ink(colors: DistrictColors): Color = when (this) {
    Tone.Neutral -> colors.mutedForeground
    Tone.District -> colors.district
    Tone.Success -> colors.success
    Tone.Warning -> colors.warning
    Tone.Danger -> colors.destructive
    Tone.Info -> colors.info
}

/**
 * A status pill: 12%-alpha fill, solid ink, 6dp radius.
 *
 * ⛔ THIS IS WHAT "completed" SHOULD HAVE BEEN. The call log rendered every status as plain grey
 * body text pinned to the far right of a full-bleed row, so a failed transfer and a completed call
 * were typographically identical — the only difference was reading the word. A tinted pill makes
 * state scannable without reading, which is the entire job of a call log.
 */
@Composable
fun DistrictBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = tone.ink(DistrictTheme.colors),
        modifier = modifier
            .background(tone.fill(DistrictTheme.colors), RoundedCornerShape(BADGE_RADIUS))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A 6dp dot, for presence and health. */
@Composable
fun StatusDot(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
) {
    Box(modifier = modifier.size(DOT_SIZE).background(tone.ink(DistrictTheme.colors), CircleShape))
}

private val BADGE_RADIUS = 6.dp
private val DOT_SIZE = 6.dp
