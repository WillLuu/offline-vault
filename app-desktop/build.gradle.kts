import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

    // 最小化窗口：JNA 直调 user32（见 Platform.kt minimizeWindow）
    implementation("net.java.dev.jna:jna:5.14.0")

    // 桌面 Dispatchers.Main 提供者（经 ServiceLoader 发现；打包后必需，dev 运行同受益）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
}

// 官方桌面任务：./gradlew :app-desktop:run / createDistributable / packageDistributionForCurrentOS
compose.desktop {
    application {
        mainClass = "vault.desktop.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            modules("java.sql", "jdk.unsupported", "java.management") // sqlite-jdbc 需 java.sql；BC/jna 相关需 jdk.unsupported
            packageName = "offline-vault"
            packageVersion = "1.0.1"
            vendor = "Will (17deg)"
            description = "Offline Vault - pure offline password manager (desktop)"
            // 注意：MSI/EXE 安装包需本机安装 WiX 3.x；createDistributable（自包含目录）无需
            windows {
                dirChooser = true
            }
        }
    }
}
