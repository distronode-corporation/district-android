package com.distronode.districtai.ui.setup

import com.distronode.districtai.core.model.SETUP_STEP_DONE
import com.distronode.districtai.core.model.SETUP_STEP_SKIPPED
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.l10n.AppLanguage
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wizard's order, where it opens, who it refuses, and the values it writes as the web does. */
class SetupFlowTest {

    @Test
    fun `the steps are the web's six, by their wire names`() {
        assertEquals(
            listOf("business", "number", "receptionist", "callers", "calls", "golive"),
            SetupFlow.steps.map(SetupStep::wire),
        )
        assertEquals(SetupStep.NUMBER, SetupFlow.next(SetupStep.BUSINESS))
        assertNull(SetupFlow.next(SetupStep.GOLIVE))
        assertEquals(SetupStep.CALLS, SetupFlow.previous(SetupStep.GOLIVE))
        assertNull(SetupFlow.previous(SetupStep.BUSINESS))
    }

    @Test
    fun `the wizard opens on the first step still to do`() {
        val state = setupState(business = SETUP_STEP_DONE, number = SETUP_STEP_SKIPPED)
        assertEquals(SetupStep.RECEPTIONIST, SetupFlow.firstOpen(state.setupProgress))
    }

    @Test
    fun `with nothing left to do, or a state this build does not know, it opens on the first step`() {
        val done = setupState(
            business = SETUP_STEP_DONE,
            number = SETUP_STEP_DONE,
            receptionist = SETUP_STEP_DONE,
            callers = SETUP_STEP_DONE,
            calls = "postponed",
            golive = SETUP_STEP_SKIPPED,
        )
        assertEquals(SetupStep.BUSINESS, SetupFlow.firstOpen(done.setupProgress))
        assertEquals(SetupStep.BUSINESS, SetupFlow.firstOpen(null))
        assertEquals("postponed", done.setupProgress!!.steps.state(SetupStep.CALLS))
        assertEquals(SETUP_STEP_SKIPPED, done.setupProgress!!.steps.state(SetupStep.GOLIVE))
    }

    @Test
    fun `a member is told only the owner can do setup, and a pre-wizard workspace that it is not here`() {
        assertEquals(SetupRefusal.NOT_OWNER, SetupRefusal.from(ApiResult.Forbidden("Owner only")))
        assertEquals(
            SetupRefusal.NOT_AVAILABLE,
            SetupRefusal.from(ApiResult.HttpFailure(409, "Not available", code = "setup_not_available")),
        )
        assertNull(SetupRefusal.from(ApiResult.HttpFailure(409, "Conflict", code = "other")))
        assertNull(SetupRefusal.from(ApiResult.HttpFailure(500, "Down", code = "setup_not_available")))
        assertNull(SetupRefusal.from(ApiResult.NetworkFailure(IOException("offline"))))
    }

    @Test
    fun `the persona language names a family, or none`() {
        assertEquals(SetupLanguage.FR, SetupLanguage.ofPersona("fr-CA"))
        assertEquals(SetupLanguage.EN, SetupLanguage.ofPersona("EN-us"))
        assertNull(SetupLanguage.ofPersona("de-DE"))
        assertNull(SetupLanguage.ofPersona(null))
        assertEquals(SetupLanguage.FR, SetupLanguage.ofWire("fr"))
        assertEquals(SetupLanguage.EN, SetupLanguage.ofWire("xx"))
        assertEquals(SetupLanguage.FR, SetupLanguage.of(AppLanguage.FR))
        assertEquals(SetupLanguage.EN, SetupLanguage.of(AppLanguage.EN))
    }

    @Test
    fun `a stored language is kept unless the owner chose the other family`() {
        val en = SetupLanguage.EN
        val fr = SetupLanguage.FR
        assertEquals("fr-FR", SetupLanguage.toSave("fr-FR", chosen = fr, appDefault = en))
        assertEquals("en-US", SetupLanguage.toSave("fr-FR", chosen = en, appDefault = en))
        // ⛔ A GERMAN RECEPTIONIST LEFT ON THE APP'S LANGUAGE STAYS GERMAN.
        assertEquals("de-DE", SetupLanguage.toSave(" de-DE ".trim(), chosen = en, appDefault = en))
        assertEquals("fr-CA", SetupLanguage.toSave("de-DE", chosen = fr, appDefault = en))
        assertEquals("fr-CA", SetupLanguage.toSave(null, chosen = fr, appDefault = en))
        assertEquals("en-US", SetupLanguage.toSave("  ", chosen = en, appDefault = fr))
    }

    @Test
    fun `the first draft takes the stored place and the persona's language`() {
        val facts = """{"address":{"city":"Gatineau","region":"qc","country":"CA"}}"""
        val draft = SetupBusinessDraft.initial(setupState(facts = facts), "fr-CA", "Clinique", SetupLanguage.EN)
        assertEquals(SetupBusinessDraft("Clinique", "CA", "QC", "Gatineau", "fr"), draft)
        assertEquals(SetupLanguage.FR, draft.setupLanguage)
    }

    @Test
    fun `with nothing stored the draft opens on the region's country and the app's language`() {
        val draft = SetupBusinessDraft.initial(
            setupState(region = "us"),
            null,
            "Sam's Communications Workspace",
            SetupLanguage.FR,
        )
        assertEquals(SetupBusinessDraft("", "US", "", "", "fr"), draft)
        val unknown = SetupBusinessDraft.initial(null, "de", "Shop", SetupLanguage.EN)
        assertEquals(SetupBusinessDraft("Shop", "", "", "", "en"), unknown)
    }

    @Test
    fun `a stored province this table does not have is dropped, a region elsewhere is kept as typed`() {
        val canada = SetupBusinessDraft.initial(
            setupState(facts = """{"address":{"city":"X","region":"ZZ","country":"CA"}}"""),
            null,
            "A",
            SetupLanguage.EN,
        )
        assertEquals("", canada.subdivision)
        val france = SetupBusinessDraft.initial(
            setupState(facts = """{"address":{"city":"Lyon","region":"Rhône","country":"FR"}}"""),
            null,
            "A",
            SetupLanguage.EN,
        )
        assertEquals("Rhône", france.subdivision)
    }

    @Test
    fun `the wire values match the web byte for byte`() {
        assertEquals("What does the business do?", SetupWire.whatQuestion(SetupLanguage.EN))
        assertEquals("Que fait l'entreprise?", SetupWire.whatQuestion(SetupLanguage.FR))
        assertEquals(2, SetupWire.whatQuestions.size)
        assertEquals(
            "Thanks for calling Bloom, this is Ava. How can I help?",
            SetupWire.greeting(" Bloom ", "Ava", SetupLanguage.EN),
        )
        assertEquals(
            "Thanks for calling, this is Ava. How can I help?",
            SetupWire.greeting("", "Ava", SetupLanguage.EN),
        )
        assertEquals(
            "Merci d'avoir appelé Bloom, ici Ava. Comment puis-je vous aider?",
            SetupWire.greeting("Bloom", "Ava", SetupLanguage.FR),
        )
        assertEquals(
            "Merci d'avoir appelé, ici Ava. Comment puis-je vous aider?",
            SetupWire.greeting("", "Ava", SetupLanguage.FR),
        )
        assertTrue(SetupWire.isDefaultWorkspaceName("Sam's Communications Workspace "))
        assertFalse(SetupWire.isDefaultWorkspaceName("Bloom"))
    }

    @Test
    fun `a phone number is E164, or ten or eleven North American digits in a us or ca workspace`() {
        assertEquals("+33142685300", SetupWire.e164("+33 1 42 68 53 00", region = "eu"))
        assertEquals("+14165550123", SetupWire.e164("(416) 555-0123", region = "ca"))
        assertEquals("+14165550123", SetupWire.e164("1 416 555 0123", region = "us"))
        assertNull(SetupWire.e164("416 555 0123", region = "eu"))
        assertNull(SetupWire.e164("1 416 555 0123", region = "apac"))
        assertNull(SetupWire.e164("0123", region = "ca"))
        assertNull(SetupWire.e164("1 016 555 0123", region = "ca"))
    }
}
