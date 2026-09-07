package vault.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import vault.desktop.DesktopVault
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import vault.CategoryRow
import vault.EntryInput
import vault.ExtraField
import vault.PasswordEntryRow
import vault.SettingsPatch
import vault.SortKey
import com.qiqiao.passwordvault.util.ChangeMasterError
import com.qiqiao.passwordvault.util.generatePassword
import com.qiqiao.passwordvault.util.formatTime
import com.qiqiao.passwordvault.util.maskPassword
import com.qiqiao.passwordvault.util.passwordStrength
import com.qiqiao.passwordvault.util.strengthLabel
import com.qiqiao.passwordvault.util.validateChangeMaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ============================================================================
// 桌面 UI（PC 端 PRD M5 MVP，Material3 标准样式；新拟态重绘为 P2）。
//  屏：解锁/首初始化（F1/F2）→ 主屏（列表+详情双栏，F3/F4）→ 编辑弹窗（F10 生成器）/
//  设置弹窗（F8/F9 参数 + 改主密码 + 备份导出/导入 F5/F6）。
//  安全行为：列表掩码、点眼临时显形、复制延时清除+最小化/退出冲刷、空闲自动锁、
//  改主密码校验复用 core 的 ChangeMaster（同一份逻辑）。
// ============================================================================

class AppModel(private val vault: DesktopVault) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
        scope.launch(Dispatchers.IO) {
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
                // 解锁页冷却倒计时（SEC-8）
                if (!unlocked && lockoutSec > 0) {
                    lockoutSec = (vault.lockoutRemainingMs() / 1000L).toInt().coerceAtLeast(0)
                }
            }
        }
    }

    fun stop() { scope.cancel() }

    fun lockNow() {
        scope.launch(Dispatchers.IO) { vault.lock() }
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
        scope.launch(Dispatchers.IO) {
            entries = vault.listEntries(search.takeIf { it.isNotBlank() }, sortBy, categoryFilter)
            categories = vault.listCategories()
        }
    }

    fun onSearchChange(text: String) {
        search = text
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(250) // 防抖，与 Android 同款
            withContext(Dispatchers.IO) {
                entries = vault.listEntries(search.takeIf { it.isNotBlank() }, sortBy, categoryFilter)
            }
        }
    }

    fun setSort(key: SortKey) { sortBy = key; refreshData() }
    fun filterByCategory(id: Long?) { categoryFilter = id; refreshData() }

    fun select(id: Long) {
        selectedId = id
        scope.launch(Dispatchers.IO) { selectedDetail = vault.getEntry(id) }
    }

    fun unlock(password: String) {
        if (busy || lockoutSec > 0) return
        busy = true; unlockError = null
        scope.launch(Dispatchers.IO) {
            val ok = vault.unlockWithPassword(password)
            withContext(Dispatchers.Main) {
                busy = false
                if (ok) { unlocked = true; refreshData() }
                else {
                    lockoutSec = (vault.lockoutRemainingMs() / 1000L).toInt()
                    unlockError = if (lockoutSec > 0) "尝试次数过多，请 ${lockoutSec}s 后重试" else "主密码错误"
                }
            }
        }
    }

    fun initialize(password: String) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val ok = vault.initializeMasterPassword(password)
            withContext(Dispatchers.Main) {
                busy = false
                if (ok) { initialized = true; unlocked = true; refreshData() }
                else unlockError = "初始化失败（可能已初始化过）"
            }
        }
    }

    fun saveEntry(id: Long?, input: EntryInput, onDone: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val ok = try {
                if (id == null) { vault.createEntry(input) > 0; true } else vault.updateEntry(id, input)
            } catch (e: Exception) { false }
            withContext(Dispatchers.Main) { if (ok) refreshData(); onDone(ok) }
        }
    }

    fun deleteEntry(id: Long, onDone: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val ok = vault.deleteEntry(id)
            withContext(Dispatchers.Main) {
                if (ok) { selectedId = null; selectedDetail = null; refreshData() }
                onDone(ok)
            }
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
        val err = when (validateChangeMaster(old, new, confirm)) {
            ChangeMasterError.OLD_BLANK, ChangeMasterError.NEW_BLANK -> "密码不能为空"
            ChangeMasterError.NEW_TOO_SHORT -> "新主密码至少 8 位"
            ChangeMasterError.MISMATCH -> "两次输入的新密码不一致"
            null -> null
        }
        if (err != null) { onResult(err); return }
        scope.launch(Dispatchers.IO) {
            val ok = vault.changeMasterPassword(old, new)
            withContext(Dispatchers.Main) { onResult(if (ok) null else "旧主密码错误") }
        }
    }

    fun exportBackup(path: String, filePw: String, same: Boolean, onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val msg = try {
                val bytes = vault.exportVault(filePw, same)
                File(path).writeBytes(bytes)
                "已导出 ${bytes.size} 字节 → $path"
            } catch (e: vault.LockedException) { "会话已锁定" }
            catch (e: vault.WrongPasswordException) { "主密码错误" }
            catch (e: Exception) { "导出失败：${e.message}" }
            withContext(Dispatchers.Main) { onResult(msg) }
        }
    }

    fun importBackup(path: String, filePw: String, onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val msg = try {
                val r = vault.importVault(File(path).readBytes(), filePw)
                refreshData()
                "导入完成：分类 +${r.categoriesAdded}/合并${r.categoriesMerged}，" +
                    "条目 +${r.entriesAdded}/更新${r.entriesUpdated}/跳过${r.entriesSkipped}" +
                    "（合并≠同步，删除不会传播）"
            } catch (e: vault.WrongPasswordException) { "导入失败：文件密码错误" }
            catch (e: Exception) { "导入失败：${e.message}" }
            withContext(Dispatchers.Main) { onResult(msg) }
        }
    }

    fun saveSettings(lock: Int, clip: Int, onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val ok = vault.updateSettings(SettingsPatch(autoLockTimeoutSec = lock, clipboardClearDelaySec = clip))
            withContext(Dispatchers.Main) {
                if (ok) { autoLockSec = lock; clipboardSec = clip; onResult("已保存") } else onResult("保存失败")
            }
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
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(model.snackbar) {
        model.snackbar?.let { snackbarHost.showSnackbar(it, withDismissAction = false) }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!model.unlocked) UnlockScreen(model) else MainScreen(model)
        SnackbarHost(hostState = snackbarHost, modifier = Modifier.padding(12.dp))
    }
}

// ---------------- 解锁 / 首次初始化（F1/F2） ----------------

@Composable
fun UnlockScreen(model: AppModel) = if (!model.initialized) InitScreen(model) else UnlockPanel(model)

@Composable
private fun InitScreen(model: AppModel) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("秘匣 · 密码保险库", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("首次使用：设置主密码（≥8 位，纯离线无法找回，请牢记）",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(p1, { p1 = it }, label = { Text("主密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.width(360.dp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(p2, { p2 = it }, label = { Text("确认主密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.width(360.dp))
        if (model.unlockError != null) {
            Text(model.unlockError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                when {
                    p1.length < 8 -> model.unlockError = "主密码至少 8 位"
                    p1 != p2 -> model.unlockError = "两次输入不一致"
                    else -> model.initialize(p1)
                }
            },
            enabled = !model.busy
        ) { Text(if (model.busy) "正在初始化…" else "创建密码库") }
    }
}

@Composable
private fun UnlockPanel(model: AppModel) {
    var pw by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("秘匣 · 密码保险库", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("输入主密码解锁", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(pw, { pw = it }, label = { Text("主密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.width(360.dp))
        if (model.unlockError != null) {
            Text(model.unlockError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { model.unlock(pw); pw = "" }, enabled = !model.busy && model.lockoutSec <= 0) {
            Text(when {
                model.busy -> "解锁中…"
                model.lockoutSec > 0 -> "冷却中 ${model.lockoutSec}s"
                else -> "解锁"
            })
        }
    }
}

// ---------------- 主屏（F3/F4，列表 + 详情双栏） ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(model: AppModel) {
    var showSettings by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<PasswordEntryRow?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(model.snackbar) {
        model.snackbar?.let { snackbarHost.showSnackbar(it, withDismissAction = false) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            Surface(shadowElevation = 4.dp) {
                TopAppBar(
                    title = { Text("秘匣", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { model.lockNow() }) {
                            Icon(Icons.Filled.Lock, contentDescription = "立即锁定")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editTarget = null; showEdit = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新增条目")
            }
        }
    ) { pad ->
        Row(modifier = Modifier.padding(pad).fillMaxSize()) {
            // 左：搜索 + 过滤 + 列表
            Column(modifier = Modifier.width(400.dp).fillMaxHeight().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(model.search, { model.onSearchChange(it) },
                        placeholder = { Text("搜索名称或用户名") }, singleLine = true,
                        modifier = Modifier.weight(1f).height(52.dp),
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) })
                    SortMenu(model.sortBy) { model.setSort(it) }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                    CategoryMenu(model) { model.filterByCategory(it) }
                    Spacer(Modifier.weight(1f))
                    Text("共 ${model.entries.size} 条",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                if (model.entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (model.search.isBlank()) "还没有条目，点右下角 + 新增" else "无匹配结果",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn {
                        items(model.entries, key = { it.id }) { e ->
                            EntryCard(
                                name = e.name, username = e.username.orEmpty(),
                                category = e.categoryName ?: "未分类",
                                selected = e.id == model.selectedId,
                                onClick = { model.select(e.id) }
                            )
                        }
                    }
                }
            }
            // 分隔线
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            // 右：详情
            DetailPane(
                model = model,
                onEdit = { editTarget = model.selectedDetail; showEdit = true },
                onDelete = { showDelete = true }
            )
        }
    }

    if (showEdit) EditDialog(model, editTarget) { showEdit = false }
    if (showSettings) SettingsDialog(model) { showSettings = false }
    if (showDelete) {
        val name = model.selectedDetail?.name ?: ""
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除条目") },
            text = { Text("删除「$name」？（软删除，可由含该条目的备份重新导入恢复）") },
            confirmButton = {
                TextButton(onClick = {
                    model.selectedDetail?.let { e -> model.deleteEntry(e.id) { showDelete = false } }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ showDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun EntryCard(name: String, username: String, category: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).background(
                    MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)
                ), contentAlignment = Alignment.Center
            ) {
                Text(monogram(name), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(username.ifBlank { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(category, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val e = model.selectedDetail
    if (e == null) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text("选择左侧条目查看详情", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "编辑") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${e.categoryName ?: "未分类"} · 更新于 ${formatTime(e.updatedAt)} · 强度 ${strengthLabel(passwordStrength(e.password.orEmpty()))}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        FieldRow("账号", e.username, model)
        PasswordRow(e.password.orEmpty(), model)
        FieldRow("网站", e.website.orEmpty(), model)
        FieldRow("备注", e.notes.orEmpty(), model)
        e.extras.forEach { f -> FieldRow(f.label, f.value, model) }
        Spacer(Modifier.height(8.dp))
        Text("创建于 ${formatTime(e.createdAt)}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FieldRow(label: String, value: String, model: AppModel) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value.ifBlank { "—" }, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
            IconButton(onClick = { model.copySecret(value, label) }, enabled = value.isNotBlank()) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制$label")
            }
        }
    }
}

@Composable
private fun PasswordRow(password: String, model: AppModel) {
    var revealed by remember(password) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("密码", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (revealed) password else maskPassword(),
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = if (revealed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "隐藏密码" else "显示密码"
                )
            }
            IconButton(onClick = { model.copySecret(password, "密码") }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制密码")
            }
        }
    }
}

// ---------------- 下拉菜单（排序 / 分类过滤） ----------------

@Composable
private fun SortMenu(current: SortKey, onSelect: (SortKey) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(SortOptions.first { it.first == current }.second)
        }
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
    val label = if (model.categoryFilter == null) "全部分类"
    else model.categories.firstOrNull { it.id == model.categoryFilter }?.name ?: "全部分类"
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
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
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var website by remember { mutableStateOf(initial?.website ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var revealed by remember { mutableStateOf(false) }
    var catId by remember { mutableStateOf(initial?.categoryId) }
    var showCat by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(520.dp)) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text(if (initial == null) "新增条目" else "编辑条目",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(name, { name = it }, label = { Text("平台 *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(username, { username = it }, label = { Text("账号") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                        visualTransformation = if (revealed) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealed) "隐藏密码" else "显示密码"
                        )
                    }
                    TextButton(onClick = {
                        password = generatePassword(20, com.qiqiao.passwordvault.util.CharsetFlags())
                    }) { Text("生成") }
                }
                Text("强度：${strengthLabel(passwordStrength(password))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(website, { website = it }, label = { Text("网站") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(notes, { notes = it }, label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth().height(90.dp))
                Spacer(Modifier.height(10.dp))
                Box {
                    TextButton(onClick = { showCat = true }) {
                        Text("分类：${model.categories.firstOrNull { it.id == catId }?.name ?: "未分类"}")
                    }
                    DropdownMenu(showCat, { showCat = false }) {
                        DropdownMenuItem(text = { Text("未分类") },
                            onClick = { catId = null; showCat = false })
                        model.categories.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) },
                                onClick = { catId = c.id; showCat = false })
                        }
                    }
                }
                if (initial != null && initial.extras.isNotEmpty()) {
                    Text("自定义词条 ${initial.extras.size} 条将保留（词条编辑 P2）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // 编辑时保留已有自定义词条（P2 才开放词条编辑），防保存误清
                            val input = EntryInput(
                                name.trim(), username.trim(), password, website.trim(), notes,
                                catId, initial?.extras ?: emptyList()
                            )
                            model.saveEntry(initial?.id, input) { ok -> if (ok) onDismiss() }
                        },
                        enabled = name.isNotBlank()
                    ) { Text("保存") }
                }
            }
        }
    }
}

// ---------------- 设置弹窗（F8/F9 参数 + 改主密码 + 备份 F5/F6） ----------------

@Composable
fun SettingsDialog(model: AppModel, onDismiss: () -> Unit) {
    var lockSec by remember { mutableStateOf(model.autoLockSec.toString()) }
    var clipSec by remember { mutableStateOf(model.clipboardSec.toString()) }
    var msg by remember { mutableStateOf<String?>(null) }

    var showChangePw by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }


    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(520.dp)) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(lockSec, { lockSec = it }, label = { Text("自动锁超时（秒，0=不超时）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(clipSec, { clipSec = it },
                    label = { Text("剪贴板清除延时（秒，0=永不）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val l = lockSec.toIntOrNull(); val c = clipSec.toIntOrNull()
                    if (l == null || c == null || l < 0 || c < 0) msg = "请输入非负整数"
                    else model.saveSettings(l, c) { msg = it }
                }) { Text("保存安全设置") }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text("修改主密码", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { msg = null; showChangePw = true }) { Text("修改主密码…") }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text("备份（与 Android 版 .vault 双向兼容）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { msg = null; showExport = true }) { Text("导出 .vault…") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { msg = null; showImport = true }) { Text("导入 .vault…") }
                }

                msg?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }

    if (showChangePw) ChangePwDialog(model, onDone = { m -> msg = m; showChangePw = false })
    if (showExport) ExportDialog(model, onDone = { m -> msg = m; showExport = false })
    if (showImport) ImportDialog(model, onDone = { m -> msg = m; showImport = false })
}

@Composable
private fun ChangePwDialog(model: AppModel, onDone: (String?) -> Unit) {
    var old by remember { mutableStateOf("") }
    var n1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { onDone(null) }) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(460.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("修改主密码", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(old, { old = it }, label = { Text("旧主密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(n1, { n1 = it }, label = { Text("新主密码（≥8 位）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(n2, { n2 = it }, label = { Text("确认新主密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ onDone(null) }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { model.changeMaster(old, n1, n2) { onDone(it) } },
                        enabled = old.isNotBlank() && n1.isNotBlank() && n2.isNotBlank()) { Text("修改") }
                }
            }
        }
    }
}

@Composable
private fun ExportDialog(model: AppModel, onDone: (String?) -> Unit) {
    var same by remember { mutableStateOf(true) }
    var master by remember { mutableStateOf("") }
    var exportPw by remember { mutableStateOf("") }

    Dialog(onDismissRequest = { onDone(null) }) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(480.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("导出加密备份（.vault）", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(same, { same = true })
                    Text("主密码同源", modifier = Modifier.clickable { same = true }.padding(end = 16.dp))
                    RadioButton(!same, { same = false })
                    Text("独立导出密码", modifier = Modifier.clickable { same = false })
                }
                if (same) {
                    // 同源路径需重输主密码（会话仅持 DEK，不存主密码——与 Android 导出一致）
                    OutlinedTextField(master, { master = it }, label = { Text("本库主密码") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    OutlinedTextField(exportPw, { exportPw = it },
                        label = { Text("独立导出密码（≥8 位）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (same) "导入端需输入本库主密码" else "导入端需输入此独立导出密码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ onDone(null) }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val filePw = if (same) master else exportPw
                        if (!same && exportPw.length < 8) { onDone("独立导出密码至少 8 位"); return@Button }
                        if (filePw.isBlank()) { onDone("请输入密码"); return@Button }
                        val target = awtSaveDialog(null, "保存加密备份", "offline-vault.vault")
                        if (target == null) { onDone(null); return@Button }
                        model.exportBackup(target.absolutePath, filePw, same) { onDone(it) }
                    }) { Text("选择位置并导出") }
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(model: AppModel, onDone: (String?) -> Unit) {
    var filePw by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { onDone(null) }) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(480.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("导入加密备份（.vault）", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("按（名称+账号+分类）合并、更新时间取新。删除不会传播，导入≠同步。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(filePw, { filePw = it }, label = { Text("该备份文件的密码") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ onDone(null) }) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val src = awtOpenDialog(null, "选择 .vault 备份文件")
                        if (src == null) { onDone(null); return@Button }
                        model.importBackup(src.absolutePath, filePw) { onDone(it) }
                    }) { Text("选择文件并导入") }
                }
            }
        }
    }
}
