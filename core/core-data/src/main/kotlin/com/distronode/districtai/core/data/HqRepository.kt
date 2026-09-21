package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.HqConfirmAction
import com.distronode.districtai.core.model.HqConfirmRequest
import com.distronode.districtai.core.model.HqPendingWrite
import com.distronode.districtai.core.model.HqPromptRequest
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi

/**
 * District HQ: ask a question, and apply a change the operator approved.
 *
 * ⛔ THE TWO-STEP IS THE PRODUCT, NOT A CEREMONY. The model may only PROPOSE a write; nothing is
 * applied until [confirm] is called with the proposal echoed back. This layer's job is to keep that
 * guarantee honest in both directions — a proposal that cannot be confirmed is rejected as
 * malformed rather than shown, and a confirmation that came back describing a DIFFERENT action than
 * the one sent is rejected rather than reported as done.
 *
 * ⛔ NEITHER CALL IS IDEMPOTENT AND NEITHER IS RETRIED HERE. See the ⛔ on
 * [com.distronode.districtai.core.network.HqApi]. A failed [confirm] in particular may already have
 * executed, so repeating it is an operator's decision and never this layer's.
 */
class HqRepository(private val api: DistrictApi) {

    /**
     * Ask District HQ something.
     *
     * @param history prior turns, oldest first. ⚠️ Sent WHOLE. The server keeps the last 6 and
     *   drops the rest, so trimming here would only mean two places deciding the same thing and
     *   disagreeing after a server change.
     */
    suspend fun ask(
        workspaceId: String,
        prompt: String,
        history: List<HqTurn>,
    ): ApiResult<HqAnswer> {
        val request = HqPromptRequest(
            workspaceId = workspaceId,
            prompt = prompt,
            history = history,
        )
        return when (val result = api.hqPrompt(request)) {
            // ⚠️ Envelope first — see [rejectedEnvelope]. A `{}` body would otherwise decode as a
            // successful turn whose answer happens to be empty, which is a fabricated silence.
            is ApiResult.Success -> rejectedEnvelope(PROMPT_ENVELOPE, result.value.success)
                ?: answerOf(result.value.answer, result.value.needsConfirmation, result.value.pendingWrite)
            is ApiResult.Failure -> result
        }
    }

    /**
     * ⛔ `needsConfirmation` WITHOUT A `pendingWrite` IS A MALFORMED RESPONSE, NOT AN ANSWER. It
     * means the server said "the operator must confirm this" and then sent nothing to confirm. The
     * tempting fallback — show the answer, drop the flag — is the worst option available: the model
     * has just told the operator, in prose, that it has proposed a change, and the screen would
     * offer no way to apply it and no indication that anything is missing. The operator reads that
     * as "done".
     *
     * ⚠️ The mirror case is deliberately NOT an error: a `pendingWrite` present WITHOUT the flag is
     * treated as a proposal anyway. The payload is the substantive half and the flag is the
     * summary of it, so trusting the payload fails toward asking rather than toward acting.
     */
    private fun answerOf(
        answer: String,
        needsConfirmation: Boolean,
        pendingWrite: HqPendingWrite?,
    ): ApiResult<HqAnswer> = when {
        pendingWrite != null -> ApiResult.Success(HqAnswer(answer, pendingWrite))
        needsConfirmation -> ApiResult.DecodeFailure(
            IllegalStateException("needsConfirmation was set with no pendingWrite to confirm"),
            "$PROMPT_ENVELOPE{needsConfirmation=true,pendingWrite=null}",
        )
        else -> ApiResult.Success(HqAnswer(answer, null))
    }

    /**
     * Apply a proposed write.
     *
     * ⛔ TAKES THE WHOLE [HqPendingWrite], NOT A TOOL NAME AND SOME ARGUMENTS. The confirmed action
     * has to be byte-identical to the one whose [HqPendingWrite.summary] the operator read; a
     * signature that let a caller assemble its own pair would make substituting one possible, and
     * the substitution would be invisible because the summary is what was on screen.
     *
     * ⛔ AND THE SERVER'S ECHO IS CHECKED. The route echoes `tool` back precisely so the client can
     * prove the applied action is the proposed one. A mismatch is reported as contract drift rather
     * than as success: "we applied something, but not what you approved" has no honest UI, and
     * treating it as done is how a delete gets attributed to a persona edit.
     *
     * ⚠️ `executed: false` is NOT a failure of this call — the request was handled and the write was
     * declined (a view-only role, or a tool that refused internally). It is carried through so the
     * caller can say "not applied" rather than "failed"; see [HqConfirmation.executed].
     */
    suspend fun confirm(
        workspaceId: String,
        pendingWrite: HqPendingWrite,
    ): ApiResult<HqConfirmation> {
        val request = HqConfirmRequest(
            workspaceId = workspaceId,
            // ⛔ Echoed VERBATIM. See the ⛔ on HqConfirmRequest.
            confirm = HqConfirmAction(tool = pendingWrite.tool, args = pendingWrite.args),
        )
        return when (val result = api.hqConfirm(request)) {
            is ApiResult.Success -> rejectedEnvelope(CONFIRM_ENVELOPE, result.value.success)
                ?: substitutedAction(pendingWrite.tool, result.value.tool)
                ?: ApiResult.Success(
                    HqConfirmation(tool = result.value.tool, executed = result.value.executed),
                )
            is ApiResult.Failure -> result
        }
    }

    /**
     * ⚠️ Compared exactly, with no trimming or case folding. Both sides of this comparison are
     * machine-generated identifiers from a fixed catalog of ten names — a difference in case or
     * whitespace is drift, not a formatting variation to be smoothed over.
     */
    private fun substitutedAction(sent: String, echoed: String): ApiResult.DecodeFailure? =
        if (sent == echoed) {
            null
        } else {
            ApiResult.DecodeFailure(
                IllegalStateException("confirm echoed a different tool than the one approved"),
                "$CONFIRM_ENVELOPE{approved=$sent,applied=$echoed}",
            )
        }

    private companion object {
        const val PROMPT_ENVELOPE = "HqPromptResponse"
        const val CONFIRM_ENVELOPE = "HqConfirmResponse"
    }
}

/**
 * One turn's answer, and the change it proposes.
 *
 * ⚠️ [pendingWrite] non-null means NOTHING HAS BEEN WRITTEN. The answer text usually says so too,
 * because the system prompt requires it — but the affordance has to come from this field rather than
 * from reading the prose.
 */
data class HqAnswer(
    val answer: String,
    val pendingWrite: HqPendingWrite?,
)

/**
 * The outcome of a confirmed write.
 *
 * ⚠️ [executed] false means the server handled the request and DECLINED the write. Rendering that
 * as applied is precisely the lie the flag exists to prevent.
 */
data class HqConfirmation(
    val tool: String,
    val executed: Boolean,
)
