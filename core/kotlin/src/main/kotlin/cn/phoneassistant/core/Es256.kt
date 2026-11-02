package cn.phoneassistant.core

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** ES256（P-256 + SHA-256，raw r||s）验签、签名与哈希。信封、审批与设备证书共用。 */
object Es256 {

    /** 用任意 JCA/Android Keystore 私钥签名，统一输出 raw r||s 的 base64url。 */
    fun signRaw(payload: JsonObject, privateKey: PrivateKey): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(Jcs.canonicalize(payload).toByteArray(Charsets.UTF_8))
        val (r, s) = decodeDerSignature(signer.sign())
        val raw = ByteArray(64)
        copyPadded(r, raw, 0)
        copyPadded(s, raw, 32)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }


    fun verify(payload: JsonObject, signatureBase64Url: String, jwk: JsonObject): Boolean {
        return try {
            val raw = Base64.getUrlDecoder().decode(signatureBase64Url)
            if (raw.size != 64) return false
            val x = decodeCoordinate(jwk["x"]) ?: return false
            val y = decodeCoordinate(jwk["y"]) ?: return false
            val params = AlgorithmParameters.getInstance("EC").apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
            val key = KeyFactory.getInstance("EC").generatePublic(
                ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), params),
            )
            val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
            verifier.initVerify(key)
            verifier.update(Jcs.canonicalize(payload).toByteArray(Charsets.UTF_8))
            verifier.verify(raw)
        } catch (e: Exception) {
            false
        }
    }

    fun sha256Hex(payload: JsonObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(Jcs.canonicalize(payload).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decodeCoordinate(element: kotlinx.serialization.json.JsonElement?): ByteArray? {
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return try {
            Base64.getUrlDecoder().decode(primitive.content)
        } catch (e: Exception) {
            null
        }
    }

    /** 最小 DER 解析：SEQUENCE { INTEGER r, INTEGER s }。 */
    private fun decodeDerSignature(der: ByteArray): Pair<BigInteger, BigInteger> {
        var pos = 0

        fun readByte(): Int = der[pos++].toInt() and 0xFF

        fun readLength(): Int {
            var length = readByte()
            if (length and 0x80 != 0) {
                val bytes = length and 0x7F
                length = 0
                repeat(bytes) { length = (length shl 8) or readByte() }
            }
            return length
        }

        require(readByte() == 0x30) { "not a DER sequence" }
        readLength()
        require(readByte() == 0x02) { "missing r" }
        val rLength = readLength()
        val r = BigInteger(1, der.copyOfRange(pos, pos + rLength))
        pos += rLength
        require(readByte() == 0x02) { "missing s" }
        val sLength = readLength()
        val s = BigInteger(1, der.copyOfRange(pos, pos + sLength))
        return r to s
    }

    private fun copyPadded(value: BigInteger, out: ByteArray, offset: Int) {
        val bytes = value.toByteArray()
        val trimmed = if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else bytes
        System.arraycopy(trimmed, 0, out, offset + 32 - trimmed.size, trimmed.size)
    }
}
