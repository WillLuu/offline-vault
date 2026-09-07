plugins {
    kotlin("jvm")
}

// vault-desktop：Windows 桌面端存储/解锁适配（PC 端 PRD §3.2 / M3）
// 镜像 vault-android 的语义（同一 SQL 单一事实源、同一加密原语、同一限流参数），
// 差异仅在平台机制：sqlite-jdbc 替代 Android SQLite、无 Keystore 生物识别通道。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    // 复用纯 JVM 加密/存储/合并核心——禁自研 KDF/加密（PRD §1.3 / SEC-2）
    api(project(":vault-core"))

    // 桌面生产存储：sqlite-jdbc（Android 端用系统内置 SQLite，两端同一 DDL=Schema.kt）
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
}

// 桌面端自检：直跑 main（与 vault-core 契约测试同一"自检即文档"约定，无测试框架）
val desktopTestSourceSet = sourceSets.named("test")

tasks.register<JavaExec>("desktopChecks") {
    group = "verification"
    description = "运行桌面端存储/解锁自检（真实文件 DB，sqlite-jdbc）"
    mainClass.set("vault.desktop.DesktopContractChecksKt")
    classpath = desktopTestSourceSet.get().runtimeClasspath
}

// 桌面 CLI（M4 跨端互导实测 + 运维）：./gradlew :vault-desktop:runCli -q --args="stats <主密码>"
val mainSourceSet = sourceSets.named("main")

tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "桌面命令行工具（init/stats/add/export/import），--args 传参"
    mainClass.set("vault.desktop.DesktopCliKt")
    classpath = mainSourceSet.get().runtimeClasspath
    // 输出编码=系统原生（中文 Windows GBK），本机控制台显示正常；勿强设 UTF-8
    standardInput = System.`in`
}
