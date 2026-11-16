package com.distronode.districtai.ui.setup

import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.SETUP_STEP_SKIPPED
import com.distronode.districtai.core.model.SetupProgress
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeSetupPatch
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.l10n.AppLanguage
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore

/**
 * The wizard's shell: where it opens, who it refuses, the one step write at a time, and the re-read
 * after a page opened in a Custom Tab.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupWizardViewModelTest {

    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val harness = SetupHarness()

    @Test
    fun `it opens on the first step still to do, with the persona's language`() = runTest {
        harness.reads(setupState(business = SETUP_STEP_DONE, number = SETUP_STEP_SKIPPED))
        harness.config(SetupHarness.persona(language = "fr-CA"))
        val wizard = harness.wizard()
        assertEquals(SetupPhase.Loading, wizard.phase)
        advanceUntilIdle()

        assertEquals(SetupPhase.Ready, wizard.phase)
        assertEquals(SetupStep.RECEPTIONIST, wizard.active)
        assertTrue(wizard.stepModel is SetupReceptionistModel)
        assertEquals(SetupLanguage.FR, wizard.draft.setupLanguage)
        assertEquals("ca", wizard.region)
        assertEquals(SETUP_STEP_DONE, wizard.state(SetupStep.BUSINESS))
    }

    @Test
    fun `a draft kept on this device beats the persona's language`() = runTest {
        harness.store.save(SetupBusinessDraft("Bloom", "CA", "ON", "Ottawa", "en"), SetupHarness.WORKSPACE)
        harness.reads(setupState())
        harness.config(SetupHarness.persona(language = "fr-CA"))
        val wizard = harness.wizard()
        advanceUntilIdle()

        assertEquals("Bloom", wizard.draft.businessName)
        assertEquals(SetupLanguage.EN, wizard.draft.setupLanguage)
        assertTrue("the persona is not read for a kept draft", harness.district.configRequests.isEmpty())
    }

    @Test
    fun `an unreadable persona opens step 1 on the app's language`() = runTest {
        harness.reads(setupState())
        harness.district.workspaceConfigResult = ApiResult.NetworkFailure(IOException("down"))
        val wizard = harness.wizard(language = AppLanguage.FR)
        advanceUntilIdle()

        assertEquals(SetupLanguage.FR, wizard.draft.setupLanguage)
        assertTrue(wizard.stepModel is SetupBusinessModel)
    }

    @Test
    fun `a member is told only the owner can do setup`() = runTest {
        harness.setup.setupResult = ApiResult.Forbidden("Owner only", code = "workspace_owner_only")
        val wizard = harness.wizard()
        advanceUntilIdle()

        assertEquals(SetupPhase.Refused(SetupRefusal.NOT_OWNER), wizard.phase)
    }

    @Test
    fun `a workspace from before the wizard is told setup is not here, by a 409 or by no progress`() = runTest {
        harness.setup.setupResult = ApiResult.HttpFailure(409, "No setup", code = "setup_not_available")
        val refused = harness.wizard()
        advanceUntilIdle()
        assertEquals(SetupPhase.Refused(SetupRefusal.NOT_AVAILABLE), refused.phase)

        harness.reads(DistrictSetupResponse(setupProgress = null))
        val empty = harness.wizard()
        advanceUntilIdle()
        assertEquals(SetupPhase.Refused(SetupRefusal.NOT_AVAILABLE), empty.phase)
    }

    @Test
    fun `a failed read offers a retry, and the retry reads again`() = runTest {
        harness.setup.setupResult = ApiResult.NetworkFailure(IOException("offline"))
        val wizard = harness.wizard()
        advanceUntilIdle()
        assertTrue(wizard.phase is SetupPhase.Failed)

        harness.reads(setupState())
        wizard.load()
        advanceUntilIdle()
        assertEquals(SetupPhase.Ready, wizard.phase)
        assertEquals(2, harness.setup.reads.size)
    }

    @Test
    fun `a finished wizard says so`() = runTest {
        harness.reads(setupState(completedAt = "2026-11-01T00:00:00Z"))
        val wizard = harness.wizard()
        advanceUntilIdle()

        assertEquals(SetupPhase.Finished, wizard.phase)
    }

    @Test
    fun `skip marks the step skipped and moves on`() = runTest {
        harness.reads(setupState())
        harness.writes(setupState(business = SETUP_STEP_SKIPPED))
        val wizard = harness.wizard()
        advanceUntilIdle()

        wizard.skip()
        assertTrue(wizard.isMoving)
        advanceUntilIdle()

        assertEquals(
            listOf(FakeSetupPatch(SetupHarness.WORKSPACE, mapOf("business" to "skipped"), null)),
            harness.setup.patches
        )
        assertEquals(SetupStep.NUMBER, wizard.active)
        assertNull("step 2 has no model of its own", wizard.stepModel)
        assertFalse(wizard.isMoving)
    }

    @Test
    fun `two taps before the first write lands send one PATCH`() = runTest {
        harness.reads(setupState())
        val wizard = harness.wizard()
        advanceUntilIdle()
        harness.setup.gate = CompletableDeferred()

        wizard.skip()
        wizard.skip()
        wizard.completeStep(SetupStep.BUSINESS, SETUP_STEP_DONE)
        advanceUntilIdle()
        assertFalse("complete refuses while one is in flight", wizard.complete(SetupStep.BUSINESS, SETUP_STEP_DONE))
        harness.setup.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, harness.setup.patches.size)
    }

    @Test
    fun `back is refused mid-write, then moves to the step before and clears its error`() = runTest {
        harness.reads(setupState(business = SETUP_STEP_DONE))
        harness.setup.patchResult = ApiResult.HttpFailure(500, "Down")
        val wizard = harness.wizard()
        advanceUntilIdle()
        assertEquals(SetupStep.NUMBER, wizard.active)

        wizard.skip()
        advanceUntilIdle()
        assertTrue("the refusal is shown on the step", wizard.stepError != null)
        assertEquals(SetupStep.NUMBER, wizard.active)

        wizard.beginStepWrite()
        wizard.back()
        assertEquals(SetupStep.NUMBER, wizard.active)
        wizard.endStepWrite()

        wizard.back()
        assertEquals(SetupStep.BUSINESS, wizard.active)
        assertNull(wizard.stepError)
        wizard.back()
        assertEquals("there is nothing before step 1", SetupStep.BUSINESS, wizard.active)
    }

    @Test
    fun `the error can be put away`() = runTest {
        harness.reads(setupState())
        harness.setup.patchResult = ApiResult.HttpFailure(500, "Down")
        val wizard = harness.wizard()
        advanceUntilIdle()
        wizard.skip()
        advanceUntilIdle()

        wizard.dismissStepError()
        assertNull(wizard.stepError)
    }

    @Test
    fun `past the last step with one still open, the wizard goes back to the open one`() = runTest {
        harness.reads(setupState(golive = SETUP_STEP_DONE, business = SETUP_STEP_DONE, number = SETUP_STEP_DONE))
        harness.writes(setupState(business = SETUP_STEP_DONE, number = SETUP_STEP_DONE, golive = SETUP_STEP_DONE))
        val wizard = harness.wizard()
        advanceUntilIdle()
        // Finishing step 6 with step 3 still to do goes back to step 3, not past the end.
        assertEquals(SetupStep.RECEPTIONIST, wizard.active)

        assertTrue(wizard.complete(SetupStep.GOLIVE, SETUP_STEP_DONE))
        assertEquals(SetupStep.RECEPTIONIST, wizard.active)
    }

    @Test
    fun `the write that completes setup finishes the wizard and forgets the draft`() = runTest {
        harness.reads(setupState())
        harness.writes(setupState(completedAt = "2026-11-02T00:00:00Z"))
        val wizard = harness.wizard()
        advanceUntilIdle()
        wizard.updateDraft(SetupBusinessDraft(businessName = "Bloom"))
        assertEquals("Bloom", harness.store.load(SetupHarness.WORKSPACE)?.businessName)

        wizard.completeStep(SetupStep.GOLIVE, SETUP_STEP_DONE)
        advanceUntilIdle()

        assertEquals(SetupPhase.Finished, wizard.phase)
        assertNull(wizard.stepModel)
        assertNull(harness.store.load(SetupHarness.WORKSPACE))
    }

    @Test
    fun `coming back from a page the web finished moves on, and only then`() = runTest {
        harness.reads(setupState(business = SETUP_STEP_DONE, numbersHeld = 0))
        val wizard = harness.wizard()
        advanceUntilIdle()
        assertEquals(SetupStep.NUMBER, wizard.active)

        // ⚠️ NO HAND-OFF, NO RE-READ: an ordinary resume costs nothing.
        wizard.resumed()
        advanceUntilIdle()
        assertEquals(1, harness.setup.reads.size)

        harness.reads(setupState(business = SETUP_STEP_DONE, number = SETUP_STEP_DONE))
        wizard.handOffOpened()
        wizard.resumed()
        advanceUntilIdle()
        assertEquals(2, harness.setup.reads.size)
        assertEquals(SetupStep.RECEPTIONIST, wizard.active)
        assertEquals(1, wizard.data?.numbersHeld)
    }

    @Test
    fun `a step reached by Back stays on screen after a hand-off, and a failed re-read changes nothing`() = runTest {
        harness.reads(setupState(business = SETUP_STEP_DONE))
        val wizard = harness.wizard()
        advanceUntilIdle()
        wizard.back()
        val model = wizard.stepModel

        wizard.handOffOpened()
        wizard.resumed()
        advanceUntilIdle()
        assertEquals(SetupStep.BUSINESS, wizard.active)
        assertTrue("the same form, with what was typed", model === wizard.stepModel)

        harness.setup.setupResult = ApiResult.NetworkFailure(IOException("offline"))
        wizard.handOffOpened()
        wizard.resumed()
        advanceUntilIdle()
        assertEquals(SetupStep.BUSINESS, wizard.active)
    }

    @Test
    fun `a hand-off that finished setup on the web finishes the wizard`() = runTest {
        harness.reads(setupState())
        val wizard = harness.wizard()
        advanceUntilIdle()

        harness.reads(setupState(completedAt = "2026-11-03T00:00:00Z"))
        wizard.handOffOpened()
        wizard.resumed()
        advanceUntilIdle()

        assertEquals(SetupPhase.Finished, wizard.phase)
    }

    @Test
    fun `no re-read on return while the wizard is not on a step`() = runTest {
        harness.setup.setupResult = ApiResult.Forbidden("Owner only")
        val wizard = harness.wizard()
        advanceUntilIdle()

        wizard.handOffOpened()
        wizard.resumed()
        advanceUntilIdle()
        assertEquals(1, harness.setup.reads.size)
    }

    @Test
    fun `a rename is remembered so the overview reads its list again`() = runTest {
        harness.reads(setupState())
        val wizard = harness.wizard(name = "Old")
        advanceUntilIdle()
        assertFalse(wizard.didRename)

        wizard.renamed("New")
        assertEquals("New", wizard.workspaceName)
        assertTrue(wizard.didRename)
        wizard.adopt(DistrictSetupResponse(setupProgress = SetupProgress(), region = "eu"))
        assertEquals("eu", wizard.region)
    }

    @Test
    fun `clearing the wizard stops the step on screen`() = runTest {
        harness.reads(
            setupState(
                business = SETUP_STEP_DONE,
                number = SETUP_STEP_DONE,
                receptionist = SETUP_STEP_DONE,
                callers = SETUP_STEP_DONE,
                calls = SETUP_STEP_DONE,
            ),
        )
        val store = ViewModelStore()
        val wizard = ViewModelProvider.create(
            store,
            SetupWizardViewModel.factory(harness.deps, SetupHarness.WORKSPACE, "Bloom", AppLanguage.EN),
        )[SetupWizardViewModel::class]
        advanceUntilIdle()
        assertTrue(wizard.stepModel is SetupGoLiveModel)

        store.clear()
        advanceUntilIdle()
        assertEquals(SetupStep.GOLIVE, wizard.active)
    }
}
