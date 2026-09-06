package com.qiqiao.passwordvault.ui.list

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.model.PasswordEntryRow
import com.qiqiao.passwordvault.ui.common.NeuSurface
import com.qiqiao.passwordvault.util.formatTime
import com.qiqiao.passwordvault.util.maskPassword
import com.qiqiao.passwordvault.util.passwordStrength
import com.qiqiao.passwordvault.util.strengthLabel

// 列表条目适配器（照抄模板 43 .entry 卡片）。
// 纯展示 + eye/copy 回调上抛；点击卡片主体进详情。
// eye：异步回填明文（onEye），明文只写入当前 holder 的 TextView，不落任何集合；
//      滚动回收（onViewRecycled）即恢复掩码并清除揭示态。
// 强度：strengthMap 由页面层异步计算写入（明文算完即弃，只留 0..5 整数）；无数据(-1)隐藏强度区。
// 入场动画：按 position 交错 riseIn；回收时清动画残留防闪烁。
class EntryAdapter(
    private val onClick: (PasswordEntryRow) -> Unit,
    private val onCopy: (PasswordEntryRow) -> Unit,
    private val onEye: (PasswordEntryRow, EyeResult) -> Unit
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    /** eye 异步回填结果载体：revealed=false 表示切回掩码；plaintext 仅在揭示时非空。 */
    class EyeResult(val revealed: Boolean, val plaintext: String)

    private val items = mutableListOf<PasswordEntryRow>()
    private var lastCount = -1
    private val revealedIds = mutableSetOf<Long>()
    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        attachedRecyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        attachedRecyclerView = null
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val monogram: TextView = v.findViewById(R.id.tvMonogram)
        val name: TextView = v.findViewById(R.id.tvName)
        val user: TextView = v.findViewById(R.id.tvUser)
        val masked: TextView = v.findViewById(R.id.tvMasked)
        val updated: TextView = v.findViewById(R.id.tvUpdated)
        val eye: ImageButton = v.findViewById(R.id.btnEye)
        val eyeContainer: NeuSurface = v.findViewById(R.id.eyeContainer)
        val copy: View = v.findViewById(R.id.btnCopy)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_entry_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        val ctx = h.itemView.context
        h.monogram.text = monogramOf(e.name)
        h.name.text = e.name
        h.user.text = if (e.username.isBlank()) "—" else e.username
        h.updated.text = ctx.getString(R.string.list_updated_prefix, formatTime(e.updatedAt))

        // 密码槽：揭示态明文（仅当行内恰好有明文时成立）/ 默认掩码。
        // 列表行无明文，揭示态在回收重绑后必然失效 -> 主动清除，回到掩码（明文不驻留）。
        val revealed = if (e.id in revealedIds && e.password.isNotBlank()) true
        else { revealedIds.remove(e.id); false }
        h.masked.text = if (revealed) e.password else maskPassword()
        applyEyeState(h, revealed)

        h.itemView.setOnClickListener { onClick(e) }
        h.eye.setOnClickListener {
            val toRevealed = e.id !in revealedIds
            if (toRevealed) {
                onEye(e, EyeResult(true, ""))
            } else {
                revealedIds.remove(e.id)
                h.masked.text = maskPassword()
                applyEyeState(h, false)
            }
        }
        h.copy.setOnClickListener { onCopy(e) }
        // .copy:active{transform:scale(.92)}——按压缩放动效
        h.copy.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }

        // 交错入场：仅首次出现该位置时播动画，避免滚动时重复闪烁
        if (position >= lastCount) {
            h.itemView.alpha = 0f
            h.itemView.translationY = 28f
            h.itemView.animate()
                .alpha(1f).translationY(0f)
                .setDuration(240L)
                .setStartDelay((position.coerceAtMost(5)) * 50L)
                .start()
        }
    }

    /** eye 凹陷/凸起 + 图标色切换（模板 .eye[aria-pressed="true"]）。 */
    private fun applyEyeState(h: VH, revealed: Boolean) {
        h.eyeContainer.direction =
            if (revealed) NeuSurface.Direction.INSET else NeuSurface.Direction.RAISED
        h.eye.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                h.itemView.context,
                if (revealed) R.color.primary else R.color.on_surface_variant
            )
        )
        h.eye.contentDescription = h.itemView.context.getString(
            if (revealed) R.string.detail_hide else R.string.detail_show
        )
    }

    /** 页面层异步拿到明文后回填：只写当前可见 holder 的 TextView（列表行模型无明文，不走 bind）。
     *  滚动回收后 revealedIds 被清理，重新滚回即恢复掩码——明文不驻留。 */
    fun revealEntry(id: Long, plaintext: String) {
        revealedIds.add(id)
        val pos = positionOf(id)
        val h = attachedRecyclerView?.findViewHolderForAdapterPosition(pos) as? VH ?: return
        h.masked.text = plaintext
        applyEyeState(h, true)
    }

    /** 收起揭示（eye 再点一次已由 bind 处理；此处供回收/刷新路径清理）。 */
    fun collapseReveal(id: Long) {
        if (revealedIds.remove(id)) {
            val pos = positionOf(id)
            val h = attachedRecyclerView?.findViewHolderForAdapterPosition(pos) as? VH ?: return
            h.masked.text = maskPassword()
            applyEyeState(h, false)
        }
    }

    private fun positionOf(id: Long): Int = items.indexOfFirst { it.id == id }

    override fun onViewRecycled(h: VH) {
        // 回收清理：动画残留 + 揭示态（重新滚回即掩码，明文不驻留）
        h.itemView.animate().cancel()
        h.itemView.alpha = 1f
        h.itemView.translationY = 0f
        items.getOrNull(h.bindingAdapterPosition)?.let { revealedIds.remove(it.id) }
    }

    override fun getItemCount(): Int = items.size

    fun replace(list: List<PasswordEntryRow>) {
        lastCount = items.size
        items.clear()
        items.addAll(list)
        // 移除已不在列表中的揭示态/强度缓存，防泄漏
        val ids = list.map { it.id }.toSet()
        revealedIds.retainAll(ids)
        notifyDataSetChanged()
    }

    fun entryAt(id: Long): PasswordEntryRow? = items.firstOrNull { it.id == id }

    companion object {
        // 徽标字：首字符（中文/字母/数字均可），空白兜底 "？"
        fun monogramOf(name: String): String {
            val t = name.trim()
            return if (t.isEmpty()) "？" else t.substring(0, 1).uppercase()
        }
    }
}