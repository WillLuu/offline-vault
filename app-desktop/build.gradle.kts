plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
}

// app-desktop：Windows 桌面应用（Compose Desktop，PC 端 PRD M5）
// MVP 采用 Material3 标准样式（新拟态重绘为 P2）；消费 vault-desktop 同步门面。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
    // Kotlin 1.9.x + Compose 编译器 1.5.14（与 1.9.24 配对，已核实 Maven 元数据）
}

dependencies {
    implementation(project(":vault-desktop"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(compose.runtime)
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "启动桌面应用"
    mainClass.set("vault.desktop.app.MainKt")
    classpath = sourceSets.named("main").get().runtimeClasspath
    // 空闲锁/剪贴板等均走 AWT 主线程，无需额外参数
}
