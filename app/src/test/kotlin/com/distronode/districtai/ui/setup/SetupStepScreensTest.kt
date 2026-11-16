package com.distronode.districtai.ui.setup

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.CalendarResolution
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.ConnectionsResponse
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.ForwardingCheck
import com.distronode.districtai.core.model.ForwardingCheckResponse
import com.distronode.districtai.core.model.PersonaLabelledValue
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaVoiceCatalog
import com.distronode.districtai.core.model.PersonaVoiceGroup
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.SetupLookupDraft
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.model.WorkspaceConnection
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_LOADING_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_RETRY_DESCRIPTION
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

/** Steps 3 to 6 on screen, against the real models over fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w600dp-h4000dp")
class SetupStepScreensTest {

    @get:Rule(order = 0)
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val harness = SetupHarness()
    private val actions = RecordingSetupActions()

    private fun at(
        step: SetupStep,
        region: String = "ca",
        numbersHeld: Int = 1,
        facts: String? = null,
    ): DistrictSetupResponse {
        val done = SetupStep.entries.takeWhile { it != step }.map { it.wire }.toSet()
        fun s(of: SetupStep) = if (of.wire in done) SETUP_STEP_DONE else "todo"
        return setupState(
            business = s(SetupStep.BUSINESS),
            number = s(SetupStep.NUMBER),
            receptionist = s(SetupStep.RECEPTIONIST),
            callers = s(SetupStep.CALLERS),
            calls = s(SetupStep.CALLS),
            region = region,
            numbersHeld = numbersHeld,
            facts = facts,
        )
    }

    private fun show(state: DistrictSetupResponse, next: DistrictSetupResponse = state): SetupWizardViewModel {
        harness.reads(state)
        harness.writes(next)
        val wizard = harness.wizard()
        composeRule.setContent { DistrictTheme { SetupWizardScreen(wizard, actions, onClose = {}) } }
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

    private fun type(tag: String, text: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
        composeRule.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any) = composeRule.onNodeWithText(str(id, *args), substring = true)

    // ── Step 3 ──────────────────────────────────────────────────────────────────

    @Test
    fun `step 3 offers the engine's voices, says the disclosure in Europe, and checks the fields`() {
        harness.config(
            SetupHarness.persona(
                name = "Ava",
                greeting = "Hi",
                voice = "Kore",
                modelId = "gemini-live",
                language = "en-US"
            )
        )
        harness.persona.optionsResult = ApiResult.Success(
            PersonaOptionsResponse(
                success = true,
                voices = listOf(
                    PersonaVoiceCatalog(
                        "gemini-live",
                        "en-US",
                        listOf(
                            PersonaVoiceGroup(
                                "Voices",
                                listOf(PersonaLabelledValue("Kore", "Kore"), PersonaLabelledValue("Puck", "Puck")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        show(at(SetupStep.RECEPTIONIST, region = "eu"), next = at(SetupStep.CALLERS, region = "eu"))
        composeRule.onNodeWithTag(ReceptionistTags.DISCLOSURE).assertExists()

        tap(ReceptionistTags.VOICE)
        tap("${ReceptionistTags.VOICE}-option-Puck")
        type(ReceptionistTags.NAME, "")
        type(ReceptionistTags.GREETING, "")
        tap(ReceptionistTags.SAVE)
        text(R.string.setup_receptionist_name_required).assertExists()
        text(R.string.setup_receptionist_greeting_required).assertExists()

        type(ReceptionistTags.NAME, "Zoe")
        type(ReceptionistTags.GREETING, "Hello")
        harness.district.savePersonaResult = ApiResult.HttpFailure(400, "Refused.")
        tap(ReceptionistTags.SAVE)
        composeRule.onNodeWithTag(ReceptionistTags.SAVE_ERROR).assertExists()
        assertEquals("Puck", harness.district.personaPatches.single().voice)
    }

    @Test
    fun `step 3 without voices says where the voice is changed, and a failed read offers a retry`() {
        harness.district.workspaceConfigResult = ApiResult.NetworkFailure(IOException("down"))
        show(at(SetupStep.RECEPTIONIST))
        composeRule.onNodeWithTag(WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION).assertExists()

        harness.config(SetupHarness.persona())
        tap(WORKSPACE_SETTINGS_RETRY_DESCRIPTION)
        text(R.string.setup_receptionist_voice_elsewhere).assertExists()
        composeRule.onNodeWithTag(ReceptionistTags.DISCLOSURE).assertDoesNotExist()
        tap(ReceptionistTags.SAVE)
        composeRule.onNodeWithTag(SetupTags.step(SetupStep.CALLERS)).assertExists()
        assertEquals("no voice is sent when none is offered", null, harness.district.personaPatches.single().voice)
    }

    @Test
    fun `steps 3 and 5 show a skeleton while they read`() {
        harness.store.save(SetupBusinessDraft(businessName = "Bloom"), SetupHarness.WORKSPACE)
        harness.district.gate = CompletableDeferred()
        harness.reads(at(SetupStep.RECEPTIONIST))
        val wizard = harness.wizard()
        composeRule.setContent { DistrictTheme { SetupWizardScreen(wizard, actions, onClose = {}) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WORKSPACE_SETTINGS_LOADING_DESCRIPTION).assertExists()

        harness.district.gate!!.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ReceptionistTags.NAME).assertExists()

        harness.district.gate = CompletableDeferred()
        harness.writes(at(SetupStep.CALLERS))
        wizard.skip()
        harness.writes(at(SetupStep.CALLS))
        composeRule.waitForIdle()
        wizard.skip()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SetupTags.step(SetupStep.CALLS)).assertExists()
        composeRule.onNodeWithTag(WORKSPACE_SETTINGS_LOADING_DESCRIPTION).assertExists()
        harness.district.gate!!.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CallsTags.CONTINUE).assertExists()
    }

    // ── Step 4 ──────────────────────────────────────────────────────────────────

    @Test
    fun `step 4 edits hours, address, services and questions, and saves`() {
        val facts = """{"timezone":"America/Toronto","address":{"city":"Ottawa","country":"CA"},
            "services":[{"name":"Cut","enabled":false}],"faqs":[{"question":"Q","answer":"A","enabled":true}]}"""
        show(at(SetupStep.CALLERS, facts = facts), next = at(SetupStep.CALLS))
        composeRule.onNodeWithTag(CallersTags.DRAFT_NOTE).assertExists()
        text(R.string.setup_callers_off_hint).assertExists()

        tap(CallersTags.TIMEZONE)
        tap("${CallersTags.TIMEZONE}-option-America/Halifax")
        tap(CallersTags.day(SetupWeekday.MON))
        tap("${CallersTags.day(SetupWeekday.MON)}-option-OPEN")
        tap(CallersTags.addPeriod(SetupWeekday.MON))
        tap(CallersTags.period(SetupWeekday.MON, 1, "remove"))
        tap(CallersTags.period(SetupWeekday.MON, 0, "close"))
        tap("${CallersTags.period(SetupWeekday.MON, 0, "close")}-option-08:00")
        composeRule.onNodeWithTag(CallersTags.periodError(SetupWeekday.MON)).assertExists()
        tap(CallersTags.period(SetupWeekday.MON, 0, "open"))
        tap("${CallersTags.period(SetupWeekday.MON, 0, "open")}-option-07:00")
        composeRule.onNodeWithTag(CallersTags.periodError(SetupWeekday.MON)).assertDoesNotExist()

        type(CallersTags.ADDRESS_LINE, "1 Main")
        type(CallersTags.ADDRESS_REGION, "ON")
        type(CallersTags.ADDRESS_POSTAL, "K1A")
        type(CallersTags.ADDRESS_CITY, "")
        type(CallersTags.ADDRESS_COUNTRY, "CAN")
        tap(CallersTags.SAVE)
        text(R.string.setup_callers_address_incomplete).assertExists()
        composeRule.onNodeWithTag(CallersTags.SAVE_ERROR).assertExists()
        type(CallersTags.ADDRESS_CITY, "Ottawa")
        type(CallersTags.ADDRESS_COUNTRY, "CA")

        tap(CallersTags.item("service", 0, "use"))
        type(CallersTags.item("service", 0, "price"), "$30")
        tap(CallersTags.ADD_SERVICE)
        tap(CallersTags.SAVE)
        text(R.string.setup_callers_service_error).assertExists()
        type(CallersTags.item("service", 1, "name"), "Dye")
        tap("${CallersTags.item("service", 1, "use")}-remove")

        tap(CallersTags.ADD_FAQ)
        tap(CallersTags.SAVE)
        text(R.string.setup_callers_faq_error).assertExists()
        type(CallersTags.item("faq", 1, "question"), "Parking?")
        type(CallersTags.item("faq", 1, "answer"), "Yes")
        tap(CallersTags.item("faq", 0, "use"))
        tap("${CallersTags.item("faq", 0, "use")}-remove")

        tap(CallersTags.SAVE)
        composeRule.onNodeWithTag(CallersTags.SAVED).assertExists()
        val sent = harness.setup.patches.last().businessFacts!!
        assertEquals("America/Halifax", sent["timezone"].toString().trim('"'))
        tap(CallersTags.SAVE_AND_CONTINUE)
        composeRule.onNodeWithTag(SetupTags.step(SetupStep.CALLS)).assertExists()
    }

    @Test
    fun `step 4 with nothing stored says so, and hours from the website wait to be used or discarded`() {
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(SetupLookupDraft(weeklyHours = json("""{"sun":"closed"}"""))),
        )
        show(at(SetupStep.CALLERS))
        text(R.string.setup_callers_no_services).assertExists()
        text(R.string.setup_callers_no_faqs).assertExists()
        composeRule.onNodeWithTag(CallersTags.FILL).assertIsNotEnabled()

        type(CallersTags.WEBSITE, "example.com")
        tap(CallersTags.FILL)
        composeRule.onNodeWithTag(CallersTags.HOURS_DRAFT).assertExists()
        tap(CallersTags.SAVE_AND_CONTINUE)
        text(R.string.setup_callers_confirm_hours_first).assertExists()
        tap(CallersTags.DISCARD_HOURS)
        tap(CallersTags.FILL)
        tap(CallersTags.USE_HOURS)
        composeRule.onNodeWithTag(CallersTags.HOURS_DRAFT).assertDoesNotExist()

        harness.setup.lookupResult = ApiResult.Success(SetupLookupResponse())
        tap(CallersTags.FILL)
        composeRule.onNodeWithTag(CallersTags.HOURS_NOTICE).assertExists()
    }

    @Test
    fun `step 4 says it is reading while it reads`() {
        show(at(SetupStep.CALLERS))
        harness.setup.gate = CompletableDeferred()
        type(CallersTags.WEBSITE, "example.com")
        tap(CallersTags.FILL)
        text(R.string.setup_business_reading).assertExists()
        harness.setup.gate!!.complete(Unit)
        composeRule.waitForIdle()
    }

    // ── Step 5 ──────────────────────────────────────────────────────────────────

    @Test
    fun `step 5 rings the app first with a ring time, saves it, and shows the hand-off facts`() {
        harness.handling(CallHandling.AI_FIRST)
        harness.config(
            SetupHarness.withTransfer(SetupHarness.persona(dgiEnabled = false), "+14165550100", tier = "dgi")
        )
        harness.setup.connectionsResult = ApiResult.Success(
            ConnectionsResponse(listOf(WorkspaceConnection("google-calendar", "active")), CalendarResolution("google")),
        )
        show(at(SetupStep.CALLS), next = at(SetupStep.GOLIVE))
        text(R.string.setup_calls_transfer_current, "+14165550100").assertExists()
        text(R.string.setup_calls_bookings_connected, "Google Calendar").assertExists()
        text(R.string.setup_calls_profiles_off).assertExists()
        composeRule.onNodeWithTag(CallsTags.SAVE_ANSWERING).assertIsNotEnabled()

        tap(SetupTags.choice("mode", CallHandling.APP_FIRST))
        text(R.string.setup_calls_ring_seconds, 20).assertExists()
        tap(CallsTags.RING_UP)
        tap(CallsTags.RING_DOWN)
        tap(CallsTags.RING_DOWN)
        text(R.string.setup_calls_ring_seconds, 19).assertExists()
        harness.handling(CallHandling.APP_FIRST, ring = 19)
        tap(CallsTags.SAVE_ANSWERING)
        composeRule.onNodeWithTag(CallsTags.SAVED).assertExists()

        tap(CallsTags.PROFILES)
        composeRule.onNodeWithTag(CallsTags.PROFILES).assertIsOn()
        text(R.string.setup_calls_profiles_on).assertExists()

        tap(CallsTags.OPEN_SKILLS)
        tap(CallsTags.OPEN_INTEGRATIONS)
        assertEquals(listOf(SetupHandOff.SKILLS, SetupHandOff.INTEGRATIONS), actions.opened)
        tap(CallsTags.CONTINUE)
        composeRule.onNodeWithTag(SetupTags.step(SetupStep.GOLIVE)).assertExists()
    }

    @Test
    fun `step 5 shows a kept mode, no transfer number, no calendar, a refusal, and a retry`() {
        harness.handling(CallHandling.AI_THEN_APP)
        harness.setup.connectionsResult = ApiResult.Success(ConnectionsResponse())
        show(at(SetupStep.CALLS))
        text(R.string.setup_calls_ai_then_app).assertExists()
        text(R.string.setup_calls_transfer_none).assertExists()
        text(R.string.setup_calls_bookings_none).assertExists()
        composeRule.onNodeWithTag(CallsTags.PROFILES).assertDoesNotExist()

        harness.callHandling.handlingResult = ApiResult.HttpFailure(400, "Refused.")
        tap(SetupTags.choice("mode", CallHandling.AI_FIRST))
        tap(CallsTags.SAVE_ANSWERING)
        composeRule.onNodeWithTag(CallsTags.ERROR).assertExists()
    }

    @Test
    fun `step 5's ring stops at the route's bounds, and a failed read offers a retry`() {
        harness.handling(CallHandling.APP_FIRST, ring = CallHandling.MAXIMUM_RING_SECONDS)
        harness.callHandling.handlingResult = ApiResult.NetworkFailure(IOException("down"))
        show(at(SetupStep.CALLS))
        composeRule.onNodeWithTag(WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION).assertExists()

        harness.handling(CallHandling.APP_FIRST, ring = CallHandling.MAXIMUM_RING_SECONDS)
        tap(WORKSPACE_SETTINGS_RETRY_DESCRIPTION)
        composeRule.onNodeWithTag(CallsTags.RING_UP).assertIsNotEnabled()
        harness.handling(CallHandling.APP_FIRST, ring = CallHandling.MINIMUM_RING_SECONDS)
    }

    // ── Step 6 ──────────────────────────────────────────────────────────────────

    private fun check(status: String, failure: String? = null) =
        ForwardingCheck(id = "f", status = status, verifierNumber = "+14165550142", failure = failure)

    @Test
    fun `step 6 places the test call, checks the forwarding, and finishes`() {
        harness.config(SetupHarness.persona(greeting = "Hello"))
        harness.handling(CallHandling.APP_FIRST, ring = 12)
        harness.setup.forwardingResult = ApiResult.Success(ForwardingCheckResponse(available = true, attemptsLeft = 2))
        show(
            at(SetupStep.GOLIVE),
            next = at(
                SetupStep.GOLIVE
            ).copy(setupProgress = at(SetupStep.GOLIVE).setupProgress?.copy(completedAt = "x"))
        )
        text(R.string.setup_golive_answers_app_first, 12).assertExists()
        text(R.string.setup_golive_attempts_left, 2).assertExists()

        type(GoLiveTags.MOBILE, "12")
        tap(GoLiveTags.CALL_ME)
        text(R.string.setup_golive_mobile_error).assertExists()
        type(GoLiveTags.MOBILE, "+14165550123")
        tap(GoLiveTags.CALL_ME)
        text(R.string.setup_golive_calling).assertExists()
        harness.setup.testCallResult = ApiResult.RateLimited("Too many test calls today.")
        tap(GoLiveTags.CALL_ME)
        composeRule.onNodeWithTag(GoLiveTags.CALL_STATUS).assertExists()

        type(GoLiveTags.BUSINESS_LINE, "nope")
        tap(GoLiveTags.START_CHECK)
        text(R.string.setup_golive_business_line_error).assertExists()
        tap(SetupTags.choice("forwarding", "unanswered"))
        text(R.string.setup_golive_unanswered_note).assertExists()
        type(GoLiveTags.BUSINESS_LINE, "+14165550123")
        val dialFailed = ForwardingCheckResponse(true, check("failed", "dial_failed"), 1)
        harness.setup.forwardingStartResult = ApiResult.Success(dialFailed)
        tap(GoLiveTags.START_CHECK)
        text(R.string.setup_golive_check_dial_failed).assertExists()
        text(R.string.setup_golive_attempts_left_one).assertExists()

        harness.setup.forwardingStartResult = ApiResult.RateLimited("No checks left.")
        tap(GoLiveTags.START_CHECK)
        composeRule.onNodeWithText("No checks left.", substring = true).assertExists()

        tap(GoLiveTags.FINISH)
        composeRule.onNodeWithTag(SetupTags.FINISHED).assertExists()
    }

    @Test
    fun `step 6 without a number says so, hides an unavailable check, and leaves out a summary it cannot read`() {
        harness.callHandling.handlingResult = ApiResult.NetworkFailure(IOException("down"))
        show(at(SetupStep.GOLIVE, numbersHeld = 0))
        composeRule.onNodeWithTag(GoLiveTags.NO_NUMBER).assertExists()
        composeRule.onNodeWithTag(GoLiveTags.BUSINESS_LINE).assertDoesNotExist()
        composeRule.onNodeWithTag(GoLiveTags.SUMMARY).assertDoesNotExist()
    }

    @Test
    fun `step 6 hides a check it could not read`() {
        harness.setup.forwardingResult = ApiResult.NetworkFailure(IOException("down"))
        show(at(SetupStep.GOLIVE))
        composeRule.onNodeWithTag(GoLiveTags.BUSINESS_LINE).assertDoesNotExist()
    }

    @Test
    fun `step 6 says each check's state and who answers`() {
        harness.handling(CallHandling.AI_THEN_APP)
        harness.setup.forwardingResult = ApiResult.Success(ForwardingCheckResponse(true, check("passed"), 3))
        show(at(SetupStep.GOLIVE))
        text(R.string.setup_golive_check_passed).assertExists()
        text(R.string.setup_golive_answers_ai_then_app).assertExists()
        text(R.string.setup_golive_no_greeting).assertExists()
    }

    @Test
    fun `step 6 says when the check failed, and while it is pending what call to expect`() {
        harness.handling(CallHandling.AI_FIRST)
        harness.setup.forwardingResult = ApiResult.Success(ForwardingCheckResponse(true, check("failed"), 0))
        show(at(SetupStep.GOLIVE))
        text(R.string.setup_golive_check_failed).assertExists()
        text(R.string.setup_calls_ai_first).assertExists()
        composeRule.onNodeWithTag(GoLiveTags.START_CHECK).assertIsNotEnabled()
    }

    @Test
    fun `a pending check names the number that will call`() {
        harness.handling(CallHandling.AI_FIRST)
        harness.setup.forwardingResult = ApiResult.Success(ForwardingCheckResponse(true, check("pending"), 2))
        harness.reads(at(SetupStep.GOLIVE))
        val wizard = SetupWizardViewModel(
            harness.deps,
            SetupHarness.WORKSPACE,
            "Bloom",
            com.distronode.districtai.l10n.AppLanguage.EN,
            SetupTiming(60_000, 1)
        )
        composeRule.setContent { DistrictTheme { SetupWizardScreen(wizard, actions, onClose = {}) } }
        composeRule.waitForIdle()
        text(R.string.setup_golive_expect_call, "+14165550142").assertExists()
        text(R.string.setup_golive_check_pending).assertExists()
        // While it polls the button keeps its own title (nothing is being saved) and cannot be pressed.
        composeRule.onNodeWithTag(GoLiveTags.START_CHECK).assertIsNotEnabled()
        composeRule.onNodeWithText(str(R.string.setup_golive_start_check)).assertExists()
        (wizard.stepModel as SetupGoLiveModel).close()
        composeRule.waitForIdle()
        text(R.string.setup_golive_check_still_pending).assertExists()
    }

    @Test
    fun `a check still pending can be read again with no line typed and no check left today`() {
        harness.handling(CallHandling.AI_FIRST)
        harness.setup.forwardingResult = ApiResult.Success(ForwardingCheckResponse(true, check("pending"), 0))
        harness.reads(at(SetupStep.GOLIVE))
        val wizard = SetupWizardViewModel(
            harness.deps,
            SetupHarness.WORKSPACE,
            "Bloom",
            com.distronode.districtai.l10n.AppLanguage.EN,
            SetupTiming(60_000, 1),
        )
        composeRule.setContent { DistrictTheme { SetupWizardScreen(wizard, actions, onClose = {}) } }
        composeRule.waitForIdle()
        (wizard.stepModel as SetupGoLiveModel).close()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(GoLiveTags.START_CHECK).performScrollTo().assertIsEnabled()
        tap(GoLiveTags.START_CHECK)
        val model = wizard.stepModel as SetupGoLiveModel
        assertTrue("the held check is polled again", model.isPolling)
        assertTrue("no new check is placed", harness.setup.forwardingStarts.isEmpty())
        model.close()
    }
}
