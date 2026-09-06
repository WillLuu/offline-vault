package com.qiqiao.passwordvault.ui.category

import com.qiqiao.passwordvault.ui.PwdBaseActivity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.data.Vault
import com.qiqiao.passwordvault.model.CategoryRow
import com.qiqiao.passwordvault.model.DEFAULT_CATEGORY_NAME

// 分类管理：列表(含计数) + 新增 / 编辑 / 删除 / 拖拽排序 + 自动排序。
class CategoryManageActivity : PwdBaseActivity() {

    private lateinit var rvCategory: RecyclerView
    private lateinit var tvEmpty: TextView
    private val adapter = CategoryAdapter(
        onEdit = { editCategory(it) },
        onDelete = { deleteCategory(it) },
        onStartDrag = { vh -> itemTouchHelper.startDrag(vh) }
    )

    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_manage)

        rvCategory = findViewById(R.id.rvCategory)
        tvEmpty = findViewById(R.id.tvEmpty)
        rvCategory.layoutManager = LinearLayoutManager(this)
        rvCategory.adapter = adapter

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.apply {
                        alpha = 0.85f
                        scaleX = 1.03f
                        scaleY = 1.03f
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.apply { alpha = 1f; scaleX = 1f; scaleY = 1f }
                // 拖拽结束：把内存新顺序持久化进 sortOrder
                persistOrder()
            }
        })
        itemTouchHelper.attachToRecyclerView(rvCategory)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<android.widget.Button>(R.id.btnAdd).setOnClickListener { addCategory() }
        findViewById<View>(R.id.btnSort).setOnClickListener { showSortMenu() }

        load()
    }

    private fun load() {
        Vault.data.listCategories { res ->
            res.onSuccess { cats ->
                runOnUiThread {
                    adapter.replace(cats)
                    tvEmpty.visibility = if (cats.isEmpty()) TextView.VISIBLE else TextView.GONE
                }
            }
        }
    }

    // 拖拽结束：把当前顺序写入 sortOrder（下标即顺序）；手动拖拽后排序方式回到「手动」
    private fun persistOrder() {
        sortIndex = 0
        Vault.data.reorderCategories(adapter.getOrder()) { }
    }

    // 当前排序方式下标（修复：弹窗 checkedIndex 原硬编码 0，不反映用户上次选择）
    private var sortIndex = 0

    // 排序菜单（2026-09-06）：改用 NeuDialog 单选，与全局主题配色一致（原系统 PopupMenu 白底不统一）。
    private fun showSortMenu() {
        val options = listOf(
            getString(R.string.category_sort_manual),
            getString(R.string.category_sort_name),
            getString(R.string.category_sort_count),
            getString(R.string.category_sort_created)
        )
        com.qiqiao.passwordvault.ui.common.NeuDialog.showSingleChoice(
            this,
            title = getString(R.string.category_sort),
            options = options,
            checkedIndex = sortIndex,
            onSelect = { index ->
                sortIndex = index
                when (index) {
                    0 -> toast(R.string.category_sort_manual)
                    1 -> autoSort({ it.name }, R.string.category_sort_name)
                    2 -> autoSort({ -it.entryCount }, R.string.category_sort_count)
                    3 -> autoSort({ it.createdAt }, R.string.category_sort_created)
                }
            }
        )
    }

    // 按条件自动排序并持久化：重写每条 sortOrder = 下标
    private fun autoSort(selector: (CategoryRow) -> Comparable<*>, labelRes: Int) {
        val ordered = adapter.getItems().sortedWith(compareBy(selector)).map { it.id }
        Vault.data.reorderCategories(ordered) { r ->
            runOnUiThread {
                if (r.getOrDefault(false)) {
                    toast(getString(R.string.category_sorted, getString(labelRes)))
                    load()
                }
            }
        }
    }

    private fun addCategory() {
        com.qiqiao.passwordvault.ui.common.NeuDialog.showInput(
            this,
            title = getString(R.string.category_add),
            hint = getString(R.string.category_name_hint),
            onConfirm = { name ->
                if (name.isBlank()) return@showInput false
                Vault.data.createCategory(name, Int.MAX_VALUE) { r ->
                    runOnUiThread {
                        if (r.getOrDefault(-1L) < 0) toast(R.string.category_exists) else load()
                    }
                }
                true
            }
        )
    }

    private fun editCategory(c: CategoryRow) {
        com.qiqiao.passwordvault.ui.common.NeuDialog.showInput(
            this,
            title = getString(R.string.category_edit),
            hint = getString(R.string.category_name_hint),
            prefill = c.name,
            onConfirm = { name ->
                if (name.isBlank()) return@showInput false
                Vault.data.updateCategory(c.id, name = name) { r ->
                    runOnUiThread { if (r.getOrDefault(false)) load() }
                }
                true
            }
        )
    }

    private fun deleteCategory(c: CategoryRow) {
        if (c.name == DEFAULT_CATEGORY_NAME) { toast(R.string.category_cannot_delete); return }
        if (c.entryCount > 0) { toast(R.string.category_not_empty); return }
        com.qiqiao.passwordvault.ui.common.NeuDialog.showConfirm(
            context = this,
            title = c.name,
            message = getString(R.string.common_confirm_delete_category),
            danger = true,
            okText = getString(R.string.category_delete),
            onOk = {
                Vault.data.deleteCategory(c.id) { r ->
                    runOnUiThread { if (r.getOrDefault(false)) load() }
                }
            },
            onCancel = null
        )
    }

    private fun toast(msg: Int) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun toast(msg: CharSequence) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
