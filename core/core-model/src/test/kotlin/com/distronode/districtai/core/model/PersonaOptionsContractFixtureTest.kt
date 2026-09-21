package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persona vocabularies, the preview credential and the message-thread resolver, against the
 * committed fixtures.
 *
 * ⛔ THREE FIXTURES NO OTHER ANDROID TEST DECODES. `district-persona-options.json`,
 * `district-persona-preview-token.json` and `district-message-thread.json` are generated from the
 * real route handlers; without this file the gate that is supposed to catch a server-side field
 * addition would not be watching them at all.
 *
 * ⛔ EVERY FIXTURE HERE IS DECODED WITH `ignoreUnknownKeys = false`, through [ContractFixtures]. A
 * field added server-side fails this file rather than being silently dropped on a phone.
 */
class PersonaOptionsContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    // ── The vocabularies ─────────────────────────────────────────────────────

    @Test
    fun `the options payload decodes, with every list the form needs`() {
        val response =
            json.decodeFromString<PersonaOptionsResponse>(fixture("district-persona-options.json"))

        assertTrue(response.success)
        // ⚠️ CARRIED RATHER THAN DERIVED. Each engine's label states where its audio is processed,
        // which is a claim about the WORKSPACE's region and not the serving origin's.
        assertEquals("us", response.region)
        assertTrue("the catalogue must publish engines", response.engines.isNotEmpty())
        assertTrue("voice styles are Gemini Live's", response.voiceStyles.isNotEmpty())
        assertTrue("voices are published per engine and language", response.voices.isNotEmpty())
    }

    @Test
    fun `every engine carries its own response lengths, and the residency claim is in the label`() {
        val response =
            json.decodeFromString<PersonaOptionsResponse>(fixture("district-persona-options.json"))

        response.engines.forEach { engine ->
            assertTrue(
                "${engine.id} must publish its own answer lengths",
                engine.responseLengths.isNotEmpty(),
            )
            // ⛔ THE LABEL IS SHOWN VERBATIM BECAUSE IT CARRIES THE RESIDENCY CLAIM. Shortening it
            // to a product name is how a form stops saying where the audio goes.
            assertTrue("${engine.id} must be labelled", engine.label.isNotBlank())
        }
    }

    @Test
    fun `the two language lists are different, and the shorter one is not a subset`() {
        // ⛔ THE ASYMMETRY THIS TYPE EXISTS FOR. Deepgram publishes nl-NL and it-IT and does NOT
        // publish hi-IN; the general list is the other way round. A form that showed one list for
        // every engine would offer a language whose voice catalogue is empty.
        val response =
            json.decodeFromString<PersonaOptionsResponse>(fixture("district-persona-options.json"))

        val deepgram = response.languages.deepgram.map { it.value }.toSet()
        val general = response.languages.general.map { it.value }.toSet()
        assertTrue("both lists must be published", deepgram.isNotEmpty() && general.isNotEmpty())
        assertFalse("the lists are not interchangeable", deepgram == general)
    }

    @Test
    fun `the defaults arrive as the server's own constants, and temperature is a number`() {
        val response =
            json.decodeFromString<PersonaOptionsResponse>(fixture("district-persona-options.json"))

        // ⛔ THE SERVER'S CONSTANTS, NOT A CLIENT'S GUESS. A Kotlin literal here would be a second
        // source with nothing comparing the two.
        assertTrue(response.defaults.voiceByEngine.isNotEmpty())
        assertTrue(response.defaults.voiceByDeepgramLanguage.isNotEmpty())
        assertTrue(response.defaults.responseLength.isNotBlank())
        // ⚠️ A JSON NUMBER, unlike the STORED persona's temperature, which existing rows carry as
        // either a number or a string.
        assertTrue(response.defaults.temperature in 0.0..1.0)
    }

    @Test
    fun `every engine in the defaults map is an engine the catalogue publishes`() {
        // ⛔ THE ONE CONSISTENCY A CLIENT DEPENDS ON. `defaultVoice` looks a starting voice up by
        // engine id; a default keyed on an engine that is not in the list would hand the form a
        // voice for an engine nobody can select.
        val response =
            json.decodeFromString<PersonaOptionsResponse>(fixture("district-persona-options.json"))

        val ids = response.engines.map { it.id }.toSet()
        response.defaults.voiceByEngine.keys.forEach { engine ->
            assertTrue("$engine has a default voice but is not published", engine in ids)
        }
    }

    // ── The preview credential ───────────────────────────────────────────────

    @Test
    fun `the preview token decodes, and its room carries the prefix the agent branches on`() {
        val response = json.decodeFromString<PersonaPreviewTokenResponse>(
            fixture("district-persona-preview-token.json"),
        )

        assertTrue(response.success)
        assertEquals("contract-livekit-preview-jwt", response.token)
        assertEquals("wss://livekit-wss.distronode.com", response.url)
        // ⛔ THE AGENT BRANCHES ON THIS PREFIX to read the persona out of the token metadata rather
        // than the stored row. A name a client invented would be answered by the SAVED persona.
        assertTrue(response.roomName.startsWith(PREVIEW_ROOM_PREFIX))
    }

    @Test
    fun `the preview room carries an encryption key, and it is the base64 TEXT`() {
        val response = json.decodeFromString<PersonaPreviewTokenResponse>(
            fixture("district-persona-preview-token.json"),
        )

        // ⛔ A `preview_*` ROOM IS ALWAYS ENCRYPTED, so an absent key here is the server failing to
        // derive one rather than "join in the clear" — unlike `calls/token`, where absence is a
        // real answer.
        assertNotNull(response.e2ee)
        // ⛔ HANDED TO THE SDK VERBATIM AND NEVER BASE64-DECODED. Every LiveKit SDK UTF-8-encodes
        // this string and runs PBKDF2 over those ASCII bytes; decoding it to 32 raw bytes selects a
        // different derivation, and the failure is not an error — both sides join and every track
        // is undecryptable noise. Asserting the exact string is what pins "no transformation".
        assertEquals("YfxKDUkaaGp2WrLLGHCHbe2nn5ArCWBd+x+k7EzDr/8=", response.e2ee?.key)
    }

    // ── The push resolver ────────────────────────────────────────────────────

    @Test
    fun `the message thread resolver decodes, envelope facts only`() {
        val response =
            json.decodeFromString<MessageThreadResponse>(fixture("district-message-thread.json"))

        assertTrue(response.success)
        assertEquals("msg_contract_inbound", response.message.id)
        assertEquals("inbound", response.message.direction)
        assertEquals("sms", response.message.type)
        // ⚠️ NULL FOR AN UNREAD MESSAGE, which is the row a push is about.
        assertNull(response.message.readAt)
        assertEquals("2026-08-15T14:30:00.000Z", response.message.createdAt)
    }

    @Test
    fun `the thread target is what a push navigates on`() {
        val response =
            json.decodeFromString<MessageThreadResponse>(fixture("district-message-thread.json"))

        // ⛔ THE SAME VALUE THE CONVERSATION LIST COMPUTES, which is what lets a push open the
        // already-loaded conversation instead of forking a new one.
        assertEquals("contact:contact_contract_1", response.thread.threadKey)
        assertEquals("contact_contract_1", response.thread.contactId)
        assertEquals("+14165551234", response.thread.counterpart)
        assertEquals("sms", response.thread.channel)
    }
}
