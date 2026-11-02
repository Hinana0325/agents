package cn.phoneassistant.intent

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import cn.phoneassistant.core.AssistantIntent
import cn.phoneassistant.core.IntentType
import cn.phoneassistant.data.NoteStore
import cn.phoneassistant.model.AppCandidate

/**
 * 意图的唯一执行器：只使用 Android 公开 Intent，不申请危险权限，
 * 不读取其他应用的数据，不发送任何消息或电话。
 *
 * 执行前置条件全部由调用方（聊天视图模型）保证：
 * - 需要确认的意图必须已经过用户的确认点击；
 * - OPEN_APP 必须已经由用户在候选列表中选中了具体应用。
 * 本类自身不再做二次确认，只做“能否解析目标 Intent”的兜底检查，
 * 解析不到时返回中文错误，绝不抛异常导致应用崩溃。
 */
object IntentExecutor {

    /** 执行结果：成功时携带中文说明，失败时携带中文原因。 */
    sealed class ExecutionResult {
        data class Success(val message: String) : ExecutionResult()
        data class Failure(val message: String) : ExecutionResult()
    }

    /**
     * 按应用显示名称查找可启动应用。
     * 先精确匹配，再按包含匹配，候选按名称排序、最多 5 个，供用户确认后使用。
     * 匹配不到时返回空列表，调用方应向用户说明，而不是猜一个应用启动。
     */
    fun resolveAppCandidates(context: Context, appName: String): List<AppCandidate> {
        val query = appName.trim()
        if (query.isEmpty()) return emptyList()
        val packageManager = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = try {
            packageManager.queryIntentActivities(mainIntent, 0)
        } catch (e: Exception) {
            return emptyList()
        }
        val labeled = activities.mapNotNull { info ->
            val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            if (label.isEmpty()) null else AppCandidate(info.activityInfo.packageName, label)
        }.distinctBy { it.packageName }
        val exact = labeled.filter { it.label.equals(query, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact.sortedBy { it.label }.take(5)
        return labeled.filter { it.label.contains(query, ignoreCase = true) }
            .sortedBy { it.label }.take(5)
    }

    /** 执行一条已确认的意图。OPEN_APP 要求 [confirmedPackage] 非空。 */
    fun execute(
        context: Context,
        intent: AssistantIntent,
        confirmedPackage: String? = null,
    ): ExecutionResult {
        return try {
            when (intent.type) {
                IntentType.CHAT_ONLY -> ExecutionResult.Success(intent.reply.orEmpty())
                IntentType.WEB_SEARCH -> {
                    val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra(SearchManager.QUERY, intent.params.getValue("query"))
                    }
                    startIfResolvable(context, searchIntent, "没有可以执行搜索的应用。")
                }
                IntentType.OPEN_APP -> {
                    val packageName = confirmedPackage?.takeIf { it.isNotBlank() }
                        ?: return ExecutionResult.Failure("没有选中的应用，已取消打开。")
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                        ?: return ExecutionResult.Failure("该应用无法从桌面启动。")
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    val label = intent.params["appName"].orEmpty()
                    ExecutionResult.Success("已为你打开“$label”。")
                }
                IntentType.SET_ALARM -> {
                    val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, intent.params.getValue("hour").toInt())
                        putExtra(AlarmClock.EXTRA_MINUTES, intent.params.getValue("minute").toInt())
                        intent.params["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    startIfResolvable(context, alarmIntent, "没有可以设置闹钟的应用。")
                }
                IntentType.SET_TIMER -> {
                    val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, intent.params.getValue("seconds").toInt())
                        putExtra(AlarmClock.EXTRA_MESSAGE, "计时")
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    startIfResolvable(context, timerIntent, "没有可以设置计时的应用。")
                }
                IntentType.CREATE_CALENDAR_EVENT -> {
                    val eventIntent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, intent.params.getValue("title"))
                        putExtra(
                            CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                            intent.params.getValue("beginMillis").toLong(),
                        )
                        putExtra(
                            CalendarContract.EXTRA_EVENT_END_TIME,
                            intent.params.getValue("endMillis").toLong(),
                        )
                        intent.params["description"]?.let {
                            putExtra(CalendarContract.Events.DESCRIPTION, it)
                        }
                        intent.params["location"]?.let {
                            putExtra(CalendarContract.Events.EVENT_LOCATION, it)
                        }
                    }
                    startIfResolvable(context, eventIntent, "没有可以创建日程的应用。")
                }
                IntentType.SAVE_NOTE -> {
                    val title = intent.params["title"] ?: "助理备忘"
                    val fileName = NoteStore.save(context, title, intent.params.getValue("content"))
                    ExecutionResult.Success("已保存备忘“$title”（$fileName），只存在本机应用内。")
                }
            }
        } catch (e: Exception) {
            ExecutionResult.Failure("执行失败：${e.message ?: "未知错误"}，没有产生副作用。")
        }
    }

    private fun startIfResolvable(
        context: Context,
        intent: Intent,
        noTargetMessage: String,
    ): ExecutionResult {
        val resolvable = try {
            intent.resolveActivity(context.packageManager) != null
        } catch (e: Exception) {
            false
        }
        if (!resolvable) return ExecutionResult.Failure(noTargetMessage)
        return try {
            // 调用方传进来的是 Application context，不带 NEW_TASK 会直接抛
            // AndroidRuntimeException，四个系统意图全部变成“打开系统界面失败”。
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ExecutionResult.Success("已为你打开系统界面，请在系统界面中最后确认一次。")
        } catch (e: Exception) {
            val raw = e.message.orEmpty()
            val reason = if (raw.contains("Permission Denial", ignoreCase = true)) {
                "系统应用拒绝了这次请求（缺少它要求的权限），请升级应用后再试。"
            } else {
                raw.ifBlank { "未知错误" }
            }
            ExecutionResult.Failure("打开系统界面失败：$reason")
        }
    }
}
