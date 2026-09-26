package com.distronode.districtai.core.auth

import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.spec.GCMParameterSpec

/**
 * A hostile `AES/GCM/NoPadding` provider, for JVM tests only: it encrypts correctly, with a
 * provider-chosen IV that is 16 bytes rather than the 12 [KeystoreCipher] stores and reads back.
 *
 * WHY IT EXISTS. [KeystoreCipher] refuses any IV that is not 12 bytes, because the stored layout
 * is `iv || ciphertext` with a fixed 12-byte split; a longer IV would be written and then read
 * back cut in the wrong place, which decrypts to nothing and silently loses the session. No real
 * provider on this JVM produces such an IV, so this one does, to prove the refusal holds.
 *
 * Inserted AHEAD of every other provider so `Cipher.getInstance` selects it, and removed after
 * the test: it is process-wide state.
 */
internal class WrongIvLengthGcmProvider : Provider(NAME, 1.0, "AES/GCM with a 16-byte IV, for JVM tests") {

    init {
        putService(
            object : Service(this, "Cipher", TRANSFORMATION, WrongIvLengthGcmSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = WrongIvLengthGcmSpi()
            },
        )
    }

    fun install() {
        check(Security.insertProviderAt(this, 1) == 1) { "the $NAME provider could not be placed first" }
    }

    fun uninstall() = Security.removeProvider(NAME)

    private companion object {
        const val NAME = "WrongIvLengthGcm"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private class WrongIvLengthGcmSpi : CipherSpi() {

        /**
         * The JVM's own GCM. SunJCE specifically: Conscrypt, which Robolectric installs first,
         * refuses any IV but 12 bytes at init, and the JCA would then quietly fall back to it.
         */
        private val delegate: Cipher = Cipher.getInstance(TRANSFORMATION, JVM_PROVIDER)

        override fun engineSetMode(mode: String?) = Unit

        override fun engineSetPadding(padding: String?) = Unit

        override fun engineGetBlockSize(): Int = delegate.blockSize

        override fun engineGetOutputSize(inputLen: Int): Int = delegate.getOutputSize(inputLen)

        override fun engineGetIV(): ByteArray? = delegate.iv

        override fun engineGetParameters(): AlgorithmParameters? = delegate.parameters

        override fun engineInit(opmode: Int, key: Key, random: SecureRandom?) {
            val iv = ByteArray(WRONG_IV_BYTES).also { SecureRandom().nextBytes(it) }
            delegate.init(opmode, key, GCMParameterSpec(TAG_BITS, iv))
        }

        override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameterSpec?, random: SecureRandom?) =
            delegate.init(opmode, key, params)

        override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameters?, random: SecureRandom?) =
            delegate.init(opmode, key, params)

        override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray? =
            delegate.update(input, inputOffset, inputLen)

        override fun engineUpdate(
            input: ByteArray,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int,
        ): Int = delegate.update(input, inputOffset, inputLen, output, outputOffset)

        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
            delegate.doFinal(input, inputOffset, inputLen)

        override fun engineDoFinal(
            input: ByteArray?,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray,
            outputOffset: Int,
        ): Int = delegate.doFinal(input, inputOffset, inputLen, output, outputOffset)

        private companion object {
            const val WRONG_IV_BYTES = 16
            const val JVM_PROVIDER = "SunJCE"
            const val TAG_BITS = 128
        }
    }
}
