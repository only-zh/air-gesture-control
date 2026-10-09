package com.gesturecontrol.core

import android.os.SystemClock

/**
 * 所有命令的唯一入口：统一节流、统一日志、统一回调悬浮球。
 *
 * 输入源（语音 / 隔空手势 / 悬浮球按钮）-> dispatch() -> 无障碍服务执行。
 */
object CommandBus {

    fun interface Executor {
        /** @return 是否成功派发 */
        fun execute(cmd: Command, source: String): Boolean
    }

    @Volatile
    var executor: Executor? = null

    /** 悬浮球用来显示最近一次动作，回调发生在调用线程 */
    @Volatile
    var statusListener: ((String) -> Unit)? = null

    private val lastRunAt = HashMap<String, Long>()

    @Synchronized
    fun dispatch(cmd: Command, source: String): Boolean {
        if (cmd == Command.NONE) return false

        if (!Prefs.isCommandEnabled(cmd)) {
            AppLog.add("Bus", "「${cmd.label}」已在设置里被禁用，忽略（$source）")
            publish("已禁用：${cmd.label}")
            return false
        }

        val ex = executor
        if (ex == null) {
            AppLog.add("Bus", "无障碍服务未连接，无法执行「${cmd.label}」（$source）")
            publish("请先开启无障碍服务")
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val cooldown = Prefs.cooldownMs(cmd)
        val last = lastRunAt[cmd.id] ?: 0L
        val elapsed = now - last
        if (last != 0L && elapsed < cooldown) {
            AppLog.add("Bus", "「${cmd.label}」冷却中（剩 ${cooldown - elapsed}ms），忽略（$source）")
            return false
        }

        lastRunAt[cmd.id] = now
        return try {
            val ok = ex.execute(cmd, source)
            AppLog.add("Bus", "执行「${cmd.label}」来源=$source 结果=${if (ok) "成功" else "失败"}")
            publish(if (ok) "${cmd.label}" else "${cmd.label} 失败")
            ok
        } catch (t: Throwable) {
            AppLog.add("Bus", "执行「${cmd.label}」异常：${t.javaClass.simpleName} ${t.message}")
            publish("${cmd.label} 异常")
            false
        }
    }

    /** 只更新悬浮球提示，不执行命令 */
    fun publish(text: String) {
        if (!Prefs.overlayToast) return
        try {
            statusListener?.invoke(text)
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun resetThrottle() {
        lastRunAt.clear()
    }
}
