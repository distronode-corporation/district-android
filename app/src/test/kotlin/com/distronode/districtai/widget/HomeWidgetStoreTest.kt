package com.distronode.districtai.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The snapshot on disk: what is written is what a fresh store reads back, and nothing else. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class HomeWidgetStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `nothing written reads as nothing`() {
        assertNull(PreferencesHomeWidgetStore(context).read())
    }

    @Test
    fun `a full snapshot round-trips through a fresh store`() {
        val snapshot = HomeWidgetSnapshot("ws-a", "Alpha", unread = 3, callsLast7Days = 12, callsReadAtMillis = 42L)

        PreferencesHomeWidgetStore(context).write(snapshot)

        assertEquals(snapshot, PreferencesHomeWidgetStore(context).read())
    }

    @Test
    fun `counts not read yet stay unknown, and a later write drops counts it does not carry`() {
        val store = PreferencesHomeWidgetStore(context)
        store.write(HomeWidgetSnapshot("ws-a", "Alpha", unread = 3, callsLast7Days = 12, callsReadAtMillis = 42L))

        store.write(HomeWidgetSnapshot("ws-b", "Bravo"))

        assertEquals(HomeWidgetSnapshot("ws-b", "Bravo"), PreferencesHomeWidgetStore(context).read())
    }

    @Test
    fun `clear leaves nothing to read`() {
        val store = PreferencesHomeWidgetStore(context)
        store.write(HomeWidgetSnapshot("ws-a", "Alpha", unread = 3))

        store.clear()

        assertNull(PreferencesHomeWidgetStore(context).read())
    }

    @Test
    fun `a file with an id but no name reads as nothing`() {
        // Not a state this app writes; a half-written or hand-edited file must not draw a nameless card.
        context.getSharedPreferences("district_home_widget", Context.MODE_PRIVATE)
            .edit().putString("workspace_id", "ws-a").commit()

        assertNull(PreferencesHomeWidgetStore(context).read())
    }
}
