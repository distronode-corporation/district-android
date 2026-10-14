package com.distronode.districtai.ui.marketplace

import com.distronode.districtai.R
import com.distronode.districtai.core.data.NumberProvisioningRepository
import com.distronode.districtai.core.model.CountryRequirements
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.core.model.NumberLookupInfo
import com.distronode.districtai.core.model.NumberRegistration
import com.distronode.districtai.core.model.NumberRegistrationsResponse
import com.distronode.districtai.core.model.NumberReleaseResponse
import com.distronode.districtai.core.model.NumberRequirementsResponse
import com.distronode.districtai.core.model.ProviderStatusEntry
import com.distronode.districtai.core.model.ProviderStatusResponse
import com.distronode.districtai.core.model.SipTrunk
import com.distronode.districtai.core.model.SipTrunksResponse
import com.distronode.districtai.core.model.VerifyServiceResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeCarrierAccountApi
import com.distronode.districtai.core.network.testing.FakeNumberProvisioningApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.UiText
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
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

@OptIn(ExperimentalCoroutinesApi::class)
class NumberProvisioningViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val numbers = FakeNumberProvisioningApi()
    private val carrier = FakeCarrierAccountApi()

    private fun viewModel(role: WorkspaceRole? = WorkspaceRole.AGENCY) =
        NumberProvisioningViewModel(NumberProvisioningRepository(numbers, carrier), "ws", role)

    private val NumberProvisioningViewModel.now get() = state.value

    // ── Reads ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the carrier tab reads carriers, the passcode sender and SIP`() = runTest(dispatcher) {
        numbers.providerStatusResult = ApiResult.Success(
            ProviderStatusResponse(
                connected = true,
                providers = mapOf("twilio" to ProviderStatusEntry(connected = true), "sinch" to ProviderStatusEntry()),
            ),
        )
        carrier.verifyResult = ApiResult.Success(VerifyServiceResponse(success = true, enabled = true))
        carrier.sipResult = ApiResult.Success(SipTrunksResponse(success = true, trunks = listOf(SipTrunk(id = "T1"))))
        val vm = viewModel()
        vm.loadCarrierTab()
        assertEquals(SectionState.Loading, vm.now.carrier)
        advanceUntilIdle()
        val accounts = (vm.now.carrier as SectionState.Ready).value
        assertEquals(listOf("twilio"), accounts.connected)
        assertEquals(listOf("sinch"), accounts.refused)
        assertFalse(accounts.notConnected)
        assertEquals(SectionState.Ready(VerifyService(true)), vm.now.verify)
        assertEquals(SectionState.Ready(listOf(SipTrunk(id = "T1"))), vm.now.sip)
    }

    @Test
    fun `failed reads say why, and no carrier is a state of its own`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.loadCarrier()
        advanceUntilIdle()
        assertTrue((vm.now.carrier as SectionState.Ready).value.notConnected)

        numbers.providerStatusResult = ApiResult.NetworkFailure(IOException())
        carrier.verifyResult = ApiResult.NotFound("gone")
        carrier.sipResult = ApiResult.HttpFailure(500, "down")
        vm.loadCarrierTab()
        advanceUntilIdle()
        assertTrue(vm.now.carrier is SectionState.Failed)
        assertTrue(vm.now.verify is SectionState.Failed)
        assertTrue(vm.now.sip is SectionState.Failed)
    }

    @Test
    fun `filings are read for a role that may file, and a viewer is not sent`() = runTest(dispatcher) {
        numbers.registrationsResult = ApiResult.Success(
            NumberRegistrationsResponse(true, listOf(NumberRegistration(id = "BU1")), listOf("CA"), listOf("US")),
        )
        val vm = viewModel()
        vm.loadRegistrations()
        advanceUntilIdle()
        assertEquals(
            SectionState.Ready(Registrations(listOf(NumberRegistration(id = "BU1")), listOf("CA"), listOf("US"))),
            vm.now.registrations
        )

        numbers.registrationsResult = ApiResult.Forbidden("no")
        vm.loadRegistrations()
        advanceUntilIdle()
        assertTrue(vm.now.registrations is SectionState.Failed)

        val viewer = viewModel(WorkspaceRole.VIEWER)
        viewer.loadRegistrations()
        assertEquals(SectionState.Ready(Registrations(emptyList(), emptyList(), emptyList())), viewer.now.registrations)
        assertFalse(viewer.canProvision)
    }

    @Test
    fun `requirements read the draft's country, upper-cased, and say when none apply`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.loadRequirements()
        assertEquals(RequirementsState.Idle, vm.now.requirements)
        vm.updateRegistrationDraft(RegistrationDraft(country = " ca "))
        vm.loadRequirements()
        assertEquals(RequirementsState.Loading, vm.now.requirements)
        advanceUntilIdle()
        assertEquals(RequirementsState.NoRegulation("CA", purchasable = true), vm.now.requirements)
        assertEquals("numberRequirements:ws:CA:local:null", numbers.calls.last())

        val regulation = CountryRequirements(isoCountry = "DE", friendlyName = "Germany")
        numbers.requirementsResult = ApiResult.Success(
            NumberRequirementsResponse(success = true, country = "DE", purchasable = false, requirements = regulation),
        )
        vm.loadRequirements()
        advanceUntilIdle()
        assertEquals(RequirementsState.Ready(regulation, purchasable = false), vm.now.requirements)

        numbers.requirementsResult = ApiResult.NetworkFailure(IOException())
        vm.loadRequirements()
        advanceUntilIdle()
        assertTrue(vm.now.requirements is RequirementsState.Failed)
    }

    // ── A number's actions ──────────────────────────────────────────────────────────────────────

    @Test
    fun `configure reapplies routing and says so`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.openNumber(ListedNumber(phoneNumber = "+14165550142"))
        vm.configure("+14165550142")
        assertEquals(NumberWriteState.Running, vm.now.numberWrite)
        // A second tap while it runs does nothing.
        vm.configure("+14165550142")
        advanceUntilIdle()
        assertEquals(1, numbers.calls.count { it.startsWith("configureNumber") })
        assertEquals(NumberWriteState.Done(UiText.Resource(R.string.numbers_configure_done)), vm.now.numberWrite)
        vm.closeNumber()
        assertNull(vm.now.selected)
    }

    @Test
    fun `a release is confirmed, runs once, and reports its warnings`() = runTest(dispatcher) {
        numbers.releaseResult = ApiResult.Success(NumberReleaseResponse(success = true, warnings = listOf("Rule kept.")))
        val vm = viewModel()
        vm.requestConfirmation(NumberConfirmation.Release("+1"))
        assertEquals(NumberConfirmation.Release("+1"), vm.now.confirmation)
        vm.confirm()
        assertNull("the confirmation closes as the write starts", vm.now.confirmation)
        advanceUntilIdle()
        assertEquals(
            NumberWriteState.Done(UiText.Resource(R.string.numbers_release_warning_eyebrow), listOf("Rule kept.")),
            vm.now.numberWrite,
        )
        numbers.releaseResult = ApiResult.Success(NumberReleaseResponse(success = true))
        vm.requestConfirmation(NumberConfirmation.Release("+2"))
        vm.confirm()
        advanceUntilIdle()
        assertEquals(NumberWriteState.Done(UiText.Resource(R.string.numbers_release_done)), vm.now.numberWrite)
    }

    @Test
    fun `a release that may have gone through is not offered again`() = runTest(dispatcher) {
        numbers.releaseResult = ApiResult.NetworkFailure(IOException("lost"))
        val vm = viewModel()
        vm.requestConfirmation(NumberConfirmation.Release("+1"))
        vm.confirm()
        advanceUntilIdle()
        val failed = vm.now.numberWrite as NumberWriteState.Failed
        assertFalse(failed.resubmitAllowed)
        assertTrue(failed.refusedRepeat)
        assertFalse(failed.isArmed)
        // Nor can a confirmation be asked for again.
        vm.requestConfirmation(NumberConfirmation.Release("+1"))
        assertNull(vm.now.confirmation)
    }

    @Test
    fun `opening a number clears another's notice, but not a write still running`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.configure("+1")
        advanceUntilIdle()
        vm.openNumber(ListedNumber(phoneNumber = "+2"))
        assertEquals(NumberWriteState.Idle, vm.now.numberWrite)

        numbers.releaseGate = CompletableDeferred()
        vm.requestConfirmation(NumberConfirmation.Release("+2"))
        vm.confirm()
        advanceUntilIdle()
        vm.openNumber(ListedNumber(phoneNumber = "+3"))
        assertEquals(NumberWriteState.Running, vm.now.numberWrite)
        numbers.releaseGate?.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a viewer cannot write or ask to`() = runTest(dispatcher) {
        val vm = viewModel(WorkspaceRole.VIEWER)
        vm.configure("+1")
        vm.requestConfirmation(NumberConfirmation.Lookup)
        vm.setVerify(true)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), numbers.calls + carrier.calls)
        assertNull(vm.now.confirmation)
    }

    @Test
    fun `dismissing or confirming nothing changes nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.confirm()
        vm.requestConfirmation(NumberConfirmation.A2P)
        vm.dismissConfirmation()
        assertNull(vm.now.confirmation)
    }

    // ── Carrier account ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the billed lookup runs once per confirmation and shows its answer`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.updateLookupNumber(" +14165550142 ")
        vm.requestConfirmation(NumberConfirmation.Lookup)
        vm.confirm()
        advanceUntilIdle()
        assertEquals("lookupNumber:ws:+14165550142", carrier.calls.single())
        assertEquals(LookupSummary(valid = true, lines = listOf("+14165550142")), vm.now.lookupResult)
        assertEquals(NumberWriteState.Idle, vm.now.lookupWrite)

        carrier.lookupResult = ApiResult.HttpFailure(400, "Invalid number")
        vm.requestConfirmation(NumberConfirmation.Lookup)
        vm.confirm()
        advanceUntilIdle()
        assertTrue((vm.now.lookupWrite as NumberWriteState.Failed).resubmitAllowed)
    }

    @Test
    fun `a lookup with no number, or a form not ready, only closes the confirmation`() = runTest(dispatcher) {
        val vm = viewModel()
        listOf(NumberConfirmation.Lookup, NumberConfirmation.A2P, NumberConfirmation.TollFree).forEach {
            vm.requestConfirmation(it)
            vm.confirm()
            assertNull(vm.now.confirmation)
        }
        advanceUntilIdle()
        assertEquals(emptyList<String>(), carrier.calls)
    }

    @Test
    fun `a lookup summary keeps what the carrier knew, and an invalid number has no lines`() {
        assertEquals(
            LookupSummary(true, listOf("+1", "CA", "Rogers")),
            LookupSummary.of(
                NumberLookupInfo(valid = true, phoneNumber = "+1", country = "CA", type = " ", carrier = "Rogers")
            ),
        )
        assertEquals(LookupSummary(false, emptyList()), LookupSummary.of(NumberLookupInfo(valid = false)))
    }

    @Test
    fun `A2P and toll-free adopt the carrier's status`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.updateA2PDraft(
            A2PDraft(businessName = "Acme ", campaignDescription = "d", sampleOne = "1", sampleTwo = "2", ein = " ")
        )
        vm.requestConfirmation(NumberConfirmation.A2P)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(
            NumberWriteState.Done(UiText.Resource(R.string.numbers_a2p_submitted, "PENDING")),
            vm.now.carrierWrite
        )
        assertEquals("Acme", carrier.a2pRequests.single().businessName)
        assertNull(carrier.a2pRequests.single().ein)

        vm.updateTollFreeDraft(
            TollFreeDraft(phoneNumber = "+18005550100", businessName = "Acme", optInImageUrl = "https://o")
        )
        vm.requestConfirmation(NumberConfirmation.TollFree)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(
            NumberWriteState.Done(UiText.Resource(R.string.numbers_tfv_submitted, "PENDING_REVIEW")),
            vm.now.carrierWrite
        )
        assertEquals(listOf("https://o"), carrier.tollFreeRequests.single().optInImageUrls)

        carrier.tollFreeResult = ApiResult.HttpFailure(503, "down")
        vm.requestConfirmation(NumberConfirmation.TollFree)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.now.carrierWrite.refusedRepeat)

        val a2pFailing = viewModel()
        carrier.a2pResult = ApiResult.Forbidden("no")
        a2pFailing.updateA2PDraft(
            A2PDraft(businessName = "A", campaignDescription = "d", sampleOne = "1", sampleTwo = "2")
        )
        a2pFailing.requestConfirmation(NumberConfirmation.A2P)
        a2pFailing.confirm()
        advanceUntilIdle()
        assertTrue(a2pFailing.now.carrierWrite is NumberWriteState.Failed)
    }

    @Test
    fun `a SIP trunk is created from a ready form, which then clears, and the list is read again`() = runTest(
        dispatcher
    ) {
        val vm = viewModel()
        vm.createSipTrunk()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), carrier.calls)

        vm.updateSipDraft(SipTrunkDraft(name = " PBX ", domain = "acme", ipAddresses = "1.2.3.4\n\n 5.6.7.0/24 "))
        vm.createSipTrunk()
        advanceUntilIdle()
        assertEquals(listOf("createSipTrunk:ws:PBX:acme:[1.2.3.4, 5.6.7.0/24]", "sipTrunks:ws"), carrier.calls)
        assertEquals(SipTrunkDraft(), vm.now.sipDraft)

        carrier.createSipResult = ApiResult.HttpFailure(400, "bad domain")
        carrier.sipResult = ApiResult.NetworkFailure(IOException())
        vm.updateSipDraft(SipTrunkDraft(name = "PBX", domain = "acme", ipAddresses = "1.2.3.4"))
        vm.createSipTrunk()
        advanceUntilIdle()
        assertTrue((vm.now.carrierWrite as NumberWriteState.Failed).resubmitAllowed)
    }

    @Test
    fun `a created trunk whose list read fails says the list failed`() = runTest(dispatcher) {
        carrier.sipResult = ApiResult.NetworkFailure(IOException())
        val vm = viewModel()
        vm.updateSipDraft(SipTrunkDraft(name = "PBX", domain = "acme", ipAddresses = "1.2.3.4"))
        vm.createSipTrunk()
        advanceUntilIdle()
        assertTrue(vm.now.sip is SectionState.Failed)
    }

    @Test
    fun `the passcode sender adopts the reply`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setVerify(true)
        advanceUntilIdle()
        assertEquals(SectionState.Ready(VerifyService(true)), vm.now.verify)
        carrier.setVerifyResult = ApiResult.HttpFailure(502, "carrier down")
        vm.setVerify(false)
        advanceUntilIdle()
        assertTrue(vm.now.carrierWrite.refusedRepeat)
    }

    // ── Registrations ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a filing is started from the draft, which then clears`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.createRegistration()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), numbers.calls)

        vm.updateRegistrationDraft(RegistrationDraft(country = "de", friendlyName = " "))
        vm.createRegistration()
        advanceUntilIdle()
        assertEquals(listOf("createNumberRegistration:ws:DE:local:null:null", "numberRegistrations:ws"), numbers.calls)
        assertEquals(RegistrationDraft(), vm.now.registrationDraft)

        numbers.createResult = ApiResult.NetworkFailure(IOException())
        vm.updateRegistrationDraft(RegistrationDraft(country = "DE", friendlyName = "Berlin"))
        vm.createRegistration()
        advanceUntilIdle()
        assertTrue("idempotent: offered again", (vm.now.registrationWrite as NumberWriteState.Failed).resubmitAllowed)
    }

    @Test
    fun `a document is removed and the list read again`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.removeDocument("BU1", "D1")
        advanceUntilIdle()
        assertEquals(listOf("deleteRegistrationDocument:ws:BU1:D1", "numberRegistrations:ws"), numbers.calls)
        numbers.removeResult = ApiResult.NotFound("gone")
        vm.removeDocument("BU1", "D1")
        advanceUntilIdle()
        assertTrue(vm.now.registrationWrite is NumberWriteState.Failed)
    }

    @Test
    fun `a filing is confirmed, filed once, and the list read again either way`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.requestConfirmation(NumberConfirmation.SubmitRegistration("BU1", "DE"))
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf("submitNumberRegistration:ws:BU1:{}", "numberRegistrations:ws"), numbers.calls)
        assertEquals(NumberWriteState.Done(UiText.Resource(R.string.numbers_submit_filed)), vm.now.registrationWrite)
    }

    @Test
    fun `a carrier's refusal of a filing is worded with its own sentence, or a prompt to re-read`() = runTest(
        dispatcher
    ) {
        numbers.submitResult = ApiResult.HttpFailure(422, "The address document is unreadable.")
        val vm = viewModel()
        vm.requestConfirmation(NumberConfirmation.SubmitRegistration("BU1", "DE"))
        vm.confirm()
        advanceUntilIdle()
        val refused = vm.now.registrationWrite as NumberWriteState.Failed
        assertEquals(
            UiText.Resource(R.string.numbers_submit_refused_because, "The address document is unreadable."),
            refused.failure.message,
        )
        assertTrue("a 422 is a refusal before the filing: offered again", refused.resubmitAllowed)

        numbers.submitResult = ApiResult.HttpFailure(422, " ")
        vm.requestConfirmation(NumberConfirmation.SubmitRegistration("BU1", "DE"))
        vm.confirm()
        advanceUntilIdle()
        assertEquals(
            UiText.Resource(R.string.numbers_submit_refused_reread),
            (vm.now.registrationWrite as NumberWriteState.Failed).failure.message,
        )

        numbers.submitResult = ApiResult.HttpFailure(409, "Already filed")
        vm.requestConfirmation(NumberConfirmation.SubmitRegistration("BU1", "DE"))
        vm.confirm()
        advanceUntilIdle()
        assertTrue((vm.now.registrationWrite as NumberWriteState.Failed).failure.message is UiText.Server)
    }

    @Test
    fun `a 422 outside the filings is worded as any other failure`() = runTest(dispatcher) {
        carrier.a2pResult = ApiResult.HttpFailure(422, "Invalid EIN")
        val vm = viewModel()
        vm.updateA2PDraft(A2PDraft(businessName = "A", campaignDescription = "d", sampleOne = "1", sampleTwo = "2"))
        vm.requestConfirmation(NumberConfirmation.A2P)
        vm.confirm()
        advanceUntilIdle()
        assertTrue((vm.now.carrierWrite as NumberWriteState.Failed).failure.message is UiText.Server)
    }

    @Test
    fun `the drafts say when they are ready`() {
        assertFalse(SipTrunkDraft(name = "n", domain = "d").isSubmittable)
        assertFalse(SipTrunkDraft(name = " ", domain = "d", ipAddresses = "1").isSubmittable)
        assertFalse(SipTrunkDraft(name = "n", domain = " ", ipAddresses = "1").isSubmittable)
        assertFalse(TollFreeDraft(phoneNumber = "+1", businessName = "A").isSubmittable)
        assertFalse(TollFreeDraft(phoneNumber = " ", businessName = "A", optInImageUrl = "u").isSubmittable)
        assertFalse(TollFreeDraft(phoneNumber = "+1", businessName = " ", optInImageUrl = "u").isSubmittable)
        assertFalse(A2PDraft(businessName = "A").isSubmittable)
        assertEquals("Berlin", RegistrationDraft(friendlyName = " Berlin ").trimmedName)
    }
}
