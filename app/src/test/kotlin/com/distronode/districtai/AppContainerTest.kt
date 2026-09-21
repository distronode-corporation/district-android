package com.distronode.districtai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.core.auth.RevokeApi
import com.distronode.districtai.core.auth.RevokeResult
import com.distronode.districtai.core.auth.TokenStore
import com.distronode.districtai.core.data.PreferencesWorkspaceSelectionStore
import com.distronode.districtai.core.model.PushRegistrationResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.PushApi
import com.distronode.districtai.core.network.PushTokenRegisterRequest
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The hand-wired object graph.
 *
 * ⛔ THE SINGLETONS ARE THE POINT, AND TWO OF THEM ARE SECURITY-LOAD-BEARING. A second
 * `TokenRefreshCoordinator` would have its own mutex and its own idea of the current token, so two
 * of them can present the SAME refresh token concurrently — the server treats a re-presented
 * refresh token as theft and revokes the entire family, signing the user out everywhere. A second
 * `NativeLoginFlow` (reached through `loginController`) would hold no pending PKCE verifier, so
 * every login would fail with `NoAttemptInProgress`. Both are `val`/`by lazy` today; this asserts
 * that a repeated read is the same object rather than trusting the keyword.
 *
 * ⚠️ Construction is asserted at all because it is otherwise only exercised on a device: nothing
 * here opens a socket, so the whole graph builds under Robolectric, and a wiring mistake (a bad
 * base URL, a missing trailing slash) fails here rather than at first request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AppContainerTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun container(scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)) =
        AppContainer(
            ApplicationProvider.getApplicationContext(),
            appScope = scope,
            // ⛔ EVEN THE "does it build" TESTS NEED THE PUSH SEAMS, because `signOut` now calls
            // `devices/unregister` FIRST and the real one is built from `ApiEnvironment.baseUrl` —
            // so without these the graph-shape tests would POST to PRODUCTION and then sit on the
            // registrar's five-second bound, which is the same order of magnitude as this class's
            // own await timeout.
            pushApi = RecordingPushApi(),
            pushTokenSource = { null },
        )

    /**
     * A container whose credential store and revoke call are both observable.
     *
     * ⛔ BOTH SEAMS ARE REQUIRED, AND NEITHER IS OPTIONAL FOR A DIFFERENT REASON. Robolectric does
     * not implement `AndroidKeyStore`, so the real store reads as "no session" and the revoke path
     * would be skipped entirely — every assertion below would pass while testing nothing. And
     * `NativeAuthApi` is built from `ApiEnvironment.baseUrl`, a `BuildConfig` constant, so without
     * the API seam these tests would POST to PRODUCTION.
     */
    private fun signOutContainer(
        store: RecordingTokenStore,
        api: RecordingRevokeApi,
        scope: CoroutineScope = realScope(),
        push: RecordingPushApi = RecordingPushApi(),
    ) = AppContainer(
        ApplicationProvider.getApplicationContext(),
        appScope = scope,
        tokenStore = store,
        revokeApi = api,
        // ⛔ A THIRD SEAM, FOR THE SAME REASON AS THE REVOKE ONE AND WITH A SHARPER EDGE: the push
        // unregister is the FIRST thing `signOut` does, so an unstubbed one would spend the whole of
        // this class's await budget on a request to production before the revoke under test was even
        // attempted — and every assertion below would fail as a timeout rather than as a wrong order.
        pushApi = push,
        // ⚠️ Null token: nothing here exercises registration, and a source that answered one would
        // make the graph-construction tests issue a register too.
        pushTokenSource = { null },
    )

    /**
     * A [PushApi] that records the ORDER of the two calls and, for the unregister, whether the
     * session was still on disk when it was made.
     *
     * ⛔ THE ORDERING OBSERVATION IS THE WHOLE POINT, matching [RecordingRevokeApi]'s. The rule is
     * that `devices/unregister` runs while a live bearer still exists — i.e. BEFORE the revoke and
     * before the wipe — and a fake inspected only for a final count would pass against an
     * implementation that called it last, when it could never succeed.
     */
    private class RecordingPushApi(
        private val result: ApiResult<PushRegistrationResponse> =
            ApiResult.Success(PushRegistrationResponse(success = true)),
        /** Observed at the moment of the call, to prove the credential had not been wiped yet. */
        private val store: RecordingTokenStore? = null,
    ) : PushApi {
        val calls: MutableList<String> = mutableListOf()

        /** Whether the store still held a session when each unregister was made. */
        val sessionPresentAtCall: MutableList<Boolean> = mutableListOf()

        override suspend fun registerPushToken(
            request: PushTokenRegisterRequest,
        ): ApiResult<PushRegistrationResponse> {
            calls += "register"
            return result
        }

        override suspend fun unregisterPushToken(): ApiResult<PushRegistrationResponse> {
            calls += "unregister"
            sessionPresentAtCall += store?.read() != null
            return result
        }
    }

    private fun storeWithSession(refreshToken: String = "refresh-live") = RecordingTokenStore(
        PersistedSession(
            refreshToken = refreshToken,
            refreshTokenExpiresAt = Long.MAX_VALUE,
            deviceId = "device-abcdefgh",
        ),
    )

    /**
     * An in-memory [TokenStore] that records the ORDER of its mutations.
     *
     * ⛔ THE ORDER IS THE POINT, exactly as in core-auth's own `FakeTokenStore`. The rules under
     * test here are ordering properties — the revoke precedes the wipe, and the outbox write
     * precedes and SURVIVES it — and a fake inspected only for final state would pass against an
     * implementation that wiped first and revoked nothing.
     *
     * ⛔ AND `clear()` SPARES THE OUTBOX, MIRRORING `KeystoreTokenStore.wipe`. A fake that cleared
     * it would agree with the bug rather than with the code: the production store carries the slot
     * across precisely because sign-out writes it microseconds before the wipe.
     *
     * ⚠️ A LOCAL COPY RATHER THAN core-auth's `FakeTokenStore`, because a module's test source set
     * is not on a dependent module's test classpath — sharing it would mean a `testFixtures`
     * variant and a dependency-lockfile change for one class.
     */
    private class RecordingTokenStore(initial: PersistedSession? = null) : TokenStore {
        private var session: PersistedSession? = initial
        private var pendingRefresh: String? = null
        private var pendingRevoke: String? = null

        /** Append-only log of mutations, for ordering assertions. */
        val operations: MutableList<String> = mutableListOf()

        override fun read(): PersistedSession? = session

        override fun write(session: PersistedSession) {
            this.session = session
            operations += "write"
        }

        override fun clear() {
            session = null
            pendingRefresh = null
            operations += "clear"
        }

        override fun pendingRefreshToken(): String? = pendingRefresh

        override fun markRefreshPending(refreshToken: String) {
            pendingRefresh = refreshToken
            operations += "markPending"
        }

        override fun clearRefreshPending() {
            pendingRefresh = null
            operations += "clearPending"
        }

        override fun pendingRevokeToken(): String? = pendingRevoke

        override fun markRevokePending(refreshToken: String) {
            pendingRevoke = refreshToken
            operations += "markRevoke($refreshToken)"
        }

        override fun clearRevokePending() {
            pendingRevoke = null
            operations += "clearRevoke"
        }
    }

    /**
     * A [RevokeApi] that records what it was asked and answers what the test wants.
     *
     * ⚠️ Hand-written rather than mocked, for the same reason `TestDistrictApi` is: the question
     * these tests ask is "which token was presented, and in what order relative to the wipe",
     * which reads better as a list than as an argument captor.
     */
    private class RecordingRevokeApi(
        private val result: RevokeResult = RevokeResult.Done,
        /** Observed at the moment of the call, to prove the wipe had not run yet. */
        private val store: RecordingTokenStore? = null,
    ) : RevokeApi {
        val presented: MutableList<String> = mutableListOf()

        /** Whether the store still held a session when each revoke was made. */
        val sessionPresentAtCall: MutableList<Boolean> = mutableListOf()

        override suspend fun revoke(refreshToken: String): RevokeResult {
            presented += refreshToken
            sessionPresentAtCall += store?.read() != null
            return result
        }
    }

    /**
     * ⛔ REAL TIME, NOT VIRTUAL, AND THAT IS FORCED RATHER THAN CHOSEN. `signOut` awaits
     * `TokenRefreshCoordinator.forget()`, which does its Keystore wipe inside
     * `withContext(Dispatchers.IO)` — the coordinator is constructed by [AppContainer] with that
     * default and nothing here can inject a test dispatcher into it. So a `runTest` +
     * `advanceUntilIdle` drains the virtual scheduler, returns while the real IO hop is still in
     * flight, and the assertion reads as "sign-out did nothing". Awaiting the epoch itself is the
     * only honest signal that the whole sequence finished.
     */
    private fun awaitEpoch(container: AppContainer, target: Int) = runBlocking {
        withTimeout(AWAIT_TIMEOUT_MS) { container.sessionSignal.epoch.first { it >= target } }
    }

    /**
     * ⚠️ NOT `Dispatchers.Main.immediate`, which is what production uses. `setMain` has replaced
     * Main with the virtual test dispatcher for this class, so a scope built on it would park the
     * sign-out on a scheduler nothing in these tests advances — the same trap as above, wearing a
     * different hat.
     */
    private fun realScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * A scope whose work never runs.
     *
     * ⛔ THE DRAIN TESTS NEED THIS OR THEY RACE THEMSELVES. `AppContainer`'s `init` launches
     * `drainPendingRevoke()` on the app scope, so a container built with an outbox entry already
     * present would drain it CONCURRENTLY with the test's own explicit call — two revokes for one
     * token, and `presented` would hold one entry or two depending on timing. Parking the init
     * launch on a virtual dispatcher nothing advances leaves exactly one invocation: the one under
     * test.
     */
    private fun idleScope() = CoroutineScope(SupervisorJob() + dispatcher)

    @Test
    fun `the whole graph builds without touching the network`() {
        val subject = container()

        assertNotNull(subject.tokenCoordinator)
        assertNotNull(subject.workspaceRepository)
        assertNotNull(subject.overviewRepository)
        assertNotNull(subject.callsRepository)
        assertNotNull(subject.contactsRepository)
        assertNotNull(subject.inboxRepository)
        assertNotNull(subject.hqRepository)
        assertNotNull(subject.analyticsRepository)
        assertNotNull(subject.sessionSignal)
    }

    @Test
    fun `the token coordinator is one instance for the process`() {
        // ⛔ NOT NEGOTIABLE. Two coordinators can present the same refresh token concurrently, and
        // the server revokes the whole token family when it sees one re-presented.
        val subject = container()

        assertSame(subject.tokenCoordinator, subject.tokenCoordinator)
    }

    @Test
    fun `the login controller is one instance, so the PKCE verifier survives the callback`() {
        // ⛔ A second one would carry a fresh LoginAttemptTracker — the rotation bug it exists to
        // fix — and every login would fail with NoAttemptInProgress.
        val subject = container()

        assertSame(subject.loginController, subject.loginController)
    }

    @Test
    fun `each repository is a single instance sharing the one API client`() {
        val subject = container()

        assertSame(subject.workspaceRepository, subject.workspaceRepository)
        assertSame(subject.overviewRepository, subject.overviewRepository)
        assertSame(subject.callsRepository, subject.callsRepository)
        assertSame(subject.contactsRepository, subject.contactsRepository)
        assertSame(subject.inboxRepository, subject.inboxRepository)
        assertSame(subject.hqRepository, subject.hqRepository)
        // ⚠️ Analytics holds no cache, so a second instance would be harmless — asserted anyway
        // because the day it grows one, this is the assertion that has to already exist.
        assertSame(subject.analyticsRepository, subject.analyticsRepository)
    }

    @Test
    fun `two containers do not share state, which is why exactly one is built`() {
        // ⚠️ Stated as a fact about the class rather than a recommendation: nothing stops a second
        // container being constructed, so the single-instance guarantee lives at the call site
        // (the Application). This pins what a mistake there would cost.
        assertEquals(false, container().tokenCoordinator === container().tokenCoordinator)
    }

    @Test
    fun `sign-out advances the session epoch`() {
        // ⛔ AND IT ADVANCES LAST. The gate reacts to the epoch by rendering the sign-in screen;
        // firing it before `forget()` returns would race the wipe against a fresh login writing
        // new tokens, and `store.clear()` would then delete the NEW refresh token.
        val subject = container(realScope())
        val before = subject.sessionSignal.epoch.value

        subject.signOut()

        assertEquals(before + 1, awaitEpoch(subject, before + 1))
    }

    @Test
    fun `sign-out clears the credential and the workspace selection`() {
        // ⚠️ The selection is not a credential, but leaving it means the next user of a shared
        // device lands in the previous user's workspace by default.
        val subject = container(realScope())
        subject.workspaceRepository.select("ws-1")

        subject.signOut()
        awaitEpoch(subject, 1)

        // Read through a FRESH store rather than the container's, so this asserts what landed on
        // disk instead of an in-memory field. Robolectric has no AndroidKeyStore, so the token
        // half already reads as "no session"; the selection is the half that can be observed here.
        val onDisk = PreferencesWorkspaceSelectionStore(ApplicationProvider.getApplicationContext())
        assertEquals(null, onDisk.selectedWorkspaceId())
    }

    @Test
    fun `sign-out runs on the container's own scope, not the caller's`() {
        // ⛔ A sign-out interrupted midway is the worst of both states: the in-memory access token
        // is gone but the refresh token is still on disk, so the app looks signed out and silently
        // signs itself back in on the next launch. Nothing is awaited here on purpose — the call
        // must return immediately and the work must still complete.
        val subject = container(realScope())

        subject.signOut()

        // Returned immediately: the wipe is still in flight on the container's scope.
        assertEquals(1, awaitEpoch(subject, 1))
    }

    // ── Server-side revocation ───────────────────────────────────────────────

    @Test
    fun `sign-out revokes server-side BEFORE wiping the credential`() {
        // ⛔ THE ORDER IS FORCED, NOT PREFERRED. The refresh token IS the credential the revoke
        // route authenticates with, so reading it after `forget()` would leave nothing to revoke
        // WITH — the sign-out would be local-only and the server would keep honouring the session
        // for the rest of its 60-day window, which is exactly the hole this closes.
        val store = storeWithSession()
        val api = RecordingRevokeApi(store = store)
        val subject = signOutContainer(store, api)

        subject.signOut()
        awaitEpoch(subject, 1)

        assertEquals(listOf("refresh-live"), api.presented)
        assertEquals(
            "the store must still hold the session when the revoke is made",
            listOf(true),
            api.sessionPresentAtCall,
        )
        assertNull("and it must be gone afterwards", store.read())
    }

    @Test
    fun `a revoke the server could not answer is deferred, not dropped`() {
        // ⛔ 503 MEANS THE WRITE DID NOT HAPPEN. The route's own header: the client must KEEP the
        // credential and try again, because the server may still honour that refresh token.
        // Discarding it here would strand a live credential nobody is tracking.
        val store = storeWithSession()
        val api = RecordingRevokeApi(result = RevokeResult.RetryLater, store = store)
        val subject = signOutContainer(store, api)

        subject.signOut()
        awaitEpoch(subject, 1)

        assertEquals("refresh-live", store.pendingRevokeToken())
    }

    @Test
    fun `the outbox survives the wipe that immediately follows it`() {
        // ⛔ THE WHOLE SEQUENCE IN ONE ASSERTION. `markRevoke` is written and `clear` runs
        // microseconds later; a store that treated the outbox like the refresh marker would erase
        // it on that clear and the deferral would be silently worthless. The ordering is what
        // makes the previous test's assertion mean anything.
        val store = storeWithSession()
        val subject = signOutContainer(store, RecordingRevokeApi(result = RevokeResult.RetryLater, store = store))

        subject.signOut()
        awaitEpoch(subject, 1)

        assertEquals(
            listOf("markRevoke(refresh-live)", "clear"),
            store.operations.filter { it == "clear" || it.startsWith("markRevoke") },
        )
        assertEquals("refresh-live", store.pendingRevokeToken())
    }

    @Test
    fun `a successful revoke leaves no outbox entry to chase`() {
        val store = storeWithSession()
        val subject = signOutContainer(store, RecordingRevokeApi(store = store))

        subject.signOut()
        awaitEpoch(subject, 1)

        assertNull(store.pendingRevokeToken())
    }

    @Test
    fun `sign-out still completes when there is no session to revoke`() {
        // ⚠️ REACHABLE IN PRODUCTION: the account screen is available after a Keystore failure or
        // a session the coordinator already cleared, and there is then nothing to revoke with. The
        // rest of the sign-out must still run — an epoch that never advanced would leave the user
        // staring at a signed-in shell.
        val store = RecordingTokenStore()
        val api = RecordingRevokeApi(store = store)
        val subject = signOutContainer(store, api)

        subject.signOut()

        assertEquals(1, awaitEpoch(subject, 1))
        assertEquals("nothing to present, so nothing may be sent", emptyList<String>(), api.presented)
    }

    @Test
    fun `the epoch still advances last, after the revoke and the wipe`() {
        // ⛔ THE ADDED NETWORK CALL MUST NOT REORDER THE SEQUENCE. The gate reacts to the epoch by
        // rendering the sign-in screen; firing it before `forget()` returns would race the wipe
        // against a fresh login writing new tokens, and `store.clear()` would then delete the NEW
        // refresh token. So by the time the epoch is observable, the wipe has happened.
        val store = storeWithSession()
        val subject = signOutContainer(store, RecordingRevokeApi(store = store))

        subject.signOut()
        awaitEpoch(subject, 1)

        assertNull(store.read())
    }

    // ── Push registration, and where it sits in the sign-out ─────────────────

    @Test
    fun `sign-out withdraws push BEFORE the revoke, while a bearer still exists`() {
        // ⛔ THE ORDER IS FORCED, NOT PREFERRED. `devices/unregister` authenticates with the ACCESS
        // token, which the coordinator derives from the refresh token in the store — so once the
        // revoke has ended the session and `forget()` has wiped the store there is nothing left to
        // make this call with. Reversed, the row stays registered until FCM eventually reports the
        // token gone, and on a shared phone the previous account's notifications keep arriving.
        val store = storeWithSession()
        val push = RecordingPushApi(store = store)
        val revoke = RecordingRevokeApi(store = store)
        val subject = signOutContainer(store, revoke, push = push)

        subject.signOut()
        awaitEpoch(subject, 1)

        assertEquals(listOf("unregister"), push.calls)
        assertEquals(
            "the store must still hold the session when push is withdrawn",
            listOf(true),
            push.sessionPresentAtCall,
        )
        // ⚠️ And the revoke still happened, in the same run: the push call is an ADDITION to the
        // sequence, not a replacement for any of it.
        assertEquals(listOf("refresh-live"), revoke.presented)
    }

    @Test
    fun `a push unregister the server refuses does not stop the sign-out`() {
        // ⛔ PUSH IS A COURTESY CHANNEL AND MUST NEVER BE ABLE TO FAIL A SIGN-OUT. A device left
        // looking signed in because a notification registration could not be withdrawn is the worse
        // of the two failures, and it is the one the user can see. ⚠️ Unlike the REVOKE, there is
        // deliberately no outbox for this: the server retires the row itself once FCM reports the
        // token unregistered, so nothing is stranded by giving up.
        val store = storeWithSession()
        val push = RecordingPushApi(
            result = ApiResult.HttpFailure(status = 500, message = "boom"),
            store = store,
        )
        val revoke = RecordingRevokeApi(store = store)
        val subject = signOutContainer(store, revoke, push = push)

        subject.signOut()

        assertEquals(1, awaitEpoch(subject, 1))
        assertEquals(listOf("unregister"), push.calls)
        assertEquals(listOf("refresh-live"), revoke.presented)
        assertNull("the credential is still wiped", store.read())
    }

    @Test
    fun `signing out registers nothing, in either direction`() {
        // ⛔ THE REGRESSION THIS PINS IS A REAL DESIGN CHOICE ELSEWHERE. Push registration hangs off
        // `LoginController`'s success branch rather than off the session epoch, precisely because
        // the epoch is bumped by a sign-OUT too — a registrar driven from it would fire a register
        // with no bearer on the way out, spending a 401 against a 20/min per-account ceiling and
        // leaving the row pointed at the account that just left.
        val store = storeWithSession()
        val push = RecordingPushApi(store = store)
        val subject = signOutContainer(store, RecordingRevokeApi(store = store), push = push)

        subject.signOut()
        awaitEpoch(subject, 1)

        assertEquals(false, push.calls.contains("register"))
    }

    // ── Draining the outbox ──────────────────────────────────────────────────

    @Test
    fun `a pending revoke is retried and cleared on success`() = runBlocking {
        // ⚠️ Driven directly rather than through construction: the constructor's launch lands on
        // the container's scope, and awaiting it would mean polling for the absence of a value.
        // The behaviour under test is the same function either way.
        val store = RecordingTokenStore().apply { markRevokePending("refresh-stranded") }
        val api = RecordingRevokeApi(store = store)
        val subject = signOutContainer(store, api, idleScope())

        subject.drainPendingRevoke()

        assertEquals(listOf("refresh-stranded"), api.presented)
        assertNull("a token the server has accepted must stop being chased", store.pendingRevokeToken())
    }

    @Test
    fun `a pending revoke that fails again is left for the next launch`() = runBlocking {
        // ⛔ LEFT EXACTLY AS IT WAS. There is no attempt counter, deliberately: a counter would
        // only add a way to discard the entry while the credential is still live server-side.
        val store = RecordingTokenStore().apply { markRevokePending("refresh-stranded") }
        val subject = signOutContainer(
            store,
            RecordingRevokeApi(result = RevokeResult.RetryLater, store = store),
            idleScope(),
        )

        subject.drainPendingRevoke()

        assertEquals("refresh-stranded", store.pendingRevokeToken())
    }

    @Test
    fun `an empty outbox costs no network call`() = runBlocking {
        // ⚠️ THE OVERWHELMINGLY COMMON CASE, since this runs on every cold start. One store read
        // that returns null, and nothing else.
        val store = RecordingTokenStore()
        val api = RecordingRevokeApi(store = store)
        val subject = signOutContainer(store, api, idleScope())

        subject.drainPendingRevoke()

        assertEquals(emptyList<String>(), api.presented)
    }

    private companion object {
        /** Generous, because it bounds a real Keystore/SharedPreferences hop rather than a poll. */
        const val AWAIT_TIMEOUT_MS = 5_000L
    }
}
