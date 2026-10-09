package com.gesturecontrol.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.Command
import com.gesturecontrol.core.CommandBus
import com.gesturecontrol.core.Prefs

/**
 * 手势执行服务。它是整条链路里唯一真正「碰得到屏幕」的地方：
 * 语音 / 隔空手势 / 悬浮球按钮都通过 CommandBus -> 这里。
 */
class ControlAccessibilityService : AccessibilityService() {

    private var injector: GestureInjector? = null
    private var watcher: ScreenWatcher? = null
    private var actions: DouyinActions? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val inj = GestureInjector(this)
        val watch = ScreenWatcher()
        injector = inj
        watcher = watch
        actions = DouyinActions(this, inj, watch)

        CommandBus.executor = CommandBus.Executor { cmd, source -> execute(cmd, source) }
        AppLog.add("A11y", "无障碍服务已连接，可注入手势=${inj.canGesture}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        watcher?.onEvent(event)
    }

    override fun onInterrupt() {
        AppLog.add("A11y", "onInterrupt（服务被系统临时中断）")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown("onUnbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown("onDestroy")
        super.onDestroy()
    }

    private fun teardown(reason: String) {
        if (instance === this) instance = null
        CommandBus.executor = null
        actions = null
        AppLog.add("A11y", "无障碍服务断开（$reason）——语音/手势控制将不可用")
    }

    /**
     * CommandBus 的实际落点。
     *
     * Vosk 的识别回调和 MediaPipe 的结果回调都在各自的后台线程上，
     * 而无障碍的查询节点 / dispatchGesture / performGlobalAction 都该在主线程调用，
     * 所以这里统一往上抛一次。
     */
    private fun execute(cmd: Command, source: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { executeOnMain(cmd, source) }
            return true
        }
        return executeOnMain(cmd, source)
    }

    private fun executeOnMain(cmd: Command, source: String): Boolean {
        val act = actions ?: return false

        if (Prefs.targetOnly) {
            val pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
                .ifEmpty { watcher?.currentPackage.orEmpty() }
            if (pkg.isNotEmpty() &&
                pkg != Prefs.douyinPackage &&
                pkg != Prefs.DOUYIN_LITE
            ) {
                AppLog.add("A11y", "当前前台是 $pkg，不是抖音，忽略「${cmd.label}」（来源 $source）")
                return false
            }
        }
        return act.perform(cmd)
    }

    companion object {
        @Volatile
        var instance: ControlAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null

        fun currentForeground(): String =
            instance?.rootInActiveWindow?.packageName?.toString().orEmpty()
    }
}
