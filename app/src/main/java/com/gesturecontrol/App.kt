package com.gesturecontrol

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        applyTheme()
        AppLog.add("App", "启动 v${BuildConfig.VERSION_NAME}（外观=${Prefs.themeMode}）")
    }

    companion object {
        /**
         * 把用户选的外观应用到 AppCompat。
         *
         * 必须在 Activity 创建之前调用（所以在 Application.onCreate 里先跑一次）；
         * 运行中再次调用时 AppCompat 会自动重建当前 Activity，界面立即切换。
         */
        fun applyTheme() {
            AppCompatDelegate.setDefaultNightMode(
                when (Prefs.themeMode) {
                    Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
