package com.gesturecontrol.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.gesturecontrol.core.AppLog

/**
 * 把「比例坐标」翻译成真实的 dispatchGesture 调用。
 *
 * 所有坐标都用屏幕宽高的比例表示，这样换手机不用改代码。
 */
class GestureInjector(private val service: AccessibilityService) {

    val screenWidth: Int
    val screenHeight: Int

    private val handler = Handler(Looper.getMainLooper())

    init {
        var w = 0
        var h = 0
        try {
            val wm = service.getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                w = b.width()
                h = b.height()
            } else {
                @Suppress("DEPRECATION")
                val dm = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
                w = dm.widthPixels
                h = dm.heightPixels
            }
        } catch (t: Throwable) {
            AppLog.add("Gesture", "取屏幕尺寸失败：${t.message}")
        }
        if (w <= 0 || h <= 0) {
            val dm = service.resources.displayMetrics
            w = dm.widthPixels
            h = dm.heightPixels
        }
        screenWidth = w
        screenHeight = h
        AppLog.add("Gesture", "屏幕尺寸 ${screenWidth}x${screenHeight}")
    }

    val canGesture: Boolean
        get() {
            val caps = service.serviceInfo?.capabilities ?: 0
            return (caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        }

    private fun pxX(ratio: Float) = (screenWidth * ratio).coerceIn(1f, screenWidth - 1f)
    private fun pxY(ratio: Float) = (screenHeight * ratio).coerceIn(1f, screenHeight - 1f)

    private fun dispatch(gesture: GestureDescription, tag: String): Boolean {
        if (!canGesture) {
            AppLog.add("Gesture", "无障碍服务没有 canPerformGestures 能力")
            return false
        }
        val ok = try {
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    AppLog.add("Gesture", "$tag 被系统取消")
                }
            }, null)
        } catch (t: Throwable) {
            AppLog.add("Gesture", "$tag 派发异常：${t.javaClass.simpleName} ${t.message}")
            false
        }
        if (!ok) AppLog.add("Gesture", "$tag 派发失败（dispatchGesture 返回 false）")
        return ok
    }

    // ------------------------------------------------------------------ 滑动

    fun swipeRatios(
        fromXRatio: Float, fromYRatio: Float,
        toXRatio: Float, toYRatio: Float,
        durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(pxX(fromXRatio), pxY(fromYRatio))
            lineTo(pxX(toXRatio), pxY(toYRatio))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(20L, 5000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(gesture, "swipe(${fromXRatio},${fromYRatio}->${toXRatio},${toYRatio})")
    }

    // ------------------------------------------------------------------ 点击

    fun tapPx(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build(), "tap($x,$y)")
    }

    fun tapRatios(xRatio: Float, yRatio: Float): Boolean = tapPx(pxX(xRatio), pxY(yRatio))

    fun tapCenter(rect: Rect): Boolean =
        tapPx(rect.exactCenterX(), rect.exactCenterY())

    /** 两次独立的单击，交给系统自己的双击判定 */
    fun doubleTapPx(x: Float, y: Float, gapMs: Long = 90L): Boolean {
        val first = tapPx(x, y)
        handler.postDelayed({ tapPx(x, y) }, gapMs)
        return first
    }

    fun doubleTapRatios(xRatio: Float, yRatio: Float): Boolean =
        doubleTapPx(pxX(xRatio), pxY(yRatio))

    // ---------------------------------------------------------------- 长按

    /**
     * 抖音的「暂停」是长按屏幕不放。
     *
     * 无障碍没有按下/松开事件对，官方的做法是用 willContinue=true 的 StrokeDescription
     * 发出去让手指停住，再用 continueStroke(..., willContinue=false) 补一次续接来「松手」。
     * GestureDescription 本身没有 cancel()，所以只能用这个方式。
     */
    @Volatile
    private var heldStroke: GestureDescription.StrokeDescription? = null

    @Volatile
    private var heldPath: Path? = null

    private val autoRelease = Runnable {
        AppLog.add("Gesture", "长按超过安全时长，自动松手")
        pressStop()
    }

    val isPressing: Boolean get() = heldStroke != null

    fun pressStartPx(x: Float, y: Float, autoReleaseMs: Long): Boolean {
        if (heldStroke != null) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, PRESS_STEP_MS, true)
        val ok = dispatch(GestureDescription.Builder().addStroke(stroke).build(), "pressStart")
        if (ok) {
            heldStroke = stroke
            heldPath = path
            if (autoReleaseMs > 0) handler.postDelayed(autoRelease, autoReleaseMs)
        }
        return ok
    }

    fun pressStop(): Boolean {
        val stroke = heldStroke ?: return false
        val path = heldPath ?: Path()
        heldStroke = null
        heldPath = null
        handler.removeCallbacks(autoRelease)
        return try {
            val cont = stroke.continueStroke(path, 0L, PRESS_STEP_MS, false)
            dispatch(GestureDescription.Builder().addStroke(cont).build(), "pressStop")
        } catch (t: Throwable) {
            AppLog.add("Gesture", "pressStop 失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    companion object {
        private const val PRESS_STEP_MS = 60L
    }
}
