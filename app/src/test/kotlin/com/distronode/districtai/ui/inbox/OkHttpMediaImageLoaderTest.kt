package com.distronode.districtai.ui.inbox

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The production thumbnail loader, with the network replaced at OkHttp's own boundary.
 *
 * ⚠️ AN INTERCEPTOR THAT ANSWERS, NOT A SERVER. Every response here is built in the test and handed
 * back before any socket is opened, so the loader's own decisions (what counts as a failure, when to
 * decode, what to cache) are what is exercised.
 *
 * ⛔ NATIVE GRAPHICS, because the downsampling decision reads the image's real dimensions, and the
 * legacy graphics mode invents them rather than decoding the bytes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OkHttpMediaImageLoaderTest {

    private val requested = mutableListOf<String>()

    private fun loader(scope: TestScope, answer: (Request) -> Response) = OkHttpMediaImageLoader(
        client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requested += chain.request().url.toString()
                answer(chain.request())
            }
            .build(),
        io = StandardTestDispatcher(scope.testScheduler),
    )

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    private fun respond(request: Request, code: Int, bytes: ByteArray = ByteArray(0)) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status $code")
        .body(bytes.toResponseBody())
        .build()

    @Test
    fun `an image is decoded once and then served from memory without a second request`() = runTest {
        val bytes = png(40, 20)
        val loader = loader(this) { respond(it, 200, bytes) }

        val first = loader.load(URL)
        val second = loader.load(URL)

        assertNotNull(first)
        assertEquals(40, first?.width)
        assertSame("the cached bitmap, not a second decode", first, second)
        assertEquals(listOf(URL), requested)
    }

    @Test
    fun `a large image is downsampled to at most twice its drawn size`() = runTest {
        // 1200px wide: halved to 600 (still over 512) and again to 300, which is where it stops.
        val loader = loader(this) { respond(it, 200, png(1200, 10)) }

        assertEquals(300, loader.load(URL)?.width)
    }

    @Test
    fun `a refused fetch is no image and is not cached`() = runTest {
        val loader = loader(this) { respond(it, 404) }

        assertNull(loader.load(URL))
        assertNull(loader.load(URL))
        assertEquals("a failure is asked again rather than remembered", 2, requested.size)
    }

    @Test
    fun `an unreachable host is no image rather than a crash`() = runTest {
        val loader = loader(this) { throw IOException("unreachable") }

        assertNull(loader.load(URL))
    }

    @Test
    fun `bytes that are not an image are no image`() = runTest {
        val loader = loader(this) { respond(it, 200, "<html>not an image</html>".toByteArray()) }

        assertNull(loader.load(URL))
    }

    @Test
    fun `a malformed url is no image, and no request is attempted`() = runTest {
        val loader = loader(this) { respond(it, 200, png(4, 4)) }

        assertNull(loader.load("not a url"))
        assertEquals(emptyList<String>(), requested)
    }

    private companion object {
        const val URL = "https://www.distronode.test/api/media/0b9f"
    }
}
