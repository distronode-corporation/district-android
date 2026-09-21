package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The messaging WRITE half of the contract gate, generated from the real route handlers.
 *
 * ⛔ A SEPARATE CLASS FROM `KnowledgeContractFixtureTest`, which already carries the messaging READ,
 * for the reason `DeviceContractFixtureTest` states: that class reached detekt's LargeClass ceiling
 * and the healthy answer is another class rather than a raised threshold. The strict decoder and the
 * fixture loader are shared through [ContractFixtures], so the split cannot make one lenient.
 *
 * ⛔ EVERY FIXTURE HERE IS DECODED WITH `ignoreUnknownKeys = false`. A field added server-side fails
 * this file rather than being silently dropped on a phone.
 *
 * ⛔ THESE EXIST BECAUSE FIVE OPERATIONS SHARE ONE PATH AND ONE VERB. `PATCH workspace/messaging` is
 * dispatched on an `action` string in the body, so nothing about the URL distinguishes "make this
 * the default sender" from "delete this account and release its phone numbers". The fixtures are the
 * only committed evidence that the five request types this client sends produce five different
 * responses rather than four of them falling through to the switch's `default` arm, which is the
 * upsert.
 */
class MessagingWriteContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    @Test
    fun `the upsert echoes ids and nothing that could redraw a row`() {
        val response = json.decodeFromString<MessagingAccountSaveResponse>(
            fixture("district-messaging-upsert.json"),
        )

        assertEquals(true, response.success)
        assertEquals("acct-twilio", response.accountId)
        // ⚠️ THE DEFAULT DID NOT MOVE. This edit did not ask to become the default and the workspace
        // already had one, so the route echoes the STORED default rather than the edited account —
        // which is the branch a fixture built on a workspace with one account could never show.
        assertEquals("acct-telnyx", response.defaultAccountId)

        // ⛔ NO LABEL, NO PROVIDER, NO NUMBERS ON THIS RESPONSE, AND THAT IS WHY EVERY SAVE RE-READS.
        // Asserted against the RAW text rather than the DTO, because a decoded object cannot show a
        // key the DTO does not model. If the route ever started echoing the saved account, a client
        // that had started patching its list locally would silently show the label it TYPED rather
        // than the trimmed one that was stored.
        val raw = fixture("district-messaging-upsert.json")
        assertTrue(!raw.contains("label"))
        assertTrue(!raw.contains("phoneNumbers"))
        assertTrue(!raw.contains("providerConfig"))
    }

    @Test
    fun `no credential appears on any messaging write response`() {
        // ⛔ THE STORED CONFIG BEHIND THESE FIXTURES CARRIES AN ACCOUNT SID, AN AUTH TOKEN AND AN
        // API KEY — the generator plants them precisely so a leak would show up here. The write path
        // is the one worth checking twice: unlike the GET, which projects five keys, `handleUpsert`
        // holds the fully encrypted config in memory and could trivially echo it.
        listOf(
            "district-messaging-upsert.json",
            "district-messaging-set-default.json",
            "district-messaging-channel-default.json",
            "district-messaging-delete.json",
            "district-messaging-meta.json",
        ).forEach { name ->
            val raw = fixture(name)
            assertTrue("$name must carry no accountSid", !raw.contains("accountSid"))
            assertTrue("$name must carry no authToken", !raw.contains("authToken"))
            assertTrue("$name must carry no apiKey", !raw.contains("apiKey"))
            assertTrue("$name must carry no ciphertext", !raw.contains("should-never-reach-the-wire"))
        }
    }

    @Test
    fun `setDefault and delete decode with the SAME type, because they answer the same shape`() {
        // ⛔ ONE DTO FOR TWO ACTIONS, AND THIS IS THE EVIDENCE FOR IT. Both answer
        // `{success, defaultAccountId}`; giving them separate types would imply a difference the
        // server does not make, and would be two places for one shape to drift.
        val setDefault = json.decodeFromString<MessagingDefaultResponse>(
            fixture("district-messaging-set-default.json"),
        )
        assertEquals(true, setDefault.success)
        assertEquals("acct-twilio", setDefault.defaultAccountId)

        val deleted = json.decodeFromString<MessagingDefaultResponse>(
            fixture("district-messaging-delete.json"),
        )
        assertEquals(true, deleted.success)
        // ⚠️ THE DEFAULT MOVED WITHOUT BEING ASKED TO. `acct-telnyx` WAS the default and was the
        // account deleted, so the route re-pointed at the one that remained — and this field is the
        // only announcement of that. A client that ignored it would keep showing a default badge on
        // a row that no longer exists.
        assertEquals("acct-twilio", deleted.defaultAccountId)
    }

    @Test
    fun `the channel default is a MERGE, and the fixture would not prove it with one key`() {
        val response = json.decodeFromString<MessagingChannelDefaultResponse>(
            fixture("district-messaging-channel-default.json"),
        )

        assertEquals(true, response.success)
        // ⛔ TWO KEYS. The request set `voice`; `sms` was already stored. A response carrying only
        // the key that was written would be indistinguishable from a route that REPLACED the map,
        // and a client that trusted it would report every other channel as having no override.
        assertEquals(
            mapOf("sms" to "acct-twilio", "voice" to "acct-telnyx"),
            response.channelDefaults,
        )
    }

    @Test
    fun `the meta write echoes nothing, which is why the form cannot pre-fill`() {
        val response =
            json.decodeFromString<MessagingMetaResponse>(fixture("district-messaging-meta.json"))

        assertEquals(true, response.success)
        // ⛔ THE CREATOR CELL NUMBER IS NOWHERE ON THE WIRE — not on this write and not on the
        // messaging GET. Nothing this client can call returns it, so a form field seeded from what
        // the app knows could only ever be blank, which is the shape that saves a blank over a real
        // number. The screen states that rather than pretending otherwise.
        assertTrue(!fixture("district-messaging-meta.json").contains("creatorCellNumber"))
    }

    @Test
    fun `a PASSING credential probe carries the provider's own words`() {
        val response =
            json.decodeFromString<MessagingTestResponse>(fixture("district-messaging-test.json"))

        assertEquals(true, response.success)
        assertNull(response.error)
        assertNotNull(response.details)
        assertEquals("Distronode Contract", response.details!!.friendlyName)
        assertEquals("active", response.details.status)
        // ⚠️ TWILIO'S SHAPE, NOT SINCH'S OR TELNYX'S. Those two answer `{message}` instead, which is
        // why every field on the details type is nullable — a required `friendlyName` would throw on
        // a successful Sinch check.
        assertNull(response.details.message)
    }

    @Test
    fun `a REJECTED credential probe is an HTTP 200 with success false, and that is the answer`() {
        // ⛔ THE ONE ENVELOPE IN THIS CLIENT WHERE `success:false` IS NOT CONTRACT DRIFT. The route
        // catches the carrier's 401 and answers 200 on purpose, so the operator reads "these keys do
        // not authenticate" rather than "the server broke". Any repository applying the usual
        // `rejectedEnvelope` guard here would turn the button's only interesting outcome into "this
        // version of the app does not understand the response", with no retry offered — see
        // `MessagingRepository.testCredentials`, which deliberately skips that guard.
        val response = json.decodeFromString<MessagingTestResponse>(
            fixture("district-messaging-test-rejected.json"),
        )

        assertEquals(false, response.success)
        assertEquals("Authenticate (20003)", response.error)
        // ⚠️ ABSENT, hence null — the failure branch carries no details at all, so the two branches
        // do not share a key set. That is what a strict decoder has to survive.
        assertNull(response.details)
    }

    @Test
    fun `the credential field lists mirror the route's SECRET_FIELDS map exactly`() {
        // ⛔ NOT A FIXTURE, AND IT BELONGS HERE ANYWAY: a key spelled differently from the route's
        // own is not a validation error ANYWHERE. `buildEncryptedProviderConfig` carries an
        // unrecognised key through as a PLAINTEXT identifier, so a misspelled `authToken` would be
        // stored in the clear while the real one was dropped (its `else` arm deletes a secret that
        // arrives blank with nothing stored). Nothing on the wire would look wrong.
        assertEquals(
            listOf("accountSid", "authToken"),
            messagingCredentialFields(MESSAGING_PROVIDER_TWILIO).map { it.key },
        )
        assertEquals(
            listOf("projectId", "keyId", "keySecret", "applicationKey", "applicationSecret"),
            messagingCredentialFields(MESSAGING_PROVIDER_SINCH).map { it.key },
        )
        assertEquals(
            listOf("apiKey"),
            messagingCredentialFields(MESSAGING_PROVIDER_TELNYX).map { it.key },
        )

        // ⚠️ SINCH'S `projectId` IS THE ONE NON-SECRET, and the distinction is load-bearing: a blank
        // SECRET means "keep the stored ciphertext", while a blank plaintext key would be merged in
        // and stored as a blank. The client omits both when empty, which is why either is safe.
        assertEquals(
            listOf(false, true, true, true, true),
            messagingCredentialFields(MESSAGING_PROVIDER_SINCH).map { it.secret },
        )

        // ⛔ EMPTY, NOT A THROW, for a provider this build does not know. A fourth carrier added
        // server-side must not crash the settings screen on an already-installed build.
        assertEquals(emptyList<MessagingCredentialField>(), messagingCredentialFields("vonage"))
    }

    @Test
    fun `every action string is baked into its own request type`() {
        // ⛔ THE ONLY THING SEPARATING A DEFAULT CHANGE FROM A DELETION IS THIS STRING, because both
        // are a PATCH to the same URL. Pinned here so a rename cannot pass review as a cosmetic edit.
        assertEquals("setDefault", MessagingDefaultRequest("ws", "acct-1").action)
        assertEquals("setChannelDefault", MessagingChannelDefaultRequest("ws", "sms", "acct-1").action)
        assertEquals("delete", MessagingDeleteRequest("ws", "acct-1").action)
        assertEquals("meta", MessagingMetaRequest("ws", "+14165550100").action)

        // ⚠️ AND THE UPSERT HAS NO `action` FIELD AT ALL. The route reaches `handleUpsert` through
        // the switch's `default` arm, so any value here could only route it somewhere else.
        val upsert = MessagingAccountRequest(
            workspaceId = "ws",
            activeProvider = MESSAGING_PROVIDER_TWILIO,
            credentialSource = MESSAGING_SOURCE_BYOK,
            providerConfig = MessagingProviderConfig(),
        )
        assertNull(upsert.accountId)
        assertEquals(MESSAGING_PROVIDER_TWILIO, upsert.activeProvider)

        // ⚠️ The three the route's `PROVIDERS` allowlist accepts, and the two `isCredentialSource`
        // does. Anything else is a 400 the client should never send.
        assertEquals(listOf("twilio", "sinch", "telnyx"), MESSAGING_PROVIDERS)
        assertEquals(listOf("byok", "managed"), MESSAGING_CREDENTIAL_SOURCES)
        assertEquals(listOf("sms", "voice", "whatsapp"), MESSAGING_CHANNELS)
    }
}
