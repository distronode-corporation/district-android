package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * The state of a workspace's scheduling tenancy, as this client models it.
 *
 * ⛔ A PARSER RATHER THAN A `@Serializable enum`, AND THIS DIVERGES FROM THE iOS CLIENT ON
 * PURPOSE. `SchedulingTenantStatus.swift` decodes the raw value directly, so an unmodelled state
 * is a decode failure there — argued from the vocabulary being closed and owned by the server.
 * The vocabulary IS closed, but the schema's own comment on `SchedulingTenant.status` says why
 * that is not the whole story: it is "a plain String with a CHECK constraint in SQL rather than a
 * Prisma enum, so adding a state never needs a migration on five databases". A fifth state is
 * therefore a CHEAP server-side change, and a throwing decoder would turn it into a broken
 * Scheduling screen on every already-installed build the moment it shipped — the exact failure
 * `DistrictApiClient.DEFAULT_JSON` sets `ignoreUnknownKeys = true` to avoid for unknown KEYS.
 *
 * ⛔ AND FAILING TO NULL IS NOT THE SAME AS GUESSING. [fromWire] answers null, which the screen
 * renders as its own honest "this app does not recognise that state" presentation — never as
 * `error`, never as "there is nothing here". A lenient fallback that mapped `retiring` onto
 * [ERROR] would be the mistake iOS's note warns about; this one keeps the unknown unknown. Same
 * shape as [WorkspaceRole.fromWire], which fails closed for a related reason.
 *
 * ⛔ `error` IS NOT TERMINAL AND `ready` IS NOT PERMANENT. The hourly reconciler re-runs
 * provisioning for `provisioning` and `error` and re-asserts DNS for `ready`, so a screen must
 * re-read rather than latch. [DISABLED] is the only state the reconciler leaves alone: it means a
 * human, or a workspace deletion, took the tenancy down, and anything that "healed" it would
 * resurrect booking pages somebody switched off.
 */
enum class SchedulingTenantStatus {
    /**
     * The row exists and the multi-system provision has not finished (or has not been re-run
     * since it last failed). ⚠️ NOT a queue position: the enable route's work has already run by
     * the time it answers.
     */
    PROVISIONING,

    /** Booking pages are live at [SchedulingTenant.publicHost]. */
    READY,

    /**
     * The last provision failed. [SchedulingTenant.lastError] carries the operator-facing reason,
     * and the reconciler will try again.
     */
    ERROR,

    /** Switched off deliberately. ⛔ The one state nothing re-provisions. */
    DISABLED,

    ;

    companion object {
        /**
         * The wire value, or null when the server named a state this build has never heard of.
         *
         * ⚠️ CASE-FOLDED, and the fold can only ever widen what resolves. The column is written
         * lowercase by the provisioner and nothing normalises it on read, so a mixed-case value
         * would be contract drift either way — but resolving it is strictly better than
         * rendering "unrecognised" for a state this app models perfectly well.
         */
        fun fromWire(raw: String?): SchedulingTenantStatus? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/**
 * The workspace's scheduling tenancy, as the status route reports it.
 *
 * ⛔ NO CREDENTIAL AND NO CIPHERTEXT REACHES HERE, AND THAT IS THE ROUTE'S `select` RATHER THAN
 * THIS TYPE'S RESTRAINT. `apiKeyEnc` and `webhookSecretEnc` are KMS-wrapped columns the route
 * deliberately does not select; [hasCredentials] is the boolean it derives from the first of
 * them, which is what distinguishes a tenancy that can be talked to from one that cannot. Nothing
 * here should ever grow a field that carries the key itself.
 *
 * ⛔ `status`, `publicHost`, `region` AND `hasCredentials` CARRY NO DEFAULT, WHICH IS UNUSUAL IN
 * THIS PACKAGE AND IS THE POINT. Every other DTO here defaults every field because it is guarded
 * by a `success` envelope that the data layer's `rejectedEnvelope` checks by hand — and this route
 * sends no envelope at all (see [SchedulingStatusResponse]). Required
 * fields are therefore the ONLY thing that can reject a structurally wrong 200, and the columns
 * behind all four are non-nullable server-side, so a missing one is drift rather than a state.
 *
 * ⛔ `bookingUrl` IS NULL FOR EVERY STATUS EXCEPT `ready`, AND IT IS DERIVED SERVER-SIDE RATHER
 * THAN BEING A COLUMN. The route builds it from `publicHost` only when the status is `ready`, so
 * a client that rebuilt it from the host itself would publish a booking link for a tenancy that
 * is still provisioning or has failed. Use this key or offer no link.
 *
 * ⚠️ `region` IS A `String`, unlike [status]. It is `us`, `ca`, `eu` or `apac` today and is not
 * CHECK-constrained; a region this client has not heard of is a label to show rather than a
 * response to reject.
 *
 * ⚠️ `lastReadyAt` IS AN ISO-8601 STRING, NOT AN INSTANT, for the reason every timestamp on this
 * surface is: `NextResponse.json` serialises a `Date` through `JSON.stringify`, and this module
 * owns no date parsing because one decoder strategy would have to be right for every timestamp
 * here and they do not all agree.
 */
@Serializable
data class SchedulingTenant(
    /** ⚠️ The RAW wire value. Parse it with [SchedulingTenantStatus.fromWire]; see that enum. */
    val status: String,
    val publicHost: String,
    val region: String,
    /** ⚠️ Null until the first success, and NOT cleared by a later failure. */
    val lastReadyAt: String? = null,
    /**
     * ⚠️ OPERATOR-FACING AND SHOWN TO ANY MEMBER OF THE WORKSPACE, deliberately: somebody who
     * cannot see why provisioning failed has to open a ticket to learn it. ⛔ It is classified,
     * truncated text and never a raw remote body, because a raw body from the scheduler can
     * contain the once-only API key.
     */
    val lastError: String? = null,
    /** Whether the stored tenant API key exists. ⛔ A PRESENCE BOOLEAN, never the key. */
    val hasCredentials: Boolean,
    /** ⛔ Present only on `ready`. See the class doc. */
    val bookingUrl: String? = null,
) {
    /** ⚠️ Not a stored property, so it is never encoded and cannot add a key the server never sent. */
    val tenantStatus: SchedulingTenantStatus?
        get() = SchedulingTenantStatus.fromWire(status)
}

/**
 * `GET /api/district/scheduling/status?workspaceId=` — what the Scheduling card renders.
 *
 * ⛔ THERE IS NO `success` ENVELOPE ON THIS ROUTE, AND THAT IS NOT AN OVERSIGHT TO PAPER OVER.
 * `NextResponse.json({eligible, canManage, tenant})` is the whole body; there is no flag to
 * check, so the repository over it must not run this through the shared `rejectedEnvelope`
 * guard, and this type must not declare a `success` the server never sends — the
 * strict contract gate would fail on the added key. The two required fields below are what reject
 * `{}` in its place, and they have to: with defaults, an empty body would decode into a confident
 * "this workspace is not admitted to scheduling", which is a claim about somebody's product
 * entitlement made from a response that said nothing at all.
 *
 * ⛔ `tenant == null` IS THE LEGACY STATE AND IT IS A STATE, NEVER AN ERROR. It means this
 * workspace has no `SchedulingTenant` row — the ordinary condition of every workspace before
 * anyone presses Enable — and rendering it as a failure would tell an operator something is
 * broken when nothing is. It is also not the same question as [eligible]: a workspace can be
 * admitted and unprovisioned, or provisioned and later removed from the allowlist, so both
 * answers are sent.
 *
 * ⚠️ READABLE BY `viewer` TOO. [canManage] is the server telling the client which buttons to draw
 * (`agency` or `client`) rather than leaving it to re-derive that from a role string — and, like
 * [WorkspaceRole], it is a UX affordance and not the boundary: the enable route enforces the role
 * itself.
 */
@Serializable
data class SchedulingStatusResponse(
    /**
     * Whether the FEATURE admits this workspace at all, which decides between "Enable scheduling"
     * and a sentence explaining that it is not offered here.
     */
    val eligible: Boolean,
    /** Whether this member may press Enable. ⛔ Not a security boundary. */
    val canManage: Boolean,
    /** ⛔ null is the legacy, never-provisioned state. See the class doc. */
    val tenant: SchedulingTenant? = null,
)

/**
 * `POST /api/district/scheduling/enable` — the answer to pressing Enable.
 *
 * ⛔ IT ANSWERS **202**, AND THE 202 IS ABOUT THE STATE IT LEAVES RATHER THAN ABOUT QUEUEING.
 * Provisioning is a multi-system operation that has already run by the time this body is written:
 * the row may be `ready`, or `error` with a reason, and either way the client's next move is to
 * re-read the status. `DistrictApiClient` treats every 2xx as success
 * (`Response.isSuccessful` is 200..299, not `== 200`), so nothing special is needed for it —
 * which is worth stating because a client that only accepted 200 would report every successful
 * enable as a failure, with the tenancy provisioned and the screen claiming otherwise.
 *
 * ⛔ `ok: false` IS A SUCCESSFUL DECODE CARRYING AN ERROR SENTENCE, NOT AN
 * `ApiResult.Failure`. The provision refused or failed,
 * the server said so in a well-formed 202, and [error] is the operator-facing text (the same
 * string stored in `lastError`). Promoting it to a transport-level failure would throw away the
 * one sentence that says what went wrong. The refusals that ARE failures are the ones that never
 * reached the provisioner: **403** (this workspace is not on the scheduling allowlist) and
 * **429** (five enables in an hour, per workspace).
 *
 * ⚠️ NO `success` KEY HERE EITHER — the flag is spelled `ok`. Same rule as the status route:
 * nothing may run this through `rejectedEnvelope`, and [ok] and [status] carry no default so a
 * `{}` body is rejected rather than read as a silent, reasonless refusal.
 */
/**
 * `POST /api/district/scheduling/handoff` — a signed-in browser, for sixty seconds.
 *
 * ⛔ THE WHOLE BODY IS A CREDENTIAL, AND THAT IS WHY IT HAS EXACTLY TWO FIELDS. [url] carries a
 * 43-character single-use code in its query string, good for 60 seconds and one redemption, so
 * every extra key on this response is one more thing sitting in a client's logs for a minute. The
 * website's own doc says the shape must stay `{url, expiresIn}`; nothing here should grow.
 *
 * ⛔ [url] CARRIES NO DEFAULT, FOR THE SAME REASON [SchedulingStatusResponse.eligible] CARRIES
 * NONE. This route sends no `success` envelope either, so a required field is the only thing that
 * can reject a structurally wrong 200 — and with a default, `{}` would decode into an EMPTY URL
 * that the repository would then be asked to verify and a browser to open. A missing url is drift,
 * never a state.
 *
 * ⚠️ [expiresIn] DOES CARRY ONE, AND THE ASYMMETRY IS DELIBERATE RATHER THAN AN OVERSIGHT. Nothing
 * in this app branches on it: the code is minted at the moment of the tap and spent immediately, so
 * the client never has a reason to compare it against a clock. ⛔ AND UNLIKE EVERY OTHER SCHEDULING
 * RESPONSE, THIS ONE IS NOT PINNED BY A COMMITTED FIXTURE (adding one needs a matching change on the
 * server's fixture generator), so the strict contract gate cannot tell
 * anybody when the shape moves. A tolerant field is the only protection an already-installed build
 * has if the server stops sending it; a required one would turn that into "scheduling is broken"
 * on every phone.
 */
@Serializable
data class SchedulingHandOffResponse(
    /** ⛔ Never logged, never stored, never cached. See the class doc. */
    val url: String,
    /** Seconds the code remains redeemable. ⚠️ Diagnostic only — nothing branches on it. */
    val expiresIn: Int = 0,
)

@Serializable
data class SchedulingEnableResponse(
    val ok: Boolean,
    /**
     * ⛔ A `String`, AND THE ASYMMETRY WITH [SchedulingTenant.status] IS ONLY THAT THIS ONE'S
     * VOCABULARY IS WIDER. This key forwards `ProvisionResult.status`, whose TypeScript type is
     * `SchedulingTenantStatus | "skipped"` — one provisioner type serves both provisioning and
     * deprovisioning. The enable path cannot reach `"skipped"` today, and [tenantStatus] answers
     * null for it rather than pretending it is a tenancy state.
     */
    val status: String,
    /**
     * The allocated booking host, when there is one. ⚠️ Present on a FAILED provision too,
     * whenever the host had already been claimed — the host is allocated once and reused forever,
     * so it survives a failure.
     */
    val publicHost: String? = null,
    /**
     * ⚠️ Null on success. The provisioner's classified message otherwise; never a credential and
     * never a raw remote body.
     */
    val error: String? = null,
) {
    /**
     * [status] parsed into the tenancy vocabulary, or null if it is a value outside it.
     *
     * ⚠️ null MEANS "NOT A TENANCY STATE", WHICH IS NOT AN ERROR. Today the only such value the
     * server can produce is `"skipped"`; a caller branching on this should fall back to re-reading
     * the status route, which is the advice for every outcome of this call anyway.
     *
     * ⚠️ Not a stored property, so it is never encoded — which is what keeps it from adding a key
     * the server never sent and failing the strict gate's key-set walk.
     */
    val tenantStatus: SchedulingTenantStatus?
        get() = SchedulingTenantStatus.fromWire(status)
}
