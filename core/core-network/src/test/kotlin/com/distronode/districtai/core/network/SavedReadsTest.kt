package com.distronode.districtai.core.network

import com.distronode.districtai.core.auth.ReauthReason
import com.distronode.districtai.core.auth.RefreshResult
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Works offline, read-only: the saved copy of the first screens' reads, through the real client
 * against a real socket (the iOS app's `OfflineReadCacheTests`).
 */
class SavedReadsTest {

    @Serializable
    data class Payload(val value: String)

    private class MemoryCache : ReadCache {
        val reads = mutableMapOf<String, SavedRead>()
        override fun load(key: String): SavedRead? = reads[key]
        override fun save(key: String, read: SavedRead) {
            reads[key] = read
        }
        override fun remove(key: String) {
            reads.remove(key)
        }
        override fun clear() = reads.clear()
    }

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val cache = MemoryCache()
    private var now = 1_000L
    private var language = "en"
    private val saved = SavedReads(cache, language = { language }, clock = { now })

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(refresh: FakeRefreshApi = FakeRefreshApi().apply { rotating() }) = DistrictApiClient(
        baseUrl = server.url("/"),
        httpClient = DistrictHttp.client(),
        tokens = signedInCoordinator(refresh),
        savedReads = saved,
    )

    private suspend fun DistrictApiClient.overview() = get(DistrictPaths.OVERVIEW, Payload.serializer())

    private fun ok(value: String) = MockResponse(code = 200, body = """{"value":"$value"}""")

    @Test
    fun `a read answered live is shown again offline, with when it was saved`() = runTest {
        val client = client()
        server.enqueue(ok("live"))
        assertEquals(ApiResult.Success(Payload("live")), client.overview())
        assertNull("a live answer is not a saved copy", saved.showingSince.value)

        now = 5_000L
        server.close()
        assertEquals(ApiResult.Success(Payload("live")), client.overview())
        assertEquals(1_000L, saved.showingSince.value)
    }

    @Test
    fun `the next live answer takes the copy off the screen`() = runTest {
        val client = client()
        server.enqueue(ok("live"))
        client.overview()
        val portal = okhttp3.Headers.headersOf("Content-Type", "text/html")
        server.enqueue(MockResponse(code = 200, headers = portal, body = "<html>"))
        client.overview()
        assertEquals("a captive portal is offline too", 1_000L, saved.showingSince.value)

        server.enqueue(ok("again"))
        assertEquals(ApiResult.Success(Payload("again")), client.overview())
        assertNull(saved.showingSince.value)
    }

    @Test
    fun `an expired token whose refresh cannot leave the device is answered from the copy`() = runTest {
        server.enqueue(ok("live"))
        client().overview()

        val offline = client(FakeRefreshApi().apply { always(RefreshResult.NotSent) })
        assertEquals(ApiResult.Success(Payload("live")), offline.overview())
    }

    @Test
    fun `an expired token whose refresh cannot leave the device, with nothing saved, is offline`() = runTest {
        val offline = client(FakeRefreshApi().apply { always(RefreshResult.NotSent) })
        val result = offline.overview()
        assertTrue(result is ApiResult.NetworkFailure)
        assertTrue((result as ApiResult.NetworkFailure).cause is RefreshNotSentException)
    }

    @Test
    fun `an answer from the service is never covered over`() = runTest {
        val client = client()
        server.enqueue(ok("live"))
        client.overview()
        server.enqueue(MockResponse(code = 503))

        assertTrue(client.overview() !is ApiResult.Success)
        assertNull(saved.showingSince.value)
    }

    @Test
    fun `other reads keep nothing, and offline are only the offline error`() = runTest {
        val client = client()
        server.enqueue(ok("live"))
        client.get(listOf("api", "district", "thing"), Payload.serializer())
        assertTrue(cache.reads.isEmpty())

        server.close()
        val offline = client.get(listOf("api", "district", "thing"), Payload.serializer())
        assertTrue(offline is ApiResult.NetworkFailure)
        assertNull(saved.showingSince.value)
    }

    @Test
    fun `a copy older than seven days, or from the future, or that no longer decodes, is not shown`() = runTest {
        val client = client()
        server.enqueue(ok("live"))
        client.overview()
        server.close()

        now = 1_000L + SavedReads.MAX_AGE_MILLIS
        assertTrue("seven days is still shown", client.overview() is ApiResult.Success)
        now = 1_001L + SavedReads.MAX_AGE_MILLIS
        assertTrue(client.overview() is ApiResult.NetworkFailure)
        now = 999L
        assertTrue(client.overview() is ApiResult.NetworkFailure)

        now = 1_000L
        cache.reads.replaceAll { _, read -> read.copy(body = """{"other":1}""") }
        assertTrue("a copy this build cannot read", client.overview() is ApiResult.NetworkFailure)
    }

    @Test
    fun `a copy that can no longer be shown comes off the screen`() = runTest {
        val client = client()
        server.enqueue(ok("live"))
        client.overview()
        server.close()
        client.overview()
        assertEquals(1_000L, saved.showingSince.value)

        now = 1_001L + SavedReads.MAX_AGE_MILLIS
        assertTrue(client.overview() is ApiResult.NetworkFailure)
        assertNull("expired: no copy is on screen", saved.showingSince.value)

        now = 1_000L
        client.overview()
        cache.reads.clear()
        assertTrue(client.overview() is ApiResult.NetworkFailure)
        assertNull("evicted: the same", saved.showingSince.value)
    }

    @Test
    fun `a refusal from the service after reconnecting takes the copy off the screen`() = runTest {
        val client = client()
        server.enqueue(ok("live"))
        client.overview()
        val portal = okhttp3.Headers.headersOf("Content-Type", "text/html")
        server.enqueue(MockResponse(code = 200, headers = portal, body = "<html>"))
        client.overview()
        assertEquals(1_000L, saved.showingSince.value)

        server.enqueue(MockResponse(code = 503))
        assertTrue("never covered over", client.overview() !is ApiResult.Success)
        assertNull("the service answered: no copy is on screen", saved.showingSince.value)
    }

    @Test
    fun `any answer from the service takes down the banner, whichever reads showed a copy`() = runTest {
        val client = client()
        server.enqueue(ok("live"))
        client.overview()
        val portal = okhttp3.Headers.headersOf("Content-Type", "text/html")
        server.enqueue(MockResponse(code = 200, headers = portal, body = "<html>"))
        client.overview()
        assertEquals(1_000L, saved.showingSince.value)

        // Online again, and the next read is another one entirely.
        server.enqueue(ok("other"))
        client.get(listOf("api", "district", "thing"), Payload.serializer())
        assertNull(saved.showingSince.value)
    }

    @Test
    fun `a body over two megabytes is not kept`() = runTest {
        server.enqueue(ok("x".repeat(SavedReads.MAX_BYTES)))
        client().overview()
        assertTrue(cache.reads.isEmpty())
    }

    @Test
    fun `a copy in one language never fills the other`() = runTest {
        val client = client()
        server.enqueue(ok("live"))
        client.overview()
        server.close()

        language = "fr-CA"
        assertTrue(client.overview() is ApiResult.NetworkFailure)
    }

    @Test
    fun `clearing forgets every copy, and a read in flight keeps nothing`() = runTest {
        val client = client()
        server.enqueue(ok("live"))
        client.overview()
        server.close()
        client.overview()

        saved.clear()
        assertNull(saved.showingSince.value)
        assertTrue(cache.reads.isEmpty())

        val asked = saved.asking(DistrictPaths.OVERVIEW, "https://example.test/x".toHttpUrl())!!
        saved.clear()
        asked.answered("""{"value":"late"}""")
        assertTrue("an answer to a read sent before the sign-out", cache.reads.isEmpty())
    }

    @Test
    fun `a copy saved again after a sign-out is not shown to the read asked before it`() = runTest {
        val asked = saved.asking(DistrictPaths.OVERVIEW, "https://example.test/x".toHttpUrl())!!
        saved.clear()
        cache.reads[asked.key] = SavedRead("""{"value":"other session"}""", now)

        val result = saved.answer(asked, ApiResult.NetworkFailure(java.io.IOException("offline"))) {
            Payload("decoded")
        }

        assertTrue(result is ApiResult.NetworkFailure)
        assertNull(saved.showingSince.value)
    }

    @Test
    fun `a clear that lands while a copy is written still wins`() {
        val writing = java.util.concurrent.CountDownLatch(1)
        val goOn = java.util.concurrent.CountDownLatch(1)
        val held = object : ReadCache {
            val inner = MemoryCache()
            override fun load(key: String): SavedRead? = inner.load(key)
            override fun save(key: String, read: SavedRead) {
                writing.countDown()
                goOn.await()
                inner.save(key, read)
            }
            override fun remove(key: String) = inner.remove(key)
            override fun clear() = inner.clear()
        }
        val reads = SavedReads(held, language = { "en" }, clock = { now })
        val asked = reads.asking(DistrictPaths.OVERVIEW, "https://example.test/x".toHttpUrl())!!
        val writer = Thread { asked.answered("{}") }.apply { start() }

        writing.await()
        // The lock is free while the copy is written: a sign-out is not held up.
        reads.clear()
        // And the next session saves a copy of its own meanwhile.
        held.inner.save("the next session's", SavedRead("{}", 1L))
        goOn.countDown()
        writer.join()

        assertEquals(
            "only the copy the ended session wrote goes",
            setOf("the next session's"),
            held.inner.reads.keys,
        )
    }

    @Test
    fun `an older request answering last does not replace the newer copy`() {
        val url = "https://example.test/x".toHttpUrl()
        val older = saved.asking(DistrictPaths.OVERVIEW, url)!!
        now = 2_000L
        val newer = saved.asking(DistrictPaths.OVERVIEW, url)!!
        now = 3_000L

        newer.answered("""{"value":"newer"}""")
        older.answered("""{"value":"older"}""")

        assertEquals(
            "the newer answer, stamped with when it was asked",
            SavedRead("""{"value":"newer"}""", 2_000L),
            cache.reads[newer.key],
        )
    }

    @Test
    fun `the service ending the session forgets every copy`() = runTest {
        server.enqueue(ok("live"))
        client().overview()

        val signedOut = DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = DistrictHttp.client(),
            tokens = signedOutCoordinator(),
            savedReads = saved,
        )
        val refused = signedOut.get(listOf("api", "x"), Payload.serializer())
        assertEquals(ApiResult.Unauthorized(ReauthReason.NoSession), refused)
        assertTrue(cache.reads.isEmpty())
    }

    @Test
    fun `the five saved reads are the first screens' reads`() {
        assertEquals(
            setOf(
                DistrictPaths.WORKSPACE_LIST,
                DistrictPaths.OVERVIEW,
                DistrictPaths.CONVERSATIONS,
                DistrictPaths.CALLS,
                DistrictPaths.CONTACTS,
            ),
            SavedReads.SAVED_PATHS,
        )
    }

    @Test
    fun `without a clock, a copy is stamped with the system time`() {
        val memory = MemoryCache()
        val real = SavedReads(memory, language = { "en" })
        val before = System.currentTimeMillis()
        real.asking(DistrictPaths.OVERVIEW, "https://example.test/x".toHttpUrl())!!.answered("{}")
        val stamped = memory.reads.values.single().savedAtMillis
        assertTrue(stamped in before..System.currentTimeMillis())
    }

    // ── Files ────────────────────────────────────────────────────────────────

    @Test
    fun `a file cache keeps a read as it was saved, under a name that reveals nothing`() {
        val dir = File(folder.root, "saved-reads")
        val files = FileReadCache(dir)
        assertNull("nothing saved, and no directory yet", files.load("k"))
        files.clear()

        files.save("en https://x/api?workspaceId=ws-secret", SavedRead("{\"a\":\n1}", 42L))

        assertEquals(SavedRead("{\"a\":\n1}", 42L), files.load("en https://x/api?workspaceId=ws-secret"))
        val name = dir.listFiles()!!.single().name
        assertEquals(64, name.length)
        assertTrue(name, name.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `a file cache removes one read and leaves the others`() {
        val files = FileReadCache(folder.newFolder())
        files.save("k", SavedRead("{}", 1L))
        files.save("j", SavedRead("{}", 2L))

        files.remove("k")
        files.remove("never saved")

        assertNull(files.load("k"))
        assertEquals(SavedRead("{}", 2L), files.load("j"))
    }

    @Test
    fun `a damaged file reads as nothing`() {
        val dir = folder.newFolder()
        val files = FileReadCache(dir)
        files.save("k", SavedRead("{}", 1L))
        val file = dir.listFiles()!!.single()

        file.writeText("no newline")
        assertNull(files.load("k"))
        file.writeText("yesterday\n{}")
        assertNull(files.load("k"))
    }

    @Test
    fun `a file cache keeps the newest sixty-four and clears them all`() {
        val dir = folder.newFolder()
        val files = FileReadCache(dir)
        repeat(FileReadCache.MAX_FILES + 1) { index ->
            files.save("k$index", SavedRead("{}", index.toLong()))
            File(dir, FileReadCache.fileName("k$index")).setLastModified(1_000_000L + index * 1_000L)
        }
        files.save("last", SavedRead("{}", 99L))

        assertEquals(FileReadCache.MAX_FILES, dir.listFiles()!!.size)
        assertNull("the oldest went", files.load("k0"))

        files.clear()
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `a file cache touches only its own files, and a sign-out takes its temporary ones too`() {
        val dir = folder.newFolder()
        val hex = "a".repeat(64)
        val inProgress = ".$hex.0b5f2c7e-1d3a-4c8e-9f60-2a7b8c9d0e1f.tmp"
        val others = listOf("C".repeat(64), "stray", ".$hex")
        (others + inProgress).forEach { File(dir, it).writeText("") }
        File(dir, "b".repeat(64)).mkdir()
        val files = FileReadCache(dir)

        files.save("k", SavedRead("{}", 1L))
        assertTrue("a write in progress survives a save", File(dir, inProgress).exists())

        files.clear()
        assertEquals((others + "b".repeat(64)).sorted(), dir.list()!!.sorted())
    }

    @Test
    fun `a temporary file a crash left behind is removed by the next save`() {
        val dir = folder.newFolder()
        val left = File(dir, ".${"c".repeat(64)}.0b5f2c7e-1d3a-4c8e-9f60-2a7b8c9d0e1f.tmp").apply {
            writeText("half a body")
            setLastModified(System.currentTimeMillis() - 60L * 60 * 1000)
        }

        FileReadCache(dir).save("k", SavedRead("{}", 1L))

        assertTrue(!left.exists())
    }

    @Test
    fun `a file cache that cannot be written keeps nothing and says nothing`() {
        val blocker = folder.newFile()
        val files = FileReadCache(File(blocker, "saved-reads"))
        files.save("k", SavedRead("{}", 1L))
        assertNull(files.load("k"))

        // A directory where the file would go: the rename fails, and the temporary file goes.
        val dir = folder.newFolder()
        File(dir, FileReadCache.fileName("k")).mkdir()
        FileReadCache(dir).save("k", SavedRead("{}", 1L))
        assertEquals(listOf(FileReadCache.fileName("k")), dir.list()!!.toList())
    }
}
