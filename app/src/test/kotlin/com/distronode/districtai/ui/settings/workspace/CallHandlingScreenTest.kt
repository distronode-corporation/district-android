package com.distronode.districtai.ui.settings.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.AvailabilityReason
import com.distronode.districtai.core.model.AvailabilityResponse
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.CallHandlingResponse
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the Calls section draws, and the two things it must never draw: a control for a viewer, and
 * a switch for somebody whose row the server would refuse.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT, for the reason the persona form needs one: this is a `verticalScroll` Column,
// so every child is COMPOSED whether or not it is on screen while `assertIsDisplayed` checks
// visible BOUNDS. On a phone-sized Robolectric display the availability section sits below the fold.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class CallHandlingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun state(
        canMutate: Boolean = true,
        mode: String = CallHandling.AI_FIRST,
        ring: Int = 20,
        availability: AvailabilityResponse = AvailabilityResponse(
            success = true,
            availableForCalls = true,
        ),
    ) = CallHandlingUiState(
        canMutate = canMutate,
        load = CallHandlingLoad.Ready(
            CallHandlingResponse(success = true, callHandling = mode, appRingSeconds = ring),
        ),
        availability = AvailabilityLoad.Ready(availability),
    )

    private fun render(
        state: CallHandlingUiState,
        onSelectMode: (String) -> Unit = {},
        onSelectRingSeconds: (Int) -> Unit = {},
        onSave: () -> Unit = {},
        onSetAvailability: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                CallHandlingScreen(
                    state = state,
                    onSelectMode = onSelectMode,
                    onSelectRingSeconds = onSelectRingSeconds,
                    onSave = onSave,
                    onSetAvailability = onSetAvailability,
                    onRetry = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `all three modes are offered, and the stored one is reported by value`() {
        var chosen: String? = null
        render(state(), onSelectMode = { chosen = it })

        CallHandling.MODES.forEach { mode ->
            composeRule.onNodeWithContentDescription(callHandlingModeDescription(mode))
                .assertIsDisplayed()
        }

        // ⛔ THE VALUE IS THE SERVER'S WIRE STRING. A display label sent in its place is a 400.
        composeRule.onNodeWithContentDescription(
            callHandlingModeDescription(CallHandling.APP_FIRST),
        ).performClick()
        assertEquals(CallHandling.APP_FIRST, chosen)
    }

    @Test
    fun `the ring window is shown in seconds`() {
        render(state(ring = 25))

        composeRule.onNodeWithText("25 seconds").assertIsDisplayed()
    }

    @Test
    fun `a viewer sees the values and is offered no control at all`() {
        // ⛔ BOTH READS ADMIT A VIEWER AND BOTH PATCHES EXCLUDE ONE. Hiding the screen would
        // withhold the explanation for a call list they can already see; offering a control would
        // draw something that 403s.
        render(
            state(
                canMutate = false,
                availability = AvailabilityResponse(
                    success = true,
                    availableForCalls = false,
                    reason = AvailabilityReason.ROLE,
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(CALL_HANDLING_SAVE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(CALL_HANDLING_VIEWER_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CALL_HANDLING_AVAILABILITY_DESCRIPTION)
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Viewers cannot take calls.").assertIsDisplayed()
    }

    @Test
    fun `a missing membership row disables the switch and says why`() {
        // ⛔ NOT THE SAME FACT AS THE VIEWER CASE. This person holds their role through the OWNER
        // fallback and the PATCH answers 409 for exactly them, so a switch enabled on the role
        // alone would fail for the likeliest owner.
        render(
            state(
                availability = AvailabilityResponse(
                    success = true,
                    availableForCalls = false,
                    reason = AvailabilityReason.NO_MEMBER_ROW,
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(CALL_HANDLING_AVAILABILITY_DESCRIPTION)
            .assertIsNotEnabled()
        composeRule.onNodeWithText(
            "You have no membership record in this workspace, so calls cannot ring you.",
        ).assertIsDisplayed()
    }

    @Test
    fun `an agency member with a real row can move the switch`() {
        var toggled: Boolean? = null
        render(state(), onSetAvailability = { toggled = it })

        composeRule.onNodeWithContentDescription(CALL_HANDLING_AVAILABILITY_DESCRIPTION)
            .assertIsEnabled()
            .performClick()

        // ⛔ IT SENDS ON THE TAP AND HAS NO SAVE BUTTON. "I am on call" is a statement about right
        // now; a draft sitting unsent while the phone does not ring is the failure it prevents.
        assertEquals(false, toggled)
    }

    @Test
    fun `save is offered but disabled until something changes`() {
        render(state())

        composeRule.onNodeWithContentDescription(CALL_HANDLING_SAVE_DESCRIPTION).assertIsNotEnabled()
    }
}
