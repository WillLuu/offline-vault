pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// 离线密码本 · 多模块根（PC 端 PRD §3.2，2026-09-07 M1/M2 落地）
// - vault-core   : 纯 JVM 加密/存储/合并逻辑（零 android.* 依赖，桌面与 Android 共用）
// - vault-android: Android 胶水（SQLite DAO/Keystore/解锁管理），依赖 core
// - app-android  : Android 应用（目录名保持 frontend，历史沿革）
// - vault-desktop: 桌面端 JDBC 存储/解锁适配（M3），依赖 core；无 Android 依赖
// 后续：app-desktop（Compose Desktop UI）依 PRD M5 加入
rootProject.name = "offline-vault"

include(":vault-core")
include(":vault-android")
include(":vault-desktop")
include(":app-android")
project(":app-android").projectDir = file("frontend")
