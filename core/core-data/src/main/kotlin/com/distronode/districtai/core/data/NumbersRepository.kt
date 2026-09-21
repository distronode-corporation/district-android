package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.NumberSearchResponse
import com.distronode.districtai.core.model.OwnedNumbersResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi

/**
 * The phone-number marketplace: what is for sale, and what the workspace already has.
 *
 * ⛔ READ ONLY, DELIBERATELY. There is no purchase, release or configure method here and adding
 * one is a product decision rather than a gap — see `NumbersApi`. A number is a recurring carrier
 * charge and a live line; neither belongs one mis-tap away on a phone, and a released number
 * cannot be reclaimed.
 *
 * ⛔ TWO UNRELATED ROUTES BEHIND ONE SCREEN, AND THEY FAIL FOR DIFFERENT REASONS. `search` hits
 * the carrier's inventory API; `owned` merges the carrier's account with the platform's hub
 * records. They are two methods rather than one combined read for the same reason
 * [AnalyticsRepository] keeps its two apart: folding them into a single result means either
 * failure blanking a half of the screen that was answered correctly.
 *
 * ⚠️ NO CACHING. Carrier inventory changes minute to minute and an owned-number list an operator
 * is reading as current must be current. A stale marketplace is worse than a second request.
 */
class NumbersRepository(private val api: DistrictApi) {

    /**
     * Search available inventory.
     *
     * ⛔ A WORKSPACE WITH NO CARRIER CONNECTED ANSWERS **400**, AND THAT IS NOT AN ERROR TO
     * ALARM ABOUT. It is passed through as [ApiResult.HttpFailure] with the server's own
     * sentence ("Messaging provider not configured for workspace") rather than being translated
     * here, because the layer that knows how to render it as an empty state is the UI. What this
     * layer must not do is convert it into an empty success — a client that did would tell an
     * operator the carrier has no numbers in their area code, which is a claim about inventory
     * that was never looked at.
     *
     * ⚠️ Blank filters are normalised to absent. The server distinguishes an omitted parameter
     * from an empty one, and an empty `areaCode` would reach the carrier as a literal filter.
     */
    suspend fun search(
        workspaceId: String,
        areaCode: String? = null,
        country: String? = null,
        type: String? = null,
        provider: String? = null,
    ): ApiResult<NumberSearchResponse> =
        when (
            val result = api.searchNumbers(
                workspaceId = workspaceId,
                areaCode = areaCode?.takeIf { it.isNotBlank() },
                country = country?.takeIf { it.isNotBlank() },
                type = type?.takeIf { it.isNotBlank() },
                provider = provider?.takeIf { it.isNotBlank() },
            )
        ) {
            // ⚠️ Envelope first. Every field of the response has a default, so a `{}` body decodes
            // into a perfectly well-formed "no numbers available" — the exact answer an operator
            // would act on by trying a different area code.
            is ApiResult.Success -> rejectedEnvelope(SEARCH_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value)
            is ApiResult.Failure -> result
        }

    /**
     * The workspace's own numbers, managed and BYOK together.
     *
     * ⛔ THE `partial` FLAG IS CARRIED THROUGH INSIDE THE SUCCESS RATHER THAN CONVERTED INTO A
     * FAILURE. "One carrier did not answer, here is the rest" is a different fact from both "here
     * is everything" and "we could not look", and only the caller can render the middle one — a
     * short list plus a banner. Collapsing it either way loses information the operator needs:
     * upward, it hides real inventory behind an error; downward, it draws an incomplete list as a
     * complete one.
     */
    suspend fun owned(workspaceId: String): ApiResult<OwnedNumbersResponse> =
        when (val result = api.ownedNumbers(workspaceId)) {
            is ApiResult.Success -> rejectedEnvelope(OWNED_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value)
            is ApiResult.Failure -> result
        }

    private companion object {
        const val SEARCH_ENVELOPE = "NumberSearchResponse"
        const val OWNED_ENVELOPE = "OwnedNumbersResponse"
    }
}
