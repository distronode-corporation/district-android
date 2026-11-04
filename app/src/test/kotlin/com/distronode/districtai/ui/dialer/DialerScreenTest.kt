package com.distronode.districtai.ui.dialer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.activity.ComponentDialog
import org.robolectric.shadows.ShadowDialog
import com.distronode.districtai.str
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.ThemeFlip
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ WHAT THIS SCREEN GETS WRONG IS ONLY VISIBLE IN THE RENDERING, WHICH IS WHY IT IS TESTED HERE
 * AND NOT ONLY AT THE STATE. Four failures live entirely in the drawing: offering a dial control to
 * a role the server refuses (a button that 403s), drawing a duration while the callee's phone is
 * still ringing (counting ringing as conversation on the number an operator checks against an
 * invoice), showing a camera control on a call that publishes no video, and rendering a refused
 * dial as a call in progress.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DialerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var entries = mutableListOf<String>()
    private var callBacks = mutableListOf<String>()
    private var dialTaps = 0
    private var micTaps = 0
    private var speakerTaps = 0
    private var hangUpTaps = 0
    private var dismissTaps = 0
    private var lengthChecks = 0
    private var picks = mutableListOf<String>()

    private fun render(state: DialerUiState) {
        composeRule.setContent {
            DistrictTheme {
                DialerScreen(
                    state = state,
                    handlers = DialerHandlers(
                        onEntryChange = { entries += it },
                        onCallBack = { callBacks += it },
                        onDial = { dialTaps++ },
                        onCheckLength = { lengthChecks++ },
                        onPickCountry = { picks += it },
                        onToggleMicrophone = { micTaps++ },
                        onToggleSpeaker = { speakerTaps++ },
                        onHangUp = { hangUpTaps++ },
                        onDismissEndedCall = { dismissTaps++ },
                        onBack = {},
                    ),
                )
            }
        }
    }

    private fun idle(
        canDial: Boolean = true,
        entry: String = "",
        callbacks: CallbacksState = CallbacksState.Ready(emptyList()),
        refusal: FailureText? = null,
        country: String? = "CA",
        lengthChecked: Boolean = false,
    ) = DialerUiState(
        canDial = canDial,
        entry = entry,
        callbacks = callbacks,
        refusal = refusal,
        country = country,
        homeCountry = "CA",
        lengthChecked = lengthChecked,
    )

    // ── The keypad ───────────────────────────────────────────────────────────

    @Test
    fun `the entry card and the call control are drawn for a role that may dial`() {
        render(idle(entry = "+14165550100"))

        composeRule.onNodeWithTag(DIALER_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithTag(DIALER_ENTRY_CARD_DESCRIPTION).assertExists()
        composeRule.onNodeWithTag(DIALER_FIELD_DESCRIPTION).assertExists()
        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsEnabled()
        // ⚠️ THE MICROPHONE NOTICE IS SHOWN BEFORE THE DIALOG, so the permission request has a
        // visible reason. A request with no explanation is the one users deny permanently.
        composeRule.onNodeWithTag(DIALER_MIC_NOTICE_DESCRIPTION).assertExists()
    }

    @Test
    fun `a viewer gets no dial control at all, and is told why`() {
        // ⛔ PRESENCE, NOT WORDING. `POST calls/dial` excludes `viewer` server-side, so a control
        // here would be one that 403s on the screen's only action.
        render(idle(canDial = false, entry = "+14165550100"))

        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(DIALER_VIEWER_NOTICE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a number too short to dial leaves the control disabled`() {
        // ⚠️ Counted on DIGITS, so this thirteen-character five-digit entry is short. A client
        // counting characters would enable the button for a 400 that reads as a server fault.
        render(idle(entry = "(416) 5"))

        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsNotEnabled()
    }

    // ── Dialler design A ─────────────────────────────────────────────────────

    @Test
    fun `an empty field shows the local hint, the flag and an example in the country's own form`() {
        render(idle())

        composeRule.onNodeWithTag(DIALER_HINT_DESCRIPTION)
            .assertTextEquals(str(R.string.dialer_entry_hint_local, "Canada"))
        composeRule.onNodeWithTag(DIALER_COUNTRY_BUTTON_DESCRIPTION, useUnmergedTree = true)
            .assertContentDescriptionEquals(str(R.string.dialer_country_button, "Canada", "1"))
        composeRule.onNodeWithText("(416) 555-0199").assertExists()
        composeRule.onNodeWithTag(DIALER_PROBLEM_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `with no country the field asks for one, and digits say so`() {
        render(idle(country = null))
        composeRule.onNodeWithTag(DIALER_HINT_DESCRIPTION).assertTextEquals(str(R.string.dialer_entry_hint_none))
        composeRule.onNodeWithText(str(R.string.dialer_country)).assertExists()
    }

    @Test
    fun `digits with no country say why Call is off`() {
        render(idle(country = null, entry = "4165550199"))
        composeRule.onNodeWithTag(DIALER_PROBLEM_DESCRIPTION).assertTextEquals(str(R.string.dialer_entry_hint_none))
        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsNotEnabled()
    }

    /** ⛔ THE LINE THAT PREVENTS A WRONG-COUNTRY DIAL: the country and the whole number, before Call. */
    @Test
    fun `a whole number says where it rings and the number that is sent`() {
        render(idle(entry = "(416) 555-0199"))

        composeRule.onNodeWithTag(DIALER_DESTINATION_DESCRIPTION)
            .assertTextEquals(str(R.string.dialer_rings, "Canada", "+1 416 555 0199"))
        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsEnabled()
        composeRule.onNodeWithTag(DIALER_HINT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an unknown code is said plainly and stays callable`() {
        render(idle(entry = "+990 1234 5678"))
        composeRule.onNodeWithTag(DIALER_DESTINATION_DESCRIPTION)
            .assertTextEquals(str(R.string.dialer_destination_unrecognised))
        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsEnabled()
    }

    /**
     * ⛔ A +1 TOLL-FREE NUMBER NAMES NO COUNTRY (Sean, 2026-11-04, GitLab #2): the web's
     * `ringsNoCountry` for every one of the seven codes, typed locally or with `+1`, and it stays
     * callable. The button keeps its +1 country.
     */
    @Test
    fun `a toll-free number says it belongs to no one country`() {
        var entry by mutableStateOf("")
        composeRule.setContent {
            DistrictTheme {
                DialerScreen(state = idle(entry = entry), handlers = handlers())
            }
        }
        for (area in TOLL_FREE) {
            for (typed in listOf("${area}5550100", "1 $area 555 0100", "+1 $area 555 0100")) {
                entry = typed
                composeRule.onNodeWithTag(DIALER_DESTINATION_DESCRIPTION)
                    .assertTextEquals(str(R.string.dialer_rings_no_country, "+1 $area 555 0100"))
                composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsEnabled()
                composeRule.onNodeWithTag(DIALER_COUNTRY_BUTTON_DESCRIPTION, useUnmergedTree = true)
                    .assertContentDescriptionEquals(str(R.string.dialer_country_button, "Canada", "1"))
            }
        }
    }

    @Test
    @Config(qualifiers = "en-rCA")
    fun `the toll-free line is the web's English`() {
        render(idle(entry = "8005550100"))
        composeRule.onNodeWithTag(DIALER_DESTINATION_DESCRIPTION).assertTextEquals(
            "Rings +1 800 555 0100. This number does not belong to one country. Check it before you call.",
        )
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `the toll-free line is the web's French`() {
        render(idle(entry = "8005550100"))
        composeRule.onNodeWithTag(DIALER_DESTINATION_DESCRIPTION).assertTextEquals(
            "Numéro composé\u00A0: +1 800 555 0100. Ce numéro n'appartient à aucun pays en particulier. " +
                "Vérifiez-le avant d'appeler.",
        )
    }

    /**
     * ⛔ THE WEB'S TIMING: from 8 digits with the `1` (the web's `ready`), so a toll-free number still
     * short of its ten digits already says it has no country, as the bare `+1800…` the web shows for a
     * number that is not whole. Call stays off.
     */
    @Test
    fun `a partial toll-free number says it from eight digits`() {
        render(idle(entry = "800 5555"))
        composeRule.onNodeWithTag(DIALER_DESTINATION_DESCRIPTION)
            .assertTextEquals(str(R.string.dialer_rings_no_country, "+18005555"))
        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsNotEnabled()
    }

    /**
     * ⚠️ ONLY THE SEVEN CODES, FROM 8 DIGITS TO 15: a geographic +1 number still names its country,
     * an 800 outside +1 is not toll-free, and seven digits or more than E.164 allows have no line.
     */
    @Test
    fun `only a +1 toll-free number drops the country`() {
        assertTrue(belongsToNoCountry(DialPlan.read("8885550100", "US")))
        assertTrue(belongsToNoCountry(DialPlan.read("+1 833 555 0100", null)))
        assertFalse(belongsToNoCountry(DialPlan.read("4165550100", "CA")))
        assertFalse(belongsToNoCountry(DialPlan.read("+1 876 555 0100", null)))
        assertFalse(belongsToNoCountry(DialPlan.read("+41 800 555 010", null)))
        assertFalse(belongsToNoCountry(DialPlan.read("800555", "CA")))
        assertEquals("CA" to "+1 800 555 0100", destinationLine(DialPlan.read("8005550100", "CA")))
        assertEquals("CA" to "+18005555", destinationLine(DialPlan.read("800 5555", "CA")))
        assertNull(destinationLine(DialPlan.read("800 555", "CA")))
        assertEquals("US" to "+188855501001", destinationLine(DialPlan.read("+1 888 555 01001", null)))
        assertNull(destinationLine(DialPlan.read("+1 888 555 0100 12345", null)))
    }

    /**
     * ⛔ THE BILLED CALL: `+4165550123` is Switzerland with a digit missing. While typing, Call is
     * simply off; the sentence waits until the operator stops.
     */
    @Test
    fun `the Switzerland misdial keeps Call off and says nothing while typing`() {
        render(idle(entry = "+4165550123"))

        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(DIALER_PROBLEM_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(DIALER_DESTINATION_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `once the operator stops, a wrong length is said`() {
        render(idle(entry = "+4165550123", lengthChecked = true))
        composeRule.onNodeWithTag(DIALER_PROBLEM_DESCRIPTION).assertTextEquals(str(R.string.dialer_entry_wrong_length))
    }

    /** ⚠️ A disabled button runs no action, so a layer over it takes the tap and asks why. */
    @Test
    fun `a tap on the disabled Call reaches the dial handler, which refuses and explains`() {
        render(idle(entry = "+4165550123"))

        composeRule.onNodeWithTag(DIALER_CALL_REFUSED_DESCRIPTION, useUnmergedTree = true).performClick()

        assertEquals(1, dialTaps)
    }

    @Test
    fun `Done on the keyboard checks the length`() {
        render(idle(entry = "+4165550123"))

        composeRule.onNodeWithTag(DIALER_FIELD_DESCRIPTION).performImeAction()

        assertTrue(lengthChecks >= 1)
    }

    @Test
    fun `typing goes to the entry handler`() {
        render(idle())

        composeRule.onNodeWithTag(DIALER_FIELD_DESCRIPTION).performTextInput("4")

        assertEquals(listOf("4"), entries)
    }

    @Test
    fun `an emergency number sends the operator to the phone's own dialler`() {
        render(idle(entry = "911"))
        composeRule.onNodeWithTag(DIALER_PROBLEM_DESCRIPTION).assertTextEquals(str(R.string.api_error_emergency_number))
        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `the country list opens with Suggested first and a search picks a country`() {
        render(idle())

        composeRule.onNodeWithTag(DIALER_COUNTRY_BUTTON_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(DIALER_COUNTRY_PICKER_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.dialer_countries_suggested).uppercase()).assertExists()
        composeRule.onNodeWithTag(DIALER_COUNTRY_SEARCH_DESCRIPTION).performTextInput("germ")
        composeRule.onNodeWithTag(countryRowDescription("DE")).performClick()

        assertEquals(listOf("DE"), picks)
        composeRule.onNodeWithTag(DIALER_COUNTRY_PICKER_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `Done in the search picks the first, best ranked row`() {
        render(idle())

        composeRule.onNodeWithTag(DIALER_COUNTRY_BUTTON_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(DIALER_COUNTRY_SEARCH_DESCRIPTION).performTextInput("us")
        composeRule.onNodeWithTag(DIALER_COUNTRY_SEARCH_DESCRIPTION).performImeAction()

        assertEquals(listOf("US"), picks)
    }

    @Test
    fun `a search that matches nothing says so, and Done picks nothing`() {
        render(idle())

        composeRule.onNodeWithTag(DIALER_COUNTRY_BUTTON_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(DIALER_COUNTRY_SEARCH_DESCRIPTION).performTextInput("zzz")
        composeRule.onNodeWithTag(DIALER_COUNTRY_NO_MATCH_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithTag(DIALER_COUNTRY_SEARCH_DESCRIPTION).performImeAction()

        assertEquals(emptyList<String>(), picks)
    }

    @Test
    fun `dismissing the country list picks nothing`() {
        render(idle())

        composeRule.onNodeWithTag(DIALER_COUNTRY_BUTTON_DESCRIPTION).performClick()
        // ⚠️ Through the dialog's own back dispatcher; see `CreateContactDialogTest`.
        composeRule.runOnIdle {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag(DIALER_COUNTRY_PICKER_DESCRIPTION).assertDoesNotExist()
        assertEquals(emptyList<String>(), picks)
    }

    @Test
    fun `pressing call fires once`() {
        render(idle(entry = "+14165550100"))

        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).performClick()

        assertEquals(1, dialTaps)
    }

    @Test
    fun `a refusal is shown on the keypad, where the call would have been`() {
        // ⚠️ A REFUSED DIAL PRODUCED NO CALL, so there is no call screen to show it over.
        render(
            idle(
                entry = "+14165550100",
                refusal = FailureText(
                    message = UiText.Resource(R.string.dialer_subscription_inactive),
                    retryable = false,
                ),
            ),
        )

        composeRule.onNodeWithTag(DIALER_REFUSAL_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithTag(IN_CALL_ROOT_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
    }

    // ── The call-back list ───────────────────────────────────────────────────

    @Test
    fun `a call-back row fills the field rather than dialling`() {
        // ⛔ ONE TAP MUST NEVER PLACE A CALL, least of all from a scrolling list.
        render(idle(callbacks = CallbacksState.Ready(listOf(callRow()))))

        composeRule.onNodeWithTag(callbackRowDescription("call-1")).performClick()

        assertEquals(listOf("+14165550100"), callBacks)
        assertEquals(0, dialTaps)
    }

    @Test
    fun `an empty call-back list explains itself rather than showing nothing`() {
        render(idle())

        composeRule.onNodeWithTag(DIALER_CALLBACKS_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failed call-back read leaves the keypad usable`() {
        // ⛔ THE TWO ARE UNRELATED SERVER SURFACES. An outage of the call log must not become an
        // inability to place a call.
        render(
            idle(
                entry = "+14165550100",
                callbacks = CallbacksState.Failed(FailureText(message = UiText.Literal("offline"))),
            ),
        )

        composeRule.onNodeWithTag(DIALER_CALLBACKS_FAILED_DESCRIPTION).assertExists()
        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsEnabled()
    }

    // ── The call takes over the same destination ─────────────────────────────

    @Test
    fun `a live call replaces the keypad rather than opening a destination`() {
        // ⛔ ONE DESTINATION, BECAUSE A CALL DESTINATION WOULD BE RESTORED AFTER PROCESS DEATH and
        // its start effect would place a second billable call with no user action.
        render(
            idle().copy(
                call = ActiveCallUiState(
                    number = "+14165550100",
                    connection = CallConnectionState.Connected,
                ),
            ),
        )

        composeRule.onNodeWithTag(IN_CALL_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithTag(DIALER_ROOT_DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
    }

    // ── A redraw, a row with no number, and the preview ──────────────────────

    @Test
    fun `a theme change redraws the keypad and the call-back list with their handles intact`() {
        val theme = ThemeFlip(composeRule)
        val state = idle(callbacks = CallbacksState.Ready(listOf(callRow())))
        // ⚠️ One handlers instance for the whole test, so the redraw hands the row the same one.
        val handlers = handlers()
        theme.setContent { DialerScreen(state = state, handlers = handlers) }

        theme.flip()

        composeRule.onNodeWithTag(DIALER_MIC_NOTICE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithTag(callbackRowDescription("call-1")).performClick()
        assertEquals(listOf("+14165550100"), callBacks)
    }

    @Test
    fun `a viewer's notice survives a theme change`() {
        val theme = ThemeFlip(composeRule)
        val handlers = handlers()
        theme.setContent { DialerScreen(state = idle(canDial = false), handlers = handlers) }

        theme.flip()

        composeRule.onNodeWithTag(DIALER_VIEWER_NOTICE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a call-back row with no number fills an empty entry, which cannot be dialled`() {
        // ⚠️ The repository drops such rows before they reach the screen; the screen itself still
        // takes any row it is handed, and one without a number must not produce something dialable.
        render(idle(callbacks = CallbacksState.Ready(listOf(callRow().copy(from = null)))))

        composeRule.onNodeWithTag(callbackRowDescription("call-1")).performClick()

        assertEquals(listOf(""), callBacks)
        assertEquals(0, dialTaps)
    }

    @Test
    fun `the preview renders a dialable number`() {
        composeRule.setContent { DialerScreenPreview() }

        composeRule.onNodeWithTag(DIALER_CALL_DESCRIPTION).assertIsEnabled()
    }

    private fun handlers() = DialerHandlers(
        onEntryChange = { entries += it },
        onCallBack = { callBacks += it },
        onDial = { dialTaps++ },
        onCheckLength = { lengthChecks++ },
        onPickCountry = { picks += it },
        onToggleMicrophone = { micTaps++ },
        onToggleSpeaker = { speakerTaps++ },
        onHangUp = { hangUpTaps++ },
        onDismissEndedCall = { dismissTaps++ },
        onBack = {},
    )

    private fun callRow() = CallSummary(
        id = "call-1",
        type = "inbound",
        number = "Ada",
        status = "completed",
        duration = "1m 5s",
        time = "Aug 15, 02:30 PM",
        aiSummary = "",
        hasTranscript = false,
        callerName = "Ada",
        from = "+14165550100",
        direction = "inbound",
        summary = "",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    @Test
    fun `a call-back row reaches the handlers the host holds now`() {
        val later = mutableListOf<String>()
        var current by mutableStateOf(handlers())
        val state = idle(callbacks = CallbacksState.Ready(listOf(callRow())))
        composeRule.setContent {
            DistrictTheme { DialerScreen(state = state, handlers = current) }
        }

        current = handlers().copy(onCallBack = { later += it })
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(callbackRowDescription("call-1")).performClick()

        assertEquals(listOf("+14165550100"), later)
        assertEquals(emptyList<String>(), callBacks)
    }

    private companion object {
        /** The +1 toll-free area codes, the seven the web's area-code table marks toll-free. */
        val TOLL_FREE = listOf("800", "833", "844", "855", "866", "877", "888")
    }
}
