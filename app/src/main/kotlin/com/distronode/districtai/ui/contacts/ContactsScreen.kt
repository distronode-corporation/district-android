package com.distronode.districtai.ui.contacts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toPagedFailure

/**
 * The paged CRM list.
 *
 * ⛔ ROWS ARE KEYED ON THE CONTACT ID BECAUSE THE PAGING SOURCE DEDUPLICATES. Offset paging over a
 * live `createdAt desc` feed serves the boundary row twice when rows are inserted mid-scroll, and a
 * duplicate key in a `LazyColumn` is a crash. That matters more here than for calls: `bulk-create`
 * inserts an entire import in one statement, so many rows can appear between two page loads.
 *
 * ⚠️ REFRESH FAILURE OWNS THE SCREEN, APPEND FAILURE OWNS THE FOOTER — the same split as the call
 * log. Conflating them replaces a scrolled list with a full-screen error because one page failed.
 */
@Composable
fun ContactsScreen(
    contacts: LazyPagingItems<Contact>,
    canMutate: Boolean,
    onOpenContact: (String) -> Unit,
    onSignIn: () -> Unit,
    createState: CreateContactUiState,
    onCreate: (name: String, phoneNumber: String, email: String) -> Unit,
    onCreateHandled: () -> Unit,
    onBack: () -> Unit,
) {
    // ⛔ rememberSaveable, NOT remember. This is the only step in the app that holds user input, and
    // a plain `remember` meant a rotation — or a dark-mode toggle, or a font-size change — closed the
    // half-filled dialog outright. The activity is `singleTask` with no `android:configChanges`, so
    // every one of those destroys it. Whether the dialog is OPEN has to survive alongside the fields
    // inside it, or the fields survive into a dialog nobody can see.
    var creating by rememberSaveable { mutableStateOf(false) }

    // ⚠️ On success: close the sheet, refresh the list so the new row appears, and reset the state.
    // Refreshing rather than inserting locally keeps the ordering the server decides — the list is
    // `createdAt desc` with an id tie-break, and a locally-inserted row would guess its position.
    LaunchedEffect(createState) {
        if (createState is CreateContactUiState.Created) {
            creating = false
            contacts.refresh()
            onCreateHandled()
        }
    }

    if (creating) {
        CreateContactDialog(
            state = createState,
            onCreate = onCreate,
            onDismiss = {
                creating = false
                onCreateHandled()
            },
        )
    }

    // ⚠️ NO `modifier` PARAMETER: the one caller (the nav graph) never sized or placed this screen.
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = CONTACTS_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.contacts_title), onBack = onBack)
        },
    ) { inset ->
        Box(modifier = inset.fillMaxSize()) {
            when (val refresh = contacts.loadState.refresh) {
                is LoadState.Loading -> ContactsLoading()

                is LoadState.Error -> ContactsFailure(
                    failure = refresh.error.toPagedFailure(FALLBACK_MESSAGE),
                    onRetry = contacts::retry,
                    onSignIn = onSignIn,
                )

                is LoadState.NotLoading ->
                    if (contacts.itemCount == 0) {
                        ContactsEmpty(canMutate = canMutate, onCreate = { creating = true })
                    } else {
                        Loaded(contacts, canMutate, onOpenContact, onSignIn)
                    }
            }

            // ⚠️ Offered only to a role the server would admit, and only when there is already a
            // list — the empty state carries its own create action, so showing both would put two
            // competing primary actions on one screen.
            if (canMutate && contacts.itemCount > 0) {
                FloatingActionButton(
                    onClick = { creating = true },
                    // ⛔ The BRAND accent with dark ink, matching DistrictButton's primary. This was
                    // Material's default container, which under the old dynamic-colour theme
                    // rendered TEAL — a colour that appears nowhere in District's identity.
                    containerColor = DistrictTheme.colors.district,
                    contentColor = DistrictTheme.colors.districtForeground,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(DistrictTheme.spacing.section)
                        .semantics { contentDescription = CONTACTS_ADD_DESCRIPTION },
                ) {
                    Text("+")
                }
            }
        }
    }
}

@Composable
private fun Loaded(
    contacts: LazyPagingItems<Contact>,
    canMutate: Boolean,
    onOpenContact: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (!canMutate) {
            // ⚠️ Stated up front rather than discovered by tapping something that 403s. Every
            // contacts mutation excludes `viewer` server-side.
            item {
                ContentContainer {
                    Eyebrow(
                        text = stringResource(R.string.contacts_read_only),
                        modifier = Modifier
                            .padding(DistrictTheme.spacing.gutter)
                            .semantics { contentDescription = CONTACTS_READ_ONLY_DESCRIPTION },
                    )
                }
            }
        }

        items(
            count = contacts.itemCount,
            // See the ⛔ on ContactsScreen: the id, never the index.
            key = { index -> contacts[index]?.id ?: "placeholder-$index" },
        ) { index ->
            contacts[index]?.let { contact ->
                ContentContainer {
                    ContactRow(contact, onClick = { onOpenContact(contact.id) })
                    DistrictRowDivider()
                }
            }
        }

        // APPEND state only, so a failed extra page never destroys the rows already on screen.
        when (val append = contacts.loadState.append) {
            is LoadState.Loading -> item { ContactsAppending() }
            is LoadState.Error -> item {
                ContactsAppendFailure(
                    failure = append.error.toPagedFailure(FALLBACK_MESSAGE),
                    onRetry = contacts::retry,
                    onSignIn = onSignIn,
                )
            }
            is LoadState.NotLoading -> Unit
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    // ⚠️ `name` is non-null server-side but can be the literal "Unknown", which the voice agent
    // writes for an unidentified caller — printing it would read as a name.
    val name = contact.displayName
    DistrictListRow(
        title = name ?: stringResource(R.string.contacts_unnamed),
        // ⚠️ Contacts are EMAIL-FIRST, so a phone-less contact is legal and common. Prefer whichever
        // identifier exists rather than assuming a number.
        subtitle = contact.phoneNumber
            ?: contact.email
            ?: stringResource(R.string.contacts_no_contact_details),
        onClick = onClick,
        leading = {
            Avatar(name = name ?: "", tone = if (name == null) Tone.Neutral else Tone.District)
        },
        trailing = {
            // ⛔ ONLY WHEN A DOSSIER IS GENUINELY BEING BUILT. A null `dgiStatus` means "none, AND
            // none queued" — `clear-intel` resets it to NULL precisely so nothing re-crawls — so
            // rendering null as pending would promise a job that never finishes. See
            // Contact.dgiInProgress.
            if (contact.dgiInProgress) {
                DistrictBadge(
                    text = stringResource(R.string.contact_detail_dossier_pending),
                    tone = Tone.District,
                )
            }
        },
    )
}

private val FALLBACK_MESSAGE = UiText.Resource(R.string.contacts_failed)

/** Stable handles for tests. */
const val CONTACTS_ROOT_DESCRIPTION: String = "district-contacts-root"
const val CONTACTS_LOADING_DESCRIPTION: String = "district-contacts-loading"
const val CONTACTS_EMPTY_DESCRIPTION: String = "district-contacts-empty"
const val CONTACTS_FAILURE_DESCRIPTION: String = "district-contacts-failure"
const val CONTACTS_APPENDING_DESCRIPTION: String = "district-contacts-appending"
const val CONTACTS_APPEND_FAILURE_DESCRIPTION: String = "district-contacts-append-failure"
const val CONTACTS_READ_ONLY_DESCRIPTION: String = "district-contacts-read-only"
const val CONTACTS_ADD_DESCRIPTION: String = "district-contacts-add"
