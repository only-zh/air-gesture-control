package com.gesturecontrol.ui.pages

import android.content.Intent
import android.widget.LinearLayout
import com.gesturecontrol.core.Prefs
import com.gesturecontrol.service.ControlService
import com.gesturecontrol.ui.AppPage
import com.gesturecontrol.ui.PageHost
import com.gesturecontrol.ui.Prerequisites
import com.gesturecontrol.ui.UiKit
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 启动页。
 *
 * 刻意做得很短：正常使用只需要看到「一个大按钮 + 现在用什么」。
 * 启动方式和目标应用都折起来，默认是**悬浮球 + 抖音**，
 * 想换的人点开再选。前置条件不满足时不直接启动，而是弹窗说明缺什么并把人带去权限页。
 */
class LaunchPage(private val host: PageHost) {

    private val ui: UiKit get() = host.ui

    fun build(column: LinearLayout) {
        val prereqs = Prerequisites(host.activity, host::requestRuntimePermissions)
        val running = ControlService.isRunning()
        val missing = prereqs.blockingMissing()

        headline(column, running, missing.size)
        startButton(column, running)
        currentConfig(column)
        launchModes(column)
        targetApp(column)
    }

    // ---------------------------------------------------------------- 头部

    private fun headline(column: LinearLayout, running: Boolean, missingCount: Int) {
        val (text, color) = when {
            running -> "运行中 · 可以切到抖音用了" to ui.success
            missingCount > 0 -> "还差 $missingCount 项配置" to ui.warning
            else -> "已就绪 · 点下面启动" to ui.onSurfaceVariant
        }
        column.addView(
            ui.text(text, 13f, color).apply {
                setPadding(0, ui.dp(6), 0, ui.dp(14))
            }
        )
    }

    private fun startButton(column: LinearLayout, running: Boolean) {
        val wrap = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
        ui.button(
            wrap,
            if (running) "停止" else "启动",
            if (running) UiKit.ButtonStyle.TONAL else UiKit.ButtonStyle.PRIMARY
        ) {
            if (running) {
                ControlService.stop(host.activity)
                host.activity.window.decorView.postDelayed({ host.refreshCurrentPage() }, 400L)
            } else {
                onStartClicked()
            }
        }
        column.addView(wrap)
    }

    private fun onStartClicked() {
        val missing = Prerequisites(host.activity, host::requestRuntimePermissions).blockingMissing()
        if (missing.isEmpty()) {
            ControlService.start(host.activity)
            host.activity.window.decorView.postDelayed({ host.refreshCurrentPage() }, 500L)
            return
        }

        // 不满足前置条件：说清楚缺什么，并直接把人带到权限页
        MaterialAlertDialogBuilder(host.activity)
            .setTitle("还差 ${missing.size} 项配置")
            .setMessage(missing.joinToString("\n") { "· ${it.title}：${it.detail}" })
            .setPositiveButton("去配置") { _, _ -> host.openPage(AppPage.PERMISSIONS) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun currentConfig(column: LinearLayout) {
        val modes = buildList {
            if (Prefs.ballEnabled) add("悬浮球")
            if (Prefs.voiceEnabled) add("语音")
            if (Prefs.airEnabled) add("隔空手势")
        }
        val appName = when (Prefs.douyinPackage) {
            Prefs.DOUYIN_LITE -> "抖音极速版"
            else -> "抖音"
        }
        val summary = if (modes.isEmpty()) {
            "⚠ 一个启动方式都没开"
        } else {
            modes.joinToString(" + ") + " · " + appName
        }
        column.addView(
            ui.text(summary, 12f, if (modes.isEmpty()) ui.warning else ui.onSurfaceVariant)
                .apply { setPadding(0, ui.dp(12), 0, 0) }
        )
    }

    // ------------------------------------------------------------ 启动方式

    private fun launchModes(column: LinearLayout) {
        val wrap = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "启动方式", "默认只用悬浮球，最省电也最省事",
            collapsible = true,
            expanded = host.sectionExpanded("launch_modes", false),
            onToggle = host.rememberSection("launch_modes")
        ) { body ->
            ui.switchRow(
                body, "悬浮球控制面板",
                "点小球展开命令按钮，可以直接验证链路",
                Prefs.ballEnabled
            ) {
                Prefs.ballEnabled = it
                ControlService.refresh()
            }
            ui.switchRow(
                body, "语音控制",
                "离线识别，只在抖音前台且屏幕点亮时开麦克风",
                Prefs.voiceEnabled
            ) {
                Prefs.voiceEnabled = it
                ControlService.refresh()
            }
            ui.switchRow(
                body, "隔空手势",
                "前置摄像头常开，是本 App 最大的耗电源",
                Prefs.airEnabled
            ) {
                Prefs.airEnabled = it
                ControlService.refresh()
            }
        }
        column.addView(wrap)
    }

    // ------------------------------------------------------------ 目标应用

    private fun targetApp(column: LinearLayout) {
        val wrap = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
        val options = listOf(
            Prefs.DEFAULT_DOUYIN to "抖音",
            Prefs.DOUYIN_LITE to "抖音极速版"
        )
        val current = options.firstOrNull { it.first == Prefs.douyinPackage }?.second
            ?: Prefs.douyinPackage

        ui.section(
            wrap, "目标应用", "当前：$current",
            collapsible = true,
            expanded = host.sectionExpanded("launch_target", false),
            onToggle = host.rememberSection("launch_target")
        ) { body ->
            options.forEach { (pkg, label) ->
                val selected = Prefs.douyinPackage == pkg
                ui.navRow(
                    body, label, pkg,
                    leading = if (selected) "●" else "○",
                    accent = if (selected) ui.primary else null
                ) {
                    Prefs.douyinPackage = pkg
                    host.refreshCurrentPage()
                }
            }
            ui.divider(body)
            ui.button(body, "启动目标 App", UiKit.ButtonStyle.TONAL) { launchTarget() }
        }
        column.addView(wrap)
    }

    private fun launchTarget() {
        val intent = host.activity.packageManager.getLaunchIntentForPackage(Prefs.douyinPackage)
        if (intent == null) {
            host.toast("没有找到 ${Prefs.douyinPackage}，请确认已安装")
            return
        }
        try {
            host.activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            host.toast("打不开：${t.message}")
        }
    }
}
