package com.gesturecontrol.air

import kotlin.math.abs

data class AirSample(val t: Long, val x: Float, val y: Float)

enum class AirGesture { UP, DOWN, PINCH, PINCH_HOLD, PALM_HOLD }

/**
 * 隔空手势判定。刻意做成**纯 Kotlin、零 Android 依赖**，这样逻辑可以单独推演和测试。
 *
 * 判定规则：
 *   - 上/下挥：一个 500ms 时间窗内，纵向净位移超过阈值、横向位移小、且轨迹足够「直」
 *     （净位移 / 总路程 > 0.75，用来排除手抖和来回摆）
 *   - 捏合：拇指尖-食指尖距离 / 手掌尺寸 小于阈值，短捏=点赞，捏住不放=收藏
 *   - 张手保持：五指张开且手基本静止超过阈值时间 -> 暂停/继续
 *
 * 每触发一次进入冷却，并且需要「重新武装」（手回位或静止下来）才能再次触发，
 * 避免持续挥手导致连续重复执行。
 */
class TrajectoryDetector {

    var swipeThreshold: Float = 0.20f
    var pinchThreshold: Float = 0.45f
    var pinchHoldMs: Long = 900L
    var palmHoldMs: Long = 1500L
    var cooldownMs: Long = 900L

    private val samples = ArrayDeque<AirSample>()
    private var previous: AirSample? = null

    private var pinchSince = 0L
    private var pinchFired = false
    private var palmSince = 0L
    private var palmFired = false

    private var lastFireAt = 0L
    private var lastFireY = Float.NaN
    private var lastMoveAt = 0L
    private var armed = true

    /** 供悬浮球显示，方便真机上调参数 */
    @Volatile
    var debugInfo: String = ""

    val isPinching: Boolean get() = pinchSince != 0L

    fun reset() {
        samples.clear()
        previous = null
        pinchSince = 0L
        pinchFired = false
        palmSince = 0L
        palmFired = false
        armed = true
        lastFireAt = 0L
        lastFireY = Float.NaN
    }

    /** 手从画面里消失时调用，让状态可以重新武装 */
    fun onHandLost(t: Long) {
        samples.clear()
        previous = null
        pinchSince = 0L
        palmSince = 0L
        pinchFired = false
        palmFired = false
        lastMoveAt = t
        if (t - lastFireAt >= cooldownMs) armed = true
        debugInfo = "未检测到手"
    }

    fun onFrame(t: Long, x: Float, y: Float, pinchRatio: Float, palmOpen: Boolean): AirGesture? {
        samples.addLast(AirSample(t, x, y))
        while (samples.size > 1 && t - samples.first().t > WINDOW_MS) {
            samples.removeFirst()
        }

        val prev = previous
        if (prev != null && abs(y - prev.y) + abs(x - prev.x) >= MOVE_EPSILON) {
            lastMoveAt = t
        }
        previous = AirSample(t, x, y)

        // 重新武装
        if (!armed && t - lastFireAt >= cooldownMs) {
            val movedBack = !lastFireY.isNaN() && abs(y - lastFireY) >= swipeThreshold * 0.45f
            val settled = t - lastMoveAt >= SETTLE_MS
            if (movedBack || settled) armed = true
        }

        val pinching = pinchRatio > 0f && pinchRatio < pinchThreshold
        val gesture = when {
            pinching -> detectPinch(t, true)
            pinchSince != 0L -> detectPinch(t, false)
            else -> detectSwipe(t) ?: detectPalmHold(t, palmOpen)
        }

        if (gesture != null) {
            lastFireAt = t
            lastFireY = y
            lastMoveAt = t
            armed = false
            samples.clear()
        }

        debugInfo = buildDebug(t, x, y, pinchRatio, palmOpen)
        return gesture
    }

    // --------------------------------------------------------------- 捏合

    private fun detectPinch(t: Long, pinching: Boolean): AirGesture? {
        if (pinching) {
            if (pinchSince == 0L) {
                pinchSince = t
                pinchFired = false
            }
            if (armed && !pinchFired && pinchHoldMs > PINCH_MIN_MS &&
                t - pinchSince >= pinchHoldMs
            ) {
                pinchFired = true
                return AirGesture.PINCH_HOLD
            }
            return null
        }

        // 刚松开
        val held = t - pinchSince
        val alreadyFired = pinchFired
        pinchSince = 0L
        pinchFired = false
        return if (armed && !alreadyFired && held >= PINCH_MIN_MS) AirGesture.PINCH else null
    }

    // --------------------------------------------------------------- 挥动

    private fun detectSwipe(t: Long): AirGesture? {
        if (!armed || samples.size < 4) return null

        val first = samples.first()
        val last = samples.last()
        val span = last.t - first.t
        if (span < MIN_SWIPE_SPAN_MS || span > MAX_SWIPE_SPAN_MS) return null

        val dx = last.x - first.x
        val dy = last.y - first.y
        if (abs(dy) < swipeThreshold) return null
        if (abs(dy) < CROSS_AXIS_RATIO * abs(dx)) return null

        // 轨迹直不直：净位移要占总路程的大部分
        var path = 0f
        var prev = first
        for (s in samples) {
            path += abs(s.y - prev.y)
            prev = s
        }
        if (path < 1e-4f) return null
        val straightness = abs(dy) / path
        if (straightness < MIN_STRAIGHTNESS) return null

        return if (dy < 0) AirGesture.UP else AirGesture.DOWN
    }

    // ------------------------------------------------------------ 张手保持

    private fun detectPalmHold(t: Long, palmOpen: Boolean): AirGesture? {
        val still = t - lastMoveAt >= PALM_STILL_MS
        if (!palmOpen || !still) {
            palmSince = 0L
            palmFired = false
            return null
        }
        if (palmSince == 0L) palmSince = t
        if (armed && !palmFired && t - palmSince >= palmHoldMs) {
            palmFired = true
            return AirGesture.PALM_HOLD
        }
        return null
    }

    private fun buildDebug(t: Long, x: Float, y: Float, pinchRatio: Float, palmOpen: Boolean): String {
        if (samples.size < 2) return "x=%.2f y=%.2f".format(x, y)
        val first = samples.first()
        val dx = x - first.x
        val dy = y - first.y
        return "dy=%+.2f dx=%+.2f 捏=%.2f 掌=%s%s".format(
            dy, dx, pinchRatio,
            if (palmOpen) "开" else "合",
            if (armed) "" else " [冷却]"
        )
    }

    private companion object {
        const val WINDOW_MS = 500L
        const val MIN_SWIPE_SPAN_MS = 130L
        const val MAX_SWIPE_SPAN_MS = 700L
        const val MIN_STRAIGHTNESS = 0.75f
        const val CROSS_AXIS_RATIO = 1.4f
        const val MOVE_EPSILON = 0.012f
        const val SETTLE_MS = 260L
        const val PALM_STILL_MS = 250L
        const val PINCH_MIN_MS = 120L
    }
}
