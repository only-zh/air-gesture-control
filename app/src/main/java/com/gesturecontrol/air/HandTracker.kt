package com.gesturecontrol.air

import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.gesturecontrol.core.AppLog
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 前置摄像头 + MediaPipe HandLandmarker（LIVE_STREAM）。
 *
 * 640x480、KEEP_ONLY_LATEST，手机上大约 15-25fps。
 *
 * 摄像头是**整个 App 最大的耗电来源**，所以生命周期分三档：
 *   - [start]   加载模型 + 绑定摄像头开始采集
 *   - [pause]   只解绑摄像头、**保留已加载的模型**（模型重载要几百毫秒）
 *   - [stop]    解绑并释放模型
 *
 * ControlService 会在息屏或切到非目标 App 时调 pause()，
 * 这样「锁屏放兜里还在跑摄像头」这种事就不会发生了。
 */
class HandTracker(
    private val context: Context,
    private val onResult: (HandLandmarkerResult, Long) -> Unit,
    private val onError: (String) -> Unit
) {

    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null
    private var owner: LifecycleOwner? = null

    @Volatile
    private var lastTimestamp = 0L

    /** 真的在采集（区别于 Prefs.airEnabled「用户想开着」） */
    @Volatile
    var isCapturing: Boolean = false
        private set

    /** 模型已加载，可以快速恢复 */
    val isPrepared: Boolean get() = landmarker != null

    // ---------------------------------------------------------------- 生命周期

    fun start(lifecycleOwner: LifecycleOwner) {
        owner = lifecycleOwner
        if (landmarker == null) {
            try {
                landmarker = createLandmarker()
            } catch (t: Throwable) {
                AppLog.add("Air", "MediaPipe 初始化失败：${t.javaClass.simpleName} ${t.message}")
                onError("手势模型初始化失败：${t.message}")
                return
            }
        }
        bindCamera()
    }

    /** 只解绑摄像头，模型留着，恢复时不用重新加载 */
    fun pause() {
        if (!isCapturing) return
        unbindCamera()
        AppLog.add("Air", "隔空手势已暂停（模型保留在内存里）")
    }

    fun resume() {
        if (isCapturing) return
        if (owner == null) return
        bindCamera()
    }

    fun stop() {
        unbindCamera()
        quietly { landmarker?.close() }
        landmarker = null
        owner = null
        AppLog.add("Air", "隔空手势已停止")
    }

    // ---------------------------------------------------------------- 绑定

    private fun bindCamera() {
        if (isCapturing) return
        val lifecycleOwner = owner ?: return

        val cached = provider
        if (cached != null) {
            doBind(cached, lifecycleOwner)
            return
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                doBind(cameraProvider, lifecycleOwner)
            } catch (t: Throwable) {
                AppLog.add("Air", "相机初始化失败：${t.javaClass.simpleName} ${t.message}")
                onError("相机启动失败：${t.message}")
                unbindCamera()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun doBind(cameraProvider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        try {
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            executor = Executors.newSingleThreadExecutor()
            imageAnalysis.setAnalyzer(executor!!) { proxy -> analyze(proxy) }
            analysis = imageAnalysis

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                imageAnalysis
            )
            isCapturing = true
            lastTimestamp = 0L
            AppLog.add("Air", "前置摄像头已启动，隔空手势就绪")
        } catch (t: Throwable) {
            AppLog.add("Air", "相机绑定失败：${t.javaClass.simpleName} ${t.message}")
            onError("相机启动失败：${t.message}")
            unbindCamera()
        }
    }

    private fun unbindCamera() {
        isCapturing = false
        analysis?.clearAnalyzer()
        analysis = null
        quietly { provider?.unbindAll() }
        executor?.shutdown()
        executor = null
    }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- 推理

    private fun createLandmarker(): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(Delegate.CPU)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ ->
                onResult(result, SystemClock.uptimeMillis())
            }
            .setErrorListener { error ->
                AppLog.add("Air", "MediaPipe 回调错误：${error.message}")
            }
            .build()

        return HandLandmarker.createFromOptions(context, options)
    }

    private fun analyze(proxy: ImageProxy) {
        val tracker = landmarker
        if (tracker == null || !isCapturing) {
            proxy.close()
            return
        }
        try {
            val bitmap = proxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val options = ImageProcessingOptions.builder()
                .setRotationDegrees(proxy.imageInfo.rotationDegrees)
                .build()

            // MediaPipe 要求时间戳严格递增，同一毫秒内来了两帧就手动 +1
            val now = SystemClock.uptimeMillis()
            val timestamp = if (now > lastTimestamp) now else lastTimestamp + 1
            lastTimestamp = timestamp

            tracker.detectAsync(mpImage, options, timestamp)
        } catch (t: Throwable) {
            AppLog.add("Air", "处理帧失败：${t.javaClass.simpleName} ${t.message}")
        } finally {
            proxy.close()
        }
    }

    companion object {
        private const val MODEL_ASSET = "hand_landmarker.task"
    }
}
