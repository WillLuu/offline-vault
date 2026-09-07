plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// vault-android：Android 胶水层（PC 端 PRD §3.2）
// SQLite DAO / SQLiteOpenHelper / Keystore / 解锁管理 / 备份读写 —— 依赖 vault-core 纯逻辑

android {
    namespace = "vault.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
    // api：core 的类型（vault.*）会出现在本模块公开签名中，前端/桌面消费者需传递可见
    api(project(":vault-core"))
}
