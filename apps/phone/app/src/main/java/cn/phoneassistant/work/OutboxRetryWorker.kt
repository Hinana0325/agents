package cn.phoneassistant.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import cn.phoneassistant.core.Outbox
import cn.phoneassistant.data.DeviceIdentity
import cn.phoneassistant.data.SettingsStore
import cn.phoneassistant.transport.HttpHubClient
import cn.phoneassistant.transport.flushDue
import java.io.File

enum class OutboxPassResult { SUCCESS, RETRY, FAILED }

/**
 * 跑一次补发。抽成普通函数，仪器测试可以不经过系统 JobScheduler 直接验证；
 * 正式路径仍由 [OutboxRetryWorker] 调用。
 */
suspend fun runOutboxPass(context: Context): OutboxPassResult {
    val outbox = Outbox(File(context.filesDir, "outbox.json"))
    val settings = SettingsStore(context)
    Log.i("OutboxRetryWorker", "start outbox=${outbox.size()} configured=${settings.isHubConfigured}")
    if (outbox.size() == 0) return OutboxPassResult.SUCCESS
    if (!settings.isHubConfigured) return OutboxPassResult.SUCCESS
    val identity = runCatching { DeviceIdentity(context) }.getOrNull()
        ?: return OutboxPassResult.FAILED
    val client = HttpHubClient(settings.hubUrl, identity.deviceId, settings.hubToken)
    val sent = flushDue(outbox) { client.postReceipt(it) }
    Log.i("OutboxRetryWorker", "end sent=$sent remaining=${outbox.size()}")
    return if (outbox.size() == 0) OutboxPassResult.SUCCESS else OutboxPassResult.RETRY
}

/**
 * 网络可用时补发回执。WorkManager 保证进程被杀后仍会重试；
 * 具体间隔由 WorkManager 指数退避和 Outbox 的 nextAttemptAt 共同决定。
 */
class OutboxRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): ListenableWorker.Result = when (runOutboxPass(applicationContext)) {
        OutboxPassResult.SUCCESS -> ListenableWorker.Result.success()
        OutboxPassResult.RETRY -> ListenableWorker.Result.retry()
        OutboxPassResult.FAILED -> ListenableWorker.Result.failure()
    }
}
