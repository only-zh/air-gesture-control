package com.gesturecontrol.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.Command
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
 * 生命周期分三档，是为了省电又不牺牲响应速度：
 *   - [start]  加载模型 + 开始听音（模型加载要 1–3 秒，最贵）
 *   - [pause]  停止听音但**保留模型**，恢复时不用重新加载
 *   - [stop]   连模型一起释放
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

    /**
     * 代次计数。用来处理「正在后台加载模型时被 stop 掉」的竞态：
     * 后台线程回到主线程后如果发现代次变了，就把刚建好的资源原地释放掉。
     */
    @Volatile
    private var generation = 0

    /** 真的在采集音频（区别于 Prefs.voiceEnabled「用户想开着」） */
    @Volatile
    var isListening: Boolean = false
        private set

    /** 模型已加载，可以快速恢复 */
    val isPrepared: Boolean get() = model != null

    /** 一句话里已经触发过命令，避免 partial 和 result 重复触发 */
    @Volatile
    private var firedInUtterance = false

    /**
     * 上一次**命中命令**的时间。
     *
     * 这里刻意不用「识别到任何语音」来计时 —— 抖音视频的声音也会被麦克风识别出
     * partial 结果，那样计时器会被无限重置，「静默自动停止」永远不会触发。
     * 只有命令真的命中才算「活动」，这也才对得上设置项的文案。
     */
    private var lastCommandAt = 0L

    private val idleCheck = object : Runnable {
        override fun run() {
            if (!isListening) return
            val idle = Prefs.voiceIdleStopMs
            if (idle > 0 && lastCommandAt > 0 &&
                System.currentTimeMillis() - lastCommandAt > idle
            ) {
                AppLog.add("Voice", "已有 ${idle / 1000} 秒没命中任何命令，暂停听音省电")
                pause()
                CommandBus.publish("语音已自动暂停，点悬浮球恢复")
                return
            }
            main.postDelayed(this, IDLE_POLL_MS)
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** 加载模型并开始听音 */
    fun start() {
        if (isListening || preparing) return
        preparing = true
        val gen = ++generation
        onStatus("正在准备语音模型…")

        Thread {
            try {
                val m = model ?: run {
                    LibVosk.setLogLevel(LogLevel.WARNINGS)
                    Model(ensureModelUnpacked().absolutePath).also { model = it }
                }
                val r = Recognizer(m, SAMPLE_RATE)
                val service = SpeechService(r, SAMPLE_RATE)

                main.post {
                    if (gen != generation) {
                        // 加载途中被 stop/pause 了，把这批资源丢掉
                        quietly { service.shutdown() }
                        quietly { r.close() }
                        return@post
                    }
                    preparing = false
                    recognizer = r
                    speechService = service
                    beginListening(service)
                }
            } catch (t: Throwable) {
                preparing = false
                AppLog.add("Voice", "启动失败：${t.javaClass.simpleName} ${t.message}")
                onStatus("语音启动失败：${t.message}")
                releaseModel()
            }
        }.start()
    }

    /** 停止听音但保留模型，恢复时不用重新加载（模型加载要 1–3 秒） */
    fun pause() {
        if (!isListening && !preparing) return
        generation++
        preparing = false
        stopListening()
        AppLog.add("Voice", "语音已暂停（模型保留在内存里）")
    }

    /** 从暂停状态恢复；模型还没加载就直接走完整启动 */
    fun resume() {
        if (isListening || preparing) return
        if (model == null) {
            start()
            return
        }
        val gen = ++generation
        preparing = true
        Thread {
            try {
                val m = model
                if (m == null) {
                    main.post { preparing = false; start() }
                    return@Thread
                }
                val r = Recognizer(m, SAMPLE_RATE)
                val service = SpeechService(r, SAMPLE_RATE)
                main.post {
                    if (gen != generation) {
                        quietly { service.shutdown() }
                        quietly { r.close() }
                        return@post
                    }
                    preparing = false
                    recognizer = r
                    speechService = service
                    beginListening(service)
                }
            } catch (t: Throwable) {
                preparing = false
                AppLog.add("Voice", "恢复失败：${t.javaClass.simpleName} ${t.message}")
            }
        }.start()
    }

    /** 彻底关闭，连模型一起释放 */
    fun stop() {
        generation++
        preparing = false
        stopListening()
        onStatus("语音已停止")
    }

    private fun beginListening(service: SpeechService) {
        isListening = true
        firedInUtterance = false
        lastCommandAt = System.currentTimeMillis()
        service.startListening(this)
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, IDLE_POLL_MS)
        AppLog.add("Voice", "离线语音识别已启动")
        onStatus("语音聆听中…")
    }

    private fun stopListening() {
        isListening = false
        main.removeCallbacks(idleCheck)
        val service = speechService
        val rec = recognizer
        speechService = null
        recognizer = null
        quietly { service?.stop() }
        quietly { service?.shutdown() }
        quietly { rec?.close() }
    }

    private fun releaseModel() {
        quietly { model?.close() }
        model = null
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
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
        // 这里**不**重置 idle 计时：视频声音也会产生 partial，那样计时器会被无限重置
        if (Prefs.voiceShowPartial) {
            CommandBus.publish("听到：$text")
        }
        // 快速路径：partial 已经精确等于某个命令词、且本句还没触发过，就立刻执行
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

    private fun dispatch(cmd: Command, keyword: String, score: Double, text: String) {
        // 只有命令命中才算「活动」，用来驱动 idle 自动暂停
        lastCommandAt = System.currentTimeMillis()
        AppLog.add("Voice", "「$text」-> ${cmd.label}（命中「$keyword」相似度 ${"%.2f".format(score)}）")
        CommandBus.dispatch(cmd, "语音")
    }

    override fun onError(exception: Exception?) {
        AppLog.add("Voice", "识别出错：${exception?.javaClass?.simpleName} ${exception?.message}")
        onStatus("语音出错：${exception?.message}")
        main.post { pause() }
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
        private const val IDLE_POLL_MS = 5000L
    }
}
