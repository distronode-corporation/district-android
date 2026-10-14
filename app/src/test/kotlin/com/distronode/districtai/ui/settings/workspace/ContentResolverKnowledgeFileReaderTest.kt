package com.distronode.districtai.ui.settings.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.inbox.PickerProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * How a document picked in the system file chooser becomes bytes, against a real provider (the
 * composer's [PickerProvider], whose path segment selects one behaviour a real provider shows).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ContentResolverKnowledgeFileReaderTest {

    private val text = "Open 9 to 5".toByteArray()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(PickerProvider::class.java, AUTHORITY)
        PickerProvider.reset(text)
    }

    private suspend fun read(item: String, scheduler: TestCoroutineScheduler): PickedTextFile? {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        return ContentResolverKnowledgeFileReader(resolver, StandardTestDispatcher(scheduler))
            .read("content://$AUTHORITY/$item")
    }

    @Test
    fun `a named document comes back with its display name and its bytes`() = runTest {
        val picked = read("photo", testScheduler)

        assertEquals("photo.jpg", picked?.fileName)
        assertArrayEquals(text, picked?.bytes)
    }

    @Test
    fun `the read stops one byte past the limit, so a larger file is seen to be larger`() = runTest {
        PickerProvider.reset(ByteArray(MAX_FILE_BYTES * 2) { 'a'.code.toByte() })

        assertEquals(MAX_FILE_BYTES + 1, read("photo", testScheduler)?.bytes?.size)
    }

    @Test
    fun `a document at the limit is read whole`() = runTest {
        PickerProvider.reset(ByteArray(MAX_FILE_BYTES) { 'a'.code.toByte() })

        assertEquals(MAX_FILE_BYTES, read("photo", testScheduler)?.bytes?.size)
    }

    @Test
    fun `a revoked grant, a deleted file and no stream are could-not-read, not a crash`() = runTest {
        listOf("revoked", "gone", "no-stream").forEach { assertNull(it, read(it, testScheduler)) }
    }

    @Test
    fun `a document without a usable name keeps its bytes and suggests no title`() = runTest {
        listOf("no-name-column", "no-rows", "name-refused", "no-cursor").forEach { item ->
            val picked = read(item, testScheduler)

            assertEquals(item, "", picked?.fileName)
            assertArrayEquals(item, text, picked?.bytes)
        }
    }

    private companion object {
        const val AUTHORITY = "com.distronode.districtai.test.knowledgefile"
    }
}
