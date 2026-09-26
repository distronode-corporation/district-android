package com.distronode.districtai.ui.calls

import com.distronode.districtai.R
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.model.CallDetailResponse
import com.distronode.districtai.core.model.CallTranscriptResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.testCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: TestDistrictApi) =
        CallDetailViewModel(CallsRepository(api), workspaceId = "ws-1", callId = "c1")

    private fun apiWithCall(recordingUrl: String? = null) = TestDistrictApi().apply {
        detailResult = ApiResult.Success(
            CallDetailResponse(success = true, call = testCall(recordingUrl = recordingUrl)),
        )
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
        val api = TestDistrictApi().apply { detailResult = ApiResult.NotFound("Call not found") }
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
        val api = TestDistrictApi().apply { detailResult = ApiResult.Success(CallDetailResponse(success = true)) }
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

    // ── Recording ────────────────────────────────────────────────────────────

    @Test
    fun `resolves a recording url and hands it to the caller`() = runTest(dispatcher) {
        // ⛔ Resolved at playback and never stored: the URL is a short-lived presigned link, and a
        // cached one expires and then fails inside the player as if the recording were corrupt.
        val api = apiWithCall(recordingUrl = "https://legacy.test/a.mp3")
        val vm = viewModel(api)
        advanceUntilIdle()

        var handed: String? = null
        vm.resolveRecording { handed = it }
        advanceUntilIdle()

        assertEquals("https://recordings.test/x.mp3", handed)
        assertEquals(listOf("ws-1" to "c1"), api.recordingRequests)
        // Returns to Idle so the button is offerable again.
        assertEquals(RecordingState.Idle, (vm.state.value as CallDetailUiState.Content).recording)
    }

    @Test
    fun `a call with no recording reports Absent rather than an error`() = runTest(dispatcher) {
        // Ordinary for a missed call, so it must not read as a failure.
        val api = apiWithCall().apply { recordingResult = ApiResult.NotFound("Recording not found") }
        val vm = viewModel(api)
        advanceUntilIdle()

        var handed: String? = null
        vm.resolveRecording { handed = it }
        advanceUntilIdle()

        assertEquals(null, handed)
        assertEquals(RecordingState.Absent, (vm.state.value as CallDetailUiState.Content).recording)
    }

    @Test
    fun `a recording failure is surfaced without losing the call`() = runTest(dispatcher) {
        val api = apiWithCall().apply { recordingResult = ApiResult.HttpFailure(502, "Bad gateway") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.resolveRecording { }
        advanceUntilIdle()

        val state = vm.state.value as CallDetailUiState.Content
        assertEquals("c1", state.call.id)
        assertTrue(state.recording is RecordingState.Failed)
    }

    @Test
    fun `only offers playback when the row suggests a recording exists`() = runTest(dispatcher) {
        val without = viewModel(apiWithCall(recordingUrl = null))
        advanceUntilIdle()
        assertFalse((without.state.value as CallDetailUiState.Content).mayHaveRecording)

        val with = viewModel(apiWithCall(recordingUrl = "https://legacy.test/a.mp3"))
        advanceUntilIdle()
        assertTrue((with.state.value as CallDetailUiState.Content).mayHaveRecording)
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

    // ── Taps that land before the call, twice, or across a reload ────────────

    @Test
    fun `neither the transcript nor the recording is requested before the call has loaded`() = runTest(dispatcher) {
        val api = apiWithCall(recordingUrl = "https://legacy.test/a.mp3")
        val vm = viewModel(api)
        var handed: String? = null

        vm.loadTranscript()
        vm.resolveRecording { handed = it }
        advanceUntilIdle()

        assertTrue(api.transcriptRequests.isEmpty())
        assertTrue(api.recordingRequests.isEmpty())
        assertEquals(null, handed)
    }

    @Test
    fun `a double tap on play resolves the recording once`() = runTest(dispatcher) {
        // ⛔ Two resolves would open two players and mint two presigned URLs for one action.
        val api = apiWithCall(recordingUrl = "https://legacy.test/a.mp3")
        val vm = viewModel(api)
        advanceUntilIdle()
        val handed = mutableListOf<String>()

        vm.resolveRecording { handed += it }
        vm.resolveRecording { handed += it }
        advanceUntilIdle()

        assertEquals(1, api.recordingRequests.size)
        assertEquals(listOf("https://recordings.test/x.mp3"), handed)
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

    @Test
    fun `a recording resolved across a reload still plays, and leaves the fresh call's state alone`() =
        runTest(dispatcher) {
            val api = apiWithCall(recordingUrl = "https://legacy.test/a.mp3")
            val vm = viewModel(api)
            advanceUntilIdle()
            var handed: String? = null

            vm.resolveRecording { handed = it }
            vm.load()
            advanceUntilIdle()

            assertEquals("https://recordings.test/x.mp3", handed)
            assertEquals(RecordingState.Idle, (vm.state.value as CallDetailUiState.Content).recording)
        }
}
