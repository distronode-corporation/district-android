package com.distronode.districtai.ui.settings.workspace

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns the document the system file chooser handed back into bytes [readKnowledgeFile] can judge.
 *
 * ⛔ AN INTERFACE FOR THE SAME REASON AS `AttachmentReader`: the real one needs a `ContentResolver`,
 * and every outcome (too large, not text, unreadable, the form filled) is decided in
 * [KnowledgeViewModel], which must stay unit-testable.
 */
fun interface KnowledgeFileReader {
    /**
     * @return null when the item cannot be read at all (a revoked grant, a file the provider
     *   deleted, a cloud item that failed to download). ⚠️ NULL IS NOT A REFUSAL BY CONTENT: a
     *   file that is too large or not text comes back as bytes and is refused by its own rule.
     */
    suspend fun read(uri: String): PickedTextFile?
}

/**
 * The production reader, over `ContentResolver`.
 *
 * ⛔ READS ON [io], NEVER ON THE CALLER'S DISPATCHER: the caller is `viewModelScope` (the main
 * thread) and the document may live on a cloud provider. Same reasoning as
 * `ContentResolverAttachmentReader`.
 *
 * ⚠️ NO STORAGE PERMISSION IS REQUESTED OR NEEDED. `OpenDocument` returns a read grant for exactly
 * the document chosen, so the bytes are read at once rather than the URI being kept.
 *
 * ⚠️ THE READ STOPS ONE BYTE PAST [MAX_FILE_BYTES]. That byte is what tells a too-large file from
 * one at the limit, and a 200 MB log picked by mistake is never pulled into memory whole.
 */
class ContentResolverKnowledgeFileReader(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : KnowledgeFileReader {

    override suspend fun read(uri: String): PickedTextFile? = withContext(io) {
        val parsed = Uri.parse(uri)
        val bytes = try {
            resolver.openInputStream(parsed)?.use { it.readAtMost(MAX_FILE_BYTES + 1) }
        } catch (_: SecurityException) {
            null
        } catch (_: IOException) {
            null
        } ?: return@withContext null
        PickedTextFile(fileName = displayName(parsed).orEmpty(), bytes = bytes)
    }

    /**
     * ⚠️ BEST EFFORT. The name only suggests a title, and an empty one leaves the title box as the
     * operator had it, so a provider that will not say does not fail the read.
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
}

/** Up to [limit] bytes from the start of the stream. */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (out.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (count < 0) break
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

private const val READ_BUFFER_BYTES = 64 * 1024
