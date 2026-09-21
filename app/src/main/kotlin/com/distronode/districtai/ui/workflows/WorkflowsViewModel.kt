package com.distronode.districtai.ui.workflows

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.WorkflowsRepository
import com.distronode.districtai.core.model.WorkflowListItem
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The automation monitor's state machine.
 *
 * ⛔ THE TOGGLE IS **OPTIMISTIC WITH A REVERT**, AND THE CHOICE IS FORCED BY THE RESPONSE SHAPE.
 * `PATCH /api/district/workflows` answers a bare `{success:true}` and echoes nothing about the row
 * it wrote, so there are exactly three designs available: optimistic-with-revert, pessimistic with
 * a spinner on the switch, or a full list re-read after every tap. The third is a second request
 * (two queries server-side, one of them a `distinct` rollup) to learn one boolean this client
 * already knows, and it moves every OTHER row on screen while somebody is looking at one switch.
 * The second leaves the switch visibly stuck under the operator's finger for a whole round trip,
 * which on a phone reads as a control that did not work — and the most common reason to open this
 * screen is to turn something off in a hurry. So the flip is immediate and a failure puts it back,
 * with the server's own sentence beside it.
 *
 * ⚠️ THE REVERT IS SAFE PRECISELY BECAUSE THE WRITE IS IDEMPOTENT AND HAS NO SIDE EFFECT OF ITS
 * OWN. Setting `active` twice writes the same row. What it GATES spends money — the actions a
 * workflow runs — but the PATCH itself sends nothing and bills nothing, which is why an optimistic
 * flip here is not the same trade as an optimistic anything on `messages/send` or `calls/dial`.
 *
 * ⚠️ AND THE REVERTED VALUE IS THE ONE THIS CLIENT LAST READ FROM THE SERVER, not `!attempted`.
 * They are the same for a single tap and they are not for a refused tap on a row someone else
 * changed — reverting to the negation would then invent a third value nobody wrote.
 *
 * ⛔ A VIEWER CANNOT REACH [setActive] AT ALL, AND THE GUARD IS HERE AS WELL AS IN THE SCREEN. The
 * route excludes `viewer` server-side, so the switch is drawn disabled — but a ViewModel that
 * would issue the request if asked is one refactor away from a control that 403s, and the
 * optimistic flip would show the workflow as changed for the second before the refusal landed.
 * [canToggle] is read by both.
 *
 * ⛔ THE CAMPAIGN PAUSE IS **NOT** OPTIMISTIC, AND THE DIFFERENCE FROM THE SWITCH ABOVE IS THE
 * RESPONSE SHAPE RATHER THAN A CHANGE OF MIND. `PATCH workspace/campaign-status` answers in the
 * GET's shape, derived from the object it merged, so the post-write truth arrives with the reply
 * and [confirmCampaignChange] adopts it. Nothing is flipped ahead of the server, nothing is
 * reverted, and a failure leaves the last state the server actually sent on screen. See
 * [requestCampaignChange] for why it is confirmed and the workflow switch is not.
 */
class WorkflowsViewModel(
    private val repository: WorkflowsRepository,
    private val workspaceId: String,
    /**
     * ⚠️ THE EFFECTIVE ROLE FOR THIS CALLER, passed down from the overview rather than read from
     * the workspace list: support access grants "agency" with no membership row, and the list
     * would understate that.
     */
    private val role: WorkspaceRole?,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkflowsUiState())
    val state: StateFlow<WorkflowsUiState> = _state.asStateFlow()

    /** Mirrors the route's `["agency","client"]` guard. See the ⛔ on the class. */
    val canToggle: Boolean = role.allowsMutation()

    init {
        load()
    }

    /**
     * Read the campaign status and the workflow list.
     *
     * ⛔ CLEARS THE RUN CACHE AND COLLAPSES THE OPEN ROW. A reload happens on entry, on a retry and
     * on a session change; carrying a cached history across one would leave an expanded panel
     * showing the runs of a workflow that may no longer be in the list — and after a session change
     * it could be another account's. Collapsing costs one re-fetch on the rare case where the same
     * row is reopened, which is the right side of that trade.
     *
     * ⚠️ TWO REQUESTS, TWO INDEPENDENT STATES, LAUNCHED SEPARATELY. Neither waits for the other and
     * neither can fail the other: the campaign card and the workflow list answer different
     * questions from different databases.
     */
    fun load() {
        _state.value = _state.value.copy(
            campaign = CampaignState.Loading,
            workflows = WorkflowListState.Loading,
            expanded = null,
            runs = emptyMap(),
            toggleFailure = null,
            // ⛔ THE CONFIRMATION IS DROPPED, NOT CARRIED. A dialog surviving a session change
            // would be asking about the PREVIOUS account's campaign, and its submit would then
            // write to whatever workspace this ViewModel now holds.
            //
            // ⚠️ `campaignPending` is deliberately NOT cleared: a request already in flight will
            // still answer, and clearing the flag would re-enable the control underneath it.
            campaignConfirm = null,
            campaignFailure = null,
        )
        viewModelScope.launch {
            val result = repository.campaignStatus(workspaceId)
            _state.value = _state.value.copy(
                campaign = when (result) {
                    is ApiResult.Success -> CampaignState.Ready(result.value)
                    is ApiResult.Failure -> CampaignState.Failed(result.toFailureText())
                },
            )
        }
        viewModelScope.launch {
            val result = repository.workflows(workspaceId)
            _state.value = _state.value.copy(
                workflows = when (result) {
                    is ApiResult.Success -> WorkflowListState.Ready(result.value.workflows)
                    is ApiResult.Failure -> WorkflowListState.Failed(result.toFailureText())
                },
            )
        }
    }

    /**
     * Open or close one workflow's history.
     *
     * ⛔ THE FETCH IS LAZY AND HAPPENS ONCE. Reading every workflow's runs on entry would be one
     * request per row for history nobody has asked to see; re-reading on every expand would punish
     * the natural comparison gesture (open, close, open the next, go back). The cache is cleared
     * only by [load], which is what a retry and a session change do.
     *
     * ⚠️ TAPPING THE OPEN ROW CLOSES IT and does not re-fetch on the way back in.
     */
    fun toggleExpanded(workflowId: String) {
        val current = _state.value
        if (current.expanded == workflowId) {
            _state.value = current.copy(expanded = null)
            return
        }
        _state.value = current.copy(expanded = workflowId)
        if (current.runs[workflowId] == null) fetchRuns(workflowId, offset = 0)
    }

    /**
     * Read the next page of the open workflow's history.
     *
     * ⛔ THE OFFSET IS THE NUMBER OF ROWS ALREADY HELD, NOT A PAGE COUNTER, because the server may
     * have applied a different `limit` than was asked for — it clamps to 1..50 and echoes what it
     * used. Counting pages would drift the moment those two disagreed, and the symptom is silently
     * skipped or duplicated runs rather than an error.
     *
     * ⚠️ A SECOND TAP WHILE ONE IS IN FLIGHT IS DROPPED, not queued: two requests at the same
     * offset would append the same rows twice.
     */
    fun loadMoreRuns(workflowId: String) {
        val history = _state.value.runs[workflowId] ?: return
        if (history.loading || !history.hasMore) return
        fetchRuns(workflowId, offset = history.runs.size)
    }

    /**
     * Turn one workflow on or off, optimistically.
     *
     * ⛔ REFUSED OUTRIGHT FOR A ROLE THE SERVER WOULD REFUSE — see the ⛔ on the class — and
     * refused for a switch already in flight, because a second tap would leave the optimistic
     * value and the eventual revert disagreeing about which way the row ended up.
     */
    fun setActive(workflowId: String, active: Boolean) {
        if (!canToggle) return
        val current = _state.value
        val listState = current.workflows as? WorkflowListState.Ready ?: return
        val previous = listState.workflows.firstOrNull { it.id == workflowId } ?: return
        if (workflowId in current.pendingToggles) return

        _state.value = current.copy(
            workflows = WorkflowListState.Ready(listState.workflows.replacingActive(workflowId, active)),
            pendingToggles = current.pendingToggles + workflowId,
            toggleFailure = null,
        )

        viewModelScope.launch {
            when (val result = repository.setActive(workspaceId, workflowId, active)) {
                is ApiResult.Success -> finishToggle(workflowId, revertTo = null)
                is ApiResult.Failure -> finishToggle(
                    workflowId,
                    // ⚠️ The value this client LAST READ, not `!active`. See the ⚠️ on the class.
                    revertTo = previous.active,
                    failure = result,
                )
            }
        }
    }

    /** ⚠️ Transient by nature, so the screen can dismiss it without a re-read. */
    fun dismissToggleFailure() {
        _state.value = _state.value.copy(toggleFailure = null)
    }

    /**
     * Ask to pause or resume the campaign. Opens the confirmation; writes nothing.
     *
     * ⛔ THE ROLE GUARD IS HERE AS WELL AS ON THE CONTROL, for the reason [setActive]'s is: the
     * route excludes `viewer`, so the button is not drawn — but a ViewModel that would open the
     * dialog if asked is one refactor away from a confirmation whose submit 403s.
     *
     * ⚠️ A REQUEST TO SET THE VALUE IT ALREADY HOLDS IS DROPPED. The write is idempotent, so this
     * is not a safety guard — it is the same call the range chips make: a control that costs a
     * round trip to change nothing is worse than one that does not respond.
     */
    fun requestCampaignChange(enable: Boolean) {
        if (!canToggle) return
        val current = _state.value
        if (current.campaignPending) return
        val ready = current.campaign as? CampaignState.Ready ?: return
        if (ready.status.infiniteSdrEnabled == enable) return
        _state.value = current.copy(
            campaignConfirm = CampaignConfirm(enable),
            campaignFailure = null,
        )
    }

    /**
     * ⚠️ Backing out costs nothing and writes nothing — the dialog is the only thing cleared.
     *
     * ⚠️ AND THERE IS NO `dismissCampaignFailure`, UNLIKE [dismissToggleFailure]. That one exists
     * because the toggle failure is a banner over the whole list with no natural end; the campaign
     * failure sits ON the card whose button clears it, so every route out of it — asking again,
     * confirming again, a retry, a session change — already sets it to null. A dedicated dismiss
     * would be a fifth way to do what the button beside it does.
     */
    fun dismissCampaignConfirm() {
        _state.value = _state.value.copy(campaignConfirm = null)
    }

    /**
     * Commit the confirmed pause or resume.
     *
     * ⛔ NOT OPTIMISTIC, WHICH IS THE OPPOSITE CALL FROM [setActive] AND IS FORCED BY THE SAME
     * THING: the response shape. The workflow toggle answers a bare `{success:true}`, so a flip
     * with a revert is the only design available there. This route answers the READ's shape,
     * derived from the object it merged — so the truth arrives with the reply and there is nothing
     * to guess. Guessing would also be worse here than there: a resume that appeared to work and
     * had not would tell an operator their outbound engine is running when it is not.
     *
     * ⛔ AND IT READS THE TARGET OUT OF THE CONFIRMATION, not out of the card. Between opening the
     * dialog and confirming it, a background reload can have replaced the status underneath —
     * re-deriving `!current` at this point would write whichever value that left behind rather
     * than the one the sentence on screen described.
     */
    fun confirmCampaignChange() {
        if (!canToggle) return
        val current = _state.value
        val confirm = current.campaignConfirm ?: return
        if (current.campaignPending) return

        _state.value = current.copy(
            campaignConfirm = null,
            campaignPending = true,
            campaignFailure = null,
        )

        viewModelScope.launch {
            val result = repository.setCampaignEnabled(workspaceId, confirm.enable)
            val settled = _state.value
            _state.value = settled.copy(
                campaign = when (result) {
                    is ApiResult.Success -> CampaignState.Ready(result.value)
                    // ⛔ THE CARD KEEPS THE LAST STATE THE SERVER SENT. Blanking it — or showing
                    // the value that was refused — would answer "is my campaign running" with
                    // something nobody wrote.
                    is ApiResult.Failure -> settled.campaign
                },
                campaignPending = false,
                campaignFailure = (result as? ApiResult.Failure)?.toFailureText(),
            )
        }
    }

    private fun fetchRuns(workflowId: String, offset: Int) {
        val existing = _state.value.runs[workflowId] ?: RunHistory()
        _state.value = _state.value.copy(
            runs = _state.value.runs + (workflowId to existing.copy(loading = true, failure = null)),
        )
        viewModelScope.launch {
            val result = repository.runs(
                workspaceId = workspaceId,
                workflowId = workflowId,
                limit = RUNS_PAGE_SIZE,
                offset = offset,
            )
            val held = _state.value.runs[workflowId] ?: RunHistory()
            val next = when (result) {
                is ApiResult.Success -> held.copy(
                    // ⛔ APPEND, NEVER REPLACE, and `offset == 0` is the only case that starts
                    // fresh. A "load more" that assigned would throw away every page before it.
                    runs = if (offset == 0) result.value.runs else held.runs + result.value.runs,
                    total = result.value.total,
                    hasMore = result.value.hasMore,
                    loading = false,
                    failure = null,
                )
                // ⛔ THE ROWS ALREADY FETCHED SURVIVE. A failed third page must not discard the
                // first two, which are a correct answer the operator is reading.
                is ApiResult.Failure -> held.copy(loading = false, failure = result.toFailureText())
            }
            _state.value = _state.value.copy(runs = _state.value.runs + (workflowId to next))
        }
    }

    /**
     * @param revertTo null when the write landed; otherwise the value to put back.
     */
    private fun finishToggle(
        workflowId: String,
        revertTo: Boolean?,
        failure: ApiResult.Failure? = null,
    ) {
        val current = _state.value
        val listState = current.workflows
        _state.value = current.copy(
            workflows = if (revertTo != null && listState is WorkflowListState.Ready) {
                WorkflowListState.Ready(listState.workflows.replacingActive(workflowId, revertTo))
            } else {
                listState
            },
            pendingToggles = current.pendingToggles - workflowId,
            toggleFailure = failure?.toFailureText(),
        )
    }

    companion object {
        fun factory(
            repository: WorkflowsRepository,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WorkflowsViewModel(repository, workspaceId, role) as T
        }
    }
}

/**
 * Replace one workflow's `active` flag, leaving every other row identical.
 *
 * ⚠️ A TOP-LEVEL PRIVATE EXTENSION RATHER THAN A METHOD, because it touches nothing on the
 * ViewModel and the class sits at detekt's 11-function ceiling — the same reason the analytics
 * chart maths lives outside its DrawScope. Moving it back in is a lint failure, not a style
 * preference.
 */
private fun List<WorkflowListItem>.replacingActive(
    workflowId: String,
    active: Boolean,
): List<WorkflowListItem> = map { if (it.id == workflowId) it.copy(active = active) else it }
