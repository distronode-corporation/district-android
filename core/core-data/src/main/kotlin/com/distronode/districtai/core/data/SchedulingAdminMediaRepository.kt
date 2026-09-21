package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingUploadResult
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminApi
import com.distronode.districtai.core.network.SchedulingAdminEnvelope
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget

/**
 * The two scheduling-admin calls that are not the RPC: an image upload and a recording download.
 *
 * ⛔ A SEPARATE REPOSITORY BECAUSE THEY ARE SEPARATE ROUTES, NOT BECAUSE THEY ARE RARE. Neither
 * can travel through the op catalog: an image cannot go through a zod-validated params object
 * without a base64 inflation on both sides of a hop that already has a 5 MiB ceiling, and a
 * recording is a **302** to a presigned object the server refuses to proxy. They share the
 * envelope and the failure vocabulary with [SchedulingAdminRepository] and nothing else.
 *
 * ⛔ AND THE TWO DO NOT SHARE A ROLE BAR WITH THE OPS THAT NAME THEM. `recordings.list` is
 * `viewer`; [recordingDownloadUrl] is `agency`/`client`, because seeing that a recording exists
 * and taking a copy of a customer conversation away are different permissions. An `avatar` upload
 * is `viewer` while `logo` and `banner` are `client`, for the mirror-image reason.
 */
class SchedulingAdminMediaRepository(private val api: SchedulingAdminApi) {

    /**
     * Publish one of the three images the admin surface can set.
     *
     * ⚠️ THE ACCEPTED TYPES ARE JPEG, PNG, GIF AND WEBP — **NOT SVG**, which is the obvious thing
     * to want for a logo and is a script-bearing document. [SchedulingUploadFile.looksAcceptable]
     * is a courtesy check so a screen can refuse before spending a request; it is NOT the boundary,
     * because the fork sniffs the first 512 bytes rather than trusting the declared type.
     *
     * ⚠️ THE ANSWER NAMES ONE OF THREE KEYS, decided by [target]. Read it through
     * [SchedulingUploadResult.publishedUrl] rather than reaching for the key that matches, which
     * puts the same decision in two places.
     */
    suspend fun upload(
        workspaceId: String,
        target: SchedulingAdminUploadTarget,
        file: SchedulingUploadFile,
    ): SchedulingAdminOutcome<SchedulingUploadResult> {
        val result = api.uploadSchedulingImage(
            workspaceId = workspaceId,
            target = target,
            fileName = file.fileName,
            mimeType = file.mimeType,
            bytes = file.bytes,
        )

        return when (result) {
            is ApiResult.Success -> when (val envelope = result.value) {
                is SchedulingAdminEnvelope.Data -> SchedulingAdminOutcome.Success(envelope.value)
                is SchedulingAdminEnvelope.Refusal -> SchedulingAdminOutcome.Failure(
                    SchedulingAdminFailureCode.forFailure(envelope.failure),
                    envelope.failure,
                )
            }

            is ApiResult.Failure -> result.toSchedulingAdminFailure()
        }
    }

    /**
     * Resolve a playable URL for a scheduler recording.
     *
     * ⛔ THE URL IS PRESIGNED, EXPIRES IN 15 MINUTES AND IS NEVER STORED HERE. Resolve it at the
     * moment of playback; a cached one fails inside whatever player received it, which looks like a
     * broken recording rather than a stale link.
     *
     * ⛔ HTTPS IS CHECKED RATHER THAN TRUSTED, AND THE CHECK IS NOT CEREMONY. A presigned object URL
     * carries its own credential in the query string, so a non-TLS value would put that credential
     * on the wire in clear — and this client cannot know the storage host in advance, so the scheme
     * is the one thing there is to check. `SchedulingRepository.schedulerHandOff` guards its own
     * 302 the same way and for the same reason.
     *
     * ⚠️ A REFUSED VALUE IS REPORTED WITHOUT BEING QUOTED. The URL is the credential; a diagnostic
     * that echoed it would publish one wherever the diagnostic goes.
     */
    suspend fun recordingDownloadUrl(
        workspaceId: String,
        recordingId: String,
    ): SchedulingAdminOutcome<String> =
        when (val result = api.schedulingRecordingDownloadUrl(workspaceId, recordingId)) {
            is ApiResult.Success ->
                if (result.value.startsWith(HTTPS_PREFIX, ignoreCase = true)) {
                    SchedulingAdminOutcome.Success(result.value)
                } else {
                    SchedulingAdminOutcome.Failure(
                        SchedulingAdminFailureCode.UNKNOWN,
                        "SchedulingDownload{scheme!=https}",
                    )
                }

            is ApiResult.Failure -> result.toSchedulingAdminFailure()
        }

    private companion object {
        const val HTTPS_PREFIX = "https://"
    }
}

/**
 * The bytes of one image, and what it claims to be.
 *
 * ⛔ THE WHOLE FILE IS HELD IN MEMORY RATHER THAN STREAMED, deliberately, and the 5 MiB server cap
 * is what bounds the cost. `DistrictApiClient` retries a request ONCE on a 401, and a stream from a
 * `ContentResolver` is one-shot — so a streamed body would silently upload ZERO BYTES on the retry,
 * which the server accepts as a 400 ("between 1 byte and 5MB") rather than as the transport bug it
 * is. Same reasoning as the message-attachment upload.
 *
 * ⚠️ `ByteArray` MAKES `equals` IDENTITY-BASED on a data class, which is why this is a plain class.
 * Nothing compares two of these, and a data class here would offer an `equals` that answers false
 * for two identical images.
 */
class SchedulingUploadFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    /**
     * Whether this is worth spending a request on.
     *
     * ⛔ A COURTESY AND NOT A BOUNDARY. The fork sniffs content; a real SVG renamed `.png` and
     * declared `image/png` still fails there, with a **415** this cannot predict. What this catches
     * is the cheap half — an empty file, an oversized one, and a type nobody accepts — before a
     * phone spends a metered upload learning the same thing.
     */
    fun looksAcceptable(): Boolean =
        mimeType in ACCEPTED_MIME_TYPES && bytes.isNotEmpty() && bytes.size <= MAX_BYTES

    companion object {
        /** ⛔ The fork's set, verbatim. There is no `image/svg+xml` and there must not be. */
        val ACCEPTED_MIME_TYPES: Set<String> = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
        )

        /** The fork's own ceiling (`MaxBytesReader(5<<20 + 1024)`), which our route mirrors. */
        const val MAX_BYTES: Int = 5 * 1024 * 1024
    }
}
