package cn.phoneassistant.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object OutboxScheduler {

    const val WORK_NAME = "outbox-retry"

    /** 已有任务没结束就保留它，避免每次失败都重置退避。 */
    fun ensureScheduled(context: Context) {
        val request = OneTimeWorkRequestBuilder<OutboxRetryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        Log.i("OutboxScheduler", "enqueued $WORK_NAME")
    }
}
