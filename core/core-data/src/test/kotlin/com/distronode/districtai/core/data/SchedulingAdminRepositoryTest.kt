package com.distronode.districtai.core.data

import com.distronode.districtai.core.auth.ReauthReason
import com.distronode.districtai.core.model.SchedulingUploadResult
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import com.distronode.districtai.core.network.testing.FakeSchedulingAdminApi
import com.distronode.districtai.core.network.testing.FakeSchedulingAnswer
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The envelope and the failure collapse, which every typed op shares.
 *
 * ⛔ THE COLLAPSE IS COPIED ARM FOR ARM FROM district-core-swift's `SchedulingAdminRepository` and
 * the browser's `admin-fetch.ts`, so each arm is pinned here: a member shown two explanations of
 * one refusal on two devices reports a bug against whichever they saw second.
 */
class SchedulingAdminRepositoryTest {

    private val api = FakeSchedulingAdminApi()
    private val unknownOps = mutableListOf<SchedulingAdminOp>()
    private val repository = SchedulingAdminRepository(api) { unknownOps += it }

    private suspend fun errorFor(failure: ApiResult.Failure): SchedulingAdminError {
        val fresh = FakeSchedulingAdminApi().apply { fail(SchedulingAdminOp.ME_GET, failure) }
        val result = SchedulingAdminRepository(fresh) { unknownOps += it }
            .perform("ws-1", SchedulingAdminOp.ME_GET, JsonObject(emptyMap()), String.serializer())
        return (result as SchedulingAdminResult.Failed).error
    }

    @Test
    fun `data is handed back and the workspace, op and params travel as given`() = runTest {
        api.value(SchedulingAdminOp.ME_GET, "payload")
        val params = schedulingParams("a" to "b".jsonOrNull())

        val result = repository.perform("ws-1", SchedulingAdminOp.ME_GET, params, String.serializer())

        assertEquals(SchedulingAdminResult.Ok("payload"), result)
        assertEquals("ws-1", api.calls.single().workspaceId)
        assertEquals(params, api.calls.single().params)
    }

    @Test
    fun `a refusal at 200 is the scheduler's, mapped by its failure string`() = runTest {
        val expected = mapOf(
            "unavailable" to SchedulingAdminFailureCode.UNAVAILABLE,
            "instance_unavailable" to SchedulingAdminFailureCode.UNAVAILABLE,
            "slot_taken" to SchedulingAdminFailureCode.SLOT_TAKEN,
            "conflict" to SchedulingAdminFailureCode.UNKNOWN,
            null to SchedulingAdminFailureCode.UNKNOWN,
        )
        expected.forEach { (failure, code) ->
            val fresh = FakeSchedulingAdminApi()
            fresh.answer(SchedulingAdminOp.ME_GET, FakeSchedulingAnswer.Refused(failure, 503))
            val result = SchedulingAdminRepository(fresh) { unknownOps += it }
                .perform("ws", SchedulingAdminOp.ME_GET, JsonObject(emptyMap()), String.serializer())
            assertEquals(SchedulingAdminResult.Failed(SchedulingAdminError.Refused(code)), result)
        }
    }

    @Test
    fun `our route's refusals collapse by status and code`() = runTest {
        assertEquals(SchedulingAdminError.Forbidden, errorFor(ApiResult.Unauthorized(ReauthReason.NoSession)))
        assertEquals(SchedulingAdminError.Forbidden, errorFor(ApiResult.Forbidden("Forbidden")))
        assertEquals(SchedulingAdminError.Unavailable, errorFor(ApiResult.RateLimited("slow down")))
        assertEquals(SchedulingAdminError.Unavailable, errorFor(ApiResult.RegionsDegraded("x", listOf("eu"))))
        assertEquals(SchedulingAdminError.Unknown, errorFor(ApiResult.NotFound("Not found")))
        assertEquals(SchedulingAdminError.Transport, errorFor(ApiResult.NetworkFailure(IOException("down"))))
        assertEquals(SchedulingAdminError.Decoding, errorFor(ApiResult.DecodeFailure(IllegalStateException(), "x")))
        assertEquals(SchedulingAdminError.InvalidParams, errorFor(ApiResult.HttpFailure(400, "invalid_params")))
        assertEquals(SchedulingAdminError.InvalidParams, errorFor(ApiResult.HttpFailure(400, "x", "invalid_params")))
        assertEquals(SchedulingAdminError.Unknown, errorFor(ApiResult.HttpFailure(400, "something_else")))
        assertEquals(SchedulingAdminError.NotReady, errorFor(ApiResult.HttpFailure(409, "scheduling_not_ready")))
        assertEquals(SchedulingAdminError.Unknown, errorFor(ApiResult.HttpFailure(409, "conflict")))
        assertEquals(SchedulingAdminError.Unavailable, errorFor(ApiResult.HttpFailure(413, "too large")))
        assertEquals(SchedulingAdminError.Unavailable, errorFor(ApiResult.HttpFailure(500, "boom")))
        assertEquals(SchedulingAdminError.Unknown, errorFor(ApiResult.HttpFailure(418, "teapot")))
        assertEquals(emptyList<SchedulingAdminOp>(), unknownOps)
    }

    @Test
    fun `an unknown op is reported with the op that caused it and reads as unknown`() = runTest {
        assertEquals(SchedulingAdminError.Unknown, errorFor(ApiResult.HttpFailure(400, "unknown_op")))
        assertEquals(SchedulingAdminError.Unknown, errorFor(ApiResult.HttpFailure(400, "x", "unknown_op")))
        assertEquals(SchedulingAdminError.Unknown, errorFor(ApiResult.HttpFailure(418, "unknown_op")))
        assertEquals(listOf(SchedulingAdminOp.ME_GET, SchedulingAdminOp.ME_GET), unknownOps)
    }

    @Test
    fun `every error has one recovery`() {
        val slotTaken = SchedulingAdminFailureCode.SLOT_TAKEN
        val expected = mapOf(
            SchedulingAdminError.Refused(slotTaken) to slotTaken,
            SchedulingAdminError.Forbidden to SchedulingAdminFailureCode.FORBIDDEN,
            SchedulingAdminError.NotReady to SchedulingAdminFailureCode.NOT_READY,
            SchedulingAdminError.Unavailable to SchedulingAdminFailureCode.UNAVAILABLE,
            SchedulingAdminError.Transport to SchedulingAdminFailureCode.UNAVAILABLE,
            SchedulingAdminError.InvalidParams to SchedulingAdminFailureCode.UNKNOWN,
            SchedulingAdminError.Unknown to SchedulingAdminFailureCode.UNKNOWN,
            SchedulingAdminError.Decoding to SchedulingAdminFailureCode.UNKNOWN,
        )
        expected.forEach { (error, code) -> assertEquals(error.toString(), code, error.uiCode) }
    }

    @Test
    fun `an upload carries its target and file, and is interpreted like an op`() = runTest {
        api.uploadAnswer = FakeSchedulingAnswer.Value(SchedulingUploadResult(logoUrl = "https://x/logo.png"))

        val result = repository.upload("ws-1", SchedulingAdminUploadTarget.LOGO, "logo.png", "image/png", ByteArray(3))

        assertEquals("https://x/logo.png", result.valueOrNull()?.publishedUrl)
        assertEquals(SchedulingAdminUploadTarget.LOGO, api.uploads.single().target)
        assertEquals(3, api.uploads.single().size)

        // ⚠️ An upload has no op to report, even on an `unknown_op`-shaped refusal.
        api.uploadAnswer = FakeSchedulingAnswer.Failed(ApiResult.HttpFailure(400, "unknown_op"))
        val failed = repository.upload("ws-1", SchedulingAdminUploadTarget.AVATAR, "a.png", "image/png", ByteArray(1))
        assertEquals(SchedulingAdminResult.Failed(SchedulingAdminError.Unknown), failed)
        assertEquals(emptyList<SchedulingAdminOp>(), unknownOps)
    }

    @Test
    fun `the result helpers keep a failure a failure`() {
        val failed: SchedulingAdminResult<Int> = SchedulingAdminResult.Failed(SchedulingAdminError.Unknown)
        assertNull(failed.valueOrNull())
        assertEquals(failed, failed.map { it + 1 })
        assertEquals(SchedulingAdminResult.Ok(2), SchedulingAdminResult.Ok(1).map { it + 1 })
    }

    @Test
    fun `absent keys are left out and every value type is encoded`() {
        val params = schedulingParams(
            "s" to "x".jsonOrNull(),
            "i" to 3.jsonOrNull(),
            "b" to true.jsonOrNull(),
            "ls" to listOf("a").jsonStringsOrNull(),
            "li" to listOf(1, 2).jsonIntsOrNull(),
            "none" to (null as String?).jsonOrNull(),
            "noInt" to (null as Int?).jsonOrNull(),
            "noBool" to (null as Boolean?).jsonOrNull(),
            "noList" to (null as List<String>?).jsonStringsOrNull(),
            "noInts" to (null as List<Int>?).jsonIntsOrNull(),
        )
        assertEquals(Json.parseToJsonElement("""{"s":"x","i":3,"b":true,"ls":["a"],"li":[1,2]}"""), params)
        assertNull(" ".blankAsNull())
        assertEquals("a", "a".blankAsNull())
        assertNull((null as String?).blankAsNull())
    }
}
