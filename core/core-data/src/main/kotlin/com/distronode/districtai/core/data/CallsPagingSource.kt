package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi

/**
 * The call log, one page at a time.
 *
 * ⚠️ A THIN ADAPTER OVER [OffsetPagingSource], which owns the deduplication and offset-advance rules.
 * They were derived for this feed first and then shared, because every paged endpoint in this API has
 * the same shape and the rules are easy to reimplement subtly wrong — read the ⛔ items on that class
 * before changing anything here.
 *
 * ⚠️ `total = null` IS THE POINT OF THIS ADAPTER. `GET /api/district/calls` returns a BARE ARRAY with
 * no total, no `hasMore` and no cursor, so end-of-list can only be inferred from a short page. Do not
 * invent a total; contrast [ContactsPagingSource], where the endpoint reports one.
 */
@Suppress("FunctionName")
fun CallsPagingSource(
    api: DistrictApi,
    workspaceId: String,
): OffsetPagingSource<CallSummary> = OffsetPagingSource(
    idOf = { it.id },
    fetch = { limit, offset ->
        when (val result = api.calls(workspaceId, limit = limit, offset = offset)) {
            is ApiResult.Success -> ApiResult.Success(OffsetPage(result.value, total = null))
            is ApiResult.Failure -> result
        }
    },
)

/**
 * The CRM, one page at a time.
 *
 * ⚠️ Passes a REAL `total`, unlike the call log, because `GET /api/district/contacts` reports one —
 * so the end of the list is known rather than inferred and no extra empty request is made.
 *
 * ⚠️ Deduplication matters MORE here than for calls, not less: `contacts/bulk-create` inserts an
 * entire import in a single statement, so a large number of rows can appear between two page loads.
 */
@Suppress("FunctionName")
fun ContactsPagingSource(
    api: DistrictApi,
    workspaceId: String,
): OffsetPagingSource<com.distronode.districtai.core.model.Contact> = OffsetPagingSource(
    idOf = { it.id },
    fetch = { limit, offset ->
        when (val result = api.contacts(workspaceId, limit = limit, offset = offset)) {
            // ⛔ THE ENVELOPE DECIDES WHETHER THIS IS AN EMPTY CRM OR A BROKEN RESPONSE. Every
            // field of ContactListResponse defaults, so a `{}` body used to page as "0 contacts,
            // total 0" — a short page, which this source correctly reads as END OF LIST. The user
            // is then shown an empty address book with no error and no retry. See
            // [rejectedEnvelope]. (The calls feed above needs no such check: it is a bare JSON
            // array with no envelope to affirm.)
            is ApiResult.Success -> rejectedEnvelope(CONTACT_LIST_ENVELOPE, result.value.success)
                ?: ApiResult.Success(OffsetPage(result.value.contacts, total = result.value.total))
            is ApiResult.Failure -> result
        }
    },
)

private const val CONTACT_LIST_ENVELOPE = "ContactListResponse"
