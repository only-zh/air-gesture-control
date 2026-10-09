package com.gesturecontrol.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** 内存环形日志，供 App 内查看/导出。不写文件、不上传。 */
object AppLog {
    private const val MAX = 500
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var version: Int = 0
        private set

    @Synchronized
    fun add(tag: String, message: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast("${fmt.format(Date())} [$tag] $message")
        version++
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun tail(n: Int): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun clear() {
        lines.clear()
        version++
    }
}
