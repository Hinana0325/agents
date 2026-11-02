package cn.phoneassistant.data

import android.content.Context

/**
 * 设置存储：全部字段（服务地址、模型名、密钥）都用 [SecureSettings]
 * 做字段级 AES-256-GCM 加密，密钥只存在 Android Keystore。
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
     * 加密存储，主线程做加解密 I/O）。
     * 建不出来时只记中文原因并拒绝读写，绝不退回明文存储。
     * 写入统一用 commit()：设置不频繁，必须在进程被回收前落盘（仪器测试恢复也依赖这一点）。
     */
    private var cachedStorage: SecureSettings? = null
    private var initAttempted = false
    private val initLock = Any()

    private val storage: SecureSettings?
        get() = synchronized(initLock) {
            if (!initAttempted) {
                initAttempted = true
                cachedStorage = try {
                    SecureSettings(appContext)
                } catch (e: Exception) {
                    storageFailure = "本机加密存储不可用（${e.javaClass.simpleName}），" +
                        "设置无法保存，也不会调用任何接口。"
                    null
                }
            }
            cachedStorage
        }

    /** 加密存储建不出来时的中文说明；正常为 null。 */
    var storageFailure: String? = null
        private set

    var baseUrl: String
        get() = storage?.getString(KEY_BASE_URL, "").orEmpty().trim().trimEnd('/')
        set(value) {
            storage?.putString(KEY_BASE_URL, value.trim().trimEnd('/'))
        }

    var model: String
        get() = storage?.getString(KEY_MODEL, "").orEmpty().trim()
        set(value) {
            storage?.putString(KEY_MODEL, value.trim())
        }

    /** 密钥：只写不读原文到界面；界面只展示“已设置 / 未设置”。 */
    var apiKey: String
        get() = storage?.getString(KEY_API_KEY, "").orEmpty()
        set(value) {
            storage?.putString(KEY_API_KEY, value)
        }

    val hasApiKey: Boolean
        get() = apiKey.isNotBlank()

    /** 是否已配齐调用接口的全部信息。 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && hasApiKey

    /** 编排服务（hub）地址；为空表示只用本地能力。 */
    var hubUrl: String
        get() = storage?.getString(KEY_HUB_URL, "").orEmpty().trim().trimEnd('/')
        set(value) {
            storage?.putString(KEY_HUB_URL, value.trim().trimEnd('/'))
        }

    /** 设备令牌：只写不读回界面，只用于 Authorization 头。 */
    var hubToken: String
        get() = storage?.getString(KEY_HUB_TOKEN, "").orEmpty()
        set(value) {
            storage?.putString(KEY_HUB_TOKEN, value)
        }

    val hasHubToken: Boolean
        get() = hubToken.isNotBlank()

    val isHubConfigured: Boolean
        get() = hubUrl.isNotBlank() && hasHubToken && hubPubkey.isNotBlank()

    /** Hub 签名公钥（JWK JSON 文本），用于验证云端信封。 */
    var hubPubkey: String
        get() = storage?.getString(KEY_HUB_PUBKEY, "").orEmpty().trim()
        set(value) {
            storage?.putString(KEY_HUB_PUBKEY, value.trim())
        }

    /** Hub 的 kid；缺省用 hub-test-1（本地联调）。 */
    var hubKid: String
        get() = storage?.getString(KEY_HUB_KID, "hub-test-1")?.ifBlank { "hub-test-1" } ?: "hub-test-1"
        set(value) {
            storage?.putString(KEY_HUB_KID, value.trim())
        }

    fun clearHubToken() {
        storage?.remove(KEY_HUB_TOKEN)
    }

    /** 端上应用目录（JSON：app_id → 包名）；为空表示不接受远端 open_app。 */
    var appCatalogJson: String
        get() = storage?.getString(KEY_APP_CATALOG, "").orEmpty().trim()
        set(value) {
            storage?.putString(KEY_APP_CATALOG, value.trim())
        }

    fun clearApiKey() {
        storage?.remove(KEY_API_KEY)
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val KEY_HUB_URL = "hub_url"
        const val KEY_HUB_TOKEN = "hub_token"
        const val KEY_HUB_PUBKEY = "hub_pubkey"
        const val KEY_HUB_KID = "hub_kid"
        const val KEY_APP_CATALOG = "app_catalog"
    }
}
