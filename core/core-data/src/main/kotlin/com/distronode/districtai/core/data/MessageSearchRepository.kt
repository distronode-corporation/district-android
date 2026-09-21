package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.MESSAGE_SEARCH_MIN_QUERY_LENGTH
import com.distronode.districtai.core.model.MessageSearchHit
import com.distronode.districtai.core.model.MessageThreadTarget
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.InboxExtrasApi

/**
 * Finding a message, and turning a message id into somewhere to navigate.
 *
 * ⛔ SEPARATE FROM [InboxRepository] ONLY BECAUSE ITS API INTERFACE IS. See `ExtraPaths` in
 * core-network: `DistrictApi` is the one guaranteed merge conflict while several people are adding
 * endpoints, so these two reads hang off their own interface and therefore their own repository.
 */
class MessageSearchRepository(private val api: InboxExtrasApi) {

    /**
     * Search every message in the workspace.
     *
     * ⛔ THE FLOOR IS ENFORCED HERE AND MEASURED ON THE TRIMMED QUERY, because the server trims
     * before it measures. A client that measured the untrimmed value would send requests the route
     * answers empty, and one that did not measure at all would search on every keystroke.
     *
     * ⚠️ A SHORT QUERY IS AN EMPTY RESULT, NOT A FAILURE. Nothing here reports "type more" as an
     * error: the screen shows the conversation list until there is something to search for.
     */
    suspend fun search(workspaceId: String, query: String): ApiResult<MessageSearchResults> {
        val trimmed = query.trim()
        if (trimmed.length < MESSAGE_SEARCH_MIN_QUERY_LENGTH) {
            return ApiResult.Success(MessageSearchResults(hits = emptyList(), truncated = false))
        }
        return when (val result = api.searchMessages(workspaceId, trimmed)) {
            is ApiResult.Success -> {
                rejectedEnvelope(SEARCH_ENVELOPE, result.value.success)?.let { return it }
                val body = result.value
                // ⚠️ BOUND TO A LOCAL RATHER THAN READ TWICE, and it is the compiler's rule rather
                // than a style choice: `limit` is a `var`-free but PUBLIC property of a class in
                // :core:core-model, so Kotlin refuses to smart-cast it to `Int` after the null
                // check — another module could, in principle, ship a custom getter. Reading it once
                // is also the honest thing: two reads of a property that is allowed to change
                // between them is what the rule is warning about.
                val reportedLimit = body.limit
                ApiResult.Success(
                    MessageSearchResults(
                        hits = body.results,
                        // ⚠️ A FULL PAGE MEANS OLDER MATCHES EXIST AND ARE NOT SHOWN, and the cap
                        // is the server's own reported one rather than a constant here. ⛔ An
                        // ABSENT `limit` is the short-query branch, which cannot be truncated.
                        truncated = reportedLimit != null && body.results.size >= reportedLimit,
                    ),
                )
            }
            is ApiResult.Failure -> result
        }
    }

    /**
     * Resolve one message id to the thread it belongs to.
     *
     * ⛔ THE WORKSPACE IS PASSED EVEN THOUGH THE ROUTE WOULD FALL BACK to the caller's active
     * workspace. On a multi-tenant account that fallback is a different tenant from the one the
     * push named, and the answer would then 404 for a message that exists.
     *
     * ⚠️ 404 AND 409 ARE BOTH REFUSALS RATHER THAN RETRIES — a row in another workspace, a row that
     * does not exist, and a row with no addressable counterpart. They arrive as
     * `ApiResult.NotFound` and `ApiResult.HttpFailure(409)` and are left that way: a push that
     * cannot resolve should open the Inbox, not retry.
     */
    suspend fun threadFor(workspaceId: String, messageId: String): ApiResult<MessageThreadTarget> =
        when (val result = api.messageThread(workspaceId, messageId)) {
            is ApiResult.Success -> {
                rejectedEnvelope(THREAD_ENVELOPE, result.value.success)
                    ?: if (result.value.thread.threadKey.isBlank()) {
                        ApiResult.DecodeFailure(
                            cause = IllegalStateException("$THREAD_ENVELOPE carried no threadKey"),
                            bodyPreview = "$THREAD_ENVELOPE{threadKey=blank}",
                        )
                    } else {
                        ApiResult.Success(result.value.thread)
                    }
            }
            is ApiResult.Failure -> result
        }

    private companion object {
        const val SEARCH_ENVELOPE = "MessageSearchResponse"
        const val THREAD_ENVELOPE = "MessageThreadResponse"
    }
}

/**
 * What a search answered.
 *
 * ⚠️ [truncated] IS CARRIED RATHER THAN INFERRED BY A SCREEN, for the reason the conversation
 * list's `partial` flag exists: a full page of results is a DIFFERENT statement from a complete
 * one, and drawing thirty rows without saying so presents an incomplete answer as a whole one.
 */
data class MessageSearchResults(
    val hits: List<MessageSearchHit>,
    val truncated: Boolean,
)
