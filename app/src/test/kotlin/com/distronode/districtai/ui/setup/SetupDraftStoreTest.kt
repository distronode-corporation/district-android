// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.setup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The step 1 draft kept on the device between steps 1 and 3.
 *
 * ⚠️ AN UNREADABLE DRAFT IS NO DRAFT: whatever an older build (or a damaged file) left under the key,
 * [PreferencesSetupDraftStore.load] answers null or a draft, never an exception, so the wizard still opens.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SetupDraftStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val raw = context.getSharedPreferences("district_setup_drafts", Context.MODE_PRIVATE)
    private val store = PreferencesSetupDraftStore(context)

    @Before
    fun empty() {
        raw.edit().clear().commit()
    }

    private fun plant(workspaceId: String, value: String) {
        raw.edit().putString("draft.$workspaceId", value).commit()
    }

    @Test
    fun `a saved draft reads back field for field`() {
        val draft = SetupBusinessDraft("Clinique Lumière", "CA", "QC", "Gatineau", "fr")
        store.save(draft, "ws_1")
        assertEquals(draft, store.load("ws_1"))
    }

    @Test
    fun `nothing saved reads as no draft`() {
        assertNull(store.load("ws_1"))
    }

    @Test
    fun `each workspace has its own draft, and clearing one leaves the other`() {
        val first = SetupBusinessDraft("One", "CA", "ON", "Toronto", "en")
        val second = SetupBusinessDraft("Two", "US", "NY", "Buffalo", "en")
        store.save(first, "ws_1")
        store.save(second, "ws_2")

        store.clear("ws_1")

        assertNull(store.load("ws_1"))
        assertEquals(second, store.load("ws_2"))
    }

    @Test
    fun `a value that is not JSON reads as no draft`() {
        plant("ws_1", "{not json")
        assertNull(store.load("ws_1"))
    }

    @Test
    fun `JSON that is not an object reads as no draft`() {
        plant("ws_1", "[\"Clinique\"]")
        assertNull(store.load("ws_1"))
    }

    @Test
    fun `an object with none of the fields reads as an empty English draft`() {
        plant("ws_1", "{}")
        assertEquals(SetupBusinessDraft("", "", "", "", SetupLanguage.EN.wire), store.load("ws_1"))
    }

    @Test
    fun `fields of another type read as absent, not as their text`() {
        plant(
            "ws_1",
            """{"businessName":7,"country":null,"subdivision":true,"city":{"x":1},"language":["fr"]}""",
        )
        assertEquals(SetupBusinessDraft("", "", "", "", SetupLanguage.EN.wire), store.load("ws_1"))
    }
}
