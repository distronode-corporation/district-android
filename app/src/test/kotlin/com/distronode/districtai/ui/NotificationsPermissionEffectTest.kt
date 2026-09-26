package com.distronode.districtai.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Whether the notification permission is asked for, by platform level.
 *
 * ⚠️ `POST_NOTIFICATIONS` exists from API 33. Below that the manifest merger drops it and a launch
 * would fail, so an older device must see no request at all.
 *
 * ⚠️ THE OLDER DEVICE IS SIMULATED BY SETTING `Build.VERSION.SDK_INT`, NOT BY `@Config(sdk = ...)`.
 * Only this module's single Robolectric runtime is available to the build, and the gate reads the
 * field at run time, so moving the field is the whole of what an older device changes here. It is
 * restored after every test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class NotificationsPermissionEffectTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val registry = ScriptedResultRegistry()

    private val runtimeSdk = Build.VERSION.SDK_INT

    @After
    fun restoreSdk() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", runtimeSdk)
    }

    private fun show() {
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry.owner) {
                NotificationsPermissionEffect()
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `Android 13 and later are asked for the notification permission once`() {
        show()

        assertEquals(listOf<Any?>(Manifest.permission.POST_NOTIFICATIONS), registry.launched)
    }

    @Test
    fun `Android 12 is not asked for a permission it does not have`() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.S_V2)

        show()

        assertEquals(emptyList<Any?>(), registry.launched)
    }
}
