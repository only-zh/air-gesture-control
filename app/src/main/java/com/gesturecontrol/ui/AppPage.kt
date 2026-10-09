package com.gesturecontrol.ui

import androidx.appcompat.app.AppCompatActivity

/**
 * 侧边栏的四个页面。
 *
 * 用侧边栏而不是长滚动页，是因为原来的单页把所有东西铺在一起：
 * 开关、权限、滑块、测试按钮、日志混在一条长列表里，
 * 想改一个参数要滚很久，而且「启动」这个最常用的动作被埋在最上面。
 */
enum class AppPage(val title: String, val subtitle: String) {
    LAUNCH("启动", "开关与目标应用"),
    PERMISSIONS("权限", "使用前必须配好的前置条件"),
    LOGS("日志", "命令测试与运行记录"),
    SETTINGS("自定义设置", "参数微调与校准")
}

/**
 * 页面需要从宿主（MainActivity）拿到的东西。
 *
 * 每个页面只负责「把自己的内容画到给定的容器里」，
 * 导航、折叠状态、权限跳转这些共用逻辑都留在宿主，避免重复。
 */
interface PageHost {
    val ui: UiKit
    val activity: AppCompatActivity

    /** 切换到某个页面 */
    fun openPage(page: AppPage)

    /** 重建当前页面（改完设置后调用） */
    fun refreshCurrentPage()

    fun toast(message: String)

    /** 申请运行时权限（麦克风/相机/通知），由宿主用 ActivityResult API 实现 */
    fun requestRuntimePermissions()

    /** 折叠分区的状态跨重建保留 */
    fun sectionExpanded(key: String, default: Boolean): Boolean
    fun rememberSection(key: String): (Boolean) -> Unit
}
