package com.distronode.districtai.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.Avatar
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toPagedFailure
import com.distronode.districtai.ui.toneForCallStatus

/**
 * The paged call log.
 *
 * ⛔ EVERY ROW IS KEYED ON THE CALL ID, AND THE PAGING SOURCE DEDUPLICATES BECAUSE OF IT. Offset
 * paging over a live `createdAt desc` feed serves the boundary row twice when a call arrives
 * mid-scroll, and a duplicate key here is an IllegalArgumentException — a crash, not a cosmetic
 * repeat. The guard lives in CallsPagingSource; this comment exists so nobody "simplifies" the key
 * to an index and hides the reason it was needed.
 *
 * ⚠️ [onBack] IS NEW, AND ITS ABSENCE WAS A REAL GAP. This screen had no app bar and no back
 * affordance at all: it began at the bare top edge under the status bar, so a user two levels deep
 * had only the system gesture and nothing on screen saying where they were.
 */
@Composable
fun CallLogScreen(
    calls: LazyPagingItems<CallSummary>,
    onOpenCall: (String) -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = CALL_LOG_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.call_log_title),
                onBack = onBack,
            )
        },
    ) { inset ->
        // ⛔ THE INSET WRAPS *EVERY* STATE, NOT JUST THE LOADED ONE. `DistrictScaffold` hands back the
        // app-bar padding as a modifier, and passing it only to the populated branch leaves the
        // loading, empty and failure states rendering UNDERNEATH the 56dp bar. Threading the inset
        // by hand into a single branch is easy to get wrong on every screen that does it; a Box
        // around the `when` makes it structural instead.
        Box(modifier = inset.fillMaxSize()) {
            // ⛔ The REFRESH load state decides the whole screen; append/prepend only decide the
            // footer. Conflating them would replace a populated list with a full-screen error
            // because one extra page failed to load.
            when (val refresh = calls.loadState.refresh) {
                is LoadState.Loading -> FullScreenLoading()
                is LoadState.Error -> FullScreenFailure(
                    failure = refresh.error.toPagedFailure(FALLBACK_MESSAGE),
                    onRetry = calls::retry,
                    onSignIn = onSignIn,
                )
                is LoadState.NotLoading ->
                    if (calls.itemCount == 0) {
                        EmptyLog()
                    } else {
                        LoadedLog(calls, onOpenCall, onSignIn)
                    }
            }
        }
    }
}

@Composable
private fun LoadedLog(
    calls: LazyPagingItems<CallSummary>,
    onOpenCall: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            count = calls.itemCount,
            // See the ⛔ on CallLogScreen: the id, never the index.
            key = { index -> calls[index]?.id ?: "placeholder-$index" },
        ) { index ->
            calls[index]?.let { call ->
                ContentContainer {
                    CallRow(call, onClick = { onOpenCall(call.id) })
                    DistrictRowDivider()
                }
            }
        }

        // The footer reports only APPEND state, so a failed extra page never destroys the rows
        // already on screen.
        when (val append = calls.loadState.append) {
            is LoadState.Loading -> item { AppendLoading() }
            is LoadState.Error -> item {
                AppendFailure(
                    failure = append.error.toPagedFailure(FALLBACK_MESSAGE),
                    onRetry = calls::retry,
                    onSignIn = onSignIn,
                )
            }
            is LoadState.NotLoading -> Unit
        }
    }
}

/**
 * ⛔ EVERY DISPLAY RULE COMES FROM [toDisplay], NONE ARE DERIVED HERE. This row used to re-derive
 * them from the raw DTO with its own copies of the wire constants, and it had already drifted from
 * both the overview and the detail screen — most damagingly by rendering only the `success` transfer
 * chip, which made a FAILED transfer invisible in the one list an operator scans to find them.
 *
 * ⚠️ THE TRAILING BADGES ONLY WORK BECAUSE OF THE ContentContainer AROUND THIS ROW. Full-bleed on
 * this app's 1920px surface, the status sat roughly 1700px from the caller name with an empty grey
 * desert between them; capping the width is what puts the two within one glance of each other.
 */
@Composable
private fun CallRow(call: CallSummary, onClick: () -> Unit) {
    val display = call.toDisplay()
    val statusLabel = if (display.live) {
        stringResource(R.string.overview_status_live)
    } else {
        display.status
    }
    val statusTone = if (display.live) Tone.District else toneForCallStatus(display.status)

    DistrictListRow(
        title = display.displayName ?: stringResource(R.string.overview_no_caller_id),
        subtitle = buildString {
            append(
                stringResource(
                    if (display.outbound) {
                        R.string.overview_direction_outbound
                    } else {
                        R.string.overview_direction_inbound
                    },
                ),
            )
            append(" · ")
            // Pre-formatted server-side in the OPERATOR's timezone. Not a parseable instant —
            // reformatting it locally would render it in the device's zone and disagree with the
            // browser.
            append(display.time)
            display.durationLabel?.let {
                append(" · ")
                append(it)
            }
        },
        onClick = onClick,
        leading = {
            Avatar(
                name = display.displayName ?: "",
                // ⚠️ An unidentified caller gets the neutral tone, so an anonymous row does not
                // wear the brand accent as if it were a known contact.
                tone = if (display.displayName == null) Tone.Neutral else Tone.District,
            )
        },
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (display.transferred) {
                    DistrictBadge(
                        text = stringResource(R.string.overview_badge_transferred),
                        tone = Tone.Info,
                    )
                }
                // ⛔ THE BADGE THAT WAS MISSING. A transfer that failed means the caller did not
                // reach the human they were being handed to, which is the single most actionable
                // thing in this list — and the log was the only screen that would not say so. It is
                // Danger-toned so it is findable by colour while scrolling, not just by reading.
                if (display.transferFailed) {
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

/**
 * ⚠️ A resource reference, so the fallback localizes like the rest of the screen. The wire constants
 * that used to sit beside it are gone — they live once, in [CallDisplay].
 */
private val FALLBACK_MESSAGE = UiText.Resource(R.string.call_log_failed)

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val CALL_LOG_ROOT_DESCRIPTION: String = "district-call-log-root"
const val CALL_LOG_LOADING_DESCRIPTION: String = "district-call-log-loading"
const val CALL_LOG_EMPTY_DESCRIPTION: String = "district-call-log-empty"
const val CALL_LOG_FAILURE_DESCRIPTION: String = "district-call-log-failure"
const val CALL_LOG_APPENDING_DESCRIPTION: String = "district-call-log-appending"
const val CALL_LOG_APPEND_FAILURE_DESCRIPTION: String = "district-call-log-append-failure"
