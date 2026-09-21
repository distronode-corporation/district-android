package com.distronode.districtai.auth

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ApiEnvironment
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.marketplace.marketplaceWebUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ⛔ THIS TEST EXISTS BECAUSE A COMMENT WAS TRUSTED INSTEAD OF THE CODE. `DistrictNavHost`
 * asserted that this launcher "already sets FLAG_ACTIVITY_NEW_TASK, which is what makes
 * launching from [the application context] legal". It did not. `startActivity` on a
 * non-Activity context without that flag throws `AndroidRuntimeException`, which is a
 * RuntimeException and NOT an `ActivityNotFoundException` — so both of the launcher's catch
 * blocks missed it and the process died.
 *
 * ⛔ AND IT WAS REACHABLE FROM EXACTLY ONE PLACE, WHICH IS WHY MANUAL TESTING MISSED IT.
 * `MainActivity` passes an Activity, so sign-in — the flow anyone would exercise first —
 * was always safe. The application-context call site is the ACCOUNT-DELETION hand-off, a
 * flow that exists because Play requires it and that nobody taps casually.
 *
 * ⚠️ The assertion is on the FLAG, not on "it did not throw". Robolectric's shadow
 * `startActivity` does not enforce the real framework's context/flag rule, so a test that
 * only checked for an absent exception would pass against the broken code. Reading the flag
 * off the recorded intent is the only version of this test that can fail correctly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CustomTabsLauncherTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `launching from a non-activity context carries FLAG_ACTIVITY_NEW_TASK`() {
        CustomTabsLauncher.launch(app, "https://www.distronode.com/auth/native")

        val started = shadowOf(app).nextStartedActivity
        assertNotNull("the launcher must start something", started)
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK is what makes an application-context startActivity legal; " +
                "without it the framework throws AndroidRuntimeException and kills the process",
            started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `the marketplace hand-off opens the web dashboard on the app's own origin`() {
        // ⛔ THE INTENT IS THE WHOLE FEATURE, AND ITS DATA IS THE PART THAT CAN BE WRONG SILENTLY.
        // Buying a number stays off the phone under Google Play's Payments policy — see
        // `marketplaceWebUrl` — so the app's only job here is to hand the right URL to a browser.
        // A wrong path 404s in the browser, where nothing in this app reports it.
        //
        // ⚠️ Built from `ApiEnvironment.baseUrl` rather than a literal, exactly as the production
        // call site does, so a build pointed at another origin is covered by the same assertion.
        val url = marketplaceWebUrl(ApiEnvironment.baseUrl)

        CustomTabsLauncher.launch(app, url)

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://www.distronode.com/dashboard/district/marketplace", started.data.toString())
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `the account deletion url is opened as a plain view intent`() {
        // ⚠️ Pins the DATA as well as the flag: the deletion page is identity-verified on the
        // web, so sending the user anywhere else would silently break a Play requirement.
        val url = "https://www.distronode.com/privacy/account-deletion"

        CustomTabsLauncher.launch(app, url)

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.data.toString())
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
