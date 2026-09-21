package com.distronode.districtai.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rooms half of the contract gate.
 *
 * ⛔ A SEPARATE CLASS FROM `ContractFixtureTest` for the reason the device and billing halves are:
 * that class reached detekt's LargeClass ceiling as endpoints accumulated, and the healthy answer
 * to a class that has grown too large is another class rather than a raised threshold. The strict
 * decoder and the fixture loader are shared through [ContractFixtures] precisely so the split
 * cannot leave one of them lenient.
 *
 * ⛔ FOUR FIXTURES FOR THREE ROUTES, AND THE EXTRA ONE IS THE POINT. `POST /api/district/calls/token`
 * SPREADS `guestInvite`/`guestPath` into its response only for a non-viewer, so the two role
 * branches are structurally different objects. With `ignoreUnknownKeys = false` a DTO proven
 * against one is proven against half the responses the route produces.
 */
class MeetingContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    // ── The join token ───────────────────────────────────────────────────────

    @Test
    fun `a non-viewer token carries a guest invite and the path built from it`() {
        val response =
            json.decodeFromString<RoomTokenResponse>(fixture("district-room-token.json"))

        assertEquals(true, response.success)
        assertTrue("the token must not be blank", response.token.isNotBlank())
        // ⛔ THE MEDIA-NODE URL, PINNED AS A `wss://` ONE. It is used verbatim — the room only
        // exists on the deployment that created it — so a client must never derive or rewrite it.
        assertTrue("the url must be a websocket url", response.url.startsWith("wss://"))

        assertNotNull("a non-viewer must receive an invite", response.guestInvite)
        val invite = response.guestInvite!!
        assertTrue("the invite must carry an expiry", invite.exp > 0)
        assertTrue("the invite must carry a signature", invite.sig.isNotBlank())

        // ⛔ THE PATH IS ASSEMBLED SERVER-SIDE FROM THE ROOM NAME AND BOTH INVITE FIELDS, and this
        // pins that a client never has to (or gets to) rebuild it. The signature is computed over
        // the room name, so a locally assembled path is a path `/api/meet/token` will refuse.
        assertNotNull("a non-viewer must receive a guest path", response.guestPath)
        val path = response.guestPath!!
        assertTrue("the path must address the meet route", path.startsWith("/meet/"))
        assertTrue("the path must carry the expiry", path.contains("e=${invite.exp}"))
        assertTrue("the path must carry the signature", path.contains("s="))
        // ⚠️ A PATH, NOT A URL. The origin is the client's own; a scheme here would mean the server
        // had decided which host to send a guest to, which it deliberately does not.
        assertFalse("the guest path must not be absolute", path.startsWith("http"))

        // ⛔ THE ROOM KEY, AND THE CROSS-PLATFORM CONTRACT IT CARRIES. Every LiveKit SDK treats
        // this string as a PASSPHRASE: it UTF-8-encodes the 44 ASCII characters and derives the
        // AES-GCM key with PBKDF2 (verified in the 2.28.0 AAR — `BaseKeyProvider.setSharedKey`
        // calls `String.getBytes(Charsets.UTF_8)` and nothing else). A client that base64-decoded
        // it to 32 raw bytes would select HKDF instead and derive a DIFFERENT key from the same
        // input: it would join, publish, and be unable to decrypt anyone. No exception anywhere,
        // just a meeting where nobody can hear each other.
        assertNotNull("a meet_ room is end-to-end encrypted", response.e2ee)
        val key = response.e2ee!!.key
        assertEquals("the base64 TEXT of 32 bytes, not the bytes", 44, key.length)
        assertTrue("base64 of 32 bytes is padded", key.endsWith("="))

        // ⛔ VERBATIM, PROVEN AGAINST THE FIXTURE'S OWN BYTES rather than a hardcoded literal. The
        // key is derived from a server-side secret, so pinning its value here would couple this
        // suite to the website's test environment and break on a rotation that broke nothing.
        // Asserting the decoded string appears unaltered in the source text proves the one thing
        // that matters: the decode path transforms nothing.
        assertTrue(
            "the decoded key must appear verbatim in the fixture",
            fixture("district-room-token.json").contains(key),
        )
    }

    @Test
    fun `a viewer token OMITS both guest keys rather than nulling them`() {
        // ⛔ THE SECURITY BRANCH. `/api/meet/token` grants canPublish:true to whoever presents a
        // valid invite, and an invite is a transferable twelve-hour capability — so minting one
        // for a read-only seat would hand it a publish-capable route into the room it had just
        // been refused, and let it admit unauthenticated outsiders with publish rights. If this
        // fixture ever grows the keys, that gate has been reopened.
        val raw = fixture("district-room-token-viewer.json")
        val response = json.decodeFromString<RoomTokenResponse>(raw)

        assertEquals(true, response.success)
        assertTrue(response.token.isNotBlank())
        assertNull("a viewer must receive no invite", response.guestInvite)
        assertNull("a viewer must receive no guest path", response.guestPath)
        // ⚠️ ABSENT, NOT `null`. The route spreads `...{}` rather than writing explicit nulls, so
        // a decoder that required the keys would fail on every viewer join. Asserted against the
        // raw text because a decoded `null` and an absent key are the same value to the DTO.
        assertFalse("`guestInvite` must be absent from the body", raw.contains("guestInvite"))
        assertFalse("`guestPath` must be absent from the body", raw.contains("guestPath"))

        // ⛔ ENCRYPTION IS A PROPERTY OF THE ROOM, NOT OF THE SEAT — the one place this fixture
        // differs from the guest keys above, and the reason it is asserted right next to them. A
        // viewer is refused an invite because an invite is a transferable PUBLISH capability; it
        // is NOT refused the room key, because without it a read-only attendee could not decode
        // the media it is entitled to watch. Holding the key grants nothing on its own — the
        // LiveKit token still decides what the holder may do. If these two ever diverge, viewers
        // have gone deaf and blind in a room they were admitted to.
        val nonViewer =
            json.decodeFromString<RoomTokenResponse>(fixture("district-room-token.json"))
        assertEquals(
            "a viewer joins the same encrypted room and needs the same key",
            nonViewer.e2ee?.key,
            response.e2ee?.key,
        )
        assertTrue("the viewer's key must be usable", !response.e2ee?.key.isNullOrBlank())
    }

    // ── The room's encryption key ────────────────────────────────────────────
    //
    // ⛔ THE TWO BELOW RUN AGAINST LOCAL LITERALS BECAUSE NO FIXTURE COVERS EITHER SHAPE, not
    // because the fixtures are unavailable. The value-level assertions moved onto the two
    // fixture-reading tests above the moment the regenerated files landed. What is left here is
    // exactly the pair of branches the corpus cannot reach: both committed room-token samples are
    // `meet_` rooms and therefore both carry a key, so "no `e2ee` block" (every `call_` room, the
    // majority of this route's traffic) and "an `e2ee` block with no key" have no fixture and would
    // otherwise be verified by nothing.

    @Test
    fun `a token with no e2ee block means join unencrypted, not something went wrong`() {
        // ⛔ THE `call_` BRANCH, WHICH HAS NO FIXTURE AND IS THE MAJORITY OF THIS ROUTE'S TRAFFIC.
        // A supervisor joining a live phone call gets no key because the call has a SIP leg and the
        // carrier delivers it unencrypted — there is nothing an app-side key could protect. A DTO
        // that required the block, or a caller that treated null as an error, would refuse to join
        // exactly the rooms that work today.
        val body = """{"success":true,"token":"jwt","url":"wss://livekit.test"}"""

        val response = json.decodeFromString<RoomTokenResponse>(body)

        assertNull("an absent e2ee block is a valid, unencrypted join", response.e2ee)
    }

    @Test
    fun `an e2ee block with no key decodes to a blank key the caller must refuse`() {
        // ⚠️ DECODES RATHER THAN THROWS, because `key` carries a default — and that is why
        // `ActiveRoomViewModel` gates on `isNotBlank` rather than on the block's presence. A blank
        // passphrase is NOT "no encryption": it derives a real AES key that nobody else derives.
        // This pins the shape that makes that guard necessary, so deleting the guard has a test
        // that explains why it existed.
        val body = """{"success":true,"token":"jwt","url":"wss://livekit.test","e2ee":{}}"""

        val response = json.decodeFromString<RoomTokenResponse>(body)

        assertEquals("", response.e2ee?.key)
    }

    // ⚠️ The encode direction is covered by `room fixtures survive a round trip in both encodings`
    // below, which round-trips BOTH committed room-token fixtures — and those now carry `e2ee`, so
    // a serializer that dropped or altered the key on re-encode is caught there against real
    // server output rather than against a literal.

    // ── The meetings list ────────────────────────────────────────────────────

    @Test
    fun `the meetings list is a bare array and covers both summary branches`() {
        // ⛔ A BARE ARRAY, NOT AN ENVELOPE — `NextResponse.json(results)`, like the call log and
        // unlike almost every other district route. Decoded through a list serializer here for
        // exactly the reason the client uses one: there is no `success` flag to read.
        val meetings = json.decodeFromString(
            ListSerializer(MeetingSummary.serializer()),
            fixture("district-meetings.json"),
        )

        assertEquals("the fixture must carry more than one meeting", 2, meetings.size)

        // ⛔ BOTH NULL BRANCHES MUST SURVIVE INTO THE FIXTURE. The Companion writes the minutes when
        // a meeting ENDS, so an in-progress meeting has no summary, no end and no title — and that
        // is the row most likely to be at the top of a live user's list. A regeneration against
        // completed meetings alone would let these be typed non-null, and the row that would then
        // throw in production is the most ordinary one there is.
        assertTrue(
            "fixture must cover a meeting with minutes",
            meetings.any { it.summaryPreview != null },
        )
        assertTrue(
            "fixture must cover a meeting with NO minutes (the in-progress branch)",
            meetings.any { it.summaryPreview == null },
        )
        assertTrue("fixture must cover an unended meeting", meetings.any { it.endedAt == null })
        assertTrue("fixture must cover an untitled meeting", meetings.any { it.title == null })

        // ⚠️ Both statuses, so nothing starts assuming a list is all history.
        assertEquals(setOf("in-progress", "completed"), meetings.map { it.status }.toSet())

        // ⛔ THE PROJECTION, PINNED BY NAME. The list RENAMES as it reshapes — `summary` becomes
        // `summaryPreview` and the `participants` array becomes `participantCount` — so a client
        // that modelled this from the detail route's shape would fail to decode every row.
        val raw = fixture("district-meetings.json")
        assertTrue("the list must publish summaryPreview", raw.contains("summaryPreview"))
        assertTrue("the list must publish participantCount", raw.contains("participantCount"))
        assertFalse("the list must NOT publish the full summary", raw.contains("\"summary\""))
        assertFalse("the list must NOT publish the participants array", raw.contains("\"participants\""))

        // ⛔ THE TRUNCATION. The server slices to 220 characters, so a preview at exactly that
        // length proves the list is a PREVIEW rather than the full minutes under another name — a
        // client rendering it as complete would look correct against a short-summary fixture.
        val completed = meetings.first { it.summaryPreview != null }
        assertEquals("the preview must be the server's 220-character slice", 220, completed.summaryPreview!!.length)

        // ⚠️ The non-array branch of `Array.isArray(participants) ? length : 0` — a null Json
        // column counts as zero rather than omitting the key.
        assertEquals(0, meetings.first { it.summaryPreview == null }.participantCount)
    }

    // ── The meeting detail ───────────────────────────────────────────────────

    @Test
    fun `the detail is the whole row and is NOT a superset of the list row`() {
        val detail =
            json.decodeFromString<MeetingDetail>(fixture("district-meeting-detail.json"))

        // ⛔ FOUR FIELDS THE LIST NEVER PUBLISHES. This is why the client carries two models rather
        // than treating the list as a subset — and the asymmetry runs both ways, since neither
        // `summaryPreview` nor `participantCount` exists here.
        assertTrue("roomSid must be published", !detail.roomSid.isNullOrBlank())
        assertTrue("the transcript must be published", !detail.transcript.isNullOrBlank())
        assertNotNull("actionItems must be published", detail.actionItems)
        assertEquals("ws-contract-test", detail.workspaceId)

        // ⛔ THE FULL MINUTES, NOT THE PREVIEW. If these were ever equal the two routes would have
        // collapsed into one shape and the truncation assertion above would be measuring nothing.
        assertNotNull("the detail must carry the summary", detail.summary)
        assertTrue(
            "the detail summary must exceed the list's 220-character slice",
            detail.summary!!.length > 220,
        )

        // ⚠️ BOTH Json COLUMNS DECODE AS OPAQUE ELEMENTS, deliberately: their element shape belongs
        // to the voice agent rather than to this repo, and a data class here would fail to decode
        // the first time the agent adds a key. Their ARRAY-ness is still worth pinning, since a
        // client iterating them would break if either became an object.
        assertEquals(2, detail.actionItems!!.jsonArray.size)
        assertEquals(2, detail.participants!!.jsonArray.size)
        assertEquals(
            "Ada",
            detail.participants!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `room fixtures survive a round trip in both encodings`() {
        // ⛔ THE VIEWER TOKEN ESPECIALLY. Both of its optional keys are ABSENT, which is the shape
        // the server really produces; a default that swallowed either (an empty string for the
        // path, say) would decode and re-encode to something different, which is what this catches
        // and what no decode assertion above can.
        val verbose = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val terse = Json {
            encodeDefaults = false
            explicitNulls = false
        }

        fun <T> roundTrip(serializer: KSerializer<T>, fixtureName: String) {
            val decoded = json.decodeFromString(serializer, fixture(fixtureName))
            assertEquals(
                "$fixtureName must survive a round trip through an explicit-nulls encoding",
                decoded,
                json.decodeFromString(serializer, verbose.encodeToString(serializer, decoded)),
            )
            assertEquals(
                "$fixtureName must survive a round trip in the server's own omit-defaults shape",
                decoded,
                json.decodeFromString(serializer, terse.encodeToString(serializer, decoded)),
            )
        }

        roundTrip(RoomTokenResponse.serializer(), "district-room-token.json")
        roundTrip(RoomTokenResponse.serializer(), "district-room-token-viewer.json")
        roundTrip(ListSerializer(MeetingSummary.serializer()), "district-meetings.json")
        roundTrip(MeetingDetail.serializer(), "district-meeting-detail.json")
    }

    @Test
    fun `an unmodelled field on a meeting row is rejected rather than ignored`() {
        // ⛔ PROVES THE GUARD GUARDS FOR THIS FILE'S DECODER TOO. The three contract classes now
        // share one `Json` instance, so if this ever passes, the shared decoder has been relaxed
        // and EVERY half of the gate has quietly stopped protecting anything.
        val withExtraField =
            """[{"id":"m1","roomName":"meet_ws_standup","status":"completed",""" +
                """"createdAt":"2026-08-15T00:00:00.000Z","brandNewServerField":"boom"}]"""

        val failure = runCatching {
            json.decodeFromString(ListSerializer(MeetingSummary.serializer()), withExtraField)
        }
        assertTrue(
            "Decoding an unknown key MUST fail. It succeeded, which means ignoreUnknownKeys is " +
                "no longer false and contract drift can now ship silently.",
            failure.isFailure,
        )
    }
}
