package com.distronode.districtai.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * District HQ by voice: every voice fixture decodes, writes back what it read, and means what the
 * session relies on. These fixtures left `ContractManifest.NOT_ON_ANDROID` with this file.
 */
class HqVoiceContractTest {

    @Test
    fun `the voice credential decodes as an encrypted HQ room in push-to-talk`() {
        val token = WireMirror.assertMirrors(HqVoiceTokenResponse.serializer(), "district-hq-voice-token.json")

        assertTrue(token.success)
        assertTrue("an hq_ room with a key is joinable", token.isJoinable)
        assertEquals(HqMicMode.PUSH, token.micModeValue)
        assertEquals("us", token.processingRegion)
        assertTrue(token.roomName.startsWith(HqVoice.ROOM_PREFIX))
        assertTrue("the session id is what end and discard are sent with", token.sessionId.isNotBlank())
        assertTrue("the key is handed over verbatim", !token.e2ee?.key.isNullOrBlank())
    }

    @Test
    fun `the credential never prints its token, session id or key`() {
        val token = ContractFixtures.json.decodeFromString(
            HqVoiceTokenResponse.serializer(),
            ContractFixtures.read("district-hq-voice-token.json"),
        )
        val printed = token.toString()

        assertFalse(printed.contains(token.token))
        // ⚠️ The room's name embeds the session id; the id itself is never printed as a field.
        assertFalse(printed.replace(token.roomName, "").contains(token.sessionId))
        assertTrue(printed.contains("sessionId=<redacted>"))
        assertFalse(printed.contains(token.e2ee!!.key))
        assertFalse(token.e2ee.toString().contains(token.e2ee!!.key))
        assertTrue(printed.contains(token.roomName))
        assertTrue(token.copy(e2ee = null).toString().contains("e2ee=none"))
    }

    @Test
    fun `a room that is not an HQ room, or has no key, is never joinable`() {
        val token = ContractFixtures.json.decodeFromString(
            HqVoiceTokenResponse.serializer(),
            ContractFixtures.read("district-hq-voice-token.json"),
        )

        assertFalse(token.copy(roomName = "meet_ws_1").isJoinable)
        assertFalse(token.copy(e2ee = null).isJoinable)
        assertFalse(token.copy(e2ee = HqVoiceE2ee("  ")).isJoinable)
        assertEquals(token.e2ee!!.key, token.joinKey)
        assertEquals("", token.copy(e2ee = null).joinKey)
        assertFalse("no token to join with", token.copy(token = "").isJoinable)
        assertFalse("no server to join", token.copy(url = " ").isJoinable)
        assertFalse("no session to end", token.copy(sessionId = "").isJoinable)
    }

    @Test
    fun `push only for push, an open microphone for anything else`() {
        assertEquals(HqMicMode.PUSH, HqMicMode.fromWire("push"))
        assertEquals(HqMicMode.OPEN, HqMicMode.fromWire("open"))
        assertEquals(HqMicMode.OPEN, HqMicMode.fromWire("walkie"))
    }

    @Test
    fun `the settings read and the settings write decode the member's five choices`() {
        val read = WireMirror.assertMirrors(HqVoiceSettingsResponse.serializer(), "district-hq-voice-settings.json")
        val put = WireMirror.assertMirrors(HqVoiceSettingsResponse.serializer(), "district-hq-voice-settings-put.json")

        assertEquals(HqVoiceSettings("Puck", "normal", "normal", "portal", "open"), read.settings)
        assertEquals(HqVoiceSettings("Charon", "detailed", "normal", "fr", "push"), put.settings)
        assertTrue(read.success && put.success)
    }

    @Test
    fun `the session's two endings and the screen report decode`() {
        val end = WireMirror.assertMirrors(HqVoiceEndResponse.serializer(), "district-hq-voice-end.json")
        val discard = WireMirror.assertMirrors(HqVoiceDiscardResponse.serializer(), "district-hq-voice-discard.json")
        val context = WireMirror.assertMirrors(HqVoiceContextResponse.serializer(), "district-hq-voice-context.json")
        val cancel = WireMirror.assertMirrors(HqProposalCancelResponse.serializer(), "district-hq-proposal-cancel.json")

        assertTrue("this fixture pins the member's Stop ending the session", end.success && end.ended)
        assertTrue("this fixture pins a session discarded in time", discard.success && discard.discarded)
        assertTrue(context.success)
        assertTrue(cancel.success)
    }

    @Test
    fun `the allowance refusal carries its code and the service's own sentence`() {
        // ⚠️ AN ERROR ENVELOPE, read here only for the two values the session keys on; the client's
        // mapping of it to a coded failure is pinned in core-network's HqVoiceRequestTest.
        val refusal = ContractFixtures.json.parseToJsonElement(
            ContractFixtures.read("district-hq-voice-token-allowance.json"),
        ) as JsonObject

        assertEquals(HqVoice.ALLOWANCE_REACHED_CODE, refusal["code"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(false), refusal["success"])
        assertTrue(refusal["error"]!!.jsonPrimitive.content.contains("text console still works"))
    }

    @Test
    fun `history is the last six non-blank turns, trimmed and cut as the service bounds it`() {
        val long = "x".repeat(HqVoice.HISTORY_MAX_CHARACTERS + 20)
        val turns = listOf(
            HqTurn("user", "one"),
            HqTurn("model", "two"),
            HqTurn("user", "   "),
            HqTurn("model", "three"),
            HqTurn("user", "  four  "),
            HqTurn("model", "five"),
            HqTurn("user", "six"),
            HqTurn("model", long),
        )

        val bounded = HqVoice.boundedHistory(turns)

        assertEquals(HqVoice.HISTORY_MAX_TURNS, bounded.size)
        assertEquals(listOf("two", "three", "four", "five", "six"), bounded.dropLast(1).map { it.text })
        assertEquals(HqVoice.HISTORY_MAX_CHARACTERS, bounded.last().text.length)
        assertEquals("model", bounded.last().role)
        assertTrue(HqVoice.boundedHistory(emptyList()).isEmpty())
        // ⚠️ A cut that would split an emoji drops the whole character instead.
        val emoji = "a".repeat(HqVoice.HISTORY_MAX_CHARACTERS - 1) + "\uD83D\uDE00"
        val cut = HqVoice.boundedHistory(listOf(HqTurn("user", emoji))).single().text
        assertEquals(HqVoice.HISTORY_MAX_CHARACTERS - 1, cut.length)
        assertFalse(cut.last().isHighSurrogate())
    }

    @Test
    fun `defaults decode an empty body as a refusal, never a credential`() {
        val empty = ContractFixtures.json.decodeFromString(HqVoiceTokenResponse.serializer(), "{}")
        assertFalse(empty.success)
        assertFalse(empty.isJoinable)
        assertNull(empty.e2ee)
        assertFalse(ContractFixtures.json.decodeFromString(HqVoiceEndResponse.serializer(), "{}").ended)
        assertFalse(ContractFixtures.json.decodeFromString(HqVoiceDiscardResponse.serializer(), "{}").discarded)
        assertFalse(ContractFixtures.json.decodeFromString(HqVoiceContextResponse.serializer(), "{}").success)
        assertFalse(ContractFixtures.json.decodeFromString(HqProposalCancelResponse.serializer(), "{}").success)
        assertEquals(
            HqVoiceSettings(),
            ContractFixtures.json.decodeFromString(HqVoiceSettingsResponse.serializer(), "{}").settings,
        )
    }
}
