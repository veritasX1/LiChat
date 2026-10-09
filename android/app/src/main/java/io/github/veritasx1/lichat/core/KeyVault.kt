package io.github.veritasx1.lichat.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The device key (32 random bytes that seal state, chats and pictures). Never stored plainly: wrapped with an AES key
 *  in the Android Keystore that cannot leave the phone; on disk only key.wrapped. Tests use [Plain]. */
interface KeyVault {
    fun load(folder: File): ByteArray?
    fun create(folder: File): ByteArray

    object Keystore : KeyVault {
        private const val ALIAS = "lichat-geraeteschluessel"

        private fun wrapping(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }

        override fun load(folder: File): ByteArray? {
            val file = File(folder, "key.wrapped").takeIf { it.exists() } ?: return null
            val data = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapping(), GCMParameterSpec(128, data, 0, 12))
            return cipher.doFinal(data, 12, data.size - 12)
        }

        override fun create(folder: File): ByteArray {
            folder.mkdirs()
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapping())
            File(folder, "key.wrapped").writeBytes(cipher.iv + cipher.doFinal(key))
            return key
        }
    }

    /** For tests only: the key as a file (like lichat-cli). */
    object Plain : KeyVault {
        override fun load(folder: File) = File(folder, "key").takeIf { it.exists() }?.readBytes()
        override fun create(folder: File) = ByteArray(32).also { SecureRandom().nextBytes(it); folder.mkdirs(); File(folder, "key").writeBytes(it) }
    }
}
