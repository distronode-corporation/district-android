package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingUploadResult
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminApi
import com.distronode.districtai.core.network.SchedulingAdminEnvelope
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A [SchedulingAdminApi] that records what it was asked and answers what it was told to.
 *
 * ⛔ IT DECODES THE PAYLOAD WITH THE CALLER'S OWN SERIALIZER RATHER THAN HOLDING A TYPED VALUE,
 * WHICH IS THE ONLY WAY THESE TESTS CAN CHECK THE OP AND THE SHAPE AT ONCE. A fake that returned a
 * pre-built object would prove that a typed wrapper passes a value through and nothing about
 * whether it named the right response type — and naming the wrong type is the one mistake the
 * generic `perform` cannot catch at compile time.
 *
 * ⚠️ THE DECODER IS LENIENT, matching the shipped client. Strictness belongs to the contract tests
 * in core-model, which read the committed fixtures; a strict decoder here would make every minimal
 * stub below a fixture that has to be maintained.
 */
internal class FakeSchedulingAdminApi : SchedulingAdminApi {

    var lastWorkspaceId: String? = null
        private set
    var lastOp: SchedulingAdminOp? = null
        private set
    var lastParams: JsonObject? = null
        private set
    var callCount: Int = 0
        private set

    /** What `data` should contain on the next call. Ignored when [failure] or [refusal] is set. */
    var payloadJson: String = "{}"

    /** A transport-level failure to answer with, instead of an envelope. */
    var failure: ApiResult.Failure? = null

    /** A `{ok:false, failure}` refusal to answer with, at HTTP 200. */
    var refusal: String? = null

    /** The scheduler's own status on a [refusal]. ⚠️ Diagnostic only, exactly as on the wire. */
    var refusalStatus: Int? = null

    var lastUploadTarget: SchedulingAdminUploadTarget? = null
        private set
    var lastUploadFileName: String? = null
        private set
    var lastUploadMimeType: String? = null
        private set
    var lastUploadBytes: ByteArray? = null
        private set

    /** What the download route's `Location` should say. */
    var downloadLocation: String = "https://storage.test/rec.mp4?sig=abc"

    override suspend fun <T> performSchedulingOp(
        workspaceId: String,
        op: SchedulingAdminOp,
        params: JsonObject,
        serializer: KSerializer<T>,
    ): ApiResult<SchedulingAdminEnvelope<T>> {
        callCount++
        lastWorkspaceId = workspaceId
        lastOp = op
        lastParams = params
        return answer(serializer)
    }

    override suspend fun uploadSchedulingImage(
        workspaceId: String,
        target: SchedulingAdminUploadTarget,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<SchedulingAdminEnvelope<SchedulingUploadResult>> {
        callCount++
        lastWorkspaceId = workspaceId
        lastUploadTarget = target
        lastUploadFileName = fileName
        lastUploadMimeType = mimeType
        lastUploadBytes = bytes
        return answer(SchedulingUploadResult.serializer())
    }

    override suspend fun schedulingRecordingDownloadUrl(
        workspaceId: String,
        recordingId: String,
    ): ApiResult<String> {
        callCount++
        lastWorkspaceId = workspaceId
        return failure ?: ApiResult.Success(downloadLocation)
    }

    private fun <T> answer(serializer: KSerializer<T>): ApiResult<SchedulingAdminEnvelope<T>> {
        failure?.let { return it }
        refusal?.let {
            return ApiResult.Success(SchedulingAdminEnvelope.Refusal(it, refusalStatus))
        }
        return ApiResult.Success(
            SchedulingAdminEnvelope.Data(LENIENT.decodeFromString(serializer, payloadJson)),
        )
    }

    private companion object {
        val LENIENT = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
