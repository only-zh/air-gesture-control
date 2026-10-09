package com.gesturecontrol.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.gesturecontrol.MainActivity
import com.gesturecontrol.R
import com.gesturecontrol.air.AirGestureController
import com.gesturecontrol.air.HandTracker
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.CommandBus
import com.gesturecontrol.core.Prefs
import com.gesturecontrol.overlay.FloatingBallController
import com.gesturecontrol.voice.VoiceEngine

/**
 * 常驻前台服务：悬浮球 + 离线语音 + 隔空手势都在这里托管，
 * 只发一条通知，避免国产 ROM 上多服务互相拖累。
 *
 * 真实的手势注入不在这里，而是在 ControlAccessibilityService 里。
 */
class ControlService : LifecycleService() {

    private val handler = Handler(Looper.getMainLooper())

    private var ball: FloatingBallController? = null
    private var voice: VoiceEngine? = null
    private var tracker: HandTracker? = null
    private val airController = AirGestureController()

    @Volatile
    private var voiceRunning = false

    @Volatile
    private var airRunning = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForegroundCompat("已就绪")

        CommandBus.statusListener = { text -> ball?.showStatus(text) }

        if (Prefs.ballEnabled) attachBall()
        applySettings()
        AppLog.add("Service", "控制服务已创建")
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
        stopVoice()
        stopAir()
        ball?.hide()
        ball = null
        AppLog.add("Service", "控制服务已销毁")
        super.onDestroy()
    }

    // ------------------------------------------------------------ 状态应用

    /** 根据 Prefs 重新对齐运行状态（设置页改完开关后调用） */
    fun applySettings() {
        handler.post {
            if (Prefs.ballEnabled) attachBall() else detachBall()

            if (Prefs.voiceEnabled) {
                if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    AppLog.add("Service", "没有录音权限，语音控制无法开启")
                    Prefs.voiceEnabled = false
                    ball?.showStatus("请先授予麦克风权限")
                } else if (!voiceRunning) {
                    startVoice()
                }
            } else if (voiceRunning) {
                stopVoice()
            }

            if (Prefs.airEnabled) {
                if (!hasPermission(Manifest.permission.CAMERA)) {
                    AppLog.add("Service", "没有相机权限，隔空手势无法开启")
                    Prefs.airEnabled = false
                    ball?.showStatus("请先授予相机权限")
                } else if (!airRunning) {
                    startAir()
                }
            } else if (airRunning) {
                stopAir()
            }

            ball?.refreshToggles()
            updateNotification()
        }
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

    // ---------------------------------------------------------------- 语音

    private fun startVoice() {
        voiceRunning = true
        val engine = VoiceEngine(this) { status -> ball?.showStatus(status) }
        voice = engine
        engine.start()
        updateNotification()
    }

    private fun stopVoice() {
        voiceRunning = false
        voice?.stop()
        voice = null
        updateNotification()
    }

    // ------------------------------------------------------------ 隔空手势

    private fun startAir() {
        airRunning = true
        val t = HandTracker(
            context = this,
            onResult = { result, timestamp ->
                airController.onResult(result, timestamp)
                ball?.showDebug(airController.debugInfo)
            },
            onError = { message -> ball?.showStatus(message) }
        )
        tracker = t
        t.start(this)
        updateNotification()
    }

    private fun stopAir() {
        airRunning = false
        tracker?.stop()
        tracker = null
        updateNotification()
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
        val detail = buildString {
            append("无障碍：")
            append(if (com.gesturecontrol.a11y.ControlAccessibilityService.isConnected()) "已连接" else "未连接")
            append(" · 语音：")
            append(if (voiceRunning) "开" else "关")
            append(" · 手势：")
            append(if (airRunning) "开" else "关")
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
            try {
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIF_ID, notification)
            } catch (_: Throwable) {
            }
        }
    }

    /** 前台服务类型必须和「当前真的在用的能力」一致，否则 Android 14 会直接抛异常 */
    private fun foregroundTypes(): Int {
        var types = 0
        if (Build.VERSION.SDK_INT >= 34) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (voiceRunning && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (airRunning && hasPermission(Manifest.permission.CAMERA)) {
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
            try {
                startForeground(NOTIF_ID, notification)
            } catch (t2: Throwable) {
                AppLog.add("Service", "startForeground 彻底失败：${t2.message}")
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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

        @Volatile
        var instance: ControlService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            val intent = Intent(context, ControlService::class.java)
            ContextCompat.startForegroundService(context, intent)
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
