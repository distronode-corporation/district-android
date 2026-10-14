package com.distronode.districtai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.core.data.BlockSubject
import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.ui.contacts.BlockCallerViewModel
import com.distronode.districtai.ui.contacts.BlockUi
import com.distronode.districtai.ui.contacts.BlockedContactsStore
import com.distronode.districtai.ui.inbox.ThreadModeration
import com.distronode.districtai.ui.report.ReportDialogHost
import com.distronode.districtai.ui.report.ReportTarget

/**
 * The Report entry point of one screen: the action to put on it, and the dialog while it is open.
 *
 * Null when [allowed] is false: the support routes exclude `viewer`, and a role that would be refused
 * is not offered the control.
 *
 * ⚠️ IN ITS OWN FILE, NOT IN DistrictNavHost.kt, which sits at detekt's per-file function ceiling.
 */
@Composable
internal fun reportAction(
    allowed: Boolean,
    target: ReportTarget,
    workspaceId: String,
    support: SupportRepository,
): (() -> Unit)? = if (allowed) reportLauncher(target, workspaceId, support) else null

/** Opens the Report dialog for [target], and draws it while open. */
@Composable
private fun reportLauncher(target: ReportTarget, workspaceId: String, support: SupportRepository): () -> Unit {
    var reporting by rememberSaveable { mutableStateOf(false) }
    if (reporting) {
        ReportDialogHost(target = target, workspaceId = workspaceId, support = support) { reporting = false }
    }
    return { reporting = true }
}

/** The block state and control of one caller, from its own [BlockCallerViewModel]. */
@Composable
internal fun blockUi(store: BlockedContactsStore, workspaceId: String, subject: BlockSubject): BlockUi {
    val viewModel: BlockCallerViewModel = viewModel(
        key = "block-$workspaceId-$subject",
        factory = BlockCallerViewModel.factory(store, workspaceId, subject),
    )
    // ⚠️ A SILENT READ, so the badge is right on a screen opened before any list was read.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    return BlockUi(
        blocked = blocked,
        busy = busy,
        failure = failure,
        onSetBlocked = { viewModel.setBlocked(it) },
        onDismissFailure = viewModel::clearFailure,
    )
}

/**
 * The thread's Report and Block, for a role that may write.
 *
 * ⛔ THE BLOCK SUBJECT COMES FROM THE THREAD'S OWN KEY: `contact:` gives an exact id, `addr:` only an
 * address, which the server upserts a contact for. A malformed key gives no subject and no menu.
 *
 * ⛔ [onBlocked] RUNS ONLY AFTER THE SERVER CONFIRMED THE BLOCK, and closes the thread.
 */
@Composable
@Suppress("LongParameterList")
internal fun threadModeration(
    allowed: Boolean,
    threadKey: String,
    title: String?,
    workspaceId: String,
    store: BlockedContactsStore,
    support: SupportRepository,
    onBlocked: () -> Unit,
): ThreadModeration? {
    val selector = parseThreadKey(threadKey)
    val subject = selector.contactId?.let(BlockSubject::Contact) ?: selector.address?.let(BlockSubject::Phone)
    if (!allowed || subject == null) return null
    val report = reportLauncher(ReportTarget.Conversation(threadKey, title), workspaceId, support)
    val viewModel: BlockCallerViewModel = viewModel(
        key = "block-$workspaceId-$subject",
        factory = BlockCallerViewModel.factory(store, workspaceId, subject),
    )
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    return ThreadModeration(
        onReport = report,
        onBlock = { viewModel.setBlocked(true, onBlocked) },
        busy = busy,
        failure = failure,
        onDismissFailure = viewModel::clearFailure,
    )
}
