package com.qiqiao.passwordvault.data

import android.os.Handler
import android.os.Looper
import com.qiqiao.passwordvault.model.*
import com.qiqiao.passwordvault.util.MIN_NEW_PASSWORD_LEN
import com.qiqiao.passwordvault.util.sortEntries  // 2026-09-02 #QA-027 修复轮补：listEntries 用到，原缺 import 编译失败
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher  // 生物识别两段式 mock（#QA-029）
import vault.WrongPasswordException

// 前端 Mock 数据层：内存中实现契约全部方法，驱动 UI 完整可交互。
// 不实现任何加密 / DAO（backend/ 职责）。切换真实后端时本文件整体替换。
// ponytail: 解锁接受任意非空主密码（无 KDF/verifier） | 触发升级阈值：真实密码校验由 backend 提供
// ponytail: getEntry 直接回填明文（无 AES-GCM 解密） | 触发升级阈值：真实解密由 backend 提供
class MockVaultData : VaultData {

    private val main = Handler(Looper.getMainLooper())

    // 解锁会话标志（契约 §5）：锁定态 DAO 应失败
    private var locked = false

    // Mock 当前主密码（占位 verifier）：解锁/初始化/改密时更新，供 changeMasterPassword 比对
    private var masterPassword = ""

    // 延迟回调，模拟 DB/文件耗时，使加载态可见
    private fun <T> respond(cb: Done<T>, value: T, delayMs: Long = 200) {
        main.postDelayed({ cb(Result.success(value)) }, delayMs)
    }

    private fun <T> fail(cb: Done<T>, msg: String, delayMs: Long = 200) {
        main.postDelayed({ cb(Result.failure(RuntimeException(msg))) }, delayMs)
    }

    // 种子分类：支付/社交/工作/娱乐/邮箱/其他（sortOrder 0..5，其他不可删）
    private val categories = mutableListOf(
        CategoryRow(1, "支付", 0, 0, 0),
        CategoryRow(2, "社交", 1, 0, 0),
        CategoryRow(3, "工作", 2, 0, 0),
        CategoryRow(4, "娱乐", 3, 0, 0),
        CategoryRow(5, "邮箱", 4, 0, 0),
        CategoryRow(6, DEFAULT_CATEGORY_NAME, 5, 0, 0)
    )

    // 内存条目：列表行 password/website/notes 留空；用隐藏 map 模拟"解密"
    private val entries = mutableListOf<PasswordEntryRow>()
    private val secrets = mutableMapOf<Long, Triple<String, String, String>>() // id -> (password,website,notes)
    private val idSeq = AtomicLong(100)

    private var settings = AppSettings(
        initialized = true,
        autoLockTimeoutSec = 60,
        clipboardClearDelaySec = 30,
        theme = "system",
        biometricEnabled = false
    )

    init { seedEntries() }

    // 2026-09-02 #QA-027：种子数据实为 6 字段，原误用 Triple（仅 3 元组）导致编译失败。
    // 纯 Kotlin 自检子集不含 MockVaultData（它依赖 Android Handler/Looper），故此错误此前未暴露。
    private data class SeedRow(val name: String, val user: String, val cat: String, val pw: String, val web: String, val note: String)

    private fun seedEntries() {
        val seed = listOf(
            SeedRow("微信", "wx_user", "支付", "wechatpass123", "weixin.qq.com", "日常聊天"),
            SeedRow("支付宝", "alipay_user", "支付", "alipay#2024", "alipay.com", "付款码"),
            SeedRow("微博", "weibo_user", "社交", "weibo_pass!", "weibo.com", ""),
            SeedRow("抖音", "douyin_user", "社交", "dy_pass_88", "douyin.com", "短视频"),
            SeedRow("GitHub", "dev_account", "工作", "gh_T0ken$9", "github.com", "代码仓库"),
            SeedRow("公司邮箱", "me@corp.com", "工作", "CorpMail#1", "mail.corp.com", "工作往来"),
            SeedRow("Steam", "gamer_tag", "娱乐", "steam_pw_77", "store.steampowered.com", "游戏"),
            SeedRow("网易云", "music_lover", "娱乐", "music_365", "music.163.com", ""),
            SeedRow("Gmail", "personal@gmail.com", "邮箱", "gmail_pw_22", "gmail.com", "个人邮件"),
            SeedRow("QQ邮箱", "12345@qq.com", "邮箱", "qqmail_pw", "mail.qq.com", ""),
            SeedRow("招商银行", "cmb_user", "支付", "cmb#8888", "cmbchina.com", "网银"),
            SeedRow("临时账号", "temp_user", DEFAULT_CATEGORY_NAME, "temp_000", "", "未分类占位")
        )
        seed.forEachIndexed { i, (name, user, cat, pw, web, note) ->
            val catRow = categories.first { it.name == cat }
            val id = idSeq.incrementAndGet()
            entries.add(
                PasswordEntryRow(
                    id = id, name = name, username = user,
                    categoryId = catRow.id, categoryName = catRow.name,
                    createdAt = 1700000000000L + i * 1000, updatedAt = 1700000000000L + i * 1000
                )
            )
            secrets[id] = Triple(pw, web, note)
        }
        recomputeCounts()
    }

    private fun recomputeCounts() {
        categories.forEach { c ->
            val n = entries.count { !it.isDeleted && it.categoryId == c.id }
            val idx = categories.indexOf(c)
            categories[idx] = c.copy(entryCount = n)
        }
    }

    // ---------------- 解锁 / 初始化 ----------------
    override fun isInitialized(cb: Done<Boolean>) = respond(cb, settings.initialized, 50)
    override fun initializeMasterPassword(password: String, cb: Done<Boolean>) {
        // ponytail: 不校验强度 / 不派生 KEK | 触发升级阈值：真实初始化写盐+KEK+DEK+verifier
        settings = settings.copy(initialized = true)
        locked = false
        masterPassword = password
        respond(cb, password.isNotBlank())
    }

    override fun unlockWithPassword(password: String, cb: Done<Boolean>) {
        // ponytail: 任意非空即解锁 | 触发升级阈值：真实校验 verifier
        locked = password.isBlank()   // 修复：原 isNotBlank() 反了（非空密码应解锁成功）
        if (!locked) masterPassword = password
        respond(cb, !locked, 300)
    }

    override fun lockoutRemainingMs(): Long = 0L   // Mock 不做失败限流（审查 F2 仅真实后端实现）
    // ---- 生物识别（#QA-029 两段式 mock，语义对齐 Real）：Keystore 认证由真机 BiometricPrompt 完成，
    // Mock 无真 Keystore/指纹，用空 Cipher 占位表示"已就绪"；开启/关闭直接改 settings 标志。 ----
    override fun prepareBiometricWrap(cb: Done<Cipher>) {
        try {
            respond(cb, Cipher.getInstance("AES/GCM/NoPadding"))
        } catch (e: Exception) {
            fail(cb, "Cipher 初始化失败: ${e.message}")
        }
    }

    override fun completeBiometricEnable(cipher: Cipher, cb: Done<Boolean>) {
        // ponytail: mock 直接开（不验证 cipher）；真实路径 Keystore wrap DEK 后才置 enabled | 触发升级阈值：无
        settings = settings.copy(biometricEnabled = true)
        respond(cb, true)
    }

    override fun prepareBiometricUnlock(cb: Done<Cipher>) {
        if (!settings.biometricEnabled) { fail(cb, "BIOMETRIC_UNAVAILABLE"); return }
        try {
            respond(cb, Cipher.getInstance("AES/GCM/NoPadding"))
        } catch (e: Exception) {
            fail(cb, "Cipher 初始化失败: ${e.message}")
        }
    }

    override fun unlockWithBiometric(cipher: Cipher, cb: Done<Boolean>) {
        locked = !settings.biometricEnabled
        respond(cb, settings.biometricEnabled, 300)
    }

    override fun disableBiometric(cb: Done<Boolean>) {
        settings = settings.copy(biometricEnabled = false)
        respond(cb, true)
    }

    // ---- 解锁会话（契约 §3.6 / §5）----
    override fun lock() {
        // ponytail: 仅清标志，未显式清零 DEK 字节数组 | 触发升级阈值：真实实现显式 fill(0) 清 DEK
        locked = true
    }
    override fun isLocked(): Boolean = locked

    // ---------------- 条目 ----------------
    override fun listEntries(
        search: String?,
        sortBy: SortKey,
        categoryId: Long?,
        limit: Int,
        offset: Int,
        cb: Done<List<PasswordEntryRow>>
    ) {
        // ponytail: 用特殊 query 触发错误态，便于 QA 验证错误 UI | 触发升级阈值：真实 DAO 异常路径
        if (locked) { fail(cb, "LOCKED"); return }
        if (search == "__error__") { fail(cb, "模拟列表加载失败"); return }
        var list = entries.filter { !it.isDeleted }
        if (categoryId != null) list = list.filter { it.categoryId == categoryId }
        list = sortEntries(list, sortBy)
        // 分页（转成独立 List，避免持有只读视图）
        val paged = if (offset >= list.size) emptyList()
        else list.subList(offset, minOf(offset + limit, list.size)).toList()
        respond(cb, paged)
    }

    override fun getEntry(id: Long, cb: Done<PasswordEntryRow?>) {
        if (locked) { fail(cb, "LOCKED"); return }
        val row = entries.firstOrNull { it.id == id && !it.isDeleted }
        val filled = row?.let {
            val (pw, web, note) = secrets[id] ?: Triple("", "", "")
            it.copy(password = pw, website = web, notes = note)
        }
        respond(cb, filled)
    }

    override fun createEntry(input: EntryInput, cb: Done<Long>) {
        val id = idSeq.incrementAndGet()
        val cat = categories.firstOrNull { it.id == input.categoryId }
        entries.add(
            PasswordEntryRow(
                id = id, name = input.name, username = input.username,
                categoryId = input.categoryId, categoryName = cat?.name ?: DEFAULT_CATEGORY_NAME,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
            )
        )
        secrets[id] = Triple(input.password, input.website, input.notes)
        recomputeCounts()
        respond(cb, id)
    }

    override fun updateEntry(id: Long, input: EntryInput, cb: Done<Boolean>) {
        val idx = entries.indexOfFirst { it.id == id && !it.isDeleted }
        if (idx < 0) { respond(cb, false); return }
        val cat = categories.firstOrNull { it.id == input.categoryId }
        entries[idx] = entries[idx].copy(
            name = input.name, username = input.username,
            categoryId = input.categoryId, categoryName = cat?.name ?: DEFAULT_CATEGORY_NAME,
            updatedAt = System.currentTimeMillis()
        )
        secrets[id] = Triple(input.password, input.website, input.notes)
        recomputeCounts()
        respond(cb, true)
    }

    override fun deleteEntry(id: Long, cb: Done<Boolean>) {
        val idx = entries.indexOfFirst { it.id == id && !it.isDeleted }
        if (idx < 0) { respond(cb, false); return }
        entries[idx] = entries[idx].copy(isDeleted = true, updatedAt = System.currentTimeMillis())
        recomputeCounts()
        respond(cb, true)
    }

    // ---------------- 分类 ----------------
    override fun listCategories(cb: Done<List<CategoryRow>>) {
        if (locked) { fail(cb, "LOCKED"); return }
        respond(cb, categories.sortedBy { it.sortOrder }.toList())
    }

    override fun createCategory(name: String, sortOrder: Int, cb: Done<Long>) {
        if (categories.any { it.name == name }) { respond(cb, -1); return }
        val id = idSeq.incrementAndGet()
        categories.add(CategoryRow(id, name, sortOrder, 0, System.currentTimeMillis()))
        respond(cb, id)
    }

    override fun updateCategory(id: Long, name: String?, sortOrder: Int?, cb: Done<Boolean>) {
        val idx = categories.indexOfFirst { it.id == id }
        if (idx < 0) { respond(cb, false); return }
        val c = categories[idx]
        categories[idx] = c.copy(
            name = name ?: c.name,
            sortOrder = sortOrder ?: c.sortOrder
        )
        recomputeCounts()
        respond(cb, true)
    }

    override fun deleteCategory(id: Long, cb: Done<Boolean>) {
        val c = categories.firstOrNull { it.id == id }
        if (c == null) { respond(cb, false); return }
        if (c.name == DEFAULT_CATEGORY_NAME) { respond(cb, false); return } // 其他不可删
        if (c.entryCount > 0) { respond(cb, false); return }               // 非空分类禁止删
        categories.removeIf { it.id == id }
        respond(cb, true)
    }

    override fun reorderCategories(orderedIds: List<Long>, cb: Done<Boolean>) {
        if (locked) { fail(cb, "LOCKED"); return }
        val idSet = orderedIds.toSet()
        // 仅处理存在的 id，按传入顺序写入 sortOrder = 下标
        orderedIds.forEachIndexed { index, id ->
            val idx = categories.indexOfFirst { it.id == id }
            if (idx >= 0) categories[idx] = categories[idx].copy(sortOrder = index)
        }
        // 兜底：未出现在 orderedIds 中的分类（理论上不会）顺延排到末尾，避免并列
        var tail = orderedIds.size
        categories.forEach { if (it.id !in idSet) categories[categories.indexOfFirst { c -> c.id == it.id }] = it.copy(sortOrder = tail++) }
        respond(cb, true)
    }

    // ---------------- 设置 ----------------
    override fun getSettings(cb: Done<AppSettings>) {
        if (locked) { fail(cb, "LOCKED"); return }
        respond(cb, settings, 50)
    }
    override fun updateSettings(patch: SettingsPatch, cb: Done<Boolean>) {
        settings = settings.copy(
            autoLockTimeoutSec = patch.autoLockTimeoutSec ?: settings.autoLockTimeoutSec,
            clipboardClearDelaySec = patch.clipboardClearDelaySec ?: settings.clipboardClearDelaySec,
            theme = patch.theme ?: settings.theme,
            biometricEnabled = patch.biometricEnabled ?: settings.biometricEnabled
        )
        respond(cb, true)
    }

    override fun changeMasterPassword(oldPassword: String, newPassword: String, cb: Done<Unit>) {
        // 与后端 RealVaultData 对齐（#QA-023）：新密码过短/旧密码错误失败类型一致，供 UI 失败分支区分异常。
        // 新密码过短 UI 已拦截（NEW_TOO_SHORT），此处为防御性对齐，正常不可达。
        // ponytail: 用内存 mock 当前密码比对（无真实 verifier/KEK 重包），失败抛 WrongPasswordException（与后端一致） | 触发升级阈值：真实改密由 backend AppSettingsStore.changeMasterPassword 提供
        if (newPassword.isBlank() || newPassword.length < MIN_NEW_PASSWORD_LEN) {
            main.postDelayed({ cb(Result.failure(RuntimeException("新主密码为空或过短"))) }, 200)
            return
        }
        if (oldPassword.isBlank() || oldPassword != masterPassword) {
            main.postDelayed({ cb(Result.failure(WrongPasswordException())) }, 200)
            return
        }
        masterPassword = newPassword
        respond(cb, Unit)
    }

    // ---------------- 备份 ----------------
    override fun exportVault(password: String, useMasterPassword: Boolean, cb: Done<String>) {
        // ponytail: 不真正加密，返回占位文件名 | 触发升级阈值：真实 .vault 字节由 backend 产出
        if (password.isBlank()) { fail(cb, "导出密码不能为空"); return }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        respond(cb, "password_vault_$ts.vault")
    }

    override fun importVault(fileBytes: ByteArray, password: String, cb: Done<MergeReport>) {
        // ponytail: 返回固定示例报告，不解析文件 | 触发升级阈值：真实合并逻辑由 backend 提供
        if (password.isBlank()) { fail(cb, "导入密码不能为空"); return }
        if (fileBytes.isEmpty()) { fail(cb, "文件为空"); return }
        respond(
            cb, MergeReport(
                categoriesAdded = 1, categoriesMerged = 2,
                entriesAdded = 3, entriesUpdated = 1, entriesSkipped = 0
            )
        )
    }
}
