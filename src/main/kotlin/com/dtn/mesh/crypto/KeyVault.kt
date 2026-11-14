package com.dtn.mesh.crypto

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop KeyVault: protects identity secrets using Windows DPAPI (Data Protection API)
 * or user-private AES-GCM keystore.
 */
class KeyVault(private val storageDir: File = File(System.getProperty("user.home"), ".dtnmesh")) {

    init {
        storageDir.mkdirs()
    }

    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    fun wrap(plaintext: ByteArray): ByteArray {
        if (isWindows) {
            try {
                // Windows DPAPI encryption (tied to current Windows user login)
                return Crypt32Util.cryptProtectData(plaintext)
            } catch (e: Throwable) {
                // Fallback to AES-GCM
            }
        }
        return aesGcmWrap(plaintext)
    }

    fun unwrap(blob: ByteArray): ByteArray {
        if (isWindows) {
            try {
                return Crypt32Util.cryptUnprotectData(blob)
            } catch (e: Throwable) {
                // Fallback to AES-GCM
            }
        }
        return aesGcmUnwrap(blob)
    }

    private fun aesGcmWrap(plaintext: ByteArray): ByteArray {
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    private fun aesGcmUnwrap(blob: ByteArray): ByteArray {
        require(blob.size > 12) { "vault blob too short" }
        val iv = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    private fun getOrCreateAesKey(): SecretKey {
        val keyFile = File(storageDir, ".master.key")
        if (keyFile.exists()) {
            return SecretKeySpec(keyFile.readBytes(), "AES")
        }
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256, SecureRandom())
        val key = gen.generateKey()
        keyFile.writeBytes(key.encoded)
        return key
    }
}
