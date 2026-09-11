package vault.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import vault.ExtraField
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

    /** 配色主题（0 浅色 / 1 深色 / 2 莫兰迪），Preferences 持久化，不动 .vault 格式 */
    var themeMode by mutableStateOf(
        java.util.prefs.Preferences.userRoot().node("offline-vault-desktop").getInt("themeMode", 0)
    )
    fun setTheme(mode: Int) {
        themeMode = mode
        java.util.prefs.Preferences.userRoot().node("offline-vault-desktop").putInt("themeMode", mode)
    }
    /** 自定义头像图片路径（空=用品牌指纹兜底），Preferences 持久化，仅本机 */
    var avatarPath by mutableStateOf(
        java.util.prefs.Preferences.userRoot().node("offline-vault-desktop").get("avatarPath", "")
    )
    fun setAvatar(path: String) {
        avatarPath = path
        java.util.prefs.Preferences.userRoot().node("offline-vault-desktop").put("avatarPath", path)
    }
    /** 点击头像：选图片文件设为自定义头像（存路径，仅本机）。 */
    fun pickAvatar() {
        val f = awtOpenDialog(null, "选择头像图片") ?: return
        setAvatar(f.absolutePath)
    }
    /** 恢复默认头像（清除自定义图片）。 */
    fun resetAvatar() = setAvatar("")
    var categoryFilter by mutableStateOf<Long?>(null)
    var totalActive by mutableStateOf(0)   // 全部有效条目数（数据统计用，独立于当前筛选）

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
            totalActive = vault.listEntries().size   // 无筛选全量，供数据统计
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

    // ---- 分类管理（增删改 + 上下重排；vault 同步阻塞，落 scope 离 EDT）----
    fun createCategory(name: String) {
        val n = name.trim()
        if (n.isEmpty()) { note("分类名不能为空"); return }
        scope.launch {
            if (vault.createCategory(n, categories.size) < 0) note("已存在同名分类") else refreshData()
        }
    }
    fun renameCategory(id: Long, name: String) {
        val n = name.trim()
        if (n.isEmpty()) { note("分类名不能为空"); return }
        scope.launch {
            if (!vault.updateCategory(id, name = n)) note("重命名失败（同名或不存在）") else refreshData()
        }
    }
    fun deleteCategory(id: Long) {
        scope.launch {
            if (!vault.deleteCategory(id)) note("该分类不可删除（非空或为「其他」）")
            else { if (categoryFilter == id) categoryFilter = null; refreshData() }
        }
    }
    fun moveCategory(id: Long, up: Boolean) {
        val list = categories.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j < 0 || j >= list.size) return
        val t = list[i]; list[i] = list[j]; list[j] = t
        scope.launch { vault.reorderCategories(list.map { it.id }); refreshData() }
    }

    // ---- 批量录入：分类按名匹配（缺失则末尾新建），逐条写入；回调 (成功, 失败) ----
    fun importBatch(entries: List<vault.ParsedEntry>, onDone: (ok: Int, failed: Int) -> Unit) {
        scope.launch {
            val cats = vault.listCategories()
            val catMap = cats.associate { it.name.trim() to it.id }.toMutableMap()
            var nextSort = (cats.maxOfOrNull { it.sortOrder } ?: -1) + 1
            var ok = 0; var failed = 0
            for (e in entries) {
                if (!e.include) continue
                if (e.name.isBlank() && e.username.isBlank() && e.password.isBlank() && e.website.isBlank()) continue
                var catId: Long? = null
                val ct = e.category.trim()
                if (ct.isNotEmpty()) {
                    catId = catMap[ct]
                    if (catId == null) {
                        val id = vault.createCategory(ct, nextSort)
                        if (id > 0) { catMap[ct] = id; nextSort++; catId = id }
                    }
                }
                val id = vault.createEntry(
                    EntryInput(e.name.trim(), e.username.trim(), e.password, e.website.trim(), e.notes.trim(), catId)
                )
                if (id > 0) ok++ else failed++
            }
            refreshData()
            onDone(ok, failed)
        }
    }

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
    SortKey.NAME_ASC to "名称升序",
    SortKey.NAME_DESC to "名称降序",
    SortKey.CATEGORY_ASC to "分类升序",
    SortKey.CATEGORY_DESC to "分类降序",
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
            elevation = 4.dp,
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
    var showStats by remember { mutableStateOf(false) }
    val neu = LocalNeu.current

    Row(Modifier.fillMaxSize()) {
        // ========== 左侧栏：Logo + 可滚动分类导航 + 固定底部操作 ==========
        Column(
            modifier = Modifier
                .width(230.dp)
                .fillMaxHeight()
                .background(neu.bg)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 22.dp)) {
                // 左：自定义头像（点击换图；未设置时品牌指纹兜底）
                AvatarBadge(model)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("秘匣 · 密码保险库", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
                }
                // 右：17° logo，点击 → 数据统计
                Box(
                    modifier = Modifier.clip(CircleShape)
                        .clickable { showStats = true }
                ) {
                    NeuSurface(cornerRadius = 18.dp, contentPadding = PaddingValues(0.dp)) {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Text("17°", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = neu.primary)
                        }
                    }
                }
            }

            // 导航区：独立滚动，分类再多也不会把底部三键挤下去（底部位置固定）
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                NavItem("全部条目", model.entries.size, model.categoryFilter == null) {
                    model.filterByCategory(null)
                }
                model.categories.forEach { c ->
                    NavItem(c.name, c.entryCount, model.categoryFilter == c.id) {
                        model.filterByCategory(c.id)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // 固定底部：设置 / + 新增 / 锁定（一行排开）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NeuTextButton("设置", modifier = Modifier.weight(1f)) { showSettings = true }
                NeuButton("+", modifier = Modifier.width(52.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
                    editTarget = null; showEdit = true
                }
                NeuTextButton("锁定", modifier = Modifier.weight(1f)) { model.lockNow() }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NeuField(model.search, { model.onSearchChange(it) },
                    hint = "搜索名称或用户名", modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                SortMenu(model.sortBy) { model.setSort(it) }
            }
            Spacer(Modifier.height(10.dp))
            Text("共 ${model.entries.size} 条", fontSize = 12.sp, color = neu.onSurfaceVariant)
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
    if (showStats) StatisticsDialog(model) { showStats = false }
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

// ---------------- 侧栏头像 + 数据统计 ----------------

/** 侧栏头像：自定义图片（圆形裁剪，点击换图）；未设置时品牌指纹兜底。 */
@Composable
private fun AvatarBadge(model: AppModel) {
    val neu = LocalNeu.current
    val path = model.avatarPath
    val painter: BitmapPainter? = remember(path) {
        if (path.isNotBlank()) {
            try {
                val img = javax.imageio.ImageIO.read(java.io.File(path))
                if (img != null) BitmapPainter(img.toComposeImageBitmap()) else null
            } catch (e: Exception) { null }
        } else null
    }
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable { model.pickAvatar() },
        contentAlignment = Alignment.Center
    ) {
        if (painter != null) {
            Image(painter, contentDescription = "头像", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(neu.sidebar), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(fingerprintVector(neu.primary), "头像",
                    modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** 数据统计（点击 17° logo）：条目总数 / 分类数 / 未分类 / 各分类条数。 */
@Composable
private fun StatisticsDialog(model: AppModel, onDismiss: () -> Unit) {
    val neu = LocalNeu.current
    val uncategorized = (model.totalActive - model.categories.sumOf { it.entryCount }).coerceAtLeast(0)
    NeuDialogShell(width = 440.dp, onDismiss = onDismiss) {
        Text("数据统计", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(16.dp))
        StatRow("条目总数", model.totalActive, neu)
        StatRow("分类数量", model.categories.size, neu)
        StatRow("未分类条目", uncategorized, neu)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(neu.divider))
        Spacer(Modifier.height(14.dp))
        Text("各分类条数", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            model.categories.forEach { StatRow(it.name, it.entryCount, neu) }
            if (model.categories.isEmpty()) Text("暂无分类", fontSize = 13.sp, color = neu.onSurfaceVariant)
        }
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeuTextButton("关闭") { onDismiss() }
        }
    }
}

@Composable
private fun StatRow(label: String, value: Int, neu: NeuColors) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = neu.onSurface)
        Text("$value", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = neu.primary)
    }
}

// ---------------- 侧栏导航项（hover + 选中态） ----------------

@Composable
private fun NavItem(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val iso = remember { MutableInteractionSource() }
    val hovered by iso.collectIsHoveredAsState()
    val neu = LocalNeu.current
    val bg = when {
        selected -> neu.surface
        hovered -> neu.hoverBg
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.shadow(5.dp, RoundedCornerShape(12.dp)) else Modifier)
            .hoverable(iso)
            .clickable(interactionSource = iso, indication = null, onClick = onClick)
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp),
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
    val cardElev by androidx.compose.animation.core.animateDpAsState(
        if (hovered) 8.dp else 4.dp, label = "cardElev"
    )
    NeuSurface(
        cornerRadius = 18.dp,
        elevation = cardElev,
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
        Text(hint, fontSize = 15.sp, color = neu.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        PrimaryButton("新增条目", modifier = Modifier.width(190.dp), onClick = onAdd)
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
                Text("选择左侧条目查看详情", fontSize = 15.sp, color = neu.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                PrimaryButton("新增条目", modifier = Modifier.width(190.dp), onClick = onAdd)
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
            NeuTextButton("复制") { model.copySecret(value, label) }
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
            NeuTextButton(if (revealed) "隐藏" else "显示") { revealed = !revealed }
            Spacer(Modifier.width(6.dp))
            NeuTextButton("复制") { model.copySecret(password, "密码") }
        }
    }
}

// ---------------- 下拉菜单（排序 / 分类过滤） ----------------

@Composable
private fun SortMenu(current: SortKey, onSelect: (SortKey) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val neu = LocalNeu.current
    Box {
        NeuTextButton("▾ ${SortOptions.first { it.first == current }.second}") { open = !open }
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

    // 自定义词条值（邮箱 + 自定义；网站/备注走独立列，不在此）。编辑态从条目 extras 载入。
    val extras = remember { mutableStateListOf<ExtraField>().apply { initial?.extras?.forEach { add(it) } } }
    // 全局模板（有序），驱动渲染哪些词条行；每次变更即持久化到 DesktopFieldTemplate。
    val template = remember { mutableStateListOf<String>().apply { addAll(DesktopFieldTemplate.load()) } }
    var renamingLabel by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }

    fun persistTemplate() = DesktopFieldTemplate.save(template.toList())
    fun valueOf(label: String): String = extras.firstOrNull { it.label == label }?.value ?: ""
    fun setValue(label: String, v: String) {
        val i = extras.indexOfFirst { it.label == label }
        when {
            v.isBlank() -> if (i >= 0) extras.removeAt(i)
            i >= 0 -> extras[i] = ExtraField(label, v)
            else -> extras.add(ExtraField(label, v))
        }
    }
    // 行序：备注永远排最后（对齐移动端 rowOrder）。
    val orderedLabels = template.filter { it != "备注" } + template.filter { it == "备注" }

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
            NeuTextButton(if (revealed) "隐藏" else "显示") { revealed = !revealed }
            Spacer(Modifier.width(6.dp))
            NeuButton("生成") { password = generatePassword(20, com.qiqiao.passwordvault.util.CharsetFlags()) }
        }
        Spacer(Modifier.height(4.dp))
        Text("强度：${strengthLabel(passwordStrength(password))}",
            fontSize = 11.sp, color = neu.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        // 模板驱动的词条行：网站/备注映射独立列；其余（邮箱/自定义）为可增删改的 extras 行
        orderedLabels.forEach { label ->
            when (label) {
                "网站" -> {
                    NeuField(website, { website = it }, hint = "网站", modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }
                "备注" -> {
                    NeuField(notes, { notes = it }, hint = "备注", singleLine = false, minLines = 3,
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }
                else -> {
                    ExtraFieldRow(
                        label = label,
                        value = valueOf(label),
                        renaming = renamingLabel == label,
                        renameText = renameText,
                        onRenameText = { renameText = it },
                        onBeginRename = { renamingLabel = label; renameText = label },
                        onCommitRename = {
                            val nl = renameText.trim()
                            if (nl.isNotBlank() && nl != label && !template.contains(nl)) {
                                val idx = template.indexOf(label)
                                if (idx >= 0) template[idx] = nl
                                val ev = valueOf(label)
                                setValue(label, ""); setValue(nl, ev)
                                persistTemplate()
                            }
                            renamingLabel = null
                        },
                        onCancelRename = { renamingLabel = null },
                        onValue = { setValue(label, it) },
                        onRemove = { template.remove(label); persistTemplate(); setValue(label, "") }
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // 添加词条（写入全局模板，所有条目同步出现）
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuTextButton("添加词条") { showAdd = !showAdd; newLabel = "" }
            if (showAdd) {
                Spacer(Modifier.width(8.dp))
                NeuField(newLabel, { newLabel = it }, hint = "新词条名", modifier = Modifier.weight(1f),
                    onEnter = {
                        val nl = newLabel.trim()
                        if (nl.isNotBlank() && !template.contains(nl)) { template.add(nl); persistTemplate(); newLabel = ""; showAdd = false }
                    })
                Spacer(Modifier.width(8.dp))
                NeuButton("确定", contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    val nl = newLabel.trim()
                    if (nl.isNotBlank() && !template.contains(nl)) { template.add(nl); persistTemplate(); newLabel = ""; showAdd = false }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

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
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeuTextButton("取消") { onDismiss() }
            Spacer(Modifier.width(10.dp))
            PrimaryButton("保存", modifier = Modifier.width(120.dp),
                enabled = name.isNotBlank()) {
                // 仅保留非空 label+value 的自定义词条进加密 extras（网站/备注走独立列）
                val kept = extras.filter { it.label.isNotBlank() && it.value.isNotBlank() }
                val input = EntryInput(
                    name.trim(), username.trim(), password, website.trim(), notes, catId, kept
                )
                model.saveEntry(initial?.id, input) { ok -> if (ok) onDismiss() }
            }
        }
    }
}

/** 自定义词条行：[词条名 ✎ | 值输入 | ✕]；✎ 就地改名（同步全局模板），✕ 删除（隐藏行并从本条移除）。 */
@Composable
private fun ExtraFieldRow(
    label: String,
    value: String,
    renaming: Boolean,
    renameText: String,
    onRenameText: (String) -> Unit,
    onBeginRename: () -> Unit,
    onCommitRename: () -> Unit,
    onCancelRename: () -> Unit,
    onValue: (String) -> Unit,
    onRemove: () -> Unit
) {
    val neu = LocalNeu.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (renaming) {
            NeuField(renameText, onRenameText, hint = "词条名", modifier = Modifier.width(150.dp),
                onEnter = onCommitRename)
            Spacer(Modifier.width(6.dp))
            NeuButton("确定", contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) { onCommitRename() }
            Spacer(Modifier.width(6.dp))
            NeuTextButton("取消") { onCancelRename() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(150.dp)) {
                Text(label, fontSize = 14.sp, color = neu.onSurface, fontWeight = FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.weight(1f))
                Text("改名", fontSize = 12.sp, color = neu.primary,
                    modifier = Modifier.clickable { onBeginRename() })
            }
        }
        Spacer(Modifier.width(8.dp))
        NeuField(value, onValue, hint = label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Text("删除", fontSize = 12.sp, color = neu.error,
            modifier = Modifier.clickable { onRemove() })
    }
}

// ---------------- 批量录入弹窗（粘贴自由文本 → 解析 → 预览可编辑+勾选 → 导入，分类自动匹配） ----------------

@Composable
fun BatchImportDialog(model: AppModel, onDismiss: () -> Unit) {
    val neu = LocalNeu.current
    var raw by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<List<vault.ParsedEntry>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }

    fun setField(i: Int, transform: (vault.ParsedEntry) -> vault.ParsedEntry) {
        parsed = parsed.toMutableList().also { it[i] = transform(it[i]) }
    }

    NeuDialogShell(width = 640.dp, onDismiss = onDismiss) {
        Text("批量录入", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(4.dp))
        Text("粘贴账号笔记（每条以空行分隔，支持 平台/账号/密码/网站/分类/备注 等中英文标签，: ： = 分隔）→ 解析 → 校对 → 导入。",
            fontSize = 12.sp, color = neu.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        NeuField(raw, { raw = it }, hint = "在此粘贴文本…", singleLine = false, minLines = 5,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeuButton("解析") { parsed = vault.NoteParser.parse(raw) }
            if (parsed.isNotEmpty()) {
                NeuTextButton("清空") { parsed = emptyList(); raw = "" }
            }
        }

        if (parsed.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(neu.divider))
            Spacer(Modifier.height(12.dp))
            Text("解析出 ${parsed.count { it.include }} / ${parsed.size} 条（取消勾选可排除）",
                fontSize = 12.sp, color = neu.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                parsed.forEachIndexed { i, e ->
                    NeuSurface(cornerRadius = 12.dp, contentPadding = PaddingValues(12.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = e.include,
                                    onCheckedChange = { setField(i) { it.copy(include = !it.include) } },
                                    colors = CheckboxDefaults.colors(checkedColor = neu.primary)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("条目 ${i + 1}", fontSize = 12.sp, color = neu.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(8.dp))
                            NeuField(e.name, { v -> setField(i) { it.copy(name = v) } }, hint = "平台 *", modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NeuField(e.username, { v -> setField(i) { it.copy(username = v) } }, hint = "账号", modifier = Modifier.weight(1f))
                                NeuField(e.password, { v -> setField(i) { it.copy(password = v) } }, hint = "密码", modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NeuField(e.website, { v -> setField(i) { it.copy(website = v) } }, hint = "网站", modifier = Modifier.weight(1f))
                                NeuField(e.category, { v -> setField(i) { it.copy(category = v) } }, hint = "分类", modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(8.dp))
                            NeuField(e.notes, { v -> setField(i) { it.copy(notes = v) } }, hint = "备注", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeuTextButton("取消") { onDismiss() }
            Spacer(Modifier.width(10.dp))
            PrimaryButton(
                text = if (importing) "导入中…" else "导入 ${parsed.count { it.include }} 条",
                modifier = Modifier.width(160.dp),
                enabled = parsed.any { it.include } && !importing
            ) {
                importing = true
                model.importBatch(parsed) { ok, failed ->
                    model.note("批量录入完成：成功 $ok 条" + if (failed > 0) "，失败 $failed 条" else "")
                    onDismiss()
                }
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
    var showCatManage by remember { mutableStateOf(false) }
    var showBatch by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    NeuDialogShell(width = 540.dp) {
        Column(
            modifier = Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())
        ) {
            Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动锁超时（秒，0=不超时）", fontSize = 12.sp, color = neu.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    NeuField(lockSec, { lockSec = it }, hint = "秒",
                        modifier = Modifier.fillMaxWidth(0.42f))
                    Spacer(Modifier.height(14.dp))
                    Text("剪贴板清除延时（秒，0=永不自动清除）", fontSize = 12.sp, color = neu.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    NeuField(clipSec, { clipSec = it }, hint = "秒",
                        modifier = Modifier.fillMaxWidth(0.42f))
                }
                Spacer(Modifier.width(16.dp))
                // 正方形保存按钮，靠右对齐
                NeuButton("保存", modifier = Modifier.size(76.dp),
                    contentPadding = PaddingValues(0.dp)) {
                    val l = lockSec.toIntOrNull(); val c = clipSec.toIntOrNull()
                    if (l == null || c == null || l < 0 || c < 0) msg = "请输入非负整数"
                    else model.saveSettings(l, c) { msg = it }
                }
            }

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(neu.divider))
            Spacer(Modifier.height(14.dp))
            Text("配色主题（与手机端同款色板）", fontSize = 15.sp,
                fontWeight = FontWeight.Bold, color = neu.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NeuThemeNames.forEachIndexed { i, label ->
                    if (model.themeMode == i) NeuButton(label) { }
                    else NeuTextButton(label) { model.setTheme(i) }
                }
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

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(neu.divider))
            Spacer(Modifier.height(14.dp))
            Text("数据管理", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
            Spacer(Modifier.height(8.dp))
            Row {
                NeuButton("分类管理…") { showCatManage = true }
                Spacer(Modifier.width(12.dp))
                NeuButton("批量录入…") { showBatch = true }
            }

            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(neu.divider))
            Spacer(Modifier.height(14.dp))
            Text("关于", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
            Spacer(Modifier.height(8.dp))
            AboutRow("版本", "v1.1.0（桌面版）")
            AboutRow("开发者", "17° by 拾柒")
            AboutRow("开源许可", "MIT License（详见根目录 LICENSE）")
            Spacer(Modifier.height(10.dp))
            NeuTextButton("免责声明与隐私政策…") { showAbout = true }
            Spacer(Modifier.height(10.dp))
            Text("17° by 拾柒 · 离线密码本 · MIT · 纯离线加密存储",
                fontSize = 11.sp, color = neu.onSurfaceVariant)

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
    if (showCatManage) CategoryManageDialog(model) { showCatManage = false }
    if (showBatch) BatchImportDialog(model) { showBatch = false }
    if (showAbout) AboutDialog { showAbout = false }
}

@Composable
private fun AboutRow(label: String, value: String) {
    val neu = LocalNeu.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, color = neu.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text(value, fontSize = 13.sp, color = neu.onSurface)
    }
}

/** 免责声明与隐私政策（与手机端 about_disclaimer 同源全文），可滚动。 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val neu = LocalNeu.current
    NeuDialogShell(width = 560.dp, onDismiss = onDismiss) {
        Text("关于离线密码本", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(6.dp))
        Text("纯离线密码管理器 · Android + Windows 桌面双端 · 数据本地加密存储，无网络、无账号、无云同步。",
            fontSize = 13.sp, color = neu.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        AboutRow("版本", "v1.1.0（桌面版）")
        AboutRow("开发者", "17° by 拾柒")
        AboutRow("开源许可", "MIT License（详见根目录 LICENSE）")
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(neu.divider))
        Spacer(Modifier.height(14.dp))
        Text("免责声明与隐私政策", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
        ) {
            Text(ABOUT_DISCLAIMER, fontSize = 12.sp, color = neu.onSurface, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeuTextButton("关闭") { onDismiss() }
        }
    }
}

private const val ABOUT_DISCLAIMER = """【隐私政策】
1. 纯离线运行：本应用未申请任何网络权限（无 INTERNET 权限），技术上不存在把数据发送到任何服务器的通道，绝不联网、绝不上传。
2. 数据只存本机：全部密码数据经 AES-256-GCM 加密后仅保存在本机应用私有目录的加密数据库中；主密码经 Argon2id 派生密钥，密钥材料不出本机、用毕即清零。
3. 零采集零追踪：不采集、不分析、不共享任何用户信息；无广告、无统计 SDK、无第三方追踪组件。
4. 剪贴板：复制密码会写入系统剪贴板，并按设置页的延时自动清除；部分场景可能在其它应用中短暂可见，属系统机制，请留意。
5. 备份文件：导出的 .vault 备份为加密文件，由您自行选择位置保存与保管；备份文件一旦泄露，强度取决于您设置的导出密码，请勿使用弱密码。
6. 权限说明：仅使用生物识别（指纹）权限用于本地解锁认证，不用于任何其它目的。

【免责声明】
本软件是一款纯离线的个人开源密码管理器（MIT 许可）。软件按"原样"提供，作者不对因使用或无法使用本软件造成的任何数据丢失、损坏或泄露承担责任。

请务必牢记主密码——它不落盘、无法找回；未开启指纹解锁且遗忘主密码时，所有数据将永久无法解密。请勿用于生产级敏感凭证管理。"""

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

// ---------------- 分类管理弹窗（增删改 + 上下重排；对齐移动端 CategoryManageActivity 功能面） ----------------

@Composable
fun CategoryManageDialog(model: AppModel, onDismiss: () -> Unit) {
    val neu = LocalNeu.current
    var newCat by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editName by remember { mutableStateOf("") }

    NeuDialogShell(width = 480.dp, onDismiss = onDismiss) {
        Text("分类管理", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = neu.onSurface)
        Spacer(Modifier.height(4.dp))
        Text("用 ↑ ↓ 调整顺序；「其他」为种子分类不可删除；非空分类需先移空再删。",
            fontSize = 12.sp, color = neu.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            model.categories.forEach { c ->
                val editing = editingId == c.id
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    NeuIconButton(onClick = { model.moveCategory(c.id, true) }, sizeDp = 32.dp) {
                        Text("↑", fontSize = 14.sp, color = neu.onSurface)
                    }
                    Spacer(Modifier.width(6.dp))
                    NeuIconButton(onClick = { model.moveCategory(c.id, false) }, sizeDp = 32.dp) {
                        Text("↓", fontSize = 14.sp, color = neu.onSurface)
                    }
                    Spacer(Modifier.width(10.dp))
                    if (editing) {
                        NeuField(editName, { editName = it }, hint = "分类名称", modifier = Modifier.weight(1f), onEnter = {
                            model.renameCategory(c.id, editName); editingId = null
                        })
                        Spacer(Modifier.width(8.dp))
                        NeuButton("保存", contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                            model.renameCategory(c.id, editName); editingId = null
                        }
                    } else {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = neu.onSurface)
                            Text("${c.entryCount} 条", fontSize = 11.sp, color = neu.onSurfaceVariant)
                        }
                        NeuTextButton("重命名") { editingId = c.id; editName = c.name }
                        Spacer(Modifier.width(6.dp))
                        NeuTextButton("删除") { model.deleteCategory(c.id) }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(neu.divider))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuField(newCat, { newCat = it }, hint = "新分类名称", modifier = Modifier.weight(1f),
                onEnter = { model.createCategory(newCat); newCat = "" })
            Spacer(Modifier.width(10.dp))
            NeuButton("添加", contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                model.createCategory(newCat); newCat = ""
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NeuTextButton("关闭") { onDismiss() }
        }
    }
}
