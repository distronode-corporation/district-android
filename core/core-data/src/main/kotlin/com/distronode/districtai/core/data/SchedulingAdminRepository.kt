package com.distronode.districtai.core.data

import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminApi
import com.distronode.districtai.core.network.SchedulingAdminEnvelope
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The whole native scheduling admin, as ONE method plus seventy-five spellings of it.
 *
 * ⛔ ONE GENERIC [perform], NOT SEVENTY-FIVE IMPLEMENTATIONS, AND THAT IS A BOUNDARY DECISION
 * RATHER THAN AN ECONOMY. The server owns the catalog: the scheduler path, the HTTP verb, the
 * params schema and the response allowlist all live in `admin-ops.ts`, and every one of them is a
 * thing a second copy here would silently disagree with. What this layer owns is the ENVELOPE and
 * the five-code failure vocabulary — the two pieces of the contract the catalog does not describe
 * and every op shares.
 *
 * ⛔ THE TYPED FUNCTIONS ARE EXTENSIONS IN SIBLING FILES AND ARE NOT A SECOND CATALOG. They add the
 * one thing [perform] cannot know and every caller would otherwise guess — WHICH RESPONSE TYPE GOES
 * WITH WHICH OP — and nothing else. Naming the wrong type at a call site is a runtime
 * [SchedulingAdminFailureCode.UNKNOWN] rather than a compile error, which is the cost [perform]
 * pays for not duplicating 75 schemas; the extensions close that gap without reopening the one
 * [perform] avoids. They mirror iOS's `SchedulingAdminRepository+*.swift` split file for file.
 *
 * ⛔ NOTHING HERE RETRIES, AND THE SERVER'S OWN RETRY IS THE REASON IT MUST NOT START. The server's
 * op route already performs exactly ONE re-mint and ONE re-send on a 401, bounded because an unbounded one
 * rotates the member's scheduler key on every request and a rotation logs out everyone else
 * holding it. A retry loop added here multiplies that, and 46 of the 75 ops are writes.
 *
 * ⛔ AND NOTHING HERE VALIDATES `params`. The catalog's zod schema is the only validator and it
 * runs server-side; a second, laxer copy here would refuse bodies the server accepts (a
 * client-side bug nobody can work around) or accept bodies it rejects (a **400** the user cannot
 * act on). ⚠️ THE PATH KEYS STAY IN THE BODY: `slug` and `id` are BOTH the address and a required
 * member of the op's params schema, and the route strips them AFTER validating, so a client that
 * removed one first gets a 400 naming the field it was being tidy about.
 *
 * ⚠️ A NULL PAIR IS DROPPED, WHICH MEANS "LEAVE ALONE" AND NOT "CLEAR". [schedulingParams] omits a
 * null rather than writing an explicit JSON null; none of the catalog's patch schemas is
 * `.nullable()`, so there is no clear to express and the drop is the whole vocabulary.
 */
class SchedulingAdminRepository(
    private val api: SchedulingAdminApi,
    /**
     * What to do about a **400 `unknown_op`**.
     *
     * ⛔ THAT REFUSAL IS A PROGRAMMER ERROR AND NOTHING A USER CAN ACT ON. It means
     * [SchedulingAdminOp] and `ADMIN_OPS` have diverged — a key renamed or withdrawn on the server
     * — which is invisible to the compiler, because the op crosses the wire as a string.
     *
     * ⚠️ A NO-OP BY DEFAULT RATHER THAN A THROW, WHICH IS A DELIBERATE DIVERGENCE FROM iOS. Its
     * default is an `assertionFailure`, which is a trap in debug and nothing in release; Kotlin has
     * no equivalent that is free in release, and a `check(false)` here would crash a shipped app
     * over a refusal it already reports honestly as [SchedulingAdminFailureCode.UNKNOWN]. The app
     * module wires this to its own debug-only reporter.
     */
    private val reportUnknownOp: (SchedulingAdminOp) -> Unit = {},
) {

    /**
     * Run one catalogued op and decode its `data` into the type the caller names.
     *
     * ⚠️ THE RESPONSE TYPE IS THE CALLER'S CHOICE AND IS NOT CHECKED AGAINST THE OP — see the class
     * doc. Use [com.distronode.districtai.core.model.SchedulingNoContent] for the sixteen ops that
     * answer nothing; it is a real object on the wire (`{"ok":true}` inside `data`) and not an
     * empty body.
     */
    suspend fun <T> perform(
        op: SchedulingAdminOp,
        workspaceId: String,
        params: JsonObject,
        serializer: KSerializer<T>,
    ): SchedulingAdminOutcome<T> =
        when (val result = api.performSchedulingOp(workspaceId, op, params, serializer)) {
            is ApiResult.Success -> when (val envelope = result.value) {
                is SchedulingAdminEnvelope.Data -> SchedulingAdminOutcome.Success(envelope.value)
                is SchedulingAdminEnvelope.Refusal -> SchedulingAdminOutcome.Failure(
                    SchedulingAdminFailureCode.forFailure(envelope.failure),
                    envelope.failure,
                )
            }

            is ApiResult.Failure -> refusal(result, op)
        }

    /**
     * ⚠️ THE `unknown_op` ARM IS THE ONLY PLACE A FAILURE IS REPORTED ANYWHERE BEFORE IT IS
     * RETURNED, and it is reported rather than escalated: the caller still gets an ordinary
     * [SchedulingAdminFailureCode.UNKNOWN], because there is nothing a user could do differently
     * and a louder answer on their screen would be noise.
     */
    private fun refusal(
        failure: ApiResult.Failure,
        op: SchedulingAdminOp,
    ): SchedulingAdminOutcome.Failure {
        if (
            failure is ApiResult.HttpFailure &&
            failure.status == HTTP_BAD_REQUEST &&
            failure.message == SchedulingAdminFailureCode.UNKNOWN_OP_CODE
        ) {
            reportUnknownOp(op)
        }
        return failure.toSchedulingAdminFailure()
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
    }
}

/**
 * Build an op's `params`, DROPPING every pair whose value is null.
 *
 * ⛔ THE DROP IS THE PATCH VOCABULARY AND NOT A TIDY-UP. An omitted key means "leave this alone";
 * an explicit JSON null is a value the catalog's schema would have to accept, and none of them is
 * `.nullable()`. Writing nulls instead of omitting them turns every partial update into a 400.
 *
 * ⚠️ AN EMPTY OBJECT IS A LEGITIMATE RESULT and is what the twenty-odd no-argument ops send. It is
 * sent rather than omitted — see `HttpSchedulingAdminApi`.
 */
internal fun schedulingParams(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

/** A string param, or null to omit the key. */
internal fun textParam(value: String?): JsonElement? = value?.let(::JsonPrimitive)

/** An integer param, or null to omit the key. */
internal fun intParam(value: Int?): JsonElement? = value?.let(::JsonPrimitive)

/**
 * A boolean param, or null to omit the key.
 *
 * ⚠️ `false` IS A VALUE AND NOT AN OMISSION. Passing `false` sends `false`; only null omits. The
 * distinction matters on `users.list`'s `include_archived`, where the server's own default differs
 * from an explicit refusal only in the audit trail.
 */
internal fun boolParam(value: Boolean?): JsonElement? = value?.let(::JsonPrimitive)

/** A string-array param, or null to omit the key. ⚠️ An EMPTY list is sent as `[]`. */
internal fun textsParam(values: List<String>?): JsonElement? =
    values?.let { list -> JsonArray(list.map(::JsonPrimitive)) }

/** An integer-array param, or null to omit the key. ⚠️ An EMPTY list is sent as `[]`. */
internal fun intsParam(values: List<Int>?): JsonElement? =
    values?.let { list -> JsonArray(list.map(::JsonPrimitive)) }

/** An array-of-objects param, or null to omit the key. */
internal fun objectsParam(values: List<JsonObject>?): JsonElement? = values?.let(::JsonArray)
