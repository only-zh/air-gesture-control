package com.gesturecontrol.core

/**
 * 把语音识别出来的自由文本映射成 Command。
 *
 * Vosk 中文小模型的词典是**单字级**的，所以它对「上滑」这种双字命令的输出往往带空格
 * 甚至个别字错认（例如「上 划」）。因此这里不用 grammar 硬约束，而是：
 *   1. 去掉空格与标点
 *   2. 先看是否包含关键词（命中度最高）
 *   3. 再看字符重合率，容忍同音字/漏字
 */
object CommandMatcher {

    data class Match(val command: Command, val score: Double, val keyword: String, val text: String)

    private val NOISE = Regex("[\\s，。！？、,.!?~…\"'“”‘’（）()【】\\[\\]{}:：;；-]")

    fun normalize(raw: String): String = NOISE.replace(raw, "")

    fun match(raw: String, threshold: Float = 0.62f): Match? {
        val text = normalize(raw)
        if (text.isEmpty()) return null

        var best: Match? = null
        for (cmd in Command.mappable) {
            for (kw in cmd.keywords) {
                val k = normalize(kw)
                if (k.isEmpty()) continue
                val s = score(text, k)
                val cur = best
                if (cur == null || s > cur.score) best = Match(cmd, s, kw, text)
            }
        }
        val b = best ?: return null
        return if (b.score >= threshold) b else null
    }

    /**
     * 用 F1 打分：既惩罚「关键词没说全」，也惩罚「说了关键词之外的多余字」。
     *
     * 只看「关键词是否被包含」是不够的：用户漏说一个字时，
     * 「退直播间」会包含「直播间」而被判成**进入**直播间，方向完全相反。
     * 加上 precision 之后，「退出直播间」因为把用户说的话解释得更完整而胜出。
     */
    private fun score(text: String, kw: String): Double {
        if (text == kw) return 1.0

        val kwChars = kw.toCharArray().distinct()
        val textChars = text.toCharArray().distinct()
        val hit = kwChars.count { textChars.contains(it) }
        if (hit == 0) return 0.0

        val recall = hit.toDouble() / kwChars.size
        val precision = hit.toDouble() / textChars.size
        val f1 = 2 * recall * precision / (recall + precision)

        // 「上一个视频」明确包含「上一个」，给一个下限让它能命中；
        // 但下限低于 1.0，保证精确匹配仍然优先。
        return if (text.contains(kw)) maxOf(f1, CONTAINMENT_FLOOR) else f1
    }

    private const val CONTAINMENT_FLOOR = 0.80
}
