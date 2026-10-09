package com.gesturecontrol.core

/**
 * 所有输入源（语音、隔空手势、悬浮球按钮）都只产生 Command，
 * 由 CommandBus 统一节流后交给无障碍服务执行。
 */
enum class Command(
    val id: String,
    val label: String,
    /** 语音识别结果里可能出现的说法，包含常见的同音/形近字错认 */
    val keywords: List<String>,
    val defaultCooldownMs: Long
) {
    SWIPE_DOWN(
        "swipe_down", "下滑 · 下一个视频",
        listOf("下滑", "下划", "下一个", "下一条", "下一个视频", "往下滑", "换一个", "继续滑"), 400
    ),
    SWIPE_UP(
        "swipe_up", "上滑 · 上一个视频",
        listOf("上滑", "上划", "上一个", "上一条", "上一个视频", "往上滑", "前一个"), 400
    ),
    LIKE(
        "like", "点赞",
        listOf("点赞", "点攒", "点个赞", "喜欢", "双击", "给个赞", "比心"), 700
    ),
    FAVORITE(
        "favorite", "收藏",
        listOf("收藏", "收仓", "加收藏", "收藏一下", "收藏它", "存起来"), 700
    ),
    FOLLOW(
        "follow", "关注",
        listOf("关注", "关住", "加关注", "关注一下", "关注他", "关注她"), 1000
    ),
    ENTER_LIVE(
        "enter_live", "进入直播间",
        listOf("进直播间", "进入直播间", "打开直播", "进直播", "看直播", "直播间"), 1500
    ),
    EXIT_LIVE(
        "exit_live", "退出直播间",
        listOf("退出直播间", "退出直播", "离开直播间", "不看直播了", "退出"), 1500
    ),
    PAUSE(
        "pause", "暂停 / 继续",
        listOf("暂停", "停一下", "停止", "继续", "接着放", "播放"), 600
    ),
    BACK(
        "back", "返回",
        listOf("返回", "后退", "退回去", "回上一步"), 500
    ),
    NONE("none", "无操作", emptyList(), 0);

    val isSwipe: Boolean get() = this == SWIPE_DOWN || this == SWIPE_UP

    companion object {
        fun fromId(id: String?): Command = entries.firstOrNull { it.id == id } ?: NONE

        /** 需要在设置页里展示的可映射命令 */
        val mappable: List<Command> get() = entries.filter { it != NONE }
    }
}
