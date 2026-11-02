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
