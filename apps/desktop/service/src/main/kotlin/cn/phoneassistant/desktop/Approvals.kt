package cn.phoneassistant.desktop

/** L2 确认提示：由收件箱线程发起，UI 展示并回填结果。 */
data class ApprovalPrompt(val title: String, val body: String)

/** 通知出口：桌面 UI 走系统托盘，无头模式走标准输出。 */
fun interface Notifier {
    fun notify(title: String, body: String)
}
