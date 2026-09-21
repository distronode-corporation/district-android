package com.distronode.districtai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The blank-DSN guard in [DistrictSentry.initialize].
 *
 * ⛔ A SEPARATE CLASS FROM `DistrictSentryTest` BECAUSE THIS ONE NEEDS A REAL `Context` AND THAT
 * ONE MUST NOT. `DistrictSentryTest` asserts the option values on a plain `SentryOptions` and runs
 * on the bare JVM; putting a Robolectric case beside it would drag every one of those assertions
 * onto an Android runtime for no reason. Splitting them keeps the fast lane fast.
 *
 * ⛔ AND THERE IS DELIBERATELY NO TEST OF THE SUCCESSFUL PATH. Calling `initialize` with a real DSN
 * would run `SentryAndroid.init`, which installs a process-wide uncaught-exception handler, an ANR
 * watchdog and a background sender into the test JVM — none of which can be removed afterwards,
 * all of which would then be live for every test that runs later in the same worker, and one of
 * which would start trying to reach a Sentry ingest host from CI. The guard is the half that is
 * safe to exercise, and it is the half with a decision in it.
 *
 * ⛔ THESE ASSERT THE RETURN VALUE, NOT `Sentry.isEnabled()`, BECAUSE AN ASSERTION ON THE GLOBAL
 * COULD NEVER PASS. Measured against sentry-android-core 8.53.0: `Sentry.isEnabled()` is
 * **already true before any app code runs**,
 * and it stays true with `io.sentry.auto-init` set to `false` (the shipped manifest), and even in
 * an experiment that also removed BOTH `SentryInitProvider` and `SentryPerformanceProvider` from
 * the merged unit-test manifest (the shipped manifest keeps `SentryPerformanceProvider`). Nothing
 * this app controls moves that global, so an assertion on it measures the SDK's
 * own defaults rather than our guard, and reads as a crash-reporter bug when it fails.
 *
 * What this app is actually responsible for is narrower and is exactly what is asserted here:
 * **a blank DSN must not reach `SentryAndroid.init`.**
 *
 * ⚠️ THE STRONGER CLAIM IS DELIBERATELY NOT MADE. "The SDK is not armed when the DSN is blank" is
 * a statement about a device, and this suite runs on a JVM. It belongs on a device check (alongside
 * FCM delivery and Telecom audio routing), not quietly downgraded into a green test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DistrictSentryGuardTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a blank dsn arms nothing`() {
        assertFalse(DistrictSentry.initialize(context = context, dsn = "", environment = "release"))
    }

    @Test
    fun `a whitespace-only dsn arms nothing either`() {
        // ⚠️ `isBlank`, not `isEmpty`. A `buildConfigField` whose value came through a shell
        // variable that expanded to nothing yields spaces rather than an empty string, and an
        // `isEmpty` guard would hand that to the SDK as a malformed DSN.
        assertFalse(DistrictSentry.initialize(context = context, dsn = "   \t ", environment = "release"))
    }
}
