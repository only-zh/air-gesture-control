package com.gesturecontrol.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.CommandBus
import com.gesturecontrol.core.CommandMatcher
import com.gesturecontrol.core.Prefs
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream

/**
 * 阶段一：离线语音控制。
 *
 * 用 Vosk 中文小模型（约 44MB，内置在 assets 里，装完即用、不联网）。
 * 识别结果 -> CommandMatcher 模糊匹配 -> CommandBus。
 *
 * 注意：Vosk 中文模型的词典是**单字级**的，双字命令词（如「上滑」）没法直接当 grammar
 * 约束用，所以这里走自由识别 + 模糊匹配。真机上如果误触发多，调高「匹配阈值」即可。
 */
class VoiceEngine(
    private val context: Context,
    private val onStatus: (String) -> Unit
) : RecognitionListener {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var model: Model? = null

    @Volatile
    private var recognizer: Recognizer? = null

    @Volatile
    private var speechService: SpeechService? = null

    @Volatile
    private var preparing = false

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 一句话里已经触发过命令，避免 partial 和 result 重复触发 */
    @Volatile
    private var firedInUtterance = false

    private var lastActivityAt = 0L

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val idle = Prefs.voiceIdleStopMs
            if (idle > 0 && lastActivityAt > 0 &&
                System.currentTimeMillis() - lastActivityAt > idle
            ) {
                AppLog.add("Voice", "静默超过 ${idle}ms，自动停止听音省电")
                onStatus("已自动停止听音")
                stop()
                return
            }
            main.postDelayed(this, 5000L)
        }
    }

    // ---------------------------------------------------------------- 生命周期

    fun start() {
        if (isRunning || preparing) return
        preparing = true
        onStatus("正在准备语音模型…")

        Thread {
            try {
                val dir = ensureModelUnpacked()
                LibVosk.setLogLevel(LogLevel.WARNINGS)

                val m = Model(dir.absolutePath)
                val r = Recognizer(m, SAMPLE_RATE)
                val service = SpeechService(r, SAMPLE_RATE)

                model = m
                recognizer = r
                speechService = service

                main.post {
                    preparing = false
                    isRunning = true
                    lastActivityAt = System.currentTimeMillis()
                    firedInUtterance = false
                    service.startListening(this)
                    main.postDelayed(idleCheck, 5000L)
                    AppLog.add("Voice", "离线语音识别已启动")
                    onStatus("语音聆听中…")
                }
            } catch (t: Throwable) {
                preparing = false
                AppLog.add("Voice", "启动失败：${t.javaClass.simpleName} ${t.message}")
                onStatus("语音启动失败：${t.message}")
                release()
            }
        }.start()
    }

    fun stop() {
        if (!isRunning && !preparing) return
        isRunning = false
        preparing = false
        main.removeCallbacks(idleCheck)
        try {
            speechService?.stop()
        } catch (_: Throwable) {
        }
        release()
        AppLog.add("Voice", "语音识别已停止")
        onStatus("语音已停止")
    }

    private fun release() {
        try {
            speechService?.shutdown()
        } catch (_: Throwable) {
        }
        speechService = null
        try {
            recognizer?.close()
        } catch (_: Throwable) {
        }
        recognizer = null
        try {
            model?.close()
        } catch (_: Throwable) {
        }
        model = null
    }

    // ------------------------------------------------------------- 模型解包

    private fun ensureModelUnpacked(): File {
        val target = File(context.filesDir, "vosk-model-cn")
        val marker = File(target, "conf/model.conf")
        if (marker.exists()) {
            AppLog.add("Voice", "模型已就绪：${target.absolutePath}")
            return target
        }
        AppLog.add("Voice", "首次运行，正在从 assets 解包语音模型（约 44MB）…")
        onStatus("首次运行正在解包语音模型…")
        target.deleteRecursively()
        target.mkdirs()
        copyAssetDir(ASSET_MODEL_DIR, target)
        if (!marker.exists()) {
            throw IllegalStateException("模型解包不完整，assets 里可能没有 $ASSET_MODEL_DIR")
        }
        AppLog.add("Voice", "模型解包完成")
        return target
    }

    /**
     * assets 里没法直接列出「目录还是文件」，用 list() 返回空数组来判断是文件。
     * Vosk 模型里没有空目录，所以这个判断是安全的（也是 vosk-android 官方 demo 的做法）。
     */
    private fun copyAssetDir(assetPath: String, target: File) {
        val assets = context.assets
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(target, child))
        }
    }

    // --------------------------------------------------------- Vosk 回调

    override fun onPartialResult(hypothesis: String?) {
        val text = extract(hypothesis, "partial") ?: return
        lastActivityAt = System.currentTimeMillis()
        if (Prefs.voiceShowPartial) {
            CommandBus.publish("听到：$text")
        }
        // 快速路径：只有 partial 已经精确等于某个命令词、且本句还没触发过，才立刻执行
        if (firedInUtterance) return
        val m = CommandMatcher.match(text, Prefs.matchThreshold) ?: return
        if (m.score >= 0.99) {
            firedInUtterance = true
            dispatch(m.command, m.keyword, m.score, text)
        }
    }

    override fun onResult(hypothesis: String?) {
        handleFinal(hypothesis, "result")
    }

    override fun onFinalResult(hypothesis: String?) {
        handleFinal(hypothesis, "final")
    }

    private fun handleFinal(hypothesis: String?, kind: String) {
        val text = extract(hypothesis, "text")
        val already = firedInUtterance
        firedInUtterance = false
        lastActivityAt = System.currentTimeMillis()
        if (text == null || already) return
        AppLog.add("Voice", "识别到「$text」($kind)")
        val m = CommandMatcher.match(text, Prefs.matchThreshold)
        if (m == null) {
            AppLog.add("Voice", "「$text」没有匹配到任何命令")
            CommandBus.publish("没听懂：$text")
            return
        }
        dispatch(m.command, m.keyword, m.score, text)
    }

    private fun dispatch(cmd: com.gesturecontrol.core.Command, keyword: String, score: Double, text: String) {
        AppLog.add("Voice", "「$text」-> ${cmd.label}（命中「$keyword」相似度 ${"%.2f".format(score)}）")
        CommandBus.dispatch(cmd, "语音")
    }

    override fun onError(exception: Exception?) {
        AppLog.add("Voice", "识别出错：${exception?.javaClass?.simpleName} ${exception?.message}")
        onStatus("语音出错：${exception?.message}")
        main.post { stop() }
    }

    override fun onTimeout() {
        AppLog.add("Voice", "识别超时")
    }

    /** Vosk 返回的是 {"partial":"..."} 或 {"text":"..."} 这样的 JSON */
    private fun extract(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        val value = try {
            JSONObject(json).optString(key, "")
        } catch (_: Throwable) {
            return null
        }
        val cleaned = value.replace(" ", "").trim()
        return cleaned.ifEmpty { null }
    }

    companion object {
        private const val SAMPLE_RATE = 16000.0f
        private const val ASSET_MODEL_DIR = "model-cn"
    }
}
