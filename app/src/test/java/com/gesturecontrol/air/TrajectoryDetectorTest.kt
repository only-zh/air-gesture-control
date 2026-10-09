package com.gesturecontrol.air

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 隔空手势判定的验证。
 *
 * 这个类故意做成零 Android 依赖，就是为了能在这里把逻辑跑一遍 ——
 * 没有真机的情况下，这是唯一能确认「挥手/捏合判定是否正确」的办法。
 */
class TrajectoryDetectorTest {

    private fun detector(
        swipe: Float = 0.20f,
        pinch: Float = 0.45f,
        pinchHold: Long = 900L,
        palmHold: Long = 300L,
        cooldown: Long = 900L
    ) = TrajectoryDetector().apply {
        swipeThreshold = swipe
        pinchThreshold = pinch
        pinchHoldMs = pinchHold
        palmHoldMs = palmHold
        cooldownMs = cooldown
    }

    // ------------------------------------------------------------ 挥手

    @Test
    fun `手向上挥应该触发 UP`() {
        val d = detector()
        var t = 10_000L
        var fired: AirGesture? = null
        for (y in listOf(0.80f, 0.756f, 0.711f, 0.667f, 0.622f, 0.578f, 0.533f, 0.489f)) {
            fired = d.onFrame(t, 0.5f, y, 9f, false)
            t += 33
            if (fired != null) break
        }
        assertEquals(AirGesture.UP, fired)
    }

    @Test
    fun `手向下挥应该触发 DOWN`() {
        val d = detector()
        var t = 10_000L
        var fired: AirGesture? = null
        for (y in listOf(0.20f, 0.244f, 0.289f, 0.333f, 0.378f, 0.422f, 0.467f, 0.511f)) {
            fired = d.onFrame(t, 0.5f, y, 9f, false)
            t += 33
            if (fired != null) break
        }
        assertEquals(AirGesture.DOWN, fired)
    }

    @Test
    fun `移动幅度不够不应该触发`() {
        val d = detector()
        var t = 10_000L
        for (y in listOf(0.80f, 0.79f, 0.78f, 0.77f, 0.76f, 0.75f, 0.74f, 0.73f, 0.72f)) {
            assertNull(d.onFrame(t, 0.5f, y, 9f, false))
            t += 33
        }
    }

    /** 来回抖动的净位移虽然够，但轨迹不直，必须被拒掉 */
    @Test
    fun `来回抖动不应该触发`() {
        val d = detector()
        var t = 10_000L
        for (y in listOf(0.80f, 0.90f, 0.70f, 0.95f, 0.60f)) {
            assertNull(d.onFrame(t, 0.5f, y, 9f, false))
            t += 33
        }
    }

    /** 横向移动为主时不应该判成上下挥 */
    @Test
    fun `横向移动不应该触发上下挥`() {
        val d = detector()
        var t = 10_000L
        for (x in listOf(0.20f, 0.26f, 0.32f, 0.38f, 0.44f, 0.50f, 0.56f)) {
            assertNull(d.onFrame(t, x, 0.5f, 9f, false))
            t += 33
        }
    }

    @Test
    fun `冷却期内继续挥动不会重复触发`() {
        val d = detector()
        var t = 10_000L
        var fired: AirGesture? = null
        for (y in listOf(0.80f, 0.756f, 0.711f, 0.667f, 0.622f, 0.578f, 0.533f)) {
            fired = d.onFrame(t, 0.5f, y, 9f, false)
            t += 33
            if (fired != null) break
        }
        assertEquals(AirGesture.UP, fired)

        assertNull(d.onFrame(t, 0.5f, 0.50f, 9f, false))
        assertNull(d.onFrame(t + 33, 0.5f, 0.45f, 9f, false))
        assertNull(d.onFrame(t + 66, 0.5f, 0.40f, 9f, false))
    }

    /** 手回位 + 过了冷却期之后，应该能再次触发 */
    @Test
    fun `重新武装之后可以再次触发`() {
        val d = detector()
        var t = 10_000L

        fun feed(y: Float): AirGesture? {
            val g = d.onFrame(t, 0.5f, y, 9f, false)
            t += 33
            return g
        }

        var first: AirGesture? = null
        for (y in listOf(0.80f, 0.756f, 0.711f, 0.667f, 0.622f, 0.578f, 0.533f)) {
            first = feed(y)
            if (first != null) break
        }
        assertEquals(AirGesture.UP, first)

        // 手回到下方：回位判定 + 冷却已过 -> 重新武装
        t += 1000
        assertNull(feed(0.85f))

        var second: AirGesture? = null
        for (y in listOf(0.85f, 0.806f, 0.761f, 0.717f, 0.672f, 0.628f, 0.583f)) {
            second = feed(y)
            if (second != null) break
        }
        assertEquals(AirGesture.UP, second)
    }

    // ------------------------------------------------------------ 捏合

    @Test
    fun `短捏合触发 PINCH`() {
        val d = detector()
        var t = 10_000L
        repeat(7) {
            assertNull(d.onFrame(t, 0.5f, 0.5f, 0.20f, false))
            t += 33
        }
        // 松开
        assertEquals(AirGesture.PINCH, d.onFrame(t, 0.5f, 0.5f, 0.90f, false))
    }

    @Test
    fun `捏住不放触发 PINCH_HOLD 且松手不再触发 PINCH`() {
        val d = detector(pinchHold = 300L)
        var t = 10_000L
        var fired: AirGesture? = null
        repeat(20) {
            if (fired == null) {
                fired = d.onFrame(t, 0.5f, 0.5f, 0.20f, false)
            }
            t += 33
        }
        assertEquals(AirGesture.PINCH_HOLD, fired)
        assertNull(d.onFrame(t, 0.5f, 0.5f, 0.90f, false))
    }

    @Test
    fun `刚碰一下就分开不算捏合`() {
        val d = detector()
        var t = 10_000L
        // 只捏 66ms，低于 120ms 的最小判定
        assertNull(d.onFrame(t, 0.5f, 0.5f, 0.20f, false))
        assertNull(d.onFrame(t + 33, 0.5f, 0.5f, 0.20f, false))
        assertNull(d.onFrame(t + 66, 0.5f, 0.5f, 0.90f, false))
    }

    // -------------------------------------------------------- 张手保持

    @Test
    fun `张手静止保持触发 PALM_HOLD`() {
        val d = detector(palmHold = 300L)
        var t = 10_000L
        var fired: AirGesture? = null
        repeat(25) {
            if (fired == null) fired = d.onFrame(t, 0.5f, 0.5f, 9f, true)
            t += 33
        }
        assertEquals(AirGesture.PALM_HOLD, fired)
    }

    @Test
    fun `握拳保持不应该触发 PALM_HOLD`() {
        val d = detector(palmHold = 300L)
        var t = 10_000L
        repeat(25) {
            assertNull(d.onFrame(t, 0.5f, 0.5f, 9f, false))
            t += 33
        }
    }

    @Test
    fun `手从画面消失后状态可以重新武装`() {
        val d = detector()
        var t = 10_000L
        var fired: AirGesture? = null
        for (y in listOf(0.80f, 0.756f, 0.711f, 0.667f, 0.622f, 0.578f, 0.533f)) {
            fired = d.onFrame(t, 0.5f, y, 9f, false)
            t += 33
            if (fired != null) break
        }
        assertEquals(AirGesture.UP, fired)

        t += 1000
        d.onHandLost(t)

        var second: AirGesture? = null
        for (y in listOf(0.80f, 0.756f, 0.711f, 0.667f, 0.622f, 0.578f, 0.533f)) {
            second = d.onFrame(t, 0.5f, y, 9f, false)
            t += 33
            if (second != null) break
        }
        assertEquals(AirGesture.UP, second)
    }
}
