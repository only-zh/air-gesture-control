package com.gesturecontrol.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gesturecontrol.a11y.ControlAccessibilityService
import com.gesturecontrol.a11y.NodeFinder
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.Command
import com.gesturecontrol.core.CommandBus
import com.gesturecontrol.core.Prefs
import com.gesturecontrol.ui.UiKit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 节点探测 / 校准页。
 *
 * 抖音改版以后原来的文案可能就找不到了。这一页可以：
 *   1. 把当前抖音页面的全部节点文案、id、坐标 dump 出来
 *   2. 直接试某个关键词能不能定位到控件
 *   3. 手动触发每个命令，验证坐标对不对
 * 有了它，适配新版本不需要改代码。
 */
class NodeInspectorActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: UiKit
    private lateinit var column: LinearLayout
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        val (scroll, content) = ui.screen()
        column = content
        setContentView(scroll)
        build()
    }

    private fun build() {
        column.removeAllViews()

        column.addView(
            ui.text("节点探测 / 校准", 22f, ui.onSurface, bold = true)
                .apply { setPadding(0, ui.dp(14), 0, 0) }
        )

        val connected = ControlAccessibilityService.isConnected()
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(ui.pill(if (connected) "无障碍 ✓" else "无障碍 ✗", connected))
        strip.addView(
            ui.pill("目标：${Prefs.douyinPackage.substringAfterLast('.')}", true),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = ui.dp(6) }
        )
        column.addView(strip.apply { setPadding(0, ui.dp(10), 0, 0) })

        column.addView(
            ui.text(
                "本页自己占着前台，所以抓取会优先去找抖音自己的窗口。" +
                    "如果抓不到，先点「3 秒后抓取」，再切到抖音，3 秒后切回来即可。",
                12f, ui.onSurfaceVariant
            ).apply { setPadding(0, ui.dp(10), 0, 0) }
        )

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.button(actions, "立即抓取当前页面节点", UiKit.ButtonStyle.PRIMARY) { dumpNow() }
        ui.button(actions, "3 秒后抓取（先切到抖音）", UiKit.ButtonStyle.TONAL) {
            toast("3 秒后抓取，请现在切到抖音")
            handler.postDelayed({ dumpNow() }, 3000L)
        }
        column.addView(actions)

        // ---------------------------------------------------------- 关键词定位
        val keywordSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.section(keywordSection, "关键词定位", "试试某个文案能不能找到可点击控件") { body ->
            val input = ui.input(body, "关键词，例如 喜欢 / 收藏 / 直播", "喜欢")
            ui.button(body, "定位", UiKit.ButtonStyle.PRIMARY) {
                val keyword = input.text.toString().trim()
                if (keyword.isEmpty()) toast("先输入关键词") else findKeyword(keyword)
            }
        }
        column.addView(keywordSection)

        // ---------------------------------------------------------------- 输出
        val outputSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.section(outputSection, "抓取结果", "节点多了会很长，可以上下滚动、长按选中") { body ->
            output = ui.monoBlockScrollable(body, "（点上面的按钮开始抓取）", 320)
            ui.buttonGrid(
                body,
                listOf(
                    "复制内容" to {
                        getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("nodes", output.text))
                        toast("已复制")
                    },
                    "保存到文件" to { saveToFile() }
                )
            )
        }
        column.addView(outputSection)

        // ------------------------------------------------------------ 手动触发
        val manual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.section(
            manual, "手动触发命令", "先切到抖音再点，用来验证坐标对不对",
            collapsible = true, expanded = true
        ) { body ->
            ui.buttonGrid(
                body,
                Command.mappable.map { cmd ->
                    cmd.label to {
                        val ok = CommandBus.dispatch(cmd, "探测页手动")
                        toast(if (ok) "已派发：${cmd.label}" else "失败或冷却中，看日志")
                    }
                }
            )
        }
        column.addView(manual)
    }

    // ---------------------------------------------------------------- 抓取

    private fun dumpNow() {
        val service = ControlAccessibilityService.instance
        if (service == null) {
            output.text = "无障碍服务没有连接。\n\n请到 系统设置 → 无障碍 → 隔空控制，把它打开。"
            return
        }
        try {
            val text = NodeFinder.dumpHierarchy(service, limit = 800)
            output.text = text
            AppLog.add("Inspector", "抓取节点完成，共 ${text.length} 字符")
        } catch (t: Throwable) {
            output.text = "抓取失败：${t.javaClass.simpleName} ${t.message}"
            AppLog.add("Inspector", "抓取失败：${t.message}")
        }
    }

    private fun findKeyword(keyword: String) {
        val service = ControlAccessibilityService.instance
        if (service == null) {
            output.text = "无障碍服务没有连接"
            return
        }
        val hit = NodeFinder.findClickable(service, listOf(keyword))
        output.text = if (hit == null) {
            "没有找到包含「$keyword」的可点击控件。\n\n" +
                "可能原因：\n" +
                "  · 抖音把按钮文案改成了别的说法\n" +
                "  · 按钮不是 clickable 节点\n\n" +
                "这种情况就改「参数微调」里的 X/Y 比例来兜底。"
        } else {
            "命中：\n" +
                "  文案 = ${hit.label}\n" +
                "  节点 = ${hit.nodeId}\n" +
                "  坐标 = ${hit.rect}\n\n" +
                "可以点下面的命令按钮直接试效果。"
        }
        AppLog.add("Inspector", "关键词「$keyword」定位结果：${hit?.label ?: "未命中"}")
    }

    private fun saveToFile() {
        try {
            val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "nodes-$stamp.txt")
            file.writeText(output.text.toString())
            toast("已保存到 ${file.absolutePath}")
        } catch (t: Throwable) {
            toast("保存失败：${t.message}")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
