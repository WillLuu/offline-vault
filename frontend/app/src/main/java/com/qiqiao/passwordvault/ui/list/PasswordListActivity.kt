package com.qiqiao.passwordvault.ui.list

import com.qiqiao.passwordvault.ui.PwdBaseActivity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.data.Vault
import com.qiqiao.passwordvault.model.CategoryRow
import com.qiqiao.passwordvault.model.PasswordEntryRow
import com.qiqiao.passwordvault.model.SortKey
import com.qiqiao.passwordvault.ui.detail.EntryDetailActivity
import com.qiqiao.passwordvault.ui.edit.EntryEditActivity
import com.qiqiao.passwordvault.ui.common.NeuSurface
import com.qiqiao.passwordvault.ui.settings.SettingsActivity
import com.qiqiao.passwordvault.util.ClipboardHelper
import com.qiqiao.passwordvault.util.NavAnim

// 密码列表页：品牌区 + 搜索 + 分类筛选 Tab + 条目卡片（模板 43 卡片）+ 新拟态 FAB + 设置入口。
// 四种状态：有数据 / 空 / 加载中 / 错误。
// 复制交互（安全边界）：列表行不含明文 → 复制按钮异步 getEntry 回填明文，
//   经 ClipboardHelper 写入（含自动清除延时），Toast 反馈清除策略。
class PasswordListActivity : PwdBaseActivity() {

    private lateinit var etSearch: EditText
    private lateinit var tabContainer: LinearLayout
    private lateinit var rvList: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var emptyState: View
    private lateinit var tvError: View
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView

    // 当前排序（审查 2.2）：默认按更新时间倒序（2026-09-06 用户定稿，最新更新的卡片排最前）；
    // 由排序菜单选择后回写并 reload。
    // ponytail: 仅内存态，未持久化到 SharedPreferences | 触发升级阈值：用户希望记住排序偏好时再落盘。
    private var currentSortKey: SortKey = SortKey.UPDATED_DESC

    private val adapter = EntryAdapter(
        onClick = { openDetail(it) },
        onCopy = { copyFromList(it) },
        onEye = { e, result -> toggleReveal(e, result) }
    )
    private var categories: List<CategoryRow> = emptyList()
    private var selectedCategoryId: Long? = null
    private var searchText: String = ""
    private var clipboardDelaySec = 30
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password_list)

        etSearch = findViewById(R.id.etSearch)
        tabContainer = findViewById(R.id.tabContainer)
        rvList = findViewById(R.id.rvList)
        pbLoading = findViewById(R.id.pbLoading)
        emptyState = findViewById(R.id.emptyState)
        tvError = findViewById(R.id.tvError)
        tvCount = findViewById(R.id.tvCount)
        tvEmpty = findViewById(R.id.tvEmpty)

        rvList.layoutManager = LinearLayoutManager(this)
        rvList.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // ponytail: 简单 250ms 防抖，未引入 Rx/debounce 库 | 触发升级阈值：需更复杂输入处理
                searchText = s?.toString().orEmpty()
                main.postDelayed({ load() }, 250)
            }
        })

        tvError.setOnClickListener { load() }
        // 排序入口（审查 2.2）：后端已支持 4 种排序，此前无入口故硬编码 NAME_ASC。
        findViewById<View>(R.id.btnSort).setOnClickListener { showSortMenu() }
        // 底部导航（模板 .tabbar）：主页 = 滚回顶部；新增 = 新建条目；设置 = 设置页
        findViewById<View>(R.id.btnNavHome).setOnClickListener {
            rvList.smoothScrollToPosition(0)
        }
        findViewById<View>(R.id.btnNavAdd).setOnClickListener {
            navTo(Intent(this, EntryEditActivity::class.java), NavAnim.DEST_ADD)
        }
        // 数据（2026-09-06 用户需求）：数据管理独立页
        findViewById<View>(R.id.btnNavData).setOnClickListener {
            navTo(Intent(this, com.qiqiao.passwordvault.ui.import.DataManageActivity::class.java), NavAnim.DEST_DATA)
        }
        findViewById<View>(R.id.btnNavSettings).setOnClickListener {
            navTo(Intent(this, SettingsActivity::class.java), NavAnim.DEST_SETTINGS)
        }

        setupAvatar()

        // 复制延时策略与详情页同源（设置页可配）
        Vault.data.getSettings { res ->
            res.onSuccess { clipboardDelaySec = it.clipboardClearDelaySec }
        }
        Vault.data.listCategories { res ->
            res.onSuccess { cats -> runOnUiThread { categories = cats; buildTabs() } }
        }
        load()
    }

    // 底部导航切页：方向感知左右滑动（NavAnim 单一事实源；本页 = 主页(0)）。
    private fun navTo(intent: Intent, to: Int) {
        NavAnim.go(this, intent, NavAnim.DEST_HOME, to)
    }

    // ---- 头像（可更换）：默认 17° 文字；点击弹出新拟态选择弹窗（相册选图 / 恢复默认）----
    private lateinit var avatarText: TextView
    private lateinit var avatarImage: ImageView

    private val pickAvatar = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult // 用户取消选择：静默
        if (com.qiqiao.passwordvault.util.AvatarStore.saveFromUri(this, uri)) {
            refreshAvatar()
        } else {
            // 修复：保存失败不再静默（解码失败/超大图异常等），给出提示
            Toast.makeText(this, R.string.avatar_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAvatar() {
        avatarText = findViewById(R.id.avatarText)
        avatarImage = findViewById(R.id.avatarImage)
        refreshAvatar()
        // 2026-09-06 用户需求：点击头像恢复「换头像」（含相册选择）；密码统计已移到"数据管理"页
        findViewById<View>(R.id.avatarContainer).setOnClickListener { showAvatarDialog() }
    }

    private fun refreshAvatar() {
        val bmp = com.qiqiao.passwordvault.util.AvatarStore.load(this)
        if (bmp != null) {
            avatarImage.setImageBitmap(bmp)
            avatarImage.visibility = View.VISIBLE
            avatarText.visibility = View.GONE
        } else {
            avatarImage.visibility = View.GONE
            avatarText.visibility = View.VISIBLE
        }
    }

    private fun showAvatarDialog() {
        val dp = resources.displayMetrics.density
        // 选项按钮：普通卡片（r14 + 轻投影），不用 neu 凸起——白亮影在小控件上散成糊边（用户定稿）
        fun navBtn(textRes: Int): com.google.android.material.card.MaterialCardView {
            val tv = TextView(this).apply {
                text = getString(textRes)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ContextCompat.getColor(this@PasswordListActivity, R.color.on_surface))
                gravity = android.view.Gravity.CENTER
            }
            return com.google.android.material.card.MaterialCardView(this).apply {
                radius = 14 * dp
                cardElevation = 2 * dp
                strokeWidth = 0
                setCardBackgroundColor(ContextCompat.getColor(this@PasswordListActivity, R.color.surface))
                // 点击事件绑在此卡片上；内部 TextView 不可点击（防吞事件）
                addView(tv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, (46 * dp).toInt()))
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            clipChildren = false; clipToPadding = false
            addView(TextView(this@PasswordListActivity).apply {
                text = getString(R.string.avatar_change_title)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@PasswordListActivity, R.color.on_surface))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (14 * dp).toInt() })
        }

        val btnAlbum = navBtn(R.string.avatar_pick_album)
        val btnReset = navBtn(R.string.avatar_reset)
        content.addView(btnAlbum, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (12 * dp).toInt()
        })
        content.addView(btnReset, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 浮层卡片：普通深色投影（neu 亮影白 halo 浮在暗遮罩上呈白晕，用户定稿弃用）
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 26 * dp
            cardElevation = 8 * dp
            setCardBackgroundColor(com.qiqiao.passwordvault.ui.common.NeuDialog.surfaceColor(this@PasswordListActivity))
            strokeWidth = 0
        }
        card.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false
            val haloPad = (20 * dp).toInt()
            setPadding(haloPad, haloPad, haloPad, haloPad)
        }
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(resources.displayMetrics.widthPixels,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        btnAlbum.setOnClickListener {
            dlg.dismiss()
            // 修复（2026-09-06 真机反馈「点相册就退回解锁页」）：系统选择器打开瞬间本应用退后台，
            // 触发「切后台立即锁」→ 返回时被路由回解锁页，表现为"相册打不开/被踢出"。
            // 与导出/导入一致：选择器期间豁免自动锁（120s TTL 兜底，返回即清除豁免）。
            com.qiqiao.passwordvault.MainApplication.externalPickerStarted()
            try {
                pickAvatar.launch("image/*")
            } catch (e: android.content.ActivityNotFoundException) {
                // 极少数精简 ROM 无图片选择器：明确提示而非无响应
                Toast.makeText(this, R.string.avatar_pick_failed, Toast.LENGTH_SHORT).show()
            }
        }
        btnReset.setOnClickListener {
            dlg.dismiss()
            com.qiqiao.passwordvault.util.AvatarStore.clear(this)
            refreshAvatar()
        }
        dlg.show()
    }

    /** 列表卡复制：异步回填明文 → ClipboardHelper（含延时清除）→ Toast 反馈策略。 */
    private fun copyFromList(e: PasswordEntryRow) {
        Vault.data.getEntry(e.id) { res ->
            runOnUiThread {
                res.onSuccess { row ->
                    if (row == null) return@onSuccess
                    ClipboardHelper.copy(this, row.password, clipboardDelaySec)
                    val msg = if (clipboardDelaySec > 0)
                        getString(R.string.list_copied_auto, clipboardDelaySec)
                    else getString(R.string.list_copied_manual)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, R.string.list_copy_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** eye 揭示（模板 43）：异步 getEntry 回填明文只写入当前卡 TextView，明文不落集合。 */
    private fun toggleReveal(e: com.qiqiao.passwordvault.model.PasswordEntryRow, result: EntryAdapter.EyeResult) {
        if (!result.revealed) return
        Vault.data.getEntry(e.id) { res ->
            runOnUiThread {
                res.onSuccess { row ->
                    if (row != null) adapter.revealEntry(e.id, row.password)
                }
            }
        }
    }

    private fun buildTabs() {
        tabContainer.removeAllViews()
        val allChip = makeChip(getString(R.string.tab_all), null)
        tabContainer.addView(allChip)
        categories.forEach { c -> tabContainer.addView(makeChip(c.name, c.id)) }
        selectChip(allChip)
    }

    // 新拟态 chip：NeuSurface 容器（按选中态切换 RAISED/INSET，true 双影） + 内部 TextView
    //   NeuSurface wrap_content → view 尺寸 = child 尺寸，halo 由父溢出；
    //   TextView match_parent × wrap_content → 整体高度由字号自适配，halo 才有空间显示。
    @SuppressLint("ResourceType")
    private fun makeChip(text: String, catId: Long?): NeuSurface {
        val container = NeuSurface(this).apply {
            // chip 用 XS 档位：3dp 偏移 + 6dp 模糊（模板 .tab 视觉）
            elevation = NeuSurface.Elevation.XS
            direction = NeuSurface.Direction.RAISED
            // 胶囊圆角（模板 .chip r-full）：必须显式设置，否则凹陷态内阴影环带是直角带
            cornerRadiusPx = 999 * resources.displayMetrics.density
            // 文本点击热区由容器接收
            isClickable = true
            isFocusable = true
        }
        val tv = TextView(this)
        tv.text = text
        tv.gravity = android.view.Gravity.CENTER
        // 模板 .chip：12.5sp 600 muted（选中 primary 由 chip_text selector 处理）
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
        tv.typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        tv.setTextColor(AppCompatResources.getColorStateList(this, R.drawable.chip_text))
        tv.isClickable = false   // 点击由父容器接收，避免双触发
        tv.isFocusable = false
        val dp = resources.displayMetrics.density
        // 模板 .chip padding 8x16，chip 间距 10
        val hPad = (16 * dp).toInt()
        tv.setPadding(hPad, (8 * dp).toInt(), hPad, (8 * dp).toInt())
        // child 用 wrap_content 高度（跟随字号 padding），NeuSurface wrap_content 跟随 child
        container.addView(
            tv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        )
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, (10 * dp).toInt(), 0)
        container.layoutParams = params
        container.setOnClickListener {
            selectedCategoryId = catId
            selectChip(container)
            load()
        }
        return container
    }

    private fun selectChip(chip: NeuSurface) {
        for (i in 0 until tabContainer.childCount) {
            val c = tabContainer.getChildAt(i)
            val isSel = (c == chip)
            c.isSelected = isSel
            if (c is NeuSurface) {
                c.direction = if (isSel) NeuSurface.Direction.INSET else NeuSurface.Direction.RAISED
            }
        }
    }

    // 排序菜单（审查 2.2）：后端已支持 4 种排序但此前无入口（硬编码 NAME_ASC）。
    // 复用与分类管理页排序按钮同款的 PopupMenu 交互，保持一致，不引入新视觉组件。
    // 排序菜单（审查 2.2 + 2026-09-06）：NeuDialog 单选，与全局主题配色一致（系统 PopupMenu 白底不统一）。
    private fun showSortMenu() {
        val options = listOf(
            getString(R.string.sort_updated_desc),
            getString(R.string.sort_name_asc),
            getString(R.string.sort_name_desc),
            getString(R.string.sort_category_asc),
            getString(R.string.sort_category_desc)
        )
        val checked = when (currentSortKey) {
            SortKey.UPDATED_DESC -> 0
            SortKey.NAME_ASC -> 1
            SortKey.NAME_DESC -> 2
            SortKey.CATEGORY_ASC -> 3
            SortKey.CATEGORY_DESC -> 4
        }
        com.qiqiao.passwordvault.ui.common.NeuDialog.showSingleChoice(
            this,
            title = getString(R.string.list_sort),
            options = options,
            checkedIndex = checked,
            onSelect = { index ->
                val key = when (index) {
                    0 -> SortKey.UPDATED_DESC
                    1 -> SortKey.NAME_ASC
                    2 -> SortKey.NAME_DESC
                    3 -> SortKey.CATEGORY_ASC
                    else -> SortKey.CATEGORY_DESC
                }
                if (key != currentSortKey) {
                    currentSortKey = key
                    load()
                }
            }
        )
    }

    private fun load() {
        // 锁态兜底：SAF 导出等切后台可能已自动锁但页面残留前台——直接引导解锁，不显示"加载失败"
        if (Vault.data.isLocked()) {
            android.util.Log.i("PwdList", "load() while locked -> route to unlock")
            startActivity(
                Intent(this, com.qiqiao.passwordvault.ui.unlock.UnlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
            return
        }
        pbLoading.visibility = View.VISIBLE
        rvList.visibility = View.GONE
        emptyState.visibility = View.GONE
        tvError.visibility = View.GONE
        tvCount.visibility = View.GONE

        Vault.data.listEntries(
            search = searchText.takeIf { it.isNotBlank() },
            sortBy = currentSortKey,
            categoryId = selectedCategoryId
        ) { res ->
            runOnUiThread {
                pbLoading.visibility = View.GONE
                res.onSuccess { list ->
                    adapter.replace(list)
                    if (list.isEmpty()) {
                        emptyState.visibility = View.VISIBLE
                        // 审查 2.4：区分"库为空"与"没搜到 / 该分类下没有"——原先两者共用同一文案
                        val filtering = searchText.isNotBlank() || selectedCategoryId != null
                        tvEmpty.text = getString(
                            if (filtering) R.string.list_empty_filtered else R.string.list_empty
                        )
                    } else {
                        rvList.visibility = View.VISIBLE
                        // 审查 2.4：结果计数，搜索/筛选后明确告知条数
                        tvCount.visibility = View.VISIBLE
                        tvCount.text = getString(R.string.list_count, list.size)
                    }
                }.onFailure { t ->
                    android.util.Log.e("PwdList", "listEntries failed", t)
                    tvError.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从编辑/详情页返回时刷新列表：新增/修改/删除后立即可见（曾出现"新建后要退出重进才显示"）
        if (this::etSearch.isInitialized) load()
    }

    private fun openDetail(e: PasswordEntryRow) {
        val intent = Intent(this, EntryDetailActivity::class.java)
        intent.putExtra("id", e.id)
        startActivity(intent)
    }
}
