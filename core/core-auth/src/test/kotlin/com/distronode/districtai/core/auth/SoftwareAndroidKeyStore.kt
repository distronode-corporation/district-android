package com.distronode.districtai.core.auth

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A software stand-in for the platform's `AndroidKeyStore` provider, for JVM tests only.
 *
 * WHAT IT IS FAITHFUL TO. The two operations [KeystoreCipher] asks of the real provider: a
 * `KeyStore` that answers "is there a key under this alias", and an AES `KeyGenerator` that
 * accepts a [KeyGenParameterSpec], creates a key of the requested size under the spec's alias and
 * keeps it there. Everything else (the cipher itself, the IV the cipher generates) is the JVM's
 * own AES/GCM, not a fake.
 *
 * WHAT IT CANNOT STAND IN FOR, stated so no test built on it claims more: hardware backing,
 * extraction resistance, and a key invalidated by a device-security change. The last is modelled
 * the only honest way available, by [forgetAllKeys], which leaves the store exactly as an
 * invalidated or backup-restored key would: a stored ciphertext and no key that can open it.
 *
 * INSTALLED AND REMOVED AROUND EACH TEST. It is registered under the platform's provider name,
 * which is process-wide state, and [KeystoreTokenStoreTest] depends on that name being ABSENT to
 * prove the store fails closed. [uninstall] must run in an `@After`.
 */
internal class SoftwareAndroidKeyStore : Provider(NAME, 1.0, "Software AndroidKeyStore for JVM tests") {

    private val storedKeys = ConcurrentHashMap<String, SecretKey>()

    init {
        putService(
            object : Service(this, "KeyStore", NAME, SoftKeyStoreSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = SoftKeyStoreSpi(storedKeys)
            },
        )
        putService(
            object : Service(this, "KeyGenerator", "AES", SoftAesKeyGeneratorSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = SoftAesKeyGeneratorSpi(storedKeys)
            },
        )
    }

    /** The aliases a key has been generated under, in no particular order. */
    fun aliases(): Set<String> = storedKeys.keys.toSet()

    /** Drop every key, as a device-security change or a restore onto a new device would. */
    fun forgetAllKeys() = storedKeys.clear()

    fun install() {
        check(Security.getProvider(NAME) == null) { "an $NAME provider is already installed" }
        Security.addProvider(this)
    }

    fun uninstall() = Security.removeProvider(NAME)

    companion object {
        const val NAME = "AndroidKeyStore"
    }
}

private class SoftKeyStoreSpi(private val keys: MutableMap<String, SecretKey>) : KeyStoreSpi() {

    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

    // The default implementation refuses a key entry without a password; the platform's does not.
    override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
        keys[alias]?.let { KeyStore.SecretKeyEntry(it) }

    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

    override fun engineGetCertificate(alias: String): Certificate? = null

    override fun engineGetCreationDate(alias: String): Date? = null

    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
        keys[alias] = key as SecretKey
    }

    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
        throw UnsupportedOperationException("not used by KeystoreCipher")

    override fun engineSetCertificateEntry(alias: String, cert: Certificate) =
        throw UnsupportedOperationException("not used by KeystoreCipher")

    override fun engineDeleteEntry(alias: String) {
        keys.remove(alias)
    }

    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

    override fun engineContainsAlias(alias: String): Boolean = alias in keys

    override fun engineSize(): Int = keys.size

    override fun engineIsKeyEntry(alias: String): Boolean = alias in keys

    override fun engineIsCertificateEntry(alias: String): Boolean = false

    override fun engineGetCertificateAlias(cert: Certificate): String? = null

    override fun engineStore(stream: OutputStream?, password: CharArray?) =
        throw UnsupportedOperationException("the platform keystore is never written to a stream")

    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}

private class SoftAesKeyGeneratorSpi(private val keys: MutableMap<String, SecretKey>) : KeyGeneratorSpi() {

    private var alias: String? = null
    private var keySizeBits: Int = 0

    override fun engineInit(random: SecureRandom?) =
        throw InvalidAlgorithmParameterException("AndroidKeyStore keys are created from a KeyGenParameterSpec")

    override fun engineInit(keysize: Int, random: SecureRandom?) =
        throw InvalidAlgorithmParameterException("AndroidKeyStore keys are created from a KeyGenParameterSpec")

    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        val spec = params as? KeyGenParameterSpec
            ?: throw InvalidAlgorithmParameterException("expected a KeyGenParameterSpec, got $params")
        alias = spec.keystoreAlias
        keySizeBits = spec.keySize
    }

    override fun engineGenerateKey(): SecretKey {
        val bytes = ByteArray(keySizeBits / Byte.SIZE_BITS).also { SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(bytes, "AES")
        keys[checkNotNull(alias) { "the generator was never initialised" }] = key
        return key
    }
}
