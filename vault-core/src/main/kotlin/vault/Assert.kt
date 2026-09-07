package vault

// 极简自检辅助：不依赖任何测试框架（JUnit/pytest），失败即抛 AssertionError。
// 用于 "自检即文档" 铁律：非平凡逻辑同文件内静默自检。
// 同时支持字符串与惰性 lambda 两种消息写法（调用处混用不影响）。
fun checkThat(condition: Boolean, message: String) {
    if (!condition) throw AssertionError("CONTRACT CHECK FAILED: $message")
}

fun checkThat(condition: Boolean, message: () -> String) {
    if (!condition) throw AssertionError("CONTRACT CHECK FAILED: ${message()}")
}

// 字节数组相等断言（ByteArray 的 == 比较的是引用，必须 contentEquals）
fun assertBytesEq(a: ByteArray, b: ByteArray, message: String) {
    checkThat(a.contentEquals(b)) { "$message (len a=${a.size} b=${b.size})" }
}
