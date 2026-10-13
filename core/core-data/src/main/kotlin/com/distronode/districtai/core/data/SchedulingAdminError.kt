package com.distronode.districtai.core.data

/**
 * The five recoveries a scheduling admin screen can offer, as the screen cares about them.
 *
 * ⛔ NOT THE SERVER'S VOCABULARY, AND COLLAPSING TO IT IS THE POINT. The route speaks
 * `unavailable` / `conflict` / `not_found` / `rejected` plus a handful of HTTP statuses; several of
 * those want the same sentence and the same recovery. The browser's `admin-fetch.ts` and
 * district-core-swift's `SchedulingAdminFailureCode` perform exactly this collapse, and the three
 * clients have to agree: a person shown two explanations of one refusal depending on the device
 * they picked it up on reports a bug against whichever they saw second.
 *
 * ⚠️ ONLY THREE ARE REACHABLE FROM A `failure` STRING ([UNAVAILABLE], [SLOT_TAKEN], [UNKNOWN]);
 * [FORBIDDEN] and [NOT_READY] come only from a status, because a 200 carrying `{ok:false}` means
 * our route was satisfied and the SCHEDULER refused.
 */
enum class SchedulingAdminFailureCode {
    /** The scheduler did not answer, is rate-limiting us, or failed. Retryable. */
    UNAVAILABLE,

    /** The slot went between rendering it and asking for it: choose another, do not retry. */
    SLOT_TAKEN,

    /** The role gate refused: this member may read and not change. */
    FORBIDDEN,

    /** The workspace has no scheduling tenancy. Somebody has to press Enable. */
    NOT_READY,

    /** Anything the two vocabularies do not name. The honest generic. */
    UNKNOWN,
    ;

    companion object {
        /**
         * A `{ok:false, failure}` body's `failure`, mapped. `instance_unavailable` sits beside
         * `unavailable` because the calendar failure modes reach the same field; copied arm for arm
         * from `admin-fetch.ts`'s `codeForFailure`.
         */
        fun forFailure(failure: String?): SchedulingAdminFailureCode = when (failure) {
            "unavailable", "instance_unavailable" -> UNAVAILABLE
            "slot_taken" -> SLOT_TAKEN
            else -> UNKNOWN
        }
    }
}

/**
 * Everything a [SchedulingAdminRepository] call can fail with.
 *
 * ⛔ [Refused] IS A **200** AND THE OTHERS ARE NOT, which is why it is its own case rather than
 * folded into the code it carries: the request reached Distronode, was authorised, validated, and
 * the SCHEDULER refused. Every other case is our own route declining. The distinction is invisible
 * in the sentence and is the whole answer to "whose outage is this", so it survives to here and
 * is collapsed only at [uiCode].
 */
sealed interface SchedulingAdminError {

    /** HTTP 200 `{ok:false, failure}`: the scheduler refused. */
    data class Refused(val code: SchedulingAdminFailureCode) : SchedulingAdminError

    /**
     * HTTP 400 `invalid_params`. ⛔ The server names fields only and never echoes input; this
     * client does not even read the names, so nothing a member typed can come back to the screen.
     */
    data object InvalidParams : SchedulingAdminError

    /** HTTP 401 or 403: the op's role bar, or no credential at all. */
    data object Forbidden : SchedulingAdminError

    /** HTTP 409 `scheduling_not_ready`: the workspace has no tenancy. */
    data object NotReady : SchedulingAdminError

    /** HTTP 413, 429 or 5xx. Retryable, eventually. */
    data object Unavailable : SchedulingAdminError

    /** A refusal this client does not recognise, including `unknown_op`. */
    data object Unknown : SchedulingAdminError

    /** The request never produced a response (DNS, TLS, timeout, offline). */
    data object Transport : SchedulingAdminError

    /** A 2xx whose payload did not match the model: a newer server than this build. */
    data object Decoding : SchedulingAdminError

    /**
     * The one of five recoveries a screen offers. ⚠️ [Transport] lands on
     * [SchedulingAdminFailureCode.UNAVAILABLE], as the browser does; [Decoding] and [InvalidParams]
     * land on [SchedulingAdminFailureCode.UNKNOWN] because a retry cannot fix a shape.
     */
    val uiCode: SchedulingAdminFailureCode
        get() = when (this) {
            is Refused -> code
            Forbidden -> SchedulingAdminFailureCode.FORBIDDEN
            NotReady -> SchedulingAdminFailureCode.NOT_READY
            Unavailable, Transport -> SchedulingAdminFailureCode.UNAVAILABLE
            InvalidParams, Unknown, Decoding -> SchedulingAdminFailureCode.UNKNOWN
        }
}

/** What a [SchedulingAdminRepository] call answers. */
sealed interface SchedulingAdminResult<out T> {
    data class Ok<out T>(val value: T) : SchedulingAdminResult<T>
    data class Failed(val error: SchedulingAdminError) : SchedulingAdminResult<Nothing>
}

/** The value, or null on a failure. */
fun <T> SchedulingAdminResult<T>.valueOrNull(): T? = (this as? SchedulingAdminResult.Ok)?.value

/** Transform the value of a success; a failure passes through. */
inline fun <T, R> SchedulingAdminResult<T>.map(transform: (T) -> R): SchedulingAdminResult<R> = when (this) {
    is SchedulingAdminResult.Ok -> SchedulingAdminResult.Ok(transform(value))
    is SchedulingAdminResult.Failed -> this
}
