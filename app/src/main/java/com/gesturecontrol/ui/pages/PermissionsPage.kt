package com.gesturecontrol.ui.pages

import android.widget.LinearLayout
import com.gesturecontrol.ui.PageHost
import com.gesturecontrol.ui.Prerequisites
import com.gesturecontrol.ui.UiKit

/**
 * 权限页：三个前置条件的集中配置处。
 *
 * 启动页发现条件不满足时会把用户带到这里。每一条都可以直接点，
 * 跳对应的系统设置页或者弹运行时权限申请。
 */
class PermissionsPage(private val host: PageHost) {

    private val ui: UiKit get() = host.ui

    fun build(column: LinearLayout) {
        val items = Prerequisites(host.activity, host::requestRuntimePermissions).check()
        val blocking = items.filter { it.blocking }
        val done = blocking.count { it.satisfied }

        val (headline, color) = when {
            done == blocking.size -> "前置条件都已配好" to ui.success
            else -> "已完成 $done / ${blocking.size} 项" to ui.warning
        }
        column.addView(
            ui.text(headline, 13f, color).apply { setPadding(0, ui.dp(6), 0, ui.dp(4)) }
        )
        column.addView(
            ui.text(
                "需要哪些条件取决于开了哪些启动方式 —— 只用悬浮球的话，只要无障碍和悬浮窗就够了。",
                11f, ui.onSurfaceFaint
            ).apply { setPadding(0, 0, 0, ui.dp(14)) }
        )

        // 必配项
        val required = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
        ui.section(required, "必须配置", "不满足就没法启动") { body ->
            blocking.forEach { item ->
                ui.navRow(
                    body,
                    item.title,
                    item.detail,
                    trailing = if (item.satisfied) "已完成" else "去配置",
                    trailingOk = item.satisfied,
                    accent = if (item.satisfied) null else ui.primary
                ) { item.action() }
            }
        }
        column.addView(required)

        // 建议项
        val optional = items.filter { !it.blocking }
        if (optional.isNotEmpty()) {
            val suggested = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
            ui.section(suggested, "建议配置", "不影响启动，但会影响稳定性和可见性") { body ->
                optional.forEach { item ->
                    ui.navRow(
                        body,
                        item.title,
                        item.detail,
                        trailing = if (item.satisfied) "已完成" else "去配置",
                        trailingOk = item.satisfied,
                        accent = if (item.satisfied) null else ui.onSurfaceVariant
                    ) { item.action() }
                }
            }
            column.addView(suggested)
        }

        val refresh = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL }
        ui.section(refresh, "从系统设置页回来后", "状态不会自动刷新，点一下重新检测") { body ->
            ui.button(body, "重新检测", UiKit.ButtonStyle.TONAL) { host.refreshCurrentPage() }
        }
        column.addView(refresh)
    }
}
