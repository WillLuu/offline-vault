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
        versionCode = 1
        versionName = "1.0.0"
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

    sourceSets {
        getByName("main") {
            // 源码集引入 backend 数据/加密层，使 com.qiqiao.passwordvault.data 与 vault 同编译单元。
            // RealVaultData 包 com.qiqiao.passwordvault.data、vault.* 包 vault，均位于 backend/src/vault 目录；
            // Kotlin 不强制"目录=包"，故直接把该目录作为 srcDir 即可，无需按包路径摆放。
            // ponytail: 跨目录 srcDir 指向 backend/src/vault（单一事实源，不复制）。 | 升级阈值：若需独立发版/解耦跨模块路径，改复制进 app 源码树或抽 library module。
            // 2026-09-02 修正（#QA-025）：原文 `java.srcDirs += file(...)` 在 Kotlin DSL 下无法编译——
            // SourceDirectorySet.srcDirs 是只读 Set（无 setter），`+=` 会被解析成对 val 赋值。
            // 追加目录的正确写法是调用 srcDirs(vararg Object) 方法。
            java.srcDirs(file("${projectDir}/../../backend/src/vault"))
        }
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

    // 后端数据/加密层依赖（KDF 标准库无）：Argon2id 纯 Java 实现（#QA-024 方案 A）。
    // 2026-09-02 替换 argon2-jvm:2.7 + jna:5.14.0：二者原生库无 android ABI，真机必崩 UnsatisfiedLinkError。
    // bcprov-jdk15to18 目标 Java 1.5–1.8，Android 兼容（无需 desugar）；仅直接调用 lightweight API，不注册 Provider。
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")
}
