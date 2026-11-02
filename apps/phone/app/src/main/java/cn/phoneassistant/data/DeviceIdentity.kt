package cn.phoneassistant.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cn.phoneassistant.core.ApprovalProof
import cn.phoneassistant.core.DeviceCert
import cn.phoneassistant.core.Es256
import cn.phoneassistant.core.PairingRequest
import cn.phoneassistant.core.Revocation
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 手机设备身份：Keystore 里的 P-256 签名密钥 + 稳定的 device_id。
 *
 * 手机是信任根：用它给电脑/手表签发设备证书。私钥不可导出，只在 Keystore 内签名。
 */
class DeviceIdentity(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null)
        ?: ("phone-" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    private val privateKey: PrivateKey
    val publicKey: ECPublicKey

    init {
        if (!keyStore.containsAlias(ALIAS)) generateKey()
        val key = keyStore.getKey(ALIAS, null) as? PrivateKey
        val certificate = keyStore.getCertificate(ALIAS)
        if (key == null || certificate?.publicKey !is ECPublicKey) {
            keyStore.deleteEntry(ALIAS)
            generateKey()
        }
        privateKey = keyStore.getKey(ALIAS, null) as PrivateKey
        publicKey = keyStore.getCertificate(ALIAS).publicKey as ECPublicKey
    }

    fun publicJwk(): JsonObject = buildJsonObject {
        put("kty", "EC")
        put("crv", "P-256")
        put("x", b64(publicKey.w.affineX))
        put("y", b64(publicKey.w.affineY))
    }

    /** 给一个配对请求签发设备证书；有效期一年。 */
    fun createCertificate(request: PairingRequest, now: Instant = Instant.now()): JsonObject {
        val issuedAt = format(now)
        val expiresAt = format(now.plus(365, ChronoUnit.DAYS))
        return DeviceCert.create(
            deviceId = request.deviceId,
            role = request.role,
            maxLevel = request.maxLevel,
            pubkeyJwk = request.pubkey,
            issuedBy = deviceId,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            signingKey = privateKey,
        )
    }

    /** 给一条待确认信封生成 L2 审批证明（用 Keystore 身份密钥签名）。 */
    fun createApproval(envelope: JsonObject, level: Int): JsonObject =
        ApprovalProof.create(envelope, level, deviceId, newNonce(), Instant.now(), privateKey)

    /**
     * 生物识别保护的审批密钥（L3 专用）。没有安全锁屏 / 未录入生物识别时返回 null；
     * 密钥要求每次使用都认证，且生物识别重新录入后旧密钥作废。
     */
    fun ensureApprovalKey(): ECPublicKey? {
        if (keyStore.containsAlias(APPROVAL_ALIAS)) {
            return keyStore.getCertificate(APPROVAL_ALIAS)?.publicKey as? ECPublicKey
        }
        return runCatching {
            val builder = KeyGenParameterSpec.Builder(APPROVAL_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(builder.build()) }
                .generateKeyPair()
            keyStore.getCertificate(APPROVAL_ALIAS)?.publicKey as? ECPublicKey
        }.getOrNull()
    }

    fun approvalPublicJwk(): JsonObject? {
        val key = ensureApprovalKey() ?: return null
        return buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", b64(key.w.affineX))
            put("y", b64(key.w.affineY))
        }
    }

    /** 初始化待生物识别解锁的签名器；密钥不存在时返回 null。 */
    fun createApprovalSignature(): Signature? = runCatching {
        val key = keyStore.getKey(APPROVAL_ALIAS, null) as? PrivateKey ?: return@runCatching null
        Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
    }.getOrNull()

    /** 生物识别解锁后调用：用认证过的 Signature 生成 L3 证明。 */
    fun createApprovalWithSignature(envelope: JsonObject, level: Int, signature: Signature): JsonObject =
        ApprovalProof.createSigned(envelope, level, "$deviceId:approval", newNonce(), Instant.now()) { payload ->
            Es256.signRawWith(payload, signature)
        }

    private fun newNonce(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 给一张已签发的设备证书生成吊销记录。 */
    fun createRevocation(deviceId: String, certHash: String, reason: String? = "用户吊销"): JsonObject =
        Revocation.create(deviceId, certHash, this.deviceId, reason, Instant.now(), privateKey)

    /** 用本机公钥签一张测试证书并验签；返回 null 表示通过，否则是错误码。 */
    fun selfTest(now: Instant = Instant.now()): String? {        val issuedAt = format(now)
        val expiresAt = format(now.plus(365, ChronoUnit.DAYS))
        val cert = DeviceCert.create(
            deviceId = "self-test",
            role = "pc",
            maxLevel = 2,
            pubkeyJwk = publicJwk(),
            issuedBy = deviceId,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            signingKey = privateKey,
        )
        return DeviceCert.verify(cert, publicJwk(), now)
    }

    private fun generateKey() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKeyPair()
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

    private fun format(instant: Instant): String = FORMAT.format(instant)

    private companion object {
        const val ALIAS = "dpa-identity"
        const val APPROVAL_ALIAS = "dpa-approval"
        const val KEY_DEVICE_ID = "device_id"
        val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
    }
}
