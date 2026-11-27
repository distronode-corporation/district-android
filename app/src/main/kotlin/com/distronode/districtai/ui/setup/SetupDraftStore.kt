// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.setup

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The step 1 draft ([SetupBusinessDraft]), per workspace, on this device.
 *
 * ⚠️ PLAIN SharedPreferences, NOT THE KEYSTORE: a business name and a city are what the workspace
 * already shows every member, not a credential. Same reasoning as the workspace selection store.
 */
interface SetupDraftStore {
    fun load(workspaceId: String): SetupBusinessDraft?

    fun save(draft: SetupBusinessDraft, workspaceId: String)

    fun clear(workspaceId: String)
}

class PreferencesSetupDraftStore(context: Context) : SetupDraftStore {

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * ⚠️ AN UNREADABLE DRAFT IS NO DRAFT. A value written by a build whose draft had another shape
     * must not stop the wizard from opening; step 1 opens on the stored facts instead.
     */
    override fun load(workspaceId: String): SetupBusinessDraft? {
        val stored = preferences.getString(key(workspaceId), null) ?: return null
        val json = try {
            Json.parseToJsonElement(stored) as? JsonObject
        } catch (ignored: IllegalArgumentException) {
            // ⚠️ kotlinx.serialization's SerializationException is an IllegalArgumentException.
            null
        } ?: return null
        return SetupBusinessDraft(
            businessName = json.str(NAME).orEmpty(),
            country = json.str(COUNTRY).orEmpty(),
            subdivision = json.str(SUBDIVISION).orEmpty(),
            city = json.str(CITY).orEmpty(),
            language = json.str(LANGUAGE) ?: SetupLanguage.EN.wire,
        )
    }

    override fun save(draft: SetupBusinessDraft, workspaceId: String) {
        val encoded = buildJsonObject {
            put(NAME, draft.businessName)
            put(COUNTRY, draft.country)
            put(SUBDIVISION, draft.subdivision)
            put(CITY, draft.city)
            put(LANGUAGE, draft.language)
        }.toString()
        preferences.edit().putString(key(workspaceId), encoded).apply()
    }

    override fun clear(workspaceId: String) {
        preferences.edit().remove(key(workspaceId)).apply()
    }

    private fun key(workspaceId: String) = "draft.$workspaceId"

    private companion object {
        const val PREFERENCES_NAME = "district_setup_drafts"
        const val NAME = "businessName"
        const val COUNTRY = "country"
        const val SUBDIVISION = "subdivision"
        const val CITY = "city"
        const val LANGUAGE = "language"
    }
}
