package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.SchedulingUploadResult
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminApi
import com.distronode.districtai.core.network.SchedulingAdminEnvelope
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * What a queued op answers: the scheduler's data, its refusal, or our route's failure.
 *
 * ⚠️ [Value] carries the DECODED payload rather than JSON. The fake never decodes: the caller's
 * serializer is the production half of the contract and the contract tests own it, so a test here
 * names the model it expects to see and the fake hands it back.
 */
sealed interface FakeSchedulingAnswer {
    data class Value(val value: Any?) : FakeSchedulingAnswer
    data class Refused(val failure: String?, val status: Int? = null) : FakeSchedulingAnswer
    data class Failed(val failure: ApiResult.Failure) : FakeSchedulingAnswer
}

/** One op as the fake received it. */
data class FakeSchedulingCall(val workspaceId: String, val op: SchedulingAdminOp, val params: JsonObject)

/** One upload as the fake received it. */
data class FakeSchedulingUpload(
    val workspaceId: String,
    val target: SchedulingAdminUploadTarget,
    val fileName: String,
    val mimeType: String,
    val size: Int,
)

/**
 * A scripted [SchedulingAdminApi].
 *
 * ⚠️ ANSWERS ARE QUEUED PER OP AND THE LAST ONE STICKS. A screen re-reads after every write, so a
 * test that queued exactly one list answer would see its second read fail for a reason the test
 * did not choose. An op with nothing queued answers a [ApiResult.NetworkFailure], the same
 * "nothing scripted" answer the other fakes give, so a forgotten answer reads as offline rather
 * than as an empty success.
 */
class FakeSchedulingAdminApi : SchedulingAdminApi {

    private val answers = mutableMapOf<SchedulingAdminOp, ArrayDeque<FakeSchedulingAnswer>>()

    /** Every op, in the order it was sent. */
    val calls = mutableListOf<FakeSchedulingCall>()

    /** Every upload, in the order it was sent. */
    val uploads = mutableListOf<FakeSchedulingUpload>()

    /** What [uploadSchedulingImage] answers. */
    var uploadAnswer: FakeSchedulingAnswer = FakeSchedulingAnswer.Failed(
        ApiResult.NetworkFailure(IOException("no upload answer queued")),
    )

    /** When set, every op waits for it before answering: a request still in flight. */
    var gate: CompletableDeferred<Unit>? = null

    /** Queue [answers] for [op], in order. */
    fun answer(op: SchedulingAdminOp, vararg answers: FakeSchedulingAnswer) {
        this.answers.getOrPut(op) { ArrayDeque() }.addAll(answers)
    }

    /** Drop whatever is queued for [op], so the next answer queued is the next one given. */
    fun clear(op: SchedulingAdminOp) {
        answers.remove(op)
    }

    /** Queue a successful payload for [op]. */
    fun value(op: SchedulingAdminOp, value: Any?) = answer(op, FakeSchedulingAnswer.Value(value))

    /** Queue a scheduler refusal for [op]. */
    fun refuse(op: SchedulingAdminOp, failure: String?) = answer(op, FakeSchedulingAnswer.Refused(failure))

    /** Queue a transport-level failure for [op]. */
    fun fail(op: SchedulingAdminOp, failure: ApiResult.Failure) =
        answer(op, FakeSchedulingAnswer.Failed(failure))

    /** The calls made for [op], in order. */
    fun callsTo(op: SchedulingAdminOp): List<FakeSchedulingCall> = calls.filter { it.op == op }

    override suspend fun <T> performSchedulingOp(
        workspaceId: String,
        op: SchedulingAdminOp,
        params: JsonObject,
        serializer: KSerializer<T>,
    ): ApiResult<SchedulingAdminEnvelope<T>> {
        calls += FakeSchedulingCall(workspaceId, op, params)
        gate?.await()
        val queue = answers[op]
        val answer = when {
            queue == null || queue.isEmpty() -> FakeSchedulingAnswer.Failed(
                ApiResult.NetworkFailure(IOException("no answer queued for ${op.wire}")),
            )
            queue.size == 1 -> queue.first()
            else -> queue.removeFirst()
        }
        return envelope(answer)
    }

    override suspend fun uploadSchedulingImage(
        workspaceId: String,
        target: SchedulingAdminUploadTarget,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<SchedulingAdminEnvelope<SchedulingUploadResult>> {
        uploads += FakeSchedulingUpload(workspaceId, target, fileName, mimeType, bytes.size)
        gate?.await()
        return envelope(uploadAnswer)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> envelope(answer: FakeSchedulingAnswer): ApiResult<SchedulingAdminEnvelope<T>> =
        when (answer) {
            is FakeSchedulingAnswer.Value -> ApiResult.Success(SchedulingAdminEnvelope.Data(answer.value as T))
            is FakeSchedulingAnswer.Refused ->
                ApiResult.Success(SchedulingAdminEnvelope.Refusal(answer.failure, answer.status))
            is FakeSchedulingAnswer.Failed -> answer.failure
        }
}
