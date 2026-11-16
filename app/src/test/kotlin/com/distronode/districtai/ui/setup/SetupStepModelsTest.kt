package com.distronode.districtai.ui.setup

import com.distronode.districtai.R
import com.distronode.districtai.core.model.PersonaDefaults
import com.distronode.districtai.core.model.PersonaLabelledValue
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaVoiceCatalog
import com.distronode.districtai.core.model.PersonaVoiceGroup
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.SetupLookupDraft
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.model.WorkspaceConfigSaveResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.ui.UiText
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Steps 3 and 4: the receptionist's persona fields, and the facts callers are told. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupStepModelsTest {

    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val harness = SetupHarness()

    private fun TestScope.receptionist(
        region: String = "ca",
        language: AppLanguage = AppLanguage.EN,
    ): Pair<SetupWizardViewModel, SetupReceptionistModel> {
        harness.reads(setupState(business = SETUP_STEP_DONE, number = SETUP_STEP_DONE, region = region))
        harness.writes(setupState(business = SETUP_STEP_DONE, number = SETUP_STEP_DONE, receptionist = SETUP_STEP_DONE))
        val wizard = harness.wizard(language = language)
        advanceUntilIdle()
        return wizard to wizard.stepModel as SetupReceptionistModel
    }

    private val options = PersonaOptionsResponse(
        success = true,
        voices = listOf(
            PersonaVoiceCatalog(
                engine = "gemini-live",
                language = "en-US",
                groups = listOf(
                    PersonaVoiceGroup(
                        "Voices",
                        listOf(PersonaLabelledValue("Kore", "Kore"), PersonaLabelledValue("Puck", "Puck"))
                    )
                ),
            ),
        ),
        defaults = PersonaDefaults(voiceByEngine = mapOf("gemini-live" to "Puck")),
    )

    // ── Step 3 ──────────────────────────────────────────────────────────────────

    @Test
    fun `an untouched starter greeting becomes the business's own, and follows a rename`() = runTest {
        harness.store.save(SetupBusinessDraft(businessName = "Bloom", language = "en"), SetupHarness.WORKSPACE)
        harness.config(
            SetupHarness.persona(
                greeting = SetupWire.starterGreetings.first(),
                modelId = "gemini-live",
                language = "en-US"
            )
        )
        harness.persona.optionsResult = ApiResult.Success(options)
        val (_, model) = receptionist()

        assertEquals(SetupStepLoad.Ready, model.load)
        assertEquals("Ava", model.name)
        assertEquals("Thanks for calling Bloom, this is Ava. How can I help?", model.greeting)
        assertFalse(model.greetingEdited)
        assertEquals("the stored voice is gone, so the engine's default", "Puck", model.voice)
        assertEquals(2, model.voiceGroups.single().options.size)

        model.editName("Zoe")
        assertEquals("Thanks for calling Bloom, this is Zoe. How can I help?", model.greeting)
        model.editName(" ")
        assertEquals("Thanks for calling Bloom, this is Ava. How can I help?", model.greeting)
    }

    @Test
    fun `a greeting the owner wrote is kept, and a typed one stops the template`() = runTest {
        harness.config(
            SetupHarness.persona(
                name = "Max",
                greeting = "Hello from Max",
                voice = "Kore",
                modelId = "gemini-live",
                language = "en-US"
            )
        )
        harness.persona.optionsResult = ApiResult.Success(options)
        val (_, model) = receptionist()

        assertEquals("Hello from Max", model.greeting)
        assertTrue(model.greetingEdited)
        assertEquals("Kore", model.voice)

        model.editGreeting("Hello from Max")
        model.editName("Lea")
        assertEquals("Hello from Max", model.greeting)
    }

    @Test
    fun `a stored greeting that is this business's template is still the template, and follows a rename`() = runTest {
        harness.store.save(SetupBusinessDraft(businessName = "Bloom", language = "en"), SetupHarness.WORKSPACE)
        harness.config(
            SetupHarness.persona(
                name = "Max",
                greeting = "Thanks for calling Bloom, this is Max. How can I help?",
                modelId = "gemini-live",
                language = "en-US",
            ),
        )
        harness.persona.optionsResult = ApiResult.Success(options)
        val (_, model) = receptionist()

        assertFalse(model.greetingEdited)
        model.editName("Lea")
        assertEquals("Thanks for calling Bloom, this is Lea. How can I help?", model.greeting)
    }

    @Test
    fun `typing a greeting stops the name from rewriting it`() = runTest {
        harness.config(SetupHarness.persona(greeting = "Thanks for calling, this is Ava. How can I help?"))
        val (_, model) = receptionist()
        assertFalse(model.greetingEdited)
        model.editGreeting("Mine")
        model.editName("Zoe")
        assertEquals("Mine", model.greeting)
        assertTrue(model.isStarter("", "Ava", ""))
    }

    @Test
    fun `no engine or no catalogue offers no voice and sends none`() = runTest {
        harness.config(SetupHarness.persona(name = "Ava", greeting = "Hi", voice = "aura-2"))
        harness.persona.optionsResult = ApiResult.NetworkFailure(IOException("down"))
        val (wizard, model) = receptionist()
        assertTrue(model.voiceGroups.isEmpty())
        assertNull(model.voice)

        model.save()
        advanceUntilIdle()
        val sent = harness.district.personaPatches.single()
        assertNull(sent.voice)
        assertEquals("Ava", sent.name)
        assertEquals("Hi", sent.greeting)
        assertEquals("en-US", sent.language)
        assertEquals(SetupStep.CALLERS, wizard.active)
    }

    @Test
    fun `an engine with voices but no default falls back to the first one`() = runTest {
        harness.config(SetupHarness.persona(modelId = "gemini-live", language = "en-US"))
        harness.persona.optionsResult = ApiResult.Success(options.copy(defaults = PersonaDefaults()))
        val (_, model) = receptionist()
        assertEquals("Kore", model.voice)

        model.voice = "Puck"
        model.save()
        advanceUntilIdle()
        assertEquals("Puck", harness.district.personaPatches.single().voice)
    }

    @Test
    fun `a French choice in step 1 saves a French receptionist`() = runTest {
        harness.store.save(SetupBusinessDraft(language = "fr"), SetupHarness.WORKSPACE)
        harness.config(SetupHarness.persona(language = "en-US"))
        val (_, model) = receptionist()
        assertEquals("Merci d'avoir appelé, ici Ava. Comment puis-je vous aider?", model.greeting)

        model.save()
        advanceUntilIdle()
        assertEquals("fr-CA", harness.district.personaPatches.single().language)
        assertEquals("fr-CA", model.languageToSave)
    }

    @Test
    fun `Europe says the disclosure is required`() = runTest {
        val (_, model) = receptionist(region = "eu")
        assertTrue(model.disclosureIsMandatory)
    }

    @Test
    fun `a failed config read is a retry, never a form`() = runTest {
        harness.district.workspaceConfigResult = ApiResult.NetworkFailure(IOException("down"))
        val (_, model) = receptionist()
        assertTrue(model.load is SetupStepLoad.Failed)

        harness.config(SetupHarness.persona(name = "Ava", greeting = "Hi"))
        model.load()
        assertEquals(SetupStepLoad.Loading, model.load)
        advanceUntilIdle()
        assertEquals(SetupStepLoad.Ready, model.load)
    }

    @Test
    fun `empty fields are refused, a refused save keeps the step, a stale re-read is saved`() = runTest {
        harness.config(SetupHarness.persona(name = "Ava", greeting = "Hi"))
        val (wizard, model) = receptionist()
        model.editName("")
        model.save()
        assertTrue(model.showErrors && model.nameMissing)
        model.editName("Ava")
        model.editGreeting(" ")
        model.save()
        assertTrue(model.greetingMissing)
        assertTrue(harness.district.personaPatches.isEmpty())

        model.editGreeting("Hi")
        harness.district.savePersonaResult = ApiResult.HttpFailure(400, "No")
        model.save()
        model.save()
        advanceUntilIdle()
        assertEquals(1, harness.district.personaPatches.size)
        assertTrue(model.saveError != null)
        assertEquals(SetupStep.RECEPTIONIST, wizard.active)

        // ⚠️ THE WRITE LANDED, ONLY THE RE-READ FAILED: the step is saved.
        harness.district.savePersonaResult = ApiResult.Success(WorkspaceConfigSaveResponse(success = true))
        harness.district.workspaceConfigResult = ApiResult.NetworkFailure(IOException("re-read lost"))
        model.save()
        advanceUntilIdle()
        assertEquals(SetupStep.CALLERS, wizard.active)
    }

    // ── Step 4 ──────────────────────────────────────────────────────────────────

    private fun TestScope.callers(
        facts: String? = null,
        language: AppLanguage = AppLanguage.EN,
    ): Pair<SetupWizardViewModel, SetupCallersModel> {
        harness.reads(
            setupState(
                business = SETUP_STEP_DONE,
                number = SETUP_STEP_DONE,
                receptionist = SETUP_STEP_DONE,
                facts = facts
            )
        )
        harness.writes(
            setupState(
                business = SETUP_STEP_DONE,
                number = SETUP_STEP_DONE,
                receptionist = SETUP_STEP_DONE,
                callers = SETUP_STEP_DONE
            ),
        )
        val wizard = harness.wizard(language = language)
        advanceUntilIdle()
        return wizard to wizard.stepModel as SetupCallersModel
    }

    @Test
    fun `step 4 opens on the stored facts and offers the country's zones`() = runTest {
        val (_, model) = callers(
            """{"timezone":"Asia/Tokyo","address":{"city":"Lyon","country":"fr"},
               "services":[{"name":"Cut","enabled":false}],"faqs":[{"question":"Q","answer":"A","enabled":true}]}""",
        )
        assertEquals("Asia/Tokyo", model.timezone)
        assertEquals(listOf("Asia/Tokyo", "Europe/Paris"), model.zones)
        assertTrue("a drafted service is switched off", model.hasDrafts)
        assertTrue(model.isValid)
    }

    @Test
    fun `with no stored address the zones come from step 1's country`() = runTest {
        harness.store.save(SetupBusinessDraft(country = "CA"), SetupHarness.WORKSPACE)
        val (_, model) = callers()
        assertTrue(model.zones.contains("America/Vancouver"))
        assertFalse(model.hasDrafts)
    }

    @Test
    fun `a day opens on nine to five, takes up to four periods, and an empty day is not set`() = runTest {
        val (_, model) = callers()
        model.setState(SetupDayState.OPEN, SetupWeekday.MON)
        assertEquals(listOf(540 to 1020), model.day(SetupWeekday.MON).intervals.map { it.open to it.close })
        model.addPeriod(SetupWeekday.MON)
        model.addPeriod(SetupWeekday.MON)
        model.addPeriod(SetupWeekday.MON)
        model.addPeriod(SetupWeekday.MON)
        assertEquals(4, model.day(SetupWeekday.MON).intervals.size)
        assertEquals(1080 to 1140, model.day(SetupWeekday.MON).intervals[1].let { it.open to it.close })

        model.addPeriod(SetupWeekday.TUE)
        assertEquals(
            "a period on a day with none starts at ten",
            600,
            model.day(SetupWeekday.TUE).intervals.single().open
        )

        val first = model.day(SetupWeekday.MON).intervals.first()
        model.setPeriod(SetupWeekday.MON, first.id, open = 480)
        model.setPeriod(SetupWeekday.MON, first.id, close = 600)
        assertEquals(480 to 600, model.day(SetupWeekday.MON).intervals.first().let { it.open to it.close })

        model.day(SetupWeekday.MON).intervals.forEach { model.removePeriod(it.id, SetupWeekday.MON) }
        assertEquals(SetupDayState.NOT_SET, model.day(SetupWeekday.MON).state)

        model.setState(SetupDayState.CLOSED, SetupWeekday.SUN)
        model.setState(SetupDayState.OPEN, SetupWeekday.TUE)
        assertEquals("an open day keeps its periods", 1, model.day(SetupWeekday.TUE).intervals.size)
    }

    @Test
    fun `a late period is capped at the end of the day`() = runTest {
        val (_, model) = callers("""{"weeklyHours":{"fri":[{"open":"20:00","close":"23:30"}]}}""")
        model.addPeriod(SetupWeekday.FRI)
        assertEquals(1380 to 1440, model.day(SetupWeekday.FRI).intervals.last().let { it.open to it.close })
    }

    @Test
    fun `a new period starts after the latest close, not the last period's`() = runTest {
        val (_, model) = callers(
            """{"weeklyHours":{"sat":[{"open":"18:00","close":"20:00"},{"open":"09:00","close":"12:00"}]}}""",
        )
        model.addPeriod(SetupWeekday.SAT)
        assertEquals(1260 to 1320, model.day(SetupWeekday.SAT).intervals.last().let { it.open to it.close })
    }

    @Test
    fun `a day whose period closes before it opens makes the step invalid`() = runTest {
        val (_, model) = callers()
        assertTrue(model.isValid)
        model.setState(SetupDayState.OPEN, SetupWeekday.WED)
        val period = model.day(SetupWeekday.WED).intervals.single()
        model.setPeriod(SetupWeekday.WED, period.id, close = period.open - 60)
        assertFalse(model.isValid)
    }

    @Test
    fun `a website whose hours are an empty object found no hours`() = runTest {
        val (_, model) = callers()
        harness.setup.lookupResult = ApiResult.Success(SetupLookupResponse(SetupLookupDraft(weeklyHours = json("{}"))))
        model.websiteUrl = "example.com"
        model.fillHoursFromWebsite()
        advanceUntilIdle()
        assertEquals(UiText.Resource(R.string.setup_callers_no_hours_found), model.hoursNotice)
        assertFalse(model.hoursDraftPending)
    }

    @Test
    fun `rows are added up to the cap, edited by identity and removed`() = runTest {
        val (_, model) = callers()
        model.addService()
        model.addFaq()
        val service = model.services.single()
        val faq = model.faqs.single()
        assertTrue("a row typed by hand starts on", service.enabled && faq.enabled)
        model.updateService(service.id) { it.copy(name = "Cut") }
        model.updateFaq(faq.id) { it.copy(question = "Q", answer = "A") }
        assertEquals("Cut", model.services.single().name)
        assertEquals("Q", model.faqs.single().question)
        model.removeService(service.id)
        model.removeFaq(faq.id)
        assertTrue(model.services.isEmpty() && model.faqs.isEmpty())

        repeat(SetupFacts.SERVICES + 1) { model.addService() }
        repeat(SetupFacts.FAQS + 1) { model.addFaq() }
        assertEquals(SetupFacts.SERVICES, model.services.size)
        assertEquals(SetupFacts.FAQS, model.faqs.size)
    }

    @Test
    fun `hours from the website are a draft until used, and Continue refuses past them`() = runTest {
        val (wizard, model) = callers("""{"weeklyHours":{"mon":"closed"}}""", language = AppLanguage.FR)
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(SetupLookupDraft(weeklyHours = json("""{"mon":[{"open":"08:00","close":"12:00"}]}"""))),
        )
        model.fillHoursFromWebsite()
        assertTrue("no address, no read", harness.setup.lookups.isEmpty())
        model.websiteUrl = "example.com"
        model.fillHoursFromWebsite()
        model.fillHoursFromWebsite()
        advanceUntilIdle()
        assertEquals(listOf("example.com" to "fr"), harness.setup.lookups)
        assertTrue(model.hoursDraftPending)
        assertEquals(SetupDayState.OPEN, model.day(SetupWeekday.MON).state)
        assertFalse("an unconfirmed draft is not sent", "weeklyHours" in model.patch())

        model.saveAndContinue()
        assertEquals(UiText.Resource(R.string.setup_callers_confirm_hours_first), model.saveError)
        assertTrue(harness.setup.patches.isEmpty())

        // A second read keeps the hours from before the FIRST draft for a discard.
        model.fillHoursFromWebsite()
        advanceUntilIdle()
        model.discardDraftHours()
        assertFalse(model.hoursDraftPending)
        assertEquals(SetupDayState.CLOSED, model.day(SetupWeekday.MON).state)
        model.discardDraftHours()

        model.fillHoursFromWebsite()
        advanceUntilIdle()
        model.useDraftHours()
        assertTrue("weeklyHours" in model.patch())
        model.saveAndContinue()
        advanceUntilIdle()
        assertEquals(SetupStep.CALLS, wizard.active)
    }

    @Test
    fun `editing a day confirms the website's hours`() = runTest {
        val (_, model) = callers()
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(SetupLookupDraft(weeklyHours = json("""{"tue":"closed"}"""))),
        )
        model.websiteUrl = "example.com"
        model.fillHoursFromWebsite()
        advanceUntilIdle()
        model.setState(SetupDayState.CLOSED, SetupWeekday.WED)
        assertFalse(model.hoursDraftPending)
    }

    @Test
    fun `a website with no hours, or one that cannot be read, says so`() = runTest {
        val (_, model) = callers()
        model.websiteUrl = "example.com"
        model.fillHoursFromWebsite()
        advanceUntilIdle()
        assertEquals(UiText.Resource(R.string.setup_callers_no_hours_found), model.hoursNotice)

        harness.setup.lookupResult = ApiResult.HttpFailure(422, "Unreadable", code = "website_unreadable")
        model.fillHoursFromWebsite()
        advanceUntilIdle()
        assertTrue(model.hoursNotice is UiText.Server)
        assertFalse(model.hoursDraftPending)
    }

    @Test
    fun `a save sends every section whole, an empty one as a clear`() = runTest {
        val (wizard, model) = callers("""{"address":{"city":"Lyon","country":"FR"}}""")
        model.address = SetupAddressDraft()
        model.save()
        model.save()
        advanceUntilIdle()

        val sent = harness.setup.patches.single().businessFacts!!
        assertEquals(JsonNull, sent["address"])
        assertEquals(JsonNull, sent["services"])
        assertEquals(JsonNull, sent["faqs"])
        assertEquals(JsonNull, sent["weeklyHours"])
        assertFalse("no zone chosen, none sent", "timezone" in sent)
        assertTrue(model.saved)
        assertEquals("Save does not move on", SetupStep.CALLERS, wizard.active)
    }

    @Test
    fun `an invalid row stops the save, and a refused one keeps the step`() = runTest {
        val (wizard, model) = callers()
        model.timezone = "Europe/Paris"
        model.addService()
        model.save()
        assertEquals(UiText.Resource(R.string.setup_callers_fix_errors), model.saveError)
        assertTrue(model.showErrors)
        model.updateService(model.services.single().id) { it.copy(name = "Cut") }

        harness.setup.patchResult = ApiResult.HttpFailure(400, "Bad")
        model.saveAndContinue()
        advanceUntilIdle()
        assertTrue(model.saveError != null)
        assertFalse(model.saved)
        assertEquals(SetupStep.CALLERS, wizard.active)
        assertEquals("Europe/Paris", harness.setup.patches.single().businessFacts!!["timezone"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Continue mid-save is refused`() = runTest {
        val (_, model) = callers()
        harness.setup.gate = CompletableDeferred()
        model.save()
        model.saveAndContinue()
        harness.setup.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, harness.setup.patches.size)
        assertTrue("the save carried no step state", harness.setup.patches.single().steps.isEmpty())
    }
}
