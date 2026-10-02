package com.distronode.districtai.ui.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.SupportRequestSummary
import com.distronode.districtai.ui.CenteredState
import com.distronode.districtai.ui.FailureState

/**
 * This workspace's requests with Distronode.
 *
 * ⛔ AN EMPTY LIST IS AN EMPTY STATE AND A FAILED READ IS A FAILURE, AND THIS SCREEN MUST NEVER
 * COLLAPSE THEM. The web did, and told a customer with three open tickets that they had none; they
 * stopped chasing and nobody here ever saw the request. That is the single most expensive thing
 * this screen can get wrong.
 *
 * ⛔ NOT THE CUSTOMER DESK. Every string here says "Distronode" or "our team"; the desk's copy says
 * "your customers". A two-word label cannot hold the distinction and a bare "Tickets" on either
 * surface collapses it.
 */
@Composable
fun SupportScreen(
    state: SupportUiState,
    canUse: Boolean,
    onOpenRequest: (SupportRequestSummary) -> Unit,
    onCompose: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = SUPPORT_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.support_title), onBack = onBack)
        },
    ) { inset ->
        Box(modifier = inset.fillMaxSize()) {
            // ⛔ NO REQUEST IS SPENT FOR A VIEWER: all five routes refuse them, reads included.
            // ⚠️ The states are matched exhaustively, so a new one cannot fall through to a blank screen.
            if (!canUse) {
                SupportRefused()
            } else {
                when (state) {
                    SupportUiState.Loading -> SupportLoading()
                    is SupportUiState.Failed -> FailureState(
                        failure = state.failure,
                        onRetry = onRetry,
                        onSignIn = onSignIn,
                        description = SUPPORT_FAILURE_DESCRIPTION,
                    )
                    is SupportUiState.Content -> SupportLoaded(state, onOpenRequest, onCompose)
                }
            }
        }
    }
}

@Composable
private fun SupportLoaded(
    state: SupportUiState.Content,
    onOpenRequest: (SupportRequestSummary) -> Unit,
    onCompose: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.refreshing) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = DistrictTheme.colors.district,
                    trackColor = DistrictTheme.colors.muted,
                )
            }
        }

        item {
            ContentContainer {
                DistrictButton(
                    text = stringResource(R.string.support_new),
                    onClick = onCompose,
                    modifier = Modifier
                        .padding(DistrictTheme.spacing.gutter)
                        .semantics { contentDescription = SUPPORT_NEW_DESCRIPTION },
                )
            }
        }

        if (state.requests.isEmpty()) {
            item { SupportEmpty() }
        }

        section(state.open, R.string.support_section_open, onOpenRequest)
        section(state.resolved, R.string.support_section_resolved, onOpenRequest)

        // ⚠️ STATED. The route caps at 100 with no cursor, so a long-lived workspace does not see
        // its oldest requests here at all.
        if (state.requests.size >= LIST_CAP) {
            item {
                ContentContainer {
                    Text(
                        text = stringResource(R.string.support_capped),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.mutedForeground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(DistrictTheme.spacing.section)
                            .semantics { contentDescription = SUPPORT_CAPPED_DESCRIPTION },
                    )
                }
            }
        }
    }
}

private fun LazyListScope.section(
    requests: List<SupportRequestSummary>,
    titleId: Int,
    onOpenRequest: (SupportRequestSummary) -> Unit,
) {
    if (requests.isEmpty()) return

    item {
        ContentContainer {
            Eyebrow(
                text = stringResource(titleId),
                modifier = Modifier.padding(
                    start = DistrictTheme.spacing.gutter,
                    top = DistrictTheme.spacing.row,
                ),
            )
        }
    }

    // ⛔ KEYED ON OUR OWN ROW `id`, NEVER ON `issueKey`. The key is null while a request is unfiled,
    // so keying on it would give every pending request the same key — a duplicate key in a
    // LazyColumn is a crash rather than a cosmetic repeat.
    items(requests, key = { it.id }) { request ->
        ContentContainer {
            RequestRow(request = request, onClick = { onOpenRequest(request) })
            DistrictRowDivider()
        }
    }
}

@Composable
private fun RequestRow(request: SupportRequestSummary, onClick: () -> Unit) {
    DistrictListRow(
        title = request.subject,
        // ⚠️ THE KEY IF WE HAVE ONE, OTHERWISE "being opened". A request with no key is not broken:
        // we hold it and it is addressable by its own id, Atlassian simply does not have it yet.
        subtitle = request.issueKey ?: stringResource(R.string.support_unfiled),
        onClick = onClick,
        trailing = {
            DistrictBadge(
                // ⛔ THE DESK'S OWN WORD, SHOWN AS SENT. The live workflow is localised, so
                // substituting "Closed" would print English over a status Atlassian spells in
                // another language.
                text = request.statusName,
                tone = if (request.isResolved) Tone.Success else Tone.District,
                modifier = Modifier.semantics { contentDescription = SUPPORT_STATUS_DESCRIPTION },
            )
        },
    )
}

@Composable
private fun SupportEmpty() {
    ContentContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DistrictTheme.spacing.section)
                .semantics { contentDescription = SUPPORT_EMPTY_DESCRIPTION },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyState(
                title = stringResource(R.string.support_empty_title),
                body = stringResource(R.string.support_empty),
            )
        }
    }
}

@Composable
private fun SupportRefused() {
    CenteredState(SUPPORT_REFUSED_DESCRIPTION) {
        EmptyState(
            title = stringResource(R.string.support_viewer_title),
            body = stringResource(R.string.support_viewer_body),
        )
    }
}

@Composable
private fun SupportLoading() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = SUPPORT_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_ROWS) { SkeletonBlock(height = SKELETON_HEIGHT) }
        }
    }
}

private const val SKELETON_ROWS = 6
private val SKELETON_HEIGHT = 56.dp

/** ⚠️ The route's own cap. */
private const val LIST_CAP = 100

const val SUPPORT_ROOT_DESCRIPTION: String = "district-support-root"
const val SUPPORT_LOADING_DESCRIPTION: String = "district-support-loading"
const val SUPPORT_EMPTY_DESCRIPTION: String = "district-support-empty"
const val SUPPORT_FAILURE_DESCRIPTION: String = "district-support-failure"
const val SUPPORT_REFUSED_DESCRIPTION: String = "district-support-refused"
const val SUPPORT_NEW_DESCRIPTION: String = "district-support-new"
const val SUPPORT_STATUS_DESCRIPTION: String = "district-support-status"
const val SUPPORT_CAPPED_DESCRIPTION: String = "district-support-capped"
