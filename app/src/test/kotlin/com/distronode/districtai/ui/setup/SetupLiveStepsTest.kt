package com.distronode.districtai.ui.setup

import com.distronode.districtai.core.model.CalendarResolution
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.ConnectionsResponse
import com.distronode.districtai.core.model.ForwardingCheck
import com.distronode.districtai.core.model.ForwardingCheckMode
import com.distronode.districtai.core.model.ForwardingCheckResponse
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.WorkspaceConfigSaveResponse
import com.distronode.districtai.core.model.WorkspaceConnection
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Steps 5 and 6: who answers, the hand-off facts, the test call, the forwarding check and Finish. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupLiveStepsTest {

    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val harness = SetupHarness()

    private val throughCallers = setupState(
        business = SETUP_STEP_DONE,
        number = SETUP_STEP_DONE,
        receptionist = SETUP_STEP_DONE,
        callers = SETUP_STEP_DONE,
    )

    private val throughCalls = setupState(
        business = SETUP_STEP_DONE,
        number = SETUP_STEP_DONE,
        receptionist = SETUP_STEP_DONE,
        callers = SETUP_STEP_DONE,
        calls = SETUP_STEP_DONE,
    )

    private fun TestScope.calls(): Pair<SetupWizardViewModel, SetupCallsModel> {
        harness.reads(throughCallers)
        harness.writes(throughCalls)
        val wizard = harness.wizard()
        advanceUntilIdle()
        return wizard to wizard.stepModel as SetupCallsModel
    }

    private val google = ConnectionsResponse(
        connections = listOf(
            WorkspaceConnection("google-calendar", "active"),
            WorkspaceConnection("microsoft-calendar", "active")
        ),
        calendar = CalendarResolution(activeProvider = "microsoft", source = "preference"),
    )

    // ── Step 5 ──────────────────────────────────────────────────────────────────

    @Test
    fun `step 5 reads who answers, the transfer number, the calendar and the plan`() = runTest {
        harness.handling(CallHandling.APP_FIRST, ring = 15)
        harness.config(
            SetupHarness.withTransfer(SetupHarness.persona(dgiEnabled = true), "+14165550100", tier = "VoiceStudio")
        )
        harness.setup.connectionsResult = ApiResult.Success(google)
        val (_, model) = calls()

        assertEquals(SetupStepLoad.Ready, model.load)
        assertEquals(CallHandling.APP_FIRST, model.mode)
        assertEquals(15, model.ringSeconds)
        assertEquals("+14165550100", model.transferNumber)
        assertEquals("Microsoft Outlook Calendar", model.calendarName)
        assertTrue(model.profilesOffered)
        assertTrue(model.profilesOn)
        assertEquals(listOf(CallHandling.AI_FIRST, CallHandling.APP_FIRST), model.modes)
        assertFalse(model.answeringChanged)
    }

    @Test
    fun `a mode chosen in Settings is shown and kept, and an unknown calendar is none`() = runTest {
        harness.handling(CallHandling.AI_THEN_APP)
        harness.config(SetupHarness.withTransfer(SetupHarness.persona(), " ", tier = "VoicePro"))
        harness.setup.connectionsResult = ApiResult.NetworkFailure(IOException("down"))
        val (_, model) = calls()

        assertEquals(3, model.modes.size)
        assertNull(model.transferNumber)
        assertNull(model.calendarName)
        assertFalse(model.profilesOffered)
        assertNull(SetupCallsModel.calendarName(ConnectionsResponse(connections = google.connections)))
        assertNull(
            "an errored row is not the calendar in use",
            SetupCallsModel.calendarName(
                google.copy(connections = listOf(WorkspaceConnection("microsoft-calendar", "error")))
            ),
        )
        assertEquals(
            "Google Calendar",
            SetupCallsModel.calendarName(google.copy(calendar = CalendarResolution("google")))
        )
        assertTrue(SetupCallsModel.profilesOffered("DGI"))
        assertFalse(SetupCallsModel.profilesOffered(null))
    }

    @Test
    fun `a failed read of who answers or of the config is a retry, never a form of defaults`() = runTest {
        harness.callHandling.handlingResult = ApiResult.NetworkFailure(IOException("down"))
        val (_, model) = calls()
        assertTrue(model.load is SetupStepLoad.Failed)

        harness.handling(CallHandling.AI_FIRST)
        harness.district.workspaceConfigResult = ApiResult.NetworkFailure(IOException("down"))
        model.load()
        advanceUntilIdle()
        assertTrue(model.load is SetupStepLoad.Failed)
    }

    @Test
    fun `only what changed is sent, and the echo is the new baseline`() = runTest {
        harness.handling(CallHandling.AI_FIRST, ring = 20)
        val (wizard, model) = calls()
        model.saveAnswering()
        assertTrue("nothing changed, nothing sent", harness.callHandling.handlingWrites.isEmpty())

        model.selectMode(CallHandling.APP_FIRST)
        model.selectRing(99)
        assertEquals(CallHandling.MAXIMUM_RING_SECONDS, model.ringSeconds)
        harness.handling(CallHandling.APP_FIRST, ring = 30)
        model.saveAnswering()
        model.saveAnswering()
        assertTrue(wizard.isMoving)
        advanceUntilIdle()
        assertEquals(listOf(CallHandling.APP_FIRST to 30), harness.callHandling.handlingWrites)
        assertTrue(model.answeringSaved)
        assertFalse(model.answeringChanged)

        model.selectRing(10)
        model.saveAnswering()
        advanceUntilIdle()
        assertEquals(null to 10, harness.callHandling.handlingWrites.last())

        model.selectMode(CallHandling.AI_FIRST)
        model.saveAnswering()
        advanceUntilIdle()
        assertEquals(CallHandling.AI_FIRST to null, harness.callHandling.handlingWrites.last())
    }

    @Test
    fun `a refused save keeps the choice and says why`() = runTest {
        val (_, model) = calls()
        model.selectMode(CallHandling.APP_FIRST)
        harness.callHandling.handlingResult = ApiResult.HttpFailure(400, "No")
        model.saveAnswering()
        advanceUntilIdle()
        assertTrue(model.error != null)
        assertTrue(model.answeringChanged)
    }

    @Test
    fun `Continue saves an unsaved choice first, and stays when that save fails`() = runTest {
        val (wizard, model) = calls()
        model.selectMode(CallHandling.APP_FIRST)
        harness.callHandling.handlingResult = ApiResult.HttpFailure(400, "No")
        model.saveAndContinue()
        model.saveAndContinue()
        advanceUntilIdle()
        assertEquals(1, harness.callHandling.handlingWrites.size)
        assertEquals(SetupStep.CALLS, wizard.active)

        harness.handling(CallHandling.APP_FIRST)
        model.saveAndContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.GOLIVE, wizard.active)
    }

    @Test
    fun `Continue while Save is writing the answering choice is refused`() = runTest {
        val (wizard, model) = calls()
        model.selectMode(CallHandling.APP_FIRST)
        model.saveAnswering()
        assertTrue(model.isSavingAnswering)
        model.saveAndContinue()
        advanceUntilIdle()
        assertEquals(1, harness.callHandling.handlingWrites.size)
        assertEquals(SetupStep.CALLS, wizard.active)
    }

    @Test
    fun `Continue with nothing to save moves on`() = runTest {
        val (wizard, model) = calls()
        model.saveAndContinue()
        advanceUntilIdle()
        assertTrue(harness.callHandling.handlingWrites.isEmpty())
        assertEquals(SetupStep.GOLIVE, wizard.active)
    }

    @Test
    fun `caller profiles send only the flip, and only where the plan has them`() = runTest {
        harness.config(SetupHarness.withTransfer(SetupHarness.persona(), null, tier = "voicestudio"))
        val (_, model) = calls()
        model.setProfiles(false)
        assertTrue(harness.district.personaPatches.isEmpty())

        model.setProfiles(true)
        model.setProfiles(true)
        advanceUntilIdle()
        assertEquals(true, harness.district.personaPatches.single().dgiEnabled)
        assertNull(harness.district.personaPatches.single().name)
        assertTrue(model.profilesOn)

        harness.district.savePersonaResult = ApiResult.HttpFailure(403, "dgi_requires_studio")
        model.setProfiles(false)
        advanceUntilIdle()
        assertTrue(model.profilesOn)
        assertTrue(model.error != null)
        harness.district.savePersonaResult = ApiResult.Success(WorkspaceConfigSaveResponse(success = true))
    }

    @Test
    fun `the step's writes run one at a time`() = runTest {
        harness.config(SetupHarness.withTransfer(SetupHarness.persona(), null, tier = "voicestudio"))
        val (wizard, model) = calls()

        model.selectMode(CallHandling.APP_FIRST)
        model.saveAnswering()
        model.setProfiles(true)
        advanceUntilIdle()
        assertEquals(1, harness.callHandling.handlingWrites.size)
        assertTrue("no profile write while who answers is saving", harness.district.personaPatches.isEmpty())

        model.setProfiles(true)
        model.selectMode(if (model.mode == CallHandling.AI_FIRST) CallHandling.APP_FIRST else CallHandling.AI_FIRST)
        assertTrue(model.answeringChanged)
        model.saveAnswering()
        model.saveAndContinue()
        advanceUntilIdle()
        assertEquals(1, harness.district.personaPatches.size)
        assertEquals("nothing else while the profile write runs", 1, harness.callHandling.handlingWrites.size)
        assertEquals(SetupStep.CALLS, wizard.active)
    }

    @Test
    fun `no profiles switch on a plan without them`() = runTest {
        val (_, model) = calls()
        model.setProfiles(true)
        advanceUntilIdle()
        assertTrue(harness.district.personaPatches.isEmpty())
    }

    @Test
    fun `back from a page the transfer number and calendar are read again, a failed read keeps them`() = runTest {
        harness.config(SetupHarness.withTransfer(SetupHarness.persona(), "+1"))
        val (wizard, model) = calls()
        model.selectMode(CallHandling.APP_FIRST)

        harness.config(SetupHarness.withTransfer(SetupHarness.persona(), "+2"))
        harness.setup.connectionsResult = ApiResult.Success(google)
        wizard.handOffOpened()
        wizard.resumed()
        advanceUntilIdle()
        assertEquals("+2", model.transferNumber)
        assertEquals("Microsoft Outlook Calendar", model.calendarName)
        assertEquals("an unsaved choice survives", CallHandling.APP_FIRST, model.mode)

        harness.district.workspaceConfigResult = ApiResult.NetworkFailure(IOException("down"))
        harness.setup.connectionsResult = ApiResult.NetworkFailure(IOException("down"))
        wizard.handOffOpened()
        wizard.resumed()
        advanceUntilIdle()
        assertEquals("+2", model.transferNumber)
    }

    @Test
    fun `a step that never loaded is not refreshed`() = runTest {
        harness.callHandling.handlingResult = ApiResult.NetworkFailure(IOException("down"))
        val (_, model) = calls()
        val before = harness.district.configRequests.size
        model.refreshAfterHandOff()
        assertEquals(before, harness.district.configRequests.size)
    }

    // ── Step 6 ──────────────────────────────────────────────────────────────────

    private fun TestScope.golive(
        region: String = "ca",
        numbersHeld: Int = 1,
    ): Pair<SetupWizardViewModel, SetupGoLiveModel> {
        harness.reads(throughCalls.copy(region = region, numbersHeld = numbersHeld))
        harness.writes(
            throughCalls.copy(setupProgress = throughCalls.setupProgress?.copy(completedAt = "2026-11-04T00:00:00Z"))
        )
        val wizard = harness.wizard()
        advanceUntilIdle()
        return wizard to wizard.stepModel as SetupGoLiveModel
    }

    private fun started(check: ForwardingCheck, left: Int) =
        ApiResult.Success(ForwardingCheckResponse(true, check, left))

    private fun check(status: String, failure: String? = null) = ForwardingCheck(
        id = "fwd",
        status = status,
        mode = "all",
        businessLine = "+14165550123",
        verifierNumber = "+14165550142",
        failure = failure,
    )

    @Test
    fun `the summary reads the greeting and who answers, and is left out when either read fails`() = runTest {
        harness.config(SetupHarness.persona(greeting = " Hello "))
        harness.handling(CallHandling.APP_FIRST, ring = 12)
        val (_, model) = golive()
        assertEquals(SetupGoLiveSummary("Hello", CallHandling.APP_FIRST, 12), model.summary)
        assertTrue(model.hasNumber)
        assertNull(model.checkSentence)

        harness.callHandling.handlingResult = ApiResult.NetworkFailure(IOException("down"))
        val (_, other) = golive()
        assertNull(other.summary)

        harness.handling(CallHandling.AI_FIRST)
        harness.district.workspaceConfigResult = ApiResult.NetworkFailure(IOException("down"))
        val (_, third) = golive()
        assertNull(third.summary)
    }

    @Test
    fun `the test call places one call to an E164 number and says it is calling`() = runTest {
        val (_, model) = golive()
        model.mobile = "12"
        model.callMe()
        assertTrue(model.mobileError)
        assertTrue(harness.setup.testCalls.isEmpty())

        harness.setup.gate = CompletableDeferred()
        model.mobile = "416 555 0123"
        model.callMe()
        model.callMe()
        assertFalse(model.mobileError)
        harness.setup.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("+14165550123"), harness.setup.testCalls)
        assertEquals(SetupCallStatus.Calling, model.callStatus)
        assertFalse(model.isCalling)

        harness.setup.gate = null
        harness.setup.testCallResult = ApiResult.RateLimited("Too many test calls today.")
        model.callMe()
        advanceUntilIdle()
        assertTrue(model.callStatus is SetupCallStatus.Failed)
    }

    @Test
    fun `no number, no test call`() = runTest {
        val (_, model) = golive(numbersHeld = 0)
        model.mobile = "+14165550123"
        model.callMe()
        assertFalse(model.hasNumber)
        assertTrue(harness.setup.testCalls.isEmpty())
    }

    @Test
    fun `a started check is polled until it passes`() = runTest {
        val (_, model) = golive()
        model.businessLine = "nope"
        model.startCheck()
        assertTrue(model.lineError)

        model.businessLine = "+14165550123"
        model.mode = ForwardingCheckMode.UNANSWERED
        harness.setup.forwardingStartResult = ApiResult.Success(ForwardingCheckResponse(true, check("pending"), 4))
        harness.setup.forwardingQueue += ApiResult.NetworkFailure(IOException("blip"))
        harness.setup.forwardingQueue += ApiResult.Success(ForwardingCheckResponse(true, check("passed"), 3))
        model.startCheck()
        model.startCheck()
        assertTrue(model.isStartingCheck)
        runCurrent()
        assertTrue(model.isPolling)
        assertEquals(SetupCheckSentence.PENDING, model.checkSentence)
        advanceUntilIdle()

        assertEquals(listOf("+14165550123" to "unanswered"), harness.setup.forwardingStarts)
        assertEquals(SetupCheckSentence.PASSED, model.checkSentence)
        assertFalse(model.isPolling)
        assertFalse(model.pollGaveUp)
        assertEquals(3, model.forwarding?.attemptsLeft)
    }

    @Test
    fun `a check pending at the limit says so, and the button reads again instead of calling again`() = runTest {
        harness.setup.forwardingResult = ApiResult.Success(ForwardingCheckResponse(true, check("pending"), 2))
        val (_, model) = golive()
        // The step opened on a pending check and polled it to the limit.
        advanceTimeBy(10_000)
        advanceUntilIdle()
        assertTrue(model.pollGaveUp)
        assertEquals(SetupCheckSentence.STILL_PENDING, model.checkSentence)
        val reads = harness.setup.forwardingReads.size

        model.businessLine = "+14165550123"
        model.startCheck()
        advanceUntilIdle()
        assertTrue(harness.setup.forwardingStarts.isEmpty())
        assertTrue(harness.setup.forwardingReads.size > reads)
    }

    @Test
    fun `a refused start is said, and the failures have their own sentences`() = runTest {
        val (_, model) = golive()
        model.businessLine = "+14165550123"
        harness.setup.forwardingStartResult = ApiResult.RateLimited("No checks left today.")
        model.startCheck()
        advanceUntilIdle()
        assertTrue(model.checkError != null)
        assertFalse(model.isStartingCheck)

        harness.setup.forwardingStartResult = started(check("failed", "dial_failed"), 1)
        model.startCheck()
        advanceUntilIdle()
        assertNull(model.checkError)
        assertEquals(SetupCheckSentence.DIAL_FAILED, model.checkSentence)

        harness.setup.forwardingStartResult = started(check("failed", "no_arrival"), 0)
        model.startCheck()
        advanceUntilIdle()
        assertEquals(SetupCheckSentence.FAILED, model.checkSentence)
    }

    @Test
    fun `an unreadable check on open holds no check and polls nothing`() = runTest {
        // The screen then draws no check section, as for a region with no verifier; opening the step
        // again reads it again.
        harness.setup.forwardingResult = ApiResult.NetworkFailure(IOException("down"))
        val (_, model) = golive()
        assertNull(model.forwarding)
        assertNull(model.checkSentence)
        assertFalse(model.isPolling)
        assertEquals(1, harness.setup.forwardingReads.size)
    }

    @Test
    fun `the button does nothing while a pending check is being polled`() = runTest {
        harness.setup.forwardingResult = ApiResult.Success(ForwardingCheckResponse(true, check("pending"), 2))
        harness.reads(throughCalls)
        val wizard = harness.wizard()
        runCurrent()
        val model = wizard.stepModel as SetupGoLiveModel
        runCurrent()
        assertTrue(model.isPolling)
        val reads = harness.setup.forwardingReads.size

        model.businessLine = "+14165550123"
        model.startCheck()
        runCurrent()

        assertTrue(harness.setup.forwardingStarts.isEmpty())
        assertEquals("no extra read either", reads, harness.setup.forwardingReads.size)
        wizard.back()
        advanceUntilIdle()
    }

    @Test
    fun `a check started while the step is still opening is polled once, not twice`() = runTest {
        // A draft on the device, so opening the wizard reads no config and only step 6's read waits.
        harness.store.save(SetupBusinessDraft("Bloom", "CA", "ON", "Ottawa", "en"), SetupHarness.WORKSPACE)
        harness.district.gate = CompletableDeferred()
        harness.setup.forwardingResult = ApiResult.Success(ForwardingCheckResponse(true, check("pending"), 2))
        harness.setup.forwardingStartResult = started(check("pending"), 2)
        val (_, model) = golive()

        model.businessLine = "+14165550123"
        model.startCheck()
        runCurrent()
        assertTrue(model.isPolling)
        harness.district.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("+14165550123" to "all"), harness.setup.forwardingStarts)
        assertEquals("the open read, then one poll to the limit", 1 + 3, harness.setup.forwardingReads.size)
        assertTrue(model.pollGaveUp)
    }

    @Test
    fun `a check already held shows the mode it was started with, and an unknown one keeps the default`() = runTest {
        harness.setup.forwardingResult =
            ApiResult.Success(ForwardingCheckResponse(true, check("passed").copy(mode = "unanswered"), 3))
        val (_, model) = golive()
        assertEquals(ForwardingCheckMode.UNANSWERED, model.mode)

        harness.setup.forwardingResult =
            ApiResult.Success(ForwardingCheckResponse(true, check("passed").copy(mode = "sometimes"), 3))
        val (_, other) = golive()
        assertEquals(ForwardingCheckMode.ALL, other.mode)
    }

    @Test
    fun `leaving the step while it is still reading starts no poll afterwards`() = runTest {
        // A draft on the device, so opening the wizard reads no config and only step 6's read waits.
        harness.store.save(SetupBusinessDraft("Bloom", "CA", "ON", "Ottawa", "en"), SetupHarness.WORKSPACE)
        harness.district.gate = CompletableDeferred()
        harness.setup.forwardingResult = ApiResult.Success(ForwardingCheckResponse(true, check("pending"), 2))
        val (wizard, model) = golive()

        wizard.back()
        harness.district.gate!!.complete(Unit)
        advanceUntilIdle()

        assertFalse(model.isPolling)
        assertEquals("the open read only", 1, harness.setup.forwardingReads.size)
    }

    @Test
    fun `leaving the step stops the poll`() = runTest {
        harness.setup.forwardingResult = ApiResult.Success(ForwardingCheckResponse(true, check("pending"), 2))
        harness.reads(throughCalls)
        val wizard = harness.wizard()
        runCurrent()
        val model = wizard.stepModel as SetupGoLiveModel
        runCurrent()
        assertTrue(model.isPolling)
        wizard.back()
        advanceUntilIdle()
        assertFalse(model.isPolling)
        assertEquals("one read on open, none after leaving", 1, harness.setup.forwardingReads.size)
    }

    @Test
    fun `Finish completes setup`() = runTest {
        val (wizard, model) = golive()
        model.finish()
        advanceUntilIdle()
        assertEquals(SetupPhase.Finished, wizard.phase)
        assertEquals(mapOf("golive" to SETUP_STEP_DONE), harness.setup.patches.single().steps)
    }
}
