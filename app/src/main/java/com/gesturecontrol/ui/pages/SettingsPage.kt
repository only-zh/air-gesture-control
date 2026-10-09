package com.gesturecontrol.ui.pages

import android.content.Intent
import android.widget.LinearLayout
import com.gesturecontrol.App
import com.gesturecontrol.core.Prefs
import com.gesturecontrol.debug.NodeInspectorActivity
import com.gesturecontrol.ui.PageHost
import com.gesturecontrol.ui.UiKit

/**
 * 自定义设置页：参数微调 + 校准 + 外观。
 *
 * 「校准」单独成块而不是塞进参数里 —— 抖音改版后要先探测出新的文案和坐标，
 * 才知道该调哪个滑块，这两件事是有先后的。
 */
class SettingsPage(private val host: PageHost) {

    private val ui: UiKit get() = host.ui

    fun build(column: LinearLayout) {
        calibrationSection(column)
        tuningSection(column)
        appearanceSection(column)
    }

    // ---------------------------------------------------------------- 校准

    private fun calibrationSection(column: LinearLayout) {
        val wrap = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "校准", "抖音改版后按钮点不中，先来这里",
            collapsible = true,
            expanded = host.sectionExpanded("set_calib", true),
            onToggle = host.rememberSection("set_calib")
        ) { body ->
            ui.button(body, "节点探测 / 校准", UiKit.ButtonStyle.PRIMARY) {
                host.activity.startActivity(
                    Intent(host.activity, NodeInspectorActivity::class.java)
                )
            }
            ui.caption(
                body,
                "把当前抖音页面的控件文案、id、坐标全部导出来；" +
                    "再用「关键词定位」试出可用的关键词。改文案需要重编译，改坐标不用。"
            )
        }
        column.addView(wrap)
    }

    // ------------------------------------------------------------ 参数微调

    private fun tuningSection(column: LinearLayout) {
        val wrap = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "参数微调", "改完立即生效，可以边看抖音边调",
            collapsible = true,
            expanded = host.sectionExpanded("set_tuning", false),
            onToggle = host.rememberSection("set_tuning")
        ) { body ->
            ui.caption(body, "滑动与点击坐标")
            ui.slider(body, "滑动时长 (ms)", Prefs.swipeDurationMs.toFloat(), 80, 600, 10, 1f, 0) {
                Prefs.swipeDurationMs = it.toInt()
            }
            ui.slider(body, "点赞按钮 Y", Prefs.likeY, 30, 95, 1, 0.01f, 2) { Prefs.likeY = it }
            ui.slider(body, "收藏按钮 Y", Prefs.favoriteY, 30, 95, 1, 0.01f, 2) {
                Prefs.favoriteY = it
            }
            ui.slider(body, "关注按钮 Y", Prefs.followY, 20, 90, 1, 0.01f, 2) {
                Prefs.followY = it
            }
            ui.slider(body, "操作栏 X", Prefs.actionXRatio, 70, 99, 1, 0.01f, 2) {
                Prefs.actionXRatio = it
            }

            ui.divider(body)
            ui.caption(body, "语音")
            ui.slider(body, "匹配阈值", Prefs.matchThreshold, 40, 99, 1, 0.01f, 2) {
                Prefs.matchThreshold = it
            }
            ui.caption(body, "越低越容易命中，也越容易误触发。听不懂就调低，误触发多就调高。")
            ui.slider(
                body, "静默暂停（秒）",
                (Prefs.voiceIdleStopMs / 1000).toFloat(), 0, 600, 10, 1f, 0
            ) { Prefs.voiceIdleStopMs = it.toInt() * 1000 }
            ui.caption(body, "多久没命中命令就自动停听音。0 = 不停（默认）。")

            ui.divider(body)
            ui.caption(body, "隔空手势")
            ui.slider(body, "挥动阈值", Prefs.airSwipeThreshold, 8, 45, 1, 0.01f, 2) {
                Prefs.airSwipeThreshold = it
            }
            ui.slider(body, "捏合阈值", Prefs.airPinchThreshold, 20, 80, 1, 0.01f, 2) {
                Prefs.airPinchThreshold = it
            }

            ui.divider(body)
            ui.caption(body, "节流")
            ui.slider(body, "节流倍率", Prefs.throttleScale, 10, 40, 1, 0.1f, 1) {
                Prefs.throttleScale = it
            }
            ui.caption(body, "被平台限流时调大它。")

            ui.divider(body)
            ui.switchRow(
                body, "优先用节点定位", "关掉的话所有点击都直接走坐标",
                Prefs.useNodeFirst
            ) { Prefs.useNodeFirst = it }
            ui.switchRow(
                body, "找不到点赞按钮时双击屏幕", null,
                Prefs.doubleTapToLike
            ) { Prefs.doubleTapToLike = it }
            ui.switchRow(
                body, "悬浮球显示执行结果", null,
                Prefs.overlayToast
            ) { Prefs.overlayToast = it }

            ui.button(body, "恢复默认参数", UiKit.ButtonStyle.TONAL) {
                Prefs.resetTuning()
                host.refreshCurrentPage()
                host.toast("已恢复默认")
            }
        }
        column.addView(wrap)
    }

    // ---------------------------------------------------------------- 外观

    private fun appearanceSection(column: LinearLayout) {
        val wrap = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "外观", null,
            collapsible = true,
            expanded = host.sectionExpanded("set_theme", false),
            onToggle = host.rememberSection("set_theme")
        ) { body ->
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
                    // AppCompat 会自动重建 Activity，界面立即切换
                    App.applyTheme()
                }
            }
        }
        column.addView(wrap)
    }
}
