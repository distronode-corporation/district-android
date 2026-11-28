// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.auth.LoginStatus
import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.core.auth.RevokeApi
import com.distronode.districtai.core.auth.RevokeResult
import com.distronode.districtai.core.auth.TokenStore
import com.distronode.districtai.core.model.PushRegistrationResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.PushApi
import com.distronode.districtai.core.network.PushTokenRegisterRequest
import com.distronode.districtai.core.network.testing.FakeAccountApi
import com.distronode.districtai.core.data.PreferencesWorkspaceSelectionStore
import com.distronode.districtai.widget.PreferencesHomeWidgetStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the device forgets after the account is deleted from the app, and what it does NOT ask the
 * service for.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AppContainerDeletionTest {

    private class Store : TokenStore {
        var session: PersistedSession? = PersistedSession("refresh-live", Long.MAX_VALUE, "device-abcdefgh")
        var pendingRevoke: String? = null
        override fun read(): PersistedSession? = session
        override fun write(session: PersistedSession) {
            this.session = session
        }
        override fun clear() {
            session = null
        }
        override fun pendingRefreshToken(): String? = null
        override fun markRefreshPending(refreshToken: String) = Unit
        override fun clearRefreshPending() = Unit
        override fun pendingRevokeToken(): String? = pendingRevoke
        override fun markRevokePending(refreshToken: String) {
            pendingRevoke = refreshToken
        }
        override fun clearRevokePending() {
            pendingRevoke = null
        }
    }

    private class Revoke : RevokeApi {
        val presented = mutableListOf<String>()
        override suspend fun revoke(refreshToken: String): RevokeResult {
            presented += refreshToken
            return RevokeResult.Done
        }
    }

    private class Push : PushApi {
        val calls = mutableListOf<String>()
        override suspend fun registerPushToken(request: PushTokenRegisterRequest): ApiResult<PushRegistrationResponse> {
            calls += "register"
            return ApiResult.Success(PushRegistrationResponse(success = true))
        }
        override suspend fun unregisterPushToken(): ApiResult<PushRegistrationResponse> {
            calls += "unregister"
            return ApiResult.Success(PushRegistrationResponse(success = true))
        }
    }

    @Test
    fun `a deleted account is forgotten locally, with no revoke and no push unregister`() {
        val store = Store()
        val revoke = Revoke()
        val push = Push()
        val subject = AppContainer(
            ApplicationProvider.getApplicationContext(),
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            seams = AppContainerSeams(
                accountApi = FakeAccountApi(),
                tokenStore = store,
                revokeApi = revoke,
                pushApi = push,
                pushTokenSource = { null },
            ),
        )
        subject.workspaceRepository.select("ws-1")
        subject.homeWidget.workspaceRead().show("ws-1", "Alpha")
        subject.savedReads.own("user-a")
        subject.savedReads.asking(
            listOf("api", "district", "overview"),
            "https://example.test/api/district/overview".toHttpUrl(),
        )!!.answered("{}")
        val before = subject.sessionSignal.epoch.value

        subject.finishDeletedAccount(externalErasurePending = true)
        runBlocking { withTimeout(AWAIT_MS) { subject.sessionSignal.epoch.first { it > before } } }

        assertNull("the credential is gone", store.session)
        assertNull(
            "the workspace choice is gone",
            PreferencesWorkspaceSelectionStore(ApplicationProvider.getApplicationContext()).selectedWorkspaceId(),
        )
        assertNull(
            "the home-screen widget's snapshot is gone",
            PreferencesHomeWidgetStore(ApplicationProvider.getApplicationContext()).read(),
        )
        assertEquals(
            "the saved reads are gone",
            0,
            File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "saved-reads")
                .listFiles()!!.size,
        )
        assertTrue("nothing to revoke: the account and its sessions are gone", revoke.presented.isEmpty())
        assertNull("and nothing left in the outbox for later launches", store.pendingRevoke)
        assertTrue("no device row left to unregister", "unregister" !in push.calls)
        assertEquals(LoginStatus.AccountDeleted(externalErasurePending = true), subject.loginController.status.value)
    }

    private companion object {
        const val AWAIT_MS = 5_000L
    }
}
