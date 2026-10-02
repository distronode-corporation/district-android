package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.viewmodel.CreationExtras
import com.distronode.districtai.core.data.MessagingRepository
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_SINCH
import com.distronode.districtai.core.model.MessagingAccount
import com.distronode.districtai.core.model.MessagingResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
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
import org.junit.Before
import org.junit.Test

/**
 * The messaging ViewModel's construction and its single-flight probe.
 *
 * ⚠️ SPLIT FROM `MessagingViewModelTest` ONLY BECAUSE THAT CLASS REACHED DETEKT'S SIZE CEILING. The
 * fixture is the same shape: two carrier accounts, a default, and one channel override.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagingViewModelLifecycleTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val populated = MessagingResponse(
        success = true,
        accounts = listOf(
            MessagingAccount(id = "acct-twilio", provider = "twilio", label = "Twilio (main)"),
            MessagingAccount(id = "acct-telnyx", provider = "telnyx", label = "Telnyx (overflow)"),
        ),
        defaultAccountId = "acct-telnyx",
        channelDefaults = mapOf("sms" to "acct-twilio"),
    )

    private fun api() = TestDistrictApi().apply {
        messagingApi.messagingResult = ApiResult.Success(populated)
    }

    private fun viewModel(api: TestDistrictApi) =
        MessagingViewModel(MessagingRepository(api), "ws-1", WorkspaceRole.CLIENT)

    @Test
    fun `a second probe press while the first is running sends nothing`() = runTest {
        // ⛔ ONE PRESS, ONE AUTHENTICATED THIRD-PARTY CALL. The route is capped at 10/min per
        // workspace, and a double tap would spend two of them to learn one answer.
        val api = api()
        val model = viewModel(api)
        advanceUntilIdle()
        model.startEditing(null)
        model.editDraft(
            model.state.value.draft!!.copy(
                provider = MESSAGING_PROVIDER_SINCH,
                secrets = mapOf(
                    "projectId" to "proj",
                    "keyId" to "kid",
                    "keySecret" to "ksec",
                    "applicationKey" to "akey",
                    "applicationSecret" to "asec",
                ),
            ),
        )

        model.testCredentials()
        assertEquals(MessagingTestState.Running, model.state.value.test)
        model.testCredentials()
        advanceUntilIdle()

        assertEquals(1, api.messagingApi.messagingTests.size)
    }

    @Test
    fun `the factory builds a ViewModel that reads the workspace and carries the role gate`() =
        runTest {
            val api = api()
            val model = MessagingViewModel
                .factory(MessagingRepository(api), "ws-1", WorkspaceRole.VIEWER)
                .create(MessagingViewModel::class.java, CreationExtras.Empty)
            advanceUntilIdle()

            assertEquals(listOf("ws-1"), api.messagingApi.messagingRequests)
            assertEquals(populated, (model.state.value.load as MessagingLoadState.Ready).messaging)
            assertFalse("a viewer's ViewModel offers no edits", model.state.value.canEdit)
        }
}
