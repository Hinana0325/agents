package cn.phoneassistant.desktop

import com.sun.jna.platform.win32.Crypt32Util

/**
 * 私钥落盘保护：Windows 用 DPAPI（当前用户作用域），其他系统暂时明文。
 * 计划目标是把密钥放 DPAPI/TPM；这里先把最关键的“文件被拷走也解不开”补上。
 */
object SecretStore {

    val isProtected: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    fun protect(bytes: ByteArray): ByteArray =
        if (isProtected) Crypt32Util.cryptProtectData(bytes) else bytes

    fun unprotect(bytes: ByteArray): ByteArray =
        if (isProtected) Crypt32Util.cryptUnprotectData(bytes) else bytes
}
