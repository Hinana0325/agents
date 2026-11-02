package cn.phoneassistant.core.localmodel

/**
 * 单台设备跑本地模型的资源预算。
 *
 * 背景：Android 17 起引入按设备总内存设定的应用内存上限（可用 adb 的
 * memory-limiter 命令在测试机上调整或关闭），端上常驻的 KWS/ASR/意图分类
 * 模型共享同一个进程配额，必须先立预算再加载模型，避免模型把应用挤到
 * 被 LMK 杀掉（开发文档第 6 节“平台与库事实”）。
 *
 * @property maxMemoryMb 本层所有模型常驻内存的合计上限（MB），必须大于 0。
 * @property tier 设备算力档位，取值对齐开发文档第 7 节分档：
 *   - flagship：旗舰手机（可跑 3B-7B）
 *   - mid：中端手机（可跑 1B-3B）
 *   - entry：入门手机（只跑 0.5B-1B）
 *   - pc_high：高性能电脑（可跑 7B-14B）
 *   - pc_mid：中端电脑（可跑 3B-7B）
 *   - pc_entry：入门电脑（只跑 1B-3B）
 */
data class ResourceBudget(
    val maxMemoryMb: Int,
    val tier: String,
) {
    init {
        require(maxMemoryMb > 0) { "maxMemoryMb 必须大于 0，实际为 $maxMemoryMb" }
    }
}
