package com.distronode.districtai.core.data

import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image upload and the recording download, as the data layer sees them.
 *
 * ⛔ THEY SHARE THE ENVELOPE AND THE FIVE-CODE VOCABULARY WITH THE RPC AND NOTHING ELSE. The
 * upload is enveloped exactly like an op — including the **200** that is a refusal — while the
 * download is a 302 with no envelope at all, so the two need different handling for the same
 * outcome. Both are here so that difference is visible in one file.
 */
class SchedulingAdminMediaRepositoryTest {

    private val api = FakeSchedulingAdminApi()
    private val repository = SchedulingAdminMediaRepository(api)

    private fun png(bytes: Int = 4) = SchedulingUploadFile(
        fileName = "logo.png",
        mimeType = "image/png",
        bytes = ByteArray(bytes) { 1 },
    )

    @Test
    fun `an upload forwards the target and the file, and answers the published url`() = runTest {
        api.payloadJson = """{"logo_url":"https://x.test/logo.png"}"""

        val outcome = repository.upload(WORKSPACE, SchedulingAdminUploadTarget.LOGO, png())

        assertEquals(SchedulingAdminUploadTarget.LOGO, api.lastUploadTarget)
        assertEquals("logo.png", api.lastUploadFileName)
        assertEquals("image/png", api.lastUploadMimeType)
        assertEquals(4, api.lastUploadBytes?.size)
        assertEquals(WORKSPACE, api.lastWorkspaceId)

        val result = (outcome as SchedulingAdminOutcome.Success).value
        assertEquals("https://x.test/logo.png", result.logoUrl)

        // ⚠️ THE ACCESSOR IS THE SUPPORTED READ. Reaching for the key that matches the target puts
        // the same decision in two places.
        assertEquals("https://x.test/logo.png", result.publishedUrl)
        assertNull(result.bannerUrl)
    }

    @Test
    fun `an upload the scheduler refuses is a failure and not an empty result`() = runTest {
        // ⛔ THE 200 THAT IS A REFUSAL. A lenient type would decode `{ok:false}` cleanly and report
        // an outage as an upload that published nothing.
        api.refusal = "instance_unavailable"
        api.refusalStatus = 502

        val outcome = repository.upload(WORKSPACE, SchedulingAdminUploadTarget.BANNER, png())

        assertEquals(
            SchedulingAdminOutcome.Failure(
                SchedulingAdminFailureCode.UNAVAILABLE,
                "instance_unavailable",
            ),
            outcome,
        )
    }

    @Test
    fun `a 413 on an upload is retryable-eventually and a 403 is the role bar`() = runTest {
        api.failure = ApiResult.HttpFailure(413, "payload_too_large")
        val tooLarge = repository.upload(WORKSPACE, SchedulingAdminUploadTarget.LOGO, png())
        assertEquals(
            SchedulingAdminFailureCode.UNAVAILABLE,
            (tooLarge as SchedulingAdminOutcome.Failure).code,
        )

        // ⚠️ A `viewer` AIMING AT `logo` OR `banner` GETS THIS, while `avatar` is their own picture
        // and is viewer-level. The target's `minRole` is what a screen should draw from.
        api.failure = ApiResult.Forbidden("forbidden")
        val refused = repository.upload(WORKSPACE, SchedulingAdminUploadTarget.LOGO, png())
        assertEquals(
            SchedulingAdminFailureCode.FORBIDDEN,
            (refused as SchedulingAdminOutcome.Failure).code,
        )
    }

    @Test
    fun `the acceptability check refuses svg, empty files and oversized ones`() {
        // ⛔ A COURTESY AND NOT A BOUNDARY. The fork sniffs the first 512 bytes, so a real SVG
        // renamed `.png` and declared `image/png` still fails there with a 415 this cannot predict.
        // What this catches is the cheap half, before a phone spends a metered upload.
        assertTrue(png().looksAcceptable())
        assertTrue(
            SchedulingUploadFile("a.jpg", "image/jpeg", byteArrayOf(1)).looksAcceptable(),
        )
        assertTrue(
            SchedulingUploadFile("a.webp", "image/webp", byteArrayOf(1)).looksAcceptable(),
        )
        assertTrue(
            SchedulingUploadFile("a.gif", "image/gif", byteArrayOf(1)).looksAcceptable(),
        )

        // ⛔ SVG IS THE OBVIOUS THING TO WANT FOR A LOGO AND IS A SCRIPT-BEARING DOCUMENT.
        assertFalse(
            SchedulingUploadFile("a.svg", "image/svg+xml", byteArrayOf(1)).looksAcceptable(),
        )
        assertFalse(SchedulingUploadFile("a.png", "image/png", ByteArray(0)).looksAcceptable())
        assertFalse(
            SchedulingUploadFile(
                "a.png",
                "image/png",
                ByteArray(SchedulingUploadFile.MAX_BYTES + 1),
            ).looksAcceptable(),
        )

        // ⚠️ EXACTLY AT THE CEILING IS ACCEPTABLE; the fork's reader allows the 5 MiB itself.
        assertTrue(
            SchedulingUploadFile(
                "a.png",
                "image/png",
                ByteArray(SchedulingUploadFile.MAX_BYTES),
            ).looksAcceptable(),
        )
        assertEquals(4, SchedulingUploadFile.ACCEPTED_MIME_TYPES.size)
    }

    @Test
    fun `a recording download answers the presigned location`() = runTest {
        api.downloadLocation = "https://storage.test/rec.mp4?sig=abc"

        val outcome = repository.recordingDownloadUrl(WORKSPACE, "rec-1")

        assertEquals(
            "https://storage.test/rec.mp4?sig=abc",
            (outcome as SchedulingAdminOutcome.Success).value,
        )
    }

    @Test
    fun `a non-https location is refused without being quoted`() = runTest {
        // ⛔ THE URL CARRIES ITS OWN CREDENTIAL IN THE QUERY STRING, so a non-TLS value would put
        // that credential on the wire in clear — and a diagnostic that echoed it would publish one
        // wherever the diagnostic goes.
        api.downloadLocation = "http://storage.test/rec.mp4?sig=abc"

        val outcome = repository.recordingDownloadUrl(WORKSPACE, "rec-1")

        val failure = outcome as SchedulingAdminOutcome.Failure
        assertEquals(SchedulingAdminFailureCode.UNKNOWN, failure.code)
        assertEquals("SchedulingDownload{scheme!=https}", failure.detail)
        assertFalse(
            "the refused value may not be quoted",
            failure.detail?.contains("sig=abc") == true,
        )
    }

    @Test
    fun `a 403 on the download is the stricter bar the listing op does not carry`() = runTest {
        // ⚠️ `recordings.list` IS `viewer` AND THIS ROUTE IS NOT: a viewer may see that a recording
        // exists and may not take a copy of a customer conversation away.
        api.failure = ApiResult.Forbidden("forbidden")

        val outcome = repository.recordingDownloadUrl(WORKSPACE, "rec-1")

        assertEquals(
            SchedulingAdminFailureCode.FORBIDDEN,
            (outcome as SchedulingAdminOutcome.Failure).code,
        )
    }

    private companion object {
        const val WORKSPACE = "ws-1"
    }
}
