package com.distronode.districtai.ui.inbox

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * How a picked `content://` item becomes an attachment, against a real provider.
 *
 * The reader trusts the RESOLVER for the MIME type (a picker item often has no extension, and the
 * server's allowlist is on the type), reads the bytes once, and asks for a display name only as a
 * nicety. Each of those three can fail independently on a real device (a provider that publishes
 * no type, a read grant revoked between the pick and the read, a provider that refuses the name
 * query), and the composer must get "could not read" or a fallback name rather than a crash.
 *
 * [PickerProvider] is a real ContentProvider registered with Robolectric under its own authority,
 * so the resolver calls go through the same `getType` / `openFile` / `query` path a gallery app
 * answers on a phone. Each path segment selects one behaviour.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ContentResolverAttachmentReaderTest {

    private val photoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(PickerProvider::class.java, AUTHORITY)
        PickerProvider.reset(photoBytes)
    }

    private fun uri(item: String) = "content://$AUTHORITY/$item"

    private suspend fun read(item: String, io: CoroutineDispatcher): PickedAttachment? {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        return ContentResolverAttachmentReader(resolver, io).read(uri(item))
    }

    @Test
    fun `a named image comes back with its resolver type, its bytes and its display name`() = runTest {
        val picked = read("photo", StandardTestDispatcher(testScheduler))

        assertEquals("photo.jpg", picked?.fileName)
        assertEquals("image/jpeg", picked?.mimeType)
        assertArrayEquals(photoBytes, picked?.bytes)
    }

    @Test
    fun `an item the provider gives no type for is refused before any byte is read`() = runTest {
        assertNull(read("untyped", StandardTestDispatcher(testScheduler)))
        assertEquals("the bytes were never opened", 0, PickerProvider.opened("untyped"))
    }

    @Test
    fun `a read grant revoked after the pick is could-not-read, not a crash`() = runTest {
        assertNull(read("revoked", StandardTestDispatcher(testScheduler)))
    }

    @Test
    fun `a provider whose file has gone is could-not-read`() = runTest {
        assertNull(read("gone", StandardTestDispatcher(testScheduler)))
    }

    @Test
    fun `an item without a usable display name falls back to a neutral file name`() = runTest {
        // Three different ways a provider can decline to name an item: no name column, a name
        // column with no row, and refusing the query outright. None of them loses the bytes.
        listOf("no-name-column", "no-rows", "name-refused").forEach { item ->
            val picked = read(item, StandardTestDispatcher(testScheduler))

            assertEquals(item, "attachment", picked?.fileName)
            assertEquals(item, "image/png", picked?.mimeType)
            assertArrayEquals(item, photoBytes, picked?.bytes)
        }
    }

    private companion object {
        const val AUTHORITY = "com.distronode.districtai.test.picker"
    }
}

/**
 * A picker-shaped provider whose behaviour is chosen by the item's path segment.
 *
 * Every behaviour is one a real provider exhibits; none is a state the platform cannot produce.
 */
internal class PickerProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment) {
        "untyped" -> null
        "photo", "revoked", "gone" -> "image/jpeg"
        else -> "image/png"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val item = uri.lastPathSegment.orEmpty()
        openCounts[item] = opened(item) + 1
        return when (item) {
            "revoked" -> throw SecurityException("Permission Denial: reading $uri requires a grant")
            "gone" -> throw FileNotFoundException("No item at $uri")
            else -> {
                val file = File.createTempFile("picked", ".bin").apply {
                    deleteOnExit()
                    writeBytes(bytes)
                }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = when (uri.lastPathSegment) {
        "name-refused" -> throw SecurityException("Permission Denial: querying $uri")
        "no-name-column" -> MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(bytes.size))
        }
        "no-rows" -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
        else -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf<Any>("photo.jpg")) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        var bytes: ByteArray = ByteArray(0)
            private set
        private val openCounts = mutableMapOf<String, Int>()

        fun reset(content: ByteArray) {
            bytes = content
            openCounts.clear()
        }

        fun opened(item: String): Int = openCounts[item] ?: 0
    }
}
