package com.distronode.districtai.core.network

import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The body read and the decode must not happen on the main thread.
 *
 * ⛔ THIS IS A REGRESSION TEST FOR A PROCESS-KILLING CRASH, NOT A STYLE PREFERENCE. Nothing in
 * this client used to switch dispatchers, and `Call.await` resumes on the CALLER's — which for
 * `viewModelScope` and for Paging is `Dispatchers.Main.immediate`. So after the suspension point
 * the app was back on main for `response.body.string()`, a blocking socket read for anything okio
 * had not already buffered (roughly any body over 8 KiB). Android installs
 * `PENALTY_DEATH_ON_NETWORK` on the main thread, which turns that read into a
 * `NetworkOnMainThreadException` — a RuntimeException, which the old `catch (IOException)` did not
 * catch, so it propagated out of a ViewModel's coroutine and KILLED THE PROCESS. The overview
 * (8 calls with transcripts) and the call log's 75-row first page both clear the threshold on
 * their first load, so this was every cold start on both of the app's main screens.
 *
 * ⛔ THE HARNESS USES A REAL SECOND THREAD, NOT A `TestDispatcher`. `runTest`'s scheduler is
 * single-threaded but it is not a distinct THREAD from the test's, so "did this run on main"
 * cannot be observed through it — every assertion would pass vacuously. `Dispatchers.setMain` with
 * a named single-thread executor gives the main thread an identity that can be compared against.
 *
 * ⚠️ WHAT THIS CANNOT DO IS ASSERT ON StrictMode. `PENALTY_DEATH_ON_NETWORK` is Android
 * framework behaviour and there is no StrictMode on the JVM, so the crash itself is not
 * reproducible here — only its precondition, which is the code running on the main thread at all.
 * The thread identity is therefore the assertion. Verified against the reasoning above rather than
 * against a device.
 */
class MainThreadSafetyTest {

    @Serializable
    data class Payload(val value: String, val count: Int = 0)

    /**
     * Records the thread the decode actually ran on.
     *
     * ⚠️ WRAPS THE SERIALIZER RATHER THAN THE DISPATCHER, deliberately. A dispatcher can be
     * observed for "something was submitted to me", but only this proves that the specific work
     * that used to crash — the parse, immediately after the one-shot body read — happened
     * somewhere other than main. It is the innermost observable point in the call.
     */
    private class ThreadRecordingStrategy<T>(
        private val delegate: DeserializationStrategy<T>,
    ) : DeserializationStrategy<T> {

        @Volatile
        var thread: String? = null
            private set

        override val descriptor: SerialDescriptor get() = delegate.descriptor

        override fun deserialize(decoder: Decoder): T {
            thread = baseThreadName()
            return delegate.deserialize(decoder)
        }
    }

    /**
     * Records the thread every block dispatched through it actually ran on.
     *
     * ⚠️ THE OBSERVATION POINT OF LAST RESORT, for paths with nothing else to instrument. It
     * cannot distinguish which statement ran where — only that the client handed work to this
     * dispatcher and that main is not among the threads that executed it.
     */
    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {

        private val recorded = Collections.synchronizedSet(mutableSetOf<String>())

        val threads: Set<String> get() = recorded.toSet()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                recorded.add(baseThreadName())
                block.run()
            }
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var refreshApi: FakeRefreshApi
    private lateinit var fakeMain: ExecutorService
    private lateinit var fakeIo: ExecutorService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        refreshApi = FakeRefreshApi().apply { rotating() }
        fakeMain = singleThread(MAIN_THREAD_NAME)
        fakeIo = singleThread(IO_THREAD_NAME)
        // Stands in for Android's main looper, so `Dispatchers.Main` in this JVM has a real,
        // identifiable thread — exactly what viewModelScope would dispatch to on a device.
        Dispatchers.setMain(fakeMain.asCoroutineDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fakeMain.shutdownNow()
        fakeIo.shutdownNow()
        server.close()
    }

    private fun singleThread(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }

    private fun client(io: CoroutineDispatcher? = null) = if (io == null) {
        // ⚠️ NO `io` ARGUMENT: exercises the production default. This is the configuration that
        // ships, so it is the one that has to be safe.
        testApiClient(server, refreshApi)
    } else {
        DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = DistrictHttp.client(),
            tokens = signedInCoordinator(refreshApi),
            io = io,
        )
    }

    /** A body comfortably past the ~8 KiB okio buffers eagerly, so the read must hit the socket. */
    private fun largeBody(): String =
        """{"value":"${"transcript ".repeat(LARGE_BODY_REPEATS)}","count":75}"""

    // ── The regression ───────────────────────────────────────────────────────

    @Test
    fun `the harness really does put the caller on the main thread`() = runBlocking(Dispatchers.Main) {
        // ⛔ WITHOUT THIS THE REST OF THE FILE COULD PASS VACUOUSLY. If the fake main dispatcher
        // were not in effect, "the decode was not on main" would be trivially true and the file
        // would assert nothing. This pins the precondition the crash needs.
        assertEquals(MAIN_THREAD_NAME, baseThreadName())
    }

    @Test
    fun `does not decode a large body on the main thread`() = runBlocking(Dispatchers.Main) {
        // ⛔ THE TEST THAT FAILS WITHOUT THE DISPATCHER SWITCH. Before it existed, `Call.await`
        // resumed on this same main thread and the read plus decode below ran here — which on a
        // device is a NetworkOnMainThreadException and a dead process. Note the deliberate absence
        // of an injected dispatcher: this is the shipped default being asserted.
        val strategy = ThreadRecordingStrategy(Payload.serializer())
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.headersOf("Content-Type", "application/json"),
                body = largeBody(),
            ),
        )

        val result = client().get(listOf("api", "district", "thing"), strategy)

        assertTrue("expected Success, got $result", result is ApiResult.Success)
        assertNotNull("the decode never ran, so this proves nothing", strategy.thread)
        assertNotEquals(
            "the body read and decode ran on the main thread — this is the crash",
            MAIN_THREAD_NAME,
            strategy.thread,
        )
    }

    @Test
    fun `does not decode a small body on the main thread either`() = runBlocking(Dispatchers.Main) {
        // ⚠️ A SMALL BODY IS NOT SAFE, IT IS ONLY LUCKY. Whether okio has already buffered the
        // whole body decides whether `string()` touches the socket, and that is a function of
        // network timing rather than of payload size. Relying on "small bodies do not block" would
        // make the crash intermittent instead of absent.
        val strategy = ThreadRecordingStrategy(Payload.serializer())
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.headersOf("Content-Type", "application/json"),
                body = """{"value":"ok","count":1}""",
            ),
        )

        client().get(listOf("api", "district", "thing"), strategy)

        assertNotEquals(MAIN_THREAD_NAME, strategy.thread)
    }

    @Test
    fun `the retry after a 401 also decodes off the main thread`() = runBlocking(Dispatchers.Main) {
        // ⚠️ The retry path re-enters the loop, and a dispatcher switch placed on the wrong side of
        // that loop would cover the first attempt and not the second — a crash that only happens
        // to users whose session was revoked, which is the hardest kind to reproduce.
        val strategy = ThreadRecordingStrategy(Payload.serializer())
        server.enqueue(MockResponse(code = 401, body = """{"error":"Session expired."}"""))
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.headersOf("Content-Type", "application/json"),
                body = largeBody(),
            ),
        )

        val result = client().get(listOf("api", "district", "thing"), strategy)

        assertTrue("expected Success, got $result", result is ApiResult.Success)
        assertNotEquals(MAIN_THREAD_NAME, strategy.thread)
    }

    // ── The injection seam ───────────────────────────────────────────────────

    @Test
    fun `runs on the injected dispatcher rather than picking its own`() = runBlocking(Dispatchers.Main) {
        // Proves the constructor parameter is actually honoured, so a caller (or a test) can
        // substitute a dispatcher and have it mean something.
        val strategy = ThreadRecordingStrategy(Payload.serializer())
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.headersOf("Content-Type", "application/json"),
                body = """{"value":"ok"}""",
            ),
        )

        client(io = fakeIo.asCoroutineDispatcher())
            .get(listOf("api", "district", "thing"), strategy)

        assertEquals(IO_THREAD_NAME, strategy.thread)
    }

    @Test
    fun `an error body is also read off the main thread`() = runBlocking(Dispatchers.Main) {
        // ⚠️ `mapFailure` READS THE BODY TOO, and it is reached on EVERY non-2xx — so a dispatcher
        // switch that covered only the success path would move the crash from the happy path to the
        // error path rather than removing it. A 500 with a 9 KiB body is past okio's eager
        // buffering, so this read touches the socket exactly like the success one does.
        //
        // ⚠️ OBSERVED THROUGH THE DISPATCHER RATHER THAN A SERIALIZER, because a failure never
        // reaches one — there is no `deserialize` to instrument on this path. `RecordingDispatcher`
        // records the thread each dispatched block actually ran on; since the whole call including
        // `mapFailure` sits inside one `withContext(io)` with no further dispatch beneath it, "the
        // block ran on the IO thread and main is absent from the record" is the available proof.
        val recording = RecordingDispatcher(fakeIo.asCoroutineDispatcher())
        server.enqueue(
            MockResponse(
                code = 500,
                headers = Headers.headersOf("Content-Type", "application/json"),
                body = """{"success":false,"error":"${"x".repeat(LARGE_ERROR_CHARS)}"}""",
            ),
        )

        val result = client(io = recording).get(listOf("api", "district", "thing"), Payload.serializer())

        assertTrue("expected HttpFailure, got $result", result is ApiResult.HttpFailure)
        assertEquals(500, (result as ApiResult.HttpFailure).status)
        assertTrue(
            "the error path never reached the injected dispatcher, so it ran on the caller's",
            recording.threads.contains(IO_THREAD_NAME),
        )
        assertFalse(
            "the error body was read on the main thread — the fix missed the failure path",
            recording.threads.contains(MAIN_THREAD_NAME),
        )
    }

    private companion object {
        /** Distinctive so an assertion failure names the thread rather than a boolean. */
        const val MAIN_THREAD_NAME = "district-fake-main"
        const val IO_THREAD_NAME = "district-fake-io"

        /**
         * The executor's thread name, with coroutine debug decoration stripped.
         *
         * ⛔ NOT COSMETIC — WITHOUT THIS THE WHOLE FILE ASSERTS NOTHING. kotlinx-coroutines runs
         * with debug naming on under test and appends " @coroutine#N" to the current thread's
         * name for the duration of a coroutine. So the raw name on the main thread is
         * "district-fake-main @coroutine#79", and `assertNotEquals("district-fake-main", raw)`
         * PASSES even when the code is running exactly where it must not. Measured: the two
         * harness assertions in this file failed on the decorated name first, which is how the
         * unsound comparison was caught before it could green-light the bug.
         */
        fun baseThreadName(): String = Thread.currentThread().name.substringBefore(" @")

        /** 11 chars each — comfortably past okio's eager buffering at ~8 KiB. */
        const val LARGE_BODY_REPEATS = 1200

        const val LARGE_ERROR_CHARS = 9000
    }
}
