package com.distronode.districtai.ui.inbox

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.distronode.districtai.core.data.ComposerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One picked image, already in memory.
 *
 * ⛔ BYTES, NOT A URI OR A STREAM, AND THAT IS WHAT MAKES THE UPLOAD'S 401 RETRY CORRECT.
 * `DistrictApiClient` re-sends the same request body after a token refresh, so the body must be
 * readable twice; a `ContentResolver` InputStream is one-shot and would have uploaded ZERO bytes
 * on the retry — which the server accepts as a 400 ("between 1 byte and 5MB") rather than
 * reporting as the transport bug it is. The 5MB ceiling bounds the cost of holding it.
 *
 * ⚠️ [ByteArray] IN A DATA CLASS, SO `equals` IS IDENTITY. Deliberate: nothing compares two of
 * these, and generating a content-based `equals` over five megabytes to satisfy a lint rule would
 * be a real cost for no caller. Suppressed rather than silently accepted.
 */
@Suppress("ArrayInDataClass")
data class PickedAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Turns whatever the system photo picker handed back into bytes the uploader can send.
 *
 * ⛔ AN INTERFACE BECAUSE THE REAL ONE NEEDS A `ContentResolver`, AND A VIEWMODEL THAT REACHED FOR
 * ONE COULD NOT BE UNIT-TESTED AT ALL. Every attach path — the size reject, the type reject, the
 * unreadable-file case, the successful upload — is decided in the ViewModel, so the seam has to be
 * below it.
 */
fun interface AttachmentReader {
    /**
     * @return null when the picked item cannot be read at all — a revoked grant, a file the
     *   provider deleted between the pick and the read, or a cloud item that failed to download.
     *   ⚠️ NULL IS NOT THE SAME AS A REJECTED TYPE OR SIZE. Those are answered with a populated
     *   [PickedAttachment] and refused by the caller with a message naming the actual rule; null
     *   means "there was nothing to read", which needs different words.
     */
    suspend fun read(uri: String): PickedAttachment?
}

/**
 * The production reader, over `ContentResolver`.
 *
 * ⛔ READS ON [io], NEVER ON THE CALLER'S DISPATCHER. `openInputStream().readBytes()` on a 5MB
 * item is a blocking read of a file that may be on a cloud provider, and the caller is
 * `viewModelScope`, i.e. `Dispatchers.Main.immediate`. Android's `PENALTY_DEATH_ON_NETWORK` does
 * not fire for a local file, so this would not crash — it would just jank the composer for as long
 * as the read took, which is the harder bug to attribute. Same reasoning as
 * `DistrictApiClient.io`.
 *
 * ⚠️ NO READ PERMISSION IS REQUESTED OR NEEDED. `PickVisualMedia` returns a URI carrying a
 * one-shot read grant for exactly the item the user chose, so this app declares no
 * `READ_MEDIA_IMAGES` and cannot enumerate the gallery. That grant is scoped to the process and is
 * why the bytes are read immediately rather than the URI being stored for later.
 */
class ContentResolverAttachmentReader(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentReader {

    override suspend fun read(uri: String): PickedAttachment? = withContext(io) {
        val parsed = Uri.parse(uri)
        // ⚠️ The type comes from the RESOLVER, not from the file extension. A picker item often
        // has no extension at all, and the server's allowlist is on the MIME type — so a
        // guess from the name would refuse legitimate images and, worse, could label a
        // non-image as one and spend the upload to be refused server-side.
        val mimeType = resolver.getType(parsed) ?: return@withContext null
        val bytes = try {
            resolver.openInputStream(parsed)?.use { it.readBytes() }
        } catch (_: SecurityException) {
            // The read grant was revoked between the pick and this read. Reported as "could not
            // read" rather than crashing the composer.
            null
        } catch (_: java.io.IOException) {
            null
        } ?: return@withContext null

        PickedAttachment(
            fileName = displayName(parsed) ?: FALLBACK_FILE_NAME,
            mimeType = mimeType,
            bytes = bytes,
        )
    }

    /**
     * ⚠️ BEST EFFORT, AND A FAILURE HERE MUST NOT FAIL THE ATTACH. The server stores the mime type
     * and the byte length and never reads the name; it matters only because a multipart part with
     * no filename is not a file part at all, and `file instanceof File` then fails server-side.
     * So an unqueryable provider falls back to a constant rather than aborting.
     */
    private fun displayName(uri: Uri): String? =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        } catch (_: SecurityException) {
            null
        }

    private companion object {
        const val FALLBACK_FILE_NAME = "attachment"
    }
}

/**
 * Whether this app will even offer to upload the picked item.
 *
 * ⚠️ THE SAME ALLOWLIST THE SERVER ENFORCES, duplicated deliberately so a 5MB body is not spent on
 * a metered connection to be told no. [ComposerRepository] re-checks it, and the route re-checks it
 * again; this is a shortcut, never the boundary.
 */
internal fun PickedAttachment.isSupportedType(): Boolean =
    mimeType in ComposerRepository.ALLOWED_MIME_TYPES

/** ⚠️ 1 byte to 5MB, matching the route exactly. A zero-byte pick is a failed read, not an image. */
internal fun PickedAttachment.isWithinSizeLimit(): Boolean =
    bytes.isNotEmpty() && bytes.size <= ComposerRepository.MAX_UPLOAD_BYTES
