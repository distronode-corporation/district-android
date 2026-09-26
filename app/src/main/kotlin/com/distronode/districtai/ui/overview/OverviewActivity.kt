package com.distronode.districtai.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.data.RecentActivity
import com.distronode.districtai.core.designsystem.Avatar
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.ui.toneForCallStatus

/**
 * One recent-activity row.
 *
 * ⚠️ WHAT CHANGED. This was a bare `Row` of two `Text`s with the status as plain grey text pushed
 * to the far edge by `SpaceBetween`, plus up to two `AssistChip`s. Inside the screen's
 * `ContentContainer` the row is now capped at 720dp, so the trailing badge sits beside the name
 * instead of ~1700px away from it — the `AssistChip`s were also interactive-looking controls with
 * an empty `onClick`, which invited a tap that did nothing.
 */
@Composable
internal fun ActivityRow(call: RecentActivity) {
    // ⚠️ `live` is derived from the DISPLAY status, which the server has already downgraded for a
    // stale call, so this cannot label a day-old row "Live".
    val statusLabel = if (call.live) stringResource(R.string.overview_status_live) else call.status
    val statusTone = if (call.live) Tone.District else toneForCallStatus(call.status)

    DistrictListRow(
        // ⚠️ The API sends the literal "Unknown" for an unresolved caller; the repository maps
        // that to null so it is never printed as if it were a name.
        title = call.displayName ?: stringResource(R.string.overview_no_caller_id),
        subtitle = stringResource(
            if (call.outbound) {
                R.string.overview_direction_outbound
            } else {
                R.string.overview_direction_inbound
            },
        ) + " · " + call.time,
        leading = {
            Avatar(
                name = call.displayName ?: "",
                // ⚠️ A caller with no id gets the neutral tone, so an anonymous row does not wear
                // the brand accent as if it were a known contact.
                tone = if (call.displayName == null) Tone.Neutral else Tone.District,
            )
        },
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ⛔ BOTH TRANSFER OUTCOMES, AND THE FAILURE IS DANGER-TONED. A transfer that
                // failed is the one thing on this screen a human has to act on, and it used to be
                // rendered in the same neutral chip as a successful one.
                if (call.transferred) {
                    DistrictBadge(
                        text = stringResource(R.string.overview_badge_transferred),
                        tone = Tone.Info,
                    )
                }
                if (call.transferFailed) {
                    DistrictBadge(
                        text = stringResource(R.string.overview_badge_transfer_failed),
                        tone = Tone.Danger,
                    )
                }
                DistrictBadge(text = statusLabel, tone = statusTone)
            }
        },
    )
}
