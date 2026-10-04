package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.SchedulingAdminEnvelopeHead
import com.distronode.districtai.core.model.SchedulingAdminSuccess
import com.distronode.districtai.core.model.SchedulingUploadResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The scheduling admin over HTTP: one RPC path and one upload path.
 *
 * ⛔ THE RPC IS FETCHED AS A [JsonElement] AND INTERPRETED HERE, NOT DECODED STRAIGHT INTO THE
 * CALLER'S TYPE, AND "TIDYING" THAT BREAKS IT IN THE QUIET DIRECTION. A failed op is a **200**
 * carrying `{ok:false, failure, status}` — the request reached us, cleared auth, cleared the role
 * bar, validated, and the scheduler refused. Handing those bytes to the caller's serializer is
 * usually an [ApiResult.DecodeFailure] blaming the contract for an outage, and occasionally
 * worse: a type whose every field has a default decodes `{ok:false}` CLEANLY and reports an
 * outage as an empty success.
 *
 * ⛔ AND THE TWO-PASS DECODE IS THE POINT RATHER THAN THE COST. The flag is read first, from a
 * type that models only the flag, and the payload is decoded only once the flag has said there is
 * one. One generic type with a non-nullable `data` cannot decode a refusal at all; one with a
 * nullable `data` cannot tell "the op answered null" from "the op failed".
 *
 * ⚠️ IT DECODES WITH [DistrictApiClient.DEFAULT_JSON], WHICH IS LENIENT ABOUT UNKNOWN KEYS, and
 * that is deliberate for the same reason it is everywhere else in this module: strictness lives
 * in the contract tests, which read the committed fixtures with `ignoreUnknownKeys = false`. A
 * field added server-side has to degrade to "the app ignores it" on already-installed builds, not
 * to "every scheduling screen fails to parse".
 *
 * ⚠️ PUBLIC, UNLIKE EVERY `Http*Api` BESIDE IT, AND THE ASYMMETRY IS STRUCTURAL RATHER THAN AN
 * OVERSIGHT. Those are `internal` because `HttpDistrictApi` composes them by delegation inside
 * this module, so nothing outside ever names one. [SchedulingAdminApi] is deliberately NOT part
 * of that composition — it is a generic RPC rather than a family of typed endpoints — so the
 * graph in `app/` has to construct this itself.
 */
class HttpSchedulingAdminApi(
    private val client: DistrictApiClient,
    /**
     * ⚠️ INJECTED RATHER THAN REACHED FOR, SO A TEST CAN PROVE THE STRICT/LENIENT SPLIT. The
     * default is the one the shipped client uses; nothing in production passes anything else.
     */
    private val json: Json = DistrictApiClient.DEFAULT_JSON,
) : SchedulingAdminApi {

    override suspend fun <T> performSchedulingOp(
        workspaceId: String,
        op: SchedulingAdminOp,
        params: JsonObject,
        serializer: KSerializer<T>,
    ): ApiResult<SchedulingAdminEnvelope<T>> {
        val body = buildJsonObject {
            put("workspaceId", workspaceId)
            put("op", op.wire)
            // ⛔ ALWAYS PRESENT, EVEN WHEN EMPTY. The route defaults a missing `params` to `{}`
            // itself, so the two agree today; sending the empty object makes the agreement a
            // contract rather than a coincidence, and it is the one key a future zod
            // `.strictObject()` on the envelope would start requiring.
            put("params", params)
        }

        return when (
            val result = client.send(
                method = "POST",
                segments = SchedulingAdminPaths.ADMIN,
                serializer = JsonElement.serializer(),
                body = body,
            )
        ) {
            is ApiResult.Success -> interpret(result.value, op.wire, serializer)
            is ApiResult.Failure -> result
        }
    }

    override suspend fun uploadSchedulingImage(
        workspaceId: String,
        target: SchedulingAdminUploadTarget,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<SchedulingAdminEnvelope<SchedulingUploadResult>> {
        val result = client.sendMultipart(
            segments = SchedulingAdminPaths.ADMIN_UPLOAD,
            serializer = JsonElement.serializer(),
            // ⛔ `target` IS A FORM FIELD AND THE WORKSPACE IS NOT. See the `query` argument
            // below and `SchedulingAdminApi.uploadSchedulingImage`; the split is the server's.
            fields = mapOf("target" to target.wire),
            fileName = fileName,
            contentType = mimeType,
            bytes = bytes,
            query = mapOf("workspaceId" to workspaceId),
        )

        return when (result) {
            is ApiResult.Success -> interpret(
                result.value,
                "upload:${target.wire}",
                SchedulingUploadResult.serializer(),
            )

            is ApiResult.Failure -> result
        }
    }

    /**
     * Split a 200 into "the op answered" and "the scheduler refused".
     *
     * ⚠️ NOT A SUSPEND FUNCTION AND IT CALLS NOTHING THAT SUSPENDS, which is why catching
     * [IllegalArgumentException] here cannot swallow a coroutine cancellation. kotlinx's
     * `SerializationException` extends it, and `parseError` in [DistrictApiClient] catches the
     * same type for the same reason.
     *
     * ⛔ NO BODY PREVIEW, WHICH BREAKS THIS MODULE'S HABIT DELIBERATELY. These bodies are customer
     * bookings, attendee answers and contact details, and an [ApiResult.DecodeFailure]'s preview
     * reaches a diagnostic. The op name and the payload's size are enough to tell "sent nothing" from
     * "sent a shape we do not know".
     */
    private fun <T> interpret(
        body: JsonElement,
        opName: String,
        serializer: KSerializer<T>,
    ): ApiResult<SchedulingAdminEnvelope<T>> {
        val head = try {
            json.decodeFromJsonElement(SchedulingAdminEnvelopeHead.serializer(), body)
        } catch (e: IllegalArgumentException) {
            return ApiResult.DecodeFailure(
                cause = e,
                bodyPreview = "SchedulingAdmin{op=$opName,noEnvelope}",
            )
        }

        if (!head.ok) {
            return ApiResult.Success(SchedulingAdminEnvelope.Refusal(head.failure, head.status))
        }

        return try {
            val decoded = json.decodeFromJsonElement(
                SchedulingAdminSuccess.serializer(serializer),
                body,
            )
            ApiResult.Success(SchedulingAdminEnvelope.Data(decoded.data))
        } catch (e: IllegalArgumentException) {
            ApiResult.DecodeFailure(
                cause = e,
                bodyPreview = "SchedulingAdmin{op=$opName,payload=${body.toString().length}b}",
            )
        }
    }
}

/**
 * The two paths this surface uses.
 *
 * ⛔ ONE PATH FOR SIXTY-FOUR OPERATIONS, AND THAT IS THE SECURITY MODEL RATHER THAN AN ECONOMY.
 * The route takes an `op` NAME out of the body and resolves the scheduler path itself, so this
 * client cannot address a scheduler route the server's catalog does not name — including the ones
 * a tenant must never reach (instance credentials shared by every tenancy, the platform API that
 * can delete any tenancy, and the ownership transfer).
 *
 * ⚠️ DECLARED HERE RATHER THAN IN `DistrictPaths`, which is the other per-family path object in
 * this module. Keeping them apart is what lets this whole surface be added without touching the
 * file every other endpoint family shares.
 */
internal object SchedulingAdminPaths {
    private val SCHEDULING = ApiRoots.DISTRICT + "scheduling"

    val ADMIN: List<String> = SCHEDULING + "admin"

    /** ⚠️ Multipart, and the workspace travels in the QUERY. See [SchedulingAdminApi]. */
    val ADMIN_UPLOAD: List<String> = ADMIN + "upload"
}
