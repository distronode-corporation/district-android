package com.distronode.districtai.ui.report

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.core.model.SupportRequestDraft
import com.distronode.districtai.core.model.SupportRequestFiling
import com.distronode.districtai.core.model.SupportRequestKind
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where one report stands. */
sealed interface ReportState {
    data object Idle : ReportState
    data object Sending : ReportState

    /**
     * Raised. ⚠️ A SENTENCE, NOT A BOOLEAN: filed, already reported and being opened are three
     * successes, and reporting the second as a failure invites a second ticket.
     */
    data class Done(@StringRes val confirmation: Int) : ReportState

    /** Not raised. The form stays armed under it, with the note still in it. */
    data class Failed(val failure: FailureText) : ReportState
}

/**
 * Flagging AI-generated content, on the support desk (iOS `ReportContentModel`, Windows
 * `report.rs`).
 *
 * ⛔ IT RIDES THE EXISTING SUPPORT ROUTE, `kind: problem`, AND NOTHING MAY BE ADDED TO THE PAYLOAD:
 * the desk accepts only the request type's own fields, and an unknown one is a 400 raised after the
 * server has claimed the row. So what was reported goes in the MESSAGE.
 *
 * ⛔ [idempotencyKey] IS MINTED ONCE PER OPENED DIALOG, NEVER PER ATTEMPT (the dialog keys this
 * ViewModel by it), which is what makes a retry safe: the server answers a re-used key with
 * `deduplicated: true` instead of a second ticket in a human's queue.
 *
 * ⚠️ THE SUPPORT ROUTES EXCLUDE `viewer`, so the screens offer Report only to a role that may write.
 */
class ReportViewModel(
    private val support: SupportRepository,
    private val workspaceId: String,
    private val idempotencyKey: String,
) : ViewModel() {

    private val _state = MutableStateFlow<ReportState>(ReportState.Idle)
    val state: StateFlow<ReportState> = _state.asStateFlow()

    /**
     * File the report. ⚠️ The note is optional: the message says when none was added. A second tap
     * while one is on its way does nothing.
     */
    fun submit(subject: String, message: String) {
        if (_state.value == ReportState.Sending) return
        _state.value = ReportState.Sending
        viewModelScope.launch {
            val draft = SupportRequestDraft(SupportRequestKind.PROBLEM, subject, message)
            _state.value = when (val result = support.createRequest(workspaceId, draft, idempotencyKey)) {
                is ApiResult.Success -> ReportState.Done(confirmation(result.value))
                // ⚠️ THE SERVER'S OWN SENTENCE: a 429 and a 503 name their remedy.
                is ApiResult.Failure -> ReportState.Failed(result.toFailureText())
            }
        }
    }

    companion object {
        /** The confirmation of a report raised, as iOS words it. */
        @StringRes
        fun confirmation(filing: SupportRequestFiling): Int = when (filing) {
            is SupportRequestFiling.Filed -> R.string.report_accepted
            SupportRequestFiling.Deduplicated -> R.string.report_already_reported
            SupportRequestFiling.Pending -> R.string.report_pending
        }

        fun factory(
            support: SupportRepository,
            workspaceId: String,
            idempotencyKey: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportViewModel(support, workspaceId, idempotencyKey) }
        }
    }
}
