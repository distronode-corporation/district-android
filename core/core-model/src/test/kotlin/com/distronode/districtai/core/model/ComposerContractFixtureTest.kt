package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's half of the two-sided contract: attachments, persisted drafts and AI generation.
 *
 * ⛔ A SEPARATE CLASS FROM [ContractFixtureTest] FOR THE REASON [ContractFixtures] STATES — that one
 * reached detekt's LargeClass ceiling, and the healthy answer is more classes rather than a raised
 * threshold. Both share the one strict decoder so neither can drift lenient.
 *
 * ⛔ THE THING THIS FILE EXISTS TO PIN IS THAT `draft` MEANS TWO DIFFERENT TYPES ON TWO ROUTES ONE
 * LETTER APART. `messages/drafts` (plural) answers `draft` as an OBJECT; `messages/draft` (singular)
 * answers `draft` as a STRING and costs a Vertex generation to ask. A client that reused one DTO
 * across both would decode one of them as an empty envelope — no error, no field, and a composer
 * that silently fills with nothing.
 */
class ComposerContractFixtureTest {

    private val json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    /** The key set of a send fixture's echoed row, read from the raw JSON. */
    private fun messageKeys(name: String): Set<String> {
        val envelope = json.parseToJsonElement(fixture(name)) as JsonObject
        return (envelope["message"] as JsonObject).keys
    }

    @Test
    fun `a media upload decodes the url the send route will accept`() {
        val response = json.decodeFromString<MediaUploadResponse>(fixture("district-media-upload.json"))

        assertTrue(response.success)
        // ⚠️ `assertNotNull` RETURNS Unit IN JUNIT 4, so it cannot be used to unwrap. A null here
        // fails with a message either way; `requireNotNull` is what actually narrows the type.
        val media = requireNotNull(response.media) { "the envelope must carry media" }
        assertEquals("image/png", media.mimeType)
        assertTrue("sizeBytes must be the real byte length", media.sizeBytes > 0)
        // ⛔ ABSOLUTE AND https. A carrier fetches this exact string to deliver the MMS, and
        // `messages/send` rejects anything that is not `https://…` outright. A relative path would
        // pass a naive client and fail at the provider.
        assertTrue("the media url must be absolute https", media.url.startsWith("https://"))
        assertTrue("the media url must be the anonymous capability path", "/api/media/" in media.url)
    }

    /**
     * ⛔ THE BYTES MUST NOT BE IN THE RESPONSE, AND THIS IS THE ASSERTION THAT KEEPS THAT TRUE. The
     * route's `select` omits the `data` column; a future edit that dropped the select would put up
     * to five megabytes of base64 into a JSON body the phone parses on every attach. The strict
     * decoder would catch it as an unknown key, but only if something actually decodes the
     * fixture — so this checks the raw text too, which cannot be satisfied by a DTO change.
     */
    @Test
    fun `a media upload never carries the stored bytes`() {
        val raw = json.parseToJsonElement(fixture("district-media-upload.json")) as JsonObject
        val media = raw["media"] as JsonObject
        assertEquals(setOf("id", "mimeType", "sizeBytes", "url"), media.keys)
        assertFalse("the bytes column must never be published", "data" in media.keys)
    }

    @Test
    fun `a saved draft decodes with its attachments`() {
        val response = json.decodeFromString<DraftResponse>(fixture("district-draft.json"))

        assertTrue(response.success)
        val draft = requireNotNull(response.draft) { "a saved draft must decode" }
        assertTrue("threadKey must be a contact- or addr-keyed identity", draft.threadKey.startsWith("contact:"))
        assertTrue("a stored draft always has text — a blank one is a 400", draft.body.isNotBlank())
        assertEquals("Re: Thursday appointment", draft.subject)
        assertEquals(1, draft.mediaUrls.size)
        // ⚠️ The timestamp is what a second device would compare; it must survive as a string
        // rather than being reformatted. See TimelineEvent.timestamp for the same rule.
        assertTrue(draft.updatedAt.isNotBlank())
    }

    /**
     * ⛔ THE NULL BRANCH, WHICH IS THE ORDINARY CASE RATHER THAN THE EDGE ONE. Almost every thread
     * has no draft. The server answers 200 with `draft: null` instead of 404 so the composer's
     * normal open path is not an error in every log — and a DTO that typed `draft` non-nullable
     * would throw on the majority of thread opens.
     */
    @Test
    fun `a thread with no draft decodes as a null draft, not a failure`() {
        val response = json.decodeFromString<DraftResponse>(fixture("district-draft-null.json"))

        assertTrue("null draft is still a SUCCESS", response.success)
        assertNull(response.draft)
        assertNull("and it is not an error", response.error)
    }

    /**
     * ⛔ A DIFFERENT KEY AND A DIFFERENT TYPE ON THE SAME PATH, decided by whether `threadKey` was
     * sent. Pointing [DraftResponse] at the list fixture decodes to `draft = null` — a silent
     * "you have no draft" for a request that returned several.
     */
    @Test
    fun `the drafts list decodes on its own key, and covers the sparse row`() {
        val response = json.decodeFromString<DraftListResponse>(fixture("district-drafts-list.json"))

        assertTrue(response.success)
        assertEquals(2, response.drafts.size)

        // Newest first. The list is not paged; this is ordering, not a cursor.
        val (newest, oldest) = response.drafts
        assertTrue("the list must be newest-first", newest.updatedAt > oldest.updatedAt)

        // ⚠️ THE SPARSE ROW IS THE POINT OF THE SECOND ENTRY. Its subject is a real null and its
        // `mediaUrls` column was null server-side — normalised to `[]` on the wire. Without it the
        // fixture would only ever exercise the populated branch, and `subject: String?` would meet
        // its first actual null on a phone.
        assertNull(oldest.subject)
        assertTrue(oldest.mediaUrls.isEmpty())
        assertTrue("an address-keyed thread carries the normalized address", oldest.threadKey.startsWith("addr:"))
    }

    /**
     * ⛔ `mediaUrls` IS ALWAYS AN ARRAY ON THE WIRE, NEVER OMITTED AND NEVER NULL — asserted on the
     * RAW JSON rather than through the DTO, because the DTO's default would happily paper over an
     * omission and report `[]` either way. The server normalises its nullable `Json?` column
     * precisely so a strict decoder never has to branch on absent-versus-empty; this is what
     * detects the normalisation being dropped.
     */
    @Test
    fun `every draft on the wire carries mediaUrls as an array`() {
        val raw = json.parseToJsonElement(fixture("district-drafts-list.json")) as JsonObject
        val drafts = raw["drafts"]!!
        val entries = (drafts as kotlinx.serialization.json.JsonArray).map { it as JsonObject }
        assertEquals(2, entries.size)
        entries.forEach { entry ->
            assertTrue(
                "mediaUrls must be PRESENT on every row, including the one whose column is null",
                "mediaUrls" in entry.keys,
            )
            assertTrue(
                "mediaUrls must be an array, never null",
                entry["mediaUrls"] is kotlinx.serialization.json.JsonArray,
            )
        }
    }

    /**
     * ⚠️ PINNED SEPARATELY FROM THE GET even though both answer `{success, draft}`. They are the
     * same shape today and nothing enforces that; the client reuses one DTO on the strength of it,
     * so a fixture each turns a divergence into a red test rather than a decode failure in the field.
     */
    @Test
    fun `an upserted draft decodes with the same type the read returns`() {
        val put = json.decodeFromString<DraftResponse>(fixture("district-draft-put.json"))
        val get = json.decodeFromString<DraftResponse>(fixture("district-draft.json"))

        assertTrue(put.success)
        assertEquals(
            "the write's echo and the read must stay the same shape",
            get.draft,
            put.draft,
        )
    }

    /**
     * ⚠️ NO COUNT, DELIBERATELY. The delete is idempotent — the caller's goal state is "no draft",
     * which is already true when there never was one — so publishing a count would invite a client
     * to treat zero as a failure and re-issue a write that already succeeded.
     */
    @Test
    fun `a draft delete decodes as a bare success`() {
        val raw = json.parseToJsonElement(fixture("district-draft-delete.json")) as JsonObject
        assertEquals(setOf("success"), raw.keys)

        val response = json.decodeFromString<DraftDeleteResponse>(fixture("district-draft-delete.json"))
        assertTrue(response.success)
    }

    /**
     * ⛔ THE BILLED ONE. `messages/draft` (singular) runs a Vertex generation per request and
     * answers `draft` as a STRING, where `messages/drafts` (plural) answers it as an OBJECT. The
     * two fixtures are decoded side by side here so the difference is a checked fact rather than
     * something a reader has to notice.
     */
    @Test
    fun `an AI draft decodes as a string on the same key the stored draft uses as an object`() {
        val generated = json.decodeFromString<AiDraftResponse>(fixture("district-ai-draft.json"))

        assertTrue(generated.success)
        assertTrue("the generation must carry text", generated.draft.isNotBlank())

        // The same key name, an object rather than a string, one route apart.
        val storedRaw = json.parseToJsonElement(fixture("district-draft.json")) as JsonObject
        val generatedRaw = json.parseToJsonElement(fixture("district-ai-draft.json")) as JsonObject
        assertTrue("the persisted draft is an OBJECT", storedRaw["draft"] is JsonObject)
        assertNotNull(
            "the generated draft is a STRING",
            (generatedRaw["draft"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
    }

    /**
     * ⛔ AN MMS SEND ECHOES NO `mediaUrls`, AND THAT IS THE FINDING THIS FIXTURE RECORDS. The route
     * writes them to the message row but the thirteen-field echo it returns does not include them,
     * so a client that expected its own attachments back would render a sent message with none.
     * The key sets of the media and text-only fixtures are compared directly, because "identical"
     * is the claim and eyeballing two lists is how that claim goes stale.
     */
    @Test
    fun `a send with attachments decodes into the same row type as a text-only send`() {
        val withMedia = json.decodeFromString<SendMessageResponse>(
            fixture("district-message-send-media.json"),
        )
        val textOnly = json.decodeFromString<SendMessageResponse>(
            fixture("district-message-send.json"),
        )

        val sent = requireNotNull(withMedia.message) { "the send must echo its row" }
        assertEquals("sms", sent.type)
        assertEquals("queued", sent.status)
        assertEquals("twilio", sent.provider)
        // A body is present because `messages/send` REQUIRES one — its first guard is
        // `!workspaceId || !to || !body` and it runs before `mediaUrls` is parsed, so an image
        // with no caption is a 400 rather than a message.
        assertTrue("an MMS still carries a body", sent.body.isNotBlank())

        val mediaKeys = messageKeys("district-message-send-media.json")
        val plainKeys = messageKeys("district-message-send.json")
        assertEquals("both branches echo the same fields", plainKeys, mediaKeys)
        assertFalse("and neither returns the attachments", "mediaUrls" in mediaKeys)

        assertEquals(textOnly.message?.provider, sent.provider)
    }

    /**
     * ⛔ WHAT THE CLIENT SENDS, NOT WHAT IT RECEIVES, AND IT IS THE HALF THE FIXTURES CANNOT COVER.
     * `messages/send` and `messages/drafts` both read their fields off a bare `req.json()` with no
     * schema, so a field encoded in the wrong form is not rejected — it is silently absent, and the
     * route then answers 400 or, worse, stores something different from what was meant.
     *
     * ⚠️ kotlinx OMITS A DEFAULT-VALUED PROPERTY. That is CORRECT for `mediaUrls` (the routes map an
     * absent value to `[]`, so omission and an empty array mean the same thing) and was WRONG for
     * `channel`, whose server default could drift away from the client's — which is why that one
     * carries no default at all. This test pins both halves of that asymmetry.
     */
    @Test
    fun `an attachment-bearing request body carries mediaUrls, and an empty one omits it`() {
        val encoder = Json { explicitNulls = false }

        val withMedia = encoder.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(
                workspaceId = "ws-1",
                to = "+14165550142",
                body = "Here is the roof.",
                channel = "sms",
                mediaUrls = listOf("https://www.distronode.test/api/media/m1"),
            ),
        )
        assertTrue("a populated list must reach the wire", "mediaUrls" in withMedia)

        val withoutMedia = encoder.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(
                workspaceId = "ws-1",
                to = "+14165550142",
                body = "No pictures.",
                channel = "sms",
            ),
        )
        assertFalse("an empty list is omitted, which the route reads as []", "mediaUrls" in withoutMedia)
        assertTrue("channel is ALWAYS explicit — it has no default", "channel" in withoutMedia)

        val draft = encoder.encodeToString(
            DraftSaveRequest.serializer(),
            DraftSaveRequest(
                workspaceId = "ws-1",
                threadKey = "contact:c1",
                body = "half a sentence",
            ),
        )
        assertFalse("an absent subject is OMITTED, not null", "subject" in draft)
        assertFalse("and so is an empty mediaUrls", "mediaUrls" in draft)
    }
}
