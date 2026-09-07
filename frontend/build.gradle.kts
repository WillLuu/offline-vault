plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qiqiao.passwordvault"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qiqiao.passwordvault"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // ponytail: 未接签名配置 | 触发升级阈值：发布需配置签名（backend 协同）
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 平台标准 UI 工具包（AndroidX），非第三方业务库
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // 2026-09-02 修正（#QA-026）：原声明 1.2.0 在 Google Maven 与 Maven Central 均 404（该版本不存在）。
    // androidx.biometric 至今只发过 1.1.0 稳定版与 1.4.0-alphaXX；取最新稳定版 1.1.0。
    // 影响面为零：UnlockActivity 目前仅为占位 Toast，尚未接入 BiometricPrompt（见对应 ponytail）。
    implementation("androidx.biometric:biometric:1.1.0")

    // 数据/加密层：vault-android（api 传递 vault-core；BC 随 core 引入，应用层零直接加密依赖）
    implementation(project(":vault-android"))
}
