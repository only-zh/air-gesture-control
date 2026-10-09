package com.gesturecontrol.air

import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.Command
import com.gesturecontrol.core.CommandBus
import com.gesturecontrol.core.Prefs
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.sqrt

/**
 * 把 MediaPipe 的手部关键点喂给 TrajectoryDetector，再把判定结果翻译成 Command。
 *
 * 只用**纵向**挥动，因为前置摄像头画面是否镜像会影响左右判定，纵向不受影响。
 */
class AirGestureController {

    private val detector = TrajectoryDetector()

    val debugInfo: String get() = detector.debugInfo

    fun onNoHand(t: Long) = detector.onHandLost(t)

    fun onResult(result: HandLandmarkerResult, t: Long) {
        detector.swipeThreshold = Prefs.airSwipeThreshold
        detector.pinchThreshold = Prefs.airPinchThreshold
        detector.pinchHoldMs = Prefs.airPinchHoldMs.toLong()
        detector.palmHoldMs = Prefs.airPalmHoldMs.toLong()
        detector.cooldownMs = Prefs.airCooldownMs.toLong()

        val hand = result.landmarks().firstOrNull()
        if (hand == null || hand.size < 21) {
            detector.onHandLost(t)
            return
        }

        val scale = distance(hand[0], hand[9])
        val pinchRatio = if (scale > 1e-4f) distance(hand[4], hand[8]) / scale else 9f
        val open = isPalmOpen(hand, scale)

        // 掌心 = 腕关节 + 四个掌指关节的平均，比单个关键点稳
        val cx = (hand[0].x() + hand[5].x() + hand[9].x() + hand[13].x() + hand[17].x()) / 5f
        val cy = (hand[0].y() + hand[5].y() + hand[9].y() + hand[13].y() + hand[17].y()) / 5f

        val gesture = detector.onFrame(t, cx, cy, pinchRatio, open) ?: return

        val cmd = map(gesture)
        if (cmd == Command.NONE) {
            AppLog.add("Air", "手势 $gesture 未映射到任何命令，忽略")
            return
        }
        AppLog.add("Air", "隔空手势 $gesture -> ${cmd.label}")
        CommandBus.dispatch(cmd, "隔空手势")
    }

    private fun map(gesture: AirGesture): Command = when (gesture) {
        AirGesture.UP -> Command.fromId(Prefs.airUpCommand)
        AirGesture.DOWN -> Command.fromId(Prefs.airDownCommand)
        AirGesture.PINCH -> Command.fromId(Prefs.airPinchCommand)
        AirGesture.PINCH_HOLD -> Command.fromId(Prefs.airPinchHoldCommand)
        AirGesture.PALM_HOLD -> Command.fromId(Prefs.airPalmCommand)
    }

    /** 四指（食/中/无名/小指）指尖离腕关节明显比近端指节远，就认为张开了 */
    private fun isPalmOpen(hand: List<NormalizedLandmark>, scale: Float): Boolean {
        val pairs = intArrayOf(8, 6, 12, 10, 16, 14, 20, 18)
        var extended = 0
        var i = 0
        while (i < pairs.size) {
            val tip = distance(hand[pairs[i]], hand[0])
            val pip = distance(hand[pairs[i + 1]], hand[0])
            if (tip > pip + 0.12f * scale) extended++
            i += 2
        }
        return extended >= 3
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }
}
