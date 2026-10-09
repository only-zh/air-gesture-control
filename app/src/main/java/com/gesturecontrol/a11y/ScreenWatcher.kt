package com.gesturecontrol.a11y

import android.view.accessibility.AccessibilityEvent
import com.gesturecontrol.core.ForegroundState

/**
 * 跟踪当前前台应用与页面，用来判断「是不是在抖音」「是不是在直播间」。
 *
 * 抖音的 Activity 名是混淆过的，不同版本还不一样，所以这里只做启发式判断，
 * 真正的直播间判定会再看节点里有没有「在线人数」之类的特征文案。
 */
class ScreenWatcher {

    @Volatile
    var currentPackage: String = ""
        private set

    @Volatile
    var currentClass: String = ""
        private set

    @Volatile
    var lastEventAt: Long = 0L
        private set

    fun onEvent(event: AccessibilityEvent) {
        lastEventAt = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.let { currentPackage = it.toString() }
                event.className?.let { currentClass = it.toString() }
                // 广播出去，让 ControlService 决定要不要继续开摄像头/麦克风
                ForegroundState.update(event.packageName)
            }
        }
    }

    fun setFrom(rootPackage: CharSequence?, rootClass: CharSequence?) {
        rootPackage?.let { currentPackage = it.toString() }
        rootClass?.let { currentClass = it.toString() }
        ForegroundState.update(rootPackage)
    }

    val isDouyin: Boolean
        get() = currentPackage == com.gesturecontrol.core.Prefs.douyinPackage ||
                currentPackage == com.gesturecontrol.core.Prefs.DOUYIN_LITE

    /** 类名里带 live 就是强信号；抖音混淆后这个经常失效，只作为加分项 */
    val classNameLooksLikeLive: Boolean
        get() = currentClass.contains("live", ignoreCase = true)

    fun describe(): String = "pkg=$currentPackage cls=$currentClass"
}
