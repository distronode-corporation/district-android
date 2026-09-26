package com.distronode.districtai.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The encrypted round trip that [KeystoreTokenStoreTest] states it cannot verify.
 *
 * That class proves the store fails CLOSED when there is no keystore at all. This one installs
 * [SoftwareAndroidKeyStore] under the platform's provider name, so the real [KeystoreCipher] and
 * the real [KeystoreTokenStore] run end to end over the JVM's own AES/GCM, and asserts what the
 * design promises once a key exists:
 *
 *   - what reaches disk is `base64(iv || ciphertext)`, never the plaintext;
 *   - the IV is the provider's and is fresh on every write (a reused GCM IV under one key destroys
 *     both confidentiality and authenticity);
 *   - a stored value that was altered, truncated, or whose key is absent reads as NO SESSION,
 *     never as a crash and never as a half-decoded credential;
 *   - the key is asked for with the spec the design depends on (AES-256, GCM, no padding,
 *     randomized encryption, no user authentication), because the double does not enforce it;
 *   - `clear()` carries the revoke outbox across VERBATIM, so it still decrypts afterwards.
 *
 * What the software provider cannot stand in for (hardware backing, extraction resistance,
 * authorisation enforcement, non-extractable key objects, and a key that is present but
 * invalidated) is listed on [SoftwareAndroidKeyStore] and is still verified on a device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class KeystoreRoundTripTest {

    private val keystore = SoftwareAndroidKeyStore()

    private val session = PersistedSession(
        refreshToken = "super-secret-refresh-token",
        refreshTokenExpiresAt = 1_805_184_000_000L,
        deviceId = "device-abcdefgh",
    )

    @Before
    fun setUp() {
        keystore.install()
    }

    @After
    fun tearDown() {
        keystore.uninstall()
    }

    private fun store() = KeystoreTokenStore(ApplicationProvider.getApplicationContext())

    private fun prefs(): SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun stored(key: String): String = checkNotNull(prefs().getString(key, null)) { "$key was not written" }

    @Test
    fun `a session written through the keystore reads back exactly, and nothing on disk is plaintext`() {
        store().write(session)

        assertEquals(session, store().read())
        assertEquals("one key, created on first use", setOf(KEY_ALIAS), keystore.aliases())
        val onDisk = prefs().all.values.map { it.toString() }
        assertEquals(3, onDisk.size)
        onDisk.forEach { value ->
            assertFalse(value.contains(session.refreshToken))
            assertFalse(value.contains(session.deviceId))
            // An IV, at least one byte of ciphertext and the 16-byte GCM tag.
            assertTrue(Base64.decode(value, Base64.NO_WRAP).size > IV_BYTES + TAG_BYTES)
        }
    }

    @Test
    fun `every write draws a fresh IV, so the same token never encrypts to the same bytes`() {
        store().write(session)
        val first = stored(KEY_REFRESH_TOKEN)
        store().write(session)
        val second = stored(KEY_REFRESH_TOKEN)

        assertNotEquals(first, second)
        val firstIv = Base64.decode(first, Base64.NO_WRAP).copyOf(IV_BYTES)
        val secondIv = Base64.decode(second, Base64.NO_WRAP).copyOf(IV_BYTES)
        assertFalse("a reused GCM IV under one key is the catastrophic mistake", firstIv.contentEquals(secondIv))
        assertEquals(session, store().read())
    }

    @Test
    fun `a ciphertext altered on disk fails authentication and reads as no session`() {
        store().write(session)
        val bytes = Base64.decode(stored(KEY_REFRESH_TOKEN), Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        prefs().edit().putString(KEY_REFRESH_TOKEN, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()

        assertNull(store().read())
    }

    @Test
    fun `a truncated stored value fails closed and reads as no session`() {
        store().write(session)
        prefs().edit()
            .putString(KEY_DEVICE_ID, Base64.encodeToString(ByteArray(IV_BYTES), Base64.NO_WRAP))
            .commit()

        assertNull(store().read())
    }

    @Test
    fun `an absent key makes the stored session unreadable, and the next login stores a new one`() {
        // A backup restored onto a new device: the ciphertext came across and the key did not, so
        // nothing can open it. The answer is a clean re-login. This is an ABSENT key only; a key
        // that is present but invalidated is not modelled by the double (see its class comment).
        store().write(session)
        keystore.forgetAllKeys()

        assertNull(store().read())

        store().write(session)
        assertEquals(session, store().read())
    }

    @Test
    fun `the key is generated with the spec the design depends on`() {
        // The double enforces none of this (see SoftwareAndroidKeyStore), so a spec that drifted
        // would still round trip here. Asserting the spec itself is what catches it.
        store().write(session)

        val spec = checkNotNull(keystore.lastKeySpec()) { "no key was generated" }
        assertEquals(KEY_ALIAS, spec.keystoreAlias)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes.toList())
        assertEquals(listOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings.toList())
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertEquals(256, spec.keySize)
        assertTrue("the provider, never the caller, picks the IV", spec.isRandomizedEncryptionRequired)
        assertFalse("background refresh runs with the screen locked", spec.isUserAuthenticationRequired)
    }

    @Test
    fun `a record missing its device id or holding a nonsense expiry is no session`() {
        store().write(session)
        prefs().edit().remove(KEY_DEVICE_ID).commit()
        assertNull("a partial record is not a session", store().read())

        // The expiry slot holding a decryptable value that is not a number.
        store().write(session)
        prefs().edit().putString(KEY_REFRESH_EXPIRES_AT, stored(KEY_REFRESH_TOKEN)).commit()
        assertNull("an unparsable expiry is a corrupt record", store().read())

        store().write(session.copy(refreshTokenExpiresAt = 0L))
        assertNull("a non-positive expiry is a corrupt record", store().read())
    }

    @Test
    fun `the refresh marker and the revoke outbox round trip, and clear keeps only the outbox`() {
        val subject = store()
        subject.write(session)
        subject.markRefreshPending("refresh-in-flight")
        subject.markRevokePending("refresh-to-revoke")

        assertEquals("refresh-in-flight", subject.pendingRefreshToken())
        assertEquals("refresh-to-revoke", subject.pendingRevokeToken())

        subject.clear()

        assertNull(subject.read())
        assertNull("the marker describes the session that was just cleared", subject.pendingRefreshToken())
        // Carried across as the same ciphertext, so it still opens with the same key.
        assertEquals("refresh-to-revoke", subject.pendingRevokeToken())

        subject.clearRevokePending()
        assertNull(subject.pendingRevokeToken())
    }

    @Test
    fun `clearing the refresh marker leaves the session in place`() {
        val subject = store()
        subject.write(session)
        subject.markRefreshPending(session.refreshToken)

        subject.clearRefreshPending()

        assertNull(subject.pendingRefreshToken())
        assertEquals(session, subject.read())
    }
}

/** Robolectric's newest supported level, the same as the other Robolectric tests in this module. */
private const val ROBOLECTRIC_SDK = 35

private const val PREFS_FILE = "district_native_session"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_REFRESH_EXPIRES_AT = "refresh_token_expires_at"
private const val KEY_DEVICE_ID = "device_id"
private const val KEY_ALIAS = "district_native_session_key_v1"
private const val IV_BYTES = 12
private const val TAG_BYTES = 16
