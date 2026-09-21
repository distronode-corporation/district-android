package com.distronode.districtai.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The installation identity sent with a token exchange.
 *
 * ⛔ THE STABILITY IS THE SECURITY PROPERTY, NOT AN OPTIMISATION. `deviceId` exists so one
 * installation's sessions can be revoked without touching the others, and the device holds a
 * refresh token whose `deviceId` the server has recorded. A value that changed between calls
 * would leave the device unable to reproduce the id its own credential was bound to, so every
 * re-login would look like a new install and dead session rows would accumulate server-side.
 *
 * ⛔ AND IT IS NOT A HARDWARE IDENTIFIER, DELIBERATELY. `ANDROID_ID`, the advertising ID and
 * anything derived from IMEI are declarable tracking identifiers under Play's Data Safety rules.
 * A random UUID identifies the INSTALL, which is all the server needs, and it is what keeps the
 * Data Safety declaration clean. ⚠️ A test cannot prove a negative here; what it can do is pin the
 * SHAPE, so a change to a 16-hex-character `ANDROID_ID` or a 15-digit IMEI fails rather than
 * shipping.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DeviceIdentityTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun identity() = DeviceIdentity(context())

    @Test
    fun `the same instance returns the same id every time`() {
        val subject = identity()

        assertEquals(subject.deviceId(), subject.deviceId())
    }

    @Test
    fun `a second instance reads the id the first one persisted`() {
        // ⛔ The real case: the process restarts, or `AppContainer` builds the login flow lazily
        // after something else already asked. `commit()` rather than `apply()` is what makes this
        // hold even when the very next thing is a token exchange.
        val first = identity().deviceId()

        assertEquals(first, identity().deviceId())
    }

    @Test
    fun `the id is a UUID, not a hardware identifier`() {
        // ⚠️ Shape, not provenance — see the class note. 36 characters with dashes in the UUID
        // positions rules out ANDROID_ID (16 hex) and an IMEI (15 digits).
        val id = identity().deviceId()

        assertEquals(36, id.length)
        assertTrue(
            "Expected a canonical UUID, got '$id'",
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(id),
        )
    }

    @Test
    fun `the id fits the server's 8 to 200 character window`() {
        val id = identity().deviceId()

        assertTrue(id.length in 8..200)
    }

    @Test
    fun `a fresh install gets a different id`() {
        val first = identity().deviceId()
        context().getSharedPreferences("district_device_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()

        assertNotEquals(first, identity().deviceId())
    }

    @Test
    fun `the device name is non-blank and inside the server's 120 character limit`() {
        // ⚠️ Display only — the server's schema comment says it is never trusted — but a long
        // manufacturer string would still cause a 400, which is what the cap is for.
        val name = identity().deviceName()

        assertTrue(name.isNotBlank())
        assertTrue("'$name' exceeds the server limit", name.length <= 120)
    }

    @Test
    fun `the device name is stable across instances`() {
        assertEquals(identity().deviceName(), identity().deviceName())
    }
}
