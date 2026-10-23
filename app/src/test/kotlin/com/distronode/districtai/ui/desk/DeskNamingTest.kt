package com.distronode.districtai.ui.desk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The product area's name: "Desk" in English and « Comptoir » in French, everywhere (owner decision,
 * 2026-10-23). It used to be "Customer desk" / « Comptoir clients » in places and "the desk" in
 * others. « Comptoir » as a title or label and « le comptoir » in running text (« Le comptoir … »
 * at the start of a sentence); English capitalises it in running text too ("the Desk").
 *
 * ⚠️ ITS SETTINGS STAY « Paramètres du comptoir » ON ANDROID. The decision names « Réglages du
 * comptoir », but frcheck's OS-WORD rule (vendored, an error that cannot be waived) holds every
 * non-Apple catalog to « Paramètres », the word Android's own Settings app uses.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DeskNamingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Every string the desk's screens own, read in the device's current language. */
    private fun deskStrings(): Map<String, String> =
        R.string::class.java.fields
            .filter { it.name.startsWith("desk_") }
            .associate { it.name to context.getString(it.getInt(null)) }

    @Test
    fun `English names it Desk`() {
        assertEquals("Desk", context.getString(R.string.desk_title))
        assertEquals("Desk settings", context.getString(R.string.desk_settings_title))
        assertEquals("Desk settings", context.getString(R.string.desk_settings_open))
        assertEquals("Open the Desk", context.getString(R.string.desk_open))
        val old = deskStrings().filterValues { it.contains("customer desk", ignoreCase = true) }
        assertTrue("old name left in $old", old.isEmpty())
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `French names it Comptoir`() {
        assertEquals("Comptoir", context.getString(R.string.desk_title))
        assertEquals("Paramètres du comptoir", context.getString(R.string.desk_settings_title))
        assertEquals("Paramètres du comptoir", context.getString(R.string.desk_settings_open))
        assertEquals("Ouvrir le comptoir", context.getString(R.string.desk_open))
        val old = deskStrings().filterValues { it.contains("comptoir clients", ignoreCase = true) }
        assertTrue("old name left in $old", old.isEmpty())
    }
}
