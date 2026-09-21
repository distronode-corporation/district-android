package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi

/**
 * Telephony analytics, and the workspace's metered usage.
 *
 * ⛔ TWO INDEPENDENT READS BEHIND ONE SCREEN, AND THEY ARE DELIBERATELY NOT COMBINED INTO ONE
 * CALL HERE. They hit different routes with different data layers and they fail independently:
 * analytics is raw SQL over the Call table, usage is a metering groupBy. Folding them into a
 * single `ApiResult` would mean either failure blanking both halves of the screen, which throws
 * away a correct answer the caller already has. The ViewModel runs them in parallel and keeps two
 * sub-states; this layer's job is to keep each one honest on its own.
 *
 * ⚠️ NO CACHING AND NO Pager. Analytics is a fixed-size aggregate per window and usage is one row
 * per month — neither pages, and a stale cache on a figure an operator is reading as current is
 * worse than a second request.
 */
class AnalyticsRepository(private val api: DistrictApi) {

    /**
     * The analytics window.
     *
     * ⚠️ Envelope first — see [rejectedEnvelope]. It matters more here than almost anywhere else
     * in this client: every field of [AnalyticsResponse] has a default, so a `{}` body decodes
     * into a complete, well-formed report reading ZERO CALLS, ZERO CONVERSIONS and a flat trend.
     * That is not an empty state, it is a fabricated one, and an operator has no way to tell it
     * from a genuinely quiet week.
     */
    suspend fun analytics(
        workspaceId: String,
        range: AnalyticsRange,
    ): ApiResult<AnalyticsResponse> =
        when (val result = api.analytics(workspaceId, range)) {
            is ApiResult.Success -> rejectedEnvelope(ANALYTICS_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value)
            is ApiResult.Failure -> result
        }

    /**
     * This month's metered usage, or `null` when nothing has been metered yet.
     *
     * ⛔ THE NULL IS CARRIED THROUGH INSIDE A SUCCESS, NOT CONVERTED INTO A FAILURE OR AN EMPTY
     * OBJECT. `ApiResult.Success(null)` means "we asked, and there is genuinely nothing recorded
     * for this month"; a [ApiResult.Failure] means "we could not find out". Those are different
     * answers and the screen says different things about them — the first is "no usage yet", the
     * second is a failure with a retry. Collapsing them, in either direction, is the mistake this
     * signature exists to prevent: a zeroed row would assert a billing fact that was never read.
     *
     * ⚠️ Deliberately NOT `?: ApiResult.DecodeFailure(...)` the way `ContactsRepository.detail`
     * handles a missing contact. There, a success with no payload is malformed — absence is a
     * 404. Here the null IS the payload, and the server documents it as such.
     */
    suspend fun usage(workspaceId: String): ApiResult<UsageData?> =
        when (val result = api.usage(workspaceId)) {
            is ApiResult.Success -> rejectedEnvelope(USAGE_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value.usage)
            is ApiResult.Failure -> result
        }

    /**
     * The last [months] months, newest first.
     *
     * ⚠️ A SHORT LIST IS NOT AN ERROR. The server skips months with no rows and clamps the
     * request to 1..24, so the result can be shorter than asked for — or empty for a workspace
     * that has never been metered. An empty list here means exactly what it says.
     */
    suspend fun usageHistory(
        workspaceId: String,
        months: Int = DEFAULT_HISTORY_MONTHS,
    ): ApiResult<List<UsageData>> =
        when (val result = api.usageHistory(workspaceId, months)) {
            is ApiResult.Success -> rejectedEnvelope(USAGE_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value.usage)
            is ApiResult.Failure -> result
        }

    private companion object {
        /** Matches what the web console asks for, so the two surfaces show the same span. */
        const val DEFAULT_HISTORY_MONTHS = 3

        const val ANALYTICS_ENVELOPE = "AnalyticsResponse"
        const val USAGE_ENVELOPE = "UsageResponse"
    }
}
