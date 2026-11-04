package com.distronode.districtai.ui.dialer

import android.content.Context

/**
 * What the dialler remembers about a workspace's country between launches.
 *
 * ⚠️ TWO FACTS, KEPT APART. The HOME country is derived from the workspace's own numbers
 * ([DialCountries.homeCountry]) and cached so the keypad opens on the right flag before the numbers
 * have loaded. The PICKED country is what the operator chose on a workspace with no home country,
 * so that "pick it once" holds: the next dial starts there. A home country, once known, wins.
 *
 * ⚠️ NOT A SERVER FIELD, ON PURPOSE. Adding a country to an API response would change the mobile
 * contracts; everything here is read from what the app already receives. (district-ios
 * `DialerCountryMemory`.)
 */
interface DialerCountryMemory {
    fun home(workspaceId: String): String?
    fun rememberHome(iso: String, workspaceId: String)
    fun picked(workspaceId: String): String?
    fun rememberPicked(iso: String, workspaceId: String)
}

/**
 * [DialerCountryMemory] in its own small preferences file.
 *
 * ⚠️ PLAIN SharedPreferences: a country code is not a secret, it is wiped with the app, and a stale
 * one costs one tap on the picker.
 *
 * ⛔ A STORED VALUE IS CHECKED AGAINST THE TABLE ON READ, so a code this version does not list (or a
 * corrupted value) reads as "no country" rather than a flag with no calling code.
 */
class PreferencesDialerCountryMemory(context: Context) : DialerCountryMemory {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun home(workspaceId: String): String? = known(prefs.getString(HOME + workspaceId, null))

    override fun rememberHome(iso: String, workspaceId: String) {
        prefs.edit().putString(HOME + workspaceId, iso).apply()
    }

    override fun picked(workspaceId: String): String? = known(prefs.getString(PICKED + workspaceId, null))

    override fun rememberPicked(iso: String, workspaceId: String) {
        prefs.edit().putString(PICKED + workspaceId, iso).apply()
    }

    private fun known(iso: String?): String? = iso?.takeIf { DialCountries.callingCode(it) != null }

    private companion object {
        const val FILE = "district_dialer_country"
        const val HOME = "home."
        const val PICKED = "picked."
    }
}
