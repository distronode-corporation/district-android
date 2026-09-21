package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.KB_MODES
import com.distronode.districtai.core.model.KB_MODE_LINKED
import com.distronode.districtai.core.model.KnowledgeDocument
import com.distronode.districtai.ui.FailureText

/**
 * What the knowledge screen holds.
 *
 * ⛔ THE LOAD GATE HERE IS WEAKER THAN THE REST OF THIS PACKAGE, AND THAT IS CORRECT RATHER THAN AN
 * INCONSISTENCY. Nothing on this screen is a wholesale replace: a document is created and deleted
 * BY ID, and the mode is a single scalar. There is no array whose absence would be written back as
 * a deletion, so an add form can exist before the list has loaded without being dangerous. The one
 * control that IS gated on a successful read is the mode selector — because switching a mode this
 * client never read would be an edit made against an unknown value.
 *
 * ⛔ [canWrite] IS A UX GATE AND NEVER A SECURITY ONE. The two READS admit `viewer` server-side and
 * all three writes exclude one, and a viewer genuinely reaches this screen. Its job is that a
 * viewer is never OFFERED a control that would 403; the server re-checks on every request, and
 * [KnowledgeViewModel] re-checks this flag at each write's call site rather than trusting that a
 * control was not drawn.
 *
 * ⛔ AND [documents] IS NEVER PATCHED LOCALLY. A create echoes a row with one field fewer than the
 * list carries (`sourceUrl` is absent from the POST's `select`), and a delete answers success even
 * when it matched nothing — so both re-read. Appending or removing locally would show a document
 * with no source, or hide one that is still there.
 */
data class KnowledgeUiState(
    val list: KnowledgeListState = KnowledgeListState.Loading,
    /** ⛔ False for `viewer` and for an unparseable role — see the ⛔ on the class. */
    val canWrite: Boolean = false,
    /**
     * The stored mode, or null when the mode read has not landed.
     *
     * ⚠️ NULL IS NOT `internal`. The route's own sanitiser defaults an unreadable stored value to
     * `internal`, but a FAILED READ is a different thing and must not render as a chosen mode —
     * that would show "your questions stay in region" for a workspace that chose otherwise.
     */
    val mode: String? = null,
    /** ⛔ True when the mode READ failed. The selector is withheld; the document list still shows. */
    val modeUnavailable: Boolean = false,
    val modeSave: SaveState = SaveState.Idle,
    val draftTitle: String = "",
    val draftContent: String = "",
    /** ⚠️ Set by a rejected add, cleared by the next keystroke. */
    val addRejected: Boolean = false,
    val addSave: SaveState = SaveState.Idle,
    val deleteSave: SaveState = SaveState.Idle,
) {

    val documents: List<KnowledgeDocument>
        get() = (list as? KnowledgeListState.Ready)?.documents ?: emptyList()

    val busy: Boolean get() = addSave.busy || deleteSave.busy || modeSave.busy

    /**
     * ⛔ TITLE AND CONTENT ARE BOTH REQUIRED, WHICH IS THE ROUTE'S OWN RULE rather than a client
     * invention: it answers 400 "Missing title or content" for a blank either. Checked here so an
     * operator is not charged a round trip to be told, and so the button is honest.
     */
    val canAdd: Boolean
        get() = canWrite && !busy && draftTitle.isNotBlank() && draftContent.isNotBlank()

    /**
     * ⛔ Gated on a successful mode READ — see the ⛔ on the class — AND on the role. A viewer sees
     * which mode is stored (the read admits them) and cannot select another.
     */
    val canChangeMode: Boolean get() = canWrite && !busy && !modeUnavailable && mode != null

    /** ⚠️ The stored mode first when it is one this build knows, so the current choice is selectable. */
    val modeOptions: List<String> get() = KB_MODES

    /** ⛔ The one option that sends this workspace's questions to a third party. */
    fun isResidencyChange(next: String): Boolean = next == KB_MODE_LINKED && mode != KB_MODE_LINKED
}

/**
 * The document list's own load state.
 *
 * ⚠️ SEPARATE FROM [ConfigState] BECAUSE IT IS A DIFFERENT READ WITH A DIFFERENT PAYLOAD. Reusing
 * the config state would imply this screen hydrates from `workspace/config`, which it does not —
 * and the whole point of that type is that it gates a wholesale-replace save.
 */
sealed interface KnowledgeListState {

    data object Loading : KnowledgeListState

    /** ⚠️ An empty list is a real answer: a workspace that has uploaded nothing. */
    data class Ready(val documents: List<KnowledgeDocument>) : KnowledgeListState

    data class Failed(val failure: FailureText) : KnowledgeListState
}
