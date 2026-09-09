package vault.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.qiqiao.passwordvault.util.formatTime
import com.qiqiao.passwordvault.util.generatePassword
import com.qiqiao.passwordvault.util.maskPassword
import com.qiqiao.passwordvault.util.passwordStrength
import com.qiqiao.passwordvault.util.strengthLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import vault.CategoryRow
import vault.EntryInput
import vault.PasswordEntryRow
import vault.SettingsPatch
import vault.SortKey
import vault.desktop.DesktopVault

// ============================================================================
// 桌面 UI（PC 端 PRD M5，三栏布局版）：
//   左侧栏（230dp）：Logo + 分类导航（带计数）+ 新增大按钮 + 设置/锁定
//   中栏（340dp）：搜索 + 排序 + 条目列表 + 空状态（大图标 + 新增按钮）
//   右栏（自适应）：选中条目详情（大字号）
//  交互：全部导航/卡片/按钮带 hover 反馈；主操作用强调色填充按钮（白字）。
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
                if (unlocked && autoLockSec > 0 && ActivityMonitor.idleMillis() >= autoLockSec * 1000L) {
                    withContext(Dispatchers.IO) { vault.lock() }
                    DesktopClipboard.flushNow()
                    lockUi()
                }
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
            delay(250)
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
    // 全局提示条（凸起气泡）
    model.snackbar?.let { msg ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            NeuSurface(cornerRadius = 14.dp,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                Text(msg, color = neu.onSurface, fontSize = 13.sp)
            }
        }
        LaunchedEffect(msg) { delay(3000); if (model.snackbar == msg) model.snackbar = null }
    }
}

// ---------------- 品牌徽章 ----------------

@Composable
private fun BrandBadge(sizeDp: Int = 56) {
    val neu = LocalNeu.current
    NeuSurface(cornerRadius = sizeDp.dp,
        contentPadding = PaddingValues((sizeDp * 0.22f).dp)) {
        androidx.compose.material3.Icon(
            imageVector = fingerprintVector(neu.primary),
            contentDescription = "秘匣",
            modifier = Modifier.size((sizeDp * 0.55f).dp)
        )
    }
}

// ---------------- 解锁 / 首次初始化（F1/F2）：元素收拢进统一居中卡片 ----------------

@Composable
fun UnlockScreen(model: AppModel) = if (!model.initialized) InitScreen(model) else UnlockPanel(model)

@Composable
private fun UnlockCard(model: AppModel, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        NeuSurface(
            cornerRadius = 26.dp,
            modifier = Modifier.width(420.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 40.dp)
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandBadge()
                Spacer(Modifier.height(16.dp))
                Text("秘匣 · 密码保险库", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = LocalNeu.current.onSurface)
                Text("NEUMORPHISM VAULT", fontSize = 10.sp, letterSpacing = 3.sp,
                    color = LocalNeu.current.onSurfaceVariant)
                Spacer(Modifier.height(28.dp))
                content()
            }
        }
    }
}

@Composable
private fun InitScreen(model: AppModel) {
    val neu = LocalNeu.current
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    UnlockCard(model) {
        NeuField(p1, { p1 = it }, hint = "主密码", isPassword = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        NeuField(p2, { p2 = it }, hint = "确认主密码", isPassword = true, modifier = Modifier.fillMaxWidth(), onEnter = { model.initialize(p1) })
        if (model.unlockError != null) {
            Spacer(Modifier.height(8.dp))
            Text(model.unlockError!!, color = neu.error, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = if (model.busy) "正在初始化…" else "创建密码库",
            enabled = !model.busy && p1.length >= 8 && p1 == p2,
            modifier = Modifier.fillMaxWidth()
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
    UnlockCard(model) {
        NeuField(pw, { pw = it }, hint = "主密码", isPassword = true, modifier = Modifier.fillMaxWidth(), onEnter = { model.unlock(pw); pw = "" })
        if (model.unlockError != null) {
            Spacer(Modifier.height(8.dp))
            Text(model.unlockError!!, color = neu.error, fontSize = 13.sp)
        }
        if (model.lockoutSec > 0) {
            Spacer(Modifier.height(6.dp))
            Text("冷却中 ${model.lockoutSec}s", color = neu.onSurfaceVariant, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = when {
                model.busy -> "解锁中…"
                model.lockoutSec > 0 -> "冷却中 ${model.lockoutSec}s"
                else -> "解 锁"
            },
            enabled = !model.busy && model.lockoutSec <= 0,
            modifier = Modifier.fillMaxWidth()
        ) { model.unlock(pw); pw = "" }
    }
}

// ---------------- 主屏：三栏布局 ----------------

@Composable
fun MainScreen(model: AppModel) {
    var showSettings by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<PasswordEntryRow?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    val neu = LocalNeu.current

    Row(Modifier.fillMaxSize()) {
        // ========== 左侧栏：Logo + 分类 + 底部操作 ==========
        Column(
            modifier = Modifier
                .width(230.dp)
                .fillMaxHeight()
                .background(neu.sidebar)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 22.dp)) {
                NeuSurface(cornerRadius = 12.dp,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                    androidx.compose.material3.Icon(
                        imageVector = fingerprintVector(neu.primary),
                        contentDescription = "秘匣",
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text("秘匣", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
            }

            NavItem("全部条目", model.entries.size, model.categoryFilter == null) {
                model.filterByCategory(null)
            }
            model.categories.forEach { c ->
                NavItem(c.name, c.entryCount, model.categoryFilter == c.id) {
                    model.filterByCategory(c.id)
                }
            }

            Spacer(Modifier.weight(1f))
            PrimaryButton("＋ 新增条目", modifier = Modifier.fillMaxWidth()) {
                editTarget = null; showEdit = true
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NeuTextButton("⚙ 设置") { showSettings = true }
                NeuTextButton("🔒 锁定") { model.lockNow() }
            }
        }

        // 分隔
        Box(Modifier.width(2.dp).fillMaxHeight().background(neu.divider))

        // ========== 中栏：搜索 + 条目列表 ==========
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .padding(12.dp)
        ) {
            NeuField(model.search, { model.onSearchChange(it) },
                hint = "🔍 搜索名称或用户名", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SortMenu(model.sortBy) { model.setSort(it) }
                Text("共 ${model.entries.size} 条", fontSize = 12.sp, color = neu.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            if (model.entries.isEmpty()) {
                EmptyState(
                    hint = if (model.search.isBlank()) "还没有条目" else "无匹配结果",
                    onAdd = { editTarget = null; showEdit = true }
                )
            } else {
                LazyColumn {
                    items(model.entries, key = { it.id }) { e ->
                        EntryCard(
                            entry = e, selected = e.id == model.selectedId,
                            onClick = { model.select(e.id) }
                        )
                    }
                }
            }
        }

        // 分隔
        Box(Modifier.width(2.dp).fillMaxHeight().background(neu.divider))

        // ========== 右栏：详情 ==========
        DetailPane(
            model = model,
            onAdd = { editTarget = null; showEdit = true },
            onEdit = { editTarget = model.selectedDetail; showEdit = true },
            onDelete = { showDelete = true }
        )
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

// ---------------- 侧栏导航项（hover + 选中态） ----------------

@Composable
private fun NavItem(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val iso = remember { MutableInteractionSource() }
    val hovered by iso.collectIsHoveredAsState()
    val neu = LocalNeu.current
    val bg = when {
        selected -> neu.primary.copy(alpha = 0.15f)
        hovered -> neu.hoverBg
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(iso)
            .clickable(interactionSource = iso, indication = null, onClick = onClick)
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (selected) neu.primary else neu.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp
        )
        Text("$count", fontSize = 12.sp, color = neu.onSurfaceVariant)
    }
}

// ---------------- 条目卡片（hover 提亮） ----------------

@Composable
private fun EntryCard(entry: PasswordEntryRow, selected: Boolean, onClick: () -> Unit) {
    val iso = remember { MutableInteractionSource() }
    val hovered by iso.collectIsHoveredAsState()
    val neu = LocalNeu.current
    NeuSurface(
        cornerRadius = 18.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .hoverable(iso)
            .clickable(interactionSource = iso, indication = null, onClick = onClick),
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuSurface(cornerRadius = 12.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text(monogram(entry.name), color = neu.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.SemiBold, color = neu.onSurface, fontSize = 16.sp)
                Text(entry.username.ifBlank { "—" }, fontSize = 12.sp, color = neu.onSurfaceVariant)
            }
            Text(entry.categoryName ?: "未分类", fontSize = 11.sp, color = neu.onSurfaceVariant)
        }
    }
}

private fun monogram(name: String): String {
    val t = name.trim()
    return if (t.isEmpty()) "？" else t.substring(0, 1).uppercase()
}

// ---------------- 中栏空状态（大图标 + 文案 + 新增按钮） ----------------

@Composable
private fun EmptyState(hint: String, onAdd: () -> Unit) {
    val neu = LocalNeu.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🔑", fontSize = 40.sp)
        Spacer(Modifier.height(14.dp))
        Text(hint, fontSize = 15.sp, color = neu.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        PrimaryButton("＋ 新增条目", modifier = Modifier.width(190.dp), onClick = onAdd)
    }
}

// ---------------- 右栏：详情（自适应宽） ----------------

@Composable
private fun RowScope.DetailPane(
    model: AppModel,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val neu = LocalNeu.current
    val e = model.selectedDetail
    if (e == null) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔑", fontSize = 36.sp)
                Spacer(Modifier.height(10.dp))
                Text("选择左侧条目查看详情", fontSize = 15.sp, color = neu.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                PrimaryButton("＋ 新增条目", modifier = Modifier.width(190.dp), onClick = onAdd)
            }
        }
        return
    }
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = neu.onSurface,
                modifier = Modifier.weight(1f))
            NeuTextButton("编辑") { onEdit() }
            NeuTextButton("删除") { onDelete() }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${e.categoryName ?: "未分类"} · 更新于 ${formatTime(e.updatedAt)} · 强度 ${strengthLabel(passwordStrength(e.password.orEmpty()))}",
            fontSize = 12.sp, color = neu.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        FieldRow("账号", e.username, model)
        PasswordRow(e.password.orEmpty(), model)
        FieldRow("网站", e.website.orEmpty(), model)
        FieldRow("备注", e.notes.orEmpty(), model)
        e.extras.forEach { f -> FieldRow(f.label, f.value, model) }
        Spacer(Modifier.height(10.dp))
        Text("创建于 ${formatTime(e.createdAt)}", fontSize = 12.sp, color = neu.onSurfaceVariant)
    }
}

@Composable
private fun FieldRow(label: String, value: String, model: AppModel) {
    val neu = LocalNeu.current
    Column(modifier = Modifier.padding(vertical = 9.dp)) {
        Text(label, fontSize = 12.sp, color = neu.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value.ifBlank { "—" },
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = neu.onSurface,
                fontSize = 15.sp
            )
            NeuIconButton(onClick = { model.copySecret(value, label) }, sizeDp = 36.dp) {
                Text("⧉", color = neu.accent, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun PasswordRow(password: String, model: AppModel) {
    val neu = LocalNeu.current
    var revealed by remember(password) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 9.dp)) {
        Text("密码", fontSize = 12.sp, color = neu.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (revealed) password else maskPassword(),
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = if (revealed) neu.primary else neu.onSurface,
                fontSize = 15.sp
            )
            NeuIconButton(onClick = { revealed = !revealed }, sizeDp = 36.dp) {
                Text(if (revealed) "🙈" else "👁", fontSize = 14.sp)
            }
            NeuIconButton(onClick = { model.copySecret(password, "密码") }, sizeDp = 36.dp) {
                Text("⧉", color = neu.accent, fontSize = 15.sp)
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
            PrimaryButton("保存", modifier = Modifier.width(120.dp),
                enabled = name.isNotBlank()) {
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
            Box(Modifier.fillMaxWidth().height(2.dp).background(neu.divider))
            Spacer(Modifier.height(14.dp))
            Text("修改主密码", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
            Spacer(Modifier.height(8.dp))
            NeuButton("修改主密码…") { msg = null; showChangePw = true }

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(neu.divider))
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
