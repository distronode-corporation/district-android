package com.distronode.districtai.ui.scheduling.admin.developer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.apiKeys
import com.distronode.districtai.core.data.deleteApiKey
import com.distronode.districtai.core.data.deleteOAuthConnection
import com.distronode.districtai.core.data.deleteWebhook
import com.distronode.districtai.core.data.me
import com.distronode.districtai.core.data.oauthConnections
import com.distronode.districtai.core.data.valueOrNull
import com.distronode.districtai.core.data.webhookDeliveries
import com.distronode.districtai.core.data.webhooks
import com.distronode.districtai.core.model.SchedulingApiKey
import com.distronode.districtai.core.model.SchedulingOAuthConnection
import com.distronode.districtai.core.model.SchedulingWebhook
import com.distronode.districtai.core.model.SchedulingWebhookDelivery
import com.distronode.districtai.ui.scheduling.admin.SchedulingRemoval
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.UTC
import com.distronode.districtai.ui.scheduling.admin.access
import com.distronode.districtai.ui.scheduling.admin.displayTimezone
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The developer screen: three tabs, each read the first time it is shown.
 *
 * ⚠️ A NULL TAB HAS NOT BEEN READ YET, which the screen draws as loading.
 *
 * @property canManage whether the create, revoke, edit and delete controls are drawn.
 * @property deliveries the one webhook whose deliveries are open, if any.
 */
data class SchedulingDeveloperUiState(
    val tab: SchedulingDeveloperTab = SchedulingDeveloperTab.KEYS,
    val keys: SectionState<List<SchedulingApiKey>>? = null,
    val apps: SectionState<List<SchedulingOAuthConnection>>? = null,
    val webhooks: SectionState<List<SchedulingWebhook>>? = null,
    val deliveries: SchedulingOpenDeliveries? = null,
    val timezone: String = UTC,
    val publicHost: String? = null,
    val canManage: Boolean = false,
)

/**
 * One webhook's deliveries, open under its row.
 *
 * ⚠️ READ EACH TIME THE ROW IS OPENED rather than cached per webhook as district-ios does: the
 * read is `viewer`-level and cheap, and a member opening it again wants the latest attempts.
 */
data class SchedulingOpenDeliveries(
    val webhookId: String,
    val read: SectionState<List<SchedulingWebhookDelivery>> = SectionState.Loading,
)

/**
 * The three confirm-then-remove flows, bound to their ops so the wrong delete is a type error.
 *
 * ⛔ EVERY ONE IS IMMEDIATE AND HAS NO UNDO, which is why each asks first, naming the row.
 */
class SchedulingDeveloperRemovals(
    scope: CoroutineScope,
    admin: SchedulingAdminRepository,
    workspaceId: String,
    onRemoved: () -> Unit,
) {
    val keyRevoke = SchedulingRemoval<SchedulingApiKey>(scope, { admin.deleteApiKey(workspaceId, it.id) }) {
        onRemoved()
    }

    /**
     * ⚠️ NOT AN API KEY, though the lists look alike: a connection is a grant a person gave an app,
     * and deleting it signs that app out. Its `client_name` is the app's own text, a label only.
     */
    val appRevoke = SchedulingRemoval<SchedulingOAuthConnection>(
        scope,
        { admin.deleteOAuthConnection(workspaceId, it.id) },
    ) { onRemoved() }

    val webhookDelete = SchedulingRemoval<SchedulingWebhook>(scope, { admin.deleteWebhook(workspaceId, it.id) }) {
        onRemoved()
    }
}

/**
 * API keys, connected apps and webhooks, ported from district-ios's `SchedulingDeveloperModel`.
 *
 * ⛔ NOTHING THIS SCREEN READS CARRIES A SECRET: `apiKeys.list` has no key material and
 * `webhooks.list` no signing secret. The two creates answer one exactly once, and
 * [SchedulingDeveloperWrites] holds it only while its dialog is open.
 *
 * ⚠️ THE CONTEXT (status and profile) IS READ ONCE, BEFORE THE TAB: the timezone captions the
 * stamps, the host builds the MCP address, and `canManage` decides which controls are drawn.
 */
class SchedulingDeveloperViewModel(
    private val admin: SchedulingAdminRepository,
    private val scheduling: SchedulingRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingDeveloperUiState())
    val state: StateFlow<SchedulingDeveloperUiState> = _state.asStateFlow()

    val removals = SchedulingDeveloperRemovals(viewModelScope, admin, workspaceId, ::reload)
    val writes = SchedulingDeveloperWrites(viewModelScope, admin, workspaceId, ::reload)

    init {
        load()
    }

    /** Read everything again: the context, then the tab on screen. The tab choice stays. */
    fun load() {
        _state.value = SchedulingDeveloperUiState(tab = _state.value.tab)
        viewModelScope.launch {
            val profile = async { admin.me(workspaceId) }
            val access = scheduling.access(workspaceId)
            val timezone = profile.await().valueOrNull()?.displayTimezone ?: UTC
            _state.update { it.copy(timezone = timezone, publicHost = access.publicHost, canManage = access.canManage) }
            loadCurrentTab()
        }
    }

    /** ⚠️ A tab already read is not read again when it is returned to; Refresh re-reads it. */
    fun selectTab(tab: SchedulingDeveloperTab) {
        _state.update { it.copy(tab = tab) }
        loadCurrentTab()
    }

    /** Read the tab on screen again. Also what every successful write and removal calls. */
    fun reload() {
        _state.update {
            when (it.tab) {
                SchedulingDeveloperTab.KEYS -> it.copy(keys = null)
                SchedulingDeveloperTab.APPS -> it.copy(apps = null)
                SchedulingDeveloperTab.WEBHOOKS -> it.copy(webhooks = null, deliveries = null)
            }
        }
        loadCurrentTab()
    }

    /**
     * Show or hide one webhook's deliveries, reading them as they open.
     *
     * ⛔ `webhooks.deliveries` IS `viewer`-LEVEL, which is why this is offered to everyone while the
     * edit and delete beside it are not, the split the web makes.
     */
    fun toggleDeliveries(webhookId: String) {
        if (_state.value.deliveries?.webhookId == webhookId) {
            _state.update { it.copy(deliveries = null) }
        } else {
            loadDeliveries(webhookId)
        }
    }

    /** Open one webhook's deliveries and read them (again). */
    fun loadDeliveries(webhookId: String) {
        _state.update { it.copy(deliveries = SchedulingOpenDeliveries(webhookId)) }
        viewModelScope.launch {
            val read = admin.webhookDeliveries(workspaceId, webhookId).toSectionState()
            _state.update { current ->
                // ⚠️ An answer for a row the member has since closed or swapped is dropped.
                if (current.deliveries?.webhookId != webhookId) return@update current
                current.copy(deliveries = SchedulingOpenDeliveries(webhookId, read))
            }
        }
    }

    private fun loadCurrentTab() {
        val current = _state.value
        when (current.tab) {
            SchedulingDeveloperTab.KEYS -> if (current.keys == null) {
                read({ admin.apiKeys(workspaceId) }) { state, keys -> state.copy(keys = keys) }
            }
            SchedulingDeveloperTab.APPS -> if (current.apps == null) {
                read({ admin.oauthConnections(workspaceId) }) { state, apps -> state.copy(apps = apps) }
            }
            SchedulingDeveloperTab.WEBHOOKS -> if (current.webhooks == null) {
                read({ admin.webhooks(workspaceId) }) { state, hooks -> state.copy(webhooks = hooks) }
            }
        }
    }

    private fun <T> read(
        fetch: suspend () -> SchedulingAdminResult<T>,
        put: (SchedulingDeveloperUiState, SectionState<T>) -> SchedulingDeveloperUiState,
    ) {
        _state.update { put(it, SectionState.Loading) }
        viewModelScope.launch {
            val result = fetch().toSectionState()
            _state.update { put(it, result) }
        }
    }

    companion object {
        fun factory(
            admin: SchedulingAdminRepository,
            scheduling: SchedulingRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchedulingDeveloperViewModel(admin, scheduling, workspaceId) }
        }
    }
}
