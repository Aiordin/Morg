package aiordin.morg

import aiordin.morg.core.Onboarding
import aiordin.morg.core.PermissionStatus
import aiordin.morg.core.Permissions
import aiordin.morg.launch.InstanceLauncher
import aiordin.morg.launch.LaunchMode
import aiordin.morg.launch.WindowLaunchDecision
import aiordin.morg.launch.WindowMode
import aiordin.morg.launch.WindowProbe
import aiordin.morg.launch.WindowProbeActivity
import aiordin.morg.launch.WindowProbeRecord
import aiordin.morg.launch.WindowProbeResult
import aiordin.morg.launch.WindowProbeStore
import aiordin.morg.launch.WindowSupport
import aiordin.morg.overlay.HandleService
import aiordin.morg.ui.BuildInfoScreen
import aiordin.morg.ui.MainScreen
import aiordin.morg.ui.OnboardingScreen
import aiordin.morg.ui.WelcomeScreen
import aiordin.morg.ui.WindowGuideScreen
import aiordin.morg.ui.rememberBuildInfoSections
import aiordin.morg.ui.theme.MorgTheme
import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 唯一的 Activity。首次启动先走引导，之后是设置页 —— 没有导航栈。
 *
 * 权限状态用 `mutableStateOf` 持有：用户去系统设置里授权再返回时，
 * `onResume` 重新读一次并写回状态，UI 自然刷新。不用 `recreate()`
 * （那会在 `onResume` 里再次触发自己）。
 */
public class MainActivity : ComponentActivity() {

    private val app: MorgApp by lazy { application as MorgApp }
    private val permissions: Permissions by lazy { Permissions(this) }
    private val probeStore: WindowProbeStore by lazy { WindowProbeStore(this) }

    private var permissionStatus by mutableStateOf(PermissionStatus())

    /** 小窗能力。静态探测 + 最近一次自检结果合并后的结论。 */
    private var windowSupport by mutableStateOf(WindowSupport.Unavailable)

    /** 最近一次小窗自检。只有它为「小窗」时，引导里的小窗步骤才算完成。 */
    private var probeRecord by mutableStateOf<WindowProbeRecord?>(null)

    /** 当前显示的首次引导；`null` 表示主界面。开关回调是 Activity 成员，所以放在这里。 */
    private var activeGuide by mutableStateOf<Guide?>(null)

    // POST_NOTIFICATIONS 是 API 33 才有的常量（字符串常量会被内联），
    // 调用点由 OnboardingScreen 的 notificationsAvailable 控制，低版本不会走到。
    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()
        refreshWindowSupport()
        activeGuide = savedInstanceState
            ?.getString(KEY_ACTIVE_GUIDE)
            ?.let { name -> runCatching { Guide.valueOf(name) }.getOrNull() }

        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            // 通知是唯一的运行时权限，其余两项只能跳系统设置。
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { refreshPermissions() }

            // 构建信息页是 MainScreen 之上的一层，不是新 Activity ——
            // 项目只有一个 Activity，详情页也不需要自己的任务栈。
            // 用 rememberSaveable 让转屏、切后台回来后仍停在这一页。
            var showBuildInfo by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = showBuildInfo) { showBuildInfo = false }
            BackHandler(enabled = activeGuide != null) { dismissActiveGuide() }

            MorgTheme {
                Surface(
                    // 铺满全屏（含状态栏 / 导航栏区域），否则顶部会露出一条窗口底色。
                    // 安全区交给内层，让背景色延伸到系统栏后面。
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                    ) {
                        when {
                            Onboarding.shouldShowWelcome(settings.welcomeCompleted) -> WelcomeScreen(
                                onStart = { app.settings.setWelcomeCompleted(true) },
                            )

                            activeGuide == Guide.Service -> OnboardingScreen(
                                status = permissionStatus,
                                notificationsAvailable = permissions.notificationsAvailable,
                                onOpenOverlaySettings = {
                                    openSettings(permissions.overlaySettingsIntent())
                                },
                                onOpenUsageSettings = {
                                    openSettings(permissions.usageAccessSettingsIntent())
                                },
                                onRequestNotifications = {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                onFinish = { finishServiceGuide(start = true) },
                                onDismiss = { finishServiceGuide(start = false) },
                            )

                            activeGuide == Guide.Window -> WindowGuideScreen(
                                windowSupport = windowSupport,
                                windowConfirmed = windowConfirmed(),
                                onOpenDeveloperOptions = ::openDeveloperOptions,
                                onTryWindow = ::startWindowProbe,
                                onEnable = { finishWindowGuide(enable = true) },
                                onDismiss = { finishWindowGuide(enable = false) },
                            )

                            showBuildInfo -> BuildInfoScreen(
                                sections = rememberBuildInfoSections(),
                                onBack = { showBuildInfo = false },
                            )

                            else -> MainScreen(
                                app = app,
                                windowSupport = windowSupport,
                                hasOverlayPermission = permissionStatus.overlay,
                                hasUsageAccess = permissionStatus.usageAccess,
                                onServiceChecked = ::requestService,
                                onWindowChecked = ::requestWindow,
                                onOpenBuildInfo = { showBuildInfo = true },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshWindowSupport()
        ensureServiceMatchesSettings()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 小窗自检会把应用退到后台；进程若被回收，回来时还想停在同一段引导。
        activeGuide?.let { outState.putString(KEY_ACTIVE_GUIDE, it.name) }
    }

    private fun windowConfirmed(): Boolean =
        probeRecord?.result == WindowProbeResult.SmallWindow

    /** 首次打开走引导；看过引导后仍缺浮窗权限的，直接跳系统设置。 */
    private fun requestService(enabled: Boolean) {
        if (!enabled) {
            app.settings.setServiceEnabled(false)
            HandleService.stop(this)
            return
        }
        if (Onboarding.shouldShowGuide(app.settings.state.value.serviceGuideSeen)) {
            activeGuide = Guide.Service
            return
        }
        if (!permissionStatus.overlay) {
            openSettings(permissions.overlaySettingsIntent())
            return
        }
        app.settings.setServiceEnabled(true)
        HandleService.start(this)
    }

    /** 必需权限齐了才开服务；跳过也记为「看过」。 */
    private fun finishServiceGuide(start: Boolean) {
        refreshPermissions()
        app.settings.markServiceGuideSeen()
        activeGuide = null
        if (start && Onboarding.isReady(permissionStatus)) {
            app.settings.setServiceEnabled(true)
            HandleService.start(this)
        }
    }

    /** 打开方式开关：关 = 全屏，开 = 小窗。首次打开先走小窗引导。 */
    private fun requestWindow(enabled: Boolean) {
        if (!enabled) {
            app.settings.setLaunchMode(LaunchMode.Fullscreen)
            return
        }
        if (Onboarding.shouldShowGuide(app.settings.state.value.windowGuideSeen)) {
            activeGuide = Guide.Window
            return
        }
        if (windowSupport == WindowSupport.Unavailable) {
            toast(R.string.probe_not_available)
            return
        }
        app.settings.setLaunchMode(LaunchMode.Window)
    }

    private fun finishWindowGuide(enable: Boolean) {
        app.settings.markWindowGuideSeen()
        activeGuide = null
        if (!enable) return
        if (windowSupport == WindowSupport.Unavailable) {
            toast(R.string.probe_not_available)
        } else {
            app.settings.setLaunchMode(LaunchMode.Window)
        }
    }

    /** 系统返回键关掉当前引导，与引导里的「稍后设置」等价。 */
    private fun dismissActiveGuide() {
        when (activeGuide) {
            Guide.Service -> finishServiceGuide(start = false)
            Guide.Window -> finishWindowGuide(enable = false)
            null -> Unit
        }
    }

    /** 让服务的实际运行状态追上设置：进程被回收、覆盖安装、重新授权后都会走到。 */
    private fun ensureServiceMatchesSettings() {
        if (!app.settings.state.value.serviceEnabled) return
        if (!permissionStatus.overlay) return
        HandleService.start(this)
    }

    private fun refreshPermissions() {
        permissionStatus = permissions.status()
    }

    /** 取走自检结果（一次性）并合并进静态探测；自检结果优先。 */
    private fun refreshWindowSupport() {
        probeStore.consume()?.let { record ->
            probeRecord = record
            toast(
                if (record.result == WindowProbeResult.SmallWindow) {
                    R.string.toast_window_confirmed
                } else {
                    R.string.toast_window_not_confirmed
                },
            )
        }
        windowSupport = WindowProbe.resolveSupport(
            hint = InstanceLauncher.windowSupportHint(
                zoomSupported = hasZoomWindow(),
                freeformEnabled = isFreeformEnabled(),
            ),
            record = probeRecord,
        )
    }

    /**
     * 打开一次小窗自检。
     *
     * 目标在前台时，系统会复用 top-most task 并丢掉 `windowingMode`，
     * 所以先把自己退到后台，再以小窗模式启动自检页。用户看到的是
     * 「点一下 → 回到桌面 → 小窗弹出 → 自动关闭 → 回到设置」，关掉就是完成了确认。
     */
    private fun startWindowProbe() {
        val request = WindowLaunchDecision.request(
            mode = LaunchMode.Window,
            zoomSupported = hasZoomWindow(),
            freeformEnabled = isFreeformEnabled(),
            screen = screenSize(),
            side = app.settings.state.value.placement.side,
        )
        if (request.windowingMode == WindowMode.FULLSCREEN) {
            // 两者都没有：先别跳，直接说明要去哪开。开出来也只会是全屏。
            toast(R.string.probe_not_available)
            return
        }

        val intent = Intent(this, WindowProbeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(WindowProbeActivity.EXTRA_WINDOWING_MODE, request.windowingMode)
            .putExtra(WindowProbeActivity.EXTRA_MAIN_TASK_ID, taskId)
        val options = ActivityOptions.makeBasic()
        request.bounds?.let {
            options.setLaunchBounds(Rect(it.left, it.top, it.right, it.bottom))
        }
        val bundle = options.toBundle().apply {
            putInt(WindowMode.KEY_LAUNCH_WINDOWING_MODE, request.windowingMode)
        }

        moveTaskToBack(true)
        Handler(Looper.getMainLooper()).postDelayed({
            val started = runCatching { startActivity(intent, bundle) }.isSuccess
            if (!started) {
                // 起不来（后台启动受限 / ROM 拦了）时别把用户丢在桌面上。
                toast(R.string.probe_failed)
                returnToMain(clearProbe = true)
            }
        }, PROBE_START_DELAY_MS)
    }

    /** 把主界面带回前台。自检页自己关闭时也走同一条意图。 */
    private fun returnToMain(clearProbe: Boolean) {
        if (clearProbe) {
            // 没启动成功：清掉可能残留的 pending 标记，避免下次误报「刚测过」。
            probeStore.consume()
        }
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    /**
     * 跳系统设置。个别 ROM 没有对应页面，退到应用详情页 ——
     * 点一下完全没反应比找不到入口更让人困惑。
     */
    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:$packageName".toUri(),
                    ),
                )
            }
        }
    }

    /** 开发者选项没有稳定的 deep link，个别 ROM 连这个页面都藏起来时就退到设置首页。 */
    private fun openDeveloperOptions() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun hasZoomWindow(): Boolean = runCatching {
        Class.forName("com.oplus.zoomwindow.OplusZoomWindowManager")
        true
    }.getOrDefault(false)

    private fun isFreeformEnabled(): Boolean {
        val resolver = contentResolver
        val freeform = runCatching {
            Settings.Global.getInt(resolver, InstanceLauncher.KEY_ENABLE_FREEFORM, 0)
        }.getOrDefault(0)
        val resizable = runCatching {
            Settings.Global.getInt(resolver, InstanceLauncher.KEY_FORCE_RESIZABLE, 0)
        }.getOrDefault(0)
        return freeform != 0 || resizable != 0
    }

    /** 真实屏幕尺寸，与 `InstanceLauncher` / 自检页用同一套取法。 */
    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val fallback = resources.displayMetrics
        val w = if (metrics.widthPixels > 0) metrics.widthPixels else fallback.widthPixels
        val h = if (metrics.heightPixels > 0) metrics.heightPixels else fallback.heightPixels
        return w to h
    }

    private companion object {
        /** 退到后台到启动自检页之间的间隔，留给系统完成 task 切换。 */
        private const val PROBE_START_DELAY_MS = 400L

        /** 转屏或进程被回收后恢复「正在看哪段引导」。 */
        private const val KEY_ACTIVE_GUIDE = "active_guide"
    }

    /** 三段首次引导。 */
    private enum class Guide {
        Service,
        Window,
    }
}
