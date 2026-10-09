package com.gesturecontrol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 语音文本 -> 命令 的匹配验证。
 *
 * 重点是 Vosk 中文模型的实际输出特点：字之间带空格、偶尔同音字错认。
 */
class CommandMatcherTest {

    private fun match(text: String): Command? =
        CommandMatcher.match(text, 0.62f)?.command

    @Test
    fun `精确命中命令词`() {
        assertEquals(Command.SWIPE_UP, match("上滑"))
        assertEquals(Command.SWIPE_DOWN, match("下滑"))
        assertEquals(Command.LIKE, match("点赞"))
        assertEquals(Command.FAVORITE, match("收藏"))
        assertEquals(Command.FOLLOW, match("关注"))
        assertEquals(Command.ENTER_LIVE, match("进直播间"))
        assertEquals(Command.EXIT_LIVE, match("退出直播间"))
        assertEquals(Command.PAUSE, match("暂停"))
    }

    /** Vosk 中文模型的输出经常是「上 滑」这样带空格的 */
    @Test
    fun `带空格的识别结果也能命中`() {
        assertEquals(Command.SWIPE_UP, match("上 滑"))
        assertEquals(Command.SWIPE_DOWN, match("下 滑"))
        assertEquals(Command.LIKE, match("点 赞"))
        assertEquals(Command.FAVORITE, match("收 藏"))
    }

    /** 「退出直播间」包含「直播间」，必须优先判成退出而不是进入 */
    @Test
    fun `包含关系不能判反`() {
        assertEquals(Command.EXIT_LIVE, match("退出直播间"))
        assertEquals(Command.EXIT_LIVE, match("退出直播"))
        assertEquals(Command.ENTER_LIVE, match("进入直播间"))
        assertEquals(Command.ENTER_LIVE, match("进直播间"))
    }

    @Test
    fun `口语化说法也能命中`() {
        assertEquals(Command.SWIPE_DOWN, match("下一个"))
        assertEquals(Command.SWIPE_DOWN, match("下一个视频"))
        assertEquals(Command.SWIPE_DOWN, match("换一个"))
        assertEquals(Command.SWIPE_UP, match("上一个"))
        assertEquals(Command.LIKE, match("比心"))
        assertEquals(Command.LIKE, match("点个赞"))
        assertEquals(Command.EXIT_LIVE, match("不看直播了"))
    }

    @Test
    fun `带标点也能命中`() {
        assertEquals(Command.LIKE, match("点赞！"))
        assertEquals(Command.SWIPE_DOWN, match("下一个，"))
    }

    /** 同音/形近字错认：靠关键词里的变体表兜底（Vosk 中文模型很常见的错法） */
    @Test
    fun `轻微错认仍能命中`() {
        assertEquals(Command.SWIPE_UP, match("上划"))
        assertEquals(Command.SWIPE_DOWN, match("下划"))
        assertEquals(Command.LIKE, match("点攒"))
        assertEquals(Command.FAVORITE, match("收仓"))
        assertEquals(Command.EXIT_LIVE, match("退直播间"))
    }

    @Test
    fun `无关内容不应该命中任何命令`() {
        assertNull(match("今天天气不错"))
        assertNull(match("帮我放首歌"))
        assertNull(match(""))
        assertNull(match("   "))
    }

    @Test
    fun `阈值调高以后误触发会减少`() {
        // 「上划一下」只是包含关键词（相似度约 0.93），阈值拉到 0.99 就应该被拒
        assertEquals(Command.SWIPE_UP, match("上划一下"))
        assertNull(CommandMatcher.match("上划一下", 0.99f))
    }

    @Test
    fun `每个命令的关键词都应该能匹配到自己`() {
        for (cmd in Command.mappable) {
            for (keyword in cmd.keywords) {
                val hit = CommandMatcher.match(keyword, 0.62f)
                assertEquals("关键词「$keyword」应该命中 ${cmd.label}", cmd, hit?.command)
            }
        }
    }
}
