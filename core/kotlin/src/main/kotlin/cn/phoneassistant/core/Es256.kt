package cn.phoneassistant.core

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** ES256（P-256 + SHA-256，raw r||s）验签与哈希。信封与审批证明共用。 */
object Es256 {

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
}
