package com.distronode.districtai

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.SchedulingAdminFailureCode
import com.distronode.districtai.core.data.SchedulingAdminOutcome
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingUploadResult
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminApi
import com.distronode.districtai.core.network.SchedulingAdminEnvelope
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.TestDistrictApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * What the container does with a scheduler op the server does not recognise.
 *
 * ⛔ A PROGRAMMER ERROR, SO IT IS LOUD IN DEBUG AND SILENT IN RELEASE, AND NEVER FATAL. A 400
 * `unknown_op` means `SchedulingAdminOp` and the server's `ADMIN_OPS` have diverged; the caller still
 * gets an ordinary failure, and only a developer build says why in the log.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AppContainerSchedulingAdminTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun logged(): List<ShadowLog.LogItem> = ShadowLog.getLogsForTag(TAG)

    private val container = AppContainer(
        ApplicationProvider.getApplicationContext(),
        appScope = scope,
        seams = AppContainerSeams(
            tokenStore = ShellTokenStore(),
            pushTokenSource = { null },
            districtApi = TestDistrictApi(),
            schedulingAdminApi = ScriptedSchedulingAdminApi,
        ),
    )

    @Test
    fun `an unknown op from the server is logged on a debug build and still fails ordinarily`() {
        val outcome = runBlocking {
            container.schedulingAdminRepository.perform(
                SchedulingAdminOp.ME_GET,
                "ws-1",
                JsonObject(emptyMap()),
                SchedulingNoContent.serializer(),
            )
        }

        assertEquals(SchedulingAdminFailureCode.UNKNOWN, (outcome as SchedulingAdminOutcome.Failure).code)
        // ⚠️ The unit tests run the debug variant, so this is the build's own flag at work.
        val line = logged().single()
        assertEquals(Log.ERROR, line.type)
        assertTrue(line.msg, line.msg.contains("'me.get'"))
    }

    @Test
    fun `a debug build names the op that diverged`() {
        reportUnknownSchedulingOp(SchedulingAdminOp.ME_GET, debug = true)

        val line = logged().single()
        assertEquals(Log.ERROR, line.type)
        assertTrue(line.msg, line.msg.contains("'me.get'"))
    }

    @Test
    fun `a release build logs nothing at all`() {
        reportUnknownSchedulingOp(SchedulingAdminOp.ME_GET, debug = false)

        assertEquals(emptyList<ShadowLog.LogItem>(), logged())
    }

    @Test
    fun `the media repository reads through the same scheduler API`() {
        // ⚠️ The media half cannot travel through the op route (see its class doc), so it is wired
        // to the scheduler API separately; this proves it is the same one, seam and all.
        val outcome = runBlocking {
            container.schedulingAdminMediaRepository.recordingDownloadUrl("ws-1", "rec-1")
        }

        assertEquals(SchedulingAdminOutcome.Success(RECORDING_URL), outcome)
        assertEquals(emptyList<ShadowLog.LogItem>(), logged())
    }

    /** Every op answers the server's refusal for an op it does not know; a recording resolves. */
    private object ScriptedSchedulingAdminApi : SchedulingAdminApi {
        override suspend fun <T> performSchedulingOp(
            workspaceId: String,
            op: SchedulingAdminOp,
            params: JsonObject,
            serializer: KSerializer<T>,
        ): ApiResult<SchedulingAdminEnvelope<T>> = ApiResult.HttpFailure(status = 400, message = "unknown_op")

        override suspend fun uploadSchedulingImage(
            workspaceId: String,
            target: SchedulingAdminUploadTarget,
            fileName: String,
            mimeType: String,
            bytes: ByteArray,
        ): ApiResult<SchedulingAdminEnvelope<SchedulingUploadResult>> = error("not exercised")

        override suspend fun schedulingRecordingDownloadUrl(
            workspaceId: String,
            recordingId: String,
        ): ApiResult<String> = ApiResult.Success(RECORDING_URL)
    }

    private companion object {
        const val TAG = "SchedulingAdmin"
        const val RECORDING_URL = "https://recordings.test/rec-1.mp4"
    }
}
