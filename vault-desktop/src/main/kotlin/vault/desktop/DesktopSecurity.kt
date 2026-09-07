package vault.desktop

import java.io.File

// ============================================================================
// DesktopSecurity：桌面端目录加固（PC 端 PRD §5.1"文件权限"项，M6 落地）。
//  - 首次创建数据目录时，去除继承的"所有用户"权限，仅保留当前用户 + SYSTEM。
//    （Windows 默认 %APPDATA% 下新目录仍继承 Users 读取链；密码库目录应收紧。）
//  - 尽力而为：icacls 失败（如域环境/微软账户用户名解析差异）不阻断启动，
//    仅静默跳过——ACL 缺失时退化为系统默认权限（与旧版行为一致）。
// ============================================================================

object DesktopSecurity {

    /** 目录首次创建后调用：收紧 ACL。已加固过（标记文件存在）则跳过，幂等。 */
    fun hardenDataDir(dir: File) {
        if (!dir.isDirectory) return
        val marker = File(dir, ".acl-hardened")
        if (marker.exists()) return
        try {
            val user = System.getenv("USERNAME") ?: System.getProperty("user.name") ?: return
            val icacls = listOf(
                "icacls", dir.absolutePath,
                "/inheritance:r",
                "/grant:r", "$user:(OI)(CI)F",
                "/grant:r", "SYSTEM:(OI)(CI)F"
            )
            val proc = ProcessBuilder(icacls).redirectErrorStream(true).start()
            val ok = proc.waitFor() == 0
            if (ok) {
                marker.writeText("data dir hardened: only current user + SYSTEM\n")
            } else {
                // 失败不标记，下次启动重试；不阻断应用
                proc.inputStream.readBytes() // 吞掉输出避免管道阻塞
            }
        } catch (_: Exception) {
            // 尽力而为：任何失败（无 icacls/权限异常）都退化为默认权限
        }
    }
}
