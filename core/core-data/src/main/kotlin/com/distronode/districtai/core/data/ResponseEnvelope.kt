package com.distronode.districtai.core.data

import com.distronode.districtai.core.network.ApiResult

/**
 * Assert the `success` flag that every District envelope carries and nothing used to read.
 *
 * ⛔ WITHOUT THIS, AN HTTP 200 WHOSE BODY IS `{}` DECODES CLEANLY AND CONFIDENTLY LIES. Every
 * response DTO in core-model declares `success: Boolean = false` **and defaults every other
 * field**, which is correct individually — the API omits keys rather than sending explicit
 * nulls, and the lenient shipped parser has to tolerate that — but taken together it means the
 * empty object satisfies the whole contract. Two of those lies are the worst outcomes this
 * client has:
 *
 *   - `WorkspaceListResponse()` reads as **"this account belongs to no workspace at all"**,
 *     which routes a paying customer to an onboarding or checkout dead end: "we could not
 *     look" rendered as "there is nothing".
 *   - `OverviewResponse()` reads as **four confident zeros**. `OverviewMetrics`' own KDoc warns
 *     about this shape and tells callers to branch on the result type instead of on whether the
 *     numbers look populated. The client does branch on the result type. The problem was that
 *     the result type could not tell the two apart, because nothing in the body was required,
 *     so [ApiResult.DecodeFailure] could never fire for a structurally wrong 200.
 *
 * ⛔ THIS IS THE ONE REQUIRED FIELD, AND IT HAS TO BE CHECKED BY HAND RATHER THAN BY THE PARSER.
 * Dropping the `= false` default so kotlinx.serialization enforced presence would be stricter
 * and worse: it would also make the STRICT contract fixtures and the lenient production parser
 * disagree about a field the server does send, and more importantly `ignoreUnknownKeys = true`
 * must stay for forward compatibility (a field added server-side has to degrade to "ignored" on
 * already-installed builds, not "every response fails to parse"). Leniency about EXTRA keys and
 * strictness about THIS key are independent, and this is how the second one is expressed.
 *
 * ⚠️ Reported as [ApiResult.DecodeFailure] on purpose: a 200 that does not affirm success is
 * contract drift, which is exactly what that case means and exactly what should be noisy in
 * debug builds. It is NOT connectivity, so "check your connection" would be wrong, and it is
 * not a retryable server error either.
 *
 * @param name the DTO whose envelope failed, for the diagnostic. ⚠️ The preview deliberately
 *   does NOT include the decoded body: these responses carry customer call transcripts and
 *   contact details, and the only diagnostic fact here is "the flag was not true".
 * @return a failure to return as-is, or null when the envelope is good.
 */
internal fun rejectedEnvelope(name: String, success: Boolean): ApiResult.DecodeFailure? =
    if (success) {
        null
    } else {
        ApiResult.DecodeFailure(
            cause = IllegalStateException("$name did not affirm success=true"),
            bodyPreview = "$name{success=false}",
        )
    }
