package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingUploadResult
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminApi
import com.distronode.districtai.core.network.SchedulingAdminEnvelope
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The native scheduling admin, typed: one generic [perform] here, and one extension per op in the
 * `SchedulingAdmin*Ops.kt` files beside it, ported from district-core-swift 7.0.0-beta.1's
 * `SchedulingAdminRepository+*.swift`.
 *
 * ⛔ THE SERVER OWNS THE CATALOG. The scheduler path, the verb, the params schema and the response
 * allowlist all live in `admin-ops.ts`; what this layer owns is the envelope and the collapse of
 * every failure into a [SchedulingAdminError]. Nothing here validates `params` (the catalog's zod
 * schema is the only validator) and nothing here retries: the server already re-mints once on a
 * 401, and 41 of the 64 ops are writes.
 *
 * ⛔ PATH KEYS STAY IN `params`. The route validates the whole object against a schema that
 * REQUIRES them and strips them afterwards, so `slug` and `id` travel in the body like any field.
 *
 * @param reportUnknownOp told about a **400 `unknown_op`**, which means [SchedulingAdminOp] and
 *   the server's catalog have diverged. It is a programmer error a member cannot act on, so the
 *   screen shows the generic sentence and the app decides what else to do about it.
 */
class SchedulingAdminRepository(
    private val api: SchedulingAdminApi,
    private val reportUnknownOp: (SchedulingAdminOp) -> Unit,
) {

    /** Run one catalogued op and hand back its decoded `data`. */
    suspend fun <T> perform(
        workspaceId: String,
        op: SchedulingAdminOp,
        params: JsonObject,
        serializer: KSerializer<T>,
    ): SchedulingAdminResult<T> =
        interpret(api.performSchedulingOp(workspaceId, op, params, serializer), op)

    /**
     * Publish a logo, a banner or the caller's avatar.
     *
     * ⚠️ JPEG, PNG, GIF AND WEBP ONLY, at most [MAX_UPLOAD_BYTES]. The server sniffs the bytes
     * rather than trusting [mimeType], so these checks belong to the picker, not to this call.
     */
    suspend fun upload(
        workspaceId: String,
        target: SchedulingAdminUploadTarget,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): SchedulingAdminResult<SchedulingUploadResult> =
        interpret(api.uploadSchedulingImage(workspaceId, target, fileName, mimeType, bytes), op = null)

    private fun <T> interpret(
        result: ApiResult<SchedulingAdminEnvelope<T>>,
        op: SchedulingAdminOp?,
    ): SchedulingAdminResult<T> = when (result) {
        is ApiResult.Success -> when (val envelope = result.value) {
            is SchedulingAdminEnvelope.Data -> SchedulingAdminResult.Ok(envelope.value)
            is SchedulingAdminEnvelope.Refusal -> SchedulingAdminResult.Failed(
                SchedulingAdminError.Refused(SchedulingAdminFailureCode.forFailure(envelope.failure)),
            )
        }
        is ApiResult.Failure -> SchedulingAdminResult.Failed(errorFor(result, op))
    }

    private fun errorFor(failure: ApiResult.Failure, op: SchedulingAdminOp?): SchedulingAdminError {
        val error = error(failure)
        if (error == SchedulingAdminError.Unknown && op != null && failure.isUnknownOp()) {
            reportUnknownOp(op)
        }
        return error
    }

    companion object {
        /** The upload ceiling, 5 MiB, the route's own. */
        const val MAX_UPLOAD_BYTES: Int = 5 * 1024 * 1024

        /** The image types the upload route accepts. ⚠️ No SVG: it is a script-bearing document. */
        val ACCEPTED_UPLOAD_TYPES: Set<String> = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_CONFLICT = 409
        private const val HTTP_TOO_LARGE = 413
        private const val HTTP_SERVER_ERROR = 500
        private const val CODE_INVALID_PARAMS = "invalid_params"
        private const val CODE_UNKNOWN_OP = "unknown_op"
        private const val CODE_NOT_READY = "scheduling_not_ready"

        /**
         * Our route's refusal, by status and code.
         *
         * ⚠️ THE CODE IS READ FROM `code` AND THEN FROM THE MESSAGE. This route answers
         * `{"error":"invalid_params"}`: the machine key sits where [DistrictApiClient] puts the
         * human sentence, so [ApiResult.HttpFailure.message] is where it arrives.
         *
         * ⚠️ 409 IS CHECKED ON THE CODE, copying `codeForStatus`: a future 409 that is not about
         * provisioning must not tell an operator to set up a feature they already have.
         */
        fun error(failure: ApiResult.Failure): SchedulingAdminError = when (failure) {
            is ApiResult.Unauthorized, is ApiResult.Forbidden -> SchedulingAdminError.Forbidden
            is ApiResult.RateLimited, is ApiResult.RegionsDegraded -> SchedulingAdminError.Unavailable
            is ApiResult.NotFound -> SchedulingAdminError.Unknown
            is ApiResult.NetworkFailure -> SchedulingAdminError.Transport
            is ApiResult.DecodeFailure -> SchedulingAdminError.Decoding
            is ApiResult.HttpFailure -> httpError(failure.status, failure.code ?: failure.message)
        }

        private fun httpError(status: Int, code: String): SchedulingAdminError = when {
            status == HTTP_BAD_REQUEST && code == CODE_INVALID_PARAMS -> SchedulingAdminError.InvalidParams
            status == HTTP_CONFLICT && code == CODE_NOT_READY -> SchedulingAdminError.NotReady
            status == HTTP_TOO_LARGE || status >= HTTP_SERVER_ERROR -> SchedulingAdminError.Unavailable
            else -> SchedulingAdminError.Unknown
        }

        private fun ApiResult.Failure.isUnknownOp(): Boolean =
            this is ApiResult.HttpFailure && status == HTTP_BAD_REQUEST && (code ?: message) == CODE_UNKNOWN_OP
    }
}

/**
 * A params object with the absent keys left out.
 *
 * ⛔ ABSENT, NOT NULL. Every optional field in the catalog is `.optional()`, which accepts a
 * missing key and refuses an explicit `null` on most of them; a patch that sent nulls for the
 * fields it did not touch would be a 400, or worse, a clear.
 */
internal fun schedulingParams(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

internal fun String?.jsonOrNull(): JsonElement? = this?.let(::JsonPrimitive)

internal fun Int?.jsonOrNull(): JsonElement? = this?.let(::JsonPrimitive)

internal fun Boolean?.jsonOrNull(): JsonElement? = this?.let(::JsonPrimitive)

internal fun List<String>?.jsonStringsOrNull(): JsonElement? =
    this?.let { list -> JsonArray(list.map(::JsonPrimitive)) }

internal fun List<Int>?.jsonIntsOrNull(): JsonElement? = this?.let { list -> JsonArray(list.map(::JsonPrimitive)) }
