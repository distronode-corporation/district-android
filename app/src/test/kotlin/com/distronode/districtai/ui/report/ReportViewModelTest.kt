package com.distronode.districtai.ui.report

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.core.model.SupportRequestCreateResponse
import com.distronode.districtai.core.model.SupportRequestDraft
import com.distronode.districtai.core.model.SupportRequestKind
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeSupportApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val api = FakeSupportApi()
    private val viewModel by lazy { ReportViewModel(SupportRepository(api), "ws-1", "key-1") }

    @Test
    fun `a report is a problem request carrying the dialog's key`() = runTest(dispatcher) {
        viewModel.submit("Report: AI-generated content", "P\nCall: c1\n\nN")
        assertEquals(ReportState.Sending, viewModel.state.value)
        // A second tap while it is on its way does nothing.
        viewModel.submit("again", "again")
        advanceUntilIdle()

        assertEquals(
            listOf(SupportRequestDraft(SupportRequestKind.PROBLEM, "Report: AI-generated content", "P\nCall: c1\n\nN")),
            api.createDrafts,
        )
        assertEquals(listOf<String?>("key-1"), api.createKeys)
        assertEquals(listOf("ws-1"), api.workspaceIds)
        assertEquals(ReportState.Done(R.string.report_accepted), viewModel.state.value)
    }

    @Test
    fun `a failure keeps the form armed, and the retry reuses the key`() = runTest(dispatcher) {
        api.createResult = ApiResult.RateLimited("Reply on your open request instead.")
        viewModel.submit("S", "M")
        advanceUntilIdle()
        assertTrue(viewModel.state.value is ReportState.Failed)

        api.createResult = ApiResult.Success(SupportRequestCreateResponse(success = true, deduplicated = true))
        viewModel.submit("S", "M")
        advanceUntilIdle()
        assertEquals(listOf<String?>("key-1", "key-1"), api.createKeys)
        assertEquals(ReportState.Done(R.string.report_already_reported), viewModel.state.value)
    }

    @Test
    fun `each filing has its own sentence`() {
        assertEquals(
            R.string.report_accepted,
            ReportViewModel.confirmation(com.distronode.districtai.core.model.SupportRequestFiling.Filed("DA-1"))
        )
        assertEquals(
            R.string.report_pending,
            ReportViewModel.confirmation(com.distronode.districtai.core.model.SupportRequestFiling.Pending)
        )
        assertEquals(
            R.string.report_already_reported,
            ReportViewModel.confirmation(com.distronode.districtai.core.model.SupportRequestFiling.Deduplicated),
        )
    }
}
