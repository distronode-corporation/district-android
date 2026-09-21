package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * Workspace membership, and the workspace's own display name.
 *
 * ⛔ MEMBERSHIP IS THE THING EVERY OTHER GUARD IN THE PRODUCT IS DERIVED FROM. `getWorkspaceRole`
 * answers from these rows, so a client or viewer able to write here could grant themselves any
 * role and walk through every `requireWorkspaceRole` in the API. The route's own header says it:
 * the READ admits all three roles, and **every mutation is agency-only** — a narrower allow-list
 * than the `["agency","client"]` that [WorkspaceRole.canMutate] mirrors, which is exactly the case
 * that property's KDoc warns is too coarse. Gate the mutation controls on `AGENCY` specifically.
 *
 * ⛔ AND THE SERVER REFUSES TO LEAVE A WORKSPACE WITH NO ADMINISTRATOR. Demoting or removing the
 * LAST agency member is a **409 `last_agency_member`**, because no remaining caller would pass the
 * agency-only guard and the state is unrecoverable from the tenant side. That refusal, and the
 * duplicate-address **409 `member_exists`**, are the two machine-readable codes this client
 * branches on; see [CODE_LAST_AGENCY_MEMBER] and [CODE_MEMBER_EXISTS].
 *
 * ⚠️ NO INVITATION, NO PROVISIONING. Adding a row makes an EXISTING login a member; an address
 * that never signed up simply has a row waiting for it. Nothing is emailed. The screen has to say
 * so, or an operator will add an address and wait for an invitation that is not coming.
 *
 * ⚠️ NO IDS ON THE WIRE. `(workspaceId, email)` is the natural key every membership path already
 * uses, and the route deliberately does not publish the row's uuid — so [WorkspaceMember.email] IS
 * the identity, and it is what the PATCH body and the DELETE query carry.
 */

/**
 * One membership row, as every one of these routes selects it.
 *
 * ⛔ THE SAME TYPE DECODES THE LIST AND THE TWO WRITES THAT ECHO A ROW. `GET` returns an array of
 * these, `POST` and `PATCH` each return one under `member`, and all three use the route's single
 * `MEMBER_SELECT` — so the shapes genuinely are identical rather than coincidentally similar.
 *
 * ⚠️ [role] IS A PLAIN STRING ON THE WIRE, NOT AN ENUM, FOR THE REASON [WorkspaceRole] STATES: the
 * column is `role String @default("client")` with no Prisma enum and no TypeScript union behind
 * it, so a fourth value is a schema-level possibility. Decoding an enum here would throw and take
 * out the whole member list; parse it through [WorkspaceRole.fromWire], which fails closed.
 *
 * ⚠️ AND IT IS NOT NECESSARILY LOWERCASE. This route only ever writes lowercase, but a
 * server-side replace (`setWorkspaceMembers`) stores whatever it was handed, and
 * the server's own agency count is deliberately case-INSENSITIVE because of it. An "Agency" row
 * grants agency; `fromWire` lowercases, so do not compare this string directly.
 *
 * ⚠️ [createdAt] IS AN ISO-8601 STRING. This module owns no date parsing.
 */
@Serializable
data class WorkspaceMember(
    val email: String = "",
    val role: String? = null,
    val createdAt: String? = null,
)

/**
 * `GET /api/district/workspace/members?workspaceId=` — the roster, oldest first.
 *
 * ⚠️ ORDERED `createdAt asc`, WHICH IS THE OPPOSITE OF EVERY OTHER LIST ON THIS SURFACE (documents,
 * calls and conversations are all newest-first). It is the route's own `orderBy` and it is the
 * right one here: the first row is the founding member, and a roster that reshuffled as people
 * joined would be harder to scan than one that grows at the bottom.
 *
 * ⚠️ `members` DEFAULTS TO EMPTY rather than being nullable, because `findMany` always emits the
 * array. An absent key is contract drift, which the envelope check is what catches.
 */
@Serializable
data class MemberListResponse(
    val success: Boolean = false,
    val members: List<WorkspaceMember> = emptyList(),
)

/**
 * The answer to every membership WRITE.
 *
 * ⛔ ONE TYPE FOR THREE ROUTES WITH TWO DIFFERENT KEY SETS, AND THAT IS WHY [member] IS NULLABLE.
 * `POST` and `PATCH` answer `{success:true, member:{…}}`; `DELETE` answers a bare `{success:true}`
 * with no `member` key at all. A type that required the row would throw on the response to a
 * successful REMOVAL — the one place a client must not fail, because the row really is gone and a
 * decode failure would present it as still there.
 *
 * ⚠️ THE ECHOED ROW IS NOT A SUBSTITUTE FOR RE-READING THE LIST. It is one row of a roster whose
 * ORDER the client does not control, and a removal echoes nothing at all, so the screen re-reads
 * after every write rather than patching what is on screen.
 */
@Serializable
data class MemberMutationResponse(
    val success: Boolean = false,
    val member: WorkspaceMember? = null,
)

/**
 * `PATCH /api/district/workspace/rename` — `{success, name}`.
 *
 * ⛔ NAME ONLY, AND THE ROUTE'S HEADER EXPLAINS WHY THE SLUG IS NOT HERE: `slug` is unique in two
 * physically separate databases (the hub's `WorkspaceDirectory` and the region's `Workspace` row)
 * with no cross-database transaction to keep them in step, so a slug change could commit in one
 * and fail in the other. `name` is display-only and lives on the regional row alone.
 *
 * ⛔ AND [name] IS THE **TRIMMED** VALUE THE SERVER STORED, not the string that was sent. The route
 * trims before it measures (" " is an empty name, not a one-character one) and echoes what it
 * wrote — so this is the value a later read will see, and adopting it is what makes a second read
 * unnecessary. Adopting the REQUESTED string instead would display a name nobody stored.
 */
@Serializable
data class RenameResponse(
    val success: Boolean = false,
    val name: String? = null,
)

/**
 * The body of `POST /api/district/workspace/members`.
 *
 * ⚠️ [role] IS OPTIONAL AND THE SERVER DEFAULTS IT TO `client`. Sent explicitly by this client so
 * the row that lands matches the picker the operator was looking at — `explicitNulls = false` on
 * the body encoder means a null is OMITTED rather than sent, which is the same thing as absent and
 * is what makes an unset picker take the server's default rather than a 400.
 *
 * ⚠️ THE EMAIL IS LOWERCASED AND TRIMMED SERVER-SIDE before it is stored or compared, so the
 * duplicate check is case-insensitive whatever this sends. The route's own regex is deliberately
 * loose — the point is that a stored address is MATCHABLE by the membership lookups, not that it
 * is deliverable — and an address that fails it is a **400**, not a stored row.
 */
@Serializable
data class MemberAddRequest(
    val workspaceId: String,
    val email: String,
    val role: String? = null,
)

/**
 * The body of `PATCH /api/district/workspace/members`.
 *
 * ⛔ [role] IS REQUIRED HERE, UNLIKE [MemberAddRequest]. There is no "reset to the default" verb:
 * an absent or unrecognised role is a 400 rather than a silent `client`, which is correct for a
 * call whose whole purpose is to say what the role should become.
 *
 * ⛔ AND A DEMOTION MAY BE REFUSED. If this member is the last one holding `agency`, the route
 * answers 409 [CODE_LAST_AGENCY_MEMBER] and writes nothing — the count and the write run in one
 * interactive transaction precisely so two concurrent demotions cannot both pass a count of two.
 */
@Serializable
data class MemberRoleRequest(
    val workspaceId: String,
    val email: String,
    val role: String,
)

/**
 * The body of `PATCH /api/district/workspace/rename`.
 *
 * ⚠️ VALIDATED BEFORE THE ROLE CHECK, so a malformed request costs no session lookup — which means
 * a 400 from here is about the NAME and never about permission. The rule is 1..[MAX_WORKSPACE_NAME_LENGTH]
 * characters AFTER trimming; this client applies the same rule so the operator is not charged a
 * round trip to be told.
 */
@Serializable
data class WorkspaceRenameRequest(
    val workspaceId: String,
    val name: String,
)

/**
 * ⛔ THE 409 FOR AN ADDRESS THAT IS ALREADY A MEMBER. The route answers it from a pre-check AND
 * from the unique-constraint catch behind it, so the code arrives whether the duplicate was
 * ordinary or a race between two concurrent adds.
 */
const val CODE_MEMBER_EXISTS: String = "member_exists"

/**
 * ⛔ THE 409 THAT STOPS A WORKSPACE LOSING ITS LAST ADMINISTRATOR. Sent by both `PATCH` (a
 * demotion) and `DELETE` (a removal). It is not a validation failure and must not be worded as
 * one: nothing the operator typed is wrong, the workspace simply needs someone left who can
 * administer it.
 */
const val CODE_LAST_AGENCY_MEMBER: String = "last_agency_member"

/** ⚠️ The route's `MAX_NAME_LENGTH`, measured AFTER trimming. */
const val MAX_WORKSPACE_NAME_LENGTH: Int = 120

/**
 * The role the server assigns when a request omits one.
 *
 * ⚠️ `client`, NOT `viewer`. Worth stating because the cautious guess is wrong: the ordinary
 * tenant role is the default, so an operator who adds someone without touching the picker grants
 * more than read-only.
 */
val DEFAULT_MEMBER_ROLE: WorkspaceRole = WorkspaceRole.CLIENT

/**
 * The roles the picker offers, in the server's own order (`MEMBER_ROLES` in the route).
 *
 * ⚠️ DERIVED FROM THE ENUM RATHER THAN LISTED AGAIN, so a role added to [WorkspaceRole] cannot be
 * silently missing from the picker — the two would otherwise drift with nothing comparing them.
 */
val ASSIGNABLE_MEMBER_ROLES: List<WorkspaceRole> = WorkspaceRole.entries.toList()

/**
 * The wire spelling of a role.
 *
 * ⛔ LOWERCASE, BECAUSE THAT IS WHAT THE ROUTE VALIDATES AGAINST. It normalises with
 * `raw.trim().toLowerCase()` and answers 400 for anything outside `agency|client|viewer`, so
 * sending `AGENCY` would be a 400 rather than a coerced value. The inverse is
 * [WorkspaceRole.fromWire], which is deliberately case-insensitive because older stored rows are
 * not.
 */
fun WorkspaceRole.toWire(): String = name.lowercase()
