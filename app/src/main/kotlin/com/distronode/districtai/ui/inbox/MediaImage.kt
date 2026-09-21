package com.distronode.districtai.ui.inbox

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches and decodes one attachment thumbnail.
 *
 * ⛔ NO IMAGE-LOADING LIBRARY, DELIBERATELY. Coil or Glide would each bring a disk cache, a
 * lifecycle integration and a transitive graph for a feature whose whole surface is "draw at most
 * five small images in one screen". This is the shared OkHttp client, a bounded in-memory cache and
 * `BitmapFactory` — about sixty lines, and it reuses the connection pool the rest of the app
 * already holds.
 *
 * ⛔ AND NO `Authorization` HEADER. `/api/media/<uuid>` is an ANONYMOUS capability URL — it must be,
 * because carriers fetch it to deliver the MMS — so it needs no token, and attaching one would send
 * an access token to a route that never asked for it. This is why the loader takes a plain
 * `OkHttpClient` rather than going through `DistrictApiClient`.
 */
fun interface MediaImageLoader {
    /** @return null on any failure — an unreachable host, a 404, or bytes that do not decode. */
    suspend fun load(url: String): ImageBitmap?
}

/**
 * The production loader.
 *
 * ⛔ DECODES OFF THE MAIN THREAD. `BitmapFactory.decodeByteArray` on a multi-megapixel JPEG is tens
 * of milliseconds of CPU, and the caller is a composition — the same reasoning that put
 * `DistrictApiClient`'s body read on [io].
 *
 * ⛔ AND IT DOWNSAMPLES RATHER THAN DECODING FULL SIZE. A 5MB photo decodes to roughly 48MB of
 * ARGB_8888 at full resolution, and five of those in one thread is an `OutOfMemoryError` on a
 * mid-range device — for images drawn at 96dp. `inSampleSize` is chosen from the bounds pass so the
 * decoded bitmap is at most twice the size it is drawn at.
 *
 * ⚠️ THE CACHE IS BOUNDED BY BYTES, NOT BY COUNT. A count-based cache holding five full-resolution
 * photos is the same OOM by another route; `LruCache.sizeOf` reports each entry's real footprint.
 */
class OkHttpMediaImageLoader(
    private val client: OkHttpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MediaImageLoader {

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * BYTES_PER_PIXEL
    }

    override suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }

        return withContext(io) {
            val bytes = try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes() else null
                }
            } catch (_: java.io.IOException) {
                null
            } catch (_: IllegalArgumentException) {
                // A malformed URL from a draft row. Not a crash: the chip shows its failure state.
                null
            } ?: return@withContext null

            decode(bytes)?.also { cache.put(url, it) }
        }
    }

    /**
     * ⚠️ TWO PASSES: bounds only, then the real decode at a sample size. The first pass allocates
     * nothing (`inJustDecodeBounds`), which is what makes it safe to run on an image whose
     * dimensions are unknown and attacker-influenced — the bytes come from a URL the workspace
     * stored, but the DIMENSIONS were never validated by anything.
     */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        var sample = 1
        val widest = maxOf(bounds.outWidth, bounds.outHeight)
        while (widest > TARGET_MAX_PIXELS * sample) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private companion object {
        /** Roughly a dozen thumbnails at the size below. Bounded so five photos cannot OOM. */
        const val CACHE_BYTES = 8 * 1024 * 1024
        const val BYTES_PER_PIXEL = 4

        /** Twice the drawn size, so the thumbnail still looks sharp on a high-density screen. */
        const val TARGET_MAX_PIXELS = 512
    }
}

/**
 * One inbound attachment, drawn as a tappable thumbnail.
 *
 * ⛔ TAPPING OPENS THE BROWSER RATHER THAN AN IN-APP VIEWER. The URL is anonymous and already
 * viewable anywhere; a full-screen viewer would mean pinch-zoom, save, share and rotation handling
 * for a feature the system browser does completely and correctly. The caller supplies the intent.
 *
 * ⚠️ THREE STATES, AND "FAILED" IS NOT ALLOWED TO LOOK LIKE "LOADING". See [MediaPhase].
 */
@Composable
fun MediaImage(
    url: String,
    loader: MediaImageLoader,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⛔ ONE produceState, NOT TWO. An earlier shape used a second one to tell "still loading"
    // from "loaded nothing", which called the loader TWICE per thumbnail — two requests, two
    // decodes, and a cache that made the duplicate invisible in testing. The three-state value
    // carries the distinction instead.
    //
    // ⚠️ KEYED ON THE URL, so scrolling a thread does not re-fetch an image already decoded and
    // changing the URL does re-fetch.
    val phase by produceState<MediaPhase>(initialValue = MediaPhase.Loading, url) {
        value = loader.load(url)?.let { MediaPhase.Ready(it) } ?: MediaPhase.Failed
    }

    Box(
        modifier = modifier
            .size(THUMBNAIL_SIZE)
            .clip(RoundedCornerShape(THUMBNAIL_RADIUS))
            .background(DistrictTheme.colors.muted)
            .clickable { onOpen(url) }
            .semantics { contentDescription = THREAD_MEDIA_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            is MediaPhase.Ready -> Image(
                bitmap = (phase as MediaPhase.Ready).image,
                // Null: the enclosing Box already carries the description, and a second one would
                // make TalkBack announce the same thumbnail twice.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(THUMBNAIL_SIZE),
            )
            MediaPhase.Failed -> Text(
                text = stringResource(R.string.thread_image_failed),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.semantics {
                    contentDescription = THREAD_MEDIA_FAILED_DESCRIPTION
                },
            )
            MediaPhase.Loading -> Text(
                text = stringResource(R.string.thread_image_loading),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.semantics {
                    contentDescription = THREAD_MEDIA_LOADING_DESCRIPTION
                },
            )
        }
    }
}

/**
 * ⛔ THREE STATES, NOT A NULLABLE BITMAP. Null cannot say whether the fetch is in flight or has
 * already failed, and rendering a permanent shimmer for an image that will never arrive is the
 * worst of the three outcomes — the operator waits for something that is not coming.
 */
private sealed interface MediaPhase {
    data object Loading : MediaPhase
    data object Failed : MediaPhase
    data class Ready(val image: ImageBitmap) : MediaPhase
}

private val THUMBNAIL_SIZE = 96.dp
private val THUMBNAIL_RADIUS = 8.dp

/** Stable handles for tests. */
const val THREAD_MEDIA_DESCRIPTION: String = "district-thread-media"
const val THREAD_MEDIA_LOADING_DESCRIPTION: String = "district-thread-media-loading"
const val THREAD_MEDIA_FAILED_DESCRIPTION: String = "district-thread-media-failed"
