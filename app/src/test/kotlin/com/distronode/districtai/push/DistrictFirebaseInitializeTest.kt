package com.distronode.districtai.push

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Bringing Firebase up at application start, against Robolectric's real `FirebaseApp` registry.
 *
 * ⛔ FALSE IS AN ORDINARY ANSWER AND NEVER A THROW. An unregistered package and an initialisation
 * that fails both leave push absent, which every path tolerates; a throw from `Application.onCreate`
 * would take the whole app down on the device where it happened.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DistrictFirebaseInitializeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun noFirebaseApp() {
        // ⚠️ The registry is static and the application's own `onCreate` has already filled it.
        FirebaseApp.getApps(context).toList().forEach(FirebaseApp::delete)
    }

    @Test
    fun `the debug package brings up its own Firebase app`() {
        assertTrue(DistrictFirebase.initialize(context, DistrictFirebase.DEBUG_APPLICATION_ID))

        assertEquals(DistrictFirebase.DEBUG_FIREBASE_APP_ID, FirebaseApp.getInstance().options.applicationId)
    }

    @Test
    fun `an app that already exists is kept rather than initialised twice`() {
        DistrictFirebase.initialize(context, DistrictFirebase.DEBUG_APPLICATION_ID)

        assertTrue(DistrictFirebase.initialize(context, DistrictFirebase.RELEASE_APPLICATION_ID))
        assertEquals(DistrictFirebase.DEBUG_FIREBASE_APP_ID, FirebaseApp.getInstance().options.applicationId)
    }

    @Test
    fun `an unregistered package initialises nothing`() {
        assertFalse(DistrictFirebase.initialize(context, "com.example.staging"))

        assertTrue(FirebaseApp.getApps(context).isEmpty())
    }

    @Test
    fun `an initialisation that throws answers false instead of crashing startup`() {
        val broken = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = throw IllegalStateException("no application yet")
        }

        assertFalse(DistrictFirebase.initialize(broken, DistrictFirebase.DEBUG_APPLICATION_ID))
        assertTrue(FirebaseApp.getApps(context).isEmpty())
    }
}
