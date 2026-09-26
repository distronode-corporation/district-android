package com.distronode.districtai

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The production container, as the real application builds it.
 *
 * ⛔ ONE CONTAINER PER PROCESS IS A SECURITY PROPERTY, NOT A TIDINESS ONE. It holds the only
 * `TokenRefreshCoordinator`, and two coordinators can present the same refresh token concurrently,
 * which the server reads as theft and answers by revoking the whole token family. `MainActivity`
 * reads the container through [AppContainerOwner], so the application has to be that owner and
 * hand back the same instance on every read.
 *
 * ⚠️ BUILT WITH NO SEAMS AT ALL, exactly as production builds it, and nothing here reaches the
 * network: construction opens no socket, and the only work it starts is the revoke-outbox drain,
 * which finds nothing on a fresh install.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DistrictApplicationContainerTest {

    @Test
    fun `the application is the container's owner and builds exactly one`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        assertTrue("the manifest's application must be DistrictApplication", app is DistrictApplication)
        val owner = app as AppContainerOwner

        assertSame(owner.container, owner.container)
        assertSame(owner.container.tokenCoordinator, owner.container.tokenCoordinator)
    }
}
