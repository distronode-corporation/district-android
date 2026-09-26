package com.distronode.districtai.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.Avatar
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
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.MessageSearchHit
import com.distronode.districtai.ui.resolve

/**
 * The unified Inbox: one row per conversation, SMS and email folded together.
 *
 * ⛔ ROWS ARE KEYED ON `threadKey`, NEVER ON THE COUNTERPART ADDRESS. A contact-keyed thread carries
 * both a phone number and an email, so keying on the address would produce a DUPLICATE KEY the moment
 * one person's SMS and email fold into a single row — and a duplicate key in a `LazyColumn` is an
 * IllegalArgumentException, i.e. a crash rather than a cosmetic repeat. Same rule as the call log's id
 * key, for the same reason.
 *
 * ⚠️ NO PAGING, BY DESIGN. The server scans a bounded window and groups it, so there is no offset to
 * page on. When the window is exhausted the screen SAYS the list is partial rather than implying it is
 * everything — see [InboxUiState.Content.partial].
 */
@Composable
fun InboxScreen(
    state: InboxUiState,
    onOpenThread: (ConversationSummary) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    searchState: InboxSearchState,
    onSearchQueryChanged: (String) -> Unit,
    onOpenHit: (MessageSearchHit) -> Unit,
) {
    // ⚠️ NO `modifier` PARAMETER: the one caller (the nav graph) never sized or placed this screen.
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = INBOX_ROOT_DESCRIPTION },
        topBar = { DistrictTopBar(title = stringResource(R.string.inbox_title), onBack = onBack) },
    ) { inset ->
        Column(modifier = inset.fillMaxSize()) {
            SearchField(searchState, onSearchQueryChanged)
            Box(modifier = Modifier.fillMaxSize()) {
                // ⛔ SEARCH REPLACES THE LIST RATHER THAN FILTERING IT, because it answers a
                // different question: the route queries EVERY message in the workspace, while the
                // list holds a bounded window of recent ones grouped into threads. Merging the two
                // would present a whole-history answer as if it were a page of the list.
                if (searchState.active) {
                    SearchResults(searchState, onOpenHit)
                } else {
                    when (state) {
                        InboxUiState.Loading -> InboxLoading()
                        is InboxUiState.Failed -> InboxFailure(state, onRetry)
                        is InboxUiState.Content ->
                            if (state.conversations.isEmpty()) {
                                InboxEmpty()
                            } else {
                                Loaded(state, onOpenThread)
                            }
                    }
                }
            }
        }
    }
}

/** ⚠️ Always visible, including on an empty Inbox: a workspace with no recent threads can still
 * have older ones, and a search box that appeared only when there was something to filter would
 * hide the one control that reaches them. */
@Composable
private fun SearchField(state: InboxSearchState, onQueryChanged: (String) -> Unit) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChanged,
        label = { Eyebrow(stringResource(R.string.inbox_search_label)) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = DistrictTheme.colors.muted,
            unfocusedContainerColor = DistrictTheme.colors.muted,
            disabledContainerColor = DistrictTheme.colors.muted,
            focusedBorderColor = DistrictTheme.colors.district,
            unfocusedBorderColor = DistrictTheme.colors.border,
            focusedTextColor = DistrictTheme.colors.foreground,
            unfocusedTextColor = DistrictTheme.colors.foreground,
            cursorColor = DistrictTheme.colors.district,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(DistrictTheme.spacing.gutter)
            .semantics { contentDescription = INBOX_SEARCH_FIELD_DESCRIPTION },
    )
}

/**
 * The results for one query.
 *
 * ⛔ KEYED ON `messageId`, NEVER ON `threadKey`. Two matches in one conversation are two rows the
 * server sent separately, so a thread key here is a duplicate key in a `LazyColumn`.
 *
 * ⛔ THE CAP IS STATED, NOT SWALLOWED. The server stopped at its own ceiling, so older matches
 * exist and are not on this screen; there is no offset to page on, so this is a note rather than a
 * control.
 */
@Composable
private fun SearchResults(state: InboxSearchState, onOpenHit: (MessageSearchHit) -> Unit) {
    state.failure?.let { failure ->
        ContentContainer(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DistrictTheme.spacing.section)
                    .semantics { contentDescription = INBOX_SEARCH_FAILURE_DESCRIPTION },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = failure.message.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DistrictTheme.colors.foreground,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    if (state.hits.isEmpty()) {
        ContentContainer(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = INBOX_SEARCH_EMPTY_DESCRIPTION },
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyState(
                    title = stringResource(
                        if (state.running) R.string.inbox_search_running else R.string.inbox_search_none_title,
                    ),
                    body = stringResource(R.string.inbox_search_none),
                )
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.hits, key = { it.messageId }) { hit ->
            ContentContainer {
                MessageSearchRow(hit = hit, onClick = { onOpenHit(hit) })
                DistrictRowDivider()
            }
        }
        if (state.truncated) {
            item {
                ContentContainer {
                    Text(
                        text = stringResource(R.string.inbox_search_capped),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.mutedForeground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(DistrictTheme.spacing.section)
                            .semantics { contentDescription = INBOX_SEARCH_CAPPED_DESCRIPTION },
                    )
                }
            }
        }
    }
}

@Composable
private fun Loaded(
    state: InboxUiState.Content,
    onOpenThread: (ConversationSummary) -> Unit,
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

        items(state.conversations, key = { it.threadKey }) { thread ->
            ContentContainer {
                ThreadRow(
                    thread = thread,
                    hasDraft = thread.threadKey in state.draftThreadKeys,
                    onClick = { onOpenThread(thread) },
                )
                DistrictRowDivider()
            }
        }

        // ⛔ STATED, NOT SWALLOWED. The scan window was exhausted, so a quiet older thread is simply
        // absent. Showing a truncated list as if it were complete is the same class of mistake as
        // reporting a degraded region's absence as "you have no workspaces".
        if (state.partial) {
            item {
                ContentContainer {
                    Text(
                        text = stringResource(R.string.inbox_partial),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.mutedForeground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(DistrictTheme.spacing.section)
                            .semantics { contentDescription = INBOX_PARTIAL_DESCRIPTION },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: ConversationSummary, hasDraft: Boolean, onClick: () -> Unit) {
    DistrictListRow(
        title = thread.displayName,
        // ⚠️ The last message's body, one line. An inbound row reads as the customer talking; the
        // direction is carried by the badge rather than by a prefix, which would eat the preview.
        subtitle = thread.lastMessage.body.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.inbox_no_preview),
        onClick = onClick,
        leading = {
            Avatar(
                name = thread.contactName.orEmpty(),
                // ⚠️ Neutral for an unresolved address, so a thread with no Contact row does not wear
                // the brand accent as if it were a known customer.
                tone = if (thread.contactName == null) Tone.Neutral else Tone.District,
            )
        },
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ⚠️ Channels only when the thread actually mixes them. A single-channel thread does
                // not need to be told it is SMS; two badges on every row would be noise that hides
                // the one badge that matters.
                if (thread.channels.size > 1) {
                    thread.channels.forEach {
                        DistrictBadge(text = it.uppercase(), tone = Tone.Neutral)
                    }
                }
                // ⚠️ BEFORE the unread count, so the unread badge keeps the rightmost position it
                // has always had. An operator scanning the list reads the right edge for "needs
                // me"; a draft chip that displaced it would move the thing they look for.
                if (hasDraft) {
                    DistrictBadge(
                        text = stringResource(R.string.inbox_draft_badge),
                        tone = Tone.Info,
                        modifier = Modifier.semantics {
                            contentDescription = INBOX_DRAFT_DESCRIPTION
                        },
                    )
                }
                if (thread.hasUnread) {
                    DistrictBadge(
                        text = thread.unreadCount.toString(),
                        tone = Tone.District,
                        modifier = Modifier.semantics {
                            contentDescription = INBOX_UNREAD_DESCRIPTION
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun InboxLoading() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = INBOX_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_ROWS) { SkeletonBlock(height = SKELETON_ROW_HEIGHT) }
        }
    }
}

@Composable
private fun InboxEmpty() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = INBOX_EMPTY_DESCRIPTION },
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyState(
                title = stringResource(R.string.inbox_empty_title),
                body = stringResource(R.string.inbox_empty),
            )
        }
    }
}

@Composable
private fun InboxFailure(state: InboxUiState.Failed, onRetry: () -> Unit) {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DistrictTheme.spacing.section)
                .semantics { contentDescription = INBOX_FAILURE_DESCRIPTION },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Eyebrow(text = stringResource(R.string.inbox_title))
            Text(
                text = state.failure.message.resolve(),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
            )
            // ⚠️ Only when retrying could work. A role refusal answers identically every time, and a
            // button that cannot succeed is worse than no button.
            if (state.failure.retryable) {
                DistrictButton(
                    text = stringResource(R.string.overview_retry),
                    onClick = onRetry,
                    modifier = Modifier.padding(top = DistrictTheme.spacing.section),
                )
            }
        }
    }
}

private const val SKELETON_ROWS = 8
private val SKELETON_ROW_HEIGHT = 56.dp

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val INBOX_ROOT_DESCRIPTION: String = "district-inbox-root"
const val INBOX_LOADING_DESCRIPTION: String = "district-inbox-loading"
const val INBOX_EMPTY_DESCRIPTION: String = "district-inbox-empty"
const val INBOX_FAILURE_DESCRIPTION: String = "district-inbox-failure"
const val INBOX_PARTIAL_DESCRIPTION: String = "district-inbox-partial"
const val INBOX_UNREAD_DESCRIPTION: String = "district-inbox-unread"
const val INBOX_DRAFT_DESCRIPTION: String = "district-inbox-draft"
const val INBOX_SEARCH_FIELD_DESCRIPTION: String = "district-inbox-search-field"
const val INBOX_SEARCH_EMPTY_DESCRIPTION: String = "district-inbox-search-empty"
const val INBOX_SEARCH_FAILURE_DESCRIPTION: String = "district-inbox-search-failure"
const val INBOX_SEARCH_CAPPED_DESCRIPTION: String = "district-inbox-search-capped"
