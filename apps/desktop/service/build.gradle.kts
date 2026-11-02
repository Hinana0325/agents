plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    testImplementation(libs.junit)
}
