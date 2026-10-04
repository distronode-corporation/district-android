package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.PERSONA_GEMINI_LIVE_ENGINE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persona form's language and answer length, against [TEST_PERSONA_OPTIONS].
 *
 * ⛔ THE ENGINE IS READ-ONLY HERE (it is the Voice Studio's), so every assertion about the engine
 * is about what this form does NOT send.
 */
class PersonaIdentityDraftTest {

    private val deepgram = AiPersona(modelId = "deepgram-pipeline", language = "en-US", voice = "aura-2-asteria-en")

    @Test
    fun `a workspace with no persona starts on the server's defaults and sends nothing`() {
        val draft = PersonaIdentityDraft.hydrate(null, TEST_PERSONA_OPTIONS)

        assertEquals("", draft.modelId)
        assertEquals("", draft.values.voice)
        assertEquals("concise", draft.values.responseLength)
        assertFalse(draft.isDirty)
        assertEquals(PersonaIdentityWrite(), draft.write)
    }

    @Test
    fun `the language list and answer lengths follow the stored engine`() {
        val draft = PersonaIdentityDraft.hydrate(deepgram, TEST_PERSONA_OPTIONS)

        assertEquals(listOf("en-US", "it-IT"), draft.languages.map { it.value })
        assertEquals(deepgram, draft.persona)
        assertEquals(draft.values, draft.baseline)
        assertEquals(listOf("concise", "balanced"), draft.responseLengths.map { it.value })
    }

    @Test
    fun `a language change keeps the voice on an engine that is not keyed by language`() {
        // ⚠️ GEMINI LIVE KEEPS ONE VOICE PER ENGINE, and that voice is the Voice Studio's to change.
        val gemini = AiPersona(modelId = PERSONA_GEMINI_LIVE_ENGINE, language = "en-US", voice = "Kore")
        val draft = PersonaIdentityDraft.hydrate(gemini, TEST_PERSONA_OPTIONS).selectLanguage("hi-IN")

        assertEquals("Kore", draft.values.voice)
        assertEquals(PersonaIdentityWrite(language = "hi-IN"), draft.write)
    }

    @Test
    fun `a language with no published voice keeps the stored one rather than clearing it`() {
        val draft = PersonaIdentityDraft.hydrate(deepgram, TEST_PERSONA_OPTIONS).selectLanguage("xx-XX")

        assertEquals("aura-2-asteria-en", draft.values.voice)
    }

    @Test
    fun `a level change on a persona with no engine is not sent, because the server would drop it`() {
        val draft = PersonaIdentityDraft.hydrate(AiPersona(), TEST_PERSONA_OPTIONS).selectResponseLength("balanced")

        assertTrue(draft.isDirty)
        assertNull(draft.write.responseLength)
        assertNull(draft.write.modelId)
    }

    @Test
    fun `the audition carries the form on screen and the stored engine settings`() {
        val stored = deepgram.copy(temperature = 0.3, voiceStyle = "warm", preemptiveTts = true)
        val form = PersonaIdentityDraft.hydrate(stored, TEST_PERSONA_OPTIONS)
            .selectResponseLength("balanced")
            .previewForm(name = "Ada", greeting = "", personality = "Brisk.")

        assertEquals("Ada", form.name)
        assertNull("an empty box is not sent", form.greeting)
        assertEquals("deepgram-pipeline", form.modelId)
        assertEquals("balanced", form.responseLength)
        assertEquals(0.3, form.temperature!!, 0.0)
        assertEquals("warm", form.voiceStyle)
        assertEquals(true, form.preemptiveTts)
    }

    @Test
    fun `an audition of a persona that never set a temperature uses the server's default`() {
        val form = PersonaIdentityDraft.hydrate(null, TEST_PERSONA_OPTIONS).previewForm("", "", "")

        assertEquals(0.7, form.temperature!!, 0.0)
        assertNull(form.voiceStyle)
        assertEquals(false, form.preemptiveTts)
    }
}
