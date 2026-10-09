package com.gesturecontrol

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.gesturecontrol.core.AppLog
import com.gesturecontrol.core.Prefs
import com.gesturecontrol.service.ControlService
import com.gesturecontrol.ui.AppPage
import com.gesturecontrol.ui.PageHost
import com.gesturecontrol.ui.Prerequisites
import com.gesturecontrol.ui.UiKit
import com.gesturecontrol.ui.pages.LaunchPage
import com.gesturecontrol.ui.pages.LogsPage
import com.gesturecontrol.ui.pages.PermissionsPage
import com.gesturecontrol.ui.pages.SettingsPage

/**
 * 侧边栏宿主。
 *
 * 四个页面：启动 / 权限 / 日志 / 自定义设置。
 *
 * 为什么从单页改成侧边栏：原来所有东西铺在一条长滚动列表里 ——
 * 权限、开关、滑块、测试按钮、日志混在一起，改一个参数要滚很久，
 * 而「启动」这个最常用的动作反而被埋在最上面。
 *
 * 页面内容由 ui/pages 下的类负责画，这里只管导航、折叠状态和共用逻辑。
 */
class MainActivity : AppCompatActivity(), PageHost {

    override lateinit var ui: UiKit
        private set

    override val activity: AppCompatActivity get() = this

    private lateinit var drawer: DrawerLayout
    private lateinit var sidebar: LinearLayout
    private lateinit var barHolder: LinearLayout
    private lateinit var column: LinearLayout

    private var currentPage = AppPage.LAUNCH

    /** 折叠分区的状态，跨页面重建保留 */
    private val sectionState = mutableMapOf<String, Boolean>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        toast(if (denied.isEmpty()) "权限已授予" else "被拒绝：${denied.joinToString()}")
        render()
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        setContentView(buildRoot())

        // 侧边栏打开时，返回键先关侧边栏
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(Gravity.START)) {
                    drawer.closeDrawers()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        render()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页回来时权限状态可能变了，重建一次
        render()
    }

    // ---------------------------------------------------------------- 骨架

    private fun buildRoot(): DrawerLayout {
        drawer = DrawerLayout(this).apply { setBackgroundColor(ui.bg) }

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 顶栏固定在内容区上方，不随页面滚动 —— 否则滚下去就打不开侧边栏了
        barHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(barHolder)

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(40))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(column)
        }
        content.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        drawer.addView(
            content,
            DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.surface)
        }
        drawer.addView(
            sidebar,
            DrawerLayout.LayoutParams(ui.dp(288), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
            }
        )
        return drawer
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        renderTopBar()
        renderSidebar()
        renderPage()
    }

    private fun renderTopBar() {
        barHolder.removeAllViews()
        val running = ControlService.isRunning()
        val ready = Prerequisites(this) { }.ready()
        val statusText: String
        val ok: Boolean
        val warn: Boolean
        when {
            running -> {
                statusText = "运行中"; ok = true; warn = false
            }

            ready -> {
                statusText = "已就绪"; ok = true; warn = false
            }

            else -> {
                statusText = "待配置"; ok = false; warn = true
            }
        }
        barHolder.addView(
            ui.topBar(
                title = currentPage.title,
                statusText = statusText,
                statusOk = ok,
                statusWarn = warn,
                onMenu = { drawer.openDrawer(Gravity.START) }
            )
        )
    }

    private fun renderSidebar() {
        sidebar.removeAllViews()
        val pad = ui.dp(14)
        sidebar.setPadding(pad, ui.dp(22), pad, ui.dp(18))

        sidebar.addView(ui.text("隔空控制", 20f, ui.onSurface, bold = true))
        sidebar.addView(
            ui.text("v${BuildConfig.VERSION_NAME}", 11f, ui.onSurfaceFaint).apply {
                setPadding(0, ui.dp(2), 0, ui.dp(16))
            }
        )

        AppPage.entries.forEach { page ->
            ui.sidebarItem(
                sidebar, page.title, page.subtitle,
                selected = page == currentPage
            ) {
                openPage(page)
            }
        }

        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(22), 0, 0)
        }
        statusBox.addView(
            ui.text("当前状态", 11f, ui.onSurfaceFaint).apply {
                setPadding(ui.dp(6), 0, 0, ui.dp(4))
            }
        )
        val running = ControlService.isRunning()
        ui.sidebarStatus(statusBox, "控制服务", if (running) "运行中" else "已停止", running)

        val modes = buildList {
            if (Prefs.ballEnabled) add("悬浮球")
            if (Prefs.voiceEnabled) add("语音")
            if (Prefs.airEnabled) add("手势")
        }
        ui.sidebarStatus(
            statusBox, "已开启", modes.joinToString("·").ifEmpty { "无" }, modes.isNotEmpty()
        )
        ui.sidebarStatus(
            statusBox, "目标应用",
            if (Prefs.douyinPackage == Prefs.DOUYIN_LITE) "抖音极速版" else "抖音",
            true
        )
        sidebar.addView(statusBox)
    }

    private fun renderPage() {
        column.removeAllViews()
        when (currentPage) {
            AppPage.LAUNCH -> LaunchPage(this).build(column)
            AppPage.PERMISSIONS -> PermissionsPage(this).build(column)
            AppPage.LOGS -> LogsPage(this).build(column)
            AppPage.SETTINGS -> SettingsPage(this).build(column)
        }
    }

    // ---------------------------------------------------------------- PageHost

    override fun openPage(page: AppPage) {
        currentPage = page
        drawer.closeDrawers()
        render()
    }

    override fun refreshCurrentPage() {
        render()
    }

    override fun requestRuntimePermissions() {
        permissionLauncher.launch(Prerequisites.neededRuntimePermissions())
    }

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun sectionExpanded(key: String, default: Boolean): Boolean =
        sectionState[key] ?: default

    override fun rememberSection(key: String): (Boolean) -> Unit = { value ->
        sectionState[key] = value
    }
}
