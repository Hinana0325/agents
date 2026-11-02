package cn.phoneassistant.desktop

import cn.phoneassistant.core.Jcs
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 电脑端身份与审批密钥（P-256）。
 *
 * Windows 上用 DPAPI 保护私钥（`identity-private.dpapi`）；
 * 旧版本留下的明文 `identity-private.der` 会在首次加载时自动加密并删除。
 * 非 Windows 系统暂时明文落盘，界面上会明确标注。
 */
class DesktopIdentity(home: File) {

    private val protectedFile = File(home, "identity-private.dpapi")
    private val plainFile = File(home, "identity-private.der")
    private val publicFile = File(home, "identity-public.der")

    val keyPair: KeyPair = loadOrCreate()

    fun publicJwk(): JsonObject {
        val pub = keyPair.public as ECPublicKey
        return buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", b64(pub.w.affineX))
            put("y", b64(pub.w.affineY))
        }
    }

    fun sign(payload: JsonObject): String {
        val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
        signer.initSign(keyPair.private)
        signer.update(Jcs.canonicalize(payload).toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
    }

    private fun loadOrCreate(): KeyPair {
        val privateBytes = when {
            protectedFile.isFile -> SecretStore.unprotect(protectedFile.readBytes())
            plainFile.isFile -> plainFile.readBytes().also { raw ->
                if (SecretStore.isProtected) {
                    protectedFile.writeBytes(SecretStore.protect(raw))
                    plainFile.delete()
                }
            }
            else -> null
        }
        if (privateBytes != null && publicFile.isFile) {
            return try {
                val factory = KeyFactory.getInstance("EC")
                val priv = factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
                val pub = factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
                KeyPair(pub, priv)
            } catch (e: Exception) {
                throw IllegalStateException("身份密钥读取失败，请检查 $protectedFile：${e.message}")
            }
        }
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = generator.generateKeyPair()
        val encoded = (pair.private as PrivateKey).encoded
        if (SecretStore.isProtected) {
            protectedFile.writeBytes(SecretStore.protect(encoded))
        } else {
            plainFile.writeBytes(encoded)
        }
        publicFile.writeBytes(pair.public.encoded)
        return pair
    }

    private fun b64(value: BigInteger): String {
        val bytes = value.toByteArray()
        val fixed = when {
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> bytes
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fixed)
    }
}
