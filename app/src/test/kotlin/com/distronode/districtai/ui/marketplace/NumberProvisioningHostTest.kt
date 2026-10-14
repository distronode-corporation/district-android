package com.distronode.districtai.ui.marketplace

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.NumberProvisioningRepository
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.testing.FakeCarrierAccountApi
import com.distronode.districtai.core.network.testing.FakeNumberProvisioningApi
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The marketplace's number-management host reads each tab as it opens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class NumberProvisioningHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `each tab is read as it opens, and the owned tabs read nothing`() {
        val numbers = FakeNumberProvisioningApi()
        val carrier = FakeCarrierAccountApi()
        val repository = NumberProvisioningRepository(numbers, carrier)
        val tab = mutableStateOf(MarketplaceTab.OWNED)
        var ui: ProvisioningUi? = null
        composeRule.setContent {
            DistrictTheme { ui = numberProvisioningUi(repository, "ws", WorkspaceRole.CLIENT, tab.value) }
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), numbers.calls + carrier.calls)
        assertTrue(ui!!.canProvision)

        tab.value = MarketplaceTab.SEARCH
        composeRule.waitForIdle()
        tab.value = MarketplaceTab.REGISTRATIONS
        composeRule.waitForIdle()
        assertEquals(listOf("numberRegistrations:ws"), numbers.calls)
        tab.value = MarketplaceTab.CARRIER
        composeRule.waitForIdle()
        assertEquals(listOf("numberRegistrations:ws", "providerStatus:ws"), numbers.calls)
        assertEquals(listOf("verifyService:ws", "sipTrunks:ws"), carrier.calls)
    }
}
