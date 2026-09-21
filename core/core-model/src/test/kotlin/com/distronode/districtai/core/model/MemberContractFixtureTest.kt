package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The membership and rename halves of the contract gate.
 *
 * ⛔ A SEPARATE CLASS FROM `KnowledgeContractFixtureTest`, for the reason that one states in turn:
 * `ContractFixtureTest` reached detekt's LargeClass ceiling and the healthy answer is another class
 * rather than a raised threshold. The strict decoder and the fixture loader are shared through
 * [ContractFixtures], so the split cannot make one of them lenient.
 *
 * ⛔ THE REFUSALS ARE NOT HERE, AND THAT IS DELIBERATE. `district-member-duplicate.json` and
 * `district-member-last-agency.json` are pinned in core-network's `ApiErrorEnvelopeContractTest`,
 * next to [com.distronode.districtai.core.network.ApiErrorEnvelope] and the `mapFailure` that
 * consumes them — the same reasoning that put the degraded-regions body there. This module has no
 * type that decodes an error body at all, so pinning them here would mean inventing a test-only
 * copy of the shape and proving the copy correct while the shipping type drifted.
 */
class MemberContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    // ── The roster ───────────────────────────────────────────────────────────

    @Test
    fun `the member list decodes, with all three role branches`() {
        val response = json.decodeFromString<MemberListResponse>(fixture("district-members.json"))

        assertEquals(true, response.success)
        assertEquals(3, response.members.size)

        // ⛔ ONE ROW PER ROLE, AND `role` IS A PLAIN STRING COLUMN RATHER THAN AN ENUM. The schema
        // is `role String @default("client")` with no Prisma enum and no TypeScript union behind
        // it, so a DTO that decoded a sealed vocabulary would throw on an unmodelled value and
        // lose the WHOLE list — not one row.
        assertEquals(listOf("agency", "client", "viewer"), response.members.map { it.role })

        // ⛔ AND EVERY ONE OF THEM PARSES THROUGH THE CLIENT'S OWN VOCABULARY. This is the half a
        // string assertion cannot cover: `fromWire` fails CLOSED to null, so a spelling this build
        // does not know silently becomes "no privileges", and a fixture that only ever carried
        // `client` would let that regression ship for the other two.
        assertEquals(
            listOf(WorkspaceRole.AGENCY, WorkspaceRole.CLIENT, WorkspaceRole.VIEWER),
            response.members.map { WorkspaceRole.fromWire(it.role) },
        )

        val founder = response.members[0]
        assertEquals("founder@contract.test", founder.email)
        // ⚠️ An ISO-8601 STRING. This module owns no date parsing.
        assertEquals("2026-08-15T14:30:00.000Z", founder.createdAt)

        // ⚠️ OLDEST FIRST — the route's `createdAt asc`, the opposite of every other list in this
        // gate. Asserted on the VALUES rather than trusting the order the stub returned, so a
        // fixture regenerated from a changed `orderBy` fails here rather than reshuffling silently.
        val timestamps = response.members.mapNotNull { it.createdAt }
        assertEquals(timestamps.sorted(), timestamps)
    }

    @Test
    fun `no membership row carries an id`() {
        // ⛔ `(workspaceId, email)` IS THE NATURAL KEY EVERY MEMBERSHIP PATH USES, and the route's
        // `MEMBER_SELECT` deliberately does not publish the row's uuid — so `email` is the identity
        // the PATCH body and the DELETE query carry. Asserted against the RAW text, because a
        // decoded object cannot show a key the DTO does not model.
        val raw = fixture("district-members.json")

        assertTrue("an id on the wire would invite a client to key on it", !raw.contains("\"id\""))
    }

    // ── The three writes ─────────────────────────────────────────────────────

    @Test
    fun `the add response echoes a row in exactly the list's shape`() {
        val response =
            json.decodeFromString<MemberMutationResponse>(fixture("district-member-add.json"))

        assertEquals(true, response.success)
        assertNotNull("the add must echo the row it made", response.member)
        assertEquals("newcomer@contract.test", response.member!!.email)
        // ⚠️ LOWERCASED SERVER-SIDE. The request sent "  Newcomer@Contract.Test "; the route trims
        // and lowercases before it stores or compares, which is what makes the membership lookups
        // able to match it. A client that displayed what it sent would show a different address
        // from the one that exists.
        assertEquals("newcomer@contract.test", response.member.email.lowercase())
        assertEquals("viewer", response.member.role)

        // ⚠️ THE SAME KEY SET AS A LIST ROW — unlike the knowledge create, which is one field
        // shorter than its list. Both writes here go through the route's single `MEMBER_SELECT`.
        val listRow = json.decodeFromString<MemberListResponse>(fixture("district-members.json"))
            .members
            .first()
        assertEquals(
            "a write's echo and a list row must decode into the same shape",
            listRow::class,
            response.member::class,
        )
    }

    @Test
    fun `the role change echoes the NEW role`() {
        val response = json.decodeFromString<MemberMutationResponse>(
            fixture("district-member-role-patch.json"),
        )

        assertEquals(true, response.success)
        // ⛔ THE DEMOTED ROLE, not the one the row held. This fixture is generated by a request
        // that had to pass the lockout guard — the member WAS `agency` and a second agency member
        // existed — so it pins the allowed side of the branch whose refusal is
        // `district-member-last-agency.json`.
        assertEquals("client", response.member!!.role)
        assertEquals(WorkspaceRole.CLIENT, WorkspaceRole.fromWire(response.member.role))
    }

    @Test
    fun `the removal decodes even though it carries no member at all`() {
        // ⛔ THE ASYMMETRY THIS TEST EXISTS FOR. `DELETE` answers a bare `{success:true}` while the
        // other two writes answer `{success, member}`, so the same type has to decode both — which
        // it can only do because `member` is nullable. A type that required the row would throw on
        // the response to a successful REMOVAL, which is the one place a client must not fail: the
        // row really is gone, and a decode failure would present it as still there.
        val raw = fixture("district-member-remove.json")
        val response = json.decodeFromString<MemberMutationResponse>(raw)

        assertEquals(true, response.success)
        assertNull(response.member)
        assertTrue(
            "the removal fixture must keep omitting `member`, or this test proves nothing",
            !raw.contains("member"),
        )
    }

    // ── The rename ───────────────────────────────────────────────────────────

    @Test
    fun `the rename echoes the TRIMMED name the server stored`() {
        // ⛔ THE REQUEST SENT "  Renamed Workspace  ". The route trims BEFORE it measures — " " is
        // an empty name, not a one-character one — and echoes what it WROTE, which is why this is
        // the one write on this surface the client does not follow with a re-read. Adopting the
        // requested string instead would display a name nobody stored.
        val response = json.decodeFromString<RenameResponse>(fixture("district-rename.json"))

        assertEquals(true, response.success)
        assertEquals("Renamed Workspace", response.name)
        assertEquals(
            "the echo must already be trimmed, or the client would have to trim it too",
            response.name,
            response.name!!.trim(),
        )
    }

    @Test
    fun `the rename carries the name and nothing else`() {
        // ⛔ NAME ONLY, NEVER THE SLUG. `slug` is unique in two physically separate databases (the
        // hub's WorkspaceDirectory and the region's Workspace row) with no cross-database
        // transaction to keep them in step, so it is not editable from this route at all. A `slug`
        // key appearing here would mean that decision had been reversed.
        val raw = fixture("district-rename.json")

        assertTrue(!raw.contains("slug"))
        assertTrue(!raw.contains("\"id\""))
    }

    // ── The client's own constants ───────────────────────────────────────────

    @Test
    fun `the role vocabulary this client sends matches the one the routes accept`() {
        // ⛔ THE WIRE SPELLING IS LOWERCASE AND THE ROUTE VALIDATES AGAINST IT. `normalizeRole`
        // lowercases and then tests membership of `["agency","client","viewer"]`, answering 400 for
        // anything else — so sending `AGENCY` would be a 400 rather than a coerced value.
        assertEquals(
            listOf("agency", "client", "viewer"),
            ASSIGNABLE_MEMBER_ROLES.map { it.toWire() },
        )
        // ⚠️ `client`, NOT `viewer`. The cautious guess is wrong: an operator who never touches the
        // picker grants the ordinary tenant role.
        assertEquals(WorkspaceRole.CLIENT, DEFAULT_MEMBER_ROLE)
        assertEquals(MAX_NAME_LENGTH, MAX_WORKSPACE_NAME_LENGTH)

        // ⛔ THE TWO CODES MUST BE DISTINCT AND MUST BE THESE EXACT STRINGS. `MembersRepository`
        // branches on them to choose between two messages that mean opposite things.
        assertEquals("member_exists", CODE_MEMBER_EXISTS)
        assertEquals("last_agency_member", CODE_LAST_AGENCY_MEMBER)
        assertTrue(CODE_MEMBER_EXISTS != CODE_LAST_AGENCY_MEMBER)
    }

    @Test
    fun `every membership DTO compares by VALUE, field by field`() {
        // ⛔ NOT A TAUTOLOGY ABOUT `data class`, AND THE LAYERS ABOVE DEPEND ON IT. `MembersRepository`
        // returns `MemberMutationOutcome.Done(member)` and the ViewModel's tests assert on whole
        // rosters with `assertEquals`; if any of these stopped being value types, those comparisons
        // would silently fall back to identity and pass for the wrong reasons — an assertion that
        // cannot fail is worse than none. Each field is varied separately, because an `equals` that
        // ignored ONE field would still satisfy a whole-object comparison of two identical
        // instances.
        val member = WorkspaceMember("a@example.com", "agency", "2026-08-15T14:30:00.000Z")
        assertEquals(member, member.copy())
        listOf(
            member.copy(email = "b@example.com"),
            member.copy(role = "client"),
            member.copy(createdAt = "2026-08-16T00:00:00.000Z"),
        ).forEach { assertTrue("each field must participate in equality", member != it) }

        val list = MemberListResponse(success = true, members = listOf(member))
        assertEquals(list, list.copy())
        assertTrue(list != list.copy(success = false))
        assertTrue(list != list.copy(members = emptyList()))

        val mutation = MemberMutationResponse(success = true, member = member)
        assertEquals(mutation, mutation.copy())
        assertTrue(mutation != mutation.copy(success = false))
        // ⚠️ The removal's shape — a `null` member — must not compare equal to an echoed one.
        assertTrue(mutation != mutation.copy(member = null))

        val rename = RenameResponse(success = true, name = "A")
        assertEquals(rename, rename.copy())
        assertTrue(rename != rename.copy(success = false))
        assertTrue(rename != rename.copy(name = "B"))
    }

    @Test
    fun `every membership REQUEST compares by value, so a test can assert what was sent`() {
        // ⚠️ The fakes record these and the repository tests assert on them; identity comparison
        // would make every such assertion vacuous.
        val add = MemberAddRequest("ws-1", "a@example.com", "client")
        assertEquals(add, add.copy())
        listOf(
            add.copy(workspaceId = "ws-2"),
            add.copy(email = "b@example.com"),
            // ⚠️ A NULL ROLE IS A DIFFERENT REQUEST FROM AN EXPLICIT ONE — it is omitted on the
            // wire, which is what makes the server apply its own default rather than answer 400.
            add.copy(role = null),
        ).forEach { assertTrue(add != it) }

        val patch = MemberRoleRequest("ws-1", "a@example.com", "agency")
        assertEquals(patch, patch.copy())
        listOf(
            patch.copy(workspaceId = "ws-2"),
            patch.copy(email = "b@example.com"),
            patch.copy(role = "viewer"),
        ).forEach { assertTrue(patch != it) }

        val rename = WorkspaceRenameRequest("ws-1", "A Name")
        assertEquals(rename, rename.copy())
        assertTrue(rename != rename.copy(workspaceId = "ws-2"))
        assertTrue(rename != rename.copy(name = "Another"))
    }

    private companion object {
        /** The rename route's own `MAX_NAME_LENGTH`, restated so a drift is a failing assertion. */
        const val MAX_NAME_LENGTH = 120
    }
}
