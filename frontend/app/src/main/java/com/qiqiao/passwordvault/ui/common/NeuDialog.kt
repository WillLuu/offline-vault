package com.qiqiao.passwordvault.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.qiqiao.passwordvault.R

/**
 * 新拟态自绘弹窗（统一设置页/分类管理等所有弹窗的视觉语言）：
 * 透明窗口 + halo 呼吸容器 + NeuSurface r26 凸起卡 + 凹陷输入 + 文字取消钮 + 凸起胶囊确认钮。
 * 调用方在 onConfirm 中完成校验/提交；返回 true 关闭弹窗，false 保持打开（如校验失败）。
 */
object NeuDialog {

    /**
     * 弹窗背景色：取当前主题 attr colorSurface（主题感知）。
     * 不能硬编码 R.color.background——morandi 无资源限定符、靠运行时换肤，
     * 硬编码会取到浅色值，造成"莫兰迪主题下弹窗是浅色、浅色主题下弹窗是莫兰迪"的错乱。
     */
    fun surfaceColor(context: android.content.Context): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true)) {
            tv.data
        } else ContextCompat.getColor(context, R.color.surface)
    }

    /** 单输入弹窗（分类新增/编辑等）。 */
    fun showInput(
        context: Context,
        title: String,
        hint: String,
        prefill: String = "",
        isPassword: Boolean = false,
        onConfirm: (text: String) -> Boolean,
        onCancel: (() -> Unit)? = null
    ) {
        val dp = context.resources.displayMetrics.density
        fun sp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics).toInt()
        fun color(res: Int) = ContextCompat.getColor(context, res)

        val input = EditText(context).apply {
            setHint(hint)
            setText(prefill)
            inputType = if (isPassword)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            setBackgroundResource(android.R.color.transparent)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(R.color.on_surface))
            setHintTextColor(color(R.color.on_surface_variant))
        }

        val inputSurface = NeuSurface(context).apply {
            direction = NeuSurface.Direction.INSET
            cornerRadiusPx = 16 * dp
        }
        inputSurface.addView(input, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad, pad, (10 * dp).toInt())
            clipChildren = false; clipToPadding = false
            addView(TextView(context).apply {
                text = title
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setTextColor(color(R.color.on_surface))
            })
            addView(inputSurface, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (14 * dp).toInt()
            })
        }

        val btnCancel = TextView(context).apply {
            text = context.getString(R.string.common_cancel)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color(R.color.on_surface_variant))
            gravity = Gravity.CENTER
            isClickable = true
        }
        val btnOk = TextView(context).apply {
            text = context.getString(R.string.common_ok)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            setTextColor(color(R.color.primary))
            gravity = Gravity.CENTER
            isClickable = true
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        buttonRow.addView(btnCancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (18 * dp).toInt()
        })
        // 确认钮：普通卡片（轻投影），弹窗内嵌套控件不用 neu 凸起（白亮影糊边，用户定稿）
        val okSurface = com.google.android.material.card.MaterialCardView(context).apply {
            radius = 22 * dp
            cardElevation = 2 * dp
            strokeWidth = 0
            setCardBackgroundColor(color(R.color.surface))
        }
        okSurface.addView(btnOk, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()).apply {
            marginStart = (22 * dp).toInt(); marginEnd = (22 * dp).toInt()
        })
        buttonRow.addView(okSurface)
        content.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (18 * dp).toInt()
        })

        // 浮层卡片：用普通深色投影（MaterialCardView），不用新拟态双影——
        // neu 亮影白 halo 浮在暗色遮罩上呈白色光晕，破坏全局风格（用户定稿）
        val card = com.google.android.material.card.MaterialCardView(context).apply {
            radius = 26 * dp
            cardElevation = 8 * dp
            setCardBackgroundColor(surfaceColor(context))
            strokeWidth = 0
            isClickable = false; isFocusable = false
        }
        card.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val dlg = Dialog(context)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // 外层透明容器给凸起 halo 留空间（窗口裁剪会造成四角白色残留）
        val root = FrameLayout(context).apply {
            clipChildren = false; clipToPadding = false
            val haloPad = (20 * dp).toInt()
            setPadding(haloPad, haloPad, haloPad, haloPad)
        }
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(context.resources.displayMetrics.widthPixels,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        btnCancel.setOnClickListener { dlg.dismiss(); onCancel?.invoke() }
        btnOk.setOnClickListener {
            if (onConfirm(input.text.toString().trim())) dlg.dismiss()
        }
        dlg.show()
    }

    /**
     * 单选列表弹窗（主题选择等）。选项行点击即生效；当前项 ✓ + primary 高亮，
     * 视觉语言与 showInput 一致（MaterialCardView 深投影卡 + 主题 surface 底色）。
     */
    fun showSingleChoice(
        context: Context,
        title: String,
        options: List<String>,
        checkedIndex: Int,
        onSelect: (Int) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val dp = context.resources.displayMetrics.density
        fun sp(v: Float) = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics).toInt()
        fun color(res: Int) = ContextCompat.getColor(context, res)

        val dlg = Dialog(context)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad, pad, (10 * dp).toInt())
            clipChildren = false; clipToPadding = false
            addView(TextView(context).apply {
                text = title
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setTextColor(color(R.color.on_surface))
                setPadding(0, 0, 0, (6 * dp).toInt())
            })

            // 选中行高亮（主题 attr 涟漪背景）
            val ripple = android.util.TypedValue()
            val hasRipple = context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true)
            val rippleRes = if (hasRipple) ripple.resourceId else 0

            options.forEachIndexed { index, label ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (52 * dp).toInt()
                    setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                    clipChildren = false; clipToPadding = false
                    isClickable = true
                    if (rippleRes != 0) {
                        background = androidx.core.content.res.ResourcesCompat.getDrawable(
                            context.resources, rippleRes, context.theme)
                    }
                }
                val isChecked = index == checkedIndex
                row.addView(TextView(context).apply {
                    text = label
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    if (isChecked) {
                        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                        setTextColor(color(R.color.primary))
                    } else {
                        setTextColor(color(R.color.on_surface))
                    }
                    setPadding(0, 0, (8 * dp).toInt(), 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(context).apply {
                    text = if (isChecked) "✓" else ""
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    setTextColor(color(R.color.primary))
                })
                row.setOnClickListener {
                    dlg.dismiss()
                    onSelect(index)
                }
                addView(row)
            }
        }

        // 取消（可空：为空则不显示，靠点外部/返回键关闭）
        if (onCancel != null) {
            val btnCancel = TextView(context).apply {
                text = context.getString(R.string.common_cancel)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(color(R.color.on_surface_variant))
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, (12 * dp).toInt(), 0, (6 * dp).toInt())
            }
            content.addView(btnCancel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            })
            btnCancel.setOnClickListener { dlg.dismiss(); onCancel.invoke() }
        }

        val card = com.google.android.material.card.MaterialCardView(context).apply {
            radius = 26 * dp
            cardElevation = 8 * dp
            setCardBackgroundColor(surfaceColor(context))
            strokeWidth = 0
            isClickable = false; isFocusable = false
        }
        card.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val root = FrameLayout(context).apply {
            clipChildren = false; clipToPadding = false
            val haloPad = (20 * dp).toInt()
            setPadding(haloPad, haloPad, haloPad, haloPad)
        }
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(context.resources.displayMetrics.widthPixels,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    /**
     * 确认弹窗（删除等破坏性操作 danger=true 确认字标红）。视觉语言与 showInput/showSingleChoice 一致：
     * MaterialCardView 深投影卡 + surface 底色；取消=文字钮，确认=白卡胶囊（danger 红 / 普通主紫）。
     */
    fun showConfirm(
        context: Context,
        title: String? = null,
        message: String,
        danger: Boolean = false,
        okText: String = "",
        onOk: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val dp = context.resources.displayMetrics.density
        fun color(res: Int) = ContextCompat.getColor(context, res)

        val dlg = Dialog(context)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad, pad, (10 * dp).toInt())
            clipChildren = false; clipToPadding = false
            if (title != null) {
                addView(TextView(context).apply {
                    text = title
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    setTextColor(color(R.color.on_surface))
                    setPadding(0, 0, 0, (10 * dp).toInt())
                })
            }
            addView(TextView(context).apply {
                text = message
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(color(R.color.on_surface))
                setLineSpacing(0f, 1.25f)
            })
        }

        val btnCancel = TextView(context).apply {
            text = context.getString(R.string.common_cancel)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color(R.color.on_surface_variant))
            gravity = Gravity.CENTER
            isClickable = true
        }
        val btnOk = TextView(context).apply {
            text = okText.ifEmpty { context.getString(R.string.common_ok) }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            setTextColor(color(if (danger) R.color.error else R.color.primary))
            gravity = Gravity.CENTER
            isClickable = true
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        buttonRow.addView(btnCancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (18 * dp).toInt()
        })
        val okSurface = com.google.android.material.card.MaterialCardView(context).apply {
            radius = 22 * dp
            cardElevation = 2 * dp
            strokeWidth = 0
            setCardBackgroundColor(color(R.color.surface))
        }
        okSurface.addView(btnOk, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()).apply {
            marginStart = (22 * dp).toInt(); marginEnd = (22 * dp).toInt()
        })
        buttonRow.addView(okSurface)
        content.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (18 * dp).toInt()
        })

        val card = com.google.android.material.card.MaterialCardView(context).apply {
            radius = 26 * dp
            cardElevation = 8 * dp
            setCardBackgroundColor(surfaceColor(context))
            strokeWidth = 0
            isClickable = false; isFocusable = false
        }
        card.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val root = FrameLayout(context).apply {
            clipChildren = false; clipToPadding = false
            val haloPad = (20 * dp).toInt()
            setPadding(haloPad, haloPad, haloPad, haloPad)
        }
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(context.resources.displayMetrics.widthPixels,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        btnCancel.setOnClickListener { dlg.dismiss(); onCancel?.invoke() }
        btnOk.setOnClickListener { dlg.dismiss(); onOk() }
        dlg.show()
    }
}