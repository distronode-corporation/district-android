package com.distronode.districtai.l10n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The language the app follows: the device's (or, on Android 13+, the app's own), with every French
 * reading Canadian French, since French is built in from 3.0.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ResolvedLanguageTest {

    private fun resolved(): AppLanguage = ApplicationProvider.getApplicationContext<Context>().resolvedLanguage()

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `Canadian French is French`() = assertEquals(AppLanguage.FR, resolved())

    @Test
    @Config(qualifiers = "fr-rFR")
    fun `France's French reads the Canadian French`() = assertEquals(AppLanguage.FR, resolved())

    @Test
    @Config(qualifiers = "fr-rBE")
    fun `Belgian French reads the Canadian French`() = assertEquals(AppLanguage.FR, resolved())

    @Test
    @Config(qualifiers = "fr")
    fun `plain French reads the Canadian French`() = assertEquals(AppLanguage.FR, resolved())

    @Test
    @Config(qualifiers = "en-rCA")
    fun `Canadian English is English`() = assertEquals(AppLanguage.EN, resolved())

    @Test
    @Config(qualifiers = "de-rDE")
    fun `a language the app does not have is English`() = assertEquals(AppLanguage.EN, resolved())
}
