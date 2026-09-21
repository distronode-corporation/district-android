package com.distronode.districtai.ui.scheduling

import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which card the status response means, and which controls it may offer.
 *
 * ⛔ A PLAIN JVM TEST WITH NO ROBOLECTRIC, WHICH IS WHY THE DECISIONS LIVE IN `SchedulingUiState`
 * RATHER THAN INSIDE THE COMPOSABLE. Every rule below is one somebody could quietly get wrong in a
 * `when` arm inside a screen, where it would be reachable only by rendering the whole thing on the
 * slow runner. Here each is one line.
 *
 * ⛔ AND THE RULES ARE NOT COSMETIC. Offering Enable in the wrong state either provisions a tenancy
 * somebody deliberately switched off, or shows a button that the enable route answers 403.
 */
class SchedulingPresentationTest {

    private fun tenant(status: String) = SchedulingTenant(
        status = status,
        publicHost = "acme-book.distronode.com",
        region = "us",
        hasCredentials = status == "ready",
    )

    private fun response(
        eligible: Boolean = true,
        canManage: Boolean = true,
        tenant: SchedulingTenant? = null,
    ) = SchedulingStatusResponse(eligible = eligible, canManage = canManage, tenant = tenant)

    @Test
    fun `a null tenant splits on eligibility rather than collapsing`() {
        // ⛔ THE PAIR THAT MUST NOT COLLAPSE. Both are `tenant == null` and both are ordinary, but
        // one wants a button and the other wants a sentence and nothing else — and the enable route
        // answers 403 to the second even for an owner.
        assertEquals(
            SchedulingPresentation.Legacy,
            SchedulingPresentation.from(response(eligible = true)),
        )
        assertEquals(
            SchedulingPresentation.NotEligible,
            SchedulingPresentation.from(response(eligible = false)),
        )
    }

    @Test
    fun `each modelled tenancy status maps to its own presentation`() {
        assertEquals(
            SchedulingPresentation.Provisioning(tenant("provisioning")),
            SchedulingPresentation.from(response(tenant = tenant("provisioning"))),
        )
        assertEquals(
            SchedulingPresentation.Live(tenant("ready")),
            SchedulingPresentation.from(response(tenant = tenant("ready"))),
        )
        assertEquals(
            SchedulingPresentation.FailedProvision(tenant("error")),
            SchedulingPresentation.from(response(tenant = tenant("error"))),
        )
        assertEquals(
            SchedulingPresentation.SwitchedOff(tenant("disabled")),
            SchedulingPresentation.from(response(tenant = tenant("disabled"))),
        )
    }

    @Test
    fun `a tenancy the app does not model is its own case, never an error`() {
        // ⛔ THE FORWARD-COMPATIBILITY ARM. The status column takes a new value with no migration,
        // so this state is reachable on an installed build the day the server adds one. Drawing it
        // as FailedProvision would report a failure that did not happen; drawing it as Legacy would
        // offer to provision a tenancy that already exists.
        assertEquals(
            SchedulingPresentation.Unrecognised(tenant("retiring")),
            SchedulingPresentation.from(response(tenant = tenant("retiring"))),
        )
    }

    @Test
    fun `the tenancy wins over eligibility whenever there is one`() {
        // ⚠️ A WORKSPACE CAN BE PROVISIONED AND LATER REMOVED FROM THE ALLOWLIST — the route sends
        // both answers precisely because they are independent. The booking page is still live and
        // its link is still the truth; eligibility then decides only whether Enable is offered.
        val presentation =
            SchedulingPresentation.from(response(eligible = false, tenant = tenant("ready")))

        assertEquals(SchedulingPresentation.Live(tenant("ready")), presentation)
        assertFalse(presentation.offersEnable(eligible = false, canManage = true))
        assertTrue("the link is still real", presentation.offersOpen)
    }

    @Test
    fun `Enable is offered for exactly two states and only when the server admits it`() {
        val legacy = SchedulingPresentation.Legacy
        val failed = SchedulingPresentation.FailedProvision(tenant("error"))

        assertTrue(legacy.offersEnable(eligible = true, canManage = true))
        assertTrue(failed.offersEnable(eligible = true, canManage = true))

        // ⛔ BOTH GATES COME FROM THE SERVER AND NEITHER IS RE-DERIVED FROM A ROLE. `canManage` is
        // the status route telling the client which buttons to draw; a second gate built from a
        // role string would fail closed on a role that did not parse and hide the button from an
        // owner the server would have admitted.
        assertFalse(legacy.offersEnable(eligible = true, canManage = false))
        assertFalse(legacy.offersEnable(eligible = false, canManage = true))
    }

    @Test
    fun `Enable is NOT offered for a switched-off or unrecognised tenancy`() {
        // ⛔ `disabled` IS THE ONE STATUS NOTHING RE-PROVISIONS, and this DIVERGES FROM THE WEB
        // CARD deliberately: the web dashboard offers Enable there. Resurrecting booking pages
        // somebody switched off is a change that should be made where the switch was thrown.
        assertFalse(
            SchedulingPresentation.SwitchedOff(tenant("disabled"))
                .offersEnable(eligible = true, canManage = true),
        )
        // ⛔ AND AN UNMODELLED STATE IS NOT OFFERED EITHER, for a different reason: this build does
        // not know what the row is doing, so it cannot know that provisioning is right for it.
        assertFalse(
            SchedulingPresentation.Unrecognised(tenant("retiring"))
                .offersEnable(eligible = true, canManage = true),
        )
        assertFalse(
            SchedulingPresentation.Live(tenant("ready"))
                .offersEnable(eligible = true, canManage = true),
        )
        assertFalse(
            SchedulingPresentation.Provisioning(tenant("provisioning"))
                .offersEnable(eligible = true, canManage = true),
        )
        assertFalse(
            SchedulingPresentation.NotEligible.offersEnable(eligible = true, canManage = true),
        )
    }

    @Test
    fun `Refresh is offered only where re-reading could change the answer`() {
        // ⚠️ THE TWO UNSETTLED STATES. The reconciler re-runs provisioning for both hourly, so a
        // re-read can genuinely move them; the other four are settled until somebody acts, and a
        // refresh there is the same class of thing as a retry button on a role refusal.
        assertTrue(SchedulingPresentation.Provisioning(tenant("provisioning")).offersRefresh)
        assertTrue(SchedulingPresentation.FailedProvision(tenant("error")).offersRefresh)

        assertFalse(SchedulingPresentation.Live(tenant("ready")).offersRefresh)
        assertFalse(SchedulingPresentation.SwitchedOff(tenant("disabled")).offersRefresh)
        assertFalse(SchedulingPresentation.Unrecognised(tenant("retiring")).offersRefresh)
        assertFalse(SchedulingPresentation.Legacy.offersRefresh)
        assertFalse(SchedulingPresentation.NotEligible.offersRefresh)
    }

    @Test
    fun `Open scheduler is offered only for a live tenancy`() {
        // ⚠️ THE SSO ROUTE ANSWERS 409 FOR EVERY OTHER STATE, so a button anywhere else would be a
        // press that can only ever fail.
        assertTrue(SchedulingPresentation.Live(tenant("ready")).offersOpen)
        assertFalse(SchedulingPresentation.Provisioning(tenant("provisioning")).offersOpen)
        assertFalse(SchedulingPresentation.FailedProvision(tenant("error")).offersOpen)
        assertFalse(SchedulingPresentation.SwitchedOff(tenant("disabled")).offersOpen)
        assertFalse(SchedulingPresentation.Unrecognised(tenant("retiring")).offersOpen)
        assertFalse(SchedulingPresentation.Legacy.offersOpen)
        assertFalse(SchedulingPresentation.NotEligible.offersOpen)
    }

    @Test
    fun `the three states specified to offer NOTHING have no action row at all`() {
        // ⛔ ASKED BEFORE THE ROW IS BUILT. A Row that renders nothing still takes its own spacing
        // and padding, so these states would each carry a gap where a control used to be — which is
        // how one quietly comes back.
        assertFalse(
            SchedulingPresentation.NotEligible.offersAnyAction(eligible = false, canManage = true),
        )
        assertFalse(
            SchedulingPresentation.SwitchedOff(tenant("disabled"))
                .offersAnyAction(eligible = true, canManage = true),
        )
        assertFalse(
            SchedulingPresentation.Unrecognised(tenant("retiring"))
                .offersAnyAction(eligible = true, canManage = true),
        )
        // ⚠️ And a viewer on a legacy workspace: the card is readable, the button is not drawn, so
        // there is nothing left to put in a row.
        assertFalse(
            SchedulingPresentation.Legacy.offersAnyAction(eligible = true, canManage = false),
        )

        assertTrue(
            SchedulingPresentation.Live(tenant("ready"))
                .offersAnyAction(eligible = true, canManage = false),
        )
        assertTrue(
            SchedulingPresentation.Legacy.offersAnyAction(eligible = true, canManage = true),
        )
        assertTrue(
            SchedulingPresentation.Provisioning(tenant("provisioning"))
                .offersAnyAction(eligible = true, canManage = false),
        )
    }

    @Test
    fun `the tenancy row is recoverable from every presentation that has one`() {
        assertNull(SchedulingPresentation.NotEligible.tenantOrNull)
        assertNull(SchedulingPresentation.Legacy.tenantOrNull)
        assertEquals(
            tenant("provisioning"),
            SchedulingPresentation.Provisioning(tenant("provisioning")).tenantOrNull,
        )
        assertEquals(tenant("ready"), SchedulingPresentation.Live(tenant("ready")).tenantOrNull)
        assertEquals(
            tenant("error"),
            SchedulingPresentation.FailedProvision(tenant("error")).tenantOrNull,
        )
        assertEquals(
            tenant("disabled"),
            SchedulingPresentation.SwitchedOff(tenant("disabled")).tenantOrNull,
        )
        assertEquals(
            tenant("retiring"),
            SchedulingPresentation.Unrecognised(tenant("retiring")).tenantOrNull,
        )
    }
}
