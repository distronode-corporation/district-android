package com.distronode.districtai.ui.scheduling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant

/*
 * The booking-page card itself: the badge, the sentence, the per-state detail and the action row.
 *
 * ⛔ A SEPARATE FILE FROM `SchedulingScreen.kt` FOR THE REASON `WorkflowCards.kt` IS ONE: detekt
 * caps a FILE at 11 functions and the rule fires AT that number, not above it. Keeping the whole
 * surface in one file put it at 12. The cut is along a real seam — this file is the CARD and knows
 * nothing about loading, failure or the notice, which are the screen's own three states.
 *
 * ⚠️ THE SPLIT WIDENS EXACTLY ONE DECLARATION, AND THAT IS THE COST OF IT. Kotlin's `private` is
 * FILE-scoped, so [SchedulingCard] has to be `internal` for `SchedulingScreen` to call it across
 * the new boundary; every helper below it stays `private` to this file, which is where the rest of
 * the encapsulation the old single file had still lives.
 */

@Composable
internal fun SchedulingCard(
    status: SchedulingStatusResponse,
    busy: Boolean,
    openingDashboard: Boolean,
    openingConsole: Boolean,
    consoleRetired: Boolean,
    onEnable: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenScheduler: () -> Unit,
    onRefresh: () -> Unit,
) {
    val presentation = SchedulingPresentation.from(status)
    DistrictCard(
        modifier = Modifier.semantics { contentDescription = SCHEDULING_CARD_DESCRIPTION },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow(stringResource(R.string.scheduling_eyebrow))
            PresentationBadge(presentation)
        }
        Text(
            text = presentationSentence(presentation),
            style = MaterialTheme.typography.bodySmall,
            // ⚠️ ONLY THE ONE STATE WHERE SOMETHING WENT WRONG IS RED. "Not admitted", "switched
            // off" and "state this app does not know" are ordinary answers, and painting them
            // destructive would report a fault that did not happen.
            color = if (presentation is SchedulingPresentation.FailedProvision) {
                DistrictTheme.colors.destructive
            } else {
                DistrictTheme.colors.mutedForeground
            },
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        PresentationDetail(presentation)

        // ⛔ ASKED BEFORE THE ROW IS BUILT, NOT INSIDE IT — see [offersAnyAction]. A Row that
        // renders nothing still takes its own spacing, so the states specified to offer NOTHING
        // would each carry a gap where a control used to be.
        if (presentation.offersAnyAction(status.eligible, status.canManage)) {
            SchedulingActions(
                presentation = presentation,
                status = status,
                busy = busy,
                openingDashboard = openingDashboard,
                openingConsole = openingConsole,
                consoleRetired = consoleRetired,
                onEnable = onEnable,
                onOpenDashboard = onOpenDashboard,
                onOpenScheduler = onOpenScheduler,
                onRefresh = onRefresh,
            )
        }
    }
}

/**
 * ⚠️ A BADGE ONLY WHERE THERE IS A TENANCY ROW, matching the web card: the pill reports the ROW's
 * status, and there is nothing to report without one.
 */
@Composable
private fun PresentationBadge(presentation: SchedulingPresentation) {
    val badge = when (presentation) {
        SchedulingPresentation.NotEligible, SchedulingPresentation.Legacy -> null
        is SchedulingPresentation.Provisioning -> R.string.scheduling_badge_setting_up to Tone.Info
        is SchedulingPresentation.Live -> R.string.scheduling_badge_live to Tone.Success
        is SchedulingPresentation.FailedProvision ->
            R.string.scheduling_badge_needs_attention to Tone.Danger
        is SchedulingPresentation.SwitchedOff ->
            R.string.scheduling_badge_switched_off to Tone.Neutral
        // ⚠️ NEUTRAL, NOT WARNING. A state this build has not heard of is a gap in the APP, not a
        // problem with the workspace's booking page, and an amber pill would say the second.
        is SchedulingPresentation.Unrecognised -> R.string.scheduling_badge_unknown to Tone.Neutral
    } ?: return

    DistrictBadge(
        text = stringResource(badge.first),
        tone = badge.second,
        modifier = Modifier.semantics { contentDescription = SCHEDULING_BADGE_DESCRIPTION },
    )
}

@Composable
private fun presentationSentence(presentation: SchedulingPresentation): String = stringResource(
    when (presentation) {
        SchedulingPresentation.NotEligible -> R.string.scheduling_not_eligible
        SchedulingPresentation.Legacy -> R.string.scheduling_legacy
        is SchedulingPresentation.Provisioning -> R.string.scheduling_provisioning
        is SchedulingPresentation.Live -> R.string.scheduling_live
        // ⚠️ The reason itself is drawn by [PresentationDetail] so it can carry the danger tone on
        // its own line.
        is SchedulingPresentation.FailedProvision -> R.string.scheduling_setup_failed
        is SchedulingPresentation.SwitchedOff -> R.string.scheduling_switched_off
        is SchedulingPresentation.Unrecognised -> R.string.scheduling_unrecognised
    },
)

@Composable
private fun PresentationDetail(presentation: SchedulingPresentation) {
    when (presentation) {
        is SchedulingPresentation.Live -> LiveDetail(presentation.tenant)
        is SchedulingPresentation.FailedProvision -> {
            // ⛔ SHOWN TO ANY MEMBER, BY DESIGN. Somebody who cannot see why a provision failed has
            // to open a ticket to learn it, and the server already classifies and truncates this
            // string so it can never carry a credential or a raw remote body.
            Text(
                text = presentation.tenant.lastError
                    ?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.scheduling_setup_failed_no_reason),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = SCHEDULING_REASON_DESCRIPTION },
            )
            HostLine(presentation.tenant.publicHost)
        }
        is SchedulingPresentation.Provisioning -> HostLine(presentation.tenant.publicHost)
        is SchedulingPresentation.SwitchedOff -> HostLine(presentation.tenant.publicHost)
        is SchedulingPresentation.Unrecognised -> HostLine(presentation.tenant.publicHost)
        SchedulingPresentation.NotEligible, SchedulingPresentation.Legacy -> Unit
    }
}

/**
 * The live booking page: the link, the public host, and when it was last observed provisioned.
 *
 * ⚠️ THE LINK IS SELECTABLE RATHER THAN HAVING A COPY BUTTON. `SelectionContainer` is long-press
 * copy with no new platform surface — the alternative reaches for the clipboard API, which nothing
 * else in this app touches and which has moved twice in Compose. This is the one string on the
 * screen a customer needs off the phone, so it has to be reachable; it does not have to be a
 * control.
 */
@Composable
private fun LiveDetail(tenant: SchedulingTenant) {
    Column(
        modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
    ) {
        val link = tenant.bookingUrl?.takeIf { it.isNotBlank() }
        if (link == null) {
            // ⛔ NEVER REBUILT FROM `publicHost`. The route derives this key server-side and sends
            // it only for a ready tenancy, so a client that assembled its own would publish a
            // booking link for a page that is not there. Use the key or offer no link.
            Text(
                text = stringResource(R.string.scheduling_live_without_link),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.semantics {
                    contentDescription = SCHEDULING_NO_LINK_DESCRIPTION
                },
            )
        } else {
            SelectionContainer {
                Text(
                    text = link,
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.foreground,
                    modifier = Modifier.semantics {
                        contentDescription = SCHEDULING_LINK_DESCRIPTION
                    },
                )
            }
        }
        HostLine(tenant.publicHost)
        // ⚠️ THE RAW ISO-8601 STRING, exactly as the devices screen shows `lastUsedAt`. This module
        // owns no date parsing (see the DTO), and inventing one here for a single caption would be
        // a second timestamp convention in the same app.
        tenant.lastReadyAt?.let {
            Text(
                text = stringResource(R.string.scheduling_last_checked, it),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun HostLine(host: String) {
    Text(
        text = stringResource(R.string.scheduling_host, host),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = Modifier
            .padding(top = DistrictTheme.spacing.hairline)
            .semantics { contentDescription = SCHEDULING_HOST_DESCRIPTION },
    )
}

/**
 * ⛔ THE ORDER OF THESE TWO BUTTONS IS THE POINT. "Manage on the web" comes first and carries the
 * primary variant; the scheduler console sits behind it as a secondary. The console is being
 * switched off region by region and a shipped Play build cannot be reverted, so an installed copy
 * has to lead with the hand-off that survives the flip.
 */
@Composable
private fun SchedulingActions(
    presentation: SchedulingPresentation,
    status: SchedulingStatusResponse,
    busy: Boolean,
    openingDashboard: Boolean,
    openingConsole: Boolean,
    consoleRetired: Boolean,
    onEnable: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenScheduler: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        if (presentation.offersOpen) {
            DistrictButton(
                text = stringResource(
                    if (openingDashboard) {
                        R.string.scheduling_opening
                    } else {
                        R.string.scheduling_open_dashboard
                    },
                ),
                onClick = onOpenDashboard,
                size = ButtonSize.Sm,
                // ⚠️ Gated on its OWN flag alone, never on `busy` and never on the console's. Each
                // hand-off mints its own credential at a different server, and enabling is a
                // separate budget again; none may disable another.
                enabled = !openingDashboard,
                modifier = Modifier.semantics {
                    contentDescription = SCHEDULING_DASHBOARD_DESCRIPTION
                },
            )
        }
        // ⛔ WITHDRAWN ONCE THIS REGION HAS ANSWERED 410. The refusal is a property of the region
        // rather than of one press, so every later attempt would get the identical sentence — the
        // same class of thing as a retry button on a role refusal. See
        // `SchedulingUiState.consoleRetired`, which is a latch and is deliberately not persisted.
        if (presentation.offersOpen && !consoleRetired) {
            DistrictButton(
                text = stringResource(
                    if (openingConsole) {
                        R.string.scheduling_opening
                    } else {
                        R.string.scheduling_open_scheduler
                    },
                ),
                onClick = onOpenScheduler,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                enabled = !openingConsole,
                modifier = Modifier.semantics { contentDescription = SCHEDULING_OPEN_DESCRIPTION },
            )
        }
        if (presentation.offersEnable(status.eligible, status.canManage)) {
            DistrictButton(
                text = stringResource(
                    if (busy) R.string.scheduling_enabling else R.string.scheduling_enable,
                ),
                onClick = onEnable,
                size = ButtonSize.Sm,
                enabled = !busy,
                modifier = Modifier.semantics {
                    contentDescription = SCHEDULING_ENABLE_DESCRIPTION
                },
            )
        }
        if (presentation.offersRefresh) {
            DistrictButton(
                text = stringResource(R.string.scheduling_refresh),
                onClick = onRefresh,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                enabled = !busy,
                modifier = Modifier.semantics {
                    contentDescription = SCHEDULING_REFRESH_DESCRIPTION
                },
            )
        }
    }
}
