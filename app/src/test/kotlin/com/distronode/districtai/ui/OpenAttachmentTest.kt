package com.distronode.districtai.ui

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Opening a message attachment, and refusing everything that is not one.
 *
 * ⛔ THE URL IS SERVER DATA AND THE INTENT IS AN IMPLICIT ACTION_VIEW WITH NEW_TASK. Whatever scheme
 * arrives decides which app on the device answers it: `intent:` can name an arbitrary component,
 * `content:` and `file:` reach providers and storage, `javascript:` runs in whatever browser takes
 * it. Only https is an attachment, so only https may leave the app.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class OpenAttachmentTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `an https attachment is opened, whatever the case of its scheme`() {
        for (url in listOf("https://media.example.test/a.jpg", "HTTPS://media.example.test/b.jpg")) {
            openAttachment(app, url)

            val started = shadowOf(app).nextStartedActivity
            assertNotNull("'$url' must be opened", started)
            assertEquals(Intent.ACTION_VIEW, started.action)
            assertEquals(url, started.dataString)
        }
    }

    @Test
    fun `anything that is not an https address is refused without launching anything`() {
        val hostile = listOf(
            "intent://scan/#Intent;component=com.victim/.Exported;end",
            "content://com.victim.provider/secret",
            "file:///data/data/com.distronode.districtai/shared_prefs/x.xml",
            "http://media.example.test/a.jpg",
            "javascript:alert(1)",
            "/api/district/media/a.jpg",
            "//media.example.test/a.jpg",
            "https:media.example.test",
            "https:///a.jpg",
            "",
        )

        for (url in hostile) {
            openAttachment(app, url)

            assertNull("'$url' must not reach startActivity", shadowOf(app).nextStartedActivity)
        }
    }
}
