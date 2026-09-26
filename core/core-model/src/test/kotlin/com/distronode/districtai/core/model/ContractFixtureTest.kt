package com.distronode.districtai.core.model

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DTOs in this module against fixtures generated from the REAL server route
 * handlers.
 *
 * ⛔ WHY THIS EXISTS. This repo has no OpenAPI spec and no generated client — zod is used
 * inline in 30 of 215 route files and nothing emits a schema. So the only thing that can
 * catch "the server renamed a field" is a committed sample of the server's actual output,
 * checked against the types the app parses it with.
 *
 * ⛔ `ignoreUnknownKeys = false` IS THE WHOLE MECHANISM. With the default `true`, a new
 * server field would be silently dropped and this test would keep passing while the app
 * quietly ignored data it was supposed to show. Do not relax it to make a failure go
 * away — the failure IS the signal. The fix is to add the field to the DTO.
 *
 * The two-sided break, by design:
 *   1. Server shape changes -> the website's vitest suite fails ("fixture is stale").
 *   2. Fixture regenerated + committed -> THIS test fails until the DTO is updated.
 * Neither half alone is sufficient; together they make a rename impossible to ship
 * silently.
 */
class ContractFixtureTest {

    /**
     * ⚠️ THE STRICT DECODER AND THE FIXTURE LOADER LIVE IN [ContractFixtures] so this class and
     * [DeviceContractFixtureTest] cannot disagree about either. A second copy of the `Json` block
     * is a second chance for one of them to end up lenient, which would silently retire the whole
     * gate on whichever endpoints it covered.
     */
    private val json = ContractFixtures.json

    private val contractsDir: File get() = ContractFixtures.dir

    private fun fixture(name: String): String = ContractFixtures.read(name)

    /**
     * ⛔ GUARD AGAINST VERIFYING NOTHING. An empty or missing contracts directory would
     * make every test below trivially skip-shaped, and a green build that checked zero
     * fixtures is indistinguishable from a green build that checked all of them.
     *
     * This is not hypothetical: a blanket `*.json` ignore rule without an explicit negation
     * for the contracts directory means CI checks out an empty directory, and this assertion
     * is what turns that into a red build instead of a silent pass.
     *
     * ⚠️ Do not write a glob like that rule's literal text in a KDoc here: Kotlin block
     * comments NEST, so a slash-star sequence inside one opens a nested comment and the
     * file stops compiling with a misleading "Missing '}'" several lines earlier.
     */
    @Test
    fun `contracts directory contains fixtures`() {
        assertTrue(
            "Contracts directory ${contractsDir.absolutePath} does not exist.",
            contractsDir.isDirectory,
        )
        val fixtures = contractsDir.listFiles { f: File -> f.extension == "json" }?.toList().orEmpty()
        assertTrue(
            "No .json fixtures found in ${contractsDir.absolutePath}. Either they were never " +
                "generated, or a .gitignore rule excludes them and CI checked out an empty " +
                "directory.",
            fixtures.isNotEmpty(),
        )
        println("contract fixtures found: ${fixtures.map { it.name }.sorted()}")
    }

    @Test
    fun `district calls decodes as a bare array`() {
        val raw = fixture("district-calls.json")
        // Decoded as a LIST, not an envelope — see the ⛔ on CallSummary.
        val calls = json.decodeFromString<List<CallSummary>>(raw)

        assertTrue("fixture should contain at least one call", calls.isNotEmpty())

        // Assert the fixture actually exercises the awkward parts of the shape, so a
        // future regeneration against thinner data cannot quietly reduce coverage.
        assertTrue(
            "fixture must cover a row WITH a followUp object",
            calls.any { it.followUp != null },
        )
        assertTrue(
            "fixture must cover a row WITHOUT a followUp (the null branch)",
            calls.any { it.followUp == null },
        )
        assertTrue(
            "fixture must cover a null recordingUrl",
            calls.any { it.recordingUrl == null },
        )
        assertTrue(
            "fixture must cover a 'missed' type, which the handler derives from status " +
                "rather than direction",
            calls.any { it.type == "missed" },
        )
        // ⛔ REGRESSION GUARD. `analysis` is a Prisma Json? column and arrives as an OBJECT.
        // Every seed row originally had it null, so the fixture never exercised it and the DTO
        // typed it as String? — which would have thrown on any real call carrying an analysis
        // blob. If this assertion ever fails, the fixture has stopped covering the field and the
        // same blind spot is back.
        assertTrue(
            "fixture must cover a POPULATED analysis object, not just nulls",
            calls.any { it.analysis != null },
        )
        val analysis = calls.firstNotNullOf { it.analysis }
        assertTrue(
            "the analysis object should decode its documented fields",
            analysis.keyPoints.isNotEmpty() && analysis.topics.isNotEmpty(),
        )
    }

    @Test
    fun `workspace list decodes and covers every role`() {
        val response = json.decodeFromString<WorkspaceListResponse>(fixture("district-workspace-list.json"))

        assertEquals(true, response.success)
        assertTrue("fixture should list workspaces", response.workspaces.isNotEmpty())

        // ⛔ ALL THREE ROLES MUST BE PRESENT. `role` is a plain String column server-side with
        // no enum and no TS union, and WorkspaceRole.fromWire fails closed on anything else —
        // a fixture covering only "client" would never exercise that mapping, and a viewer
        // being silently granted mutation controls is the failure it prevents.
        val roles = response.workspaces.mapNotNull { WorkspaceRole.fromWire(it.role) }.toSet()
        assertEquals(
            "fixture must cover agency, client and viewer",
            setOf(WorkspaceRole.AGENCY, WorkspaceRole.CLIENT, WorkspaceRole.VIEWER),
            roles,
        )
        assertTrue(
            "every role in the fixture must be one this client understands",
            response.workspaces.all { WorkspaceRole.fromWire(it.role) != null },
        )

        // A null tier has to survive as null rather than becoming a display string — a
        // different endpoint substitutes a capitalised "Free" and this one deliberately
        // does not.
        assertTrue(
            "fixture must cover a null subscriptionTier",
            response.workspaces.any { it.subscriptionTier == null },
        )
        // ⛔ AND A MIXED-CASE ONE. The column is not normalised on write: a real database holds
        // "VoicePro", not "voicepro". A fixture carrying only lowercase
        // values would let a case-sensitive comparison pass here and silently never match in
        // production, so the fixture is required to keep exercising the awkward case.
        assertTrue(
            "fixture must cover a MIXED-CASE subscriptionTier, because the database has them",
            response.workspaces.any { it.subscriptionTier?.any(Char::isUpperCase) == true },
        )
        // More than one region, so nothing starts assuming a single-region account.
        assertTrue(
            "fixture should span more than one region",
            response.workspaces.map { it.region }.toSet().size > 1,
        )
        // The withheld-for-billing count, which is the only thing distinguishing "lapsed
        // subscription" from "no workspaces at all".
        assertTrue("fixture must exercise inactiveCount", response.inactiveCount > 0)
    }

    // ⚠️ The `district-workspace-list-degraded.json` error envelope is pinned in core-network
    // instead (ApiErrorEnvelopeContractTest), because the type that must decode it —
    // ApiErrorEnvelope — lives there alongside the mapping that consumes it, and core-model
    // cannot depend on core-network. A test-only copy of that shape here would pin a
    // duplicate rather than the thing that actually ships.

    @Test
    fun `overview decodes with the same call shape as the calls feed`() {
        val overview = json.decodeFromString<OverviewResponse>(fixture("district-overview.json"))

        assertEquals(true, overview.success)
        assertTrue("workspaceId should be echoed", !overview.workspaceId.isNullOrBlank())
        assertTrue("role should be parseable", WorkspaceRole.fromWire(overview.role) != null)

        // ⛔ THE LABEL IS SERVER-SUPPLIED AND MUST NOT BE RECOMPUTED HERE. Two duration formats
        // ship in this product and they disagree on the same input: the tile omits a zero
        // minutes component ("45s"), a call row always emits one ("0m 45s"). Asserting the
        // fixture carries a non-trivial label keeps the field exercised.
        assertTrue("avgDurationLabel should be populated", overview.avgDurationLabel.isNotBlank())
        assertTrue("metrics should be populated", overview.metrics.totalCalls > 0)

        // ⛔ ONE DTO SERVES BOTH SURFACES. The website's contract suite asserts these two
        // responses are byte-equal; this is the Kotlin half — the same CallSummary must decode
        // both, so a divergence shows up here rather than as a parse failure on a phone.
        val calls = json.decodeFromString<List<CallSummary>>(fixture("district-calls.json"))
        assertEquals(
            "overview.recentCalls must decode identically to the calls feed",
            calls,
            overview.recentCalls,
        )
    }

    @Test
    fun `contacts list decodes and covers the populated and sparse rows`() {
        val response = json.decodeFromString<ContactListResponse>(fixture("district-contacts.json"))

        assertEquals(true, response.success)
        assertTrue("fixture should list contacts", response.contacts.size >= 2)
        // ⚠️ A real total, which the calls feed cannot supply — paging here knows its end.
        assertTrue("total should be populated", response.total > 0)
        assertTrue("the applied limit should be echoed", response.limit > 0)

        val populated = response.contacts.first { it.socialHandles != null }
        assertTrue("company should decode its documented keys", populated.company?.name != null)
        assertTrue("intelligence should decode as an object", populated.intelligence != null)
        assertTrue("visualMemory should be present on the populated row", populated.visualMemory != null)

        // ⛔ THE SPARSE ROW IS THE POINT. Every optional column null, INCLUDING a null phoneNumber —
        // contacts are email-first, so a phone-less contact is legal and any number of them coexist.
        val sparse = response.contacts.first { it.phoneNumber == null }
        assertNull("a phone-less contact must decode", sparse.phoneNumber)
        assertNull(sparse.socialHandles)
        assertNull(sparse.company)
        assertNull(sparse.lastUpdated)

        // ⛔ NULL dgiStatus IS NOT "pending". clear-intel resets it to NULL so nothing re-crawls, so
        // null must read as "no dossier" and be offerable rather than showing a spinner forever.
        assertNull("the sparse row should have no dossier state", sparse.dgiStatus)
        assertFalse("null must not read as in-progress", sparse.dgiInProgress)
        assertTrue("null must be offerable for enrichment", sparse.dgiOfferable)
    }

    @Test
    fun `contact detail decodes identically to its list row`() {
        val detail = json.decodeFromString<ContactDetailResponse>(
            fixture("district-contact-detail.json"),
        )
        val list = json.decodeFromString<ContactListResponse>(fixture("district-contacts.json"))

        assertEquals(true, detail.success)
        val contact = detail.contact
        assertTrue("detail must carry a contact", contact != null)

        // ⛔ ONE DTO FOR BOTH SURFACES. Both routes return the RAW Prisma row; the mapped server
        // helper would have dropped dgiStatus and forced a second DTO with different nullability.
        assertEquals(
            "detail.contact must decode identically to its list row",
            list.contacts.first { it.id == contact!!.id },
            contact,
        )

        // The derived block rides BESIDE the contact, not inside it.
        assertEquals("ON", detail.phoneIntel?.region?.code)
        assertEquals("+1 416 555 1234", detail.phoneIntel?.internationalFormat)
    }

    @Test
    fun `calls feed carries hasTranscript and phoneIntel, and never the transcript text`() {
        val calls = json.decodeFromString<List<CallSummary>>(fixture("district-calls.json"))
        val byId = calls.associateBy { it.id }
        val answered = byId.getValue("call_contract_answered")
        val missed = byId.getValue("call_contract_missed")
        val outbound = byId.getValue("call_contract_outbound")

        // ⛔ The feed carries no transcript text; the flag is what says the transcript route has one.
        // The key itself stays, empty, for builds that still require it.
        assertTrue("the answered call has a transcript to fetch", answered.hasTranscript)
        assertEquals("the feed must never carry transcript text", "", answered.transcript)
        assertFalse("the missed call has none", missed.hasTranscript)

        // Both branches of the carrier half: a stored lookup on the answered caller, none on the
        // missed one. Metadata never fills these, so null is the honest "nobody asked".
        val intel = answered.phoneIntel
        assertTrue("the answered call should carry phoneIntel", intel != null)
        assertEquals("CA", intel!!.country)
        assertEquals("ON", intel.region?.code)
        assertEquals("mobile", intel.lineType)
        assertEquals("Rogers", intel.carrier)
        assertNull("no stored lookup means no line type", missed.phoneIntel?.lineType)
        assertNull("no stored lookup means no carrier", missed.phoneIntel?.carrier)
        assertTrue("the missed call still has parsed intel", missed.phoneIntel?.nationalFormat != null)

        // An outbound row with no recorded callee describes nobody rather than our own trunk.
        assertNull("outbound with no `to` must carry no phoneIntel", outbound.phoneIntel)
    }

    @Test
    fun `call detail decodes with the same call shape as the feed`() {
        val detail = json.decodeFromString<CallDetailResponse>(fixture("district-call-detail.json"))

        assertEquals(true, detail.success)
        val call = detail.call
        assertTrue("detail must carry a call", call != null)

        // ⛔ ONE DTO FOR THREE SURFACES. The detail route reuses the server's own feed mapping
        // rather than inventing a richer shape, so the same CallSummary decodes the feed, the
        // overview's recent activity, and this. The website's contract suite asserts the two
        // responses are equal; this is the Kotlin half, and a divergence shows up here rather than
        // as a parse failure on a phone.
        val feed = json.decodeFromString<List<CallSummary>>(fixture("district-calls.json"))
        assertEquals(
            "detail.call must decode identically to its feed row",
            feed.first { it.id == call!!.id },
            call,
        )

        // The fixture should be the fully-populated row, so the awkward parts of the shape stay
        // covered rather than a regeneration quietly reducing it to nulls.
        assertTrue("detail fixture should cover a followUp object", call!!.followUp != null)
        assertTrue("detail fixture should cover a populated analysis", call.analysis != null)
    }

    @Test
    fun `call transcript decodes as an envelope`() {
        val response = json.decodeFromString<CallTranscriptResponse>(
            fixture("district-call-transcript.json"),
        )

        assertEquals(true, response.success)
        assertTrue("fixture should carry a transcript", response.hasTranscript)
        // ⚠️ The handler sends "" for a call with no transcript, never null, so emptiness is the
        // "nothing to show" test. Asserting the type here keeps a future `transcript: null` from
        // being absorbed silently by a nullable DTO field.
        assertTrue("transcript must be a string", response.transcript.isNotEmpty())
    }

    @Suppress("DEPRECATION")
    @Test
    fun `conversations decode with one customer's channels folded into one thread`() {
        val response = json.decodeFromString<ConversationsResponse>(fixture("district-conversations.json"))

        assertEquals(true, response.success)

        // ⛔ THE FOLD IS THE CONTRACT. The fixture holds four messages from two customers, one of
        // whom was reached at BOTH a phone number and an email address. Two threads, not three.
        // If this reads 3, the server regressed to grouping by counterpart address and the Inbox
        // is showing the same person twice — the bug `threadKey` exists to prevent.
        assertEquals("four messages must group into two threads", 2, response.conversations.size)

        val folded = response.conversations.first { it.contactId != null }
        assertEquals("contact:contact_contract_1", folded.threadKey)
        assertEquals("Contract Test Caller", folded.displayName)
        assertEquals(listOf("email", "sms"), folded.channels)
        // ⚠️ UNREAD SPANS CHANNELS: one unread email plus one unread SMS. A count derived from a
        // single channel would read 1 and the badge would never clear.
        assertEquals(2, folded.unreadCount)
        assertTrue(folded.hasUnread)
        assertEquals(3, folded.totalMessages)
        // Reachable both ways, decided by the CONTACT's own addresses rather than by the thread's
        // most recent message type.
        assertTrue("a contact with a phone must be SMS-able", folded.canSms)
        assertTrue("a contact with an email must be email-able", folded.canEmail)
        // Both normalized addresses fold in, which is what lets a deep link on either one land on
        // this thread instead of opening a second.
        assertEquals(
            setOf("14165551234", "ada@contract.test"),
            folded.matchKeys.toSet(),
        )

        // ⛔ THE DEPRECATED FIELDS ARE ASSERTED TO BE WRONG, WHICH IS WHY THEY ARE MODELLED. `key`
        // holds the email while the thread also carries SMS from a phone number — so anything
        // keyed on it would split this row. Pinning that keeps the reason visible instead of
        // leaving a future reader to wonder why the DTO carries a field it must not use.
        assertEquals("ada@contract.test", folded.key)
        assertEquals("email", folded.kind)
        assertTrue(
            "the deprecated key must NOT be able to stand in for threadKey",
            folded.key != folded.threadKey,
        )

        // ⛔ AND A THREAD WITH NO CONTACT AT ALL. Conversations are derived from the Message table
        // directly so an unknown sender still appears; a client that assumed a non-null contactId
        // would crash on a real inbox rather than on this fixture.
        val unknown = response.conversations.first { it.contactId == null }
        assertTrue("an unresolved thread keys by address", unknown.threadKey.startsWith("addr:"))
        assertNull(unknown.contactName)
        // The display name falls back to the raw counterpart: a phone number IS the identity of
        // that thread, and "Unknown" would hide the only information available.
        assertEquals(unknown.counterpart, unknown.displayName)
        // No contact means no second address to answer at, so only the arrival channel is open.
        assertTrue(unknown.canSms)
        assertFalse(unknown.canEmail)
        assertEquals(0, unknown.unreadCount)
        assertFalse(unknown.hasUnread)

        // ⚠️ THE TRUTHFULNESS SIGNAL. This endpoint does not page — it scans a bounded window and
        // groups what it finds. `scanned == scanLimit` means older conversations were never looked
        // at, and the UI has to say so rather than implying the list is everything.
        assertEquals(response.conversations.sumOf { it.totalMessages }, response.scanned)
        assertTrue("the scan limit must be reported", response.scanLimit > 0)
        assertTrue("this fixture is well inside the window", response.scanned < response.scanLimit)
    }

    @Test
    fun `timeline decodes calls and messages into one chronological thread`() {
        val response = json.decodeFromString<TimelineResponse>(fixture("district-timeline.json"))

        assertEquals(true, response.success)
        assertEquals(5, response.timeline.size)

        // ⛔ CALLS ARE IN THE MESSAGE THREAD ON PURPOSE. An operator reading a conversation needs
        // to see that the customer phoned between two texts. A client that filtered this to
        // messages would silently drop the event that explains the gap.
        assertEquals(
            setOf("sms", "email", "call"),
            response.timeline.map { it.type }.toSet(),
        )
        assertTrue("calls must be present", response.timeline.any { !it.isMessage })

        // ⚠️ ASCENDING, OLDEST FIRST — the opposite of the conversation list. A thread reads top to
        // bottom like a chat; the list reads newest-first like an inbox. The repository sorts
        // defensively anyway, but a fixture that arrived descending would mean the two disagree.
        assertEquals(
            "the timeline must arrive oldest-first",
            response.timeline.map { it.timestamp }.sorted(),
            response.timeline.map { it.timestamp },
        )

        // ⚠️ THE THIRD DIRECTION. A missed call is neither inbound nor outbound, so a two-state
        // client would have to put it on one side of the conversation and both are wrong.
        assertEquals(
            setOf("inbound", "outbound", "missed"),
            response.timeline.map { it.direction }.toSet(),
        )
        val missed = response.timeline.first { it.isMissedCall }
        assertEquals(0, missed.duration)
        // ⛔ hasTranscript IS NOT "THIS IS A CALL". Only the answered call has one.
        assertFalse("a missed call has no transcript to offer", missed.hasTranscript)

        val answered = response.timeline.first { !it.isMessage && !it.isMissedCall }
        assertTrue("the answered call must be offerable", answered.hasTranscript)
        // ⛔ AND THE TRANSCRIPT ITSELF IS ABSENT. It is fetched lazily by
        // GET /api/district/calls/[callId]/transcript, because transcripts dominated this payload
        // before that split. A DTO that expected it inline would render an empty thread body.
        assertNull("the transcript must NOT be inlined", answered.transcript)
        assertTrue("an answered call carries a summary", !answered.summary.isNullOrBlank())

        // Email carries a subject; SMS never does, and the key is OMITTED rather than null.
        val email = response.timeline.first { it.type == "email" }
        assertTrue("an email must decode its subject", !email.subject.isNullOrBlank())
        val sms = response.timeline.filter { it.type == "sms" }
        assertTrue("fixture must cover an SMS with no subject", sms.any { it.subject == null })
        // ⚠️ mediaUrls is a Json column: an array when present, ABSENT when empty. Both shapes have
        // to decode, and the empty case must arrive as an empty list rather than throwing.
        assertTrue("fixture must cover an MMS", sms.any { it.mediaUrls.isNotEmpty() })
        assertTrue("fixture must cover a plain SMS", sms.any { it.mediaUrls.isEmpty() })

        // ⛔ pageInfo IS ALWAYS PRESENT, CURSOR OR NOT — it is not a field that appears only when
        // there is more to read. This fixture is a short thread, so `hasMore` is false and the
        // cursor still names the oldest event: the client stores it either way and simply has no
        // reason to spend it.
        assertFalse("a thread this short has nothing behind it", response.pageInfo.hasMore)
        assertEquals(response.timeline.first().timestamp, response.pageInfo.oldest)
        assertEquals(response.timeline.first().id, response.pageInfo.oldestId)
    }

    /**
     * ⛔ THE FULL-WINDOW BRANCH, WHICH THE SHORT FIXTURE ABOVE CANNOT REACH. `hasMore` is derived
     * from a source coming back with exactly `TIMELINE_MAX_EVENTS_PER_SOURCE` rows, so only a
     * fixture that actually fills a window exercises it — and `hasMore` is the single field the
     * "load older" affordance is drawn from. A DTO that decoded it as its `false` default would
     * hide the rest of every long thread with nothing red anywhere.
     */
    @Test
    fun `a full timeline window decodes a cursor and says there is more behind it`() {
        val response = json.decodeFromString<TimelineResponse>(fixture("district-timeline-page.json"))

        assertEquals(true, response.success)
        assertEquals(TIMELINE_WINDOW, response.timeline.size)
        assertTrue("a filled window must report more behind it", response.pageInfo.hasMore)

        // ⛔ THE CURSOR IS THE OLDEST EVENT OF THE MERGED PAGE, i.e. index 0 of the ascending list —
        // NOT the last element. Reading it off the end would send the NEWEST point back as
        // `before` and re-request the same window forever.
        assertEquals(
            "the timeline must arrive oldest-first",
            response.timeline.map { it.timestamp }.sorted(),
            response.timeline.map { it.timestamp },
        )
        assertEquals(response.timeline.first().timestamp, response.pageInfo.oldest)
        assertEquals(response.timeline.first().id, response.pageInfo.oldestId)

        // ⚠️ The ids must be distinct, because the client's merge dedupes on exactly this field.
        // A fixture with a repeated id would make a dedupe test pass against the wrong thing.
        assertEquals(response.timeline.size, response.timeline.map { it.id }.toSet().size)
    }

    @Test
    fun `a sent SMS decodes the whole row, not just a receipt`() {
        val response = json.decodeFromString<SendMessageResponse>(fixture("district-message-send.json"))

        assertEquals(true, response.success)
        assertNull("a success must not carry an error", response.error)
        val message = response.message
        assertTrue("a success must carry the created row", message != null)

        // ⛔ THE FIELD THIS FIXTURE EXISTS FOR. `status` was being discarded by a DTO that modelled
        // five of thirteen fields, so the app could not tell a queued message from a delivered one.
        assertEquals("queued", message!!.status)
        assertEquals("msg_contract_sent", message.id)
        // The carrier's own id for the send (a Twilio SID here), distinct from our row id.
        assertEquals("SM_contract", message.messageSid)
        assertEquals("sms", message.type)
        assertEquals("outbound", message.direction)
        assertEquals("twilio", message.provider)
        // ⚠️ `from` is the workspace's own number. The customer is `to` — reversing them labels the
        // operator's reply with the operator's own address.
        assertTrue("from must be the sending number", message.from.startsWith("+"))
        assertTrue("to must be the recipient", message.to.startsWith("+"))
        // SMS-only fields present, email-only field absent.
        assertTrue("SMS carries a provider external id", !message.externalId.isNullOrBlank())
        assertTrue("SMS carries the sending account", !message.accountId.isNullOrBlank())
        assertNull("an SMS has no subject", message.subject)
        assertTrue("the row must be timestamped", message.createdAt.isNotBlank())
        assertTrue("the row must name its workspace", message.workspaceId.isNotBlank())
    }

    @Test
    fun `a sent email decodes the same type with the other branch's fields`() {
        val response = json.decodeFromString<SendMessageResponse>(
            fixture("district-message-send-email.json"),
        )

        assertEquals(true, response.success)
        val message = response.message
        assertTrue("a success must carry the created row", message != null)

        // ⛔ ONE ENDPOINT, TWO SHAPES, ONE DTO. This is why so many of SentMessage's fields are
        // nullable: the email branch sends `subject` and omits `externalId`/`accountId`, and the
        // SMS branch does the exact reverse. A single fixture would have made one branch's fields
        // look mandatory and the other branch would fail to decode on a phone.
        assertEquals("email", message!!.type)
        assertEquals("postmark", message.provider)
        assertTrue("an email must carry its subject", !message.subject.isNullOrBlank())
        assertNull("the email branch sends no external id", message.externalId)
        assertNull("the email branch sends no account id", message.accountId)
        // ⚠️ "sent", not "queued". Postmark accepts outright while Twilio queues, so status is not
        // a constant the client may assume from a successful send.
        assertEquals("sent", message.status)
        assertTrue("the sender must be a resolved address", message.from.contains("@"))
    }

    @Test
    fun `mark-read decodes a count rather than a boolean`() {
        val response = json.decodeFromString<MarkReadResponse>(
            fixture("district-message-mark-read.json"),
        )

        assertEquals(true, response.success)
        // ⚠️ A COUNT, AND THE DISTINCTION MATTERS. Zero is a legitimate success — an unknown
        // contact, an unparseable address — so `success: true` alone cannot tell the client whether
        // the badge actually moved. The fixture pins a non-zero one so the field stays exercised.
        assertTrue("the fixture must cover a non-zero mark", response.marked > 0)
    }

    @Test
    fun `unread count decodes as an envelope`() {
        val response = json.decodeFromString<UnreadCountResponse>(fixture("district-messages-unread-count.json"))
        assertEquals(true, response.success)
        assertTrue("count should be non-negative", response.count >= 0)
        assertTrue("workspaceId should be present", response.workspaceId.isNotBlank())
    }

    @Test
    fun `an HQ answer decodes with no confirmation attached`() {
        val response = json.decodeFromString<HqPromptResponse>(fixture("district-hq-answer.json"))

        assertEquals(true, response.success)
        assertTrue("an answer must carry text", response.answer.isNotBlank())

        // ⛔ THE ABSENCE IS THE CONTRACT. The route OMITS both keys on this branch rather than
        // sending them null or false, so the DTO's defaults are what make a plain answer decodable
        // at all. If either field ever loses its default, this fixture stops parsing.
        assertFalse("a plain answer proposes nothing", response.needsConfirmation)
        assertNull("and carries no pending write", response.pendingWrite)
    }

    @Test
    fun `an HQ proposal decodes with opaque args and an operator-facing summary`() {
        val response = json.decodeFromString<HqPromptResponse>(
            fixture("district-hq-pending-write.json"),
        )

        assertEquals(true, response.success)
        // ⛔ NOTHING HAS BEEN WRITTEN. The answer text says so and this flag is the machine-readable
        // half; a client that rendered the answer without the confirm affordance would leave the
        // operator believing the change they asked for had been applied.
        assertTrue("a proposal must announce itself", response.needsConfirmation)

        val pending = response.pendingWrite
        assertTrue("needsConfirmation must come with something to confirm", pending != null)
        assertEquals("update_persona", pending!!.tool)

        // ⛔ A NON-EMPTY ARGS OBJECT, ASSERTED SEPARATELY FROM THE DECODE. `args` is typed as an
        // opaque JsonObject with an EMPTY default, so a fixture that stopped carrying arguments —
        // or a server that stopped sending them — would still decode cleanly and silently confirm
        // a change with no parameters. That is the one failure this fixture exists to make loud.
        assertTrue("the proposal must carry its arguments", pending.args.isNotEmpty())
        assertTrue(
            "and they must be the ones the summary describes",
            pending.args.containsKey("greeting"),
        )

        // ⛔ THE SUMMARY IS THE ONLY DESCRIPTION THE OPERATOR READS. It is composed server-side from
        // the real arguments precisely so the client never has to interpret them; a client falling
        // back to the tool name would ask for approval of "update_persona".
        assertTrue("the summary must be a sentence, not an identifier", pending.summary.contains(" "))
        assertFalse("and must not be the raw tool name", pending.summary == pending.tool)
    }

    @Test
    fun `an HQ confirmation decodes and echoes the action it applied`() {
        val response = json.decodeFromString<HqConfirmResponse>(fixture("district-hq-confirm.json"))

        assertEquals(true, response.success)
        // ⛔ TWO DIFFERENT QUESTIONS. `success` means the request was handled; `executed` means the
        // write took effect. A view-only role answers success:true, executed:false — reporting that
        // as done is what this pair prevents.
        assertTrue("this fixture pins the applied branch", response.executed)

        // ⛔ THE ECHO IS WHAT LETS THE CLIENT PROVE THE APPLIED ACTION IS THE APPROVED ONE. Without
        // it, "we applied something, but not what you approved" would be indistinguishable from
        // success — see HqRepository.confirm.
        assertEquals("update_persona", response.tool)
        assertTrue("the arguments must be echoed too", response.args.containsKey("greeting"))

        // ⚠️ Modelled as an opaque JsonElement because nothing constrains a tool's return value to
        // an object. Carried for diagnostics; `executed` is what decides "applied".
        assertTrue("the tool's own result must survive the round trip", response.result != null)
    }

    @Test
    fun `analytics decodes a populated window with a real prior-period delta`() {
        val report = json.decodeFromString<AnalyticsResponse>(fixture("district-analytics.json"))

        assertEquals(true, report.success)

        // ⛔ EVERY ONE OF THESE IS DERIVED SERVER-SIDE AND MUST NOT BE RECOMPUTED. avgDuration
        // divides by COMPLETED calls while totalCalls counts all of them, so 4200/35 = 120 rather
        // than 4200/48 = 88 — a client that re-derived it from the fields it can see would quote a
        // different number from the web console on the same data.
        assertEquals(48, report.metrics.totalCalls)
        assertEquals(120, report.metrics.avgDuration)
        // ⚠️ ALREADY A PERCENTAGE, already rounded (37.5 -> 38). Multiplying by 100 is the obvious
        // mistake and yields a plausible four-digit number.
        assertEquals(38, report.metrics.conversionRate)
        assertEquals(9, report.metrics.missedCalls)
        assertEquals(4, report.metrics.abandonedCalls)
        // ⛔ HARDCODED ZERO SERVER-SIDE. Pinned so nobody builds a presence tile on a field that
        // can never be anything else.
        assertEquals(0, report.metrics.activeAgents)

        // ⛔ THE NON-NULL BRANCH OF pct. Its sibling fixture pins null; both are needed, because a
        // single fixture would make the field look either mandatory or non-existent.
        assertEquals(20, report.callVolumeDelta.pct)
        assertEquals(DIRECTION_UP, report.callVolumeDelta.direction)
        assertFalse("a workspace with a prior period is not New", report.callVolumeDelta.isNew)
        assertEquals(48, report.callVolumeDelta.current)
        assertEquals(40, report.callVolumeDelta.prior)

        // Seven daily buckets for the 7d window, and the bars sum to the headline.
        assertEquals(7, report.engagementTrends.size)
        assertEquals(
            "the trend bars must sum to the headline call count",
            report.metrics.totalCalls,
            report.engagementTrends.sumOf { it.calls },
        )

        // ⛔ THE TWO DATE FIELDS ARE NOT INTERCHANGEABLE. `date` is a display label localized to
        // the operator's timezone and carrying no year; `isoDate` is the machine-readable one.
        // Asserting the SHAPES is what keeps a client from parsing the wrong field — the failure
        // there is silent, because "Aug 15" parses to nothing rather than throwing.
        assertTrue(
            "isoDate must be a full calendar date",
            report.engagementTrends.all { it.isoDate.matches(Regex("""\d{4}-\d{2}-\d{2}""")) },
        )
        assertTrue(
            "date must be a display label, not an ISO string",
            report.engagementTrends.none { it.date.matches(Regex("""\d{4}-.*""")) },
        )
        // Oldest first — the order the chart draws in, applied server-side.
        assertEquals(
            "the series must arrive oldest-first",
            report.engagementTrends.map { it.isoDate }.sorted(),
            report.engagementTrends.map { it.isoDate },
        )

        // ⛔ THE ZERO-FILLED GAP AND THE ZERO-AVERAGE POINT, both of which are the chart's
        // degenerate inputs. Postgres emits no row for an empty bucket, so a client reading the
        // query result directly would silently draw a shorter axis; and a bucket with traffic but
        // no completed calls has calls > 0 with avgDuration == 0, which is not corrupt data.
        assertTrue(
            "fixture must cover a zero-filled bucket",
            report.engagementTrends.any { it.calls == 0 },
        )
        assertTrue(
            "fixture must cover a bucket with calls but no completed ones",
            report.engagementTrends.any { it.calls > 0 && it.avgDuration == 0 },
        )

        // The funnel narrows: every dial, the connected ones, the converted ones.
        assertEquals(3, report.funnelData.size)
        assertEquals(48, report.funnelData.first().count)
        assertTrue(
            "the funnel must be non-increasing",
            report.funnelData.zipWithNext().all { (a, b) -> a.count >= b.count },
        )

        // ⛔ COLOUR IS AN OPAQUE SERVER-CHOSEN STRING. Typed as a colour it would fail the WHOLE
        // response — every metric above — over a presentational detail; it is parsed leniently at
        // render time instead.
        assertEquals(3, report.sentimentDistribution.size)
        assertTrue(
            "every slice must carry the server's colour verbatim",
            report.sentimentDistribution.all { it.color.startsWith("#") },
        )
        // Neutral is a REMAINDER server-side and is never queried, so the three must sum to the
        // total call count. If they stop doing so, the derivation changed.
        assertEquals(
            report.metrics.totalCalls,
            report.sentimentDistribution.sumOf { it.value },
        )
    }

    @Test
    fun `analytics decodes a workspace with no prior baseline, where pct is null`() {
        // ⛔ THE FIXTURE THIS DTO'S NULLABILITY DEPENDS ON. With only the populated sample above,
        // `pct` would have been typed `Int` — it decodes perfectly there — and every brand-new
        // workspace would have thrown on its first analytics load, in production, with
        // ignoreUnknownKeys = false making it a hard decode failure rather than a missing number.
        val report = json.decodeFromString<AnalyticsResponse>(
            fixture("district-analytics-new-workspace.json"),
        )

        assertEquals(true, report.success)
        assertNull("no prior period means no percentage exists", report.callVolumeDelta.pct)
        assertTrue("and the client renders that as New", report.callVolumeDelta.isNew)
        // ⚠️ `flat`, NOT `up`. Direction and percentage are independent: zero against zero is
        // neither a rise nor a fall, and a client must not infer one field from the other.
        assertEquals(DIRECTION_FLAT, report.callVolumeDelta.direction)
        assertEquals(0, report.callVolumeDelta.current)
        assertEquals(0, report.callVolumeDelta.prior)

        // ⛔ THE SERIES IS ALL ZEROS AND NOT EMPTY, WHICH IS THE DEGENERATE CHART INPUT. The server
        // seeds one point per bucket regardless of data, so "no calls" arrives as seven zeros —
        // meaning a client cannot detect an empty window by checking for an empty list, and its
        // scaling maths divides by a maximum of zero. See AnalyticsChartMathTest.
        assertEquals(7, report.engagementTrends.size)
        assertTrue(
            "an empty window is a series of zeros, not an absent series",
            report.engagementTrends.all { it.calls == 0 && it.avgDuration == 0 },
        )
        // ⚠️ And the sentiment bands are all PRESENT at zero, so a proportional bar has to divide
        // by a total of zero rather than skip an absent list.
        assertEquals(3, report.sentimentDistribution.size)
        assertEquals(0, report.sentimentDistribution.sumOf { it.value })
        assertEquals(0, report.metrics.avgDuration)
        assertEquals(0, report.metrics.conversionRate)
    }

    @Test
    fun `usage decodes a populated month, with fractional minutes and absent metrics`() {
        val response = json.decodeFromString<UsageResponse>(fixture("district-usage.json"))

        assertEquals(true, response.success)
        val usage = response.usage
        assertTrue("a populated month must carry a row", usage != null)
        assertEquals("2026-08", usage!!.month)

        // ⛔ FRACTIONAL, WHICH IS WHY THESE ARE Double AND NOT Int. `amount` is summed as a float
        // server-side; an Int field would fail to decode outright, and "fixing" that by rounding
        // would round a bill.
        assertEquals(1204.25, usage.callMinutesInbound!!, 0.0)
        assertEquals(318.5, usage.callMinutesOutbound!!, 0.0)

        // ⛔ ABSENT IS NOT ZERO, AND THE FIXTURE COVERS BOTH IN ONE ROW. `whatsappInbound` has no
        // key at all — the metric was never metered — while `whatsappOutbound` is present at zero,
        // which means it WAS metered and was zero. Defaulting these fields to 0 would collapse two
        // different facts into one.
        assertNull("an unmetered metric has no key", usage.whatsappInbound)
        assertEquals(0.0, usage.whatsappOutbound!!, 0.0)

        assertEquals("twilio", usage.provider)
        assertTrue("the row should be timestamped", !usage.lastUpdated.isNullOrBlank())
    }

    @Test
    fun `usage decodes a month with no rows as an explicit null rather than zeros`() {
        // ⛔ THE FIXTURE THAT KEEPS `usage` NULLABLE, and the reason it matters is that the failure
        // mode is not a crash. A DTO defaulting this to `UsageData()` decodes the same JSON
        // without complaint and renders a workspace that has sent nothing this month as a
        // confident column of zeros — a billing claim that was never measured, in the one place an
        // operator would take it at face value.
        val response = json.decodeFromString<UsageResponse>(fixture("district-usage-empty.json"))

        assertEquals(true, response.success)
        assertNull("no rows this month means null, never a zeroed row", response.usage)
    }

    @Test
    fun `usage history decodes as an array on the same key the single month uses`() {
        // ⛔ ONE ROUTE, TWO RESPONSE TYPES ON ONE KEY. `usage` is an object (or null) without
        // `history=true` and an ARRAY with it, so a strict parser genuinely needs two DTOs — this
        // is the half that proves the array branch exists rather than being assumed.
        val response = json.decodeFromString<UsageHistoryResponse>(
            fixture("district-usage-history.json"),
        )

        assertEquals(true, response.success)
        assertEquals(3, response.usage.size)
        // Newest first.
        assertEquals(listOf("2026-08", "2026-07", "2026-06"), response.usage.map { it.month })

        // ⚠️ A REAL HISTORY IS NOT A RECTANGLE. Older months carry only the metrics they had rows
        // for, so a client that assumed every key present on every row would throw on a real
        // account while passing against a fixture of identical full rows.
        val oldest = response.usage.last()
        assertNull("a sparse month carries only what it metered", oldest.callMinutesOutbound)
        assertNull(oldest.provider)
        assertEquals(12.0, oldest.smsOutbound!!, 0.0)
    }

    // ── DGI: the dossier's two writes ────────────────────────────────────────

    @Test
    fun `enrich decodes with the server's own pending status, not the web console's`() {
        val response = json.decodeFromString<EnrichResponse>(fixture("district-enrich.json"))

        assertEquals(true, response.success)
        // ⛔ THE VOCABULARY IS THE SERVER'S. The route's progression is
        // pending -> crawling -> synthesizing -> complete | failed, plus NULL. The web dashboard's
        // `useDgi` additionally renders an optimistic "processing" that no route ever writes, so a
        // client that had copied the console would poll for a status that cannot arrive. Pinned
        // here because the two surfaces genuinely disagree and only one of them is the contract.
        assertEquals("pending", response.status)
        assertTrue("the response must carry something to show", !response.message.isNullOrBlank())
    }

    @Test
    fun `clear-intel decodes as an envelope whose flag is the whole payload`() {
        val response = json.decodeFromString<ClearIntelResponse>(fixture("district-clear-intel.json"))

        assertEquals(true, response.success)
        // ⛔ THERE IS NOTHING ELSE IN THIS BODY, which is exactly the shape that decodes cleanly
        // from `{}` and reads as a successful clear. `rejectedEnvelope` in core-data is the only
        // thing standing between an empty 200 and "dossier cleared" on screen — this assertion is
        // here to make that dependency visible, not to prove it.
        assertEquals(ClearIntelResponse(success = true), response)
    }

    // ── Phone-number marketplace ─────────────────────────────────────────────

    @Test
    fun `a number search decodes both the priced row and the priceless one`() {
        val response = json.decodeFromString<NumberSearchResponse>(
            fixture("district-numbers-search.json"),
        )

        assertEquals(true, response.success)
        // The carrier that actually answered, which need not be the one the client asked for.
        assertEquals("twilio", response.provider)
        assertEquals(2, response.numbers.size)

        // ⛔ THE FIXTURE MUST KEEP COVERING BOTH BRANCHES OF THE PRICE FIELD. The Twilio
        // implementation swallows a failed pricing lookup, leaving `monthlyPrice` UNDEFINED — and
        // JSON.stringify drops the key entirely rather than writing null. A regeneration against
        // fully-priced rows would let a future DTO type this as required and throw on the first
        // search from an account without Pricing API access.
        assertTrue(
            "fixture must cover a row WITH a price",
            response.numbers.any { it.monthlyPrice != null },
        )
        assertTrue(
            "fixture must cover a row with NO price key at all",
            response.numbers.any { it.monthlyPrice == null },
        )
        // ⚠️ A price and its currency are separate optional fields. A priced row that carried no
        // currency must not be rendered with a symbol nobody sent.
        assertEquals("USD", response.numbers.first().currency)
        assertNull(response.numbers.last().currency)
        assertNull("locality is optional too", response.numbers.last().locality)

        // Both number types, so the type label is genuinely exercised rather than assumed.
        assertEquals(listOf("local", "tollFree"), response.numbers.map { it.type })
    }

    @Test
    fun `owned numbers decode with a managed row and a workspace-owned row side by side`() {
        val response = json.decodeFromString<OwnedNumbersResponse>(
            fixture("district-provider-numbers.json"),
        )

        assertEquals(true, response.success)

        // ⛔ BOTH HALVES, FROM TWO DIFFERENT DATABASES. `managed:false` is what the tenant's own
        // carrier account reports; `managed:true` is a hub record of a line held on DISTRONODE's
        // account, which the carrier fetch deliberately never sees. Before that hub read existed,
        // a workspace with live managed DIDs answered with an empty list — indistinguishable from
        // owning none. A fixture with only one kind would let a client that ignored the flag pass.
        assertTrue("fixture must cover a managed line", response.numbers.any { it.managed })
        assertTrue("fixture must cover a workspace-owned line", response.numbers.any { !it.managed })

        val own = response.numbers.first { !it.managed }
        assertEquals("+14165550100", own.phoneNumber)
        assertEquals("Main line", own.friendlyName)
        assertEquals(listOf("sms", "voice"), own.capabilities)
        assertTrue("a BYOK row carries its webhook URLs", !own.smsUrl.isNullOrBlank())

        // ⚠️ A MANAGED ROW IS SPARSER, AND THAT IS THE POINT OF HAVING ONE. It is built from a hub
        // index row rather than a carrier response, so it has no friendly name and no webhook
        // URLs — fields a client must therefore treat as optional rather than as "always there
        // for a number you own".
        val managed = response.numbers.first { it.managed }
        assertNull(managed.friendlyName)
        assertNull(managed.smsUrl)
        assertNull(managed.voiceUrl)

        // The all-fallbacks row: no price key at all, plus a defaulted type and status.
        val fallback = response.numbers.first { it.phoneNumber == "+18005550188" }
        assertNull("an unpriced managed line has no price key", fallback.monthlyPrice)
        assertEquals("local", fallback.type)
        assertEquals("active", fallback.status)
        assertEquals("telnyx", fallback.provider)

        // ⛔ NEITHER FLAG IS PRESENT ON A CLEAN LIST — they are absent, not false/empty — which is
        // what makes the partial fixture below necessary rather than redundant.
        assertFalse(response.partial)
        assertTrue(response.failedProviders.isEmpty())
    }

    @Test
    fun `a partial owned-numbers list still carries rows, and names the carrier that failed`() {
        // ⛔ THE DANGEROUS SHAPE OF THIS ROUTE: a 200 with a REAL but SHORT list. It decodes
        // perfectly and draws exactly like a complete answer, so the only thing separating "here
        // is everything you own" from "here is what one carrier could tell us" is this flag. The
        // route reserves its 502 for the case where nothing resolved at all — precisely so a
        // failed lookup is never rendered as "you own no numbers".
        val response = json.decodeFromString<OwnedNumbersResponse>(
            fixture("district-provider-numbers-partial.json"),
        )

        assertEquals(true, response.success)
        assertTrue("the flag is the entire warning", response.partial)
        assertTrue("must name which carrier failed", response.failedProviders.isNotEmpty())
        assertEquals(listOf("telnyx"), response.failedProviders)

        // ⛔ AND THE ROWS SURVIVE. A client that replaced the list with a banner would discard
        // inventory already in hand.
        assertTrue("a partial answer still carries what resolved", response.numbers.isNotEmpty())
        assertTrue(response.numbers.any { it.managed })
    }

    /**
     * ⛔ PROVES THE ANALYTICS AND USAGE DTOs ARE LOSSLESS FOR THE SERVER'S OWN OUTPUT, which the
     * decode assertions above do NOT. Decoding only shows that the fields a DTO declares can be
     * read; it says nothing about a field the DTO does not declare, or one whose declared default
     * happens to equal the value that arrived. Round-tripping through the model and comparing the
     * result to the first decode is what catches a field that was silently absorbed — the value
     * survives the first parse into a default and cannot be told apart from a real one.
     *
     * ⚠️ ENCODED BOTH WAYS ON PURPOSE. `encodeDefaults = true` writes every field, `false` writes
     * only the ones that differ from their declared default — which is exactly the shape the
     * SERVER sends (it omits keys rather than sending nulls). A DTO that survives one and not the
     * other has a default that disagrees with the wire, and the honest test is the pair.
     *
     * ⚠️ NOTHING IN THE APP ENCODES THESE TYPES IN PRODUCTION — they are read-only responses. That
     * is what makes this a contract check rather than a behaviour one, and it is why it lives here
     * beside the fixtures rather than in a repository test.
     */
    @Test
    fun `the analytics and usage DTOs re-encode without losing anything`() {
        val verbose = Json { encodeDefaults = true }
        val terse = Json { encodeDefaults = false }

        fun <T> roundTrip(
            serializer: kotlinx.serialization.KSerializer<T>,
            fixtureName: String,
        ) {
            val decoded = json.decodeFromString(serializer, fixture(fixtureName))
            assertEquals(
                "$fixtureName must survive a full round trip with every field written",
                decoded,
                json.decodeFromString(serializer, verbose.encodeToString(serializer, decoded)),
            )
            assertEquals(
                "$fixtureName must survive a round trip in the server's own omit-defaults shape",
                decoded,
                json.decodeFromString(serializer, terse.encodeToString(serializer, decoded)),
            )
        }

        roundTrip(AnalyticsResponse.serializer(), "district-analytics.json")
        // ⛔ The all-zero, null-pct workspace specifically: nearly every field of it EQUALS its
        // declared default, so this is the one case where the terse encoding writes almost nothing
        // and a wrong default would go completely unnoticed.
        roundTrip(AnalyticsResponse.serializer(), "district-analytics-new-workspace.json")
        roundTrip(UsageResponse.serializer(), "district-usage.json")
        roundTrip(UsageResponse.serializer(), "district-usage-empty.json")
        roundTrip(UsageHistoryResponse.serializer(), "district-usage-history.json")

        // ⛔ THE MARKETPLACE DTOs SPECIFICALLY, BECAUSE THEIR OPTIONAL FIELDS ARE ABSENT RATHER
        // THAN NULL ON THE WIRE. The terse encoding writes only what differs from a declared
        // default, which is exactly the server's own shape — so a field whose default silently
        // swallowed a real value (a `monthlyPrice = 0.0` default, say, absorbing a genuine zero)
        // would fail here and pass every decode assertion above.
        roundTrip(NumberSearchResponse.serializer(), "district-numbers-search.json")
        roundTrip(OwnedNumbersResponse.serializer(), "district-provider-numbers.json")
        roundTrip(OwnedNumbersResponse.serializer(), "district-provider-numbers-partial.json")
        roundTrip(EnrichResponse.serializer(), "district-enrich.json")
        roundTrip(ClearIntelResponse.serializer(), "district-clear-intel.json")

        // ⛔ BOTH TIMELINE FIXTURES, AND THE PAIR IS THE POINT. `pageInfo` is a nested object with a
        // whole-object default, so the terse encoding writes the KEY ONLY when the value differs
        // from `TimelinePageInfo()` — a default that swallowed a real cursor (an `oldest` wrongly
        // declared `""`, say) would round-trip cleanly on the no-cursor case and lose the cursor on
        // this one. The short thread has `hasMore = false`, the full window has `true`, so the two
        // sit on opposite sides of that default.
        roundTrip(TimelineResponse.serializer(), "district-timeline.json")
        roundTrip(TimelineResponse.serializer(), "district-timeline-page.json")
    }

    /**
     * Proves the guard actually guards. If this ever passes, `ignoreUnknownKeys` has been
     * relaxed somewhere and every other assertion in this file has quietly stopped
     * protecting anything.
     */
    @Test
    fun `an unmodelled server field is rejected rather than ignored`() {
        val withExtraField = """[{"id":"c1","type":"inbound","number":"x","status":"completed",""" +
            """"duration":"0s","time":"t","aiSummary":"s","hasTranscript":false,"callerName":"x",""" +
            """"summary":"","createdAt":"2026-08-15T00:00:00.000Z","brandNewServerField":"boom"}]"""

        val failure = runCatching { json.decodeFromString<List<CallSummary>>(withExtraField) }
        assertTrue(
            "Decoding an unknown key MUST fail. It succeeded, which means " +
                "ignoreUnknownKeys is no longer false and contract drift can now ship silently.",
            failure.isFailure,
        )
    }
}

/**
 * The server's `TIMELINE_MAX_EVENTS_PER_SOURCE`.
 *
 * ⚠️ ASSERTED RATHER THAN ASSUMED, because it is the number `hasMore` is derived from: a source
 * that came back with exactly this many rows had its window filled. A fixture holding fewer would
 * still decode, and would silently stop exercising the branch this constant exists for.
 */
private const val TIMELINE_WINDOW = 50
