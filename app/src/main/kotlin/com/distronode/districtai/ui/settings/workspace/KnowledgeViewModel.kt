package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.KnowledgeRepository
import com.distronode.districtai.core.model.KnowledgeCreateRequest
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The knowledge screen's state machine.
 *
 * ⛔ THE CREATE IS THE ONLY CALL IN THIS APP THAT SPENDS MODEL BUDGET ON THE OPERATOR'S BEHALF, and
 * the size of the spend is set by what they pasted: the route chunks the content and embeds every
 * chunk in one request. So there is no auto-retry here, no re-send on a session change, and the
 * button is disabled for the whole round trip — a second tap on a slow network would pay twice for
 * a duplicate document.
 *
 * ⛔ AND BOTH WRITES RE-READ THE LIST RATHER THAN PATCHING IT. The create's echoed row is one field
 * short of a list row (`sourceUrl` is not in the POST's `select`) and the delete answers success
 * even when it matched nothing, so a locally-patched list would be confidently wrong in two
 * different directions.
 *
 * ⛔ THE ROLE GATE IS THE WHOLE SHAPE OF THIS SCREEN FOR A VIEWER. Both READS admit `viewer`
 * server-side and all three writes exclude one, so a viewer gets the document list and the
 * stored mode, and no add form, no delete button and no mode selector. ⚠️ Every write re-checks
 * [KnowledgeUiState.canWrite] at the call site rather than trusting that the UI hid the control: a
 * state flag is not a call site, and the server's `requireWorkspaceRole` is the actual boundary.
 *
 * ⚠️ THE TWO READS RUN CONCURRENTLY AND FAIL INDEPENDENTLY. A workspace whose mode read fails still
 * gets its document list; the mode selector is withheld rather than the screen failing, because
 * offering a selector seeded from a guess is how a data-residency setting gets changed by accident.
 */
class KnowledgeViewModel(
    private val repository: KnowledgeRepository,
    private val workspaceId: String,
    /**
     * ⛔ NO DEFAULT, DELIBERATELY. A defaulted `null` would fail closed and therefore be SAFE, but
     * it would also let a new call site silently ship a screen with no write controls and no
     * compiler complaint. Every caller states the role it resolved.
     */
    role: WorkspaceRole?,
) : ViewModel() {

    private val _state = MutableStateFlow(KnowledgeUiState(canWrite = role.allowsMutation()))
    val state: StateFlow<KnowledgeUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** ⚠️ Both reads, concurrently. Idempotent GETs, so replaying this on a session change is safe. */
    fun load() {
        _state.value = _state.value.copy(list = KnowledgeListState.Loading)
        viewModelScope.launch {
            val documents = async { repository.documents(workspaceId) }
            val mode = async { repository.mode(workspaceId) }
            _state.value = applyLoad(_state.value, documents.await(), mode.await())
        }
    }

    fun editTitle(value: String) {
        _state.value = _state.value.copy(draftTitle = value, addRejected = false)
    }

    fun editContent(value: String) {
        _state.value = _state.value.copy(draftContent = value, addRejected = false)
    }

    /**
     * Upload a document.
     *
     * ⛔ BILLABLE, AND NOT IDEMPOTENT. The guard is not defensive: [KnowledgeUiState.busy] covers
     * the whole round trip precisely so a second tap cannot buy a second embedding run over the
     * same text.
     */
    fun addDocument() {
        val current = _state.value
        if (current.busy || !current.canWrite) return
        if (!current.canAdd) {
            _state.value = current.copy(addRejected = true)
            return
        }

        _state.value = current.copy(addSave = SaveState.Saving)
        viewModelScope.launch {
            val result = repository.addDocument(
                KnowledgeCreateRequest(
                    workspaceId = workspaceId,
                    title = current.draftTitle.trim(),
                    // ⚠️ NOT TRIMMED BEYOND THE ENDS. The chunker owns what the text becomes;
                    // reshaping it here would change what was embedded from what was shown.
                    content = current.draftContent.trim(),
                ),
            )
            _state.value = when (result) {
                is ApiResult.Success -> _state.value.copy(
                    draftTitle = "",
                    draftContent = "",
                    addSave = SaveState.Saved,
                )
                is ApiResult.Failure -> _state.value.copy(
                    // ⚠️ THE DRAFT SURVIVES A FAILURE. Losing a pasted document because the
                    // upload failed would be two losses for one fault.
                    addSave = SaveState.Failed(result.toFailureText()),
                )
            }
            if (result is ApiResult.Success) reloadDocuments()
        }
    }

    /**
     * Delete a document.
     *
     * ⛔ THE CONFIRMATION IS THE SCREEN'S AND HAS ALREADY HAPPENED. Deleting is not recoverable: the
     * chunks cascade and the embeddings have to be paid for again to restore the document.
     *
     * ⛔ AND SUCCESS DOES NOT PROVE A ROW WAS REMOVED — the route's `deleteMany` never reads its
     * count. The list is re-read rather than the row being dropped locally.
     */
    fun deleteDocument(documentId: String) {
        if (_state.value.busy || !_state.value.canWrite) return
        _state.value = _state.value.copy(deleteSave = SaveState.Saving)
        viewModelScope.launch {
            _state.value = when (val result = repository.deleteDocument(workspaceId, documentId)) {
                is ApiResult.Success -> _state.value.copy(deleteSave = SaveState.Saved)
                is ApiResult.Failure ->
                    _state.value.copy(deleteSave = SaveState.Failed(result.toFailureText()))
            }
            reloadDocuments()
        }
    }

    /**
     * Choose where answers come from.
     *
     * ⛔ THE SERVER'S ECHO IS ADOPTED, NEVER THE REQUESTED VALUE. The route re-reads through its own
     * sanitiser before answering, so the mode that comes back is what a later read will see.
     *
     * ⚠️ The confirmation for `linked` lives on the screen: it is a data-residency change, not a
     * display preference, and the wording has to say where the questions go.
     */
    fun setMode(mode: String) {
        val current = _state.value
        if (!current.canChangeMode || mode == current.mode) return

        _state.value = current.copy(modeSave = SaveState.Saving)
        viewModelScope.launch {
            _state.value = when (val result = repository.setMode(workspaceId, mode)) {
                is ApiResult.Success ->
                    _state.value.copy(mode = result.value, modeSave = SaveState.Saved)
                is ApiResult.Failure ->
                    _state.value.copy(modeSave = SaveState.Failed(result.toFailureText()))
            }
        }
    }

    /** ⚠️ The list only. A write must not re-read the MODE and quietly overwrite a save banner. */
    private suspend fun reloadDocuments() {
        _state.value = when (val result = repository.documents(workspaceId)) {
            is ApiResult.Success ->
                _state.value.copy(list = KnowledgeListState.Ready(result.value))
            is ApiResult.Failure ->
                _state.value.copy(list = KnowledgeListState.Failed(result.toFailureText()))
        }
    }

    /**
     * ⛔ TWO INDEPENDENT OUTCOMES. A failed mode read sets [KnowledgeUiState.modeUnavailable] and
     * leaves the documents alone; a failed document read leaves whatever the mode read said. Neither
     * failure may present as the other, because "we could not read the mode" and "the mode is
     * internal" are different claims about where a customer's questions go.
     */
    private fun applyLoad(
        current: KnowledgeUiState,
        documents: ApiResult<List<com.distronode.districtai.core.model.KnowledgeDocument>>,
        mode: ApiResult<String?>,
    ): KnowledgeUiState = current.copy(
        list = when (documents) {
            is ApiResult.Success -> KnowledgeListState.Ready(documents.value)
            is ApiResult.Failure -> KnowledgeListState.Failed(documents.toFailureText())
        },
        mode = (mode as? ApiResult.Success)?.value,
        modeUnavailable = mode is ApiResult.Failure,
    )

    companion object {
        fun factory(
            repository: KnowledgeRepository,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                KnowledgeViewModel(repository, workspaceId, role) as T
        }
    }
}
