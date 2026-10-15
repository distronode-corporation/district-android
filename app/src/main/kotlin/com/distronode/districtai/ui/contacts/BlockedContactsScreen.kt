package com.distronode.districtai.ui.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.l10n.serverText
import com.distronode.districtai.ui.CenteredState
import com.distronode.districtai.ui.FailureState
import com.distronode.districtai.ui.PagedListEmpty

/** Everyone this workspace has blocked, each with an Unblock. */
@Composable
fun BlockedContactsScreen(
    state: BlockedContactsUiState,
    canMutate: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onUnblock: (String) -> Unit,
    onDismissFailure: () -> Unit,
) {
    DistrictScaffold(
        modifier = Modifier.testTag(BLOCKED_ROOT_TAG),
        topBar = { DistrictTopBar(title = stringResource(R.string.contact_block_list_title), onBack = onBack) },
    ) { inset ->
        Box(modifier = inset.fillMaxSize()) {
            when (state) {
                BlockedContactsUiState.Loading -> CenteredState(BLOCKED_LOADING_TAG) { CircularProgressIndicator() }
                is BlockedContactsUiState.Failed -> FailureState(
                    failure = state.failure,
                    onRetry = onRetry,
                    onSignIn = onSignIn,
                    description = BLOCKED_FAILURE_TAG,
                )
                is BlockedContactsUiState.Content -> Content(state, canMutate, onUnblock, onDismissFailure)
            }
        }
    }
}

@Composable
private fun Content(
    state: BlockedContactsUiState.Content,
    canMutate: Boolean,
    onUnblock: (String) -> Unit,
    onDismissFailure: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        state.failure?.let { failure ->
            ContentContainer(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
                BlockFailureNotice(failure, onDismissFailure)
            }
        }
        if (state.contacts.isEmpty()) {
            // ⚠️ Reachable only after a read SUCCEEDED, so it genuinely means "no one is blocked".
            PagedListEmpty(
                description = BLOCKED_EMPTY_TAG,
                title = stringResource(R.string.contact_block_list_title),
                body = stringResource(R.string.contact_block_empty),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.contacts, key = { it.contactId }) { contact ->
                    ContentContainer {
                        Row(contact, canMutate, enabled = state.unblocking == null) {
                            confirming = contact.contactId
                        }
                        DistrictRowDivider()
                    }
                }
            }
        }
    }
    confirming?.let { contactId ->
        BlockConfirmDialog(
            blocked = true,
            onConfirm = {
                confirming = null
                onUnblock(contactId)
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun Row(contact: BlockedContact, canMutate: Boolean, enabled: Boolean, onUnblock: () -> Unit) {
    DistrictListRow(
        title = contact.name.takeIf { it.isNotBlank() }?.let { serverText(it) }
            ?: stringResource(R.string.contacts_unnamed),
        subtitle = contact.phoneNumber,
        trailing = {
            if (canMutate) {
                TextButton(
                    onClick = onUnblock,
                    enabled = enabled,
                    modifier = Modifier.testTag(blockedUnblockTag(contact.contactId)),
                ) {
                    Text(stringResource(R.string.contact_block_confirm_unblock))
                }
            } else {
                BlockedBadge()
            }
        },
    )
}

/** Stable handles for tests. */
const val BLOCKED_ROOT_TAG: String = "district-blocked-root"
const val BLOCKED_LOADING_TAG: String = "district-blocked-loading"
const val BLOCKED_FAILURE_TAG: String = "district-blocked-failure"
const val BLOCKED_EMPTY_TAG: String = "district-blocked-empty"

fun blockedUnblockTag(contactId: String): String = "district-blocked-unblock-$contactId"
