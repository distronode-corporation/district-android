package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.PushRegistrationResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.PLATFORM_ANDROID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two push-registration calls, and the three ways a 200 can still mean "no".
 *
 * ⛔ EVERY ASSERTION HERE IS ABOUT NOT LYING, WHICH IS THE WHOLE JOB OF THIS LAYER. `register` and
 * `unregister` both answer a boolean, and the only value of that boolean is that it is TRUE only
 * when the server said so — because the caller uses it to decide nothing at all on the happy path
 * and everything on the sad one: a false unregister leaves a row that may still be delivering the
 * previous account's notifications to a handset somebody else is holding.
 */
class PushTokenRepositoryTest {

    private fun api(
        register: ApiResult<PushRegistrationResponse> =
            ApiResult.Success(PushRegistrationResponse(success = true)),
        unregister: ApiResult<PushRegistrationResponse> =
            ApiResult.Success(PushRegistrationResponse(success = true)),
    ) = FakeDistrictApi().apply {
        pushApi.pushRegisterResult = register
        pushApi.pushUnregisterResult = unregister
    }

    @Test
    fun `a register sends the token and names the platform explicitly`() = runTest {
        // ⛔ `platform` IS SENT EVEN THOUGH THE SERVER DEFAULTS IT. The column is NOT NULL and the
        // route's schema defaults it to "android", so omitting it works today — and would silently
        // mislabel every row the day an iOS client ships, because that client would have to
        // remember to send what this one relied on being assumed.
        val fake = api()
        val repository = PushTokenRepository(fake)

        assertTrue(repository.register("fcm-token-1"))

        assertEquals(1, fake.pushApi.pushRegisterRequests.size)
        assertEquals("fcm-token-1", fake.pushApi.pushRegisterRequests.single().token)
        assertEquals(PLATFORM_ANDROID, fake.pushApi.pushRegisterRequests.single().platform)
    }

    @Test
    fun `a blank token is refused without spending a request`() = runTest {
        // ⛔ A REAL CASE RATHER THAN A TIDINESS GUARD. `FirebaseMessaging.getToken()` fails on a
        // device with no Play Services and while the SDK is still provisioning, and the natural
        // shapes of "no token yet" are null and the empty string. Sending one spends a request
        // against a 20/min per-account ceiling to be told 400 — and a client that read that 400 as
        // "registration failed" would retry it.
        val fake = api()
        val repository = PushTokenRepository(fake)

        assertFalse(repository.register(""))
        assertFalse(repository.register("   "))

        assertEquals(emptyList<Any>(), fake.pushApi.pushRegisterRequests)
    }

    @Test
    fun `a 200 that does not affirm success is not a registration`() = runTest {
        // ⛔ `PushRegistrationResponse.success` DEFAULTS TO FALSE, so a `{}` body — or any 200 from
        // something that is not this API, a captive portal included — decodes cleanly into a
        // well-formed "no". Reading the flag is what makes it a no rather than a yes.
        val repository = PushTokenRepository(
            api(register = ApiResult.Success(PushRegistrationResponse(success = false))),
        )

        assertFalse(repository.register("fcm-token-1"))
    }

    @Test
    fun `a transport failure is false rather than an exception`() = runTest {
        // ⛔ IT MUST NOT THROW, AND THAT IS THE CONTRACT RATHER THAN DEFENSIVE HABIT. One caller
        // runs during sign-IN, where throwing would turn a successful login into a failed one, and
        // the other during sign-OUT, where throwing would abandon the token revoke that follows it.
        val repository = PushTokenRepository(
            api(register = ApiResult.HttpFailure(status = 429, message = "slow down")),
        )

        assertFalse(repository.register("fcm-token-1"))
    }

    @Test
    fun `an unregister that the server affirms is reported as done`() = runTest {
        val fake = api()
        val repository = PushTokenRepository(fake)

        assertTrue(repository.unregister())

        assertEquals(1, fake.pushApi.pushUnregisterCount)
    }

    @Test
    fun `an unregister that threw server-side is NOT reported as done`() = runTest {
        // ⛔ THE ASYMMETRY WITH `register` IS THE POINT. The route answers `{success:true}` even
        // when there was nothing to delete — deliberately, so it is not an existence oracle over
        // the device-id space — but a delete that THREW answers 500, and that means the row may
        // still be live and this handset may still receive another person's notifications after
        // they sign in on it. False here is "we do not know", not "there was nothing to remove".
        val repository = PushTokenRepository(
            api(unregister = ApiResult.HttpFailure(status = 500, message = "boom")),
        )

        assertFalse(repository.unregister())
    }

    @Test
    fun `registering twice is two requests, because the server upsert is idempotent`() = runTest {
        // ⚠️ THE REPOSITORY DELIBERATELY REMEMBERS NOTHING. A local "already registered" flag would
        // be a cache of server state that is wrong exactly after the case it exists to optimise — a
        // token rotation — and it would survive into a session belonging to a different account.
        // The server's write is keyed on the installation, so a repeat costs one request and is
        // always correct.
        val fake = api()
        val repository = PushTokenRepository(fake)

        repository.register("fcm-token-1")
        repository.register("fcm-token-2")

        assertEquals(
            listOf("fcm-token-1", "fcm-token-2"),
            fake.pushApi.pushRegisterRequests.map { it.token },
        )
    }
}
