package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/workspace/list` — the workspaces this user may operate on.
 *
 * ⛔ THIS ROUTE EXISTS FOR THIS CLIENT. On the web the list never crosses the network: it
 * is rendered into the page as React Server Component props by `toWorkspaceOptions`, and
 * the switcher only ever POSTs the user's choice back. There was nothing to GET.
 *
 * ⛔ AND THE ACTIVE WORKSPACE IS THIS CLIENT'S OWN STATE, NOT THE SERVER'S. The browser's
 * active workspace is the httpOnly `distronode_workspace_id` cookie, which the lister
 * promotes to index 0 and every server-side `requireWorkspaceRole` fallback then reads. A
 * bearer-token client holds no cookies, so it must send `workspaceId` explicitly on every
 * request instead — which the server already treats as a hint and re-validates against
 * live membership. [workspaces] is what may legally be sent.
 *
 * ⚠️ INDEX 0 IS MEANINGFUL. The server has already applied owned-first ordering, so
 * `workspaces.first()` is the workspace the browser would consider active. Use it as the
 * default when the user has expressed no preference; do NOT re-sort this list and then
 * treat the new first element as the default, or the app and the browser will disagree
 * about which tenant is in view.
 */
@Serializable
data class WorkspaceListResponse(
    val success: Boolean = false,
    val workspaces: List<WorkspaceEntry> = emptyList(),
    /**
     * Regions whose databases did not answer while the list was being built.
     *
     * ⛔ A NON-EMPTY VALUE MEANS THIS LIST IS INCOMPLETE, AND IGNORING THAT IS A REAL BUG.
     * "This account belongs to nothing" and "we could not finish looking" are different
     * facts, and conflating them sends a paying customer to the checkout page. The server
     * refuses to let this client make the same
     * mistake in the total-failure case — nothing resolved AND a region down is a 503, not
     * an empty 200 — but a PARTIAL failure arrives here as a 200 with a short list and
     * this field populated. Surface it; never silently show a subset as if it were
     * everything.
     */
    val degradedRegions: List<String> = emptyList(),
    /**
     * How many workspaces were withheld because their subscription is not active.
     *
     * The server filters to `subscriptionStatus === "active"` to match the web picker,
     * because a wider list would let this app operate on a workspace the browser sends to
     * checkout. When [workspaces] is empty and this is greater than zero, the account
     * exists and its billing lapsed — which is a different screen from "no workspaces",
     * and the only way to tell them apart.
     *
     * ⚠️ Billing is READ-ONLY in this app (Play Payments policy). Report the state; do not
     * offer a way to pay for it here.
     */
    val inactiveCount: Int = 0,
    /**
     * The user's stored "remember my choice", so a fresh install can land where the
     * browser would.
     *
     * ⚠️ NOT GUARANTEED TO APPEAR IN [workspaces]. The stored preference can name a
     * workspace whose subscription has since lapsed, or one the user was removed from —
     * the server echoes it verbatim without cross-checking. Treat it as an id to LOOK UP
     * in [workspaces], and fall back to index 0 when it is absent. Sending it blind would
     * earn a 403.
     */
    val defaultWorkspaceId: String? = null,

    /**
     * How many ACTIVE workspaces exist for this user, independent of how many this page
     * carried.
     *
     * ⛔ THIS IS THE COUNT OF THE THING BEING PAGED, SO IT EXCLUDES INACTIVE ONES. It is not
     * `workspaces.size + inactiveCount`, and it is not the size of the account. Compare it
     * against `workspaces.size` to decide whether more pages exist; do not derive
     * "everything is here" from any other field.
     *
     * ⚠️ AND IT IS NOT A SUBSTITUTE FOR [degradedRegions]. A region that failed to answer is
     * missing from BOTH this number and the list, so `workspaces.size == total` can hold
     * while the answer is still incomplete. The degraded check stays load-bearing.
     */
    val total: Int = 0,

    /**
     * The page size the server actually applied, which is not necessarily the one requested.
     *
     * ⚠️ The route clamps: a missing, zero, negative or NaN `limit` becomes the default, and
     * anything above the ceiling is capped. Echoing the EFFECTIVE value means a client can
     * detect that it was clamped rather than assuming its request was honoured — which is
     * the same short-page-means-end-of-list trap that
     * `page-size-invariant.test.ts` exists to prevent on the calls feed.
     */
    val limit: Int = 0,

    /** The offset the server applied. Zero unless this client asked for a later page. */
    val offset: Int = 0,
)

/**
 * One selectable workspace.
 *
 * ⚠️ [role] is deliberately a raw `String` rather than an enum. Server-side it is a plain
 * `String` column with a comment — `role String @default("client") // agency | client | viewer`
 * — with no Prisma enum and no TypeScript union anywhere, so a value outside those three
 * is a schema-level possibility. Decoding straight into an enum would throw on an
 * unmodelled role and take out the whole workspace list; parse it through
 * [WorkspaceRole.fromWire] instead, which fails closed to no privileges.
 */
@Serializable
data class WorkspaceEntry(
    val id: String,
    val name: String,
    /** Data-residency region of this workspace's rows: "us" | "ca" | "eu" | "apac". */
    val region: String,
    /** "agency" | "client" | "viewer", lowercased by the server. See the class note. */
    val role: String,
    /**
     * Raw billing tier, straight off the column — null when never set.
     *
     * ⛔ CASE IS WHATEVER THE DATABASE HOLDS. In a real database the values are `VoicePro` and
     * `VoiceStarter`, MIXED CASE. Nothing normalises this column on write and the route does not
     * normalise on read, so a comparison like `subscriptionTier == "voicepro"` silently never
     * matches. Lowercase before comparing: the server's own tier check in /api/auth/me does
     * exactly that, which is why it works.
     *
     * ⚠️ Not a display string either. `GET /api/settings` substitutes a capitalised "Free" for
     * an empty tier and this route deliberately does not, so the same underlying value reads
     * differently depending on which endpoint produced it. Format it here, in the client.
     */
    val subscriptionTier: String? = null,
)
