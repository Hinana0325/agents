package cn.phoneassistant.desktop

import java.io.File

/**
 * 登录自启（仅 Windows）：写 HKCU\...\Run 一个指向本地 .cmd 的项。
 *
 * 用 .cmd 而不是直接写 exe：这样 installDist（java -cp ...）与 jpackage app-image
 * 两种运行方式都能用同一条命令启动。命令只由用户显式执行 install-autostart 时才写入，
 * remove-autostart 会删除该项。
 */
object Autostart {

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "DpaDesktop"
    private const val CMD_NAME = "autostart.cmd"

    val isSupported: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    fun isInstalled(): Boolean {
        if (!isSupported) return false
        val result = runCommand("reg", "query", RUN_KEY, "/v", VALUE_NAME)
        return result == 0
    }

    /** 返回写入的 .cmd 路径；失败抛异常。 */
    fun install(paths: DesktopPaths): String {
        check(isSupported) { "自启只支持 Windows" }
        val cmdFile = File(paths.home, CMD_NAME)
        cmdFile.writeText("@echo off\r\n${currentCommandLine()} run\r\n", Charsets.UTF_8)
        val code = runCommand(
            "reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ",
            "/d", cmdFile.absolutePath, "/f",
        )
        check(code == 0) { "写入注册表失败（reg add 退出码 $code）" }
        return cmdFile.absolutePath
    }

    fun remove(): Boolean {
        if (!isSupported) return false
        return runCommand("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f") == 0
    }

    /**
     * 用当前 JVM 的 java.home 与 classpath 拼启动命令。
     * 不能依赖 ProcessHandle.arguments()：Windows 上通常拿不到命令行参数。
     * jpackage 与 installDist 都适用（前者 java.home 是内置 runtime，后者是本机 JDK）。
     */
    private fun currentCommandLine(): String {
        val exeName = if (isSupported) "java.exe" else "java"
        val javaExe = File(File(System.getProperty("java.home"), "bin"), exeName).absolutePath
        val classpath = System.getProperty("java.class.path").orEmpty()
        val tokens = listOf(
            javaExe,
            "-Dfile.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
            "-cp",
            classpath,
            "cn.phoneassistant.desktop.MainKt",
        )
        return tokens.joinToString(" ") { quote(it) }
    }

    private fun quote(token: String): String =
        if (token.any { it.isWhitespace() } && !token.startsWith("\"")) "\"$token\"" else token

    private fun runCommand(vararg command: String): Int {
        return try {
            ProcessBuilder(*command).redirectErrorStream(true).start().waitFor()
        } catch (e: Exception) {
            -1
        }
    }
}
