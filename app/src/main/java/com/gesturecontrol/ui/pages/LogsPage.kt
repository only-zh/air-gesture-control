package com.gesturecontrol.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.LinearLayout
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.Command
import com.gesturecontrol.core.CommandBus
import com.gesturecontrol.ui.PageHost
import com.gesturecontrol.ui.UiKit

/**
 * 日志页：命令测试 + 运行日志。
 *
 * 这两件事放一起是有意为之 —— 点完测试按钮立刻就能看到日志里写了什么，
 * 排查时不用来回切页面。
 */
class LogsPage(private val host: PageHost) {

    private val ui: UiKit get() = host.ui

    fun build(column: LinearLayout) {
        testSection(column)
        logSection(column)
    }

    // ---------------------------------------------------------------- 测试

    private fun testSection(column: LinearLayout) {
        val wrap = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "命令测试", "先切到抖音再点，用来验证无障碍链路是否还通",
            collapsible = true,
            expanded = host.sectionExpanded("logs_test", true),
            onToggle = host.rememberSection("logs_test")
        ) { body ->
            ui.buttonGrid(
                body,
                Command.mappable.map { cmd ->
                    cmd.label to {
                        val ok = CommandBus.dispatch(cmd, "设置页手动")
                        host.toast(
                            if (ok) "已派发：${cmd.label}"
                            else "派发失败或冷却中 —— 看下面的日志"
                        )
                        // 日志会立即多一条，刷新一下让用户看到
                        host.activity.window.decorView.postDelayed(
                            { host.refreshCurrentPage() }, 300L
                        )
                    }
                }
            )
        }
        column.addView(wrap)
    }

    // ---------------------------------------------------------------- 日志

    private fun logSection(column: LinearLayout) {
        val wrap = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            wrap, "运行日志", "最近 40 条：每条命令是谁触发、成功还是失败",
            collapsible = true,
            expanded = host.sectionExpanded("logs_log", true),
            onToggle = host.rememberSection("logs_log")
        ) { body ->
            val lines = AppLog.tail(40)
            ui.monoBlock(
                body,
                lines.joinToString("\n").ifEmpty { "（暂无日志）" }
            )
            ui.buttonGrid(
                body,
                listOf(
                    "复制全部日志" to {
                        host.activity.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("gesture-log", AppLog.dump()))
                        host.toast("日志已复制到剪贴板")
                    },
                    "清空日志" to {
                        AppLog.clear()
                        host.refreshCurrentPage()
                    }
                )
            )
        }
        column.addView(wrap)
    }
}
