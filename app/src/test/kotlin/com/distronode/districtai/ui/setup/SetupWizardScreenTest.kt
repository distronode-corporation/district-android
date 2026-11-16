package com.distronode.districtai.ui.setup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.SETUP_STEP_SKIPPED
import com.distronode.districtai.core.model.SetupDraftFaq
import com.distronode.districtai.core.model.SetupDraftService
import com.distronode.districtai.core.model.SetupLookupDraft
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.str
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the page hands to its host, recorded. */
class RecordingSetupActions(var https: Boolean = true) : SetupActions {
    val opened = mutableListOf<SetupHandOff>()

    override fun canOpen(page: SetupHandOff): Boolean = https

    override fun openHandOff(page: SetupHandOff) {
        opened += page
    }
}

/**
 * The wizard's shell and steps 1 and 2 on screen, against the real models over fakes: every phase,
 * the moves, and the hand-offs.
 *
 * ⚠️ AN UNCONFINED MAIN DISPATCHER, so every fake answer lands before the next line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w600dp-h4000dp")
class SetupWizardScreenTest {

    @get:Rule(order = 0)
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val harness = SetupHarness()
    private val actions = RecordingSetupActions()
    private var closed = 0

    private fun show(wizard: SetupWizardViewModel = harness.wizard()): SetupWizardViewModel {
        composeRule.setContent {
            DistrictTheme { SetupWizardScreen(wizard, actions, onClose = { closed++ }) }
        }
        composeRule.waitForIdle()
        return wizard
    }

    private fun tap(tag: String) {
        val node = composeRule.onNodeWithTag(tag)
        // ⚠️ The top bar's actions sit outside the scrolling column.
        runCatching { node.performScrollTo() }
        node.performClick()
        composeRule.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any) = composeRule.onNodeWithText(str(id, *args), substring = true)

    private fun back() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun `a member is told only the owner can do setup, and Done closes`() {
        harness.setup.setupResult = ApiResult.Forbidden("Owner only")
        show()

        text(R.string.setup_not_owner_title).assertExists()
        text(R.string.setup_not_owner_body).assertExists()
        tap(SetupTags.DONE)
        assertEquals(1, closed)
    }

    @Test
    fun `a workspace from before the wizard is told setup is not here`() {
        harness.setup.setupResult = ApiResult.HttpFailure(409, "No", code = "setup_not_available")
        show()
        text(R.string.setup_not_available_title).assertExists()
        composeRule.onNodeWithTag(SetupTags.REFUSED).assertExists()
    }

    @Test
    fun `a failed read offers a retry that reads again`() {
        harness.setup.setupResult = ApiResult.NetworkFailure(IOException("offline"))
        show()
        composeRule.onNodeWithTag(SetupTags.FAILED).assertExists()

        harness.reads(setupState())
        tap(SetupTags.RETRY)
        composeRule.onNodeWithTag(SetupTags.step(SetupStep.BUSINESS)).assertExists()
    }

    @Test
    fun `while it reads it says so, and a finished wizard says it is finished`() {
        harness.setup.gate = CompletableDeferred()
        harness.reads(setupState(completedAt = "2026-11-01T00:00:00Z"))
        show()
        composeRule.onNodeWithTag(SetupTags.LOADING).assertExists()

        harness.setup.gate!!.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SetupTags.FINISHED).assertExists()
        text(R.string.setup_finished_title).assertExists()
    }

    @Test
    fun `the header names the step and says every step's state in words`() {
        harness.reads(setupState(business = SETUP_STEP_DONE, number = SETUP_STEP_SKIPPED))
        show()

        text(R.string.setup_step_of, 3, 6).assertExists()
        composeRule.onNodeWithText(str(R.string.setup_receptionist_title)).assertExists()
        val spoken = str(R.string.setup_business_title) + ": " + str(R.string.setup_step_done)
        composeRule.onNode(hasContentDescription(spoken, substring = true)).assertExists()
        composeRule.onNode(hasContentDescription(str(R.string.setup_step_skipped), substring = true)).assertExists()
        composeRule.onNode(hasContentDescription(str(R.string.setup_step_current), substring = true)).assertExists()
    }

    @Test
    fun `Skip and Back move between steps, and system back is Finish later`() {
        harness.reads(setupState())
        harness.writes(setupState(business = SETUP_STEP_SKIPPED))
        show()
        composeRule.onNodeWithTag(SetupTags.BACK).assertDoesNotExist()

        tap(SetupTags.SKIP)
        composeRule.onNodeWithTag(SetupTags.step(SetupStep.NUMBER)).assertExists()
        tap(SetupTags.BACK)
        composeRule.onNodeWithTag(SetupTags.step(SetupStep.BUSINESS)).assertExists()

        back()
        assertEquals(1, closed)
        tap(SetupTags.FINISH_LATER)
        assertEquals(2, closed)
    }

    @Test
    fun `nothing closes mid-write, and a refused move shows its error`() {
        harness.reads(setupState())
        val wizard = show()
        wizard.beginStepWrite()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SetupTags.FINISH_LATER).assertIsNotEnabled()
        composeRule.onNodeWithTag(SetupTags.SKIP).assertIsNotEnabled()
        back()
        assertEquals(0, closed)
        wizard.endStepWrite()

        harness.setup.patchResult = ApiResult.HttpFailure(500, "The service is down.")
        tap(SetupTags.SKIP)
        composeRule.onNodeWithTag(SetupTags.STEP_ERROR).assertExists()
    }

    // ── Step 1 ──────────────────────────────────────────────────────────────────

    @Test
    fun `step 1 reads the website, says what it found, and opens the Skills page`() {
        harness.reads(setupState(region = "us"))
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(
                SetupLookupDraft(
                    services = listOf(SetupDraftService("Cut"), SetupDraftService("Dye")),
                    faqs = listOf(SetupDraftFaq("Q", "A")),
                    weeklyHours = json("""{"mon":"closed"}"""),
                ),
            ),
        )
        show()
        composeRule.onNodeWithTag(BusinessTags.READ_WEBSITE).assertIsNotEnabled()

        composeRule.onNodeWithTag(BusinessTags.WEBSITE).performTextInput("example.com")
        tap(BusinessTags.READ_WEBSITE)
        val found = str(
            R.string.setup_business_read_result,
            str(R.string.setup_business_found_services_other, 2),
            str(R.string.setup_business_found_faqs_one, 1),
        )
        composeRule.onNodeWithText(found, substring = true).assertExists()
        text(R.string.setup_business_hours_found).assertExists()

        tap(BusinessTags.OPEN_SKILLS)
        assertEquals(listOf(SetupHandOff.SKILLS), actions.opened)
    }

    @Test
    fun `an empty read and a refused read each have their sentence`() {
        harness.reads(setupState())
        show()
        composeRule.onNodeWithTag(BusinessTags.WEBSITE).performTextInput("example.com")
        tap(BusinessTags.READ_WEBSITE)
        text(R.string.setup_business_read_nothing).assertExists()

        harness.setup.lookupResult = ApiResult.HttpFailure(422, "Unreadable.", code = "website_unreadable")
        tap(BusinessTags.READ_WEBSITE)
        composeRule.onNodeWithTag(BusinessTags.NOTICE).assertExists()
    }

    @Test
    fun `an address-only read says the address was filled in`() {
        harness.reads(setupState())
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(SetupLookupDraft(address = json("""{"city":"Ottawa","country":"CA"}"""))),
        )
        show()
        composeRule.onNodeWithTag(BusinessTags.WEBSITE).performTextInput("example.com")
        tap(BusinessTags.READ_WEBSITE)
        text(R.string.setup_business_address_found).assertExists()
    }

    @Test
    fun `step 1 checks every field, offers the places, and saves`() {
        harness.reads(setupState(region = "ca"))
        harness.writes(setupState(business = SETUP_STEP_DONE))
        show(harness.wizard(name = "Sam's Communications Workspace"))
        text(R.string.setup_business_timezone_unknown).assertExists()
        composeRule.onNodeWithText(str(R.string.setup_business_subdivision_label_ca)).assertExists()

        tap(BusinessTags.SAVE)
        text(R.string.setup_business_name_required).assertExists()
        text(R.string.setup_business_subdivision_required).assertExists()
        text(R.string.setup_business_city_required).assertExists()

        tap(BusinessTags.SUBDIVISION)
        tap("${BusinessTags.SUBDIVISION}-option-MB")
        text(R.string.setup_business_timezone_value, "America/Winnipeg").assertExists()
        tap(BusinessTags.SUBDIVISION)
        tap("${BusinessTags.SUBDIVISION}-option-QC")
        text(R.string.setup_business_timezone_multi, "America/Toronto").assertExists()

        tap(BusinessTags.COUNTRY)
        tap("${BusinessTags.COUNTRY}-option-US")
        composeRule.onNodeWithText(str(R.string.setup_business_subdivision_label_us)).assertExists()
        tap(BusinessTags.COUNTRY)
        tap("${BusinessTags.COUNTRY}-option-FR")
        composeRule.onNodeWithTag(BusinessTags.SUBDIVISION).performScrollTo().performTextInput("Rhône")
        tap(SetupTags.choice("language", "fr"))

        composeRule.onNodeWithTag(BusinessTags.NAME).performScrollTo().performTextInput("Bloom")
        composeRule.onNodeWithTag(BusinessTags.WHAT).performScrollTo().performTextInput("Bakery")
        composeRule.onNodeWithTag(BusinessTags.CITY).performScrollTo().performTextInput("Lyon")
        composeRule.onNodeWithTag(BusinessTags.STREET).performScrollTo().performTextInput("1 Rue")
        tap(BusinessTags.SAVE)

        composeRule.onNodeWithTag(SetupTags.step(SetupStep.NUMBER)).assertExists()
        assertEquals("fr", harness.store.load(SetupHarness.WORKSPACE)?.language)
    }

    @Test
    fun `with no country step 1 asks for one, and offers no province field`() {
        harness.store.save(SetupBusinessDraft("Bloom", "", "", "Lyon", "en"), SetupHarness.WORKSPACE)
        harness.reads(setupState(region = null))
        show(harness.wizard(name = "Bloom"))
        composeRule.onNodeWithTag(BusinessTags.SUBDIVISION).assertDoesNotExist()
        text(R.string.setup_business_timezone_unknown).assertExists()

        tap(BusinessTags.SAVE)
        text(R.string.setup_business_country_required).assertExists()
        assertTrue("nothing is written without a country", harness.setup.patches.isEmpty())
    }

    @Test
    fun `a zone chosen in step 4 is shown as it is, not as the place's`() {
        harness.reads(
            setupState(
                facts = """{"timezone":"America/Winnipeg","address":{"city":"Ottawa","region":"ON","country":"CA"}}""",
            ),
        )
        show(harness.wizard(name = "Bloom"))
        text(R.string.setup_business_timezone_value, "America/Winnipeg").assertExists()
    }

    @Test
    fun `while step 1 saves, the place and the language cannot be changed`() {
        harness.reads(setupState(facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"}}"""))
        show(harness.wizard(name = "Bloom"))
        harness.setup.gate = CompletableDeferred()
        tap(BusinessTags.SAVE)

        composeRule.onNodeWithTag(BusinessTags.CITY).assertIsNotEnabled()
        composeRule.onNodeWithTag(BusinessTags.COUNTRY).performScrollTo().performClick()
        composeRule.onNodeWithTag("${BusinessTags.COUNTRY}-option-US").assertDoesNotExist()
        composeRule.onNodeWithTag(BusinessTags.SUBDIVISION).performScrollTo().performClick()
        composeRule.onNodeWithTag("${BusinessTags.SUBDIVISION}-option-QC").assertDoesNotExist()
        composeRule.onNodeWithTag(SetupTags.choice("language", "fr")).assertIsNotEnabled()
        harness.setup.gate!!.complete(Unit)
        composeRule.waitForIdle()
    }

    @Test
    fun `a refused step 1 save is said on the step`() {
        harness.reads(setupState(facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"}}"""))
        harness.setup.patchResult = ApiResult.HttpFailure(400, "The address was refused.")
        show(harness.wizard(name = "Bloom"))
        tap(BusinessTags.SAVE)
        composeRule.onNodeWithTag(BusinessTags.SAVE_ERROR).assertExists()
    }

    // ── Step 2 ──────────────────────────────────────────────────────────────────

    @Test
    fun `an owner with a number keeps it, and is told where calls are answered`() {
        harness.reads(setupState(business = SETUP_STEP_DONE, region = "eu"))
        harness.writes(setupState(business = SETUP_STEP_DONE, number = SETUP_STEP_DONE, region = "eu"))
        show()

        text(R.string.setup_number_included, 1, 3).assertExists()
        text(R.string.setup_number_answered_eu).assertExists()
        tap(NumberTags.KEEP)
        composeRule.onNodeWithTag(SetupTags.step(SetupStep.RECEPTIONIST)).assertExists()
    }

    @Test
    fun `an owner with no number opens the setup page, and a plan with no ceiling says how many are held`() {
        harness.reads(setupState(business = SETUP_STEP_DONE, numbersHeld = 0, includedNumbers = null, region = "apac"))
        show()

        text(R.string.setup_number_held, 0).assertExists()
        text(R.string.setup_number_answered_apac).assertExists()
        tap(NumberTags.OPEN_PAGE)
        assertEquals(listOf(SetupHandOff.SETUP), actions.opened)
    }

    @Test
    fun `off HTTPS the hand-off is drawn but cannot be pressed, and an unknown region says nothing`() {
        actions.https = false
        harness.reads(setupState(business = SETUP_STEP_DONE, numbersHeld = 0, region = null))
        show()
        composeRule.onNodeWithTag(NumberTags.OPEN_PAGE).assertIsNotEnabled()
        composeRule.onNodeWithTag(NumberTags.ANSWERED).assertDoesNotExist()
    }

    @Test
    fun `French takes the singular for zero and one, English for one only`() {
        var french = ""
        var english = ""
        composeRule.setContent {
            french = foundSentence(SetupBusinessNotice.Found(0, 2, hours = false, address = true), AppLanguage.FR)
            english = foundSentence(SetupBusinessNotice.Found(0, 1, hours = false, address = true), AppLanguage.EN)
        }
        composeRule.waitForIdle()
        assertTrue(french.contains(str(R.string.setup_business_found_services_one, 0)))
        assertTrue(french.contains(str(R.string.setup_business_found_faqs_other, 2)))
        assertTrue(english.contains(str(R.string.setup_business_found_services_other, 0)))
        assertTrue(english.contains(str(R.string.setup_business_found_faqs_one, 1)))
        assertTrue(
            "an address is said only when nothing else was found",
            !english.contains(str(R.string.setup_business_address_found))
        )
        assertEquals(R.string.setup_number_answered_us, answeredIn("us"))
        assertEquals(R.string.setup_number_answered_ca, answeredIn("ca"))
        assertEquals(null, answeredIn("mars"))
    }

    @Test
    fun `step 2's Keep is pressable when the number is held`() {
        harness.reads(setupState(business = SETUP_STEP_DONE))
        show()
        composeRule.onNodeWithTag(NumberTags.KEEP).assertIsEnabled()
    }
}
