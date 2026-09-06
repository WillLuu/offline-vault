package com.qiqiao.passwordvault.util

// 静默自检入口。纯 Kotlin，无 Android 依赖，可用 kotlinc 直接运行：
//   kotlinc app/src/main/java/com/qiqiao/passwordvault/model/Models.kt \
//           app/src/main/java/com/qiqiao/passwordvault/util/*.kt \
//           -include-runtime -d selfcheck.jar && java -jar selfcheck.jar
// 全部 assert 通过则静默退出（退出码 0）；任一失败抛 AssertionError。
// 不依赖任何测试框架（JUnit 等）。
fun main() {
    testMask()
    testStrength()
    testGenerator()
    testFilterSort()
    testTheme()
    testChangeMaster()
    // 静默通过（退出码 0，无输出）；失败抛 AssertionError。删除原 println("SelfChecks OK") 噪声。
}
