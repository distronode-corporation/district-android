package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.SchedulingUploadResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * What a **200** from `POST /api/district/scheduling/admin` turned out to be.
 *
 * ⛔ BOTH ARMS ARE HTTP 200 AND THAT IS THE WHOLE REASON THIS TYPE EXISTS. The server's op
 * route answers `{ok:false, failure, status}` at 200 on purpose: the request reached
 * Distronode, was authorised, cleared the op's role bar and validated, and the SCHEDULER is what
 * refused. Folding that into [ApiResult.Failure] would say our route failed; folding it into
 * [ApiResult.Success] with a nullable payload would let a caller read a refusal as an empty
 * answer, which on this surface means "this tenancy has no bookings" for "the scheduler is down".
 *
 * ⚠️ IT IS A TRANSPORT-LEVEL DISTINCTION AND NOT A UI ONE. A screen should branch on a small
 * recovery vocabulary that collapses this arm together with the HTTP refusals, built in core-data
 * beside the first screen that calls this route (none does today), and never switch on [Refusal]
 * directly.
 */
sealed interface SchedulingAdminEnvelope<out T> {

    /** `{ok:true, data}` — the op ran and this is what it answered. */
    data class Data<out T>(val value: T) : SchedulingAdminEnvelope<T>

    /**
     * `{ok:false, failure, status}` — the scheduler refused.
     *
     * @property failure the refusal kind: `unavailable`, `conflict`, `not_found`, `rejected`,
     *   `instance_unavailable` or `slot_taken`. ⚠️ Nullable because a malformed refusal is still a
     *   refusal, and "we could not say which" is a truer answer than a guess.
     * @property status ⚠️ THE SCHEDULER'S OWN HTTP STATUS, CARRIED FOR A LOG LINE AND NOTHING
     *   ELSE. It must never reach the code a screen branches on — that is keyed on [failure].
     */
    data class Refusal(val failure: String?, val status: Int?) : SchedulingAdminEnvelope<Nothing>
}

/**
 * The scheduling admin surface: one RPC and one image upload.
 *
 * ⛔ TWO METHODS FOR SIXTY-FOUR OPERATIONS, AND THE RATIO IS THE POINT. Every read and every
 * write goes through [performSchedulingOp] under an `op` NAME from [SchedulingAdminOp], so the set
 * of scheduler routes this client can address is exactly the server's catalog and cannot be
 * widened from here. The upload exists only because the RPC physically cannot carry its payload:
 * an image cannot travel through a zod-validated params object without a base64 inflation on both
 * sides of a hop that already has a 5 MiB ceiling.
 *
 * ⛔ A SEPARATE INTERFACE FROM [DistrictApi], NOT A SET OF METHODS ON IT. `DistrictApi` composes
 * twenty-odd per-family interfaces whose methods are all ordinary typed endpoints; this one is a
 * generic RPC whose response type is the CALLER's choice, and it carries an envelope no other
 * route on the API sends.
 *
 * ⚠️ NOTHING IN THE APP CALLS THIS YET, AND NOTHING CONSTRUCTS IT. The typed core-data layer that
 * once sat on top of it was deleted unused; the interface and [HttpSchedulingAdminApi] stay
 * because endpoint parity counts their methods and the contract tests decode their DTOs. The
 * first screen that needs the admin surface wires it into `AppContainer` itself.
 *
 * ⛔ NOTHING HERE RETRIES, AND THE SERVER'S OWN RETRY IS THE REASON IT MUST NOT START. The server's
 * op route already performs exactly ONE re-mint and ONE re-send on a 401, bounded because an unbounded one
 * rotates the member's scheduler key on every request and a rotation logs out everyone else
 * holding it. A retry loop on this side multiplies that, and 41 of the 64 ops are writes.
 *
 * ⛔ AND NOTHING HERE VALIDATES `params`. The catalog's zod schema is the only validator and it
 * runs server-side; a second, laxer copy here would refuse bodies the server accepts (a
 * client-side bug nobody can work around) or accept bodies it rejects (a **400** the user cannot
 * act on). `params` is passed through verbatim, PATH KEYS INCLUDED — the catalog's `pathKeys`
 * strip happens server-side AFTER validation, so a client that helpfully removed `slug` before
 * sending gets a 400 naming the very key it was being clever about.
 */
interface SchedulingAdminApi {

    /**
     * Run one catalogued op as the signed-in member and decode its `data`.
     *
     * ⚠️ THE RESPONSE TYPE IS THE CALLER'S CHOICE AND IS NOT CHECKED AGAINST THE OP. Nothing on
     * this side knows that `eventTypes.list` answers a list of event types — the catalog does, and
     * it is not importable from Kotlin. Naming the wrong type is an [ApiResult.DecodeFailure] at
     * runtime rather than a compile error, which is the cost of not duplicating 64 schemas. Close
     * that gap with one typed wrapper per op in core-data rather than calling this from a screen.
     *
     * @param params sent EXACTLY as given, including path keys, and never trimmed. An op that
     *   takes nothing sends `{}` rather than omitting the key: the route defaults a missing
     *   `params` to `{}` itself, so the two agree today, and sending the empty object makes the
     *   agreement a contract rather than a coincidence.
     */
    suspend fun <T> performSchedulingOp(
        workspaceId: String,
        op: SchedulingAdminOp,
        params: JsonObject,
        serializer: KSerializer<T>,
    ): ApiResult<SchedulingAdminEnvelope<T>>

    /**
     * Publish one of the three images the admin surface can set.
     *
     * ⛔ THE WORKSPACE IS A QUERY PARAMETER AND [target] IS A FORM FIELD, WHICH IS A THIRD SHAPE
     * AGAIN — `uploadMedia` puts the workspace in the fields and the desk logo sends no fields at
     * all. The split is the server's: the workspace id has to be readable BEFORE `req.formData()`
     * so the session check can run ahead of a multipart parse of a body up to Cloudflare's 100 MB.
     * A field list copied from the media upload leaves `requireWorkspaceRole` with null while the
     * URL looks perfectly correct.
     *
     * ⛔ THE FILE PART IS NAMED `file` HERE AND IS RENAMED AT THE FAR END. The fork reads `logo`,
     * `banner` and `avatar` respectively; our route translates, which is why the client's own
     * `file` part name is still correct and why a part named after the target is "Missing file
     * field".
     *
     * ⚠️ JPEG, PNG, GIF AND WEBP ONLY — **NOT SVG**, which is the obvious thing to want for a logo
     * and is a script-bearing document. The refusal is a **415** and this client cannot pre-compute
     * it, because the fork sniffs the first 512 bytes rather than trusting the declared type.
     */
    suspend fun uploadSchedulingImage(
        workspaceId: String,
        target: SchedulingAdminUploadTarget,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<SchedulingAdminEnvelope<SchedulingUploadResult>>
}
