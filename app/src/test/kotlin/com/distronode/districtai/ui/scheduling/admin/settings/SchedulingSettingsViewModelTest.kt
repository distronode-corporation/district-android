package com.distronode.districtai.ui.scheduling.admin.settings

import androidx.lifecycle.viewmodel.CreationExtras
import com.distronode.districtai.core.model.SchedulingLlmSettings
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The settings reads: only the tab on screen, once; the shared profile read; adopting what a write stored. */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val me = schedulingMe()
    private val llm = SchedulingLlmSettings(enabled = true, extraInstructions = "")

    private fun rig(canManage: Boolean = true) = SchedulingAdminTestRig(canManage = canManage).apply {
        admin.value(SchedulingAdminOp.ME_GET, me)
        admin.value(SchedulingAdminOp.SETTINGS_BRANDING_GET, schedulingBranding())
        admin.value(SchedulingAdminOp.SETTINGS_LLM_GET, llm)
    }

    @Test
    fun `only the tab on screen reads, once, and the profile tabs share one read`() = runTest(dispatcher) {
        val rig = rig()
        val vm = SchedulingSettingsViewModel(rig.adminRepository, rig.scheduling, "ws")
        assertNull(vm.state.value.branding)
        advanceUntilIdle()
        assertTrue(vm.state.value.canManage)
        assertEquals(SectionState.Ready(schedulingBranding()), vm.state.value.branding)
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.ME_GET).isEmpty())

        vm.selectTab(SchedulingSettingsTab.ASSISTANT)
        assertEquals(SectionState.Loading, vm.state.value.assistant)
        advanceUntilIdle()
        assertEquals(SectionState.Ready(llm), vm.state.value.assistant)
        vm.selectTab(SchedulingSettingsTab.PROFILE)
        advanceUntilIdle()
        vm.selectTab(SchedulingSettingsTab.NOTIFICATIONS)
        vm.selectTab(SchedulingSettingsTab.BOOKING_PAGE)
        advanceUntilIdle()
        assertEquals(SectionState.Ready(me), vm.state.value.profile)
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.ME_GET).size)
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.SETTINGS_BRANDING_GET).size)

        vm.reload()
        vm.selectTab(SchedulingSettingsTab.ASSISTANT)
        vm.reload()
        vm.selectTab(SchedulingSettingsTab.NOTIFICATIONS)
        vm.reload()
        advanceUntilIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.SETTINGS_BRANDING_GET).size)
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.SETTINGS_LLM_GET).size)
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.ME_GET).size)

        vm.load()
        assertEquals(SchedulingSettingsTab.NOTIFICATIONS, vm.state.value.tab)
        assertNull(vm.state.value.profile)
        advanceUntilIdle()
        assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.ME_GET).size)
    }

    @Test
    fun `a refused status draws no client-level editors and a failed read says so`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = false)
        val vm = SchedulingSettingsViewModel.factory(rig.adminRepository, rig.scheduling, "ws")
            .create(SchedulingSettingsViewModel::class.java, CreationExtras.Empty)
        advanceUntilIdle()
        assertFalse(vm.state.value.canManage)
        assertTrue(vm.state.value.branding is SectionState.Failed)
    }

    @Test
    fun `what each write stored is adopted without another read`() = runTest(dispatcher) {
        val rig = rig()
        rig.admin.value(SchedulingAdminOp.ME_PATCH, me.copy(name = "Ana L"))
        rig.admin.value(SchedulingAdminOp.SETTINGS_BRANDING_PATCH, schedulingBranding().copy(businessName = "Acme"))
        rig.admin.value(SchedulingAdminOp.SETTINGS_LLM_PATCH, llm.copy(enabled = false))
        val vm = SchedulingSettingsViewModel(rig.adminRepository, rig.scheduling, "ws")
        advanceUntilIdle()

        vm.writes.profile.open(me)
        vm.writes.profile.save()
        advanceUntilIdle()
        assertEquals("Ana L", (vm.state.value.profile as SectionState.Ready).value.name)
        vm.writes.branding.open(schedulingBranding())
        vm.writes.branding.save()
        advanceUntilIdle()
        assertEquals("Acme", (vm.state.value.branding as SectionState.Ready).value.businessName)
        vm.writes.assistant.open(llm)
        vm.writes.assistant.editEnabled(false)
        vm.writes.assistant.save()
        advanceUntilIdle()
        assertEquals(false, (vm.state.value.assistant as SectionState.Ready).value.enabled)
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.SETTINGS_BRANDING_GET).size)
    }
}
