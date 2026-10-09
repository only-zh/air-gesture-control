package com.gesturecontrol.core

import android.content.Context
import android.content.SharedPreferences

/** 全部可调参数集中在这里，方便真机上一轮轮调。 */
object Prefs {
    private const val NAME = "gesture_control"
    private var sp: SharedPreferences? = null

    private val p: SharedPreferences
        get() = sp ?: throw IllegalStateException("Prefs.init() 未调用")

    fun init(context: Context) {
        if (sp == null) {
            sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        }
    }

    private fun putBool(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()
    private fun putInt(key: String, v: Int) = p.edit().putInt(key, v).apply()
    private fun putFloat(key: String, v: Float) = p.edit().putFloat(key, v).apply()
    private fun putStr(key: String, v: String) = p.edit().putString(key, v).apply()

    // ------------------------------------------------------------ 功能开关

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    /** 外观：跟随系统 / 始终浅色 / 始终深色 */
    var themeMode: String
        get() = p.getString("theme_mode", THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = putStr("theme_mode", v)

    const val DEFAULT_DOUYIN = "com.ss.android.ugc.aweme"
    const val DOUYIN_LITE = "com.ss.android.ugc.aweme.lite"

    /** 语音控制（阶段一） */
    var voiceEnabled: Boolean
        get() = p.getBoolean("voice_enabled", false)
        set(v) = putBool("voice_enabled", v)

    /** 隔空手势（阶段二） */
    var airEnabled: Boolean
        get() = p.getBoolean("air_enabled", false)
        set(v) = putBool("air_enabled", v)

    /** 悬浮球控制面板 */
    var ballEnabled: Boolean
        get() = p.getBoolean("ball_enabled", true)
        set(v) = putBool("ball_enabled", v)

    /** 只在目标 App 处于前台时响应命令，避免误操作别的应用 */
    var targetOnly: Boolean
        get() = p.getBoolean("target_only", true)
        set(v) = putBool("target_only", v)

    var douyinPackage: String
        get() = p.getString("douyin_pkg", null) ?: DEFAULT_DOUYIN
        set(v) = putStr("douyin_pkg", v)

    /** 优先用无障碍节点定位，失败再回退到比例坐标 */
    var useNodeFirst: Boolean
        get() = p.getBoolean("use_node_first", true)
        set(v) = putBool("use_node_first", v)

    /** 节流倍率：真机上如果被限流就调大 */
    var throttleScale: Float
        get() = p.getFloat("throttle_scale", 1.0f)
        set(v) = putFloat("throttle_scale", v)

    /** 把执行结果显示在悬浮球上 */
    var overlayToast: Boolean
        get() = p.getBoolean("overlay_toast", true)
        set(v) = putBool("overlay_toast", v)

    // ------------------------------------------------------- 滑动 / 点击参数

    var swipeDurationMs: Int
        get() = p.getInt("swipe_duration", 220)
        set(v) = putInt("swipe_duration", v)

    /** 下滑（看下一个）起点 Y 比例 */
    var swipeDownFromY: Float
        get() = p.getFloat("swipe_down_from_y", 0.78f)
        set(v) = putFloat("swipe_down_from_y", v)

    /** 下滑（看下一个）终点 Y 比例 */
    var swipeDownToY: Float
        get() = p.getFloat("swipe_down_to_y", 0.22f)
        set(v) = putFloat("swipe_down_to_y", v)

    /** 右侧操作栏的横向位置 */
    var actionXRatio: Float
        get() = p.getFloat("action_x", 0.92f)
        set(v) = putFloat("action_x", v)

    var likeY: Float
        get() = p.getFloat("like_y", 0.62f)
        set(v) = putFloat("like_y", v)

    var favoriteY: Float
        get() = p.getFloat("favorite_y", 0.71f)
        set(v) = putFloat("favorite_y", v)

    var followY: Float
        get() = p.getFloat("follow_y", 0.50f)
        set(v) = putFloat("follow_y", v)

    /** 推荐流里「直播」入口的位置（左侧竖排胶囊） */
    var liveEntryX: Float
        get() = p.getFloat("live_entry_x", 0.085f)
        set(v) = putFloat("live_entry_x", v)

    var liveEntryY: Float
        get() = p.getFloat("live_entry_y", 0.34f)
        set(v) = putFloat("live_entry_y", v)

    /** 找不到点赞按钮时，用双击屏幕中央点赞 */
    var doubleTapToLike: Boolean
        get() = p.getBoolean("double_tap_like", true)
        set(v) = putBool("double_tap_like", v)

    // ------------------------------------------------------------- 语音参数

    /** 多长时间没有识别到命令就自动停止听音，省电 */
    var voiceIdleStopMs: Int
        get() = p.getInt("voice_idle_stop", 60_000)
        set(v) = putInt("voice_idle_stop", v)

    /** 悬浮球上显示识别中的中间结果 */
    var voiceShowPartial: Boolean
        get() = p.getBoolean("voice_show_partial", true)
        set(v) = putBool("voice_show_partial", v)

    /** 匹配阈值，越低越容易误触发 */
    var matchThreshold: Float
        get() = p.getFloat("match_threshold", 0.62f)
        set(v) = putFloat("match_threshold", v)

    // --------------------------------------------------------- 隔空手势参数

    /** 判定为「挥动」所需的归一化位移 */
    var airSwipeThreshold: Float
        get() = p.getFloat("air_swipe_threshold", 0.20f)
        set(v) = putFloat("air_swipe_threshold", v)

    /** 拇指尖-食指尖距离 / 手掌尺寸 低于该值算捏合 */
    var airPinchThreshold: Float
        get() = p.getFloat("air_pinch_threshold", 0.45f)
        set(v) = putFloat("air_pinch_threshold", v)

    /** 两次隔空手势之间的冷却 */
    var airCooldownMs: Int
        get() = p.getInt("air_cooldown", 900)
        set(v) = putInt("air_cooldown", v)

    /** 张手保持多久触发「暂停/继续」 */
    var airPalmHoldMs: Int
        get() = p.getInt("air_palm_hold", 1500)
        set(v) = putInt("air_palm_hold", v)

    /** 捏合保持多久触发「收藏」（短捏=点赞） */
    var airPinchHoldMs: Int
        get() = p.getInt("air_pinch_hold", 900)
        set(v) = putInt("air_pinch_hold", v)

    // ------------------------------------------------------------ 命令启用表

    fun isCommandEnabled(cmd: Command): Boolean =
        p.getBoolean("cmd_enabled_${cmd.id}", true)

    fun setCommandEnabled(cmd: Command, enabled: Boolean) =
        putBool("cmd_enabled_${cmd.id}", enabled)

    /** 实际冷却时间 = 命令默认值 × 节流倍率 */
    fun cooldownMs(cmd: Command): Long =
        (cmd.defaultCooldownMs * throttleScale).toLong().coerceAtLeast(120L)

    // ------------------------------------------------------------- 隔空映射

    /** 手心向上挥 -> 命令 id */
    var airUpCommand: String
        get() = p.getString("air_up_command", Command.SWIPE_DOWN.id) ?: Command.SWIPE_DOWN.id
        set(v) = putStr("air_up_command", v)

    var airDownCommand: String
        get() = p.getString("air_down_command", Command.SWIPE_UP.id) ?: Command.SWIPE_UP.id
        set(v) = putStr("air_down_command", v)

    var airPinchCommand: String
        get() = p.getString("air_pinch_command", Command.LIKE.id) ?: Command.LIKE.id
        set(v) = putStr("air_pinch_command", v)

    var airPinchHoldCommand: String
        get() = p.getString("air_pinch_hold_command", Command.FAVORITE.id) ?: Command.FAVORITE.id
        set(v) = putStr("air_pinch_hold_command", v)

    var airPalmCommand: String
        get() = p.getString("air_palm_command", Command.PAUSE.id) ?: Command.PAUSE.id
        set(v) = putStr("air_palm_command", v)

    fun resetTuning() {
        p.edit()
            .remove("swipe_duration").remove("swipe_down_from_y").remove("swipe_down_to_y")
            .remove("action_x").remove("like_y").remove("favorite_y").remove("follow_y")
            .remove("live_entry_x").remove("live_entry_y").remove("double_tap_like")
            .remove("air_swipe_threshold").remove("air_pinch_threshold").remove("air_cooldown")
            .remove("air_palm_hold").remove("air_pinch_hold").remove("throttle_scale")
            .remove("match_threshold")
            .apply()
    }
}
