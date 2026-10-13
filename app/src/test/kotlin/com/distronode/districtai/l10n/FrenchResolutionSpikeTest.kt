package com.distronode.districtai.l10n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SPIKE (v3 fr-CA, do not merge). Which device locales resolve to values-fr-rCA, the only French
 * directory. Runs only with -Pdistrict.l10n.fr, which the test reads from a system property.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class FrenchResolutionSpikeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun report(label: String) {
        val config = context.resources.configuration
        val subtitle = context.getString(R.string.sign_in_subtitle)
        val attachments = context.resources.getQuantityString(R.plurals.thread_attachments, 2, 2)
        println(
            "SPIKE $label: config.locales=${config.locales} jvmDefault=${Locale.getDefault()} " +
                "sign_in_subtitle='$subtitle' thread_attachments(2)='$attachments'",
        )
    }

    private fun assertFrench(label: String) {
        assumeTrue(System.getProperty("district.l10n.fr") == "true")
        report(label)
        check(context.getString(R.string.sign_in_action) == "Se connecter") { "$label not French" }
    }

    @Test fun `default qualifiers are English`() {
        report("default")
        check(context.getString(R.string.sign_in_action) == "Sign in")
    }

    @Test @Config(qualifiers = "fr-rCA") fun `fr-CA`() = assertFrench("fr-rCA")

    @Test @Config(qualifiers = "fr-rFR") fun `fr-FR`() = assertFrench("fr-rFR")

    @Test @Config(qualifiers = "fr-rBE") fun `fr-BE`() = assertFrench("fr-rBE")

    @Test @Config(qualifiers = "fr-rCH") fun `fr-CH`() = assertFrench("fr-rCH")

    @Test @Config(qualifiers = "fr") fun `plain fr`() = assertFrench("fr")

    @Test @Config(qualifiers = "en-rCA") fun `en-CA stays English`() {
        report("en-rCA")
        check(context.getString(R.string.sign_in_action) == "Sign in")
    }

    @Test @Config(qualifiers = "w1280dp-h3000dp") fun `the suite's usual qualifiers carry which locale`() {
        report("w1280dp-h3000dp")
    }
}
