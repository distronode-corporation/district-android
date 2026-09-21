package com.distronode.districtai.core.network

import kotlinx.serialization.Serializable

/**
 * Every error body this API can produce, as one lenient shape.
 *
 * ⛔ THERE ARE THREE ENVELOPES, NOT ONE, AND WHICH ONE ARRIVES DEPENDS ON WHICH LAYER FAILED
 * RATHER THAN ON WHICH ROUTE WAS CALLED. Surveyed against the handlers:
 *
 *   `{ success: false, error }`   a route's OWN 400 or 500 — analytics, timeline, hq,
 *                                 workspace/usage
 *   `{ error }`                   the SHARED AUTH GUARD's 401/403/404, returned verbatim by
 *                                 every district route (`if (error) return error`), plus
 *                                 `rateLimitedResponse`'s 429
 *   `{ error, code }`             the newer `apiError` / `apiServerError` helpers —
 *                                 conversations' 500 sends `code: "INTERNAL_ERROR"`, and
 *                                 workspace/list's 503 sends `code: "REGIONS_DEGRADED"` plus
 *                                 a `degradedRegions` array
 *
 * ⛔ SO A SINGLE STRICT `{success, error}` DTO WOULD THROW ON EVERY 401, 403 AND 404 IN THE
 * ENTIRE API, on hq's 429, and on conversations' 500 — turning an authorisation failure into
 * a parse crash. Every field here is therefore optional with a default, and this type is
 * decoded on a LENIENT `Json`. That is not laziness; the union of three shapes has no
 * required field in common except `error`, and even that is absent from a body that is not
 * JSON at all (an edge 502 HTML page, a proxy timeout).
 *
 * ⚠️ NOTE THE ASYMMETRY WITH THE SUCCESS DTOs. Success shapes are pinned by committed
 * fixtures and a two-sided contract gate, because they carry data the app renders. Error
 * shapes are not, because they carry a string to show the user and a code to branch on — the
 * cost of a missed field is a less specific message, not wrong data.
 */
@Serializable
data class ApiErrorEnvelope(
    /**
     * Absent on a bare-`{error}` body, present and false on a route's own failure. Never
     * true in an error response.
     */
    val success: Boolean? = null,
    /**
     * The human-readable message. ⚠️ WRITTEN FOR AN OPERATOR, and safe to show: the server
     * routes 5xx text through `apiServerError`, which logs the real exception and returns a
     * generic sentence precisely so stack traces and DSNs never cross the API boundary. Do
     * not assume it is localised — it is English, server-side.
     */
    val error: String? = null,
    /** Machine-readable, e.g. "INTERNAL_ERROR", "REGIONS_DEGRADED". Only the newer helpers send it. */
    val code: String? = null,
    /**
     * The workspace's Stripe subscription status, on a 402 `subscription_inactive`.
     *
     * ⛔ MODELLED BECAUSE A COMMITTED FIXTURE PROVES IT EXISTS, NOT BECAUSE ANYTHING READS IT.
     * `district-dial-subscription.json` is decoded by the contract gate with
     * `ignoreUnknownKeys = false`, and this key is why that decode failed the first time it ran —
     * which is the gate doing its job. The choice it forced was between modelling the field and
     * loosening the gate, and loosening it would have meant the next added key went unnoticed too.
     *
     * ⛔ AND NOTHING MAY BRANCH ON IT. Which Stripe states count as delinquent is the SERVER's
     * decision (`BLOCKED_SUBSCRIPTION_STATES`, which already includes `past_due` as a deliberate
     * strictness choice that could be reversed); a client that decided for itself would disagree
     * with the guard the moment that set changed, and would disagree silently. The branch is
     * `code`, which is stable. This is diagnostic only.
     *
     * ⚠️ THE 409 OVERAGE REFUSAL HAS A SIBLING FIELD (`policy: "hard_cap"`) THAT IS DELIBERATELY
     * NOT MODELLED HERE. No committed fixture pins that body, so adding the field would be a guess
     * about a shape nothing checks — and the shipped parser is lenient, so its absence costs
     * nothing in the field. Model it when a fixture pins it, the same way this one was.
     */
    val status: String? = null,
    /**
     * Regions whose databases did not answer. Only present alongside
     * `code: "REGIONS_DEGRADED"`.
     */
    val degradedRegions: List<String> = emptyList(),
) {
    companion object {
        /** Shown when the body was empty, truncated, or not JSON — so the UI always has a sentence. */
        const val FALLBACK_MESSAGE: String = "Something went wrong. Please try again."

        /** The `code` that marks an incomplete answer rather than a genuine absence of data. */
        const val CODE_REGIONS_DEGRADED: String = "REGIONS_DEGRADED"
    }
}
