package com.distronode.districtai.auth

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which browser the launcher pins, and what it does when there is none.
 *
 * WHY THE PIN IS THE FEATURE. This app is a verified App Link handler for some of the URLs it
 * hands to a browser, so an implicit `ACTION_VIEW` for one of those comes straight back into the
 * app. [CustomTabsLauncher.launchExternally] exists for exactly those URLs and must either pin a
 * real browser package or REFUSE; it may never fall back to an implicit intent. [CustomTabsLauncherTest]
 * covers the flags and the data of the implicit sign-in path; this class covers the browser
 * resolution under it, with the device's browsers staged through Robolectric's package manager.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CustomTabsLauncherBrowserTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val claimedUrl = "https://www.distronode.com/dashboard/district/marketplace"

    /** Answer the launcher's own `http://` probe with an activity in [packageName], or with none. */
    private fun stageWebHandler(packageName: String?) {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://"))
        val info = ResolveInfo().apply {
            activityInfo = packageName?.let { name ->
                ActivityInfo().apply {
                    this.packageName = name
                    this.name = "$name.BrowserActivity"
                }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(probe, info)
    }

    @Test
    fun `with one browser installed, a claimed URL opens in a custom tab pinned to that browser`() {
        stageWebHandler("org.example.browser")

        val result = CustomTabsLauncher.launchExternally(app, claimedUrl)

        assertEquals(CustomTabsLauncher.LaunchResult.OpenedCustomTab, result)
        val started = shadowOf(app).nextStartedActivity
        // Explicit, so App Links resolution cannot route it back into this app.
        assertEquals("org.example.browser", started.`package`)
        assertEquals(claimedUrl, started.data.toString())
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `with no browser at all, a claimed URL is refused rather than sent out implicitly`() {
        val result = CustomTabsLauncher.launchExternally(app, claimedUrl)

        assertTrue(result is CustomTabsLauncher.LaunchResult.NoBrowser)
        assertNull("an implicit intent here would loop back into this app", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `the platform chooser is not a browser, so it is never pinned`() {
        // Several browsers and no default resolve the probe to the system's resolver activity,
        // whose package is `android`. Pinning that makes every launch fail, so it reads as none.
        stageWebHandler("android")

        assertNull(CustomTabsLauncher.browserPackage(app))
        assertTrue(CustomTabsLauncher.launchExternally(app, claimedUrl) is CustomTabsLauncher.LaunchResult.NoBrowser)
    }

    @Test
    fun `a resolved entry with no activity behind it names no browser`() {
        stageWebHandler(packageName = null)

        assertNull(CustomTabsLauncher.browserPackage(app))
    }

    @Test
    fun `when nothing can open a link the launch reports both failures instead of crashing`() {
        // The framework throws ActivityNotFoundException for an intent nothing resolves; Robolectric
        // only does so once activity checking is on. Both attempts (the custom tab, then the plain
        // browser intent) fail, and the result carries each reason.
        shadowOf(app).checkActivities(true)

        val result = CustomTabsLauncher.launch(app, "https://www.distronode.com/auth/native")

        assertTrue("expected NoBrowser, got $result", result is CustomTabsLauncher.LaunchResult.NoBrowser)
        val refused = result as CustomTabsLauncher.LaunchResult.NoBrowser
        assertTrue(refused.customTabsReason.orEmpty().isNotEmpty())
        assertTrue(refused.browserReason.orEmpty().isNotEmpty())
    }
}
