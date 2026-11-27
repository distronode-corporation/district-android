// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.MainActivity
import com.distronode.districtai.R
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the widget draws. ⚠️ Rendered by applying the RemoteViews to a real view tree, so a test
 * reads the text a launcher would show rather than the calls that were made.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class HomeWidgetProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun render(face: HomeWidgetFace?): View =
        homeWidgetViews(context, face).apply(context, FrameLayout(context))

    private fun View.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun View.spoken(id: Int): String = findViewById<TextView>(id).contentDescription.toString()

    // ── The face ─────────────────────────────────────────────────────────────

    @Test
    fun `no snapshot is the signed-out face`() {
        assertNull(HomeWidgetFace.of(null, NOW))
    }

    @Test
    fun `a call count read within the day is shown`() {
        val snapshot = HomeWidgetSnapshot("ws-a", "Alpha", unread = 3, callsLast7Days = 12, callsReadAtMillis = NOW)

        assertEquals(HomeWidgetFace("Alpha", unread = 3, calls = 12), HomeWidgetFace.of(snapshot, NOW))
        assertEquals(
            HomeWidgetFace("Alpha", unread = 3, calls = 12),
            HomeWidgetFace.of(snapshot, NOW + HomeWidgetFace.CALLS_VALID_MILLIS - 1),
        )
    }

    @Test
    fun `a call count a day old, or read in the future, or never read, is a hyphen`() {
        val read = HomeWidgetSnapshot("ws-a", "Alpha", unread = 3, callsLast7Days = 12, callsReadAtMillis = NOW)

        assertNull("a day old", HomeWidgetFace.of(read, NOW + HomeWidgetFace.CALLS_VALID_MILLIS)?.calls)
        assertNull("the clock went back", HomeWidgetFace.of(read, NOW - 1)?.calls)
        assertNull("never read", HomeWidgetFace.of(HomeWidgetSnapshot("ws-a", "Alpha"), NOW)?.calls)
        assertEquals("the unread count does not age", 3, HomeWidgetFace.of(read, NOW + DAY * 3)?.unread)
    }

    // ── The views ────────────────────────────────────────────────────────────

    @Test
    fun `signed out, only the sign-in line shows`() {
        val view = render(null)

        assertEquals(View.GONE, view.findViewById<View>(R.id.home_widget_signed_in).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.home_widget_signed_out).visibility)
        assertEquals(str(R.string.widget_signed_out), view.text(R.id.home_widget_signed_out))
    }

    @Test
    fun `signed in, the name and both counts show`() {
        val view = render(HomeWidgetFace("Alpha", unread = 3, calls = 12))

        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.home_widget_signed_in).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.home_widget_signed_out).visibility)
        assertEquals("Alpha", view.text(R.id.home_widget_name))
        assertEquals("3", view.text(R.id.home_widget_unread_value))
        assertEquals("3", view.spoken(R.id.home_widget_unread_value))
        assertEquals("12", view.text(R.id.home_widget_calls_value))
    }

    @Test
    fun `a count not known is a hyphen, never zero, and says so to TalkBack`() {
        val view = render(HomeWidgetFace("Alpha", unread = null, calls = null))

        assertEquals("-", view.text(R.id.home_widget_unread_value))
        assertEquals("-", view.text(R.id.home_widget_calls_value))
        assertEquals(str(R.string.widget_count_unknown), view.spoken(R.id.home_widget_calls_value))
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `in French a count is grouped the French way`() {
        val view = render(HomeWidgetFace("Alpha", unread = 1204, calls = 0))

        assertEquals(DistrictFormat.number(1204.0, AppLanguage.FR), view.text(R.id.home_widget_unread_value))
        assertEquals("0", view.text(R.id.home_widget_calls_value))
    }

    @Test
    fun `a tap opens the app as its launcher icon does`() {
        val view = render(null)

        view.findViewById<View>(R.id.home_widget_root).performClick()

        val started: Intent = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity
        assertEquals(ComponentName(context, MainActivity::class.java), started.component)
        assertEquals(Intent.ACTION_MAIN, started.action)
        assertTrue(started.hasCategory(Intent.CATEGORY_LAUNCHER))
    }

    // ── The provider ─────────────────────────────────────────────────────────

    @Test
    fun `a placed widget draws the stored snapshot, and a redraw draws the new one`() {
        val manager = AppWidgetManager.getInstance(context)
        val id = shadowOf(manager).createWidget(HomeWidgetProvider::class.java, R.layout.home_widget)

        // Placed with nothing stored: the system's update drew the signed-out face.
        val placed = shadowOf(manager).getViewFor(id)
        assertEquals(View.VISIBLE, placed.findViewById<View>(R.id.home_widget_signed_out).visibility)

        PreferencesHomeWidgetStore(context).write(HomeWidgetSnapshot("ws-a", "Alpha", unread = 3))
        HomeWidgetProvider.redraw(context)

        val view = shadowOf(manager).getViewFor(id)
        assertEquals("Alpha", view.text(R.id.home_widget_name))
        assertEquals("3", view.text(R.id.home_widget_unread_value))
    }

    @Test
    fun `a redraw with no widget placed does nothing`() {
        PreferencesHomeWidgetStore(context).write(HomeWidgetSnapshot("ws-a", "Alpha"))

        HomeWidgetProvider.redraw(context)

        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, HomeWidgetProvider::class.java))
        assertEquals(0, ids.size)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY = 24L * 60 * 60 * 1000
    }
}
