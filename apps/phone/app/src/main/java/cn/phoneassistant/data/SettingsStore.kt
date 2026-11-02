package cn.phoneassistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 设置存储：全部字段（服务地址、模型名、密钥）都只放在
 * Android Keystore 保护的 EncryptedSharedPreferences 中。
 *
 * - 密钥绝不会出现在代码、构建配置、日志或普通 SharedPreferences 里；
 * - 读取密钥只用于构造本次请求的 Authorization 请求头，用完即丢弃，
 *   不保存到任何成员变量或聊天记录中；
 * - 清除密钥后，下一次请求会被直接拒绝，不会再调用接口。
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 懒加载：构造时不碰 Keystore 和磁盘（原来在 ViewModel 字段初始化里同步建
     * EncryptedSharedPreferences，主线程做加解密 I/O）。
     * 建不出来时只记中文原因并拒绝读写，绝不退回明文存储。
     */
    private var cachedPrefs: SharedPreferences? = null
    private var initAttempted = false
    private val initLock = Any()

    private val storagePrefs: SharedPreferences?
        get() = synchronized(initLock) {
            if (!initAttempted) {
                initAttempted = true
                cachedPrefs = try {
                    EncryptedSharedPreferences.create(
                        appContext,
                        "assistant_settings",
                        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                } catch (e: Exception) {
                    storageFailure = "本机加密存储不可用（${e.javaClass.simpleName}），" +
                        "设置无法保存，也不会调用任何接口。"
                    null
                }
            }
            cachedPrefs
        }

    /** 加密存储建不出来时的中文说明；正常为 null。 */
    var storageFailure: String? = null
        private set

    var baseUrl: String
        get() = storagePrefs?.getString(KEY_BASE_URL, "").orEmpty().trim().trimEnd('/')
        set(value) {
            storagePrefs?.edit()?.putString(KEY_BASE_URL, value.trim().trimEnd('/'))?.apply()
        }

    var model: String
        get() = storagePrefs?.getString(KEY_MODEL, "").orEmpty().trim()
        set(value) {
            storagePrefs?.edit()?.putString(KEY_MODEL, value.trim())?.apply()
        }

    /** 密钥：只写不读原文到界面；界面只展示“已设置 / 未设置”。 */
    var apiKey: String
        get() = storagePrefs?.getString(KEY_API_KEY, "").orEmpty()
        set(value) {
            storagePrefs?.edit()?.putString(KEY_API_KEY, value)?.apply()
        }

    val hasApiKey: Boolean
        get() = apiKey.isNotBlank()

    /** 是否已配齐调用接口的全部信息。 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && hasApiKey

    /** 编排服务（hub）地址；为空表示只用本地能力。 */
    var hubUrl: String
        get() = storagePrefs?.getString(KEY_HUB_URL, "").orEmpty().trim().trimEnd('/')
        set(value) {
            storagePrefs?.edit()?.putString(KEY_HUB_URL, value.trim().trimEnd('/'))?.apply()
        }

    /** 设备令牌：只写不读回界面，只用于 Authorization 头。 */
    var hubToken: String
        get() = storagePrefs?.getString(KEY_HUB_TOKEN, "").orEmpty()
        set(value) {
            storagePrefs?.edit()?.putString(KEY_HUB_TOKEN, value)?.apply()
        }

    val hasHubToken: Boolean
        get() = hubToken.isNotBlank()

    val isHubConfigured: Boolean
        get() = hubUrl.isNotBlank() && hasHubToken && hubPubkey.isNotBlank()

    /** Hub 签名公钥（JWK JSON 文本），用于验证云端信封。 */
    var hubPubkey: String
        get() = storagePrefs?.getString(KEY_HUB_PUBKEY, "").orEmpty().trim()
        set(value) {
            storagePrefs?.edit()?.putString(KEY_HUB_PUBKEY, value.trim())?.apply()
        }

    /** Hub 的 kid；缺省用 hub-test-1（本地联调）。 */
    var hubKid: String
        get() = storagePrefs?.getString(KEY_HUB_KID, "hub-test-1").orEmpty().ifBlank { "hub-test-1" }
        set(value) {
            storagePrefs?.edit()?.putString(KEY_HUB_KID, value.trim())?.apply()
        }

    fun clearHubToken() {
        storagePrefs?.edit()?.remove(KEY_HUB_TOKEN)?.apply()
    }

    fun clearApiKey() {
        storagePrefs?.edit()?.remove(KEY_API_KEY)?.apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val KEY_HUB_URL = "hub_url"
        const val KEY_HUB_TOKEN = "hub_token"
        const val KEY_HUB_PUBKEY = "hub_pubkey"
        const val KEY_HUB_KID = "hub_kid"
    }
}
