package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * Metered platform usage for one workspace.
 *
 * ⛔ ONE ROUTE, TWO RESPONSE TYPES ON THE SAME KEY, WHICH IS WHY THERE ARE TWO ENVELOPES HERE.
 * `GET /api/district/workspace/usage` answers `{success, usage: UsageData|null}` for the current
 * month and `{success, usage: UsageData[]}` when asked for `history=true`. kotlinx.serialization
 * cannot decode both into one field, so the split is structural rather than stylistic — a single
 * type with a `JsonElement` would just move the branch somewhere it is easier to get wrong.
 */

/**
 * The current month.
 *
 * ⛔ [usage] IS NULL WHEN THE MONTH HAS NO ROWS AT ALL, AND NULL IS NOT ZERO. The server builds
 * the shape by iterating the usage rows that exist and returns a JSON `null` when there are none.
 * A screen that rendered that as a column of zeros would state, with the authority of a billing
 * figure, that a workspace sent no messages and placed no calls — when the honest answer is "no
 * usage has been recorded yet". Those are different claims, and the second one is checkable while
 * the first invites a support ticket about a bill.
 *
 * ⚠️ THE DEFAULT IS null RATHER THAN AN EMPTY OBJECT, DELIBERATELY. Every other DTO in this
 * module defaults its payload to a constructed empty value so a `{}` body decodes; here that
 * would erase the distinction the whole type exists to carry. `rejectedEnvelope` is what catches
 * a `{}` body instead — see the repository.
 */
@Serializable
data class UsageResponse(
    val success: Boolean = false,
    val usage: UsageData? = null,
)

/**
 * Several months, newest first.
 *
 * ⚠️ SHORTER THAN THE `months` THAT WAS ASKED FOR IS NORMAL, not an error: the server walks back
 * a month at a time and appends only the months that had rows. It also CLAMPS `months` to 1..24
 * and falls back to 6 for anything non-numeric, so the client never gets to assume it received
 * what it requested.
 */
@Serializable
data class UsageHistoryResponse(
    val success: Boolean = false,
    val usage: List<UsageData> = emptyList(),
)

/**
 * One month's metered totals.
 *
 * ⛔ EVERY METRIC IS NULLABLE, AND ABSENT IS NOT ZERO. A metric with no rows has NO KEY in the
 * response, while a metric measured at zero is present as `0`. Both facts are real and they are
 * different: "we do not meter WhatsApp for this workspace" versus "WhatsApp was metered and it
 * was zero". Defaulting these to `0` would collapse the two and make an unmetered channel look
 * like an idle one.
 *
 * ⛔ AND THEY ARE `Double`, NOT `Int`. `amount` is summed as a float server-side, so call minutes
 * genuinely arrive fractional (1204.25 in the committed fixture). An Int here would fail to
 * decode outright — and if it were ever "fixed" by rounding, it would round a bill.
 *
 * ⚠️ [month] carries a default even though the server always sends it, for the same reason every
 * field in this module does: a strict parser must not fail the whole response over one key, and
 * the envelope check is what asserts the response was real. See `ResponseEnvelope.kt`.
 */
@Serializable
data class UsageData(
    /** `YYYY-MM`. */
    val month: String = "",
    /** The carrier or vendor the row was metered against, e.g. `twilio` or `tavus`. */
    val provider: String? = null,
    val smsOutbound: Double? = null,
    val smsInbound: Double? = null,
    val mmsOutbound: Double? = null,
    val whatsappOutbound: Double? = null,
    val whatsappInbound: Double? = null,
    val callMinutesOutbound: Double? = null,
    val callMinutesInbound: Double? = null,
    val numberCount: Double? = null,
    /**
     * Tavus video-avatar minutes.
     *
     * ⚠️ TRACKING-ONLY BY OWNER DECISION. These are deliberately excluded from the overage
     * calculation server-side, so presenting them beside billable metrics without saying so
     * would imply a charge that is not made.
     */
    val videoMinutes: Double? = null,
    /** ISO-8601, or absent. Carried as a String — nothing here does date arithmetic on it. */
    val lastUpdated: String? = null,
)
