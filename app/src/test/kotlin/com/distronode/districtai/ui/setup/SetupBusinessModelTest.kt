package com.distronode.districtai.ui.setup

import com.distronode.districtai.core.model.RenameResponse
import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.SetupDraftFaq
import com.distronode.districtai.core.model.SetupDraftService
import com.distronode.districtai.core.model.SetupLookupDraft
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.l10n.AppLanguage
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Step 1: the website read saves drafts only, and the save writes the place, the name and "what you do". */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupBusinessModelTest {

    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val harness = SetupHarness()

    private fun TestScope.open(
        facts: String? = null,
        name: String = "Bloom",
        language: AppLanguage = AppLanguage.EN,
        region: String = "ca",
    ): Pair<SetupWizardViewModel, SetupBusinessModel> {
        harness.reads(setupState(facts = facts, region = region))
        harness.writes(setupState(business = SETUP_STEP_DONE, facts = facts, region = region))
        val wizard = harness.wizard(name = name, language = language)
        advanceUntilIdle()
        return wizard to wizard.stepModel as SetupBusinessModel
    }

    private fun lastFacts(): JsonObject = harness.setup.patches.last { it.businessFacts != null }.businessFacts!!

    @Test
    fun `it opens on the stored place and the workspace's name`() = runTest {
        val (_, model) = open(
            facts = """{"address":{"line1":"1 Rue","city":"Gatineau","region":"QC","country":"CA"},
                "faqs":[{"question":"Que fait l'entreprise?","answer":"Dentisterie","enabled":true}]}""",
        )

        assertEquals("Bloom", model.businessName)
        assertEquals("CA", model.country)
        assertEquals("QC", model.subdivision)
        assertEquals("Gatineau", model.city)
        assertEquals("1 Rue", model.street)
        assertEquals("Dentisterie", model.what)
        assertTrue(model.isValid)
    }

    @Test
    fun `with no stored address it opens on the draft, and a country change drops the province`() = runTest {
        harness.store.save(SetupBusinessDraft("Bloom", "CA", "ON", "Ottawa", "en"), SetupHarness.WORKSPACE)
        val (_, model) = open()
        assertEquals("ON", model.subdivision)
        assertEquals(SetupZone("America/Toronto", multiZone = true), model.zone)

        model.chooseCountry("CA")
        assertEquals("ON", model.subdivision)
        model.chooseCountry("FR")
        assertEquals("", model.subdivision)
        assertNull(model.subdivisions)
        assertEquals("Europe/Paris", model.timezone)
        model.chooseCountry("")
        assertNull(model.zone)
        assertNull(model.timezone)
    }

    @Test
    fun `every required field is checked`() = runTest {
        val (_, model) = open(name = "Sam's Communications Workspace", region = "us")
        assertTrue(model.nameMissing)
        assertFalse(model.countryMissing)
        assertTrue(model.subdivisionMissing)
        assertTrue(model.cityMissing)
        model.chooseCountry("")
        assertTrue(model.countryMissing)

        model.save()
        assertTrue(model.showErrors)
        assertTrue("nothing is written while the form is invalid", harness.setup.patches.isEmpty())
    }

    @Test
    fun `with a name, each other missing field alone still makes the form invalid`() = runTest {
        val (_, model) = open(facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"}}""")
        assertTrue(model.isValid)

        model.city = " "
        assertFalse(model.isValid)
        model.city = "Ottawa"

        model.subdivision = ""
        assertFalse(model.isValid)
        model.subdivision = "ON"

        model.chooseCountry("")
        assertFalse(model.isValid)
    }

    @Test
    fun `a new province is a new place, so its own zone is saved`() = runTest {
        val facts = """{"timezone":"America/Winnipeg","address":{"city":"Ottawa","region":"ON","country":"CA"}}"""
        val (_, model) = open(facts = facts)
        model.subdivision = "BC"
        assertEquals("America/Vancouver", model.timezone)
        assertEquals("America/Vancouver", model.factsPatch()["timezone"]!!.jsonPrimitive.content)
    }

    @Test
    fun `step 4's time zone survives a save over an unchanged place`() = runTest {
        val facts = """{"timezone":"America/Winnipeg","address":{"city":"Ottawa","region":"ON","country":"CA"}}"""
        val (_, model) = open(facts = facts)
        assertEquals("America/Winnipeg", model.timezone)
        assertEquals("America/Winnipeg", model.factsPatch()["timezone"]!!.jsonPrimitive.content)

        model.city = "Toronto"
        assertEquals("America/Toronto", model.timezone)
        assertEquals("America/Toronto", model.factsPatch()["timezone"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the website read appends drafts switched off, saves no hours, and fills only empty fields`() = runTest {
        val (wizard, model) = open(
            facts = """{"services":[{"name":"Cleaning","enabled":true,"note":"kept"}]}""",
            region = "eu"
        )
        model.chooseCountry("")
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(
                SetupLookupDraft(
                    address = json("""{"line1":"100 Example","city":"Toronto","region":"on","country":"CA"}"""),
                    weeklyHours = json("""{"mon":[{"open":"09:00","close":"17:00"}]}"""),
                    services = listOf(SetupDraftService("cleaning"), SetupDraftService("Whitening", price = "\$90")),
                    faqs = listOf(SetupDraftFaq("Parking?", "Yes")),
                ),
            ),
        )
        model.websiteUrl = "  example.com "
        model.readWebsite()
        assertTrue(model.isBusy)
        assertTrue(wizard.isMoving)
        advanceUntilIdle()

        assertEquals(listOf("example.com" to "en"), harness.setup.lookups)
        val facts = lastFacts()
        assertFalse("hours from a website are never saved from here", "weeklyHours" in facts)
        val services = facts["services"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Cleaning", "Whitening"), services.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("kept", services[0]["note"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), services[1]["enabled"])
        assertEquals("\$90", services[1]["price"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), facts["faqs"]!!.jsonArray[0].jsonObject["enabled"])
        assertEquals(SetupBusinessNotice.Found(services = 2, faqs = 1, hours = true, address = true), model.notice)
        assertEquals("CA", model.country)
        assertEquals("ON", model.subdivision)
        assertEquals("Toronto", model.city)
        assertEquals("100 Example", model.street)
        assertFalse(model.isBusy)

        model.dismissNotice()
        assertNull(model.notice)
    }

    @Test
    fun `a read in French asks for a French draft, and an address fills a missing province only`() = runTest {
        val (_, model) = open(language = AppLanguage.FR, facts = """{"address":{"city":"Ottawa","country":"CA"}}""")
        model.street = "Mine"
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(
                SetupLookupDraft(address = json("""{"line1":"Theirs","city":"York","region":"ZZ","country":"CA"}""")),
            ),
        )
        model.websiteUrl = "example.ca"
        model.readWebsite()
        advanceUntilIdle()

        assertEquals("fr", harness.setup.lookups.single().second)
        assertEquals("Mine", model.street)
        assertEquals("Ottawa", model.city)
        assertEquals("a province this table lacks is not chosen", "", model.subdivision)
        assertEquals(SetupBusinessNotice.Found(0, 0, hours = false, address = true), model.notice)
        assertTrue("an address-only answer writes nothing", harness.setup.patches.isEmpty())
    }

    @Test
    fun `an address from another country, or one with no parts, leaves the form alone`() = runTest {
        val (_, model) = open(region = "us")
        model.chooseCountry("US")
        val canada = SetupLookupDraft(address = json("""{"country":"CA"}"""))
        harness.setup.lookupResult = ApiResult.Success(SetupLookupResponse(canada))
        model.websiteUrl = "example.com"
        model.readWebsite()
        advanceUntilIdle()
        assertEquals("", model.subdivision)
        assertEquals("", model.city)

        model.chooseCountry("FR")
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(SetupLookupDraft(address = json("""{"region":"Rhône","country":"FR"}"""))),
        )
        model.readWebsite()
        advanceUntilIdle()
        assertEquals("a region outside the tables is taken as written", "RHÔNE", model.subdivision)
    }

    @Test
    fun `an empty draft says nothing was found, and a failed read says why`() = runTest {
        val (_, model) = open()
        model.readWebsite()
        assertTrue("no address, no read", harness.setup.lookups.isEmpty())

        model.websiteUrl = "example.com"
        model.readWebsite()
        advanceUntilIdle()
        assertEquals(SetupBusinessNotice.Nothing, model.notice)

        harness.setup.lookupResult = ApiResult.HttpFailure(422, "Unreadable.", code = "website_unreadable")
        model.readWebsite()
        advanceUntilIdle()
        assertTrue(model.notice is SetupBusinessNotice.Failed)
    }

    @Test
    fun `hours alone are found and write nothing, and an empty hours object is nothing found`() = runTest {
        val (_, model) = open()
        model.websiteUrl = "example.com"
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(SetupLookupDraft(weeklyHours = json("""{"tue":[{"open":"09:00","close":"17:00"}]}"""))),
        )
        model.readWebsite()
        advanceUntilIdle()
        assertEquals(SetupBusinessNotice.Found(0, 0, hours = true, address = false), model.notice)
        assertTrue("hours from a website are never saved from here", harness.setup.patches.isEmpty())

        harness.setup.lookupResult = ApiResult.Success(SetupLookupResponse(SetupLookupDraft(weeklyHours = json("{}"))))
        model.readWebsite()
        advanceUntilIdle()
        assertEquals(SetupBusinessNotice.Nothing, model.notice)
    }

    @Test
    fun `an address with no country, or one outside the list, fills the city but no country`() = runTest {
        val (_, model) = open(region = "us")
        model.chooseCountry("")
        model.websiteUrl = "example.com"
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(SetupLookupDraft(address = json("""{"city":"Springfield"}"""))),
        )
        model.readWebsite()
        advanceUntilIdle()
        assertEquals("Springfield", model.city)
        assertEquals("", model.country)

        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(SetupLookupDraft(address = json("""{"region":"XX","country":"ZZ"}"""))),
        )
        model.readWebsite()
        advanceUntilIdle()
        assertEquals("", model.country)
        assertEquals("", model.subdivision)
    }

    @Test
    fun `a province already chosen is not replaced by the website's`() = runTest {
        val (_, model) = open(facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"}}""")
        model.websiteUrl = "example.com"
        harness.setup.lookupResult = ApiResult.Success(
            SetupLookupResponse(SetupLookupDraft(address = json("""{"region":"QC","country":"CA"}"""))),
        )
        model.readWebsite()
        advanceUntilIdle()
        assertEquals("ON", model.subdivision)
    }

    @Test
    fun `a read is refused while the save is in flight`() = runTest {
        val (_, model) = open(facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"}}""")
        harness.setup.gate = CompletableDeferred()
        model.save()
        assertTrue(model.isBusy)
        model.websiteUrl = "example.com"
        model.readWebsite()
        harness.setup.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(harness.setup.lookups.isEmpty())
    }

    @Test
    fun `a drafts write the service refuses is said, not reported as found`() = runTest {
        val (_, model) = open()
        val faqs = SetupLookupDraft(faqs = listOf(SetupDraftFaq("Q", "A")))
        harness.setup.lookupResult = ApiResult.Success(SetupLookupResponse(faqs))
        harness.setup.patchResult = ApiResult.HttpFailure(400, "Too many")
        model.websiteUrl = "example.com"
        model.readWebsite()
        advanceUntilIdle()

        assertTrue(model.notice is SetupBusinessNotice.Failed)
    }

    @Test
    fun `the read and the save never overlap`() = runTest {
        val (_, model) = open(facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"}}""")
        harness.setup.gate = CompletableDeferred()
        model.websiteUrl = "example.com"
        model.readWebsite()
        model.readWebsite()
        model.save()
        advanceUntilIdle()
        assertEquals(1, harness.setup.lookups.size)
        assertTrue(harness.setup.patches.isEmpty())
        harness.setup.gate!!.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `the save writes the place, keeps the stored postal code, renames by the echo, and moves on`() = runTest {
        val facts = """{"address":{"city":"Ottawa","region":"ON","postalCode":"K1A 0A6","country":"CA"},
            "faqs":[{"question":"Parking?","answer":"Yes","enabled":true}]}"""
        val (wizard, model) = open(facts = facts, name = "Old name")
        harness.district.renameResult = ApiResult.Success(RenameResponse(success = true, name = "Bloom Dental"))
        model.businessName = " Bloom Dental  "
        model.what = "Family dentistry"
        model.street = " 1 Main "
        model.language = SetupLanguage.FR
        model.save()
        assertTrue(model.isSaving)
        advanceUntilIdle()

        val sent = harness.setup.patches.first().businessFacts!!
        assertEquals(
            json("""{"line1":"1 Main","city":"Ottawa","region":"ON","postalCode":"K1A 0A6","country":"CA"}"""),
            sent["address"],
        )
        assertEquals("America/Toronto", sent["timezone"]!!.jsonPrimitive.content)
        val faqs = sent["faqs"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Que fait l'entreprise?", faqs[0]["question"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), faqs[0]["enabled"])
        assertEquals("Parking?", faqs[1]["question"]!!.jsonPrimitive.content)
        assertEquals("Bloom Dental", harness.district.renameRequests.single().name)
        assertEquals("Bloom Dental", wizard.workspaceName)
        assertTrue(wizard.didRename)
        assertEquals(SetupBusinessDraft("Bloom Dental", "CA", "ON", "Ottawa", "fr"), wizard.draft)
        assertEquals(mapOf("business" to SETUP_STEP_DONE), harness.setup.patches.last().steps)
        assertEquals(SetupStep.NUMBER, wizard.active)
    }

    @Test
    fun `an unchanged name is not renamed, and a null echo keeps the typed name`() = runTest {
        val facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"}}"""
        val (wizard, model) = open(facts = facts)
        model.save()
        advanceUntilIdle()
        assertTrue(harness.district.renameRequests.isEmpty())
        assertFalse(wizard.didRename)

        val (renamed, other) = open(facts = facts)
        harness.district.renameResult = ApiResult.Success(RenameResponse(success = true, name = null))
        other.businessName = "Fresh"
        other.save()
        advanceUntilIdle()
        assertEquals("Fresh", renamed.workspaceName)
    }

    @Test
    fun `a refused facts write or rename keeps the step, with the refusal`() = runTest {
        val facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"}}"""
        val (wizard, model) = open(facts = facts)
        harness.setup.patchResult = ApiResult.HttpFailure(400, "Bad facts")
        model.save()
        advanceUntilIdle()
        assertTrue(model.saveError != null)
        assertEquals(SetupStep.BUSINESS, wizard.active)

        harness.writes(setupState(business = SETUP_STEP_DONE))
        harness.district.renameResult = ApiResult.NetworkFailure(IOException("offline"))
        model.businessName = "Other"
        model.save()
        advanceUntilIdle()
        assertTrue(model.saveError != null)
        assertEquals(SetupStep.BUSINESS, wizard.active)
        assertFalse(model.isSaving)
    }

    @Test
    fun `what you do keeps the owner's switch, and clearing it drops the question`() = runTest {
        val facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"},
            "faqs":[{"question":"What does the business do?","answer":"Old","enabled":false},
                    {"question":"Parking?","answer":"Yes","enabled":true}]}"""
        val (_, model) = open(facts = facts)
        model.what = "New"
        val kept = model.factsPatch()["faqs"]!!.jsonArray
        assertEquals(JsonPrimitive(false), kept[0].jsonObject["enabled"])
        assertEquals(2, kept.size)

        model.what = " "
        assertEquals(
            listOf("Parking?"),
            model.factsPatch()["faqs"]!!.jsonArray.map { it.jsonObject["question"]!!.jsonPrimitive.content }
        )
    }

    @Test
    fun `with no what and no stored one, the questions are left alone`() = runTest {
        val (_, model) = open(facts = """{"faqs":[{"question":"Parking?","answer":"Yes","enabled":true}]}""")
        model.what = ""
        assertFalse("faqs" in model.factsPatch())
    }

    @Test
    fun `a new what on a full list is refused, never truncated`() = runTest {
        val rows = (1..SetupFacts.FAQS).joinToString(",") { """{"question":"Q$it","answer":"A","enabled":true}""" }
        val (_, model) = open(facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"},"faqs":[$rows]}""")
        model.what = "Dentistry"
        assertTrue(model.faqsFull)

        model.save()
        assertTrue(model.saveError != null)
        assertTrue(harness.setup.patches.isEmpty())

        model.what = ""
        assertFalse(model.faqsFull)
    }

    @Test
    fun `a full list that already holds what you do has room, because that question is replaced`() = runTest {
        val others = (2..SetupFacts.FAQS).joinToString(",") { """{"question":"Q$it","answer":"A","enabled":true}""" }
        val what = """{"question":"What does the business do?","answer":"Old","enabled":true}"""
        val (_, model) = open(
            facts = """{"address":{"city":"Ottawa","region":"ON","country":"CA"},"faqs":[$what,$others]}""",
        )
        model.what = "Dentistry"
        assertFalse(model.faqsFull)
    }
}
