package aiordin.morg.launch

import aiordin.morg.overlay.DockSide
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 以小窗形态启动时需要的一切。
 *
 * 单独抽出来是为了让 [WindowLaunchDecision] 能纯逻辑地决定「用什么参数」，
 * 真正碰 Android API 的部分（[InstanceLauncher]）只负责照做。
 */
public data class WindowRequest(
    /** 写进 bundle 的 windowingMode；[WindowMode.NONE] 表示不写。 */
    val windowingMode: Int,
    /** 小窗初始边界；系统自己排版或全屏时为 null。 */
    val bounds: WindowBounds?,
)

/**
 * 屏幕矩形。
 *
 * 刻意不用 `android.graphics.Rect`：它是 Android 类型，在 JVM 单测里是空实现
 * （`left` 恒为 0），会让「边界算得对不对」这件事**测不出来**。
 * 这个项目里所有位置计算都用它，只在真正写 bundle 时转成 `Rect`。
 */
public data class WindowBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 决定一次「再开一个实例」该用什么启动参数。
 *
 * 纯函数，不碰 Android 类型之外的东西（`Rect` 只在返回结构里），可以直接单测。
 */
public object WindowLaunchDecision {

    /**
     * @param mode 用户选的形态
     * @param zoomSupported 设备有 ColorOS 的 `OplusZoomWindowManager`
     * @param freeformEnabled 开发者选项里自由窗口开关打开
     * @param screen 屏幕像素尺寸
     * @param side 手柄贴的一侧 —— 小窗靠那一侧出现，避免挡住游戏画面中间
     */
    public fun request(
        mode: LaunchMode,
        zoomSupported: Boolean,
        freeformEnabled: Boolean,
        screen: Pair<Int, Int>,
        side: DockSide,
    ): WindowRequest {
        val windowingMode = WindowMode.resolve(mode, zoomSupported, freeformEnabled)
        // ColorOS 的 zoom 模式自己算尺寸位置，塞 bounds 反而会干扰它。
        val bounds = if (windowingMode == WindowMode.FREEFORM) {
            freeformBounds(screen, side)
        } else {
            null
        }
        return WindowRequest(windowingMode, bounds)
    }

    /**
     * freeform 退路用的初始边界：宽 62%、高 58%，靠手柄那一侧，纵向略偏上。
     *
     * 这条路径只有非 ColorOS 设备才会走到，位置跟随手柄侧别能让观感接近原生小窗。
     */
    public fun freeformBounds(
        screen: Pair<Int, Int>,
        side: DockSide,
    ): WindowBounds {
        val (width, height) = screen
        val w = (width * FREEFORM_WIDTH_RATIO).toInt()
        val h = (height * FREEFORM_HEIGHT_RATIO).toInt()
        val margin = ((width - w) * 0.08f).toInt()
        val left = when (side) {
            DockSide.Left -> margin
            DockSide.Right -> width - w - margin
        }
        val top = ((height - h) * FREEFORM_VERTICAL_BIAS).toInt()
        return WindowBounds(left, top, left + w, top + h)
    }

    private const val FREEFORM_WIDTH_RATIO = 0.62f
    private const val FREEFORM_HEIGHT_RATIO = 0.58f
    private const val FREEFORM_VERTICAL_BIAS = 0.38f
}

/**
 * 把「当前前台应用」再打开一次。
 *
 * ## 「多开」到底是什么
 *
 * 不是多进程 —— 实测同一应用的多份实例共享一个进程（`ps` 只有一个 PID）。
 * 真正发生的是**多个 Task**：每个 Task 有自己的返回栈与 Activity 实例，
 * 所以两份实例可以停在不同页面（例如一个停在笔记列表、另一个停在某篇笔记详情）。
 *
 * 触发条件里 `FLAG_ACTIVITY_MULTIPLE_TASK` 是必须的，但**只有目标当前不在前台时才生效**。
 * 实测：目标在前台时系统会提示 "intent has been delivered to currently running
 * top-most instance" 并复用现有 Task；目标在后台时则新建 Task。
 * 而本项目的调用方永远是自己（手柄所在的服务），所以自然满足这个前提。
 *
 * ## 为什么不用 `startZoomWindow()`
 *
 * 那个 API 受 `oplus.permission.OPLUS_COMPONENT_SAFE`（signature 级）保护，
 * 第三方（含 adb shell / Shizuku，UID 2000）调用直接 `SecurityException`。
 * 但把 `android.activity.windowingMode` 写进 `ActivityOptions` 不做签名校验，
 * 效果等价 —— `ActivityStarter` 只从 bundle 里读这个 key，不校验签名。
 */
public class InstanceLauncher(
    private val context: Context,
) {
    /**
     * 再开一个 [packageName] 的实例。
     *
     * @return 成功时返回 true。失败（应用被卸载、没有启动入口）返回 false。
     */
    public suspend fun launchAnother(
        packageName: String,
        mode: LaunchMode,
        side: DockSide,
    ): Boolean = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull()
            ?: run {
                // 最常见的原因是 Android 11+ 的包可见性：manifest 里少了 <queries>。
                // 静默返回 false 会让这个坑非常难查，所以必须留日志。
                Log.w(TAG, "no launch intent for $packageName (missing <queries> or app removed?)")
                return@withContext false
            }

        // MULTIPLE_TASK 才是「新开一个」的关键；NEW_TASK 是后台启动的必要条件。
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
        )

        val screen = screenSize()
        val request = WindowLaunchDecision.request(
            mode = mode,
            zoomSupported = zoomSupported(),
            freeformEnabled = freeformEnabled(),
            screen = screen,
            side = side,
        )

        val options = ActivityOptions.makeBasic()
        request.bounds?.toRect()?.let(options::setLaunchBounds)
        // `ActivityOptions.setLaunchWindowingMode` 是 hidden API，编译期 stub 里没有，
        // 但设备 runtime 的 framework 里存在。反射尝试一次：能调到就用（更明确），
        // 调不到也不影响 —— 下面的 bundle key 是与它等价的写法。
        if (request.windowingMode != WindowMode.NONE) {
            runCatching {
                ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .invoke(options, request.windowingMode)
            }.onSuccess {
                Log.i(TAG, "setLaunchWindowingMode(${request.windowingMode}) ok")
            }.onFailure { error ->
                Log.d(TAG, "setLaunchWindowingMode unavailable: ${error.javaClass.simpleName}")
            }
        }
        val bundle: Bundle = options.toBundle().apply {
            if (request.windowingMode != WindowMode.NONE) {
                putInt(WindowMode.KEY_LAUNCH_WINDOWING_MODE, request.windowingMode)
            }
        }

        Log.i(
            TAG,
            "launching $packageName flags=0x${intent.flags.toString(16)} " +
                "bundleKeys=${bundle.keySet()} " +
                "mode=${bundle.getInt(WindowMode.KEY_LAUNCH_WINDOWING_MODE, -1)}",
        )
        runCatching {
            context.startActivity(intent, bundle)
            Log.i(TAG, "launched $packageName mode=${request.windowingMode}")
            true
        }.getOrElse { error ->
            Log.w(TAG, "launch $packageName failed: ${error.javaClass.simpleName}: ${error.message}")
            false
        }
    }

    /**
     * 设备是否实现了 ColorOS 的小窗窗口模式。
     *
     * 只看类在不在 —— 这是能力探测，不是品牌判断。调用那个 API 本身需要签名权限，
     * 但 `windowingMode=100` 不需要。
     */
    private fun zoomSupported(): Boolean = runCatching {
        Class.forName(ZOOM_MANAGER_CLASS)
        true
    }.getOrDefault(false)

    /**
     * 开发者选项里的自由窗口开关。
     *
     * 注意**不用** `PackageManager.hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT)`：
     * ColorOS 不声明那个 feature 位，但开关打开后 freeform 确实可用，用 feature 判断会误判。
     */
    private fun freeformEnabled(): Boolean {
        val resolver = context.contentResolver
        val freeform = runCatching {
            android.provider.Settings.Global.getInt(resolver, KEY_ENABLE_FREEFORM, 0)
        }.getOrDefault(0)
        val resizable = runCatching {
            android.provider.Settings.Global.getInt(resolver, KEY_FORCE_RESIZABLE, 0)
        }.getOrDefault(0)
        return freeform != 0 || resizable != 0
    }

    /** 真实屏幕尺寸。application context 的 metrics 拿过错误值，用 Display 的 real metrics。 */
    private fun screenSize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val fallback = context.resources.displayMetrics
        val w = if (metrics.widthPixels > 0) metrics.widthPixels else fallback.widthPixels
        val h = if (metrics.heightPixels > 0) metrics.heightPixels else fallback.heightPixels
        return w to h
    }

    /** 只有真正交给系统时才变成 Android 类型。 */
    private fun WindowBounds.toRect(): Rect = Rect(left, top, right, bottom)

    public companion object {
        private const val TAG = "Morg"
        private const val ZOOM_MANAGER_CLASS = "com.oplus.zoomwindow.OplusZoomWindowManager"
        internal const val KEY_ENABLE_FREEFORM = "enable_freeform_support"
        internal const val KEY_FORCE_RESIZABLE = "force_resizable_activities"

        /** 小窗能力的人话说明，UI 与引导页共用。 */
        public fun windowSupportHint(zoomSupported: Boolean, freeformEnabled: Boolean): WindowSupport =
            when {
                zoomSupported -> WindowSupport.Ready
                freeformEnabled -> WindowSupport.FreeformOnly
                else -> WindowSupport.Unavailable
            }
    }
}

/** 小窗能力的三种状态，决定 UI 怎么提示。 */
public enum class WindowSupport {
    /** 系统原生小窗可用。 */
    Ready,

    /** 只有 AOSP freeform，能用但观感一般。 */
    FreeformOnly,

    /** 都没有，只能全屏。 */
    Unavailable,
}
