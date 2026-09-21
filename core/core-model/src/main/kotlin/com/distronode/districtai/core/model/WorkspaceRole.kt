package com.distronode.districtai.core.model

/**
 * A workspace membership role, parsed from the wire.
 *
 * ⛔ THIS IS A UX AFFORDANCE, NOT A SECURITY BOUNDARY. Authorisation happens on the server,
 * in `requireWorkspaceRole`, on every request — reads admit all three roles, mutations admit
 * `["agency","client"]` only. Nothing here protects data: hiding a button stops the app
 * OFFERING an action that would come back 403, which is a better experience than a failure
 * dialog, and that is its entire job. Never skip a server call on the strength of this, and
 * never treat a 403 as impossible because the UI was gated.
 *
 * ⚠️ NOT A `@Serializable` ENUM, AND THE DTOs KEEP `role` AS A RAW STRING ON PURPOSE.
 * Server-side the column is a plain `String` with a comment
 * (`role String @default("client") // agency | client | viewer`) — there is no Prisma enum
 * and no TypeScript union anywhere in the codebase, so a fourth value is a schema-level
 * possibility rather than a hypothetical. Decoding an enum directly would throw on an
 * unmodelled value and take out the entire response it arrived in. Parsing through
 * [fromWire] contains the blast radius to "this membership has no privileges".
 */
enum class WorkspaceRole {
    /** Agency operator. Full access, and what support access resolves to. */
    AGENCY,

    /** The ordinary tenant role, and the server's default for a member with no explicit row. */
    CLIENT,

    /** Read-only. The server excludes this role from every mutating route. */
    VIEWER,
    ;

    /**
     * Whether the server would admit this role to a mutating route.
     *
     * Mirrors the `["agency","client"]` allow-list that district write routes pass to
     * `requireWorkspaceRole`. ⚠️ If a route is ever added with a narrower list, this is too
     * coarse for it — check that route specifically rather than widening this.
     */
    val canMutate: Boolean
        get() = this != VIEWER

    companion object {
        /**
         * Parse a wire value, or null if it is absent or not one this client knows.
         *
         * ⛔ FAILS CLOSED BY RETURNING NULL, and every consumer must treat null as "no
         * privileges" rather than falling back to a default. The tempting default is
         * `CLIENT`, because that is what the SERVER falls back to for a member with no
         * explicit row — but the server reaches that conclusion having confirmed the
         * membership exists. Here, null means the opposite: the role could not be
         * established. Assuming CLIENT would show mutation controls to a viewer whose role
         * string arrived misspelled, and every one of those actions would 403.
         *
         * Comparison is case-insensitive: the server lowercases on both write and read, but
         * the column is free text and older rows predate that.
         */
        fun fromWire(raw: String?): WorkspaceRole? = when (raw?.trim()?.lowercase()) {
            "agency" -> AGENCY
            "client" -> CLIENT
            "viewer" -> VIEWER
            else -> null
        }
    }
}

/**
 * Whether to offer mutating controls for this role.
 *
 * Defined as an extension on the NULLABLE type so an unparsed role cannot be dereferenced
 * into a permissive default by accident — `role.allowsMutation()` compiles and answers false
 * when `role` is null, whereas `role!!.canMutate` would crash and `role?.canMutate == true`
 * invites being written as `!= false`, which is true for null.
 */
fun WorkspaceRole?.allowsMutation(): Boolean = this?.canMutate == true
