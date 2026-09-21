package com.distronode.districtai.push

import com.distronode.districtai.core.data.PushTokenRepository
import com.distronode.districtai.core.model.PushRegistrationResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.PushApi
import com.distronode.districtai.core.network.PushTokenRegisterRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A [PushApi] whose calls are recorded and whose latency is settable.
 *
 * ⚠️ THE DELAY IS WHAT MAKES THE SIGN-OUT BOUND TESTABLE. The registrar's whole reason to exist on
 * the sign-out path is that it must not let a courtesy call hold a button press; asserting that
 * needs a call that does not return, which a plain recorder cannot provide.
 */
private class RecordingPushApi(
    private val result: ApiResult<PushRegistrationResponse> =
        ApiResult.Success(PushRegistrationResponse(success = true)),
    private val delayMillis: Long = 0,
) : PushApi {
    val calls: MutableList<String> = mutableListOf()

    override suspend fun registerPushToken(
        request: PushTokenRegisterRequest,
    ): ApiResult<PushRegistrationResponse> {
        calls += "register:${request.token}"
        if (delayMillis > 0) delay(delayMillis)
        return result
    }

    override suspend fun unregisterPushToken(): ApiResult<PushRegistrationResponse> {
        calls += "unregister"
        if (delayMillis > 0) delay(delayMillis)
        return result
    }
}

/**
 * WHEN this installation's push token is registered, and when it is withdrawn.
 *
 * ⛔ THE REPOSITORY OWNS THE TWO CALLS; THIS CLASS OWNS THE ORDERING, AND THE ORDERING IS THE PART
 * WITH RULES. Three of them, each with a consequence that is invisible from the repository: a
 * register before a login has no bearer, a missed token rotation stops push SILENTLY, and an
 * unregister after the revoke has no credential left to make the call with.
 *
 * ⚠️ EVERY TEST DRIVES A FAKE TOKEN SOURCE. The real one is `FirebaseMessaging.getInstance()`,
 * which throws with no default `FirebaseApp` and fails outright on any device without Play Services
 * — including every Waydroid image this project can run — so a test against it would pass for the
 * wrong reason on the only machine available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrarTest {

    private fun registrar(
        api: RecordingPushApi,
        scope: CoroutineScope,
        token: String? = "fcm-token-1",
        timeoutMillis: Long = 5_000L,
    ) = PushRegistrar(
        repository = PushTokenRepository(api),
        tokens = { token },
        scope = scope,
        unregisterTimeoutMillis = timeoutMillis,
    )

    @Test
    fun `a completed sign-in registers the current token`() = runTest {
        // ⛔ AND NOT AT APP START. Before a login there is no bearer, so a register would spend a
        // 401 against a 20/min per-account ceiling and record nothing — and the server's upsert is
        // keyed on the INSTALLATION, so a phone previously signed in as somebody else keeps
        // delivering THEIR notifications until this account claims the row.
        val api = RecordingPushApi()

        registrar(api, this).onSignedIn()
        advanceUntilIdle()

        assertEquals(listOf("register:fcm-token-1"), api.calls)
    }

    @Test
    fun `a token rotation registers the token it was handed, not one it fetched`() = runTest {
        // ⚠️ `onNewToken` ALREADY HAS THE NEW VALUE. Asking the SDK again would be a second source
        // of truth that can disagree with the callback that woke us — which is precisely the moment
        // a disagreement is most likely.
        val api = RecordingPushApi()

        registrar(api, this, token = "stale-token").onNewToken("rotated-token")
        advanceUntilIdle()

        assertEquals(listOf("register:rotated-token"), api.calls)
    }

    @Test
    fun `no token means no request at all`() = runTest {
        // ⚠️ THE ORDINARY ANSWER ON A DEVICE WITH NO PLAY SERVICES, and on a build whose package
        // Firebase has never been told about — `DistrictFirebase.firebaseAppIdFor` refuses rather
        // than guessing, so this is the state it deliberately leaves behind.
        val api = RecordingPushApi()

        registrar(api, this, token = null).onSignedIn()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `a failed registration is swallowed rather than surfacing into the login`() = runTest {
        // ⛔ IT RUNS INSIDE THE TOKEN EXCHANGE'S COROUTINE, immediately before the epoch bump every
        // screen reloads on. Anything that threw here would abort a login that has already
        // succeeded — the tokens are adopted by this point.
        val api = RecordingPushApi(result = ApiResult.HttpFailure(status = 500, message = "boom"))

        registrar(api, this).onSignedIn()
        advanceUntilIdle()

        assertEquals(listOf("register:fcm-token-1"), api.calls)
    }

    @Test
    fun `unregister reports what the server affirmed`() = runTest {
        val api = RecordingPushApi()

        assertTrue(registrar(api, this).unregister())

        assertEquals(listOf("unregister"), api.calls)
    }

    @Test
    fun `an unregister that outruns its bound gives up rather than holding the sign-out`() = runTest {
        // ⛔ THE BOUND IS THE WHOLE REASON THIS METHOD IS NOT JUST A PASS-THROUGH. It runs FIRST in
        // `signOut`, ahead of the revoke, because it needs a live bearer — so an unbounded call
        // would hold the user's button press for OkHttp's own thirty-second call timeout, and would
        // delay the revoke, which is the security-relevant half. On expiry the sign-out proceeds and
        // the server retires the row itself once FCM reports the token gone.
        val api = RecordingPushApi(delayMillis = 30_000)

        val affirmed = registrar(api, this, timeoutMillis = 1_000).unregister()

        assertFalse("a timed-out unregister must not be reported as done", affirmed)
        assertEquals("the attempt was still made", listOf("unregister"), api.calls)
    }

    @Test
    fun `a refused unregister is false rather than an exception`() = runTest {
        // ⚠️ FALSE COVERS BOTH "we could not reach it" AND "we ran out of time", which are the same
        // thing to the caller: it does not know, and it proceeds anyway.
        val api = RecordingPushApi(result = ApiResult.HttpFailure(status = 500, message = "boom"))

        assertFalse(registrar(api, this).unregister())
    }

    @Test
    fun `nothing registers on the way out`() = runTest {
        // ⛔ THE REASON REGISTRATION HANGS OFF `LoginController`'s SUCCESS BRANCH RATHER THAN OFF
        // THE SESSION EPOCH. The epoch is bumped by a sign-OUT too, so a registrar driven from it
        // would fire a register with no bearer on the way out — a 401 against a 20/min ceiling, and
        // a row left pointed at the account that just left.
        val api = RecordingPushApi()

        registrar(api, this).unregister()
        advanceUntilIdle()

        assertFalse(api.calls.any { it.startsWith("register") })
    }
}
