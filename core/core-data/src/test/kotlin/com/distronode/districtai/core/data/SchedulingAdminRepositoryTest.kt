package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope, the five-code vocabulary, and the params builder they all go through.
 *
 * ⛔ THE THREE OUTCOMES THIS SURFACE HAS ARE "IT ANSWERED", "THE SCHEDULER REFUSED AT HTTP 200" AND
 * "OUR ROUTE REFUSED", and the middle one is the reason [SchedulingAdminOutcome] exists rather than
 * [ApiResult]. Folding the 200 refusal into a transport failure says OUR route failed; folding it
 * into a success hands a screen a payload that was never sent.
 */
class SchedulingAdminRepositoryTest {

    private val api = FakeSchedulingAdminApi()
    private val repository = SchedulingAdminRepository(api)

    @Test
    fun `a successful op answers its decoded payload`() = runTest {
        api.payloadJson = """{"ok":true}"""

        val outcome = repository.perform(
            SchedulingAdminOp.ME_AVATAR_DELETE,
            "ws-1",
            JsonObject(emptyMap()),
            SchedulingNoContent.serializer(),
        )

        assertEquals(SchedulingNoContent(ok = true), outcome.valueOrNull())
        assertEquals(SchedulingAdminOp.ME_AVATAR_DELETE, api.lastOp)
        assertEquals("ws-1", api.lastWorkspaceId)
    }

    @Test
    fun `a scheduler refusal maps its failure kind into the five-code vocabulary`() = runTest {
        // ⚠️ TWO SPELLINGS OF "OUR SCHEDULER DID NOT ANSWER" REACH THIS FIELD: the platform
        // client's `unavailable` and the calendar layer's `instance_unavailable`. Naming both costs
        // one line and stops the more specific one falling through to UNKNOWN.
        listOf("unavailable", "instance_unavailable").forEach { kind ->
            api.refusal = kind
            val outcome = repository.perform(
                SchedulingAdminOp.ME_GET,
                "ws-1",
                JsonObject(emptyMap()),
                SchedulingNoContent.serializer(),
            )
            assertEquals(
                SchedulingAdminOutcome.Failure(SchedulingAdminFailureCode.UNAVAILABLE, kind),
                outcome,
            )
        }

        // ⛔ THE ONE CODE WHOSE RECOVERY IS "CHOOSE SOMETHING ELSE" RATHER THAN "TRY AGAIN".
        api.refusal = "slot_taken"
        assertEquals(
            SchedulingAdminFailureCode.SLOT_TAKEN,
            failureCode(SchedulingAdminOp.BOOKINGS_RESCHEDULE),
        )

        // ⚠️ AND THE HONEST GENERIC. `rejected` is a real kind the platform client sends and this
        // client deliberately does not name it — an unrecognised code rendered as a specific one is
        // how a client starts lying about a server it no longer understands.
        api.refusal = "rejected"
        assertEquals(SchedulingAdminFailureCode.UNKNOWN, failureCode(SchedulingAdminOp.ME_GET))

        api.refusal = null
    }

    @Test
    fun `a refusal with no failure kind is unknown rather than a crash`() {
        // ⚠️ A MALFORMED REFUSAL IS STILL A REFUSAL. The head's `failure` is nullable precisely so
        // "we could not say which" is expressible, and it must not become a specific code.
        assertEquals(SchedulingAdminFailureCode.UNKNOWN, SchedulingAdminFailureCode.forFailure(null))
        assertEquals(SchedulingAdminFailureCode.UNKNOWN, SchedulingAdminFailureCode.forFailure(""))
    }

    @Test
    fun `403 and 401 both mean this member cannot do it now`() = runTest {
        api.failure = ApiResult.Forbidden("forbidden")
        assertEquals(SchedulingAdminFailureCode.FORBIDDEN, failureCode(SchedulingAdminOp.ME_GET))

        // ⚠️ FOLDED TOGETHER RATHER THAN GIVEN ITS OWN CODE, matching the status mapping's
        // `401 || 403`. The sign-in prompt is the session layer's job, driven by the token
        // coordinator, not by one refused op.
        api.failure = ApiResult.Unauthorized(reason = null)
        assertEquals(SchedulingAdminFailureCode.FORBIDDEN, failureCode(SchedulingAdminOp.ME_GET))
    }

    @Test
    fun `a 401 or 403 that arrives as a bare status is still forbidden, never unknown`() = runTest {
        // ⚠️ The client maps both statuses to their own results before they get here, so this is
        // the defensive half: a refusal that reaches the status mapping raw must not be read as a
        // fault an operator could retry their way past.
        api.failure = ApiResult.HttpFailure(status = 403, message = "forbidden")
        assertEquals(SchedulingAdminFailureCode.FORBIDDEN, failureCode(SchedulingAdminOp.ME_GET))

        api.failure = ApiResult.HttpFailure(status = 401, message = "unauthorized")
        assertEquals(SchedulingAdminFailureCode.FORBIDDEN, failureCode(SchedulingAdminOp.ME_GET))
    }

    @Test
    fun `a 409 scheduling_not_ready is a state and not a fault`() = runTest {
        // ⛔ CHECKED ON THE `error` STRING BEFORE THE STATUS. The route answers 409 for exactly one
        // reason today, but `conflict` is a generic shape and a future 409 that is not about
        // provisioning would otherwise tell an operator to go set up a feature they already have.
        api.failure = ApiResult.HttpFailure(409, "scheduling_not_ready")
        assertEquals(SchedulingAdminFailureCode.NOT_READY, failureCode(SchedulingAdminOp.ME_GET))

        api.failure = ApiResult.HttpFailure(409, "something_else")
        assertEquals(SchedulingAdminFailureCode.UNKNOWN, failureCode(SchedulingAdminOp.ME_GET))
    }

    @Test
    fun `413, 429 and 5xx are all retryable-eventually`() = runTest {
        // ⚠️ 413 IS GROUPED HERE AND IS **NOT** IN THE BROWSER'S `codeForStatus`, where it falls
        // through to unknown. On this route a 413 is a params object over 5 MiB, which no user
        // action shortens and no retry fixes, so neither answer is clearly right; the clients are
        // deliberately allowed to differ and the divergence is recorded rather than discovered.
        listOf(413, 429, 500, 502, 503).forEach { status ->
            api.failure = ApiResult.HttpFailure(status, "x")
            assertEquals(
                "HTTP $status is unavailable",
                SchedulingAdminFailureCode.UNAVAILABLE,
                failureCode(SchedulingAdminOp.ME_GET),
            )
        }

        api.failure = ApiResult.RateLimited("slow down")
        assertEquals(SchedulingAdminFailureCode.UNAVAILABLE, failureCode(SchedulingAdminOp.ME_GET))

        api.failure = ApiResult.RegionsDegraded("partial", listOf("eu"))
        assertEquals(SchedulingAdminFailureCode.UNAVAILABLE, failureCode(SchedulingAdminOp.ME_GET))
    }

    @Test
    fun `a dead socket is unavailable and an unreadable shape is not`() = runTest {
        // ⚠️ "THE REQUEST NEVER LEFT" AND "THE FAR END IS DOWN" ARE INDISTINGUISHABLE FROM HERE and
        // want the same sentence, which is what the browser does too.
        api.failure = ApiResult.NetworkFailure(IOException("boom"))
        assertEquals(SchedulingAdminFailureCode.UNAVAILABLE, failureCode(SchedulingAdminOp.ME_GET))

        // ⛔ AND A DECODE FAILURE IS UNKNOWN BECAUSE A RETRY CANNOT FIX A SHAPE.
        api.failure = ApiResult.DecodeFailure(IllegalStateException("no"), "preview")
        assertEquals(SchedulingAdminFailureCode.UNKNOWN, failureCode(SchedulingAdminOp.ME_GET))

        api.failure = ApiResult.NotFound("gone")
        assertEquals(SchedulingAdminFailureCode.UNKNOWN, failureCode(SchedulingAdminOp.ME_GET))
    }

    @Test
    fun `a failure's detail names the arm and never quotes the body`() = runTest {
        // ⛔ DIAGNOSTIC ONLY AND NEVER SHOWN. `DecodeFailure` carries a body preview and these
        // bodies are customer bookings, transcripts and meeting notes; only the cause's class name
        // survives into the outcome.
        api.failure = ApiResult.DecodeFailure(
            IllegalStateException("no"),
            "Booking{attendee=dana@contract.test}",
        )
        val outcome = repository.perform(
            SchedulingAdminOp.BOOKINGS_LIST,
            "ws-1",
            JsonObject(emptyMap()),
            SchedulingNoContent.serializer(),
        ) as SchedulingAdminOutcome.Failure

        assertEquals("IllegalStateException", outcome.detail)
        assertTrue(
            "no fragment of the body may survive",
            outcome.detail?.contains("dana") != true,
        )
    }

    @Test
    fun `an unknown op is reported once and still answers an ordinary unknown`() = runTest {
        // ⛔ A PROGRAMMER ERROR AND NOTHING A USER CAN ACT ON: this enum and `ADMIN_OPS` have
        // diverged. It is reported rather than escalated, because there is nothing a user could do
        // differently and a louder answer on their screen would be noise.
        val reported = mutableListOf<SchedulingAdminOp>()
        val repo = SchedulingAdminRepository(api) { reported += it }

        api.failure = ApiResult.HttpFailure(400, "unknown_op")
        val outcome = repo.perform(
            SchedulingAdminOp.TEAMS_MEMBERS_PATCH,
            "ws-1",
            JsonObject(emptyMap()),
            SchedulingNoContent.serializer(),
        )

        assertEquals(listOf(SchedulingAdminOp.TEAMS_MEMBERS_PATCH), reported)
        assertEquals(
            SchedulingAdminFailureCode.UNKNOWN,
            (outcome as SchedulingAdminOutcome.Failure).code,
        )
    }

    @Test
    fun `an ordinary 400 does not report an unknown op`() = runTest {
        val reported = mutableListOf<SchedulingAdminOp>()
        val repo = SchedulingAdminRepository(api) { reported += it }

        api.failure = ApiResult.HttpFailure(400, "invalid_params")
        repo.perform(
            SchedulingAdminOp.ME_PATCH,
            "ws-1",
            JsonObject(emptyMap()),
            SchedulingNoContent.serializer(),
        )

        assertTrue("only unknown_op is a divergence", reported.isEmpty())
    }

    @Test
    fun `the default reporter is a no-op rather than a crash`() = runTest {
        // ⚠️ A DELIBERATE DIVERGENCE FROM iOS, whose default is an `assertionFailure` — a trap in
        // debug and nothing in release. Kotlin has no equivalent that is free in release, and a
        // `check(false)` here would crash a shipped app over a refusal it already reports honestly.
        api.failure = ApiResult.HttpFailure(400, "unknown_op")

        val outcome = repository.perform(
            SchedulingAdminOp.ME_GET,
            "ws-1",
            JsonObject(emptyMap()),
            SchedulingNoContent.serializer(),
        )

        assertEquals(
            SchedulingAdminFailureCode.UNKNOWN,
            (outcome as SchedulingAdminOutcome.Failure).code,
        )
    }

    @Test
    fun `valueOrNull answers null on every failure and the value on a success`() = runTest {
        api.payloadJson = """{"ok":true}"""
        val success: SchedulingAdminOutcome<SchedulingNoContent> =
            SchedulingAdminOutcome.Success(SchedulingNoContent(ok = true))
        val failure: SchedulingAdminOutcome<SchedulingNoContent> =
            SchedulingAdminOutcome.Failure(SchedulingAdminFailureCode.UNAVAILABLE)

        assertEquals(SchedulingNoContent(ok = true), success.valueOrNull())
        assertNull(failure.valueOrNull())
    }

    @Test
    fun `the params builder DROPS a null pair rather than writing an explicit null`() {
        // ⛔ THE DROP IS THE PATCH VOCABULARY AND NOT A TIDY-UP. An omitted key means "leave this
        // alone"; an explicit JSON null is a value the catalog's schema would have to accept, and
        // none of them is `.nullable()`. Writing nulls turns every partial update into a 400.
        val built = schedulingParams(
            "kept" to textParam("value"),
            "dropped" to textParam(null),
            "number" to intParam(7),
            "noNumber" to intParam(null),
            "flag" to boolParam(false),
            "noFlag" to boolParam(null),
            "texts" to textsParam(listOf("a", "b")),
            "noTexts" to textsParam(null),
            "ints" to intsParam(listOf(1, 2)),
            "noInts" to intsParam(null),
            "objects" to objectsParam(listOf(buildJsonObject { put("x", 1) })),
            "noObjects" to objectsParam(null),
        )

        assertEquals(
            setOf("kept", "number", "flag", "texts", "ints", "objects"),
            built.keys,
        )

        // ⛔ `false` IS A VALUE AND NOT AN OMISSION. Only null omits.
        assertEquals(JsonPrimitive(false), built["flag"])
        assertEquals(JsonPrimitive(7), built["number"])
    }

    @Test
    fun `an empty params object is a legitimate result and is not collapsed away`() {
        // ⚠️ IT IS WHAT THE TWENTY-ODD NO-ARGUMENT OPS SEND, and it is sent rather than omitted so
        // the agreement with the route's own `?? {}` default is a contract rather than a
        // coincidence.
        assertEquals(JsonObject(emptyMap()), schedulingParams())
    }

    @Test
    fun `an empty collection is sent, because an empty list is an instruction`() {
        // ⚠️ `reminders: []` CLEARS THEM WHILE `reminders` ABSENT LEAVES THEM ALONE, and the same
        // distinction governs a webhook's `fields`. Only null omits.
        val built = schedulingParams(
            "reminders" to intsParam(emptyList()),
            "fields" to textsParam(emptyList()),
        )
        assertEquals(setOf("reminders", "fields"), built.keys)
    }

    private suspend fun failureCode(op: SchedulingAdminOp): SchedulingAdminFailureCode {
        val outcome = repository.perform(
            op,
            "ws-1",
            JsonObject(emptyMap()),
            SchedulingNoContent.serializer(),
        )
        return (outcome as SchedulingAdminOutcome.Failure).code
    }
}
