package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.AvailabilityReason
import com.distronode.districtai.core.model.AvailabilityResponse
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.CallHandlingResponse
import com.distronode.districtai.ui.FailureText

/**
 * The two independent reads behind the Calls section.
 *
 * ⛔ TWO LOAD STATES RATHER THAN ONE, BECAUSE THEY ARE TWO SCOPES AND EITHER CAN FAIL ALONE.
 * `workspace/call-handling` is a WORKSPACE setting and `workspace/availability` is a fact about the
 * caller's own membership row; a single `ConfigState` covering both would hide a working switch
 * behind a failed catalogue read, or worse, show one screen's error over the other's value.
 */
sealed interface CallHandlingLoad {

    data object Loading : CallHandlingLoad

    data class Ready(val settings: CallHandlingResponse) : CallHandlingLoad

    data class LoadFailed(val failure: FailureText) : CallHandlingLoad
}

/** The caller's own availability, which is a different scope from the workspace's handling mode. */
sealed interface AvailabilityLoad {

    data object Loading : AvailabilityLoad

    data class Ready(val availability: AvailabilityResponse) : AvailabilityLoad

    data class LoadFailed(val failure: FailureText) : AvailabilityLoad
}

/**
 * Who answers a call, and whether this person can be rung.
 *
 * ⛔ NEITHER WRITE IS A WHOLESALE REPLACE, WHICH IS WHY THIS SCREEN CAN DRAW ITSELF FROM A FAILED
 * READ WITHOUT THE USUAL DANGER. `saveTools` and `saveDirectory` overwrite a stored array, so a
 * form that opened empty and saved would delete something; these two write scalars and the PATCH
 * accepts either field alone. The reads still gate the controls — a screen must not offer a switch
 * whose current position it does not know — but the reason is honesty rather than data loss.
 */
data class CallHandlingUiState(
    /** ⛔ The PATCHes exclude `viewer` while both GETs admit one. A viewer sees values, not controls. */
    val canMutate: Boolean = false,
    val load: CallHandlingLoad = CallHandlingLoad.Loading,
    val modeDraft: String? = null,
    val ringDraft: Int? = null,
    val save: SaveState = SaveState.Idle,
    val availability: AvailabilityLoad = AvailabilityLoad.Loading,
    val availabilitySave: SaveState = SaveState.Idle,
) {

    val stored: CallHandlingResponse? get() = (load as? CallHandlingLoad.Ready)?.settings

    /**
     * ⚠️ THE STORED VALUE IS ALREADY NORMALISED SERVER-SIDE, so this never has to cope with a mode
     * the picker has no row for. The fallback is only reachable before the first read lands.
     */
    val mode: String get() = modeDraft ?: stored?.callHandling ?: CallHandling.DEFAULT

    val ringSeconds: Int
        get() = ringDraft ?: stored?.appRingSeconds ?: CallHandling.DEFAULT_RING_SECONDS

    // ⚠️ `stored` is read ONCE per check: a second read after the null test could never be null,
    // and reading it through `?.` again left a branch no state can take.
    val modeDirty: Boolean
        get() = stored.let { it != null && modeDraft != null && modeDraft != it.callHandling }

    val ringDirty: Boolean
        get() = stored.let { it != null && ringDraft != null && ringDraft != it.appRingSeconds }

    val hasUnsavedChanges: Boolean get() = modeDirty || ringDirty

    val canSave: Boolean
        get() = canMutate && load is CallHandlingLoad.Ready && !save.busy && hasUnsavedChanges

    /**
     * ⛔ ONLY WHAT CHANGED GOES ON THE WIRE. The route accepts either field alone and rejects a body
     * carrying neither; sending both every time would be harmless today and is still wrong, because
     * it would write a ring window the operator never touched.
     */
    val pendingMode: String? get() = if (modeDirty) mode else null

    val pendingRingSeconds: Int? get() = if (ringDirty) ringSeconds else null

    private val availabilityBody: AvailabilityResponse?
        get() = (availability as? AvailabilityLoad.Ready)?.availability

    val availableForCalls: Boolean get() = availabilityBody?.availableForCalls == true

    /**
     * ⚠️ A 200 WITH A REASON IS A REAL ANSWER, NOT A REFUSAL. A viewer is told `role` without any
     * database read, and a person holding their role through the owner fallback is told
     * `no_member_row`. Both mean the switch cannot be moved, and each needs its own sentence.
     */
    val availabilityReason: String? get() = availabilityBody?.reason

    /**
     * ⛔ THE ROLE GATE IS NOT ENOUGH ON ITS OWN. `no_member_row` is answered to an AGENCY member
     * who holds their role through the owner fallback, and the PATCH answers 409 for exactly that
     * person — so a switch enabled on `canMutate` alone would fail for the account most likely to
     * be the owner.
     */
    val canToggleAvailability: Boolean
        get() = canMutate &&
            availability is AvailabilityLoad.Ready &&
            !availabilitySave.busy &&
            availabilityReason == null

    /** Whether the reason is the viewer one, which reads differently from a missing member row. */
    val availabilityBlockedByRole: Boolean get() = availabilityReason == AvailabilityReason.ROLE

    val availabilityBlockedByMissingRow: Boolean
        get() = availabilityReason == AvailabilityReason.NO_MEMBER_ROW
}
