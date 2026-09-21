package com.distronode.districtai.auth

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * The installation identity sent with a token exchange.
 *
 * ⛔ NOT A HARDWARE IDENTIFIER, DELIBERATELY. `ANDROID_ID`, the advertising ID and anything
 * derived from IMEI or the serial number are all either restricted, resettable in ways that
 * would silently orphan the session, or — most importantly — declarable tracking identifiers
 * under Play's Data Safety rules. A random UUID generated on first launch identifies the
 * INSTALL, which is all the server needs: `deviceId` exists so one installation's sessions can
 * be revoked without touching the others.
 *
 * ⚠️ The server requires 8..200 characters. A UUID is 36, comfortably inside.
 *
 * ⚠️ Stored in ordinary (unencrypted) preferences on purpose: it is not a secret, and putting it
 * behind the Keystore would mean a Keystore failure — which the token store treats as "no
 * session" — also destroyed the device's stable identity, making every re-login look like a new
 * install and accumulating dead session rows server-side.
 */
class DeviceIdentity(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        // commit(), not apply(): the very next thing that happens is a token exchange that binds
        // the server's session row to this value. Losing it to an async write would leave the
        // device holding a refresh token whose deviceId it can no longer reproduce.
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    /**
     * Human label for the settings device list.
     *
     * ⚠️ Display only — the server's own schema comment says it is never trusted. Capped at the
     * server's 120-character limit here so a long manufacturer string cannot cause a 400.
     */
    fun deviceName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(MAX_DEVICE_NAME)

    private companion object {
        const val PREFS_FILE = "district_device_identity"
        const val KEY_DEVICE_ID = "device_id"
        const val MAX_DEVICE_NAME = 120
    }
}
