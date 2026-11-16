package com.distronode.districtai.ui.setup

import com.distronode.districtai.core.model.SetupLookupDraft
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The step models on a wizard that holds no setup state.
 *
 * ⚠️ EVERY MODEL READS THE STATE THROUGH `wizard.data`, WHICH IS NULL until a first read succeeds (and
 * stays null after a refused one). These pin what a model built then answers: no facts, no number and
 * no region, rather than a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupBeforeFirstReadTest {

    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val harness = SetupHarness()

    private fun TestScope.unread(): SetupWizardViewModel {
        harness.store.save(SetupBusinessDraft("Bloom", "CA", "ON", "Ottawa", "en"), SetupHarness.WORKSPACE)
        harness.setup.setupResult = ApiResult.Forbidden("Owner only")
        val wizard = harness.wizard()
        advanceUntilIdle()
        assertNull(wizard.data)
        return wizard
    }

    @Test
    fun `the wizard has no region`() = runTest {
        assertNull(unread().region)
    }

    @Test
    fun `step 1 opens on the draft, saves its place and zone, and has room for what you do`() = runTest {
        val model = SetupBusinessModel(unread())
        assertEquals("Ottawa", model.city)
        assertEquals("", model.street)
        assertEquals("", model.what)
        assertEquals("the place's own zone", "America/Toronto", model.timezone)

        model.what = "Dentistry"
        assertFalse(model.faqsFull)
        val patch = model.factsPatch()
        assertEquals("Ottawa", patch["address"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        assertFalse("no stored postal code to carry", "postalCode" in patch["address"]!!.jsonObject)
    }

    @Test
    fun `step 1's website read fills the form with nothing stored to append to`() = runTest {
        val model = SetupBusinessModel(unread())
        model.street = ""
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(SetupLookupDraft(address = json("""{"line1":"1 Rue"}"""))),
        )
        model.websiteUrl = "example.com"
        model.readWebsite()
        advanceUntilIdle()
        assertEquals("1 Rue", model.street)
        assertEquals(SetupBusinessNotice.Found(0, 0, hours = false, address = true), model.notice)
    }

    @Test
    fun `step 4 opens empty`() = runTest {
        val model = SetupCallersModel(unread())
        assertTrue(model.services.isEmpty())
        assertEquals("", model.timezone)
    }

    @Test
    fun `step 6 holds no number, so there is no test call`() = runTest {
        val model = SetupGoLiveModel(unread())
        advanceUntilIdle()
        assertFalse(model.hasNumber)

        model.mobile = "+14165550123"
        model.callMe()
        advanceUntilIdle()
        assertTrue(harness.setup.testCalls.isEmpty())
        assertNull(model.callStatus)
        model.close()
    }
}
