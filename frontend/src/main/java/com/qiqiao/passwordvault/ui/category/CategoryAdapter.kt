package com.qiqiao.passwordvault.ui.category

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.model.CategoryRow

// 分类列表适配器。编辑/删除通过回调上抛；拖拽排序由 ItemTouchHelper 驱动。
class CategoryAdapter(
    private val onEdit: (CategoryRow) -> Unit,
    private val onDelete: (CategoryRow) -> Unit,
    private val onStartDrag: (VH) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private val items = mutableListOf<CategoryRow>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvCatName)
        val count: TextView = v.findViewById(R.id.tvCatCount)
        val drag: ImageView = v.findViewById(R.id.ivDrag)
        val edit: Button = v.findViewById(R.id.btnCatEdit)
        val delete: Button = v.findViewById(R.id.btnCatDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.name.text = c.name
        h.count.text = h.itemView.context.getString(R.string.category_count, c.entryCount)
        h.edit.setOnClickListener { onEdit(c) }
        h.delete.setOnClickListener { onDelete(c) }
        // 拖拽手柄 + 整项长按均可触发排序拖拽
        h.drag.setOnLongClickListener { onStartDrag(h); true }
        h.itemView.setOnLongClickListener { onStartDrag(h); true }
    }

    override fun getItemCount(): Int = items.size

    fun replace(list: List<CategoryRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    // 拖拽中交换两条数据（仅改内存顺序，落库在拖拽结束时统一写入）
    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        notifyItemMoved(from, to)
    }

    fun getOrder(): List<Long> = items.map { it.id }

    fun getItems(): List<CategoryRow> = items.toList()
}
