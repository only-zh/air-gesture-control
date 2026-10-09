package com.gesturecontrol.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.gesturecontrol.core.AppLog

data class NodeHit(val label: String, val rect: Rect, val nodeId: String)

/**
 * 在无障碍节点树里找可点击控件。
 *
 * 抖音改版很频繁，纯坐标迟早失效，所以优先按文案/描述找：
 *   - contentDescription（点赞/收藏按钮通常在这里）
 *   - text
 *   - viewIdResourceName 的最后一段
 */
object NodeFinder {

    private const val MAX_NODES = 3000
    private const val MAX_DEPTH = 40
    private const val MAX_ANCESTOR_HOPS = 8
    private const val MAX_LABEL_CHARS = 80

    /** 直播间的兜底特征文案 */
    private val LIVE_SIGNALS = listOf(
        "在线人数", "人正在看", "音浪", "魅力值", "送礼物", "礼物面板", "说点什么给主播"
    )

    fun labelOf(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        node.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        node.viewIdResourceName?.let { sb.append(it.substringAfterLast('/')) }
        return sb.toString().trim().take(MAX_LABEL_CHARS)
    }

    fun findClickable(service: AccessibilityService, keywords: List<String>): NodeHit? {
        val root = service.rootInActiveWindow ?: run {
            AppLog.add("Node", "rootInActiveWindow 为空（无障碍服务可能被系统断开）")
            return null
        }
        val counter = intArrayOf(0)
        val hit = search(root, 0, keywords, counter)
        if (hit == null) {
            AppLog.add("Node", "未命中关键词 $keywords（已扫描 ${counter[0]} 个节点）")
        }
        return hit
    }

    /** 只判断是否存在，不点击。给「直播间判定」和探测页用 */
    fun existsKeyword(service: AccessibilityService, keywords: List<String>): String? {
        val root = service.rootInActiveWindow ?: return null
        val counter = intArrayOf(0)
        return searchLabel(root, 0, keywords, counter)
    }

    fun looksLikeLiveRoom(service: AccessibilityService): Boolean {
        val matched = existsKeyword(service, LIVE_SIGNALS) ?: return false
        AppLog.add("Node", "直播间特征命中：$matched")
        return true
    }

    private fun search(
        node: AccessibilityNodeInfo,
        depth: Int,
        keywords: List<String>,
        counter: IntArray
    ): NodeHit? {
        if (depth > MAX_DEPTH || counter[0] > MAX_NODES) return null
        counter[0]++

        val label = labelOf(node)
        if (label.isNotEmpty() && keywords.any { label.contains(it, ignoreCase = true) }) {
            val target = clickableAncestor(node)
            if (target != null) {
                val r = Rect().also { target.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) {
                    AppLog.add("Node", "命中「$label」@${r.toShortString()} id=${target.viewIdResourceName}")
                    return NodeHit(label, r, target.viewIdResourceName ?: "")
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = search(child, depth + 1, keywords, counter)
            if (hit != null) return hit
        }
        return null
    }

    private fun searchLabel(
        node: AccessibilityNodeInfo,
        depth: Int,
        keywords: List<String>,
        counter: IntArray
    ): String? {
        if (depth > MAX_DEPTH || counter[0] > MAX_NODES) return null
        counter[0]++
        val label = labelOf(node)
        if (label.isNotEmpty()) {
            keywords.firstOrNull { label.contains(it, ignoreCase = true) }?.let { return it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchLabel(child, depth + 1, keywords, counter)?.let { return it }
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < MAX_ANCESTOR_HOPS) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    // ------------------------------------------------------------ 探测/校准页

    /**
     * 探测页专用：优先取目标 App 自己的窗口。
     *
     * 因为探测页自己也占着前台，rootInActiveWindow 抓到的会是探测页本身，
     * 用 windows 列表才能拿到后台/分屏里的抖音窗口。
     */
    fun rootForInspection(service: AccessibilityService, preferredPackage: String?): AccessibilityNodeInfo? {
        if (preferredPackage != null) {
            val hit = service.windows.firstOrNull { window ->
                window.root?.packageName?.toString() == preferredPackage
            }
            hit?.root?.let { return it }
        }
        return service.rootInActiveWindow
    }

    fun dumpHierarchy(
        service: AccessibilityService,
        limit: Int = 800,
        preferredPackage: String? = null
    ): String {
        val root = rootForInspection(service, preferredPackage)
            ?: return "(读不到窗口内容。请确认无障碍服务已开启，并且抖音至少还在最近任务里)"
        val sb = StringBuilder()
        sb.append("包名：").append(root.packageName).append('\n')
        sb.append("窗口类名：").append(root.className).append('\n')
        sb.append("可交互窗口：")
            .append(service.windows.joinToString(" | ") { it.root?.packageName?.toString() ?: "?" })
            .append('\n')
        sb.append("---- 节点列表 ----\n")
        val counter = intArrayOf(0)
        dumpNode(root, 0, sb, counter, limit)
        if (counter[0] >= limit) sb.append("\n...（超过 $limit 条已截断）")
        return sb.toString()
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        sb: StringBuilder,
        counter: IntArray,
        limit: Int
    ) {
        if (counter[0] >= limit || depth > MAX_DEPTH) return
        counter[0]++

        val text = node.text?.toString()?.take(60).orEmpty()
        val desc = node.contentDescription?.toString()?.take(60).orEmpty()
        val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            sb.append("  ".repeat(minOf(depth, 12)))
            if (node.isClickable) sb.append("[可点击] ")
            if (id.isNotEmpty()) sb.append("id=").append(id).append(' ')
            if (text.isNotEmpty()) sb.append("text=").append(text).append(' ')
            if (desc.isNotEmpty()) sb.append("desc=").append(desc).append(' ')
            sb.append(r.toShortString())
            sb.append('\n')
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1, sb, counter, limit)
        }
    }
}
