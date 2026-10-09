package com.gesturecontrol.core

/**
 * 当前前台应用包名的全局广播点。
 *
 * 无障碍服务里的 ScreenWatcher 负责更新它，ControlService 订阅它，
 * 用来决定「现在值不值得开着摄像头和麦克风」。
 *
 * 之前只有 Prefs.targetOnly 挡了「命令的执行」，没挡「数据的采集」——
 * 结果就是切到微信、甚至锁屏放进兜里，摄像头和麦克风都还在全速跑。
 */
object ForegroundState {

    /** 空字符串表示「还不知道」，此时调用方应保守放行而不是暂停 */
    @Volatile
    var packageName: String = ""
        private set

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun update(pkg: CharSequence?) {
        val value = pkg?.toString().orEmpty()
        if (value.isEmpty() || value == packageName) return
        packageName = value
        listener?.invoke(value)
    }

    fun isTarget(pkg: String): Boolean =
        pkg == Prefs.douyinPackage || pkg == Prefs.DOUYIN_LITE
}
