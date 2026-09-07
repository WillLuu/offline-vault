plugins {
    kotlin("jvm")
}

// vault-core：纯 JVM 加密/存储/合并逻辑（PC 端 PRD §3.2）
// 验收（PRD §3.3）：AC-1 全模块零 android.* 引用；AC-2 契约检查保持通过（contractTests 任务）

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    // Argon2id 纯 Java 实现（BC lightweight API，不注册 Provider；#QA-024 方案 A）
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")

    // 契约测试运行时：sqlite-jdbc（JDBC 重放 DAO SQL 语义，仅 JVM 测试用；生产 Android 用系统 SQLite）
    // 运行方式：./gradlew :vault-core:contractTests  （JavaExec 直跑 main，自检即文档，无测试框架）
    testImplementation("org.xerial:sqlite-jdbc:3.46.0.0")
}

// 契约测试：直跑 ContractTestsKt.main（无 JUnit 依赖，与"自检即文档、静默通过"约定一致）
val testSourceSet = sourceSets.named("test")

tasks.register<JavaExec>("contractTests") {
    group = "verification"
    description = "运行全部契约检查（纯 JVM，sqlite-jdbc 重放）"
    mainClass.set("vault.ContractTestsKt")
    classpath = testSourceSet.get().runtimeClasspath
}
