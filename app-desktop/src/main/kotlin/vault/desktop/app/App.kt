package vault.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import vault.CategoryRow
import vault.EntryInput
import vault.ExtraField
import vault.PasswordEntryRow
import vault.SettingsPatch
import vault.SortKey
import vault.desktop.DesktopVault
import kotlinx.coroutines.CoroutineScope
import com.qiqiao.passwordvault.util.formatTime
import com.qiqiao.passwordvault.util.generatePassword
import com.qiqiao.passwordvault.util.maskPassword
import com.qiqiao.passwordvault.util.passwordStrength
import com.qiqiao.passwordvault.util.strengthLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ============================================================================
// 桌面 UI（PC 端 PRD M5，新拟态全量覆盖版：无边框自绘窗体 + NeuSurface 组件）。
//  屏：解锁/首初始化（F1/F2）→ 主屏（列表+详情双栏，F3/F4）→ 编辑弹窗（F10 生成器）/
//  设置弹窗（F8/F9 参数 + 改主密码 + 备份导出/导入 F5/F6）。
//  颜色/阴影全部取自 LocalNeu（逐字节移植 Android colors.xml 与 NeuShadowPrefs 定稿档）。
// ============================================================================

class AppModel(private val vault: DesktopVault) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    var initialized by mutableStateOf(false)
    var unlocked by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var unlockError by mutableStateOf<String?>(null)
    var lockoutSec by mutableStateOf(0)

    var entries by mutableStateOf<List<PasswordEntryRow>>(emptyList())
    var categories by mutableStateOf<List<CategoryRow>>(emptyList())
    var selectedId by mutableStateOf<Long?>(null)
    var selectedDetail by mutableStateOf<PasswordEntryRow?>(null)
    var search by mutableStateOf("")
    var sortBy by mutableStateOf(SortKey.UPDATED_DESC)
    var categoryFilter by mutableStateOf<Long?>(null)

    var autoLockSec by mutableStateOf(60)
    var clipboardSec by mutableStateOf(30)
    var snackbar by mutableStateOf<String?>(null)

    private var searchJob: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            initialized = vault.isInitialized()
            if (initialized) {
                refreshSettings()
                if (vault.isLocked()) lockUi() else { unlocked = true; refreshData() }
            }
        }
        scope.launch {
            while (true) {
                delay(1000)
                // F8 空闲锁：解锁态 + 超时无活动
                if (unlocked && autoLockSec > 0 && ActivityMonitor.idleMillis() >= autoLockSec * 1000L) {
                    withContext(Dispatchers.IO) { vault.lock() }
                    DesktopClipboard.flushNow()
                    lockUi()
                }
                // 解锁页冷却倒计时（SEC-8 UI 反馈）
                if (!unlocked && lockoutSec > 0) {
                    lockoutSec = (vault.lockoutRemainingMs() / 1000L).toInt().coerceAtLeast(0)
                }
            }
        }
    }

    fun stop() { scope.cancel() }

    fun lockNow() {
        scope.launch { vault.lock() }
        DesktopClipboard.flushNow()
        lockUi()
    }

    fun note(text: String) { snackbar = text }

    fun lockUi() { unlocked = false; selectedId = null; selectedDetail = null; unlockError = null }

    fun refreshSettings() {
        val s = vault.getSettings()
        autoLockSec = s.autoLockTimeoutSec
        clipboardSec = s.clipboardClearDelaySec
    }

    fun refreshData() {
        scope.launch {
            entries = vault.listEntries(search.takeIf { it.isNotBlank() }, sortBy, categoryFilter)
            categories = vault.listCategories()
        }
    }

    fun onSearchChange(text: String) {
        search = text
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(250) // 防抖，与 Android 同款
            entries = vault.listEntries(search.takeIf { it.isNotBlank() }, sortBy, categoryFilter)
        }
    }

    fun setSort(key: SortKey) { sortBy = key; refreshData() }
    fun filterByCategory(id: Long?) { categoryFilter = id; refreshData() }

    fun select(id: Long) {
        selectedId = id
        scope.launch { selectedDetail = vault.getEntry(id) }
    }

    fun unlock(password: String) {
        if (busy || lockoutSec > 0) return
        busy = true; unlockError = null
        scope.launch {
            val ok = vault.unlockWithPassword(password)
            busy = false
            if (ok) { unlocked = true; refreshData() }
            else {
                lockoutSec = (vault.lockoutRemainingMs() / 1000L).toInt()
                unlockError = if (lockoutSec > 0) "尝试次数过多，请 ${lockoutSec}s 后重试" else "主密码错误"
            }
        }
    }

    fun initialize(password: String) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = vault.initializeMasterPassword(password)
            busy = false
            if (ok) { initialized = true; unlocked = true; refreshData() }
            else unlockError = "初始化失败（可能已初始化过）"
        }
    }

    fun saveEntry(id: Long?, input: EntryInput, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = try {
                if (id == null) { vault.createEntry(input) > 0; true } else vault.updateEntry(id, input)
            } catch (e: Exception) { false }
            if (ok) refreshData()
            onDone(ok)
        }
    }

    fun deleteEntry(id: Long, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = vault.deleteEntry(id)
            if (ok) { selectedId = null; selectedDetail = null; refreshData() }
            onDone(ok)
        }
    }

    fun copySecret(text: String, label: String) {
        if (text.isBlank()) return
        DesktopClipboard.copy(text)
        note("$label 已复制，${if (clipboardSec > 0) "${clipboardSec}s 后自动清除" else "请手动清除剪贴板"}")
        if (clipboardSec > 0) scope.launch {
            delay(clipboardSec * 1000L)
            if (DesktopClipboard.clearIfOurs()) note("剪贴板已清除")
        }
    }

    fun changeMaster(old: String, new: String, confirm: String, onResult: (String?) -> Unit) {
        val err = when (com.qiqiao.passwordvault.util.validateChangeMaster(old, new, confirm)) {
            com.qiqiao.passwordvault.util.ChangeMasterError.OLD_BLANK,
            com.qiqiao.passwordvault.util.ChangeMasterError.NEW_BLANK -> "密码不能为空"
            com.qiqiao.passwordvault.util.ChangeMasterError.NEW_TOO_SHORT -> "新主密码至少 8 位"
            com.qiqiao.passwordvault.util.ChangeMasterError.MISMATCH -> "两次输入的新密码不一致"
            else -> null
        }
        if (err != null) { onResult(err); return }
        scope.launch {
            val ok = vault.changeMasterPassword(old, new)
            onResult(if (ok) null else "旧主密码错误")
        }
    }

    fun exportBackup(path: String, filePw: String, same: Boolean, onResult: (String) -> Unit) {
        scope.launch {
            val msg = try {
                val bytes = vault.exportVault(filePw, same)
                File(path).writeBytes(bytes)
                "已导出 ${bytes.size} 字节 → $path"
            } catch (e: Exception) {
                when (e) {
                    is vault.LockedException -> "会话已锁定"
                    is vault.WrongPasswordException -> "主密码错误"
                    else -> "导出失败：${e.message}"
                }
            }
            onResult(msg)
        }
    }

    fun importBackup(path: String, filePw: String, onResult: (String) -> Unit) {
        scope.launch {
            val msg = try {
                val r = vault.importVault(File(path).readBytes(), filePw)
                refreshData()
                "导入完成：分类 +${r.categoriesAdded}/合并${r.categoriesMerged}，" +
                    "条目 +${r.entriesAdded}/更新${r.entriesUpdated}/跳过${r.entriesSkipped}" +
                    "（合并≠同步，删除不会传播）"
            } catch (e: vault.WrongPasswordException) { "导入失败：文件密码错误" }
            catch (e: Exception) { "导入失败：${e.message}" }
            onResult(msg)
        }
    }

    fun saveSettings(lock: Int, clip: Int, onResult: (String) -> Unit) {
        scope.launch {
            val ok = vault.updateSettings(SettingsPatch(autoLockTimeoutSec = lock, clipboardClearDelaySec = clip))
            if (ok) { autoLockSec = lock; clipboardSec = clip }
            onResult(if (ok) "已保存" else "保存失败")
        }
    }
}

private val SortOptions = listOf(
    SortKey.UPDATED_DESC to "最近更新",
    SortKey.NAME_ASC to "名称 A→Z",
    SortKey.NAME_DESC to "名称 Z→A",
    SortKey.CATEGORY_ASC to "分类 ↑",
    SortKey.CATEGORY_DESC to "分类 ↓",
)

@Composable
fun AppRoot(model: AppModel) {
    val neu = LocalNeu.current
    Column(Modifier.fillMaxSize().background(neu.bg)) {
        if (!model.unlocked) UnlockScreen(model) else MainScreen(model)
    }
    // 全局提示条（新拟态凸起气泡）
    model.snackbar?.let { msg ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            NeuSurface(dir = NeuDir.Raised, cornerRadius = 14.dp,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                Text(msg, color = neu.onSurface, fontSize = 13.sp)
            }
        }
        LaunchedEffect(msg) { delay(3000); if (model.snackbar == msg) model.snackbar = null }
    }
}

// ---------------- 品牌徽章 ----------------

@Composable
private fun BrandBadge() {
    val neu = LocalNeu.current
    NeuSurface(dir = NeuDir.Raised, cornerRadius = 40.dp,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp)) {
        Text("17°", color = neu.primary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------- 解锁 / 首次初始化（F1/F2） ----------------

@Composable
fun UnlockScreen(model: AppModel) = if (!model.initialized) InitScreen(model) else UnlockPanel(model)

@Composable
private fun InitScreen(model: AppModel) {
    val neu = LocalNeu.current
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BrandBadge()
        Spacer(Modifier.height(18.dp))
        Text("秘匣 · 密码保险库", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(6.dp))
        Text("首次使用：设置主密码（≥8 位，纯离线无法找回，请牢记）",
            fontSize = 12.sp, color = neu.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        NeuField(p1, { p1 = it }, hint = "主密码", isPassword = true, modifier = Modifier.width(360.dp))
        Spacer(Modifier.height(14.dp))
        NeuField(p2, { p2 = it }, hint = "确认主密码", isPassword = true, modifier = Modifier.width(360.dp))
        if (model.unlockError != null) {
            Spacer(Modifier.height(8.dp))
            Text(model.unlockError!!, color = neu.error, fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
        NeuButton(
            text = if (model.busy) "正在初始化…" else "创建密码库",
            enabled = !model.busy && p1.length >= 8 && p1 == p2
        ) {
            when {
                p1.length < 8 -> model.unlockError = "主密码至少 8 位"
                p1 != p2 -> model.unlockError = "两次输入不一致"
                else -> model.initialize(p1)
            }
        }
    }
}

@Composable
private fun UnlockPanel(model: AppModel) {
    val neu = LocalNeu.current
    var pw by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BrandBadge()
        Spacer(Modifier.height(18.dp))
        Text("秘匣 · 密码保险库", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(6.dp))
        Text("NEUMORPHISM VAULT", fontSize = 11.sp, letterSpacing = 3.sp, color = neu.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        NeuField(pw, { pw = it }, hint = "主密码", isPassword = true, modifier = Modifier.width(360.dp))
        if (model.unlockError != null) {
            Spacer(Modifier.height(8.dp))
            Text(model.unlockError!!, color = neu.error, fontSize = 12.sp)
        }
        if (model.lockoutSec > 0) {
            Spacer(Modifier.height(6.dp))
            Text("冷却中 ${model.lockoutSec}s", color = neu.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
        NeuButton(
            text = when {
                model.busy -> "解锁中…"
                model.lockoutSec > 0 -> "冷却中 ${model.lockoutSec}s"
                else -> "解 锁"
            },
            enabled = !model.busy && model.lockoutSec <= 0,
            contentPadding = PaddingValues(horizontal = 96.dp, vertical = 14.dp)
        ) { model.unlock(pw); pw = "" }
        Spacer(Modifier.height(40.dp))
        // 底部指纹位（视觉呼应 Android 版）
        NeuSurface(dir = NeuDir.Raised, cornerRadius = 34.dp,
            contentPadding = PaddingValues(14.dp), modifier = Modifier.alpha(0.7f)) {
            Text("🔒", fontSize = 18.sp)
        }
    }
}

// ---------------- 主屏（F3/F4，列表 + 详情双栏） ----------------

@Composable
fun MainScreen(model: AppModel) {
    val neu = LocalNeu.current
    var showSettings by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<PasswordEntryRow?>(null) }
    var showDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：凸起条（搜索 + 排序 + 分类 + 计数 + 设置 + 锁定）
        NeuSurface(dir = NeuDir.Raised, cornerRadius = 0.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("秘匣", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
                Spacer(Modifier.width(16.dp))
                NeuField(model.search, { model.onSearchChange(it) }, hint = "搜索名称或用户名",
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                SortMenu(model.sortBy) { model.setSort(it) }
                CategoryMenu(model) { model.filterByCategory(it) }
                Spacer(Modifier.width(6.dp))
                Text("共 ${model.entries.size} 条", fontSize = 12.sp, color = neu.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                NeuIconButton(onClick = { showSettings = true }, sizeDp = 38.dp) {
                    Text("⚙", fontSize = 16.sp, color = neu.onSurfaceVariant)
                }
                Spacer(Modifier.width(6.dp))
                NeuIconButton(onClick = { model.lockNow() }, sizeDp = 38.dp) {
                    Text("🔒", fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxSize()) {
            // 左：列表
            Column(modifier = Modifier.width(400.dp).fillMaxHeight().padding(horizontal = 8.dp)) {
                if (model.entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (model.search.isBlank()) "还没有条目，点右下角 + 新增" else "无匹配结果",
                            color = neu.onSurfaceVariant, fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn {
                        items(model.entries, key = { it.id }) { e ->
                            EntryCard(
                                name = e.name, username = e.username,
                                category = e.categoryName ?: "未分类",
                                selected = e.id == model.selectedId,
                                onClick = { model.select(e.id) }
                            )
                        }
                    }
                }
            }
            // 分隔线（凹槽）
            Box(Modifier.width(3.dp).fillMaxHeight().background(neu.insetBg))
            // 右：详情
            DetailPane(
                model = model,
                onEdit = { editTarget = model.selectedDetail; showEdit = true },
                onDelete = { showDelete = true }
            )
        }
    }

    // 悬浮新增钮（右下）
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        NeuSurface(
            dir = NeuDir.Raised, cornerRadius = 28.dp,
            modifier = Modifier.padding(end = 24.dp, bottom = 24.dp)
        ) {
            Text(
                "+", fontSize = 26.sp, color = neu.primary, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { editTarget = null; showEdit = true }
                    .padding(horizontal = 18.dp, vertical = 4.dp)
            )
        }
    }

    if (showEdit) EditDialog(model, editTarget) { showEdit = false }
    if (showSettings) SettingsDialog(model) { showSettings = false }
    if (showDelete) {
        val name = model.selectedDetail?.name ?: ""
        NeuDialogShell(width = 420.dp) {
            Text("删除条目", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
            Spacer(Modifier.height(12.dp))
            Text("删除「$name」？（软删除，可由含该条目的备份重新导入恢复）",
                color = neu.onSurface, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                NeuTextButton("取消") { showDelete = false }
                Spacer(Modifier.width(8.dp))
                NeuButton("删除", danger = true) {
                    model.selectedDetail?.let { e -> model.deleteEntry(e.id) { showDelete = false } }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(name: String, username: String, category: String, selected: Boolean, onClick: () -> Unit) {
    val neu = LocalNeu.current
    NeuSurface(
        dir = NeuDir.Raised, cornerRadius = 18.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onClick),
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuSurface(dir = NeuDir.Raised, cornerRadius = 12.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text(monogram(name), color = neu.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = neu.onSurface, fontSize = 15.sp)
                Text(username.ifBlank { "—" }, fontSize = 12.sp, color = neu.onSurfaceVariant)
            }
            Text(category, fontSize = 11.sp, color = neu.onSurfaceVariant)
        }
    }
}

private fun monogram(name: String): String {
    val t = name.trim()
    return if (t.isEmpty()) "？" else t.substring(0, 1).uppercase()
}

// ---------------- 详情右栏 ----------------

@Composable
private fun RowScope.DetailPane(model: AppModel, onEdit: () -> Unit, onDelete: () -> Unit) {
    val neu = LocalNeu.current
    val e = model.selectedDetail
    if (e == null) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text("选择左侧条目查看详情", color = neu.onSurfaceVariant, fontSize = 13.sp)
        }
        return
    }
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = neu.onSurface,
                modifier = Modifier.weight(1f))
            NeuTextButton("编辑") { onEdit() }
            NeuTextButton("删除") { onDelete() }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${e.categoryName ?: "未分类"} · 更新于 ${formatTime(e.updatedAt)} · 强度 ${strengthLabel(passwordStrength(e.password.orEmpty()))}",
            fontSize = 11.sp, color = neu.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        FieldRow("账号", e.username, model)
        PasswordRow(e.password.orEmpty(), model)
        FieldRow("网站", e.website.orEmpty(), model)
        FieldRow("备注", e.notes.orEmpty(), model)
        e.extras.forEach { f -> FieldRow(f.label, f.value, model) }
        Spacer(Modifier.height(8.dp))
        Text("创建于 ${formatTime(e.createdAt)}", fontSize = 11.sp, color = neu.onSurfaceVariant)
    }
}

@Composable
private fun FieldRow(label: String, value: String, model: AppModel) {
    val neu = LocalNeu.current
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, fontSize = 11.sp, color = neu.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value.ifBlank { "—" },
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = neu.textIn
            )
            NeuIconButton(onClick = { model.copySecret(value, label) }, sizeDp = 34.dp) {
                Text("⧉", color = neu.accent, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PasswordRow(password: String, model: AppModel) {
    val neu = LocalNeu.current
    var revealed by remember(password) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("密码", fontSize = 11.sp, color = neu.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (revealed) password else maskPassword(),
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = if (revealed) neu.primary else neu.textIn
            )
            NeuIconButton(onClick = { revealed = !revealed }, sizeDp = 34.dp) {
                Text(if (revealed) "🙈" else "👁", fontSize = 13.sp)
            }
            NeuIconButton(onClick = { model.copySecret(password, "密码") }, sizeDp = 34.dp) {
                Text("⧉", color = neu.accent, fontSize = 14.sp)
            }
        }
    }
}

// ---------------- 下拉菜单（排序 / 分类过滤） ----------------

@Composable
private fun SortMenu(current: SortKey, onSelect: (SortKey) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val neu = LocalNeu.current
    Box {
        Text(
            "▾ ${SortOptions.first { it.first == current }.second}",
            fontSize = 13.sp, color = neu.onSurface, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        DropdownMenu(open, { open = false }) {
            SortOptions.forEach { (k, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(k); open = false })
            }
        }
    }
}

@Composable
private fun CategoryMenu(model: AppModel, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val neu = LocalNeu.current
    val label = if (model.categoryFilter == null) "全部分类"
    else model.categories.firstOrNull { it.id == model.categoryFilter }?.name ?: "全部分类"
    Box {
        Text(
            "▾ $label", fontSize = 13.sp, color = neu.onSurface, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("全部分类") },
                onClick = { onSelect(null); open = false })
            model.categories.forEach { c ->
                DropdownMenuItem(text = { Text("${c.name}（${c.entryCount}）") },
                    onClick = { onSelect(c.id); open = false })
            }
        }
    }
}

// ---------------- 编辑弹窗（F3；生成器 F10；extras 编辑 P2，编辑时保留原值不丢） ----------------

@Composable
fun EditDialog(model: AppModel, initial: PasswordEntryRow?, onDismiss: () -> Unit) {
    val neu = LocalNeu.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var website by remember { mutableStateOf(initial?.website ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var revealed by remember { mutableStateOf(false) }
    var catId by remember { mutableStateOf(initial?.categoryId) }
    var showCat by remember { mutableStateOf(false) }

    NeuDialogShell(width = 540.dp) {
        Text(if (initial == null) "新增条目" else "编辑条目",
            fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(16.dp))
        NeuField(name, { name = it }, hint = "平台 *", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        NeuField(username, { username = it }, hint = "账号", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuField(password, { password = it }, hint = "密码", isPassword = true,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            NeuIconButton(onClick = { revealed = !revealed }, sizeDp = 40.dp) {
                Text(if (revealed) "🙈" else "👁", fontSize = 13.sp)
            }
            Spacer(Modifier.width(6.dp))
            NeuButton("生成") { password = generatePassword(20, com.qiqiao.passwordvault.util.CharsetFlags()) }
        }
        Spacer(Modifier.height(4.dp))
        Text("强度：${strengthLabel(passwordStrength(password))}",
            fontSize = 11.sp, color = neu.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        NeuField(website, { website = it }, hint = "网站", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        NeuField(notes, { notes = it }, hint = "备注", singleLine = false, minLines = 3,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Box {
            NeuTextButton(
                "分类：${model.categories.firstOrNull { it.id == catId }?.name ?: "未分类"} ▾"
            ) { showCat = true }
            DropdownMenu(showCat, { showCat = false }) {
                DropdownMenuItem(text = { Text("未分类") }, onClick = { catId = null; showCat = false })
                model.categories.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { catId = c.id; showCat = false })
                }
            }
        }
        if (initial != null && initial.extras.isNotEmpty()) {
            Text("自定义词条 ${initial.extras.size} 条将保留（词条编辑 P2）",
                fontSize = 11.sp, color = neu.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeuTextButton("取消") { onDismiss() }
            Spacer(Modifier.width(10.dp))
            NeuButton("保存", enabled = name.isNotBlank()) {
                // 编辑时保留已有自定义词条（P2 才开放词条编辑），防保存误清
                val input = EntryInput(
                    name.trim(), username.trim(), password, website.trim(), notes,
                    catId, initial?.extras ?: emptyList()
                )
                model.saveEntry(initial?.id, input) { ok -> if (ok) onDismiss() }
            }
        }
    }
}

// ---------------- 设置弹窗（F8/F9 参数 + 改主密码 + 备份 F5/F6） ----------------

@Composable
fun SettingsDialog(model: AppModel, onDismiss: () -> Unit) {
    val neu = LocalNeu.current
    var lockSec by remember { mutableStateOf(model.autoLockSec.toString()) }
    var clipSec by remember { mutableStateOf(model.clipboardSec.toString()) }
    var msg by remember { mutableStateOf<String?>(null) }

    var showChangePw by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    NeuDialogShell(width = 540.dp) {
        Column {
            Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
            Spacer(Modifier.height(16.dp))

            NeuField(lockSec, { lockSec = it }, hint = "自动锁超时（秒，0=不超时）",
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            NeuField(clipSec, { clipSec = it }, hint = "剪贴板清除延时（秒，0=永不）",
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            NeuButton("保存安全设置") {
                val l = lockSec.toIntOrNull(); val c = clipSec.toIntOrNull()
                if (l == null || c == null || l < 0 || c < 0) msg = "请输入非负整数"
                else model.saveSettings(l, c) { msg = it }
            }

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(neu.insetBg))
            Spacer(Modifier.height(14.dp))
            Text("修改主密码", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
            Spacer(Modifier.height(8.dp))
            NeuButton("修改主密码…") { msg = null; showChangePw = true }

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(neu.insetBg))
            Spacer(Modifier.height(14.dp))
            Text("备份（与 Android 版 .vault 双向兼容）", fontSize = 15.sp,
                fontWeight = FontWeight.Bold, color = neu.onSurface)
            Spacer(Modifier.height(8.dp))
            Row {
                NeuButton("导出 .vault…") { msg = null; showExport = true }
                Spacer(Modifier.width(12.dp))
                NeuButton("导入 .vault…") { msg = null; showImport = true }
            }

            msg?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 13.sp, color = neu.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                NeuTextButton("关闭") { onDismiss() }
            }
        }
    }

    if (showChangePw) ChangePwDialog(model, onDone = { m -> msg = m; showChangePw = false })
    if (showExport) ExportDialog(model, onDone = { m -> msg = m; showExport = false })
    if (showImport) ImportDialog(model, onDone = { m -> msg = m; showImport = false })
}

@Composable
private fun ChangePwDialog(model: AppModel, onDone: (String?) -> Unit) {
    val neu = LocalNeu.current
    var old by remember { mutableStateOf("") }
    var n1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    NeuDialogShell(width = 460.dp) {
        Text("修改主密码", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(14.dp))
        NeuField(old, { old = it }, hint = "旧主密码", isPassword = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        NeuField(n1, { n1 = it }, hint = "新主密码（≥8 位）", isPassword = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        NeuField(n2, { n2 = it }, hint = "确认新主密码", isPassword = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeuTextButton("取消") { onDone(null) }
            Spacer(Modifier.width(10.dp))
            NeuButton("修改", enabled = old.isNotBlank() && n1.isNotBlank() && n2.isNotBlank()) {
                model.changeMaster(old, n1, n2) { onDone(it) }
            }
        }
    }
}

@Composable
private fun ExportDialog(model: AppModel, onDone: (String?) -> Unit) {
    val neu = LocalNeu.current
    var same by remember { mutableStateOf(true) }
    var master by remember { mutableStateOf("") }
    var exportPw by remember { mutableStateOf("") }

    NeuDialogShell(width = 480.dp) {
        Text("导出加密备份（.vault）", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuTextButton(if (same) "● 主密码同源" else "○ 主密码同源") { same = true }
            NeuTextButton(if (!same) "● 独立导出密码" else "○ 独立导出密码") { same = false }
        }
        if (same) {
            // 同源路径需重输主密码（会话仅持 DEK，不存主密码——与 Android 导出一致）
            Spacer(Modifier.height(10.dp))
            NeuField(master, { master = it }, hint = "本库主密码", isPassword = true,
                modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(10.dp))
            NeuField(exportPw, { exportPw = it }, hint = "独立导出密码（≥8 位）",
                modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (same) "导入端需输入本库主密码" else "导入端需输入此独立导出密码",
            fontSize = 11.sp, color = neu.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeuTextButton("取消") { onDone(null) }
            Spacer(Modifier.width(10.dp))
            NeuButton("选择位置并导出") {
                val filePw = if (same) master else exportPw
                if (filePw.isBlank()) { onDone("请输入密码"); return@NeuButton }
                if (!same && exportPw.length < 8) { onDone("独立导出密码至少 8 位"); return@NeuButton }
                val target = awtSaveDialog(null, "保存加密备份", "offline-vault.vault")
                if (target == null) { onDone(null); return@NeuButton }
                model.exportBackup(target.absolutePath, filePw, same) { onDone(it) }
            }
        }
    }
}

@Composable
private fun ImportDialog(model: AppModel, onDone: (String?) -> Unit) {
    val neu = LocalNeu.current
    var filePw by remember { mutableStateOf("") }
    NeuDialogShell(width = 480.dp) {
        Text("导入加密备份（.vault）", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(14.dp))
        Text("按（名称+账号+分类）合并、更新时间取新。删除不会传播，导入≠同步。",
            fontSize = 12.sp, color = neu.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        NeuField(filePw, { filePw = it }, hint = "该备份文件的密码", isPassword = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeuTextButton("取消") { onDone(null) }
            Spacer(Modifier.width(10.dp))
            NeuButton("选择文件并导入") {
                val src = awtOpenDialog(null, "选择 .vault 备份文件")
                if (src == null) { onDone(null); return@NeuButton }
                model.importBackup(src.absolutePath, filePw) { onDone(it) }
            }
        }
    }
}
