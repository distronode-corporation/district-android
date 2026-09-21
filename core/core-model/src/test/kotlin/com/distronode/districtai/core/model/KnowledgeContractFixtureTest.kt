package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The knowledge-base and messaging halves of the contract gate.
 *
 * ⛔ A SEPARATE CLASS FROM `WorkspaceConfigContractFixtureTest`, for the reason
 * `DeviceContractFixtureTest` states: that class reached detekt's LargeClass ceiling and the
 * healthy answer is another class rather than a raised threshold. The strict decoder and the
 * fixture loader are shared through [ContractFixtures], so the split cannot make one lenient.
 *
 * ⛔ EVERY FIXTURE HERE IS DECODED WITH `ignoreUnknownKeys = false`. A field added server-side
 * fails this file rather than being silently dropped on a phone.
 */
class KnowledgeContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    // ── Documents ────────────────────────────────────────────────────────────

    @Test
    fun `the document list decodes, with both status branches`() {
        val response = json.decodeFromString<KnowledgeListResponse>(fixture("district-knowledge.json"))

        assertEquals(true, response.success)
        assertEquals(2, response.documents.size)

        // ⛔ TWO DIFFERENT STATUSES, AND `status` IS A PLAIN STRING COLUMN RATHER THAN AN ENUM. The
        // ingest route writes `ready`; older rows carry other values. A DTO that typed this as a
        // sealed vocabulary would throw on the second row, and a screen that switched on it
        // exhaustively would render nothing for a document that genuinely exists.
        assertEquals(listOf("ready", "processing"), response.documents.map { it.status })

        val ready = response.documents[0]
        assertEquals("Refund policy", ready.title)
        // ⚠️ NULL FOR A PASTED DOCUMENT. Most documents have no source URL, so a non-nullable field
        // would throw on the ordinary row.
        assertNull(ready.sourceUrl)
        assertEquals(4, ready.chunkCount)
        // ⚠️ An ISO-8601 STRING. This module owns no date parsing.
        assertEquals("2026-08-15T14:30:00.000Z", ready.createdAt)

        assertEquals("https://contract.test/service-area", response.documents[1].sourceUrl)
    }

    @Test
    fun `the create response decodes even though it carries one field FEWER than a list row`() {
        // ⛔ THE ASYMMETRY THIS TEST EXISTS FOR. The POST's `select` omits `sourceUrl` while the
        // GET's includes it, so the same type has to decode both — which it can only do because
        // every field defaults. A client that required `sourceUrl` would throw on the response to a
        // SUCCESSFUL upload, which is about the worst place to fail.
        val response =
            json.decodeFromString<KnowledgeCreateResponse>(fixture("district-knowledge-create.json"))

        assertEquals(true, response.success)
        assertNotNull("the create must echo the row it made", response.document)
        val document = response.document!!
        assertEquals("doc_contract_created", document.id)
        assertEquals(2, document.chunkCount)
        // ⚠️ ABSENT, hence null — and this is exactly why the list is re-read rather than having
        // this row appended to it: appending would show a document whose source vanished.
        assertNull(document.sourceUrl)

        assertTrue(
            "the create fixture must keep omitting sourceUrl, or this test proves nothing",
            !fixture("district-knowledge-create.json").contains("sourceUrl"),
        )
    }

    @Test
    fun `the delete response says nothing about whether a row was removed`() {
        // ⛔ `{success:true}` WHETHER OR NOT ANYTHING MATCHED. The route's `deleteMany` is scoped
        // `{id, workspaceId}` and its count is never read, so another tenant's id answers
        // identically — deliberate non-confirmation, and the reason the client re-reads the list.
        val response =
            json.decodeFromString<KnowledgeDeleteResponse>(fixture("district-knowledge-delete.json"))

        assertEquals(true, response.success)
    }

    // ── The mode ─────────────────────────────────────────────────────────────

    @Test
    fun `the mode is a TOP-LEVEL key and decodes its linked branch`() {
        // ⛔ THE ROUTE SPREADS THE CONFIG INTO THE ENVELOPE (`{success:true, ...config}`). A DTO
        // shaped `{success, config:{mode}}` would decode to a null mode and the screen would render
        // "internal" — telling an operator their questions stay in region when they do not.
        val response =
            json.decodeFromString<KnowledgeModeResponse>(fixture("district-knowledge-mode.json"))

        assertEquals(true, response.success)
        assertEquals(KB_MODE_LINKED, response.mode)
    }

    @Test
    fun `the mode PATCH echoes what was stored, which is why it needs no re-read`() {
        val response = json.decodeFromString<KnowledgeModeResponse>(
            fixture("district-knowledge-mode-patch.json"),
        )

        assertEquals(true, response.success)
        assertEquals(KB_MODE_INTERNAL, response.mode)
    }

    @Test
    fun `an unknown mode decodes rather than throwing`() {
        // ⛔ A STRING, NOT AN ENUM, AND THIS IS THE REASON. A third mode added server-side must not
        // make an installed build fail to read its own settings screen — it renders as itself and
        // is left alone. The route's own `z.enum` is what stops this client SENDING one.
        val response =
            json.decodeFromString<KnowledgeModeResponse>("""{"success":true,"mode":"future"}""")

        assertEquals("future", response.mode)
        assertEquals(listOf(KB_MODE_INTERNAL, KB_MODE_LINKED), KB_MODES)
        assertEquals(KB_MODE_INTERNAL, DEFAULT_KB_MODE)
    }

    // ── Messaging ────────────────────────────────────────────────────────────

    @Test
    fun `the messaging read decodes and carries no credential`() {
        val response = json.decodeFromString<MessagingResponse>(fixture("district-messaging.json"))
        val raw = fixture("district-messaging.json")

        assertEquals(true, response.success)
        assertEquals(2, response.accounts.size)
        assertEquals("acct-twilio", response.accounts[0].id)
        assertEquals("byok", response.accounts[0].credentialSource)
        assertEquals(listOf("+14165550111", "+14165550112"), response.accounts[0].phoneNumbers)

        // ⛔ THE STORED CONFIG BEHIND THIS FIXTURE CARRIES AN ACCOUNT SID, AN AUTH TOKEN AND AN API
        // KEY. None of them may be on the wire. Asserted against the RAW text rather than the DTO,
        // because a decoded object could not show a key the DTO does not model.
        assertTrue(!raw.contains("authToken"))
        assertTrue(!raw.contains("accountSid"))
        assertTrue(!raw.contains("apiKey"))

        // ⛔ THE PLATFORM ACCOUNT IS ITS OWN FIELD WITH NO ID. `resolveSendingContext` rejects a
        // sender id that no entry in `accounts` owns, so a synthetic entry would render a pickable
        // sender whose every send fails.
        val managed = response.managedAccount
        assertNotNull(managed)
        assertEquals("twilio", managed!!.provider)
        assertEquals(listOf("+14165550190"), managed.phoneNumbers)

        // ⚠️ RESOLVED, not "the first account" — the stored default names the second.
        assertEquals("acct-telnyx", response.defaultAccountId)
        assertEquals(mapOf("sms" to "acct-twilio"), response.channelDefaults)
    }

    @Test
    fun `a workspace with no platform numbers decodes with a NULL managedAccount`() {
        // ⛔ TWO BRANCHES IN ONE FIXTURE, AND NEITHER IS AN EDGE CASE. `managedAccount` is null for
        // most workspaces, and `defaultAccountId` is ABSENT rather than null when there are no
        // accounts (`effectiveDefaultId` returns undefined and JSON.stringify drops the key). So
        // the two messaging fixtures do not even share a key set — which is what a strict decoder
        // has to survive, and why this is a second fixture rather than an assertion on the first.
        val response =
            json.decodeFromString<MessagingResponse>(fixture("district-messaging-unmanaged.json"))

        assertEquals(true, response.success)
        assertEquals(emptyList<MessagingAccount>(), response.accounts)
        assertNull(response.managedAccount)
        assertNull(response.defaultAccountId)
        assertEquals(emptyMap<String, String>(), response.channelDefaults)
    }
}
