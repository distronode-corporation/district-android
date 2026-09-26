package com.distronode.districtai.core.auth

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreException
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
 * WHAT IT CANNOT STAND IN FOR, stated so no test built on it claims more:
 *   - hardware backing: the key is ordinary bytes in this process's heap;
 *   - extraction resistance: nothing stops a caller reading those bytes back out;
 *   - authorisation enforcement: the platform refuses a key used outside its spec, and this double
 *     does not. Purposes, block modes, paddings and randomized encryption are NOT enforced here, so
 *     a cipher built against the wrong mode or with a caller-supplied IV would still work. The spec
 *     [KeystoreCipher] asks for is recorded instead ([lastKeySpec]) and asserted directly;
 *   - non-extractable key objects: the platform hands back an opaque key whose `encoded` is null,
 *     and this double hands back a [SecretKeySpec] with its bytes;
 *   - key invalidation, only approximately: on a device a key can be PRESENT under its alias and
 *     unusable, so the lookup succeeds and `Cipher.init` throws (`KeyPermanentlyInvalidatedException`
 *     or another `InvalidKeyException`). [forgetAllKeys] models an ABSENT key, which is a backup
 *     restored onto a new device. [plantUnusableKey] models the present-but-unusable case by the
 *     same symptom (a lookup that succeeds and an init that throws `InvalidKeyException`), not by
 *     the platform's cause.
 *
 * INSTALLED AND REMOVED AROUND EACH TEST. It is registered under the platform's provider name,
 * which is process-wide state, and [KeystoreTokenStoreTest] depends on that name being ABSENT to
 * prove the store fails closed. [uninstall] must run in an `@After`.
 */
internal class SoftwareAndroidKeyStore : Provider(NAME, 1.0, "Software AndroidKeyStore for JVM tests") {

    private val storedKeys = ConcurrentHashMap<String, SecretKey>()
    private val lastSpec = AtomicReference<KeyGenParameterSpec?>(null)
    private val lookupsLeft = AtomicInteger(UNLIMITED)

    init {
        putService(
            object : Service(this, "KeyStore", NAME, SoftKeyStoreSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = SoftKeyStoreSpi(storedKeys, lookupsLeft)
            },
        )
        putService(
            object : Service(this, "KeyGenerator", "AES", SoftAesKeyGeneratorSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = SoftAesKeyGeneratorSpi(storedKeys, lastSpec)
            },
        )
    }

    /** The aliases a key has been generated under, in no particular order. */
    fun aliases(): Set<String> = storedKeys.keys.toSet()

    /**
     * The last [KeyGenParameterSpec] any AES key generator on this provider was initialised with,
     * or null if none was. Nothing here enforces it (see the class), so tests assert it instead.
     */
    fun lastKeySpec(): KeyGenParameterSpec? = lastSpec.get()

    /**
     * Drop every key, as a backup restored onto a new device would: the key is ABSENT. This does
     * not model a key that is present but unusable, which is what a device-security change leaves.
     */
    fun forgetAllKeys() = storedKeys.clear()

    /**
     * Let the next [count] key lookups succeed and refuse every one after that, as a keystore that
     * stops answering partway through a sequence of operations does. [allowLookups] lifts it.
     */
    fun refuseLookupsAfter(count: Int) = lookupsLeft.set(count)

    fun allowLookups() = lookupsLeft.set(UNLIMITED)

    /**
     * Put a key under [alias] that the lookup returns and every AES cipher refuses at `init` with an
     * `InvalidKeyException`: the symptom a key invalidated by a device-security change shows.
     */
    fun plantUnusableKey(alias: String) {
        storedKeys[alias] = SecretKeySpec(ByteArray(UNUSABLE_KEY_BYTES), "AES")
    }

    /** The key object currently held under [alias], or null. */
    fun keyUnder(alias: String): SecretKey? = storedKeys[alias]

    fun install() {
        check(Security.getProvider(NAME) == null) { "an $NAME provider is already installed" }
        Security.addProvider(this)
    }

    fun uninstall() = Security.removeProvider(NAME)

    companion object {
        const val NAME = "AndroidKeyStore"
        private const val UNLIMITED = -1

        /** Not a legal AES key length, so `Cipher.init` rejects it. */
        private const val UNUSABLE_KEY_BYTES = 7
    }
}

private class SoftKeyStoreSpi(
    private val keys: MutableMap<String, SecretKey>,
    private val lookupsLeft: AtomicInteger,
) : KeyStoreSpi() {

    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

    // The default implementation refuses a key entry without a password; the platform's does not.
    override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? {
        // A negative budget is unlimited; otherwise each lookup spends one and none is left at zero.
        if (lookupsLeft.getAndUpdate { if (it > 0) it - 1 else it } == 0) {
            throw KeyStoreException("the keystore refused the lookup")
        }
        return keys[alias]?.let { KeyStore.SecretKeyEntry(it) }
    }

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

private class SoftAesKeyGeneratorSpi(
    private val keys: MutableMap<String, SecretKey>,
    private val lastSpec: AtomicReference<KeyGenParameterSpec?>,
) : KeyGeneratorSpi() {

    private var alias: String? = null
    private var keySizeBits: Int = 0

    override fun engineInit(random: SecureRandom?) =
        throw InvalidAlgorithmParameterException("AndroidKeyStore keys are created from a KeyGenParameterSpec")

    override fun engineInit(keysize: Int, random: SecureRandom?) =
        throw InvalidAlgorithmParameterException("AndroidKeyStore keys are created from a KeyGenParameterSpec")

    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        val spec = params as? KeyGenParameterSpec
            ?: throw InvalidAlgorithmParameterException("expected a KeyGenParameterSpec, got $params")
        lastSpec.set(spec)
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
