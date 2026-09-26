package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.AvailabilityReason
import com.distronode.districtai.core.model.AvailabilityResponse
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.CallHandlingResponse
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived half of the call-handling screen: what it shows, what it may save, and what it sends.
 *
 * ⛔ THESE ARE THE RULES THE SCREEN'S BUTTONS ARE DRAWN FROM, so a wrong one is a control that
 * either cannot be pressed when it should be or sends a value nobody touched. The ViewModel test
 * drives them through the whole load and save cycle; this one pins each rule on its own, including
 * the states the cycle passes through too quickly to observe.
 */
class CallHandlingUiStateTest {

    private val stored = CallHandlingResponse(
        success = true,
        callHandling = CallHandling.AI_THEN_APP,
        appRingSeconds = 15,
    )

    private fun ready(
        modeDraft: String? = null,
        ringDraft: Int? = null,
        canMutate: Boolean = true,
        save: SaveState = SaveState.Idle,
    ) = CallHandlingUiState(
        canMutate = canMutate,
        load = CallHandlingLoad.Ready(stored),
        modeDraft = modeDraft,
        ringDraft = ringDraft,
        save = save,
    )

    private fun available(
        reason: String? = null,
        availableForCalls: Boolean = true,
        canMutate: Boolean = true,
        save: SaveState = SaveState.Idle,
    ) = CallHandlingUiState(
        canMutate = canMutate,
        availability = AvailabilityLoad.Ready(
            AvailabilityResponse(success = true, availableForCalls = availableForCalls, reason = reason),
        ),
        availabilitySave = save,
    )

    // ── Before the first read lands ────────────────────────────────────────────────────────

    @Test
    fun `before anything is read the screen shows the documented defaults and offers no save`() {
        val state = CallHandlingUiState()

        assertNull(state.stored)
        assertEquals(CallHandling.DEFAULT, state.mode)
        assertEquals(CallHandling.DEFAULT_RING_SECONDS, state.ringSeconds)
        assertFalse(state.hasUnsavedChanges)
        assertFalse(state.canSave)
        assertFalse(state.availableForCalls)
        assertFalse(state.canToggleAvailability)
    }

    @Test
    fun `a draft made before the read lands is shown but is not a change to save`() {
        // ⚠️ DIRTY MEANS "DIFFERENT FROM WHAT IS STORED", and nothing is stored yet. Saving here
        // would write a value against a baseline the screen never read.
        val state = CallHandlingUiState(
            canMutate = true,
            modeDraft = CallHandling.APP_FIRST,
            ringDraft = 25,
        )

        assertEquals(CallHandling.APP_FIRST, state.mode)
        assertEquals(25, state.ringSeconds)
        assertFalse(state.modeDirty)
        assertFalse(state.ringDirty)
        assertFalse(state.canSave)
    }

    // ── Dirtiness and what goes on the wire ────────────────────────────────────────────────

    @Test
    fun `with no draft the stored values are shown and nothing is pending`() {
        val state = ready()

        assertEquals(CallHandling.AI_THEN_APP, state.mode)
        assertEquals(15, state.ringSeconds)
        assertFalse(state.hasUnsavedChanges)
        assertNull(state.pendingMode)
        assertNull(state.pendingRingSeconds)
        assertFalse(state.canSave)
    }

    @Test
    fun `a draft equal to the stored value is not a change`() {
        val state = ready(modeDraft = CallHandling.AI_THEN_APP, ringDraft = 15)

        assertFalse(state.modeDirty)
        assertFalse(state.ringDirty)
        assertFalse(state.canSave)
    }

    @Test
    fun `only the field that changed goes on the wire`() {
        // ⛔ THE ROUTE ACCEPTS EITHER FIELD ALONE, and sending both would write a ring window the
        // operator never touched.
        val modeOnly = ready(modeDraft = CallHandling.APP_FIRST)
        assertEquals(CallHandling.APP_FIRST, modeOnly.pendingMode)
        assertNull(modeOnly.pendingRingSeconds)
        assertTrue(modeOnly.canSave)

        val ringOnly = ready(ringDraft = 30)
        assertNull(ringOnly.pendingMode)
        assertEquals(30, ringOnly.pendingRingSeconds)
        assertTrue(ringOnly.canSave)

        val both = ready(modeDraft = CallHandling.APP_FIRST, ringDraft = 30)
        assertEquals(CallHandling.APP_FIRST, both.pendingMode)
        assertEquals(30, both.pendingRingSeconds)
    }

    @Test
    fun `a change cannot be saved by a viewer, mid-save, or without a successful read`() {
        assertFalse(
            "a viewer sees values, not controls",
            ready(modeDraft = CallHandling.APP_FIRST, canMutate = false).canSave,
        )
        assertFalse(
            "a second submit must not race the first",
            ready(ringDraft = 30, save = SaveState.Saving).canSave,
        )
        assertTrue(
            "a failed save leaves the edit savable",
            ready(ringDraft = 30, save = SaveState.Failed(failure())).canSave,
        )

        val failedRead = CallHandlingUiState(
            canMutate = true,
            load = CallHandlingLoad.LoadFailed(failure()),
            modeDraft = CallHandling.APP_FIRST,
        )
        assertFalse(failedRead.canSave)
        assertNull(failedRead.stored)
    }

    // ── Availability ───────────────────────────────────────────────────────────────────────

    @Test
    fun `availability is only togglable for a member with a row and no save in flight`() {
        assertTrue(available().canToggleAvailability)
        assertTrue(available().availableForCalls)
        assertFalse(available(availableForCalls = false).availableForCalls)

        assertFalse("a viewer cannot move it", available(canMutate = false).canToggleAvailability)
        assertFalse("not while a write is in flight", available(save = SaveState.Saving).canToggleAvailability)
        assertFalse(
            "not before the read lands",
            CallHandlingUiState(canMutate = true).canToggleAvailability,
        )
    }

    @Test
    fun `a reason on a 200 blocks the switch and says which of the two it is`() {
        // ⛔ THE ROLE GATE IS NOT ENOUGH: an agency member holding the role through the owner
        // fallback is told `no_member_row`, and the PATCH would answer 409 for them.
        val role = available(reason = AvailabilityReason.ROLE)
        assertFalse(role.canToggleAvailability)
        assertTrue(role.availabilityBlockedByRole)
        assertFalse(role.availabilityBlockedByMissingRow)

        val missingRow = available(reason = AvailabilityReason.NO_MEMBER_ROW)
        assertFalse(missingRow.canToggleAvailability)
        assertFalse(missingRow.availabilityBlockedByRole)
        assertTrue(missingRow.availabilityBlockedByMissingRow)

        val clear = available()
        assertNull(clear.availabilityReason)
        assertFalse(clear.availabilityBlockedByRole)
        assertFalse(clear.availabilityBlockedByMissingRow)
    }

    @Test
    fun `a failed availability read leaves nothing to show or toggle`() {
        val state = CallHandlingUiState(
            canMutate = true,
            availability = AvailabilityLoad.LoadFailed(failure()),
        )

        assertFalse(state.availableForCalls)
        assertNull(state.availabilityReason)
        assertFalse(state.canToggleAvailability)
    }

    private fun failure() = FailureText(message = UiText.Literal("offline"))

    @Test
    fun `a ring draft moved back to the stored value is not a change`() {
        val state = ready(ringDraft = 15)

        assertFalse(state.ringDirty)
        assertNull(state.pendingRingSeconds)
        assertFalse(state.canSave)
    }
}
