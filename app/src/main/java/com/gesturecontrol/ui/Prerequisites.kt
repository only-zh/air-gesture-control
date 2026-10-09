package com.gesturecontrol.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gesturecontrol.a11y.ControlAccessibilityService
import com.gesturecontrol.core.Prefs

/**
 * 使用前必须配好的前置条件。
 *
 * 关键点：**需要哪些条件取决于开了哪些启动方式**。
 * 默认只开悬浮球，那就只需要「无障碍 + 悬浮窗」——
 * 不应该因为没给相机权限就不让启动。
 *
 * 这也是把判定抽出来的原因：启动页要用它决定「能不能启动」，
 * 权限页要用它列出「还差什么」。
 */
class Prerequisites(
    private val activity: AppCompatActivity,
    /** 申请运行时权限的动作。由宿主用 ActivityResult API 提供，避免用已废弃的 requestPermissions */
    private val onRequestRuntime: () -> Unit
) {

    data class Item(
        val key: String,
        val title: String,
        val detail: String,
        val satisfied: Boolean,
        /** true = 不满足就不能启动；false = 只是建议 */
        val blocking: Boolean,
        val action: () -> Unit
    )

    fun check(): List<Item> = buildList {
        add(
            Item(
                key = "a11y",
                title = "无障碍服务",
                detail = if (ControlAccessibilityService.isConnected()) {
                    "已连接，可以注入手势"
                } else {
                    "未开启 —— 没有它任何动作都发不出去"
                },
                satisfied = ControlAccessibilityService.isConnected(),
                blocking = true,
                action = { openAccessibilitySettings() }
            )
        )

        if (Prefs.ballEnabled) {
            add(
                Item(
                    key = "overlay",
                    title = "悬浮窗权限",
                    detail = if (canDrawOverlay()) "已允许" else "未允许，悬浮球显示不出来",
                    satisfied = canDrawOverlay(),
                    blocking = true,
                    action = { openOverlaySettings() }
                )
            )
        }

        if (Prefs.voiceEnabled) {
            add(
                Item(
                    key = "mic",
                    title = "麦克风权限",
                    detail = if (has(Manifest.permission.RECORD_AUDIO)) "已授予" else "未授予，语音控制无法工作",
                    satisfied = has(Manifest.permission.RECORD_AUDIO),
                    blocking = true,
                    action = onRequestRuntime
                )
            )
        }

        if (Prefs.airEnabled) {
            add(
                Item(
                    key = "camera",
                    title = "相机权限",
                    detail = if (has(Manifest.permission.CAMERA)) "已授予" else "未授予，隔空手势无法工作",
                    satisfied = has(Manifest.permission.CAMERA),
                    blocking = true,
                    action = onRequestRuntime
                )
            )
        }

        add(
            Item(
                key = "notif",
                title = "通知权限",
                detail = if (notificationsOk()) {
                    "已允许，前台服务的常驻通知可见"
                } else {
                    "未允许 —— 服务仍会运行，但你看不到状态通知"
                },
                satisfied = notificationsOk(),
                blocking = false,
                action = onRequestRuntime
            )
        )

        add(
            Item(
                key = "battery",
                title = "电池优化白名单",
                detail = if (ignoringBattery()) {
                    "已在白名单里"
                } else {
                    "未加入 —— 国产 ROM 会在后台杀掉本应用"
                },
                satisfied = ignoringBattery(),
                blocking = false,
                action = { openBatterySettings() }
            )
        )
    }

    /** 不满足就不能启动的那些 */
    fun blockingMissing(): List<Item> = check().filter { it.blocking && !it.satisfied }

    fun ready(): Boolean = blockingMissing().isEmpty()

    // ------------------------------------------------------------ 单独查询

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun canDrawOverlay() = Settings.canDrawOverlays(activity)

    private fun notificationsOk(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            has(Manifest.permission.POST_NOTIFICATIONS)

    private fun ignoringBattery(): Boolean =
        activity.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(activity.packageName) == true

    // ---------------------------------------------------------------- 跳转

    private fun openAccessibilitySettings() {
        safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        safeStart(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
        )
    }

    private fun openBatterySettings() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}")
        )
        try {
            activity.startActivity(direct)
        } catch (_: Throwable) {
            safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun safeStart(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** 当前开关下需要申请的运行时权限 */
        fun neededRuntimePermissions(): Array<String> {
            val list = mutableListOf<String>()
            if (Prefs.voiceEnabled) list.add(Manifest.permission.RECORD_AUDIO)
            if (Prefs.airEnabled) list.add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return list.toTypedArray()
        }
    }
}
