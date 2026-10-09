package com.gesturecontrol.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.Command
import com.gesturecontrol.core.Prefs

/**
 * 抖音的具体动作实现。
 *
 * 策略：**先按节点文案找控件，找不到再回退到比例坐标**。
 * 所有回退坐标都能在「校准」页里改，所以抖音改版后不需要改代码。
 */
class DouyinActions(
    private val service: ControlAccessibilityService,
    private val injector: GestureInjector,
    private val watcher: ScreenWatcher
) {
    private val handler = Handler(Looper.getMainLooper())

    fun perform(cmd: Command): Boolean = when (cmd) {
        Command.SWIPE_DOWN -> swipeNext()
        Command.SWIPE_UP -> swipePrev()
        Command.LIKE -> like()
        Command.FAVORITE -> favorite()
        Command.FOLLOW -> follow()
        Command.ENTER_LIVE -> enterLive()
        Command.EXIT_LIVE -> exitLive()
        Command.PAUSE -> pauseHold()
        Command.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Command.NONE -> false
    }

    // -------------------------------------------------------------- 上下滑

    /** 「下滑」= 手指向上划 = 看下一个视频 */
    private fun swipeNext(): Boolean = injector.swipeRatios(
        0.5f, Prefs.swipeDownFromY,
        0.5f, Prefs.swipeDownToY,
        Prefs.swipeDurationMs.toLong()
    )

    /** 「上滑」= 手指向下划 = 回上一个视频 */
    private fun swipePrev(): Boolean = injector.swipeRatios(
        0.5f, Prefs.swipeDownToY,
        0.5f, Prefs.swipeDownFromY,
        Prefs.swipeDurationMs.toLong()
    )

    // ---------------------------------------------------------------- 点赞

    private fun like(): Boolean {
        if (Prefs.useNodeFirst) {
            NodeFinder.findClickable(service, listOf("喜欢", "点赞"))?.let { hit ->
                AppLog.add("Action", "点赞：命中节点「${hit.label}」")
                return injector.tapCenter(hit.rect)
            }
        }
        if (Prefs.doubleTapToLike) {
            AppLog.add("Action", "点赞：回退为双击屏幕中央")
            return injector.doubleTapRatios(0.5f, 0.5f)
        }
        AppLog.add("Action", "点赞：回退为右侧操作栏坐标")
        return injector.tapRatios(Prefs.actionXRatio, Prefs.likeY)
    }

    // ---------------------------------------------------------------- 收藏

    private fun favorite(): Boolean {
        if (Prefs.useNodeFirst) {
            NodeFinder.findClickable(service, listOf("收藏"))?.let { hit ->
                AppLog.add("Action", "收藏：命中节点「${hit.label}」")
                return injector.tapCenter(hit.rect)
            }
        }
        AppLog.add("Action", "收藏：回退为右侧操作栏坐标")
        return injector.tapRatios(Prefs.actionXRatio, Prefs.favoriteY)
    }

    // ---------------------------------------------------------------- 关注

    private fun follow(): Boolean {
        if (Prefs.useNodeFirst) {
            // 「已关注 / 互相关注」里也包含「关注」，所以先精确匹配未关注状态
            NodeFinder.findClickable(service, listOf("关注"))?.let { hit ->
                if (hit.label.contains("已关注") || hit.label.contains("互相关注")) {
                    AppLog.add("Action", "关注：当前已是「${hit.label}」，跳过以免取关")
                    return true
                }
                AppLog.add("Action", "关注：命中节点「${hit.label}」")
                return injector.tapCenter(hit.rect)
            }
        }
        AppLog.add("Action", "关注：回退为头像下方坐标")
        return injector.tapRatios(Prefs.actionXRatio, Prefs.followY)
    }

    // ------------------------------------------------------------- 直播间

    private fun enterLive(): Boolean {
        if (Prefs.useNodeFirst) {
            NodeFinder.findClickable(
                service,
                listOf("进入直播间", "正在直播", "直播中", "看直播", "直播")
            )?.let { hit ->
                AppLog.add("Action", "进直播间：命中节点「${hit.label}」")
                return injector.tapCenter(hit.rect)
            }
        }
        AppLog.add("Action", "进直播间：回退为推荐流左侧「直播」入口坐标")
        return injector.tapRatios(Prefs.liveEntryX, Prefs.liveEntryY)
    }

    private fun exitLive(): Boolean {
        val inLive = watcher.classNameLooksLikeLive || NodeFinder.looksLikeLiveRoom(service)
        AppLog.add("Action", "退出直播间：当前判定在直播间=$inLive")

        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        // 抖音直播间返回时经常先弹「确认退出」，所以延迟复查一次再补一次返回
        handler.postDelayed({
            if (NodeFinder.looksLikeLiveRoom(service)) {
                AppLog.add("Action", "仍在直播间，补一次返回")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }, 700L)
        return ok
    }

    // ----------------------------------------------------------------- 暂停

    /**
     * 抖音的暂停是「长按屏幕不放」：第一次长按进入暂停，再执行一次松手继续播放。
     * 所以这里做成开关式，而不是固定按住一段时间。
     */
    private fun pauseHold(): Boolean {
        if (injector.isPressing) {
            AppLog.add("Action", "暂停：松手继续播放")
            return injector.pressStop()
        }
        AppLog.add("Action", "暂停：长按屏幕中央")
        return injector.pressStartPx(
            injector.screenWidth * 0.5f,
            injector.screenHeight * 0.5f,
            PAUSE_MAX_HOLD_MS
        )
    }

    companion object {
        /** 安全兜底：万一没收到「继续」命令，最多按住这么久就自动松手 */
        const val PAUSE_MAX_HOLD_MS = 20_000L
    }
}
