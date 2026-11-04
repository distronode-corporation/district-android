package com.distronode.districtai.ui.dialer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The dialler's country memory: per workspace, two facts apart, and only codes the table knows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PreferencesDialerCountryMemoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `home and pick are kept per workspace and survive a new instance`() {
        val memory = PreferencesDialerCountryMemory(context)
        memory.rememberHome("CA", "ws-1")
        memory.rememberPicked("GB", "ws-1")
        memory.rememberPicked("FR", "ws-2")

        val again = PreferencesDialerCountryMemory(context)
        assertEquals("CA", again.home("ws-1"))
        assertEquals("GB", again.picked("ws-1"))
        assertEquals("FR", again.picked("ws-2"))
        assertNull(again.home("ws-2"))
    }

    /** ⛔ A code this version does not list reads as no country, never as a flag with no code. */
    @Test
    fun `a stored code the table does not know reads as none`() {
        val memory = PreferencesDialerCountryMemory(context)
        memory.rememberHome("ZZ", "ws-3")
        memory.rememberPicked("", "ws-3")
        assertNull(memory.home("ws-3"))
        assertNull(memory.picked("ws-3"))
    }
}
