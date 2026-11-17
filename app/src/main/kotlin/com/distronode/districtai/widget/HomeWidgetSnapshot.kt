package com.distronode.districtai.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * What the home-screen widget shows, as the app last wrote it.
 *
 * ⛔ NO CREDENTIAL, CONTACT, NUMBER OR MESSAGE TEXT, EVER. A launcher can be screenshotted, and
 * the widget is drawn while the phone is unlocked by anyone holding it. The workspace's name and
 * two counts are what the iOS widget shows too, and nothing more.
 *
 * @param unread the workspace's unread messages, or null when no read has answered yet for THIS
 *   workspace. ⚠️ Null is drawn as a hyphen, never as zero: zero is a claim.
 * @param callsLast7Days the Overview's "last 7 days" count, or null as above.
 * @param callsReadAtMillis when [callsLast7Days] was read. The window moves, so the widget stops
 *   showing a count a day after it was read ([HomeWidgetFace.CALLS_VALID_MILLIS]).
 */
data class HomeWidgetSnapshot(
    val workspaceId: String,
    val workspaceName: String,
    val unread: Int? = null,
    val callsLast7Days: Int? = null,
    val callsReadAtMillis: Long? = null,
)

/** Where the snapshot lives. An interface so the publisher's rules are tested without a device. */
interface HomeWidgetStore {
    fun read(): HomeWidgetSnapshot?
    fun write(snapshot: HomeWidgetSnapshot)
    fun clear()
}

/**
 * The snapshot in this app's private preferences.
 *
 * ⚠️ NOT SHARED STORAGE, AND IT NEEDS NONE. An Android widget's provider runs in the APP's process
 * and reads the app's own files, unlike the iOS extension, which is why iOS needed a Keychain group.
 * `allowBackup="false"` in the manifest keeps this file off any backup, like every other one.
 *
 * ⚠️ `apply()`, NOT `commit()`: the in-memory map updates at once, and the provider reads through
 * the same process-wide instance, so the redraw that follows a write already sees it.
 */
class PreferencesHomeWidgetStore(context: Context) : HomeWidgetStore {

    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): HomeWidgetSnapshot? {
        val id = preferences.getString(KEY_WORKSPACE_ID, null) ?: return null
        val name = preferences.getString(KEY_WORKSPACE_NAME, null) ?: return null
        return HomeWidgetSnapshot(
            workspaceId = id,
            workspaceName = name,
            unread = preferences.intOrNull(KEY_UNREAD),
            callsLast7Days = preferences.intOrNull(KEY_CALLS),
            callsReadAtMillis = if (preferences.contains(KEY_CALLS_READ_AT)) {
                preferences.getLong(KEY_CALLS_READ_AT, 0L)
            } else {
                null
            },
        )
    }

    override fun write(snapshot: HomeWidgetSnapshot) {
        preferences.edit().apply {
            clear()
            putString(KEY_WORKSPACE_ID, snapshot.workspaceId)
            putString(KEY_WORKSPACE_NAME, snapshot.workspaceName)
            snapshot.unread?.let { putInt(KEY_UNREAD, it) }
            snapshot.callsLast7Days?.let { putInt(KEY_CALLS, it) }
            snapshot.callsReadAtMillis?.let { putLong(KEY_CALLS_READ_AT, it) }
            apply()
        }
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private fun SharedPreferences.intOrNull(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private companion object {
        const val PREFERENCES_NAME = "district_home_widget"
        const val KEY_WORKSPACE_ID = "workspace_id"
        const val KEY_WORKSPACE_NAME = "workspace_name"
        const val KEY_UNREAD = "unread"
        const val KEY_CALLS = "calls_last_7_days"
        const val KEY_CALLS_READ_AT = "calls_read_at"
    }
}
