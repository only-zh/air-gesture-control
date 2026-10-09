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
 * 640x480、KEEP_ONLY_LATEST，手机上大约 15-25fps，足够判定挥手和捏合。
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

    @Volatile
    private var lastTimestamp = 0L

    val isRunning: Boolean get() = analysis != null

    fun start(lifecycleOwner: LifecycleOwner) {
        if (isRunning) return
        try {
            landmarker = createLandmarker()
        } catch (t: Throwable) {
            AppLog.add("Air", "MediaPipe 初始化失败：${t.javaClass.simpleName} ${t.message}")
            onError("手势模型初始化失败：${t.message}")
            return
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

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
                AppLog.add("Air", "前置摄像头已启动，隔空手势就绪")
            } catch (t: Throwable) {
                AppLog.add("Air", "相机启动失败：${t.javaClass.simpleName} ${t.message}")
                onError("相机启动失败：${t.message}")
                stop()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        analysis?.clearAnalyzer()
        analysis = null
        try {
            provider?.unbindAll()
        } catch (_: Throwable) {
        }
        provider = null
        executor?.shutdown()
        executor = null
        try {
            landmarker?.close()
        } catch (_: Throwable) {
        }
        landmarker = null
        AppLog.add("Air", "隔空手势已停止")
    }

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
        if (tracker == null) {
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
