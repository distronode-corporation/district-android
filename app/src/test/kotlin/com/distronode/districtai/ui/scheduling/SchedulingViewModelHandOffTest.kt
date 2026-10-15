package com.distronode.districtai.ui.scheduling

import com.distronode.districtai.ApiEnvironment
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.handoff.DashboardHandOff
import com.distronode.districtai.handoff.HandOffCallbacks
import com.distronode.districtai.handoff.HandOffEvent
import com.distronode.districtai.ui.resourceIdOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

/**
 * The Scheduling screen's dashboard button over the nonce-bound hand-off (security item S33).
 *
 * ⚠️ SPLIT OUT OF [SchedulingViewModelTest] BECAUSE THAT CLASS REACHED detekt's LargeClass ceiling;
 * the fixtures are the same, built the same way, so the two files read as one suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingViewModelHandOffTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val readyTenant = SchedulingTenant(
        status = "ready",
        publicHost = "acme-book.distronode.com",
        region = "us",
        lastReadyAt = "2026-09-06T11:20:00.000Z",
        hasCredentials = true,
        bookingUrl = "https://acme-book.distronode.com/book/phone-consultation",
    )

    private fun api(block: FakeDistrictApi.() -> Unit = {}) = FakeDistrictApi().apply(block)

    /** ⚠️ `ApiEnvironment.baseUrl`, as the production graph wires it. See [SchedulingViewModelTest]. */
    private fun repository(api: FakeDistrictApi) =
        SchedulingRepository(api, ApiEnvironment.baseUrl)

    /** What the hand-off recorded, across every model built from [callbacks]. */
    private val recorded = mutableListOf<HandOffEvent>()

    /** ⚠️ ONE PER TEST, AS THE CONTAINER HOLDS ONE PER PROCESS: two models share its single slot. */
    private val callbacks = HandOffCallbacks { recorded += it }

    private fun handOff(api: FakeDistrictApi) =
        DashboardHandOff(repository(api), callbacks, { recorded += it })

    private fun model(api: FakeDistrictApi, workspaceId: String = "ws-1") =
        SchedulingViewModel(repository(api), handOff(api), workspaceId)

    // ── The nonce-bound dashboard hand-off (security item S33) ───────────────

    /** A browser that follows leg 1 back into the app at once, recording each URL it is given. */
    private fun callingBrowser(delivered: MutableList<String>): (String) -> Boolean = { url ->
        delivered += url
        if (url.contains("/dashboard/handoff/start")) callbacks.deliver(url.substringAfter("state="), NONCE)
        true
    }

    @Test
    fun `a browser that calls back gets a code bound to its nonce, and the button settles`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        val delivered = mutableListOf<String>()
        viewModel.openDashboard(callingBrowser(delivered))
        advanceUntilIdle()

        assertEquals(2, delivered.size)
        assertEquals(listOf(NONCE), api.schedulingApi.schedulingHandOffs.map { it.nonce })
        assertTrue(HandOffEvent.BOUND in recorded)
        assertFalse(viewModel.state.value.openingDashboard)
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun `a browser that never calls back mints nothing, and says to try again`() =
        runTest {
            val api = api {
                schedulingApi.schedulingStatusResult = ApiResult.Success(
                    SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
                )
            }
            val viewModel = model(api)
            advanceUntilIdle()

            val delivered = mutableListOf<String>()
            viewModel.openDashboard { delivered.add(it) }
            dispatcher.scheduler.runCurrent()
            assertTrue("waiting on the browser", viewModel.state.value.openingDashboard)

            advanceUntilIdle()

            // ⛔ NO UNBOUND FALLBACK (S33): only leg 1 was opened, nothing was minted, and the
            // retryable sentence is on screen with the button live again.
            assertEquals(1, delivered.size)
            assertTrue(api.schedulingApi.schedulingHandOffs.isEmpty())
            assertFalse(viewModel.state.value.openingDashboard)
            assertEquals(
                R.string.scheduling_handoff_unconfirmed,
                viewModel.state.value.notice?.resourceIdOrNull,
            )
        }

    @Test
    fun `the binding's two refusals get the same retry sentence, never update the app`() = runTest {
        for (code in listOf("nonce_required", "invalid_nonce")) {
            val api = api {
                schedulingApi.schedulingStatusResult = ApiResult.Success(
                    SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
                )
                schedulingApi.schedulingHandOffResult =
                    ApiResult.HttpFailure(status = 400, message = "Bad request", code = code)
            }
            val viewModel = model(api)
            advanceUntilIdle()

            viewModel.openDashboard(callingBrowser(mutableListOf()))
            advanceUntilIdle()

            assertEquals(
                code,
                R.string.scheduling_handoff_unconfirmed,
                viewModel.state.value.notice?.resourceIdOrNull,
            )
        }
    }

    @Test
    fun `a 400 with any other code, or none, is not mistaken for the binding`() = runTest {
        // ⚠️ ON THE CODE, NOT THE STATUS: a bare 400 is an ordinary client error and keeps the shared
        // mapping's wording.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingHandOffResult =
                ApiResult.HttpFailure(status = 400, message = "workspaceId is required", code = null)
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openDashboard(callingBrowser(mutableListOf()))
        advanceUntilIdle()

        assertEquals("workspaceId is required", viewModel.state.value.notice?.literalOrNull)
    }

    @Test
    fun `a press while another screen's hand-off is running does nothing and says nothing`() = runTest {
        // ⛔ ONE HAND-OFF PER PROCESS: a second leg 1 would replace the browser's nonce cookie. The
        // second screen's button settles at once rather than waiting on somebody else's browser.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val first = model(api, "ws-1")
        val second = model(api, "ws-2")
        advanceUntilIdle()

        val delivered = mutableListOf<String>()
        first.openDashboard { url -> delivered.add(url) }
        dispatcher.scheduler.runCurrent()
        second.openDashboard { true }
        dispatcher.scheduler.runCurrent()

        assertFalse(second.state.value.openingDashboard)
        assertNull(second.state.value.notice)
        assertTrue(first.state.value.openingDashboard)
        assertTrue(HandOffEvent.ALREADY_RUNNING in recorded)
        callbacks.deliver(delivered.single().substringAfter("state="), NONCE)
        advanceUntilIdle()
        assertEquals(listOf("ws-1"), api.schedulingApi.schedulingHandOffs.map { it.workspaceId })
    }

    @Test
    fun `no press of the dashboard button ever mints without the browser's nonce`() = runTest {
        // ⛔ THE S33 GUARANTEE ACROSS THE SCREEN'S PATHS: a calling browser, a silent one, and a
        // refusal. Every mint that happened carries the nonce; the silent one mints nothing.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openDashboard(callingBrowser(mutableListOf()))
        advanceUntilIdle()
        viewModel.openDashboard { true }
        advanceUntilIdle()
        api.schedulingApi.schedulingHandOffResult = ApiResult.Forbidden("Forbidden")
        viewModel.openDashboard(callingBrowser(mutableListOf()))
        advanceUntilIdle()

        assertEquals(2, api.schedulingApi.schedulingHandOffs.size)
        assertTrue(api.schedulingApi.schedulingHandOffs.all { it.nonce == NONCE })
    }

    private companion object {
        const val NONCE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456"
    }
}
