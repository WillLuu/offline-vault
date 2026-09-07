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
// 后续：vault-desktop / app-desktop（Compose Desktop）依 PRD M3/M5 加入
rootProject.name = "offline-vault"

include(":vault-core")
include(":vault-android")
include(":app-android")
project(":app-android").projectDir = file("frontend")
