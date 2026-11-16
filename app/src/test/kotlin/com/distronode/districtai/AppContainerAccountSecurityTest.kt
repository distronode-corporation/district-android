package com.distronode.districtai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.model.PushRegistrationResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.PushApi
import com.distronode.districtai.core.network.PushTokenRegisterRequest
import com.distronode.districtai.core.network.testing.FakeAccountApi
import com.distronode.districtai.core.network.testing.FakeAccountSecurityApi
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The account-security graph: one API seam behind both repositories, and one pending re-authentication. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AppContainerAccountSecurityTest {

    private object QuietPush : PushApi {
        override suspend fun registerPushToken(request: PushTokenRegisterRequest) =
            ApiResult.Success(PushRegistrationResponse(success = true))
        override suspend fun unregisterPushToken() = ApiResult.Success(PushRegistrationResponse(success = true))
    }

    @Test
    fun `both repositories and the SSO leg are built on the one seam`() {
        val api = FakeAccountSecurityApi()
        val subject = AppContainer(
            ApplicationProvider.getApplicationContext(),
            appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher()),
            seams = AppContainerSeams(
                accountApi = FakeAccountApi(),
                pushApi = QuietPush,
                pushTokenSource = { null },
                accountSecurityApi = api,
            ),
        )

        runBlocking {
            subject.twoStepSettingsRepository.status()
            subject.nativeReauthRepository.reauthenticate(
                com.distronode.districtai.core.model.NativeReauthAction.TWO_FACTOR_CHANGE,
                "pw",
            )
        }

        assertEquals(listOf("status", "reauthPassword"), api.calls)

        // The SSO leg opens the page on this installation's hash and hands the container's pending
        // re-authentication the callback MainActivity delivers.
        var opened = ""
        val outcome = runBlocking {
            val attempt = async {
                subject.ssoReauthenticator.reauthenticate(
                    com.distronode.districtai.ui.account.ReauthProvider.GOOGLE,
                    com.distronode.districtai.core.model.NativeReauthAction.TWO_FACTOR_CHANGE,
                ) {
                    opened = it
                    true
                }
            }
            while (opened.isEmpty()) yield()
            val state = android.net.Uri.parse(opened).getQueryParameter("state")
            assertEquals(true, subject.reauthCallbacks.deliver(state, "code-1"))
            attempt.await()
        }

        assertEquals(com.distronode.districtai.ui.account.SsoReauthOutcome.Proved(300), outcome)
        assertEquals(
            com.distronode.districtai.ui.account.SsoReauthenticator.sha256Hex(subject.deviceId),
            android.net.Uri.parse(opened).getQueryParameter("device"),
        )
        assertEquals("code-1", api.ssoExchanges.single()[1])
    }
}
