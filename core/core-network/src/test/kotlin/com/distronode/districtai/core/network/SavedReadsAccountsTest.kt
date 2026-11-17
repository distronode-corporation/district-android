package com.distronode.districtai.core.network

import com.distronode.districtai.core.auth.NativeTokens
import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.core.auth.RefreshResult
import com.distronode.districtai.core.auth.TokenRefreshCoordinator
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ⛔ ONE ACCOUNT IS NEVER SHOWN ANOTHER'S SAVED OFFLINE READS, whichever way a session ends:
 * through the real client, with JWT access tokens naming the account, over files.
 */
class SavedReadsAccountsTest {

    @Serializable
    data class Payload(val value: String)

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = File(folder.root, "saved-reads")
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** A launch of the app: its saved reads over the one cache directory. */
    private fun launch() = SavedReads(FileReadCache(dir), language = { "en" })

    private fun jwt(sub: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val payload = encoder.encodeToString("""{"sub":"$sub","exp":4000000000}""".toByteArray())
        return "eyJhbGciOiJIUzI1NiJ9.$payload.c2ln"
    }

    /** A session for [user] whose refresh answers [refresh] (a fresh token for that user, or none). */
    private fun session(user: String, refresh: RefreshResult? = null): TokenRefreshCoordinator {
        val api = FakeRefreshApi().apply {
            always(
                refresh ?: RefreshResult.Success(
                    NativeTokens(
                        accessToken = jwt(user),
                        accessTokenExpiresAt = System.currentTimeMillis() + 600_000L,
                        refreshToken = "refresh-$user",
                        refreshTokenExpiresAt = System.currentTimeMillis() + 86_400_000L,
                    ),
                ),
            )
        }
        val store = FakeTokenStore(
            PersistedSession(
                refreshToken = "refresh-0",
                refreshTokenExpiresAt = System.currentTimeMillis() + 86_400_000L,
                deviceId = "device-1",
            ),
        )
        return TokenRefreshCoordinator(store = store, refreshApi = api)
    }

    private fun client(saved: SavedReads, tokens: TokenRefreshCoordinator) = DistrictApiClient(
        baseUrl = server.url("/"),
        httpClient = DistrictHttp.client(),
        tokens = tokens,
        savedReads = saved,
    )

    private suspend fun DistrictApiClient.overview() = get(DistrictPaths.OVERVIEW, Payload.serializer())

    private fun ok(value: String) = MockResponse(code = 200, body = """{"value":"$value"}""")

    /** A's overview read live, so its copy is saved under A. */
    private suspend fun aReadsOnline() {
        server.enqueue(ok("A's overview"))
        assertEquals(ApiResult.Success(Payload("A's overview")), client(launch(), session("user-a")).overview())
    }

    @Test
    fun `an expired session at start-up, then a sign-in as another account, shows nothing of the old`() =
        runTest {
            aReadsOnline()

            // The next launch finds A's session over: the service refuses it.
            val next = launch()
            client(next, signedOutCoordinator()).get(listOf("api", "district", "thing"), Payload.serializer())
            // B signs in (AppContainer.onSignedIn), then the device goes offline.
            next.clear()
            server.close()

            val offline = client(next, session("user-b")).overview()
            assertTrue("B is shown nothing of A's", offline is ApiResult.NetworkFailure)
            assertNull(next.showingSince.value)
        }

    @Test
    fun `a sign-in as another account, offline at once, shows nothing of the old`() = runTest {
        aReadsOnline()

        // B signs in on the next launch (its clear is AppContainer.onSignedIn's, pinned in
        // AppContainerTest); here nothing clears, and B's first request is sent offline.
        val next = launch()
        server.close()

        val offline = client(next, session("user-b")).overview()
        assertTrue("B is shown nothing of A's", offline is ApiResult.NetworkFailure)
        assertNull(next.showingSince.value)
    }

    @Test
    fun `another account's token alone forgets the old account's copies, even with no clear`() = runTest {
        aReadsOnline()
        val next = launch()
        server.close()

        // No sign-out ran (the app was killed first, say): B's request is the first thing it sends.
        assertTrue(client(next, session("user-b")).overview() is ApiResult.NetworkFailure)
        val marker = FileReadCache.fileName(SavedReads.OWNER_KEY)
        assertEquals("only B's owner marker is left", listOf(marker), dir.list()!!.toList())
    }

    @Test
    fun `a launch offline still shows its own account's copies`() = runTest {
        aReadsOnline()
        val next = launch()

        // The refresh cannot leave the device, so the session cannot say whose it is.
        val offline = client(next, session("user-a", refresh = RefreshResult.NotSent)).overview()

        assertEquals(ApiResult.Success(Payload("A's overview")), offline)
    }

    @Test
    fun `with no account known nothing is kept or shown`() = runTest {
        val fresh = launch()
        server.enqueue(ok("live"))
        // A token that names no account (not a JWT).
        client(fresh, signedInCoordinator(FakeRefreshApi().apply { rotating() })).overview()
        assertFalse("nothing written", dir.exists() && dir.list()!!.isNotEmpty())

        assertNull(fresh.asking(DistrictPaths.OVERVIEW, "https://example.test/x".toHttpUrl()))
    }

    @Test
    fun `the same account again keeps its copies`() {
        val saved = launch()
        assertTrue(saved.own("user-a"))
        saved.asking(DistrictPaths.OVERVIEW, "https://example.test/x".toHttpUrl())!!.answered("{}")

        assertFalse(saved.own("user-a"))
        assertFalse("a token that names no account changes nothing", saved.ownToken("not-a-jwt"))
        assertEquals(2, dir.list()!!.size)
    }

    @Test
    fun `an account is read from a token's sub claim only`() {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun token(payload: String) = "h.${encoder.encodeToString(payload.toByteArray())}.s"

        assertEquals("user-a", SavedReads.subjectOf(jwt("user-a")))
        assertNull("no payload", SavedReads.subjectOf("only-one-part"))
        assertNull("not base64", SavedReads.subjectOf("h.!!!.s"))
        assertNull("not JSON", SavedReads.subjectOf(token("not json")))
        assertNull("not an object", SavedReads.subjectOf(token("[1]")))
        assertNull("no sub", SavedReads.subjectOf(token("""{"exp":1}""")))
        assertNull("a sub that is not text", SavedReads.subjectOf(token("""{"sub":7}""")))
        assertNull("an empty sub", SavedReads.subjectOf(token("""{"sub":""}""")))
        assertNull("a sub that is an object", SavedReads.subjectOf(token("""{"sub":{}}""")))
    }
}
