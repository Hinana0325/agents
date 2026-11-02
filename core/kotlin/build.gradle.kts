plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 纯 JVM，不依赖 Android：手机/手表/电脑三端共用同一份校验与策略实现。
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    // 金标准向量只有一份，位于仓库根 protocol/vectors；VECTORS_DIR 可指向副本做变异测试。
    systemProperty("vectors.dir", System.getenv("VECTORS_DIR") ?: rootProject.file("protocol/vectors").absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
