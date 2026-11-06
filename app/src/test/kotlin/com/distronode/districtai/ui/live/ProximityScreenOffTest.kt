package com.distronode.districtai.ui.live

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.scheduling.admin.calendar.TestLifecycleOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * The takeover's proximity wake lock: acquired only while a takeover is on screen, and released
 * on hand back (or the call ending), on going to the background and on teardown.
 *
 * ⚠️ THE SHADOW'S LATEST LOCK IS STATIC ACROSS TESTS, so "no lock made" is checked as "no new lock".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ProximityScreenOffTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val owner = TestLifecycleOwner()
    private var takenOver by mutableStateOf(false)
    private var shown by mutableStateOf(true)

    private fun power(): PowerManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(PowerManager::class.java)

    @Before
    fun setUp() {
        ShadowPowerManager.clearWakeLocks()
        shadowOf(power()).setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
    }

    @After
    fun tearDown() {
        ShadowPowerManager.clearWakeLocks()
    }

    private fun screen() {
        owner.registry.currentState = Lifecycle.State.RESUMED
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (shown) ProximityScreenOff(active = takenOver)
            }
        }
        composeRule.waitForIdle()
    }

    private fun lock(): PowerManager.WakeLock? = ShadowPowerManager.getLatestWakeLock()

    /** Takes the call over on screen and returns the lock that took. */
    private fun takeOver(before: PowerManager.WakeLock?): PowerManager.WakeLock {
        takenOver = true
        composeRule.waitForIdle()
        val held = checkNotNull(lock()) { "no lock was made" }
        assertNotSame("a new lock", before, held)
        assertTrue(held.isHeld)
        return held
    }

    @Test
    fun `held only while taken over, and released on hand back or the call ending`() {
        val before = lock()
        screen()
        assertSame("listening makes no lock", before, lock())

        val held = takeOver(before)
        assertEquals(PROXIMITY_LOCK_TAG, shadowOf(held).tag)
        assertFalse("never counted, so one release frees it", shadowOf(held).isReferenceCounted)

        takenOver = false
        composeRule.waitForIdle()
        assertFalse("handed back", held.isHeld)
        assertEquals("acquired once", 1, shadowOf(held).timesHeld)
    }

    @Test
    fun `going to the background releases it`() {
        val before = lock()
        screen()
        val held = takeOver(before)

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitForIdle()

        assertFalse(held.isHeld)
    }

    @Test
    fun `the screen going away releases it`() {
        val before = lock()
        screen()
        val held = takeOver(before)

        shown = false
        composeRule.waitForIdle()

        assertFalse(held.isHeld)
    }

    @Test
    fun `a device with no proximity sensor gets no lock`() {
        shadowOf(power()).setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, false)
        val before = lock()
        screen()

        takenOver = true
        composeRule.waitForIdle()

        assertSame("no lock was made", before, lock())
    }
}
