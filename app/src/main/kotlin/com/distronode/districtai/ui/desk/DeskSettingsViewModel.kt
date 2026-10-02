package com.distronode.districtai.ui.desk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.DeskRepository
import com.distronode.districtai.core.model.DeskBrandName
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.core.model.DeskSettingsPatch
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.inbox.AttachmentReader
import com.distronode.districtai.ui.toFailureText
import com.distronode.districtai.ui.updateLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The desk's settings form, and the logo.
 *
 * ⛔ THE SAVE SENDS ONLY WHAT CHANGED, AND THAT IS A CORRECTNESS PROPERTY RATHER THAN AN
 * OPTIMISATION. The route merges per field, so a client posting its whole form state becomes the
 * writer of values it may have read before another tab changed them — the
 * `blank_form_overwrites_config` shape, where a form saved after a stale load writes blanks over
 * live configuration. [patchFrom] is the one place that is decided.
 *
 * ⛔ CLEARING THE BRAND NAME AND LEAVING IT ALONE ARE DIFFERENT WIRE VALUES AND LOOK IDENTICAL ON
 * SCREEN. An empty box is "absent" if the operator never touched it and an explicit `null` if they
 * emptied it, and only [DeskSettingsUiState.Content.brandNameEdited] can tell the two apart. That is
 * the entire reason the flag exists.
 *
 * ⛔ EACH COMPLETION LANDS ON THE LATEST STATE, NOT THE ONE CAPTURED AT THE TAP. The logo controls
 * stay usable during a save, so a save and a logo write overlap; restoring a tap-time snapshot
 * would leave Save stuck on "Saving" or put back a logo that was just replaced. A success still
 * adopts the echoed row whole, but keeps the OTHER write's in-flight flag so its control is not
 * re-armed mid-request. See [updateLatest].
 *
 * ⛔ `publicLogoUrl` IS NEVER IN A PATCH. The settings route does not accept it, by design: the
 * value must be a URL this platform produced, and a caller-supplied one would let a member point
 * their own customers' page at any image on the internet with our domain's reputation attached.
 * Only [uploadLogo] and [deleteLogo] may change it.
 */
class DeskSettingsViewModel(
    private val repository: DeskRepository,
    private val attachments: AttachmentReader,
    val workspaceId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    val canUse: Boolean = role.allowsMutation()

    private val _state = MutableStateFlow<DeskSettingsUiState>(DeskSettingsUiState.Loading)
    val state: StateFlow<DeskSettingsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (!canUse) return
        _state.value = DeskSettingsUiState.Loading
        viewModelScope.launch {
            when (val result = repository.settings(workspaceId)) {
                is ApiResult.Success -> _state.value = contentFor(result.value)
                is ApiResult.Failure ->
                    _state.value = DeskSettingsUiState.Failed(result.toFailureText())
            }
        }
    }

    fun setEnabled(value: Boolean) = edit { it.copy(enabled = value) }

    fun setNotify(value: Boolean) = edit { it.copy(notifyCustomersByEmail = value) }

    /** ⛔ Marks the box as EDITED, which is what makes an emptied box mean "clear it". */
    fun editBrandName(value: String) = edit { it.copy(brandName = value, brandNameEdited = true) }

    /**
     * ⚠️ DECLINES A CLEAN FORM RATHER THAN SENDING AN EMPTY PATCH. The route answers 400 for a body
     * with no fields, deliberately, so a save with nothing to say must not be made at all. An
     * over-long brand name is declined for the same reason; see [DeskSettingsUiState.Content.canSave].
     */
    fun save() {
        if (!canUse) return
        val current = _state.value as? DeskSettingsUiState.Content ?: return
        if (!current.canSave) return

        _state.value = current.copy(saving = true, saveFailure = null)
        viewModelScope.launch {
            when (val result = repository.saveSettings(workspaceId, patchFrom(current))) {
                // ⚠️ THE ECHO IS ADOPTED WHOLE, never the values that were sent. The write returns
                // the stored row, so this screen needs no re-read and must not trust its own form.
                is ApiResult.Success -> updateContent { latest ->
                    contentFor(result.value).copy(logoBusy = latest.logoBusy)
                }
                is ApiResult.Failure ->
                    updateContent { it.copy(saving = false, saveFailure = result.toFailureText()) }
            }
        }
    }

    /**
     * Publish a picked image as the customer-facing logo.
     *
     * ⛔ NO SIZE OR TYPE PRE-CHECK HERE, WHICH IS THE OPPOSITE OF THE MESSAGE COMPOSER'S ATTACHMENT
     * PATH. The logo route's allowlist is not published to this client and its refusals are five
     * different statuses with five different sentences (413 too large, 415 an unhostable type — an
     * SVG is sniffed from the BYTES rather than trusted from the header — 400 for pixel dimensions,
     * 502 for a storage failure, 503 for logo hosting being unconfigured). Guessing any of them here
     * would refuse an image the server would have taken, or show the wrong reason for one it would
     * not. The server's own sentence is surfaced instead.
     */
    fun uploadLogo(uri: String) {
        if (!canUse) return
        val current = _state.value as? DeskSettingsUiState.Content ?: return
        if (current.logoBusy) return

        _state.value = current.copy(logoBusy = true, logoFailure = null, logoObjectRetained = false)
        viewModelScope.launch {
            val picked = attachments.read(uri)
            if (picked == null) {
                // ⚠️ "There was nothing to read" is not a rejected type or size — a revoked grant, a
                // file the provider deleted between the pick and the read, or a cloud item that
                // failed to download. It needs different words and no request is spent.
                updateContent {
                    it.copy(
                        logoBusy = false,
                        logoFailure = FailureText(
                            message = UiText.Resource(R.string.desk_logo_unreadable),
                            retryable = false,
                        ),
                    )
                }
                return@launch
            }
            when (
                val result = repository.uploadLogo(
                    workspaceId = workspaceId,
                    fileName = picked.fileName,
                    mimeType = picked.mimeType,
                    bytes = picked.bytes,
                )
            ) {
                is ApiResult.Success -> updateContent { latest ->
                    contentFor(result.value).copy(saving = latest.saving)
                }
                is ApiResult.Failure ->
                    updateContent { it.copy(logoBusy = false, logoFailure = result.toFailureText()) }
            }
        }
    }

    /**
     * Take the logo down.
     *
     * ⛔ THE ANSWER HAS TWO PARTS AND ONLY THE FIRST IS GUARANTEED BY A 200. The column is cleared
     * first (which is what stops the image appearing on the tenant's page) and the object second
     * (which is what stops the bytes being served at all). `objectRemoved: false` is therefore
     * surfaced rather than swallowed — the logo is gone from the page either way, but "the file may
     * still be reachable" is a thing an operator taking something down deserves to be told.
     *
     * ⚠️ IT IS ALSO FALSE FOR A WORKSPACE THAT HAD NO LOGO, so the flag is only raised when there
     * was one to remove.
     */
    fun deleteLogo() {
        if (!canUse) return
        val current = _state.value as? DeskSettingsUiState.Content ?: return
        if (current.logoBusy || current.stored.publicLogoUrl == null) return

        _state.value = current.copy(logoBusy = true, logoFailure = null, logoObjectRetained = false)
        viewModelScope.launch {
            when (val result = repository.deleteLogo(workspaceId)) {
                is ApiResult.Success -> updateContent { latest ->
                    contentFor(result.value.settings).copy(
                        saving = latest.saving,
                        logoObjectRetained = !result.value.objectRemoved,
                    )
                }
                is ApiResult.Failure ->
                    updateContent { it.copy(logoBusy = false, logoFailure = result.toFailureText()) }
            }
        }
    }

    fun retryOrNoop() {
        if (_state.value is DeskSettingsUiState.Failed) load()
    }

    private fun updateContent(block: (DeskSettingsUiState.Content) -> DeskSettingsUiState) =
        _state.updateLatest(DeskSettingsUiState.Content::class.java, block)

    private fun edit(block: (DeskSettingsUiState.Content) -> DeskSettingsUiState.Content) {
        val current = _state.value as? DeskSettingsUiState.Content ?: return
        _state.value = block(current).copy(saveFailure = null)
    }

    companion object {
        fun factory(
            repository: DeskRepository,
            attachments: AttachmentReader,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DeskSettingsViewModel(repository, attachments, workspaceId, role) }
        }
    }
}

/**
 * ⛔ ONLY THE CHANGED FIELDS. An unchanged field must be ABSENT rather than sent with its
 * current value: the two are indistinguishable to the route today, and the moment another
 * client writes between this screen's read and its save, the second becomes an overwrite.
 */
private fun patchFrom(content: DeskSettingsUiState.Content) = DeskSettingsPatch(
    enabled = content.enabled.takeIf { content.enabledChanged },
    notifyCustomersByEmail = content.notifyCustomersByEmail.takeIf { content.notifyChanged },
    publicBrandName = if (content.brandNameChanged) {
        // ⛔ THE ONE PLACE THE EXPLICIT-NULL ESCAPE HATCH IS REACHED. An emptied box is Clear,
        // which sends a literal `null` and falls the page back to the workspace's own name; a
        // null here would be DROPPED by the encoder and mean "leave it alone".
        content.brandName.trim().takeIf { it.isNotEmpty() }
            ?.let(DeskBrandName::Set)
            ?: DeskBrandName.Clear
    } else {
        null
    },
)

/** ⚠️ The form is rebuilt from the stored row, so `brandNameEdited` resets after every save. */
private fun contentFor(settings: DeskSettings) = DeskSettingsUiState.Content(
    stored = settings,
    enabled = settings.enabled,
    notifyCustomersByEmail = settings.notifyCustomersByEmail,
    brandName = settings.publicBrandName.orEmpty(),
)
