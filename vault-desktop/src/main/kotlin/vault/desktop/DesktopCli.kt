package vault.desktop

import java.io.File
import vault.EntryInput
import vault.SortKey

// ============================================================================
// DesktopCli：桌面端命令行工具（M4 跨端互导实测 + 日常运维）。
//  M5 的 Compose UI 就绪前，桌面侧全部操作经此工具；之后仍保留作调试/备份脚本入口。
//
// 用法（gradle）：./gradlew :vault-desktop:runCli -q --args="<命令> ..."
// 命令：
//   init <主密码>                                   首次初始化（≥8 位）
//   stats <主密码>                                  解锁并打印条目/分类统计
//   add <主密码> <名称> <账号> <密码> [分类名]       新增条目（跨端测试造数据用）
//   export <主密码> <输出.vault> same               主密码同源导出
//   export <主密码> <输出.vault> <独立导出密码>       独立密码导出
//   import <主密码> <输入.vault> <文件密码>          导入合并，打印 MergeReport
//
// 库文件：--db=<路径> 覆盖（默认 %APPDATA%\OfflineVault\vault.db；env OFFLINE_VAULT_DIR 亦可）。
// ponytail: 密码走命令行参数仅限本地调试工具（进程列表可见风险），M5 正式 UI 不走此路径。
// | 升级阈值：工具被脚本化长期使用时改 stdin 读取。
// ============================================================================

fun main(args: Array<String>) {
    if (args.isEmpty()) { println(USAGE); return }
    val rest = args.toList()
    val dbOverride = rest.firstOrNull { it.startsWith("--db=") }?.removePrefix("--db=")
    val positional = rest.filterNot { it.startsWith("--db=") }
    val dbFile = dbOverride?.let { File(it) } ?: DesktopDb.defaultVaultFile()

    when (positional.firstOrNull()) {
        "init" -> {
            require(positional.size == 2) { "用法: init <主密码>" }
            val v = DesktopVault(dbFile)
            val ok = v.initializeMasterPassword(positional[1])
            println(if (ok) "OK 已初始化（种子分类已写入）" else "SKIP 已初始化过，拒绝重复初始化")
            v.close()
        }
        "stats" -> {
            require(positional.size == 2) { "用法: stats <主密码>" }
            val v = DesktopVault(dbFile)
            if (!v.unlockWithPassword(positional[1])) { println("FAIL 解锁失败（密码错误或冷却 ${v.lockoutRemainingMs() / 1000}s）"); v.close(); return }
            val entries = v.listEntries(sortBy = SortKey.UPDATED_DESC)
            println("条目总数: ${entries.size}")
            v.listCategories().forEach { println("  分类 ${it.name}: ${it.entryCount} 条 (sort=${it.sortOrder})") }
            entries.take(20).forEach { println("  - ${it.name} / ${it.username.ifBlank { "—" }} [${it.categoryName ?: "未分类"}]") }
            if (entries.size > 20) println("  …（其余 ${entries.size - 20} 条省略）")
            v.close()
        }
        "add" -> {
            require(positional.size in 5..6) { "用法: add <主密码> <名称> <账号> <密码> [分类名]" }
            val v = DesktopVault(dbFile)
            if (!v.unlockWithPassword(positional[1])) { println("FAIL 解锁失败"); v.close(); return }
            val catId = positional.getOrNull(5)?.let { cn -> v.listCategories().firstOrNull { it.name == cn }?.id }
            val id = v.createEntry(EntryInput(positional[2], positional[3], positional[4], "", "", catId, emptyList()))
            println("OK 已新增 id=$id ${positional[2]}/${positional[3]}")
            v.close()
        }
        "export" -> {
            require(positional.size == 4) { "用法: export <主密码> <输出.vault> same|<独立导出密码>" }
            val v = DesktopVault(dbFile)
            if (!v.unlockWithPassword(positional[1])) { println("FAIL 解锁失败"); v.close(); return }
            val useMaster = positional[3] == "same"
            // 同源:用主密码派生 KEK(第二参=主密码);独立:第二参=独立导出密码(第 3 个命令行参数)
            val bytes = v.exportVault(if (useMaster) positional[1] else positional[3], useMaster)
            File(positional[2]).writeBytes(bytes)
            println("OK 已导出 ${bytes.size} 字节 → ${positional[2]}（${if (useMaster) "主密码同源" else "独立导出密码"}）")
            v.close()
        }
        "import" -> {
            require(positional.size == 4) { "用法: import <主密码> <输入.vault> <文件密码>" }
            val v = DesktopVault(dbFile)
            if (!v.unlockWithPassword(positional[1])) { println("FAIL 解锁失败"); v.close(); return }
            val report = v.importVault(File(positional[2]).readBytes(), positional[3])
            println("OK 导入完成：分类 +${report.categoriesAdded}/合并${report.categoriesMerged}，" +
                "条目 +${report.entriesAdded}/更新${report.entriesUpdated}/跳过${report.entriesSkipped}")
            v.close()
        }
        else -> println(USAGE)
    }
}

private const val USAGE = """
离线密码本 · 桌面 CLI
用法: ./gradlew :vault-desktop:runCli -q --args="<命令> ..."   （或 --db=<路径> 指定库文件）
  init <主密码>
  stats <主密码>
  add <主密码> <名称> <账号> <密码> [分类名]
  export <主密码> <输出.vault> same|<独立导出密码>
  import <主密码> <输入.vault> <文件密码>
"""
