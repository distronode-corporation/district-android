package com.distronode.districtai.core.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * [TokenStore] that encrypts values with an AES-GCM key held in the Android Keystore and
 * keeps the ciphertext in ordinary SharedPreferences.
 *
 * ⛔ WHY NOT EncryptedSharedPreferences. That was the obvious choice and it is DEPRECATED.
 * Not just its scheme enums — the `EncryptedSharedPreferences` and `MasterKey` classes
 * themselves are deprecated, confirmed by the Kotlin compiler ("'class
 * EncryptedSharedPreferences' is deprecated"), and the androidx.security.crypto package
 * summary names the successor explicitly: "Use javax.crypto.KeyGenerator with
 * AndroidKeyStore instance instead." That is exactly what this class does. Storing the
 * app's longest-lived credential — a 60-day refresh token — behind a deprecated API with a
 * documented replacement was not worth the convenience.
 *
 * ⛔ THIS IS NOT HAND-ROLLED CRYPTOGRAPHY, and the parts that make that true live in
 * [KeystoreCipher] — the key never leaves the Keystore, the cipher is the platform's
 * AES/GCM/NoPadding, and the IV is the provider's. This class owns only WHAT is stored.
 *
 * ⛔ FAIL CLOSED, ALWAYS. [KeystoreCipher] returns null on ANY failure rather than throwing, and
 * every read here treats that as "there is no usable session". A key invalidated by a
 * device-security change, a Keystore that will not load, a value written by an older scheme — all
 * of them have the same correct outcome, a clean re-login. Throwing would crash on launch
 * instead, and returning a partially-decoded session would be worse still.
 *
 * ⚠️ `android:allowBackup="false"` IN THE APP MANIFEST REMAINS LOAD-BEARING. The Keystore key
 * does not travel with a backup, so a restored ciphertext is permanently undecryptable. With
 * fail-closed reads that degrades to a re-login rather than a crash, but the user-visible
 * result is still a mysterious sign-out on a restored device.
 *
 * ⚠️ `commit()`, NOT `apply()`, THROUGHOUT. `apply()` writes asynchronously, which is
 * normally right and is exactly wrong here: the pending-refresh marker exists to survive a
 * process death that may happen milliseconds later, and an `apply()`d marker is as lost as
 * the response it guards against. The cost is a synchronous write at most once per refresh.
 */
class KeystoreTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val cipher = KeystoreCipher()

    override fun read(): PersistedSession? {
        val refreshToken = prefs.decrypt(cipher, KEY_REFRESH_TOKEN) ?: return null
        val deviceId = prefs.decrypt(cipher, KEY_DEVICE_ID) ?: return null
        val expiresAt = prefs.decrypt(cipher, KEY_REFRESH_EXPIRES_AT)?.toLongOrNull() ?: return null
        // An absent or nonsensical expiry means a corrupt record, not merely a missing one.
        // Fail closed: no session.
        if (expiresAt <= 0L) return null
        return PersistedSession(refreshToken, expiresAt, deviceId)
    }

    override fun write(session: PersistedSession) {
        // If encryption fails there is nothing safe to store, and silently persisting
        // plaintext would be the worst possible fallback. Clear instead, forcing re-login.
        val refresh = cipher.encrypt(session.refreshToken)
        val device = cipher.encrypt(session.deviceId)
        val expiry = cipher.encrypt(session.refreshTokenExpiresAt.toString())
        if (refresh == null || device == null || expiry == null) {
            wipe()
            return
        }
        prefs.edit()
            .putString(KEY_REFRESH_TOKEN, refresh)
            .putString(KEY_DEVICE_ID, device)
            .putString(KEY_REFRESH_EXPIRES_AT, expiry)
            .commit()
    }

    override fun clear() = wipe()

    override fun pendingRefreshToken(): String? = prefs.decrypt(cipher, KEY_PENDING_REFRESH)

    override fun markRefreshPending(refreshToken: String) {
        val encrypted = cipher.encrypt(refreshToken) ?: return
        prefs.edit().putString(KEY_PENDING_REFRESH, encrypted).commit()
    }

    override fun clearRefreshPending() {
        prefs.edit().remove(KEY_PENDING_REFRESH).commit()
    }

    override fun pendingRevokeToken(): String? = prefs.decrypt(cipher, KEY_PENDING_REVOKE)

    override fun markRevokePending(refreshToken: String) {
        val encrypted = cipher.encrypt(refreshToken) ?: return
        prefs.edit().putString(KEY_PENDING_REVOKE, encrypted).commit()
    }

    override fun clearRevokePending() {
        prefs.edit().remove(KEY_PENDING_REVOKE).commit()
    }

    /**
     * Drop the session, keeping the revoke outbox.
     *
     * ⛔ THE ONE EXCEPTION TO "clear() CLEARS EVERYTHING", AND IT IS LOAD-BEARING. `signOut`
     * writes [KEY_PENDING_REVOKE] and then calls `TokenRefreshCoordinator.forget()`, which
     * calls [clear] — so a `prefs.edit().clear()` here would erase the outbox entry
     * microseconds after it was written, silently restoring the exact fail-open the outbox
     * exists to close (a device that looks signed out while the server honours its refresh
     * token for another 60 days). See [TokenStore.pendingRevokeToken] for why a stale entry
     * cannot harm a later session.
     *
     * ⚠️ THE CIPHERTEXT IS CARRIED ACROSS VERBATIM, NEVER DECRYPTED AND RE-ENCRYPTED. A
     * round trip would fail closed on an unavailable Keystore — which is the whole design of
     * this class everywhere else — and here failing closed would DROP the outbox rather than
     * merely report no session.
     *
     * ⚠️ Still clears [KEY_PENDING_REFRESH]: that marker describes this session, so a copy
     * left behind would make the NEXT login's first refresh look interrupted.
     */
    private fun wipe() {
        val pendingRevoke = prefs.getString(KEY_PENDING_REVOKE, null)
        val editor = prefs.edit().clear()
        if (pendingRevoke != null) editor.putString(KEY_PENDING_REVOKE, pendingRevoke)
        editor.commit()
    }

    private companion object {
        /**
         * ⚠️ Renaming this orphans every existing install's credential — the old file is
         * never read again, so every user silently lands on the login screen. Migrate rather
         * than rename. The KEY alias has the same property and lives in [KeystoreCipher].
         */
        const val PREFS_FILE = "district_native_session"

        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_REFRESH_EXPIRES_AT = "refresh_token_expires_at"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PENDING_REFRESH = "pending_refresh_token"

        /** ⚠️ Survives [wipe] — see the ⛔ there. */
        const val KEY_PENDING_REVOKE = "pending_revoke_token"
    }
}

/**
 * Read and decrypt one preference.
 *
 * ⛔ FILE-SCOPED RATHER THAN A MEMBER OF [KeystoreTokenStore], AND THAT IS FORCED. [TokenStore]
 * declares nine methods, plus `wipe`, which puts the class exactly AT detekt's 11-function
 * ceiling — a member helper tips it over, and the only remaining fixes would be raising a
 * threshold or splitting a class along a seam that does not exist. Taking the cipher as a
 * parameter keeps it out of the class without duplicating anything.
 *
 * ⚠️ An absent key reads as null, which is the same answer as an undecryptable one — both mean
 * "there is no usable value here", and making callers distinguish them would invite one of the
 * two to be handled and the other forgotten.
 */
private fun SharedPreferences.decrypt(cipher: KeystoreCipher, prefKey: String): String? =
    cipher.decrypt(getString(prefKey, null))
