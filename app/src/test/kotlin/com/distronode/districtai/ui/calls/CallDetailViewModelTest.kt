package com.distronode.districtai.ui.calls

import com.distronode.districtai.R
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.model.CallDetailResponse
import com.distronode.districtai.core.model.CallTranscriptResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.resourceIdOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.testCall
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class CallDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private fun viewModel(api: FakeDistrictApi) =
        CallDetailViewModel(CallsRepository(api), workspaceId = "ws-1", callId = "c1")

    private fun apiWithCall() = FakeDistrictApi().apply {
        detailResult = ApiResult.Success(CallDetailResponse(success = true, call = testCall()))
    }

    // ── Detail ───────────────────────────────────────────────────────────────

    @Test
    fun `fetches the call by id rather than receiving the row`() = runTest(dispatcher) {
        // ⚠️ This is what makes the screen survive process death and be openable from a push deep
        // link, where an id is all the app has.
        val api = apiWithCall()
        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(listOf("ws-1" to "c1"), api.detailRequests)
        assertEquals("c1", (vm.state.value as CallDetailUiState.Content).call.id)
    }

    @Test
    fun `a 404 is reported without implying the id was wrong`() = runTest(dispatcher) {
        // ⚠️ The server reads by id and checks ownership afterwards, so another tenant's id is
        // indistinguishable from a missing one. The message therefore comes from the server rather
        // than being invented as "no such call".
        val api = FakeDistrictApi().apply { detailResult = ApiResult.NotFound("Call not found") }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = vm.state.value as CallDetailUiState.Failed
        // ⚠️ `literalOrNull`, because a server-authored message is deliberately a `UiText.Literal`
        // and not a translatable resource — see [UiText]. Asserting on the wording here is what
        // proves the server's more specific text survived instead of being replaced by ours.
        assertEquals("Call not found", state.failure.message.literalOrNull)
        assertFalse("a missing call is not retryable", state.failure.retryable)
    }

    @Test
    fun `a success carrying no call is a malformed response, not an empty state`() = runTest(dispatcher) {
        // ⛔ Absence is a 404. A 2xx with no `call` is a server bug, and reporting it as "not found"
        // would hide it behind an ordinary empty screen.
        val api = FakeDistrictApi().apply { detailResult = ApiResult.Success(CallDetailResponse(success = true)) }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = vm.state.value as CallDetailUiState.Failed
        assertTrue(
            "should point at updating the app, was '${state.failure.message}'",
            state.failure.message.resourceIdOrNull == R.string.failure_unexpected_response,
        )
    }

    // ── Transcript ───────────────────────────────────────────────────────────

    @Test
    fun `does not fetch the transcript until asked`() = runTest(dispatcher) {
        // ⚠️ Transcripts are large enough that the contact timeline stopped embedding them, so this
        // screen must not fetch one just by opening.
        val api = apiWithCall()
        viewModel(api)
        advanceUntilIdle()

        assertTrue("opening the screen must not fetch a transcript", api.transcriptRequests.isEmpty())
    }

    @Test
    fun `fetches the transcript on request`() = runTest(dispatcher) {
        val api = apiWithCall()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadTranscript()
        advanceUntilIdle()

        assertEquals(listOf("ws-1" to "c1"), api.transcriptRequests)
        val state = vm.state.value as CallDetailUiState.Content
        assertEquals(TranscriptState.Loaded("Agent: hello."), state.transcript)
    }

    @Test
    fun `does not refetch a transcript it already has`() = runTest(dispatcher) {
        // ⛔ Guards against a recomposition-driven fetch loop: the composable calls this from an
        // event handler, but a future `LaunchedEffect` would call it on every recomposition.
        val api = apiWithCall()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadTranscript()
        advanceUntilIdle()
        vm.loadTranscript()
        vm.loadTranscript()
        advanceUntilIdle()

        assertEquals("exactly one request", 1, api.transcriptRequests.size)
    }

    @Test
    fun `an empty transcript is Absent rather than an empty string`() = runTest(dispatcher) {
        // ⚠️ The handler sends "" for a call with no transcript — a null check would never fire, and
        // rendering "" would leave a labelled card with nothing in it.
        val api = apiWithCall().apply {
            transcriptResult = ApiResult.Success(CallTranscriptResponse(success = true, transcript = ""))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadTranscript()
        advanceUntilIdle()

        assertEquals(
            TranscriptState.Absent,
            (vm.state.value as CallDetailUiState.Content).transcript,
        )
    }

    @Test
    fun `a whitespace-only transcript is also Absent`() = runTest(dispatcher) {
        val api = apiWithCall().apply {
            transcriptResult = ApiResult.Success(CallTranscriptResponse(success = true, transcript = "   \n"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadTranscript()
        advanceUntilIdle()

        assertEquals(
            TranscriptState.Absent,
            (vm.state.value as CallDetailUiState.Content).transcript,
        )
    }

    @Test
    fun `a transcript failure does not destroy the call already on screen`() = runTest(dispatcher) {
        val api = apiWithCall().apply {
            transcriptResult = ApiResult.HttpFailure(500, "Boom")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadTranscript()
        advanceUntilIdle()

        val state = vm.state.value as CallDetailUiState.Content
        assertEquals("c1", state.call.id)
        assertTrue(state.transcript is TranscriptState.Failed)
    }

    @Test
    fun `retrying reloads the call`() = runTest(dispatcher) {
        val api = apiWithCall()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.load()
        advanceUntilIdle()

        assertEquals(2, api.detailRequests.size)
    }

    // ── Taps that land before the call, or across a reload ─────────────────────

    @Test
    fun `the transcript is not requested before the call has loaded`() = runTest(dispatcher) {
        val api = apiWithCall()
        val vm = viewModel(api)

        vm.loadTranscript()
        advanceUntilIdle()

        assertTrue(api.transcriptRequests.isEmpty())
    }

    @Test
    fun `a transcript that lands after a reload began is dropped, not written over the fresh call`() =
        runTest(dispatcher) {
            val api = apiWithCall().apply {
                transcriptResult = ApiResult.Success(CallTranscriptResponse(success = true, transcript = "Hello."))
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            vm.loadTranscript()
            vm.load()
            advanceUntilIdle()

            assertEquals(TranscriptState.Idle, (vm.state.value as CallDetailUiState.Content).transcript)
        }
}
