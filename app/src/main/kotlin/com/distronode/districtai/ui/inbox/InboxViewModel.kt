package com.distronode.districtai.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Inbox list for one workspace.
 *
 * ⛔ NO `cachedIn`, NO Pager, AND THAT IS NOT AN OMISSION. The conversations route is not paged — the
 * server scans a bounded window and groups it — so there is no offset to page on. See
 * [InboxRepository].
 *
 * ⚠️ REFRESHES ON `load(refreshing = true)` RATHER THAN POLLING. The web sidebar polls the unread
 * count; this app does not, because a foreground poll on a phone spends battery to shorten a latency
 * the user resolves by pulling to refresh. A push channel is the right answer and is a separate task.
 */
class InboxViewModel(
    private val repository: InboxRepository,
    /**
     * The composer's repository, read here for ONE thing: which threads have an unsent draft.
     *
     * ⚠️ Nothing else on this screen touches it. The Inbox list does not compose, upload or
     * generate — it only badges — so this reads [ComposerRepository.draftThreadKeys] and never
     * the bodies.
     */
    private val composer: ComposerRepository,
    /**
     * Full-content search across every message in the workspace.
     *
     * ⛔ A SEPARATE REPOSITORY, AND NOT BECAUSE IT IS A SEPARATE CONCERN. `messages/search` belongs
     * on `InboxApi`; it hangs off its own interface because `DistrictApi` is the one guaranteed
     * merge conflict while several people are adding endpoints. See `ExtraPaths` in core-network.
     */
    private val search: MessageSearchRepository,
    val workspaceId: String,
    /**
     * The caller's role, for deciding whether to OFFER a reply box.
     *
     * ⚠️ Can UNDERSTATE access: support access resolves to "agency" for any workspace.
     * Erring low is correct: hiding a control the user could have used beats offering one that 403s.
     * The server remains the authority.
     */
    role: WorkspaceRole?,
    /** ⚠️ A test seam. See [onSearchQueryChanged]. */
    private val searchDebounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
) : ViewModel() {

    /** ⛔ `messages/send` and `messages/mark-read` both exclude `viewer` server-side. */
    val canReply: Boolean = role.allowsMutation()

    private val _state = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    private val _unread = MutableStateFlow(0)

    /** The workspace's unread total, for the nav badge. */
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private val _searchState = MutableStateFlow(InboxSearchState())

    /** ⚠️ A SECOND FLOW, NOT A FIELD ON [InboxUiState]. The list is a read that can fail on its own. */
    val searchState: StateFlow<InboxSearchState> = _searchState.asStateFlow()

    private var searchJob: Job? = null

    init {
        load()
    }

    /**
     * @param refreshing keep the current list on screen while re-reading. ⚠️ Replacing it with a
     *   spinner on a pull-to-refresh throws away what the user is looking at to show them less.
     */
    fun load(refreshing: Boolean = false) {
        val current = _state.value
        if (refreshing && current is InboxUiState.Content) {
            _state.value = current.copy(refreshing = true)
        } else if (!refreshing) {
            _state.value = InboxUiState.Loading
        }

        viewModelScope.launch {
            when (val result = repository.conversations(workspaceId)) {
                is ApiResult.Success -> {
                    _state.value = InboxUiState.Content(
                        conversations = result.value.conversations,
                        partial = result.value.partial,
                    )
                    // ⚠️ Derived from the list rather than fetched again. The list already carries a
                    // per-thread unreadCount, so a second round trip would cost a request to learn
                    // something already in hand — and could disagree with the list the user sees.
                    _unread.value = result.value.conversations.sumOf { it.unreadCount }
                    loadDraftBadges()
                }
                is ApiResult.Failure -> _state.value = InboxUiState.Failed(result.toFailureText())
            }
        }
    }

    /**
     * Record that a thread has been read, and update the badge locally.
     *
     * ⚠️ OPTIMISTIC, AND DELIBERATELY NOT AWAITED BY THE UI. Opening the thread IS the read; if the
     * write fails the badge is stale until the next load, which is a cosmetic problem. Blocking the
     * thread from opening on a bookkeeping write would make the common case slower to serve the rare
     * one.
     *
     * ⚠️ Refuses locally for a role the server would reject, so the app never fires a known 403.
     */
    fun markThreadRead(contactId: String?, counterpart: String?) {
        if (!canReply) return
        val current = _state.value

        if (current is InboxUiState.Content) {
            val updated = current.conversations.map { thread ->
                val matches = (contactId != null && thread.contactId == contactId) ||
                    (contactId == null && thread.counterpart == counterpart)
                if (matches) thread.copy(unreadCount = 0) else thread
            }
            _state.value = current.copy(conversations = updated)
            _unread.value = updated.sumOf { it.unreadCount }
        }

        viewModelScope.launch {
            repository.markRead(workspaceId, contactId, counterpart)
        }
    }

    /**
     * Fetch which threads carry an unsent draft.
     *
     * ⛔ AFTER THE LIST IS ALREADY ON SCREEN, AND FAIL-SOFT. The conversations render first and the
     * badges arrive when they arrive — an Inbox that refused to load because a decorative chip's
     * endpoint was down would be strictly worse than an Inbox with no chips. A failure therefore
     * leaves the set empty and says nothing.
     *
     * ⚠️ ONE REQUEST FOR THE WHOLE LIST, not one per row. The route answers every draft this author
     * has open (capped at 100) in a single indexed query; asking per thread would be a request per
     * visible conversation for a badge.
     *
     * ⚠️ AND ONLY WHEN THIS ROLE CAN COMPOSE. The drafts route excludes `viewer`, so calling it for
     * one would be a known 403 — the same rule that hides the reply box.
     */
    private fun loadDraftBadges() {
        if (!canReply) return
        viewModelScope.launch {
            val result = composer.draftThreadKeys(workspaceId)
            val keys = (result as? ApiResult.Success)?.value ?: return@launch
            val current = _state.value
            if (current is InboxUiState.Content) {
                _state.value = current.copy(draftThreadKeys = keys)
            }
        }
    }

    /**
     * The search field changed.
     *
     * ⛔ DEBOUNCED, AND THE PREVIOUS REQUEST IS CANCELLED RATHER THAN RACED. Without the cancel a
     * slow answer for "ab" can land after a fast one for "abcd" and leave the list showing results
     * for a query that is no longer in the box — a wrong answer rather than a slow one.
     *
     * ⚠️ A QUERY BELOW THE FLOOR CLEARS THE RESULTS AND SENDS NOTHING. The server answers such a
     * query with an empty list and no `limit`, so asking would spend a request to be told what the
     * floor already says.
     */
    fun onSearchQueryChanged(query: String) {
        searchJob?.cancel()
        val next = _searchState.value.copy(query = query, failure = null)
        if (!next.active) {
            _searchState.value = next.copy(running = false, hits = emptyList(), truncated = false)
            return
        }
        _searchState.value = next.copy(running = true)
        searchJob = viewModelScope.launch {
            delay(searchDebounceMillis)
            when (val result = search.search(workspaceId, query)) {
                is ApiResult.Success -> _searchState.value = _searchState.value.copy(
                    running = false,
                    hits = result.value.hits,
                    truncated = result.value.truncated,
                    failure = null,
                )
                is ApiResult.Failure -> _searchState.value = _searchState.value.copy(
                    running = false,
                    hits = emptyList(),
                    truncated = false,
                    failure = result.toFailureText(),
                )
            }
        }
    }

    /**
     * The loaded conversation for a thread key, if there is one.
     *
     * ⛔ THE REPLY TARGET COMES FROM THE CONVERSATION AND NEVER FROM THE HIT'S OWN `kind`. A
     * conversation carries the server's `canSms`/`canEmail`; deriving a channel from a matching
     * message's type would offer SMS to a customer who has only ever emailed, which the server
     * would then refuse. ⚠️ SO A HIT IN A THREAD OUTSIDE THE LOADED WINDOW OPENS READ-ONLY, and
     * that is the truthful outcome rather than a gap: this app does not know whether that thread
     * can be replied to, and offering a box whose send would be refused is worse than not offering
     * one.
     */
    fun conversationFor(threadKey: String): ConversationSummary? =
        (_state.value as? InboxUiState.Content)?.let { content ->
            content.conversations.firstOrNull { it.threadKey == threadKey }
        }

    /**
     * Factory.
     *
     * ⚠️ The workspace id and role travel in rather than being read from a shared holder, for the same
     * reason the contacts screens do it: a destination restored after process death must be
     * self-describing, and the safe default for a missing role (offer nothing) would silently strip an
     * operator's reply box after a kill.
     */
    class Factory(
        private val repository: InboxRepository,
        private val composer: ComposerRepository,
        private val search: MessageSearchRepository,
        private val workspaceId: String,
        private val role: WorkspaceRole?,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return InboxViewModel(repository, composer, search, workspaceId, role) as T
        }
    }

    companion object {
        /** ⚠️ Long enough that typing a word is one request, short enough not to feel stalled. */
        const val SEARCH_DEBOUNCE_MILLIS: Long = 300L
    }
}
