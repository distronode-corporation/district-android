package com.distronode.districtai.core.data

import android.content.Context

/**
 * Where this device remembers which workspace the user is working in.
 *
 * ⛔ THIS IS THE NATIVE EQUIVALENT OF A COOKIE THE APP CANNOT HOLD. On the web the active
 * workspace is `distronode_workspace_id`, httpOnly, which the server's tenant listers promote
 * to index 0 so that every `requireWorkspaceRole` fallback resolves to it. A bearer-token
 * client sends no cookies, so `POST /api/district/workspace/select` would set a cookie this
 * app immediately discards. The selection therefore lives here and is sent explicitly as
 * `workspaceId` on every request, which the server re-validates against live membership.
 *
 * ⚠️ THE STORED VALUE IS A HINT AND IS NOT TRUSTED. It is an id the user chose earlier; by the
 * time it is read they may have been removed from that workspace or its subscription may have
 * lapsed. [WorkspaceRepository] only ever sends it after confirming it still appears in the
 * server's own list — the same structural argument the server makes about its cookie, where
 * the reorder is a no-op for an id the caller cannot see.
 *
 * ⚠️ NOT A SECRET, so no Keystore and no encryption: a workspace id is not a credential, and
 * knowing one grants nothing without a token that is a member of it. Contrast [TokenStore] in
 * core-auth, which holds something that IS a credential.
 */
interface WorkspaceSelectionStore {
    /** The id the user last chose on this device, or null if they never have. */
    fun selectedWorkspaceId(): String?

    /** Remember a choice. Passing null forgets it, returning to the server's own ordering. */
    fun setSelectedWorkspaceId(workspaceId: String?)
}

/**
 * SharedPreferences-backed selection.
 *
 * ⚠️ PLAIN SharedPreferences ON PURPOSE — no DataStore and no encryption. This is one short
 * string that must be readable while the first screen composes; DataStore's asynchronous read
 * would add a suspension point (and a dependency) to buy consistency guarantees that a single
 * independent value does not need.
 */
class PreferencesWorkspaceSelectionStore(context: Context) : WorkspaceSelectionStore {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun selectedWorkspaceId(): String? =
        preferences.getString(KEY_SELECTED_WORKSPACE_ID, null)

    override fun setSelectedWorkspaceId(workspaceId: String?) {
        preferences.edit().apply {
            if (workspaceId == null) {
                remove(KEY_SELECTED_WORKSPACE_ID)
            } else {
                putString(KEY_SELECTED_WORKSPACE_ID, workspaceId)
            }
            // ⚠️ apply(), not commit(): this is called from the UI thread when the user picks a
            // workspace, and losing the preference to a kill in the next few milliseconds costs
            // nothing — the next launch falls back to the server's ordering, which is the same
            // place a first-time user starts. commit() would block the thread for durability
            // that does not matter here. Contrast TokenStore, where a lost write DOES matter and
            // durability is required before the network call.
            apply()
        }
    }

    private companion object {
        // Separate from the token store's file so clearing a session cannot take the
        // (non-sensitive) workspace preference with it, and vice versa.
        const val PREFERENCES_NAME = "district_workspace_selection"
        const val KEY_SELECTED_WORKSPACE_ID = "selected_workspace_id"
    }
}
