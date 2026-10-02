package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push-registration and inbound-answer half of the contract gate.
 *
 * ⛔ A SEPARATE CLASS FROM `ContractFixtureTest` for the reason the device, dial, meetings and
 * billing halves are: that class reached detekt's LargeClass ceiling as endpoints accumulated, and
 * the healthy answer is another class rather than a raised threshold. The strict decoder and the
 * fixture loader come from [ContractFixtures] so the split cannot leave one of them lenient.
 *
 * ⛔ THE ANSWER FIXTURE MATTERS MORE THAN THE TWO REGISTRATION ONES, AND FOR A DIFFERENT REASON
 * FROM THE DIAL FIXTURE. A dial that this client cannot decode is a telephone ringing with nobody
 * on it, billed. An ANSWER that this client cannot decode is a caller who has been told a human is
 * coming — `POST calls/{id}/answer` writes the Redis rendezvous the agent's transfer is blocked on
 * BEFORE this body is read — so a renamed field leaves the agent released, the PSTN fallback
 * skipped, and the human never joining. Nothing is billed and nobody is dialled; the caller simply
 * sits in silence.
 */
class PushContractFixtureTest {

    private val json: Json = ContractFixtures.json

    @Test
    fun `both device-registration routes answer the same bare success envelope`() {
        // ⛔ ONE DTO FOR TWO ROUTES, WHICH IS ONLY SAFE WHILE THEIR SHAPES AGREE — the same rule
        // `DeviceContractFixtureTest` states about the two revoke routes. If either grows a field
        // this is what fails, and the answer is to split the type: the routes mean opposite things,
        // and a caller reading a register's field off an unregister's body would read a default.
        val register = json.decodeFromString<PushRegistrationResponse>(
            ContractFixtures.read("district-device-register.json"),
        )
        val unregister = json.decodeFromString<PushRegistrationResponse>(
            ContractFixtures.read("district-device-unregister.json"),
        )

        assertEquals(true, register.success)
        assertEquals(true, unregister.success)
    }

    @Test
    fun `neither registration route echoes the token, the device or the platform back`() {
        // ⛔ A PRIVACY PROPERTY PINNED AS A SHAPE. An FCM registration token identifies ONE
        // installation, and a response that repeated it would put it in one more log on every
        // app start and every token rotation. The server deliberately answers `{success:true}` and
        // nothing else; this is the cheapest place a regression in that decision shows up.
        listOf("district-device-register.json", "district-device-unregister.json").forEach { name ->
            val raw = ContractFixtures.read(name)
            assertFalse("$name must not echo the token", raw.contains("token"))
            assertFalse("$name must not echo the device id", raw.contains("deviceId"))
            assertFalse("$name must not echo the platform", raw.contains("platform"))
        }
    }

    @Test
    fun `the answer response carries a usable join credential`() {
        val response = json.decodeFromString<CallAnswerResponse>(
            ContractFixtures.read("district-call-answer.json"),
        )

        assertEquals(true, response.success)
        // ⛔ BOTH OR NEITHER. Every field of this DTO defaults, so a `{}` body would decode into a
        // well-formed response holding an empty token and an empty URL — and the failure would
        // surface at `engine.connect` as a media-plane error for what was really a server refusal,
        // on a screen already showing a connected call. `InboundCallRepository` rejects that at
        // runtime; this asserts the real response is not that.
        assertTrue("the token must not be blank", response.token.isNotBlank())
        assertTrue("the url must not be blank", response.url.isNotBlank())
        assertTrue("the url must be a websocket url", response.url.startsWith("wss://"))
    }

    @Test
    fun `the answered room is an existing call_ room, which is why nothing asserts a prefix`() {
        val response = json.decodeFromString<CallAnswerResponse>(
            ContractFixtures.read("district-call-answer.json"),
        )

        // ⛔ THE OPPOSITE OF THE DIAL FIXTURE'S ASSERTION, AND DELIBERATELY SO. A dial must produce
        // a `direct_` room because that prefix is what `request_fnc` refuses BY NAME, keeping the AI
        // off a room this app created. An ANSWER joins the room the SIP bridge already made, which
        // the agent is in and is supposed to be in — the handoff metadata inside the token is what
        // tells it to step back. A `direct_` room arriving here would mean the server had pointed
        // the answer route at the wrong family of room entirely.
        assertFalse(
            "an answered call must not be a direct_ room, got ${response.roomName}",
            response.roomName.startsWith(DIRECT_ROOM_PREFIX),
        )
        assertTrue("the room must be named", response.roomName.isNotBlank())
    }

    @Test
    fun `the answer response carries no caller identity, and must not gain one`() {
        // ⛔ THE IDS-ONLY DISCIPLINE, PINNED WHERE IT CAN REGRESS. The push payload carries no
        // number and no name because a notification is readable by the OS and by any
        // notification-listener app — and this route is the natural place for somebody to "helpfully"
        // add the caller back, since by here the request is authenticated. It must not: the ringing
        // screen shows a label precisely because this client never learns who is calling, and a
        // field here would make that a lie rather than a limitation. ⚠️ With
        // `ignoreUnknownKeys = false`, a new key would ALSO fail the decode above — this assertion
        // is what says which key and why.
        val raw = ContractFixtures.read("district-call-answer.json")

        assertFalse(raw.contains("from"))
        assertFalse(raw.contains("phoneNumber"))
        assertFalse(raw.contains("callerName"))
    }

    @Test
    fun `push fixtures survive a round trip in both encodings`() {
        WireMirror.assertFixtureRoundTrips(PushRegistrationResponse.serializer(), "district-device-register.json")
        WireMirror.assertFixtureRoundTrips(PushRegistrationResponse.serializer(), "district-device-unregister.json")
        WireMirror.assertFixtureRoundTrips(CallAnswerResponse.serializer(), "district-call-answer.json")
    }

    @Test
    fun `an unmodelled field on the answer response is rejected rather than ignored`() {
        // ⛔ PROVES THE GUARD GUARDS FOR THIS FILE'S DECODER TOO — the same duplicated assertion
        // `DeviceContractFixtureTest` carries, and duplicated for the same reason: the classes share
        // one `Json`, so if this ever passes the shared decoder has been relaxed and every half of
        // the gate has quietly stopped protecting anything.
        val withExtraField =
            """{"success":true,"url":"wss://x","token":"t","roomName":"call_ws_1","from":"+1416"}"""

        val failure = runCatching { json.decodeFromString<CallAnswerResponse>(withExtraField) }

        assertTrue(
            "Decoding an unknown key MUST fail. It succeeded, which means ignoreUnknownKeys is " +
                "no longer false and contract drift can now ship silently.",
            failure.isFailure,
        )
    }
}
