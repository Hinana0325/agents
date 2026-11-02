package cn.phoneassistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设置值的加密存储：每个值用 Keystore 里的 AES-256-GCM 密钥加密，
 * 以 base64(iv || ciphertext) 写入普通 SharedPreferences；字段名不加密。
 * 字段名作为 AAD，密文不能在字段之间搬运。
 *
 * 取代已弃用的 EncryptedSharedPreferences；写入用 commit()，设置不频繁且必须落盘。
 */
class SecureSettings(context: Context, fileName: String = "assistant_secure") {

    private val prefs = context.applicationContext
        .getSharedPreferences(fileName, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val key: SecretKey = getOrCreateKey()
    private val lock = Any()

    fun getString(name: String, default: String): String = synchronized(lock) {
        val encoded = prefs.getString(name, null) ?: return default
        runCatching { decrypt(name, encoded) }.getOrDefault(default)
    }

    fun putString(name: String, value: String): Boolean = synchronized(lock) {
        val encrypted = runCatching { encrypt(name, value) }.getOrNull() ?: return false
        prefs.edit().putString(name, encrypted).commit()
    }

    fun remove(name: String): Boolean = synchronized(lock) {
        prefs.edit().remove(name).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        if (keyStore.containsAlias(ALIAS)) {
            val existing = keyStore.getKey(ALIAS, null) as? SecretKey
            if (existing != null) return existing
            keyStore.deleteEntry(ALIAS)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(name: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + ciphertext)
    }

    private fun decrypt(name: String, encoded: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        require(combined.size > IV_BYTES) { "ciphertext too short" }
        val iv = combined.copyOfRange(0, IV_BYTES)
        val ciphertext = combined.copyOfRange(IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "dpa-settings"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
