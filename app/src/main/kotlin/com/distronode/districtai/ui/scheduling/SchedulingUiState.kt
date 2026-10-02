package com.distronode.districtai.ui.scheduling

import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.core.model.SchedulingTenantStatus
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText

/**
 * The Scheduling screen's state.
 *
 * ⛔ THE READ AND THE THREE ACTIONS CARRY SEPARATE FAILURES, for the reason the device list does: a
 * refused provision or a refused hand-off must not blank a card the user is reading. The card is a
 * correct answer already in hand, and replacing it with an error would lose the booking link they
 * were about to use.
 *
 * ⚠️ [busy], [openingDashboard] AND [openingConsole] ARE THREE FLAGS, NOT ONE. Enabling is the
 * screen's only WRITE and one press of it spends one of five hourly provisions; each hand-off is a
 * read that mints its own single-use credential at a different server. None may disable another —
 * a hand-off has to stay pressable while a provision settles, and the two hand-offs are separate
 * budgets (10/min per account for the dashboard, a nonce per press at the scheduler).
 */
data class SchedulingUiState(
    val screen: SchedulingScreenState = SchedulingScreenState.Loading,
    /**
     * ⛔ ONE PRESS OF ENABLE AT A TIME, AND THE SECOND IS DROPPED RATHER THAN QUEUED. A queued
     * second press would spend another of the workspace's five hourly provisions for an answer
     * already on its way, and each one reaches two third parties.
     */
    val busy: Boolean = false,
    /** ⚠️ Separate from [busy] and from [openingConsole]. See the class doc. */
    val openingDashboard: Boolean = false,
    /** ⚠️ Separate from [busy] and from [openingDashboard]. See the class doc. */
    val openingConsole: Boolean = false,
    /**
     * Whether this region has already told us the scheduler's own console is gone.
     *
     * ⛔ A LATCH, AND IT ONLY EVER TURNS ON. The website answers **410
     * `scheduler_console_retired`** once a region's `ADMIN_SPA` switch is flipped, and that is a
     * property of the REGION rather than of this press — it will answer the same way for every
     * later attempt in this session. Leaving the secondary button on screen afterwards would offer
     * a control whose only possible outcome is the identical refusal, which is the same class of
     * mistake as a retry button on a role refusal.
     *
     * ⚠️ IT IS NOT PERSISTED AND MUST NOT BECOME SO. A flip can be rolled back, and a latch written
     * to disk would keep an app hiding a working console until somebody cleared its data. It costs
     * one press per screen visit to rediscover, which is the right price.
     */
    val consoleRetired: Boolean = false,
    /**
     * ⚠️ SHOWN ALONGSIDE THE CARD, NEVER INSTEAD OF IT. It carries the operator-facing sentence
     * from a refused provision or a refused hand-off, both of which leave the card's own answer
     * intact and worth reading.
     */
    val notice: UiText? = null,
)

/**
 * The screen's top-level state.
 *
 * ⛔ A FAILED READ IS ITS OWN CASE AND IS NEVER RENDERED AS "there is nothing here". `tenant ==
 * null` is an ordinary, expected answer on this surface — it is every workspace before anybody
 * presses Enable — so a read that did not happen and a workspace with no booking page would
 * otherwise be the same picture, and to a user that reads like account loss.
 */
sealed interface SchedulingScreenState {

    data object Loading : SchedulingScreenState

    data class Ready(val status: SchedulingStatusResponse) : SchedulingScreenState

    data class Failed(val failure: FailureText) : SchedulingScreenState
}

/**
 * The seven things this screen can be showing once the status read has answered.
 *
 * ⛔ SEVEN CASES RATHER THAN FOUR, AND EVERY EXTRA ONE IS A PAIR THAT WOULD OTHERWISE COLLAPSE.
 * `tenant == null` means two different things — a workspace nobody has provisioned yet (ordinary,
 * and the answer is a button) or one the feature does not admit (also ordinary, and the answer is
 * a sentence and nothing else) — so folding them would put a button in front of somebody the
 * enable route answers 403, or hide it from somebody who only needed to press it. The tenancy
 * status covers four more, and [Unrecognised] covers a status this build has never heard of, which
 * is not `error` and must not be drawn as one.
 *
 * ⚠️ THE TENANCY WINS OVER ELIGIBILITY WHENEVER THERE IS ONE. A workspace can be provisioned and
 * later removed from the allowlist — the status route sends both answers precisely because they
 * are independent — and in that case the booking page is still live and its link is still the
 * truth. Eligibility then decides only whether Enable is offered.
 */
sealed interface SchedulingPresentation {

    /**
     * The feature does not admit this workspace.
     *
     * ⛔ No button, and no route out. Google Play's Payments policy is the same shape as the App
     * Store's 3.1.3(b) here: a "contact sales" link out of a paid product's own settings is a
     * purchase offer wearing a different hat, and this app deliberately carries none — see the
     * marketplace's read-only hand-off, which exists for the same reason.
     */
    data object NotEligible : SchedulingPresentation

    /** Admitted, never provisioned. The ordinary state of every workspace before the first press. */
    data object Legacy : SchedulingPresentation

    data class Provisioning(val tenant: SchedulingTenant) : SchedulingPresentation

    /**
     * Booking pages are live. Named [Live] rather than `Ready` so it cannot be misread as
     * [SchedulingScreenState.Ready], which is about the READ.
     */
    data class Live(val tenant: SchedulingTenant) : SchedulingPresentation

    /**
     * The last provision failed. ⚠️ Not terminal: the hourly reconciler tries again, and re-running
     * Enable is the documented manual recovery.
     */
    data class FailedProvision(val tenant: SchedulingTenant) : SchedulingPresentation

    /**
     * ⛔ THE ONE STATE NOTHING RE-PROVISIONS, AND THEREFORE THE ONE STATE THIS SCREEN DOES NOT
     * OFFER TO HEAL. A human, or a workspace deletion, took the tenancy down; see the ⛔ on
     * [SchedulingTenantStatus].
     */
    data class SwitchedOff(val tenant: SchedulingTenant) : SchedulingPresentation

    /**
     * The row exists and names a state this build does not model.
     *
     * ⛔ ITS OWN CASE, NOT AN ERROR AND NOT A BLANK. The status column takes a new value without a
     * migration (see [SchedulingTenantStatus]), so this is a forward-compatibility state rather
     * than a fault: the honest answer is that the booking page exists and this app cannot say what
     * it is doing. Drawing it as [FailedProvision] would report a failure that did not happen, and
     * drawing it as [Legacy] would offer to provision a tenancy that already exists.
     */
    data class Unrecognised(val tenant: SchedulingTenant) : SchedulingPresentation

    companion object {
        fun from(status: SchedulingStatusResponse): SchedulingPresentation {
            val tenant = status.tenant ?: return if (status.eligible) Legacy else NotEligible
            return when (tenant.tenantStatus) {
                SchedulingTenantStatus.PROVISIONING -> Provisioning(tenant)
                SchedulingTenantStatus.READY -> Live(tenant)
                SchedulingTenantStatus.ERROR -> FailedProvision(tenant)
                SchedulingTenantStatus.DISABLED -> SwitchedOff(tenant)
                null -> Unrecognised(tenant)
            }
        }
    }
}

/**
 * Whether to draw the Enable button.
 *
 * ⛔ BOTH ANSWERS COME FROM THE SERVER AND NEITHER IS RE-DERIVED FROM A ROLE. `canManage` is the
 * status route telling the client which buttons to draw; a second gate built from a role STRING
 * would fail closed on a role that did not parse and hide the button from an owner the server
 * would have admitted. It is why the Scheduling destination carries no `{role}` segment at all,
 * unlike almost every other workspace-scoped route in this app. The enable route enforces both
 * checks itself either way — this is an affordance, never a boundary.
 *
 * ⛔ [SchedulingPresentation.SwitchedOff] IS DELIBERATELY NOT OFFERED, WHICH DIVERGES FROM THE WEB
 * CARD. The web dashboard offers Enable for a disabled tenancy; `disabled` is the one status
 * nothing re-provisions on purpose, and resurrecting booking pages somebody switched off is a
 * change that should be made where the switch was thrown, not from a phone.
 *
 * ⛔ AND [SchedulingPresentation.Unrecognised] IS NOT OFFERED EITHER, for a different reason: this
 * build does not know what the row is doing, so it cannot know that provisioning is the right
 * thing to do to it.
 */
fun SchedulingPresentation.offersEnable(eligible: Boolean, canManage: Boolean): Boolean {
    if (!eligible || !canManage) return false
    return this is SchedulingPresentation.Legacy || this is SchedulingPresentation.FailedProvision
}

/**
 * ⚠️ ONLY WHERE RE-READING COULD HONESTLY CHANGE THE ANSWER. The other states are settled until
 * somebody acts, and offering a refresh that cannot move is the same class of thing as a retry
 * button on a role refusal.
 *
 * ⚠️ THIS IS ALSO WHY THERE IS NO AUTO-POLL, WHICH DIVERGES FROM iOS. `SchedulingModel` re-reads
 * every ten seconds while a tenancy provisions; that needs a timer whose lifetime is tied to
 * screen visibility AND to the scene being active, and getting either wrong leaves a backgrounded
 * phone polling a route on a mobile connection. The reconciler re-runs a stalled provision hourly,
 * so nothing here is racing anything, and an explicit Refresh in exactly the two states where it
 * can change the answer is the honest version of the same affordance.
 */
val SchedulingPresentation.offersRefresh: Boolean
    get() = this is SchedulingPresentation.Provisioning ||
        this is SchedulingPresentation.FailedProvision

/**
 * Whether this card offers a hand-off into a browser at all — the dashboard one, and the scheduler
 * console one behind it.
 *
 * ⛔ ONE GATE FOR BOTH ACTIONS, AND THE BINDING CONSTRAINT IS THE OLDER OF THE TWO. The SSO route
 * answers **409** for every state but `ready`, so the console button can only ever exist here. The
 * dashboard hand-off has no such limit (`/dashboard/district/scheduling` renders for a workspace
 * with no tenancy row at all), so the card deliberately is NOT wider than the console's gate: it
 * decides WHICH hand-off is primary, not where a hand-off is offered. Widening it is a real
 * question (a legacy workspace could be sent to the web to enable scheduling there)
 * and it is a separate decision, with its own consequences for
 * [SchedulingPresentation.NotEligible], which must keep offering nothing.
 */
val SchedulingPresentation.offersOpen: Boolean
    get() = this is SchedulingPresentation.Live

/**
 * Whether the card has an action row at all.
 *
 * ⛔ ASKED BEFORE THE ROW IS BUILT, NOT INSIDE IT, AND THAT IS THE WHOLE REASON THIS EXISTS RATHER
 * THAN THE ROW DECIDING FOR ITSELF. A `Row` that renders nothing still takes its own spacing and
 * padding, so the states that must offer NOTHING would each carry a gap where a control used to
 * be — which is how one quietly comes back.
 */
fun SchedulingPresentation.offersAnyAction(eligible: Boolean, canManage: Boolean): Boolean =
    offersOpen || offersRefresh || offersEnable(eligible, canManage)
