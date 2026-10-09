package com.gesturecontrol.core

/**
 * 悬浮球和通知用来显示**真实运行状态**。
 *
 * 关键是区分「用户想开着」和「真的在采集」：
 * 息屏或切到别的 App 时，采集会被暂停，但开关仍然是开着的。
 * 界面必须如实显示成「待机」，否则用户会以为功能坏了，
 * 实际上只是省电策略生效了。
 */
data class RuntimeState(
    val accessibility: Boolean,
    val voiceEnabled: Boolean,
    val voiceCapturing: Boolean,
    val airEnabled: Boolean,
    val airCapturing: Boolean
) {
    val voiceLabel: String
        get() = when {
            !voiceEnabled -> "语音：关"
            voiceCapturing -> "语音：开"
            else -> "语音：待机"
        }

    val airLabel: String
        get() = when {
            !airEnabled -> "手势：关"
            airCapturing -> "手势：开"
            else -> "手势：待机"
        }

    val anyPaused: Boolean
        get() = (voiceEnabled && !voiceCapturing) || (airEnabled && !airCapturing)

    fun summary(): String = buildString {
        append("无障碍 ")
        append(if (accessibility) "✓" else "✗")
        append(" · ")
        append(voiceLabel.removePrefix("语音：").let { "语音 $it" })
        append(" · ")
        append(airLabel.removePrefix("手势：").let { "手势 $it" })
    }
}
