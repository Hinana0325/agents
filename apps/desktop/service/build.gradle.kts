import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("cn.phoneassistant.desktop.MainKt")
    // JDK 17 在中文 Windows 上默认用 GBK 输出，中文在管道/UTF-8 终端里会乱码。
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
    )
}

dependencies {
    implementation(project(":core:kotlin"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jna.platform)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(libs.junit)
}

// 打包成自带 JRE 的 app-image（不依赖本机 Java）。
// 机器上没有 WiX，MSI 暂不产出；CLI 命令继续用 installDist 的 service.bat。
val packageAppImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build a self-contained Windows app image with jpackage"
    dependsOn("installDist")

    val installDir = layout.buildDirectory.dir("install/service").get().asFile
    val outputDir = layout.buildDirectory.dir("jpackage").get().asFile

    doFirst {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
    }

    executable = File(
        System.getProperty("java.home"),
        if (System.getProperty("os.name").lowercase().contains("win")) "bin/jpackage.exe" else "bin/jpackage",
    ).absolutePath
    args(
        "--type", "app-image",
        "--name", "DpaDesktop",
        "--app-version", "0.2.0",
        "--input", File(installDir, "lib").absolutePath,
        "--main-jar", "service.jar",
        "--main-class", "cn.phoneassistant.desktop.MainKt",
        "--dest", outputDir.absolutePath,
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dsun.stdout.encoding=UTF-8",
        "--java-options", "-Dsun.stderr.encoding=UTF-8",
    )
}
