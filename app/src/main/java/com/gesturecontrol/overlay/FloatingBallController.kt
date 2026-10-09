package com.gesturecontrol.overlay

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.Command
import com.gesturecontrol.core.CommandBus
import com.gesturecontrol.core.RuntimeState
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮控制面板。
 *
 * 真机调试时最有用：不用语音、不用隔空手势，直接点按钮就能验证无障碍链路。
 * 拖动小球移动位置，单击展开/收起命令面板，长按打开 App。
 *
 * 这里刻意**不用 Material 组件**：Service 的 Context 在部分 ROM 上拿不到
 * 应用主题，而 Material 组件对主题很敏感，一旦取不到就会崩。所以用轻量的
 * TextView + 自绘圆角背景，只靠配色和间距做出层次，保证在任何 Context 下都能画出来。
 */
class FloatingBallController(
    private val context: Context,
    /** 真实运行状态由 ControlService 提供，悬浮球不自己猜 */
    private val stateProvider: () -> RuntimeState,
    private val onOpenApp: () -> Unit,
    private val onToggleVoice: () -> Boolean,
    private val onToggleAir: () -> Boolean
) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var statusView: TextView? = null
    private var panel: LinearLayout? = null
    private var stateView: TextView? = null
    private var debugView: TextView? = null

    @Volatile
    private var expanded = false

    // ---------------------------------------------------------------- 配色
    // 和 ui/UiKit 那套保持一致，避免两处界面看起来不像一个 App。
    // 悬浮窗不受 Activity 主题影响，所以这里自己判断系统深浅色。
    private val isNight: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val surface get() = if (isNight) 0xFF171B22.toInt() else 0xFFFFFFFF.toInt()
    private val statusBg get() = if (isNight) 0xF01E232C.toInt() else 0xF2FFFFFF.toInt()
    private val outline get() = if (isNight) 0xFF2E3644.toInt() else 0xFFD8DEE7.toInt()
    private val primary get() = if (isNight) 0xFF5B9DFF.toInt() else 0xFF2563EB.toInt()
    private val success get() = if (isNight) 0xFF3DDC97.toInt() else 0xFF1B7F4B.toInt()
    private val warning get() = if (isNight) 0xFFFFB84D.toInt() else 0xFFA15C00.toInt()
    private val danger get() = if (isNight) 0xFFFF6B6B.toInt() else 0xFFC62828.toInt()
    private val onSurface get() = if (isNight) 0xFFE8ECF4.toInt() else 0xFF16191F.toInt()
    private val onSurfaceVariant get() = if (isNight) 0xFF96A0B0.toInt() else 0xFF5A6472.toInt()
    private val onSurfaceFaint get() = if (isNight) 0xFF6B7686.toInt() else 0xFF8B94A2.toInt()
    private val pressBase get() = if (isNight) 0x14FFFFFF else 0x0D000000
    private val pressDown get() = if (isNight) 0x33FFFFFF else 0x1A000000

    /** 系统深浅色变了要把悬浮球重建一遍，否则它会一直停在旧配色 */
    private var listening = false

    private val configListener = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            handler.post { rebuild() }
        }

        override fun onLowMemory() = Unit
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(6)
        y = dp(150)
    }

    private val hideStatus = Runnable { statusView?.visibility = View.GONE }

    val isShowing: Boolean get() = root != null

    /** 面板是否展开。ControlService 靠它决定要不要算隔空手势的调试字符串 */
    val isExpanded: Boolean get() = expanded

    // ------------------------------------------------------------ 显示/隐藏

    fun show() {
        if (root != null) return
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        // 状态提示：执行结果 / 语音识别中间结果
        val status = TextView(context).apply {
            setTextColor(onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(statusBg, dp(12), outline)
            visibility = View.GONE
            maxWidth = dp(230)
            includeFontPadding = false
        }
        container.addView(status, wrapParams(bottom = dp(8)))
        statusView = status

        container.addView(makeBall())
        // 直接持有引用，别用 getChildAt 下标 —— 以后往容器里插东西就会错位
        val panelView = makePanel()
        container.addView(panelView, wrapParams(top = dp(8)))
        panel = panelView

        root = container
        if (!listening) {
            context.registerComponentCallbacks(configListener)
            listening = true
        }
        try {
            wm.addView(container, params)
            AppLog.add("Ball", "悬浮球已显示")
        } catch (t: Throwable) {
            root = null
            AppLog.add("Ball", "悬浮球添加失败（多半是没有悬浮窗权限）：${t.message}")
        }
    }

    /** 系统深浅色切换后重建悬浮球，让它换上对应的配色 */
    private fun rebuild() {
        if (root == null) return
        val wasExpanded = expanded
        hide()
        show()
        setExpanded(wasExpanded)
    }

    fun hide() {
        val view = root ?: return
        root = null
        statusView = null
        panel = null
        stateView = null
        debugView = null
        expanded = false
        handler.removeCallbacks(hideStatus)
        if (listening) {
            try {
                context.unregisterComponentCallbacks(configListener)
            } catch (_: Throwable) {
            }
            listening = false
        }
        try {
            wm.removeView(view)
        } catch (_: Throwable) {
        }
        AppLog.add("Ball", "悬浮球已移除")
    }

    // ---------------------------------------------------------------- 小球

    private fun makeBall(): TextView {
        val ball = TextView(context).apply {
            text = "控"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = ballBackground()
        }
        val size = dp(58)
        ball.layoutParams = LinearLayout.LayoutParams(size, size)
        attachDrag(ball)
        return ball
    }

    private fun ballBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(primary)
        setStroke(dp(3), 0x33FFFFFF)
    }

    // ---------------------------------------------------------------- 面板

    private fun makePanel(): LinearLayout {
        val panelView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(12))
            background = rounded(surface, dp(18), outline)
            visibility = View.GONE
        }

        // 头部：标题 + 当前状态
        panelView.addView(
            TextView(context).apply {
                text = "隔空控制"
                setTextColor(onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            },
            wrapParams(bottom = dp(2), start = dp(4))
        )
        val state = TextView(context).apply {
            setTextColor(onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
            maxWidth = dp(210)
        }
        panelView.addView(state, wrapParams(bottom = dp(8), start = dp(4)))
        stateView = state

        // 命令区（可滚动，命令多了不会顶出屏幕）
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                panelMaxHeight()
            )
        }
        val commands = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(commands)
        panelView.addView(scroll)

        commands.addView(sectionLabel("命令"))
        for (cmd in listOf(
            Command.SWIPE_DOWN, Command.SWIPE_UP, Command.LIKE,
            Command.FAVORITE, Command.FOLLOW, Command.ENTER_LIVE,
            Command.EXIT_LIVE, Command.PAUSE, Command.BACK
        )) {
            commands.addView(panelButton(cmd.label) { CommandBus.dispatch(cmd, "悬浮球") })
        }

        commands.addView(sectionLabel("输入通道"))
        commands.addView(panelButton(voiceLabel(), TAG_VOICE) {
            onToggleVoice()
            refreshToggles()
        })
        commands.addView(panelButton(airLabel(), TAG_AIR) {
            onToggleAir()
            refreshToggles()
        })

        commands.addView(sectionLabel("其他"))
        commands.addView(panelButton("设置 / 日志") { onOpenApp() })

        val debug = TextView(context).apply {
            setTextColor(onSurfaceFaint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(6), dp(6), dp(6), 0)
            maxWidth = dp(210)
            text = "展开面板时，隔空手势的实时数据会显示在这里"
        }
        commands.addView(debug)
        debugView = debug

        refreshToggles()
        return panelView
    }

    private fun sectionLabel(value: String): TextView = TextView(context).apply {
        text = value
        setTextColor(onSurfaceFaint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setPadding(dp(6), dp(10), dp(6), dp(4))
        includeFontPadding = false
    }

    private fun panelButton(
        label: String,
        tag: String? = null,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        this.tag = tag
        setTextColor(onSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        includeFontPadding = false
        background = pressed(dp(10))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = wrapParams(top = dp(3))
    }

    private fun voiceLabel() = stateProvider().voiceLabel

    private fun airLabel() = stateProvider().airLabel

    fun refreshToggles() {
        handler.post {
            val p = panel ?: return@post
            p.findViewWithTag<TextView>(TAG_VOICE)?.text = voiceLabel()
            p.findViewWithTag<TextView>(TAG_AIR)?.text = airLabel()
            val state = stateProvider()
            stateView?.text = state.summary()
            stateView?.setTextColor(
                when {
                    !state.accessibility -> danger
                    state.anyPaused -> warning
                    else -> success
                }
            )
        }
    }

    // ---------------------------------------------------------------- 状态

    fun showStatus(text: String) {
        handler.post {
            val v = statusView ?: return@post
            v.text = text
            v.visibility = View.VISIBLE
            handler.removeCallbacks(hideStatus)
            handler.postDelayed(hideStatus, 2600L)
        }
    }

    /**
     * 只在面板展开时更新调试信息。
     * 这里在**调用线程**先判断一次 —— 隔空手势每帧都会调它（约 20 次/秒），
     * 面板收起时不该往主线程投任务。
     */
    fun showDebug(text: String) {
        if (!expanded) return
        handler.post {
            if (!expanded) return@post
            debugView?.text = text
        }
    }

    // -------------------------------------------------------------- 拖动

    private fun attachDrag(ball: View) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var moved = false
        var downAt = 0L
        val slop = dp(6)

        ball.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    downAt = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        root?.let {
                            try {
                                wm.updateViewLayout(it, params)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val held = System.currentTimeMillis() - downAt
                    if (!moved) {
                        if (held >= 600L) {
                            onOpenApp()
                        } else {
                            setExpanded(!expanded)
                        }
                    }
                    view.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        panel?.visibility = if (value) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------- 工具

    private fun wrapParams(
        top: Int = 0,
        bottom: Int = 0,
        start: Int = 0
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = top
        bottomMargin = bottom
        marginStart = start
    }

    private fun panelMaxHeight(): Int {
        val metrics = context.resources.displayMetrics
        val screen = metrics.heightPixels
        return min(dp(400), (screen * 0.62f).roundToInt())
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeColor != 0) setStroke(dp(1), strokeColor)
        }

    /** 按下反馈：不然点面板完全没手感 */
    private fun pressed(radius: Int): StateListDrawable {
        val base = rounded(pressBase, radius)
        val down = rounded(pressDown, radius)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(), base)
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG_VOICE = "toggle_voice"
        const val TAG_AIR = "toggle_air"
    }
}
