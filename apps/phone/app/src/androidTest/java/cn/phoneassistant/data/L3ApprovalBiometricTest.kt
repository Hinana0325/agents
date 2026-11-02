package cn.phoneassistant.data

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.phoneassistant.MainActivity
import cn.phoneassistant.core.runtime.ApprovalValidator
import cn.phoneassistant.core.runtime.TrustedApprover
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 交互式验证：生物识别解锁审批密钥 → 生成 L3 证明 → 校验通过。
 * 运行期间需要在模拟器/真机上按指纹（模拟器：adb emu finger touch 1）。
 * 没有已录入生物识别的环境自动跳过（CI 不会失败）。
 */
@RunWith(AndroidJUnit4::class)
class L3ApprovalBiometricTest {

    @Test
    fun biometricUnlocksApprovalKeyAndSignsL3Proof() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "交互测试：用 -e interactiveBiometric true 显式启用，并在此期间按指纹",
            arguments.getString("interactiveBiometric") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(BiometricManager::class.java)
        val canUse = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
        assumeTrue("需要已录入的生物识别才能跑这条交互测试", canUse)

        val identity = DeviceIdentity(context)
        assertNotNull("审批密钥不可用", identity.ensureApprovalKey())
        val signature = identity.createApprovalSignature()
        assertNotNull("审批签名器不可用", signature)
        val envelope = buildJsonObject {
            put("v", 1)
            put("id", "0193f0a8-9c1b-7000-8000-0000000000ff")
            put("intent", "save_note")
            put("sig", "A".repeat(86))
        }

        val latch = CountDownLatch(1)
        val proof = java.util.concurrent.atomic.AtomicReference<kotlinx.serialization.json.JsonObject?>()
        val failure = java.util.concurrent.atomic.AtomicReference<String?>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val executor = activity.mainExecutor
                BiometricPrompt.Builder(activity)
                    .setTitle("测试：批准 L3")
                    .setNegativeButton("取消", executor) { _, _ -> latch.countDown() }
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build()
                    .authenticate(
                        BiometricPrompt.CryptoObject(signature!!),
                        CancellationSignal(),
                        executor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                result: BiometricPrompt.AuthenticationResult,
                            ) {
                                val unlocked = result.cryptoObject?.signature
                                if (unlocked == null) {
                                    failure.set("认证成功但没有返回签名器")
                                } else {
                                    runCatching {
                                        proof.set(identity.createApprovalWithSignature(envelope, 3, unlocked))
                                    }.onFailure { failure.set("签名失败：${it.message}") }
                                }
                                latch.countDown()
                            }

                            override fun onAuthenticationError(code: Int, message: CharSequence) {
                                failure.set("$code:$message")
                                latch.countDown()
                            }
                        },
                    )
            }
            assertTrue("等待生物识别超时", latch.await(90, TimeUnit.SECONDS))
        }

        val created = proof.get()
        assertNotNull("生物识别没有产生审批证明（${failure.get() ?: "未知原因"}）", created)
        val approvalId = "${identity.deviceId}:approval"
        val pubkey = identity.approvalPublicJwk()!!
        val error = ApprovalValidator.verify(
            approval = created!!,
            envelope = envelope,
            requiredLevel = 3,
            approvers = mapOf(approvalId to TrustedApprover(approvalId, 3, pubkey)),
            usedNonces = emptySet(),
            now = Instant.now(),
            windowSeconds = 120,
        )
        assertNull(error)
    }
}
