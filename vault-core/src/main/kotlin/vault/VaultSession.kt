package vault

// ============================================================================
// VaultSession：解锁会话中"活跃 DEK"的内存持有者（契约 §3.6 / §5）。纯 Kotlin，零 Android 依赖，可在 JVM 测试。
//  - UnlockManager（Android 胶水）持有本实例；本类只负责内存态与锁定时显式清零。
//  - 副作用隔离：本类不读 DB、不碰 Keystore；解锁由 UnlockManager 完成并把 DEK 注入此处。
//  内存安全：lock() 必须对持有的 DEK 字节显式 fill(0)，不落盘、不进日志（契约 §5）。
// ============================================================================

class VaultSession {
    // 活跃 DEK；null = 已锁定。
    // 并发安全（审计 2026-09-06 P3/#QA-003）：
    //  - @Volatile：lock()（主线程）清零置空 与 getActiveDek()（executor 线程）读取可见性正确；
    //  - getActiveDek() 返回副本：lock() 对内部数组 fill(0) 不会破坏正在解密的调用方持有的数组。
    @Volatile
    private var activeDek: ByteArray? = null

    // 注入解锁后的 DEK（拷贝到本会话自有内存；替换旧 DEK 前先清零旧值）
    fun unlock(dek: ByteArray) {
        activeDek?.let { zeroBytes(it) }
        activeDek = dek.copyOf()
    }

    // 获取活跃 DEK 副本；锁定态返回 null（DAO 据此拒绝数据访问，契约 §3 约定）。
    fun getActiveDek(): ByteArray? = activeDek?.copyOf()

    fun isLocked(): Boolean = activeDek == null

    // 锁定：显式清零 DEK 并置空。
    fun lock() {
        activeDek?.let { zeroBytes(it) }
        activeDek = null
    }
}

// 自检：解锁/锁定/清零不变量（静默、无 Android/DB/文件）。
fun vaultSessionSelfTest() {
    val s = VaultSession()
    checkThat(s.isLocked()) { "初始应为锁定态" }
    checkThat(s.getActiveDek() == null) { "锁定态 getActiveDek 应为 null" }

    val dek = randomDek()
    s.unlock(dek)
    checkThat(!s.isLocked()) { "unlock 后应为解锁态" }
    val got = s.getActiveDek()
    checkThat(got != null && got.contentEquals(dek)) { "unlock 后 DEK 应可取回" }

    s.lock()
    checkThat(s.isLocked()) { "lock 后应为锁定态" }
    checkThat(s.getActiveDek() == null) { "lock 后 DEK 应为 null" }
    // 原 dek 数组未被 lock 清零（lock 只清零拷贝），此不变量说明副本隔离生效
    checkThat(dek.any { it != 0.toByte() }) { "lock 不应影响调用方传入的原 DEK（持有副本）" }
}
