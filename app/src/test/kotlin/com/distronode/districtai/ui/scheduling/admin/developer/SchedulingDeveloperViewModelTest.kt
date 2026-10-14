package com.distronode.districtai.ui.scheduling.admin.developer

import androidx.lifecycle.viewmodel.CreationExtras
import com.distronode.districtai.core.model.SchedulingApiKey
import com.distronode.districtai.core.model.SchedulingApiKeyCreated
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingOAuthConnection
import com.distronode.districtai.core.model.SchedulingWebhook
import com.distronode.districtai.core.model.SchedulingWebhookDelivery
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The developer screen's reads: the context, the tabs on demand, the deliveries, and the removals. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingDeveloperViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val key = SchedulingApiKey(id = "k1", name = "Zapier", createdAt = "2026-09-01T12:00:00Z")
    private val app = SchedulingOAuthConnection(id = "o1", clientName = "Claude")
    private val hook = SchedulingWebhook(id = "wh1", url = "https://a.example.com", events = listOf("booking.created"))
    private val delivery = SchedulingWebhookDelivery(
        id = "d1",
        webhookId = "wh1",
        event = "booking.created",
        status = "success",
        attemptCount = 1,
    )

    private fun rig(canManage: Boolean = true) = SchedulingAdminTestRig(canManage = canManage).apply {
        admin.value(SchedulingAdminOp.ME_GET, schedulingMe(timezone = "America/Toronto"))
        admin.value(SchedulingAdminOp.API_KEYS_LIST, SchedulingItems(listOf(key)))
        admin.value(SchedulingAdminOp.OAUTH_CONNECTIONS_LIST, SchedulingItems(listOf(app)))
        admin.value(SchedulingAdminOp.WEBHOOKS_LIST, SchedulingItems(listOf(hook)))
        admin.value(SchedulingAdminOp.WEBHOOKS_DELIVERIES, SchedulingItems(listOf(delivery)))
    }

    private fun viewModel(rig: SchedulingAdminTestRig) =
        SchedulingDeveloperViewModel(rig.adminRepository, rig.scheduling, "ws")

    @Test
    fun `the context is read first, then only the tab on screen, once`() = runTest(dispatcher) {
        val rig = rig()
        val vm = viewModel(rig)
        assertNull(vm.state.value.keys)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("America/Toronto", state.timezone)
        assertEquals("acme-book.example", state.publicHost)
        assertTrue(state.canManage)
        assertEquals(SectionState.Ready(listOf(key)), state.keys)
        assertNull(state.apps)
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_LIST).isEmpty())

        vm.selectTab(SchedulingDeveloperTab.APPS)
        assertEquals(SectionState.Loading, vm.state.value.apps)
        advanceUntilIdle()
        assertEquals(SectionState.Ready(listOf(app)), vm.state.value.apps)
        vm.selectTab(SchedulingDeveloperTab.KEYS)
        vm.selectTab(SchedulingDeveloperTab.APPS)
        advanceUntilIdle()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.API_KEYS_LIST).size)
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.OAUTH_CONNECTIONS_LIST).size)

        vm.reload()
        advanceUntilIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.OAUTH_CONNECTIONS_LIST).size)
        vm.selectTab(SchedulingDeveloperTab.KEYS)
        vm.reload()
        advanceUntilIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.API_KEYS_LIST).size)

        // A full load keeps the tab and reads everything again.
        vm.load()
        assertEquals(SchedulingDeveloperTab.KEYS, vm.state.value.tab)
        assertNull(vm.state.value.keys)
        advanceUntilIdle()
        assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.API_KEYS_LIST).size)
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.ME_GET).size)
    }

    @Test
    fun `a failed profile reads as UTC and a refused status draws no writes`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = false, publicHost = null)
        val vm = viewModel(rig)
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("UTC", state.timezone)
        assertNull(state.publicHost)
        assertFalse(state.canManage)
        assertTrue(state.keys is SectionState.Failed)
    }

    @Test
    fun `deliveries open on demand, close, swap rows, re-read on reopen and drop a late answer`() =
        runTest(dispatcher) {
            val rig = rig()
            val vm = viewModel(rig)
            vm.selectTab(SchedulingDeveloperTab.WEBHOOKS)
            advanceUntilIdle()
            assertEquals(SectionState.Ready(listOf(hook)), vm.state.value.webhooks)

            vm.toggleDeliveries("wh1")
            assertEquals(SchedulingOpenDeliveries("wh1"), vm.state.value.deliveries)
            advanceUntilIdle()
            assertEquals(SectionState.Ready(listOf(delivery)), vm.state.value.deliveries?.read)
            val read = rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_DELIVERIES).single()
            assertEquals(JsonPrimitive("wh1"), read.params["id"])
            vm.toggleDeliveries("wh1")
            assertNull(vm.state.value.deliveries)

            // wh1's answer lands after the member has swapped to wh2, and is dropped.
            vm.toggleDeliveries("wh1")
            vm.toggleDeliveries("wh2")
            advanceUntilIdle()
            assertEquals("wh2", vm.state.value.deliveries?.webhookId)
            assertEquals(SectionState.Ready(listOf(delivery)), vm.state.value.deliveries?.read)
            assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_DELIVERIES).size)

            // Closed before its answer landed: the answer is dropped.
            vm.toggleDeliveries("wh2")
            assertNull(vm.state.value.deliveries)
            vm.toggleDeliveries("wh1")
            vm.toggleDeliveries("wh1")
            advanceUntilIdle()
            assertNull(vm.state.value.deliveries)

            vm.reload()
            assertNull(vm.state.value.deliveries)
            advanceUntilIdle()
            assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_LIST).size)
        }

    @Test
    fun `each removal sends its own op with the row's id and re-reads the tab`() = runTest(dispatcher) {
        val rig = rig()
        rig.admin.value(SchedulingAdminOp.API_KEYS_DELETE, SchedulingNoContent(ok = true))
        rig.admin.value(SchedulingAdminOp.OAUTH_CONNECTIONS_DELETE, SchedulingNoContent(ok = true))
        rig.admin.value(SchedulingAdminOp.WEBHOOKS_DELETE, SchedulingNoContent(ok = true))
        val vm = viewModel(rig)
        advanceUntilIdle()

        vm.removals.keyRevoke.ask(key)
        vm.removals.keyRevoke.confirm()
        advanceUntilIdle()
        val keyDelete = rig.admin.callsTo(SchedulingAdminOp.API_KEYS_DELETE).single()
        assertEquals(JsonPrimitive("k1"), keyDelete.params["id"])
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.API_KEYS_LIST).size)

        vm.selectTab(SchedulingDeveloperTab.APPS)
        advanceUntilIdle()
        vm.removals.appRevoke.ask(app)
        vm.removals.appRevoke.confirm()
        advanceUntilIdle()
        val appDelete = rig.admin.callsTo(SchedulingAdminOp.OAUTH_CONNECTIONS_DELETE).single()
        assertEquals(JsonPrimitive("o1"), appDelete.params["id"])
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.OAUTH_CONNECTIONS_LIST).size)

        vm.selectTab(SchedulingDeveloperTab.WEBHOOKS)
        advanceUntilIdle()
        vm.removals.webhookDelete.ask(hook)
        vm.removals.webhookDelete.confirm()
        advanceUntilIdle()
        val hookDelete = rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_DELETE).single()
        assertEquals(JsonPrimitive("wh1"), hookDelete.params["id"])
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.WEBHOOKS_LIST).size)
        assertEquals(hook, vm.removals.webhookDelete.state.value.removed)
    }

    @Test
    fun `a successful create re-reads the list behind it, and the factory builds the model`() = runTest(dispatcher) {
        val rig = rig()
        val vm = SchedulingDeveloperViewModel.factory(rig.adminRepository, rig.scheduling, "ws")
            .create(SchedulingDeveloperViewModel::class.java, CreationExtras.Empty)
        advanceUntilIdle()
        rig.admin.value(
            SchedulingAdminOp.API_KEYS_CREATE,
            SchedulingApiKeyCreated(id = "k2", name = "CI", key = "cno_x"),
        )
        vm.writes.keys.open()
        vm.writes.keys.editName("CI")
        vm.writes.keys.submit()
        advanceUntilIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.API_KEYS_LIST).size)
    }
}
