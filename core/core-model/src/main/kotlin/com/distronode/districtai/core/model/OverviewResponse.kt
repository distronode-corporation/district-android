package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/overview` — the dashboard landing screen in one round trip.
 *
 * ⛔ ALSO A ROUTE THAT EXISTS FOR THIS CLIENT. The web dashboard's landing page is an async
 * Server Component that queries Prisma directly, so none of its four tiles were reachable
 * over HTTP.
 *
 * ⛔ DO NOT REBUILD THIS FROM `/api/district/analytics`. That route looks equivalent and is
 * not: it is WINDOW-SCOPED (7d default, 90d ceiling) while three of these four metrics are
 * ALL-TIME. Its `totalCalls` counts the window, its `avgDuration` averages only calls
 * inside it, and it has no contacts count at all. Only [OverviewMetrics.callsThisWeek]
 * coincides with `analytics?timeRange=7d`. Substituting it would show numbers that quietly
 * disagree with the browser.
 */
@Serializable
data class OverviewResponse(
    val success: Boolean = false,
    /**
     * The workspace that actually answered.
     *
     * Echoed so a request that omitted `workspaceId` learns what the server resolved. Worth
     * asserting against the workspace the UI believes is active — a mismatch means the
     * local selection has drifted from what the server accepts.
     */
    val workspaceId: String? = null,
    /**
     * The caller's EFFECTIVE role in that workspace, which is not always a membership row:
     * support access resolves to "agency" even with no membership at all. Gate the UI on this,
     * not on the role cached from the workspace list. Parse via [WorkspaceRole.fromWire].
     */
    val role: String? = null,
    val metrics: OverviewMetrics = OverviewMetrics(),
    /**
     * `metrics.avgDuration` pre-formatted, e.g. "3m 12s" or "45s".
     *
     * ⛔ USE THIS RATHER THAN FORMATTING [OverviewMetrics.avgDuration] LOCALLY. Two duration
     * formats already ship in this product and they disagree on the same input: the
     * dashboard tile omits a zero minutes component ("45s") while a call row always emits
     * one ("0m 45s" — see [CallSummary.duration]). The server sends the tile's own label so
     * this client cannot pick the wrong one of the two.
     */
    val avgDurationLabel: String = "0s",
    /**
     * The 8 most recent calls, byte-identical to a row of `GET /api/district/calls`.
     *
     * ⚠️ ONE DTO SERVES BOTH SURFACES, which is why both routes share a single server-side
     * mapping and why the contract suite asserts the two responses are equal. If they ever
     * diverge, this field stops decoding.
     *
     * [CallSummary.status] is already the DISPLAY status: a call whose terminal webhook was
     * lost reads "no-answer" rather than staying live forever, and only a genuinely live
     * call keeps "in-progress"/"ringing" — which is how a Live badge is derived without a
     * separate flag.
     */
    val recentCalls: List<CallSummary> = emptyList(),
)

/**
 * The four KPI tiles, in the order the browser renders them.
 *
 * ⚠️ ALL FOUR DEFAULT TO ZERO, and zero is a legitimate value for a new workspace — so it
 * is NOT evidence the request worked. Branch on the request's result type, never on whether
 * these look populated. The server has its own version of this trap: these metrics read
 * RLS-guarded tables, and outside a workspace transaction they come back as four confident
 * zeros with HTTP 200 rather than an error.
 */
@Serializable
data class OverviewMetrics(
    /** Cumulative, all time. */
    val totalCalls: Int = 0,
    /** Calls created in the last 7 days. */
    val callsThisWeek: Int = 0,
    val totalContacts: Int = 0,
    /** SECONDS, averaged over completed calls, all time. See [OverviewResponse.avgDurationLabel]. */
    val avgDuration: Int = 0,
)
