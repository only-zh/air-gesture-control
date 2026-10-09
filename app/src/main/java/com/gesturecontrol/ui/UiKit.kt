package com.gesturecontrol.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.gesturecontrol.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * 界面工具箱。
 *
 * 原来整个 UI 是手写的 TextView + GradientDrawable，没有样式体系，
 * 所以每个页面各画各的、看起来不一致。这里把「卡片 / 行 / 按钮 / 滑块 / 状态胶囊」
 * 收敛成一套可复用组件，所有页面共用，改一处全局生效。
 *
 * 全部用 Material 3 组件（卡片、滑块、开关都是真的 Material 组件），
 * 让涟漪、状态色、动画交给框架，比自己画更像原生。
 */
class UiKit(private val context: Context) {

    val bg = color(R.color.bg)
    val surface = color(R.color.surface)
    val surfaceContainer = color(R.color.surface_container)
    val surfaceVariant = color(R.color.surface_variant)
    val outline = color(R.color.outline)
    val primary = color(R.color.primary)
    val onPrimary = color(R.color.on_primary)
    val success = color(R.color.success)
    val warning = color(R.color.warning)
    val danger = color(R.color.danger)
    val onSurface = color(R.color.on_surface)
    val onSurfaceVariant = color(R.color.on_surface_variant)
    val onSurfaceFaint = color(R.color.on_surface_faint)
    val codeBg = color(R.color.code_bg)
    val codeFg = color(R.color.code_fg)

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------ 页面骨架

    /** 返回 (ScrollView, 内容列)。内容列已经带好左右边距。 */
    fun screen(): Pair<ScrollView, LinearLayout> {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(48))
        }
        val scroll = ScrollView(context).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
            addView(column)
        }
        return scroll to column
    }

    fun space(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(context), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    // ---------------------------------------------------------------- 文字

    fun text(
        value: String,
        sizeSp: Float = 14f,
        color: Int = onSurface,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold || mono) {
            typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT_BOLD
        }
        includeFontPadding = false
    }

    // ---------------------------------------------------------------- 卡片

    /**
     * 分区卡片。collapsible = true 时标题行可点击折叠。
     *
     * 折叠对可用性帮助很大：原来所有开关、滑块、按钮、日志全铺在一页上，
     * 要滚很久才能找到想改的东西。
     */
    fun section(
        parent: LinearLayout,
        title: String,
        subtitle: String? = null,
        collapsible: Boolean = false,
        expanded: Boolean = true,
        onToggle: ((Boolean) -> Unit)? = null,
        build: (LinearLayout) -> Unit
    ): LinearLayout {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
        }

        val chevron = if (collapsible) {
            text(if (expanded) "▾" else "▸", 15f, onSurfaceVariant)
        } else null

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (collapsible) {
                isClickable = true
                setOnClickListener {
                    val next = body.visibility != View.VISIBLE
                    body.visibility = if (next) View.VISIBLE else View.GONE
                    chevron?.text = if (next) "▾" else "▸"
                    onToggle?.invoke(next)
                }
            }
        }
        val titleBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(text(title, 15f, onSurface, bold = true))
        if (!subtitle.isNullOrEmpty()) {
            titleBox.addView(
                text(subtitle, 12f, onSurfaceVariant).apply {
                    setPadding(0, dp(3), 0, 0)
                }
            )
        }
        header.addView(
            titleBox,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        chevron?.let { header.addView(it) }

        val card = MaterialCardView(context).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = outline
            setCardBackgroundColor(surface)
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
        }
        // MaterialCardView 是 FrameLayout：标题和内容必须包在一个纵向容器里，
        // 否则两块会叠在同一个位置上。
        card.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(body)
            }
        )
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        parent.addView(card, lp)

        build(body)
        return body
    }

    /** 卡片内部的分隔线 */
    fun divider(parent: LinearLayout, topDp: Int = 10, bottomDp: Int = 10) {
        parent.addView(
            View(context).apply { setBackgroundColor(outline) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(topDp)
                bottomMargin = dp(bottomDp)
            }
        )
    }

    // ------------------------------------------------------------ 状态胶囊

    fun pill(text: String, ok: Boolean, warn: Boolean = false): TextView {
        val tint = when {
            ok -> success
            warn -> warning
            else -> danger
        }
        return TextView(context).apply {
            this.text = "$text"
            setTextColor(tint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(adjustAlpha(tint, 0.14f), dp(8))
            includeFontPadding = false
        }
    }

    /** 状态胶囊排成一行，自动换行由调用方控制 */
    // ---------------------------------------------------------------- 行

    fun switchRow(
        parent: LinearLayout,
        title: String,
        subtitle: String? = null,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(title, 14f, onSurface))
        if (!subtitle.isNullOrEmpty()) {
            box.addView(
                text(subtitle, 12f, onSurfaceVariant).apply { setPadding(0, dp(3), dp(10), 0) }
            )
        }
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            MaterialSwitch(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            }
        )
        parent.addView(row)
        return row
    }

    fun navRow(
        parent: LinearLayout,
        title: String,
        subtitle: String? = null,
        leading: String? = null,
        trailing: String? = null,
        trailingOk: Boolean = true,
        accent: Int? = null,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }
        if (leading != null) {
            row.addView(
                text(leading, 15f, accent ?: onSurfaceVariant).apply {
                    setPadding(0, 0, dp(12), 0)
                }
            )
        }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(title, 14f, accent ?: onSurface))
        if (!subtitle.isNullOrEmpty()) {
            box.addView(
                text(subtitle, 12f, onSurfaceVariant).apply { setPadding(0, dp(3), dp(8), 0) }
            )
        }
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            row.addView(text(trailing, 15f, if (trailingOk) success else danger))
        }
        parent.addView(row)
        return row
    }

    fun caption(parent: LinearLayout, value: String) {
        parent.addView(
            text(value, 11f, onSurfaceFaint).apply { setPadding(0, dp(2), 0, dp(2)) }
        )
    }

    // ---------------------------------------------------------------- 按钮

    fun button(
        parent: LinearLayout,
        label: String,
        style: ButtonStyle = ButtonStyle.PRIMARY,
        onClick: () -> Unit
    ): MaterialButton {
        val (bgColor, fgColor) = when (style) {
            ButtonStyle.PRIMARY -> primary to onPrimary
            ButtonStyle.TONAL -> surfaceVariant to onSurface
            ButtonStyle.GHOST -> Color.TRANSPARENT to onSurfaceVariant
        }
        val button = MaterialButton(context).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            cornerRadius = dp(12)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(44)
            setTextColor(fgColor)
            backgroundTintList = ColorStateList.valueOf(bgColor)
            if (style == ButtonStyle.GHOST) strokeWidth = dp(1)
            if (style == ButtonStyle.GHOST) strokeColor = ColorStateList.valueOf(outline)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        parent.addView(button, lp)
        return button
    }

    enum class ButtonStyle { PRIMARY, TONAL, GHOST }

    /** 两列按钮网格，用于快捷测试那一堆命令 */
    fun buttonGrid(
        parent: LinearLayout,
        labels: List<Pair<String, () -> Unit>>,
        columns: Int = 2
    ) {
        var row: LinearLayout? = null
        labels.forEachIndexed { index, (label, action) ->
            if (index % columns == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, 0)
                }
                parent.addView(row)
            }
            val button = MaterialButton(context).apply {
                text = label
                isAllCaps = false
                textSize = 13f
                cornerRadius = dp(12)
                insetTop = 0
                insetBottom = 0
                minHeight = dp(42)
                setTextColor(onSurface)
                backgroundTintList = ColorStateList.valueOf(surfaceVariant)
                setOnClickListener { action() }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = dp(8)
                if (index % columns != 0) marginStart = dp(8)
            }
            row?.addView(button, lp)
        }
    }

    // ---------------------------------------------------------------- 滑块

    /**
     * 滑块一律用**整数**刻度。
     *
     * Material Slider 对 value / stepSize 的浮点一致性要求很严，
     * 直接喂 0.62 / 0.01 这种会因浮点误差抛 IllegalStateException。
     * 所以内部走整数，显示和回调用 scale 换算。
     */
    fun slider(
        parent: LinearLayout,
        label: String,
        value: Float,
        from: Int,
        to: Int,
        step: Int = 1,
        scale: Float = 1f,
        decimals: Int = 2,
        onChanged: (Float) -> Unit
    ) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val valueView = text(format(value, decimals), 13f, primary, bold = true)
        header.addView(
            text(label, 13f, onSurface),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(valueView)
        parent.addView(header)

        val initial = ((value / scale).roundToInt()).coerceIn(from, to)
        val slider = Slider(context)
        // 用显式 setter 而不是属性语法：这些属性的 getter 命名在不同版本里不一致
        slider.valueFrom = from.toFloat()
        slider.valueTo = to.toFloat()
        slider.stepSize = step.toFloat()
        slider.value = initial.toFloat()
        slider.setTrackHeight(dp(5))
        slider.setThumbWidth(dp(16))
        slider.setThumbHeight(dp(16))
        slider.setThumbElevation(0f)
        slider.setTickVisible(false)
        slider.setLabelFormatter { v -> format(v * scale, decimals) }
        slider.addOnChangeListener { _, v, _ ->
            val real = v * scale
            valueView.text = format(real, decimals)
            onChanged(real)
        }
        parent.addView(slider)
    }

    private fun format(value: Float, decimals: Int): String =
        if (decimals <= 0) value.roundToInt().toString() else "%.${decimals}f".format(value)

    // ---------------------------------------------------------------- 杂项

    /** 深色代码/日志块 */
    fun monoBlock(parent: LinearLayout, content: String, maxLines: Int = 0) {
        val tv = TextView(context).apply {
            text = content
            setTextColor(codeFg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(codeBg, dp(10))
            if (maxLines > 0) {
                this.maxLines = maxLines
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
        parent.addView(tv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
    }

    /**
     * 固定高度的可滚动代码块，返回 TextView 供调用方更新内容。
     * 节点 dump 动辄几百行，不能靠 maxLines 截断 —— 那样用户拿不到完整信息。
     */
    fun monoBlockScrollable(parent: LinearLayout, content: String, heightDp: Int): TextView {
        val tv = TextView(context).apply {
            text = content
            setTextColor(codeFg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(codeBg, dp(10))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            addView(tv)
            background = rounded(codeBg, dp(10))
        }
        parent.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(heightDp)
        ).apply { topMargin = dp(10) })
        return tv
    }

    fun input(parent: LinearLayout, hint: String, initial: String = ""): EditText {
        val edit = EditText(context).apply {
            this.hint = hint
            setText(initial)
            setTextColor(onSurface)
            setHintTextColor(onSurfaceFaint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(surfaceVariant, dp(12))
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        parent.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        return edit
    }

    fun rounded(fillColor: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fillColor)
        }

    fun adjustAlpha(color: Int, factor: Float): Int = Color.argb(
        (Color.alpha(color) * factor).roundToInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
