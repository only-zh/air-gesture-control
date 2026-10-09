package com.gesturecontrol.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.gesturecontrol.MainActivity
import com.gesturecontrol.R
import com.gesturecontrol.a11y.ControlAccessibilityService
import com.gesturecontrol.air.AirGestureController
import com.gesturecontrol.air.HandTracker
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.CommandBus
import com.gesturecontrol.core.ForegroundState
import com.gesturecontrol.core.Prefs
import com.gesturecontrol.core.RuntimeState
import com.gesturecontrol.overlay.FloatingBallController
import com.gesturecontrol.voice.VoiceEngine

/**
 * 常驻前台服务：悬浮球 + 离线语音 + 隔空手势都在这里托管，
 * 只发一条通知，避免国产 ROM 上多服务互相拖累。
 *
 * 真实的手势注入不在这里，而是在 ControlAccessibilityService 里。
 *
 * ## 省电策略
 *
 * 摄像头是整个 App 最大的耗电来源，麦克风次之。所以采集（而不是命令的执行）
 * 会被两件事掐断：
 *
 *  1. **息屏** -> 立即暂停采集。以前锁屏放进兜里，摄像头和麦克风还在全速跑。
 *  2. **切到非目标 App** -> 延迟 [NON_TARGET_GRACE_MS] 后暂停。
 *     延迟是必须的：调音量、来通知、弹权限框都会让前台包名短暂变化，
 *     一有变化就关摄像头会导致疯狂地绑定/解绑。
 *
 * 暂停时**保留已加载的模型**（Vosk 模型 65MB、加载要 1-3 秒，MediaPipe 也要几百毫秒），
 * 所以切回抖音能立刻恢复。
 */
class ControlService : LifecycleService() {

    private val handler = Handler(Looper.getMainLooper())

    private var ball: FloatingBallController? = null
    private var voice: VoiceEngine? = null
    private var tracker: HandTracker? = null
    private val airController = AirGestureController()

    @Volatile
    private var screenOn = true

    /** 前台切到非目标 App 后的宽限，避开音量面板之类的瞬时窗口 */
    private val foregroundDebounce = Runnable { applySettings() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    AppLog.add("Service", "屏幕已关闭 -> 暂停摄像头与麦克风")
                    applySettings()
                }

                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    AppLog.add("Service", "屏幕已点亮 -> 恢复采集")
                    applySettings()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()

        screenOn = isScreenOnNow()
        registerScreenReceiver()
        ForegroundState.listener = { pkg -> onForegroundChanged(pkg) }

        startForegroundCompat("已就绪")

        CommandBus.statusListener = { text -> ball?.showStatus(text) }

        if (Prefs.ballEnabled) attachBall()
        applySettings()
        AppLog.add("Service", "控制服务已创建（息屏=${!screenOn}）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            AppLog.add("Service", "收到停止指令")
            stopSelf()
            return START_NOT_STICKY
        }
        applySettings()
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        CommandBus.statusListener = null
        ForegroundState.listener = null
        handler.removeCallbacks(foregroundDebounce)
        quietly { unregisterReceiver(screenReceiver) }
        releaseVoice()
        releaseAir()
        ball?.hide()
        ball = null
        AppLog.add("Service", "控制服务已销毁")
        super.onDestroy()
    }

    // ------------------------------------------------------------ 状态应用

    /** 根据 Prefs + 屏幕状态 + 前台 App 重新对齐运行状态 */
    fun applySettings() {
        handler.post {
            if (Prefs.ballEnabled) attachBall() else detachBall()

            val allowed = captureAllowed()

            // 必须**先**声明前台服务类型再打开摄像头/麦克风。
            // Android 14 是在设备被访问的那一刻检查 type 的，而 HandTracker 首次
            // 绑定相机是异步的，顺序反了会直接抛 SecurityException。
            updateNotification()

            syncVoice(allowed)
            syncAir(allowed)

            ball?.refreshToggles()
            // 再刷一次通知文案（这一次才是「开 / 待机」的真实状态）
            updateNotification()
        }
    }

    /**
     * 现在值不值得开着摄像头和麦克风。
     *
     * 拿不准的时候一律**放行**——宁可多耗点电，也不要让用户觉得「功能莫名其妙坏了」。
     */
    private fun captureAllowed(): Boolean {
        if (!screenOn) return false
        if (!Prefs.targetOnly) return true
        // 无障碍没连上就根本不知道前台是谁，保守放行
        if (!ControlAccessibilityService.isConnected()) return true
        val fg = ForegroundState.packageName
        if (fg.isEmpty()) return true
        return ForegroundState.isTarget(fg)
    }

    private fun onForegroundChanged(pkg: String) {
        // 回到目标 App 立刻恢复；离开则等一会儿，避开音量面板/通知这类瞬时窗口
        val delay = if (ForegroundState.isTarget(pkg)) 0L else NON_TARGET_GRACE_MS
        handler.removeCallbacks(foregroundDebounce)
        handler.postDelayed(foregroundDebounce, delay)
    }

    fun toggleVoice(): Boolean {
        Prefs.voiceEnabled = !Prefs.voiceEnabled
        applySettings()
        return Prefs.voiceEnabled
    }

    fun toggleAir(): Boolean {
        Prefs.airEnabled = !Prefs.airEnabled
        applySettings()
        return Prefs.airEnabled
    }

    // -------------------------------------------------------------- 悬浮球

    private fun attachBall() {
        if (ball?.isShowing == true) return
        val controller = FloatingBallController(
            context = this,
            stateProvider = ::runtimeState,
            onOpenApp = { openApp() },
            onToggleVoice = { toggleVoice() },
            onToggleAir = { toggleAir() }
        )
        controller.show()
        ball = controller
    }

    private fun detachBall() {
        ball?.hide()
        ball = null
    }

    private fun runtimeState(): RuntimeState = RuntimeState(
        accessibility = ControlAccessibilityService.isConnected(),
        voiceEnabled = Prefs.voiceEnabled,
        voiceCapturing = voice?.isListening == true,
        airEnabled = Prefs.airEnabled,
        airCapturing = tracker?.isCapturing == true
    )

    // ---------------------------------------------------------------- 语音

    /**
     * 幂等：每次 applySettings 都会调一遍。
     * 关掉 -> 释放模型；暂停 -> 保留模型只停麦克风；允许 -> 恢复。
     */
    private fun syncVoice(allowed: Boolean) {
        if (!Prefs.voiceEnabled) {
            releaseVoice()
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            AppLog.add("Service", "没有录音权限，语音控制无法开启")
            Prefs.voiceEnabled = false
            ball?.showStatus("请先授予麦克风权限")
            releaseVoice()
            return
        }
        val engine = voice ?: VoiceEngine(this) { status -> ball?.showStatus(status) }
            .also { voice = it }

        if (allowed) {
            if (engine.isPrepared) engine.resume() else engine.start()
        } else {
            engine.pause()
        }
    }

    private fun releaseVoice() {
        voice?.stop()
        voice = null
    }

    // ------------------------------------------------------------ 隔空手势

    private fun syncAir(allowed: Boolean) {
        if (!Prefs.airEnabled) {
            releaseAir()
            return
        }
        if (!hasPermission(Manifest.permission.CAMERA)) {
            AppLog.add("Service", "没有相机权限，隔空手势无法开启")
            Prefs.airEnabled = false
            ball?.showStatus("请先授予相机权限")
            releaseAir()
            return
        }
        val handler = tracker ?: HandTracker(
            context = this,
            onResult = { result, timestamp ->
                val showDebug = ball?.isExpanded == true
                airController.debugEnabled = showDebug
                airController.onResult(result, timestamp)
                if (showDebug) ball?.showDebug(airController.debugInfo)
            },
            onError = { message -> ball?.showStatus(message) }
        ).also { tracker = it }

        if (!allowed) {
            handler.pause()
        } else if (handler.isCapturing || handler.isPrepared) {
            // 模型已加载，只需要重新绑定摄像头
            handler.resume()
        } else {
            handler.start(this)
        }
    }

    private fun releaseAir() {
        tracker?.stop()
        tracker = null
    }

    // ------------------------------------------------------------ 通知/前台

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ControlService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val state = runtimeState()
        val detail = buildString {
            append("无障碍：")
            append(if (state.accessibility) "已连接" else "未连接")
            append(" · 语音：")
            append(
                when {
                    !state.voiceEnabled -> "关"
                    state.voiceCapturing -> "开"
                    else -> "待机"
                }
            )
            append(" · 手势：")
            append(
                when {
                    !state.airEnabled -> "关"
                    state.airCapturing -> "开"
                    else -> "待机"
                }
            )
            if (status.isNotEmpty()) append(" · ").append(status)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "打开设置", open)
            .addAction(0, "停止", stop)
            .build()
    }

    /**
     * 同时刷新通知内容和前台服务类型。
     *
     * 必须重调 startForeground：Android 14 要求「正在用麦克风/相机」时前台服务
     * 必须声明对应的 type，否则后台访问麦克风会被系统直接掐掉。
     */
    private fun updateNotification() {
        val notification = buildNotification("")
        val types = foregroundTypes()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
                startForeground(NOTIF_ID, notification, types)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            AppLog.add("Service", "刷新前台服务类型失败：${t.message}")
            quietly {
                getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification)
            }
        }
    }

    /**
     * 前台服务类型。
     *
     * 这里用「**打算**用什么能力」而不是「正在采集」来判断 —— 因为系统是在
     * 设备被访问的那一刻检查 type 的，而相机绑定是异步的，按实际状态声明会慢一拍。
     * 声明即将使用的类型才是正确且安全的做法。
     */
    private fun foregroundTypes(): Int {
        var types = 0
        if (Build.VERSION.SDK_INT >= 34) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val allowed = captureAllowed()
            if (Prefs.voiceEnabled && allowed && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (Prefs.airEnabled && allowed && hasPermission(Manifest.permission.CAMERA)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        return types
    }

    private fun startForegroundCompat(status: String) {
        val notification = buildNotification(status)
        val types = foregroundTypes()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
                startForeground(NOTIF_ID, notification, types)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            AppLog.add("Service", "startForeground(带类型) 失败：${t.message}，退回普通前台服务")
            quietly { startForeground(NOTIF_ID, notification) }
        }
    }

    private fun registerScreenReceiver() {
        try {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (t: Throwable) {
            AppLog.add("Service", "注册息屏广播失败：${t.message}")
        }
    }

    private fun isScreenOnNow(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    private fun openApp() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (t: Throwable) {
            AppLog.add("Service", "打开设置页失败：${t.message}")
        }
    }

    companion object {
        const val ACTION_STOP = "com.gesturecontrol.action.STOP"
        private const val CHANNEL_ID = "gesture_control"
        private const val NOTIF_ID = 1001

        /** 切到非目标 App 后的宽限：避开音量面板、通知横幅、权限弹窗这类瞬时窗口 */
        private const val NON_TARGET_GRACE_MS = 4000L

        @Volatile
        var instance: ControlService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ControlService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ControlService::class.java))
        }

        /** 设置页改完开关后调用 */
        fun refresh() {
            instance?.applySettings()
        }
    }
}
