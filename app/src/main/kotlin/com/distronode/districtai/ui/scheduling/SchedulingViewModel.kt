package com.distronode.districtai.ui.scheduling

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.model.SchedulingEnableResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Scheduling screen's state machine: one read, one provision, two hand-offs.
 *
 * ⛔ THE PRIMARY HAND-OFF IS [openDashboard] AND [openScheduler] IS THE FALLBACK. The scheduler
 * fork's `/admin/` console is being switched off region by region, and a shipped Play build cannot
 * be reverted — so an installed copy that only knew the console would lose scheduling silently on
 * the day of a flip. Both ship for one release: the dashboard hand-off leads, the console hand-off
 * sits behind it, and the second is removed once every region is flipped.
 *
 * ⛔ NOTHING HERE RETRIES `enable`, AND NOTHING HERE MAY LEARN TO. One call reaches two third
 * parties (a tenancy at the scheduler and a DNS record at Cloudflare) and its 5-per-hour brake
 * FAILS OPEN, so a retry loop is somebody else's API quota and a zone full of records. The 202 is
 * telling the client to re-read the status, which is what [enable] does, once, on a human press.
 *
 * ⛔ AND NOTHING HERE POLLS. See [offersRefresh] for why the iOS client's ten-second loop is not
 * ported: the re-read it wants is offered as a button in exactly the two states where it could
 * change the answer, and a timer whose lifetime has to be tied to both screen visibility and
 * process foregroundedness is a background request away from being wrong.
 *
 * ⛔ THE HAND-OFF URL IS ANSWERED THROUGH A CALLBACK AND NEVER STORED IN THIS STATE. It carries a
 * 60-second single-use token, so parking it on a `StateFlow` would leave a live credential in
 * memory long after the browser closed, would survive a rotation, and would make a second press
 * cheap to serve from a value that is already spent. Same shape as `DevicesViewModel.revokeDevice`
 * handing its sign-out back to the call site.
 *
 * ⚠️ NO ROLE ARGUMENT, WHICH IS UNUSUAL FOR A WORKSPACE-SCOPED SCREEN IN THIS APP. `canManage`
 * arrives on every status read, so the server is what decides whether Enable is drawn — see
 * [offersEnable]. A constructor role would be a second, weaker copy of an answer already in hand.
 */
class SchedulingViewModel(
    private val repository: SchedulingRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingUiState())
    val state: StateFlow<SchedulingUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * Read the tenancy's state.
     *
     * @param refreshing ⚠️ TRUE KEEPS THE CARD ON SCREEN. The re-read after an enable, and the one
     *   a session change triggers, would otherwise blank the one card this screen has in order to
     *   redraw almost the same thing — and the flash lands exactly when somebody is watching to
     *   see whether their booking page came up.
     */
    fun load(refreshing: Boolean = false) {
        if (!refreshing) _state.value = _state.value.copy(screen = SchedulingScreenState.Loading)
        viewModelScope.launch {
            _state.value = _state.value.copy(screen = screenState())
        }
    }

    /**
     * Provision the tenancy, once, on an explicit press.
     *
     * ⛔ THE RE-READ IS UNCONDITIONAL, INCLUDING AFTER A REFUSAL. The 202 says the provision has
     * already run and the row is the truth; a 403 means the allowlist answer this screen is
     * holding is stale, and a 429 means somebody else's press may have moved the row since.
     * Trusting the POST's own view would leave the card describing a state that no longer exists.
     */
    fun enable() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            val notice = enableNotice(repository.enable(workspaceId))
            _state.value = _state.value.copy(busy = false, notice = notice)
            load(refreshing = true)
        }
    }

    /**
     * Mint a signed-in browser onto the dashboard's scheduling pages, for one press. THE PRIMARY
     * ACTION.
     *
     * @param onHandOff invoked with the URL when there is one. ⛔ It is spent immediately by the
     *   caller and never kept: 60 seconds, one use, and this one redeems into a SESSION COOKIE
     *   rather than a sign-in to somebody else's console. When the mint fails, this is NOT called
     *   and the reason lands in [SchedulingUiState.notice] instead, so the call site never has to
     *   invent copy for a failure it did not classify.
     */
    fun openDashboard(onHandOff: (String) -> Unit) {
        if (_state.value.openingDashboard) return
        _state.value = _state.value.copy(openingDashboard = true, notice = null)
        viewModelScope.launch {
            when (val result = repository.dashboardHandOff(workspaceId)) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(openingDashboard = false)
                    onHandOff(result.value)
                }
                is ApiResult.Failure ->
                    _state.value = _state.value.copy(
                        openingDashboard = false,
                        notice = dashboardNotice(result),
                    )
            }
        }
    }

    /**
     * Mint a hand-off into the scheduler's own admin, for one press. THE SECONDARY ACTION, and the
     * one that is on its way out.
     *
     * ⛔ A **410** HERE LATCHES [SchedulingUiState.consoleRetired] AND TAKES THE BUTTON AWAY. The
     * refusal is a property of the REGION, not of this press: once its `ADMIN_SPA` switch is
     * flipped, every later attempt in this session gets the identical answer. Leaving the control
     * on screen would offer a press whose only possible outcome is the same sentence again.
     *
     * @param onHandOff see [openDashboard]. Same rule, different far end.
     */
    fun openScheduler(onHandOff: (String) -> Unit) {
        if (_state.value.openingConsole) return
        _state.value = _state.value.copy(openingConsole = true, notice = null)
        viewModelScope.launch {
            when (val result = repository.schedulerHandOff(workspaceId)) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(openingConsole = false)
                    onHandOff(result.value)
                }
                is ApiResult.Failure ->
                    _state.value = _state.value.copy(
                        openingConsole = false,
                        // ⛔ THE LATCH ONLY EVER TURNS ON. `|| ` rather than `=` so an ordinary
                        // failure after a retirement cannot bring the dead button back.
                        consoleRetired = _state.value.consoleRetired || result.isConsoleRetired(),
                        notice = handOffNotice(result),
                    )
            }
        }
    }

    /** ⚠️ The notice is transient by nature, so the screen can dismiss it without a re-read. */
    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /**
     * ⛔ A FAILED READ IS A FAILURE, NEVER AN EMPTY CARD. `tenant == null` is a legitimate answer
     * on this surface, so the two would otherwise look identical, a conflation that reads to a
     * user like account loss.
     */
    private suspend fun screenState(): SchedulingScreenState =
        when (val result = repository.status(workspaceId)) {
            is ApiResult.Success -> SchedulingScreenState.Ready(result.value)
            is ApiResult.Failure -> SchedulingScreenState.Failed(result.toFailureText())
        }

    /**
     * ⚠️ ONE COMPANION, WITH THE COPY MAPPERS PRIVATE INSIDE IT. Kotlin permits exactly one per
     * class, so the factory and the three `when`s that choose a sentence share it; the mappers are
     * `internal` rather than `private` only so a ViewModel test can assert WHICH sentence a
     * refusal produces without driving a whole screen to find out.
     */
    companion object {

        /**
         * ⛔ `ok: false` INSIDE A 202 IS A SUCCESSFUL RESPONSE CARRYING THE ONE SENTENCE THAT SAYS
         * WHAT WENT WRONG. Promoting it to a failure would throw that sentence away; the fallback
         * exists only for a server that sent neither.
         *
         * ⚠️ THE SERVER'S SENTENCE IS A [UiText.Literal] AND OURS IS A [UiText.Resource], which is
         * the split that type exists for: the provisioner's classified message is more specific
         * than anything this client could infer, and it is not ours to translate.
         */
        internal fun enableNotice(result: ApiResult<SchedulingEnableResponse>): UiText? = when (result) {
            is ApiResult.Success ->
                if (result.value.ok) {
                    null
                } else {
                    result.value.error?.let { UiText.Literal(it) }
                        ?: UiText.Resource(R.string.scheduling_enable_failed_fallback)
                }
            is ApiResult.Failure -> enableFailureNotice(result)
        }

        /**
         * ⛔ 403 AND 429 ARE THE TWO REFUSALS THAT NEVER REACHED THE PROVISIONER, and each needs
         * its own sentence. A 403 here is the ALLOWLIST, not the role — the shared mapping's
         * generic permission wording would send an owner looking for a colleague to ask — and a
         * 429 is five presses in an hour for this workspace, shared across everybody in it, which
         * is the one failure on this screen that genuinely resolves by waiting.
         */
        internal fun enableFailureNotice(failure: ApiResult.Failure): UiText = when (failure) {
            is ApiResult.Forbidden -> UiText.Resource(R.string.scheduling_not_eligible)
            is ApiResult.RateLimited -> UiText.Resource(R.string.scheduling_too_many_attempts)
            else -> failure.toFailureText().message
        }

        /**
         * ⛔ 410 `scheduler_console_retired` SHOWS THE SERVER'S SENTENCE VERBATIM, AND THE BRANCH
         * IS ON `code` RATHER THAN ON THE SENTENCE. Two halves, two jobs: `error` is product copy
         * written to be shown to a person — a fixed sentence with no hostname in it, worded that
         * way precisely so a client can pin it — and `code` is the machine key. Branching on the
         * prose would break the day somebody improved the wording, and inventing our own sentence
         * would put a second, drifting copy of a product decision in an app that ships on its own
         * release schedule.
         *
         * ⚠️ AND IT IS A [UiText.Literal] FOR THE SAME REASON THE PROVISIONER'S REFUSAL IS: it is
         * the server's copy, more specific than anything this build could infer, and not ours to
         * translate. ⛔ No retry is offered anywhere near it — the notice carries a Dismiss and
         * nothing else — because the console is not coming back for this region, and the sentence
         * itself points at the primary action.
         *
         * ⚠️ 409 IS NOT A FAULT EITHER, for a different reason. The SSO route answers it when the
         * tenancy is not `ready`, which is the honest state of a workspace mid-provision.
         * Everything else, 500 and 503 included, falls to the shared mapping.
         */
        internal fun handOffNotice(failure: ApiResult.Failure): UiText = when {
            failure.isConsoleRetired() ->
                UiText.Literal((failure as ApiResult.HttpFailure).message)
            failure is ApiResult.HttpFailure && failure.status == HTTP_CONFLICT ->
                UiText.Resource(R.string.scheduling_not_ready_yet)
            else -> failure.toFailureText().message
        }

        /**
         * ⛔ THE **BEARER**, NOT THE ROLE, AND NOT THE ALLOWLIST — WHICH IS WHY THIS IS A SECOND
         * MAPPER RATHER THAN A CALL TO [enableFailureNotice]. On the enable route a 403 is the
         * workspace's admission to the feature; on the hand-off route it is the token itself,
         * missing or unverifiable, answered with a bare `{"error":"Forbidden"}`. Telling
         * somebody their workspace is not admitted when their session has lapsed would send them
         * looking for a colleague who cannot help.
         *
         * ⛔ AND THE 429 SENTENCE IS OURS BECAUSE THE SERVER'S IS A MACHINE TOKEN. This route's
         * body is `{"error":"rate_limited","message":"…"}` — the human half is in `message`, which
         * [com.distronode.districtai.core.network.ApiErrorEnvelope] does not model, so
         * `RateLimited.message` carries the snake_case string and the shared mapping (which renders
         * it verbatim) would print `rate_limited` on a customer's phone. ⚠️ It is also a DIFFERENT
         * limit from the enable route's: ten a minute for the ACCOUNT, not five an hour for the
         * workspace, so it gets its own wording rather than reusing
         * `scheduling_too_many_attempts`, which would be a sentence about a budget that was never
         * spent.
         */
        internal fun dashboardNotice(failure: ApiResult.Failure): UiText = when (failure) {
            is ApiResult.Forbidden -> UiText.Resource(R.string.failure_signed_out)
            is ApiResult.RateLimited -> UiText.Resource(R.string.scheduling_handoff_rate_limited)
            else -> failure.toFailureText().message
        }

        private const val HTTP_CONFLICT = 409
        private const val HTTP_GONE = 410

        /**
         * ⛔ THE STATUS ALONE IS NOT ENOUGH AND THE CODE ALONE IS NOT ENOUGH. A 410 from an edge
         * proxy carries no `code` and means nothing of the kind; a `code` on any other status is
         * not this refusal either. Both, together, are the only signal that this region's console
         * is gone.
         */
        internal fun ApiResult.Failure.isConsoleRetired(): Boolean =
            this is ApiResult.HttpFailure &&
                status == HTTP_GONE &&
                code == CODE_CONSOLE_RETIRED

        /**
         * ⛔ BYTE FOR BYTE WHAT THE WEBSITE SENDS. It is a wire contract, not a label: the sso
         * route answers exactly this string, and a client that spelled it differently would fall
         * through to the generic failure and show "something went wrong" for a refusal the server
         * took the trouble to explain.
         */
        internal const val CODE_CONSOLE_RETIRED: String = "scheduler_console_retired"

        fun factory(
            repository: SchedulingRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SchedulingViewModel(repository, workspaceId) as T
        }
    }
}
