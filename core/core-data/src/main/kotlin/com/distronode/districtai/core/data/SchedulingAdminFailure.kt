package com.distronode.districtai.core.data

import com.distronode.districtai.core.network.ApiResult

/**
 * The five things that can go wrong on the scheduling admin surface, as a SCREEN cares about them.
 *
 * ⛔ NOT THE SERVER'S VOCABULARY, AND COLLAPSING TO IT IS THE POINT. The route speaks
 * `unavailable` / `conflict` / `not_found` / `rejected` plus a handful of HTTP statuses; several
 * of those want the same sentence and the same recovery, and one status (403) wants a different
 * sentence from every `failure` kind. `admin-fetch.ts` performs exactly this collapse for the
 * browser and `SchedulingAdminFailureCode.swift` for iOS — all three clients have to agree, because
 * a person shown two different explanations of one refusal depending on which device they picked
 * it up on will report a bug against whichever one they saw second.
 *
 * ⛔ NO SENTENCES HERE. core-data is locale-free and the UI layer owns the copy, the same way
 * [ApiResult] refuses to invent a message. What this type carries is the DECISION — which of five
 * recoveries applies — and nothing a translator would need to touch.
 *
 * ⚠️ ONLY THREE OF THE FIVE ARE REACHABLE FROM A `failure` STRING ([UNAVAILABLE], [SLOT_TAKEN],
 * [UNKNOWN]); [FORBIDDEN] and [NOT_READY] come only from a status. That is not an oversight in the
 * mapping: a 200 carrying `{ok:false}` means our route was satisfied and the SCHEDULER refused, and
 * neither "you may not" nor "there is no tenancy" can be decided that far down.
 */
enum class SchedulingAdminFailureCode {
    /** The scheduler did not answer, is rate-limiting us, or failed. Retryable. */
    UNAVAILABLE,

    /**
     * The booking slot was taken between rendering it and asking for it. ⚠️ The only code whose
     * recovery is "choose something else" rather than "try again".
     */
    SLOT_TAKEN,

    /** The role gate refused: this member may read the surface and not change it. */
    FORBIDDEN,

    /**
     * The workspace has no scheduling tenancy. ⛔ Not a fault and not retryable — somebody has to
     * press Enable, which is `SchedulingRepository.enable` and a different screen entirely.
     */
    NOT_READY,

    /**
     * Anything the vocabularies do not name.
     *
     * ⚠️ THE HONEST GENERIC, AND IT MUST STAY REACHABLE. An unrecognised code rendered as a
     * specific one is how a client starts lying about a server it no longer understands.
     */
    UNKNOWN,

    ;

    companion object {
        /**
         * A `{ok:false, failure}` body's `failure` string, mapped to a code.
         *
         * ⚠️ `instance_unavailable` SITS BESIDE `unavailable` BECAUSE TWO VOCABULARIES REACH THIS
         * FIELD: the platform client's four kinds (`unavailable` / `conflict` / `not_found` /
         * `rejected`) and the calendar failure modes (`instance_unavailable`, `slot_taken`). Naming
         * both spellings of "our scheduler did not answer" costs one line and stops the more
         * specific one falling through to [UNKNOWN]. Copied arm for arm from `admin-fetch.ts`'s
         * `codeForFailure`.
         */
        fun forFailure(failure: String?): SchedulingAdminFailureCode = when (failure) {
            "unavailable", "instance_unavailable" -> UNAVAILABLE
            "slot_taken" -> SLOT_TAKEN
            else -> UNKNOWN
        }

        /**
         * The HTTP status this route refused with, mapped to a code.
         *
         * ⚠️ THE `error` STRING IS CHECKED BEFORE THE STATUS FOR 409, copying `codeForStatus`. The
         * route answers 409 for exactly one reason today, but `conflict` is a generic shape and a
         * future 409 that is not about provisioning would otherwise tell an operator to go set up a
         * feature they already have.
         *
         * ⚠️ 413 IS GROUPED WITH 429 AND 5xx AND IS **NOT** IN THE BROWSER'S `codeForStatus`, where
         * it falls through to `unknown`. Stated rather than left to be discovered: on this route a
         * 413 is a params object over 5 MiB, which no user action shortens and no retry fixes, so
         * neither answer is clearly right. If the clients are ever made to agree, agree in ONE
         * place — this comment, `SchedulingAdminRepository.swift` and `admin-fetch.ts` are the set.
         */
        fun forStatus(status: Int, code: String?): SchedulingAdminFailureCode = when {
            status == HTTP_FORBIDDEN || status == HTTP_UNAUTHORIZED -> FORBIDDEN
            status == HTTP_CONFLICT && code == NOT_READY_CODE -> NOT_READY
            status == HTTP_PAYLOAD_TOO_LARGE ||
                status == HTTP_TOO_MANY_REQUESTS ||
                status >= HTTP_SERVER_ERROR -> UNAVAILABLE

            else -> UNKNOWN
        }

        /** The `error` a 409 on this route carries. Checked before the status; see [forStatus]. */
        const val NOT_READY_CODE: String = "scheduling_not_ready"

        /** The `error` a 400 carries when this client sent an op the server does not have. */
        const val UNKNOWN_OP_CODE: String = "unknown_op"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_CONFLICT = 409
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * What one catalogued op answered.
 *
 * ⛔ A TYPE OF ITS OWN RATHER THAN [ApiResult], AND THE REASON IS THE **200** THAT IS A REFUSAL.
 * `ApiResult` has no arm for "the request succeeded and the far end declined", and the two
 * plausible ways of forcing one are both wrong: folding it into [ApiResult.Failure] says OUR route
 * failed, and folding it into [ApiResult.Success] hands a screen a payload that was never sent.
 *
 * ⚠️ AND IT IS NOT A `Result<T>`. The failure side here is a closed five-value decision, not a
 * `Throwable`; an exception type would invite a `catch` that treats "you may not" and "the socket
 * died" as one thing, which is exactly what the five codes exist to keep apart.
 */
sealed interface SchedulingAdminOutcome<out T> {

    /** The op ran and this is what it answered. */
    data class Success<out T>(val value: T) : SchedulingAdminOutcome<T>

    /**
     * The op did not run, or ran and was refused.
     *
     * @property code the one of five recoveries a screen should offer.
     * @property detail ⛔ DIAGNOSTIC ONLY AND NEVER SHOWN. It carries the wire spelling that
     *   produced [code] — a status, a `failure` kind, an exception class name — so a report can say
     *   which arm fired. It is not a sentence, it is not localised, and it may name server
     *   internals; anything rendering it is a bug.
     */
    data class Failure(
        val code: SchedulingAdminFailureCode,
        val detail: String? = null,
    ) : SchedulingAdminOutcome<Nothing>
}

/** The decoded value, or null on any failure. Convenience for call sites that do not branch. */
fun <T> SchedulingAdminOutcome<T>.valueOrNull(): T? =
    (this as? SchedulingAdminOutcome.Success)?.value

/**
 * Rewrap a successful outcome's value, leaving a failure alone.
 *
 * ⛔ THE TWENTY-ODD LIST OPS UNWRAP THEIR `items` THROUGH THIS RATHER THAN AT EVERY CALL SITE, AND
 * THE ALTERNATIVE IS THE REASON. A screen that reached for `.items` itself would have to branch on
 * the outcome first, and the natural shortcut for that is [valueOrNull] — which turns "we could
 * not look" into "there is nothing". Elsewhere that conflation sends a paying customer to a
 * checkout page; on this surface it tells an operator their calendar is empty.
 *
 * ⚠️ `internal`, so the shape of the wrapper stays a detail of this module.
 */
internal inline fun <T, R> SchedulingAdminOutcome<T>.map(
    transform: (T) -> R,
): SchedulingAdminOutcome<R> = when (this) {
    is SchedulingAdminOutcome.Success -> SchedulingAdminOutcome.Success(transform(value))
    is SchedulingAdminOutcome.Failure -> this
}

/**
 * Collapse a transport failure into the five-code vocabulary.
 *
 * ⚠️ [ApiResult.NetworkFailure] LANDS ON [SchedulingAdminFailureCode.UNAVAILABLE], which is what
 * the browser does too: "the request never left" and "the far end is down" are indistinguishable
 * from here and want the same sentence. [ApiResult.DecodeFailure] lands on
 * [SchedulingAdminFailureCode.UNKNOWN] because a retry cannot fix a shape.
 *
 * ⚠️ [ApiResult.Unauthorized] IS FOLDED INTO [SchedulingAdminFailureCode.FORBIDDEN] RATHER THAN
 * GETTING ITS OWN CODE, matching the status mapping's `401 || 403`. Both mean this member cannot
 * do it now; the sign-in prompt is the session layer's job and is driven by the token coordinator,
 * not by one refused op.
 */
internal fun ApiResult.Failure.toSchedulingAdminFailure(): SchedulingAdminOutcome.Failure =
    when (this) {
        is ApiResult.Unauthorized -> SchedulingAdminOutcome.Failure(
            SchedulingAdminFailureCode.FORBIDDEN,
            "401",
        )

        is ApiResult.Forbidden -> SchedulingAdminOutcome.Failure(
            SchedulingAdminFailureCode.FORBIDDEN,
            "403",
        )

        is ApiResult.NotFound -> SchedulingAdminOutcome.Failure(
            SchedulingAdminFailureCode.UNKNOWN,
            "404",
        )

        is ApiResult.RateLimited -> SchedulingAdminOutcome.Failure(
            SchedulingAdminFailureCode.UNAVAILABLE,
            "429",
        )

        is ApiResult.RegionsDegraded -> SchedulingAdminOutcome.Failure(
            SchedulingAdminFailureCode.UNAVAILABLE,
            "regions_degraded",
        )

        // ⚠️ `message` IS WHERE THE ROUTE'S `error` CODE ARRIVES, not a sentence: `ApiErrorEnvelope`
        // maps the body's `error` onto it, and on this route that field carries
        // `scheduling_not_ready`, `unknown_op` or `invalid_params` rather than prose. That is why
        // the status mapping can read a code out of it at all.
        is ApiResult.HttpFailure -> SchedulingAdminOutcome.Failure(
            SchedulingAdminFailureCode.forStatus(status, message),
            "$status:$message",
        )

        is ApiResult.NetworkFailure -> SchedulingAdminOutcome.Failure(
            SchedulingAdminFailureCode.UNAVAILABLE,
            cause.javaClass.simpleName,
        )

        // ⛔ NO BODY PREVIEW CARRIED FORWARD. These bodies are customer bookings, transcripts and
        // meeting notes; the class name of the cause is the whole diagnostic budget.
        is ApiResult.DecodeFailure -> SchedulingAdminOutcome.Failure(
            SchedulingAdminFailureCode.UNKNOWN,
            cause.javaClass.simpleName,
        )
    }
