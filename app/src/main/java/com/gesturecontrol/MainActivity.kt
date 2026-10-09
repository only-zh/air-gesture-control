package com.gesturecontrol

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gesturecontrol.a11y.ControlAccessibilityService
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.Command
import com.gesturecontrol.core.CommandBus
import com.gesturecontrol.core.Prefs
import com.gesturecontrol.debug.NodeInspectorActivity
import com.gesturecontrol.service.ControlService
import com.gesturecontrol.ui.UiKit

class MainActivity : AppCompatActivity() {

    private lateinit var ui: UiKit
    private lateinit var column: LinearLayout

    /** 折叠状态要在 onResume 重建界面后保留，否则用户一回到这个页面分区就都弹开了 */
    private val expandedState = mutableMapOf<String, Boolean>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        toast(if (denied.isEmpty()) "权限已授予" else "被拒绝：${denied.joinToString()}")
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        val (scroll, content) = ui.screen()
        column = content
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ================================================================ 渲染

    private fun expanded(key: String, default: Boolean): Boolean =
        expandedState[key] ?: default

    private fun remember(key: String): (Boolean) -> Unit = { expandedState[key] = it }

    private fun render() {
        column.removeAllViews()
        val health = health()

        hero(health)
        if (!health.ready) todoSection(health)
        switchesSection()
        targetSection()
        quickTestSection()
        tuningSection()
        toolsSection()
        logSection()
    }

    // ------------------------------------------------------------ 顶部总览

    private data class Health(
        val accessibility: Boolean,
        val overlay: Boolean,
        val permissions: Boolean,
        val service: Boolean
    ) {
        val flags: List<Pair<String, Boolean>> get() = listOf(
            "无障碍" to accessibility,
            "悬浮窗" to overlay,
            "权限" to permissions,
            "服务" to service
        )
        val done: Int get() = flags.count { it.second }
        val total: Int get() = flags.size
        val ready: Boolean get() = done == total
    }

    private fun health(): Health = Health(
        accessibility = ControlAccessibilityService.isConnected(),
        overlay = Settings.canDrawOverlays(this),
        permissions = hasPermission(Manifest.permission.RECORD_AUDIO) &&
            hasPermission(Manifest.permission.CAMERA),
        service = ControlService.isRunning()
    )

    private fun hero(health: Health) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(14), 0, 0)
        }
        header.addView(
            ui.text("隔空控制", 26f, ui.onSurface, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(ui.text("v${BuildConfig.VERSION_NAME}", 12f, ui.onSurfaceFaint))
        column.addView(header)

        val headline = when {
            health.ready -> "已就绪 —— 打开抖音就能用"
            health.accessibility -> "还差 ${health.total - health.done} 项配置就能用"
            else -> "先开启无障碍服务，否则一切动作都发不出去"
        }
        column.addView(
            ui.text(
                headline,
                13f,
                when {
                    health.ready -> ui.success
                    health.accessibility -> ui.warning
                    else -> ui.danger
                }
            ).apply { setPadding(0, ui.dp(6), 0, 0) }
        )

        // 状态胶囊横向可滑，避免窄屏换行难看
        val pillStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        health.flags.forEach { (name, ok) ->
            pillStrip.addView(
                ui.pill(if (ok) "$name ✓" else "$name ✗", ok),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = ui.dp(6) }
            )
        }
        column.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, ui.dp(12), 0, 0)
                addView(pillStrip)
            }
        )

        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.button(
            wrap,
            if (health.service) "停止控制服务" else "启动控制服务",
            if (health.service) UiKit.ButtonStyle.TONAL else UiKit.ButtonStyle.PRIMARY
        ) {
            if (ControlService.isRunning()) ControlService.stop(this) else ControlService.start(this)
            column.postDelayed({ render() }, 500L)
        }
        column.addView(wrap)
    }

    // ------------------------------------------------------------ 待配置项

    private fun todoSection(health: Health) {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap,
            "还差 ${health.total - health.done} 项配置",
            "按顺序点下面每一项去授权"
        ) { body ->
            if (!health.accessibility) {
                ui.navRow(
                    body, "开启无障碍服务", "必须开启，这是唯一能注入手势的通道", leading = "①"
                ) { openAccessibilitySettings() }
            }
            if (!Settings.canDrawOverlays(this)) {
                ui.navRow(body, "允许悬浮窗", "用于显示悬浮球控制面板", leading = "②") {
                    openOverlaySettings()
                }
            }
            if (!health.permissions) {
                ui.navRow(
                    body, "授予麦克风 / 相机 / 通知",
                    "语音要用麦克风，隔空手势要用相机", leading = "③"
                ) { requestAppPermissions() }
            }
            if (!ControlService.isRunning()) {
                ui.navRow(body, "启动常驻控制服务", "启动后悬浮球和语音才会工作", leading = "④") {
                    ControlService.start(this)
                    column.postDelayed({ render() }, 500L)
                }
            }
            ui.divider(body, topDp = 12, bottomDp = 6)
            ui.navRow(body, "加入电池优化白名单", "不加的话国产 ROM 会在后台杀掉本应用") {
                openBatterySettings()
            }
        }
        column.addView(wrap)
    }

    // ------------------------------------------------------------ 功能开关

    private fun switchesSection() {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "功能开关", "三种输入通道可以独立开关",
            collapsible = true,
            expanded = expanded("switches", true),
            onToggle = remember("switches")
        ) { body ->
            ui.switchRow(
                body, "悬浮球控制面板",
                "点小球展开命令按钮，可以直接验证无障碍链路",
                Prefs.ballEnabled
            ) {
                Prefs.ballEnabled = it
                ControlService.refresh()
            }
            ui.switchRow(
                body, "语音控制（阶段一）",
                "离线识别，${Prefs.voiceIdleStopMs / 1000} 秒没有命令会自动停，省电",
                Prefs.voiceEnabled
            ) {
                Prefs.voiceEnabled = it
                ControlService.refresh()
            }
            ui.switchRow(
                body, "隔空手势（阶段二）",
                "前置摄像头常开，发热耗电明显，建议只在需要时打开",
                Prefs.airEnabled
            ) {
                Prefs.airEnabled = it
                ControlService.refresh()
            }
            ui.divider(body)
            ui.switchRow(
                body, "只在目标 App 前台时响应",
                "除了挡住误触发，它还会在切走时暂停摄像头和麦克风 —— 最省电的一项",
                Prefs.targetOnly
            ) { Prefs.targetOnly = it }
            ui.switchRow(body, "悬浮球显示执行结果", null, Prefs.overlayToast) {
                Prefs.overlayToast = it
            }

            ui.divider(body)
            ui.caption(body, "外观")
            listOf(
                Prefs.THEME_SYSTEM to "跟随系统",
                Prefs.THEME_LIGHT to "始终浅色",
                Prefs.THEME_DARK to "始终深色"
            ).forEach { (mode, label) ->
                val selected = Prefs.themeMode == mode
                ui.navRow(
                    body, label, null,
                    leading = if (selected) "●" else "○",
                    accent = if (selected) ui.primary else null
                ) {
                    Prefs.themeMode = mode
                    // AppCompat 会自动重建当前 Activity，界面立即切换，不用手动刷新
                    App.applyTheme()
                }
            }
        }
        column.addView(wrap)
    }

    // ------------------------------------------------------------ 目标应用

    private fun targetSection() {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val options = listOf(
            Prefs.DEFAULT_DOUYIN to "抖音",
            Prefs.DOUYIN_LITE to "抖音极速版"
        )
        val current = options.firstOrNull { it.first == Prefs.douyinPackage }?.second
            ?: Prefs.douyinPackage
        ui.section(
            wrap, "目标应用", "当前：$current",
            collapsible = true,
            expanded = expanded("target", true),
            onToggle = remember("target")
        ) { body ->
            options.forEach { (pkg, label) ->
                val selected = Prefs.douyinPackage == pkg
                val installed = isInstalled(pkg)
                ui.navRow(
                    body,
                    label,
                    pkg + if (installed) "" else "（未安装）",
                    leading = if (selected) "●" else "○",
                    accent = if (selected) ui.primary else null
                ) {
                    Prefs.douyinPackage = pkg
                    render()
                }
            }
            ui.divider(body)
            ui.button(body, "启动目标 App", UiKit.ButtonStyle.TONAL) { launchTarget() }
        }
        column.addView(wrap)
    }

    // ------------------------------------------------------------ 快捷测试

    private fun quickTestSection() {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "快捷测试",
            "先打开抖音再点这里，用来验证无障碍链路是否通了",
            collapsible = true,
            expanded = expanded("test", false),
            onToggle = remember("test")
        ) { body ->
            ui.buttonGrid(
                body,
                Command.mappable.map { cmd ->
                    cmd.label to {
                        val ok = CommandBus.dispatch(cmd, "设置页手动")
                        toast(if (ok) "已派发：${cmd.label}" else "派发失败或冷却中，看日志")
                    }
                }
            )
        }
        column.addView(wrap)
    }

    // ------------------------------------------------------------ 参数微调

    private fun tuningSection() {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "参数微调",
            "抖音改版或换手机后优先调这里，改完立即生效",
            collapsible = true,
            expanded = expanded("tuning", false),
            onToggle = remember("tuning")
        ) { body ->
            ui.slider(body, "滑动时长 (ms)", Prefs.swipeDurationMs.toFloat(), 80, 600, 10, 1f, 0) {
                Prefs.swipeDurationMs = it.toInt()
            }
            ui.slider(body, "点赞按钮 Y", Prefs.likeY, 30, 95, 1, 0.01f, 2) { Prefs.likeY = it }
            ui.slider(body, "收藏按钮 Y", Prefs.favoriteY, 30, 95, 1, 0.01f, 2) { Prefs.favoriteY = it }
            ui.slider(body, "关注按钮 Y", Prefs.followY, 20, 90, 1, 0.01f, 2) { Prefs.followY = it }
            ui.slider(body, "操作栏 X", Prefs.actionXRatio, 70, 99, 1, 0.01f, 2) {
                Prefs.actionXRatio = it
            }
            ui.divider(body)
            ui.slider(body, "语音匹配阈值", Prefs.matchThreshold, 40, 99, 1, 0.01f, 2) {
                Prefs.matchThreshold = it
            }
            ui.slider(body, "节流倍率", Prefs.throttleScale, 10, 40, 1, 0.1f, 1) {
                Prefs.throttleScale = it
            }
            ui.divider(body)
            ui.slider(body, "隔空挥动阈值", Prefs.airSwipeThreshold, 8, 45, 1, 0.01f, 2) {
                Prefs.airSwipeThreshold = it
            }
            ui.slider(body, "隔空捏合阈值", Prefs.airPinchThreshold, 20, 80, 1, 0.01f, 2) {
                Prefs.airPinchThreshold = it
            }
            ui.divider(body)
            ui.caption(body, "语音静默暂停：多久没命中命令就自动停听音。0 = 不停（默认）。")
            ui.slider(
                body, "语音静默暂停（秒）",
                (Prefs.voiceIdleStopMs / 1000).toFloat(), 0, 600, 10, 1f, 0
            ) { Prefs.voiceIdleStopMs = it.toInt() * 1000 }
            ui.button(body, "恢复默认参数", UiKit.ButtonStyle.TONAL) {
                Prefs.resetTuning()
                render()
                toast("已恢复默认")
            }
        }
        column.addView(wrap)
    }

    // ------------------------------------------------------------ 校准工具

    private fun toolsSection() {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "校准与工具", null,
            collapsible = true,
            expanded = expanded("tools", false),
            onToggle = remember("tools")
        ) { body ->
            ui.button(body, "节点探测 / 校准", UiKit.ButtonStyle.PRIMARY) {
                startActivity(Intent(this, NodeInspectorActivity::class.java))
            }
            ui.caption(body, "抖音改版后按钮点不中，就用它把当前页面的控件文案和坐标导出来")
            ui.divider(body)
            ui.navRow(body, "无障碍设置", "系统设置页") { openAccessibilitySettings() }
            ui.navRow(body, "悬浮窗权限", "系统设置页") { openOverlaySettings() }
            ui.navRow(body, "电池优化白名单", "防止后台被杀") { openBatterySettings() }
        }
        column.addView(wrap)
    }

    // ------------------------------------------------------------ 运行日志

    private fun logSection() {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "运行日志", "最近 25 条：每条命令是谁触发、成功还是失败",
            collapsible = true,
            expanded = expanded("log", false),
            onToggle = remember("log")
        ) { body ->
            ui.monoBlock(body, AppLog.tail(25).joinToString("\n").ifEmpty { "（暂无日志）" })
            ui.buttonGrid(
                body,
                listOf(
                    "复制全部日志" to {
                        getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("gesture-log", AppLog.dump()))
                        toast("日志已复制到剪贴板")
                    },
                    "清空日志" to {
                        AppLog.clear()
                        render()
                    }
                )
            )
        }
        column.addView(wrap)
    }

    // -------------------------------------------------------------- 权限

    private fun requestAppPermissions() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(list.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAccessibilitySettings() {
        safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        safeStart(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun openBatterySettings() {
        try {
            safeStart(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Throwable) {
            safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            toast("打不开系统设置页：${t.message}")
        }
    }

    private fun launchTarget() {
        val intent = packageManager.getLaunchIntentForPackage(Prefs.douyinPackage)
        if (intent == null) {
            toast("没有找到 ${Prefs.douyinPackage}，请确认已安装")
            return
        }
        safeStart(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
