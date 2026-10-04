package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * The envelope `POST /api/district/scheduling/admin` wraps every one of its 64 operations in.
 *
 * ⛔ A FAILED OP IS A **200**, AND THAT IS WHY THE FLAG IS READ BEFORE THE PAYLOAD RATHER THAN
 * WITH IT. The server's op route answers `{ok:false, failure, status}` at HTTP 200 on
 * purpose: the request reached Distronode, was authorised, cleared the op's role bar and
 * validated, and the SCHEDULER is what refused. One generic type with a non-nullable `data`
 * cannot decode that body at all, and one with a nullable `data` cannot tell "the op answered
 * null" from "the op failed" — so the flag is its own decode.
 *
 * ⚠️ [status] IS PARSED AND DELIBERATELY UNUSED. It is the SCHEDULER's own HTTP status, carried
 * for a log line; it must never reach the five-code vocabulary a screen branches on, which is
 * keyed on [failure] alone.
 *
 * ⚠️ [ok] CARRIES NO DEFAULT AND THE OTHER TWO DO. A body with no flag is not a refusal and not a
 * success — it is drift, and the only safe answer is to refuse to decode it. [failure] and
 * [status] are absent on every successful envelope, so defaulting them is the shape of the wire
 * rather than a tolerance.
 */
@Serializable
data class SchedulingAdminEnvelopeHead(
    val ok: Boolean,
    /** The scheduler's refusal kind: `unavailable`, `conflict`, `not_found`, `rejected`, … */
    val failure: String? = null,
    /** ⚠️ The scheduler's own status, for diagnostics only. Nothing may branch on it. */
    val status: Int? = null,
)

/**
 * The success envelope, once [SchedulingAdminEnvelopeHead] has said it is one.
 *
 * ⛔ IT MODELS [ok] AS WELL AS [data], AND DROPPING THE FLAG WOULD FAIL THE CONTRACT GATE RATHER
 * THAN MERELY BEING UNTIDY. The gate decodes with `ignoreUnknownKeys = false`, so a key the
 * server sends and this type does not model is a decode failure. The flag is redundant to a
 * caller that already read the head; it is not redundant to the wire.
 *
 * ⚠️ GENERIC OVER THE PAYLOAD, which kotlinx.serialization supports by generating a
 * `serializer(KSerializer<T>)` overload. There is no reflection here and no way to get the
 * payload type wrong silently — naming the wrong one is a decode failure, which is the price of
 * not duplicating 64 schemas on this side of the wire.
 */
@Serializable
data class SchedulingAdminSuccess<T>(
    val ok: Boolean,
    val data: T,
)

/**
 * The body of a NON-2xx from that route: `{error}`, plus `{fields}` on an `invalid_params`.
 *
 * ⛔ FIELD NAMES, NEVER MESSAGES, AND THE SERVER IS WHERE THAT IS ENFORCED. A zod issue's message
 * quotes the offending input straight back out of the API, so `issueFields` flattens the issues
 * to paths and drops the text. Anything rendering [fields] must treat the entries as identifiers
 * to look up, not as prose to show.
 *
 * ⚠️ BOTH PROPERTIES ARE OPTIONAL AND THE BODY IS STILL MEANINGFUL WITHOUT EITHER. A 500 answers
 * `{"error":"Internal Server Error"}` with no fields and a 409 answers
 * `{"error":"scheduling_not_ready"}`; the STATUS is what decides the outcome and this type only
 * ever refines it.
 *
 * ⚠️ [fields] MAY BE EMPTY ON A REAL `invalid_params`. The route caps the set at 20 issues and a
 * body that carried none is still a 400 — an empty list means "we could not say which", never
 * "nothing was wrong".
 */
@Serializable
data class SchedulingAdminErrorBody(
    val error: String? = null,
    val fields: List<String>? = null,
)

/**
 * The `data` of the sixteen ops that answer nothing.
 *
 * ⛔ IT IS NOT AN EMPTY BODY AND MODELLING IT AS ONE FAILS. The catalog's `NO_CONTENT` is
 * `z.unknown().transform(() => ({ ok: true }))`, so a 204 or an empty 2xx from the scheduler is
 * rewritten into a real object before it reaches this client — `{"ok":true,"data":{"ok":true}}`
 * on the wire, an outer flag and an inner one that mean different things. `Unit` is not
 * serializable here, and a class with no fields would decode a `{ok:false}` failure body just as
 * happily, which is the mistake this type exists to make impossible.
 *
 * ⚠️ [ok] IS DECODED STRICTLY RATHER THAN DEFAULTED, and the reason is that it is the only thing
 * here to get wrong. The value is constant by construction — the transform ignores its input — so
 * the field is not information about the request; it is a pin on the server's own shape, and a
 * default would let that shape change without anything noticing.
 */
@Serializable
data class SchedulingNoContent(val ok: Boolean)

/**
 * The catalog's shared list wrapper, `items = (schema) => z.object({ items: z.array(schema) })`.
 *
 * ⛔ DECLARED ONCE AND NOT PER NAMESPACE. Twelve ops answer through it; a private copy beside any
 * one family would give that family an envelope that drifts from the one every other list uses.
 *
 * ⚠️ AND TWO LISTS DO NOT USE IT, WHICH IS THE THING TO CHECK BEFORE REACHING FOR IT.
 * `calendar.connections.calendars.get` declares `{calendars}` by hand, server-side, and reading
 * it through this type decodes nothing and reports an EMPTY LIST, which is a wrong answer rather
 * than an error. `users.list` is the second and a different case again: it answers a BARE ARRAY
 * with no wrapper at all.
 */
@Serializable
data class SchedulingItems<T>(val items: List<T>)
