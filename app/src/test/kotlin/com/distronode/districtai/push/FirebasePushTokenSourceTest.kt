package com.distronode.districtai.push

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the FCM token source answers, against real Play Services `Task` objects.
 *
 * ⛔ EVERY FAILURE IS `null`, and on this path failure is the ordinary case: no default
 * `FirebaseApp` for an unrecognised package, no Play Services on the device, a token the SDK is
 * still provisioning. A null means [PushRegistrar] sends nothing, which is the contract.
 *
 * ⚠️ A `Task` delivers its listener on the MAIN looper, which Robolectric holds paused, so each
 * read is started, the looper drained, and the result read back. Nothing here waits on a clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class FirebasePushTokenSourceTest {

    @Before
    fun noDefaultFirebaseApp() {
        // ⚠️ The application's `onCreate` initialises the debug package's app, and the registry is
        // static across tests; the real source is asserted against the state an unknown package
        // leaves, which is no app at all.
        val context: Context = ApplicationProvider.getApplicationContext()
        FirebaseApp.getApps(context).toList().forEach(FirebaseApp::delete)
    }

    private fun read(source: FirebasePushTokenSource): String? {
        val scope = TestScope(UnconfinedTestDispatcher())
        val token = scope.async { source.currentToken() }
        shadowOf(Looper.getMainLooper()).idle()
        return token.getCompleted()
    }

    @Test
    fun `a token the SDK holds is handed over as it is`() {
        assertEquals("fcm-token-1", read(FirebasePushTokenSource { Tasks.forResult("fcm-token-1") }))
    }

    @Test
    fun `no FirebaseApp answers null instead of throwing`() {
        // The production constructor, against a process with no default app: `getInstance()` throws.
        assertNull(read(FirebasePushTokenSource()))
    }

    @Test
    fun `a failed token task answers null`() {
        assertNull(
            read(FirebasePushTokenSource { Tasks.forException(java.io.IOException("SERVICE_NOT_AVAILABLE")) }),
        )
    }

    @Test
    fun `a cancelled token task answers null and does not leave the caller hanging`() {
        assertNull(read(FirebasePushTokenSource { Tasks.forCanceled() }))
    }

    @Test
    fun `a blank token is no token`() {
        assertNull(read(FirebasePushTokenSource { Tasks.forResult("  ") }))
        assertNull(read(FirebasePushTokenSource { Tasks.forResult("") }))
    }

    @Test
    fun `a task still pending leaves the read suspended rather than answering early`() {
        val pending = TaskCompletionSource<String>()
        val scope = TestScope(UnconfinedTestDispatcher())
        val token = scope.async { FirebasePushTokenSource { pending.task }.currentToken() }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(token.isCompleted)

        pending.setResult("late-token")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("late-token", token.getCompleted())
    }
}
