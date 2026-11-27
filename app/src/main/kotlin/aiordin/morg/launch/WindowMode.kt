package aiordin.morg.launch

/**
 * 启动目标应用时使用的窗口形态。
 *
 * 用户可在应用内切换；默认小窗，因为「同时看两个页面」正是这个项目的用处。
 */
public enum class LaunchMode {
    /** 普通全屏。任何 Android 都支持。 */
    Fullscreen,

    /** 小窗（自由窗口）。需要系统支持，见 [WindowMode]。 */
    Window,
}

/**
 * 把 [LaunchMode] 翻译成要写进 `ActivityOptions` 的 windowingMode 值。
 *
 * ## 为什么是这个值
 *
 * Android 的 `WindowingMode` 是 system_server 内部的枚举，`ActivityStarter` 从
 * `ActivityOptions` 的 bundle 里读 `android.activity.windowingMode` 决定 task 怎么布局。
 * 这个入口**不做签名校验** —— `am start --windowingMode` 用的就是它。
 *
 * 本项目在 ColorOS 上实测：
 *
 * | 值 | 结果 |
 * |---|---|
 * | 1 (`WINDOWING_MODE_FULLSCREEN`) | 全屏 |
 * | 5 (`WINDOWING_MODE_FREEFORM`) | AOSP 自由窗口，需开发者选项「可自由调整的窗口」 |
 * | 100 (ColorOS `WINDOWING_MODE_ZOOM`) | ColorOS 原生小窗（`ZoomAppControlService`） |
 *
 * 选 100 还是 5 取决于设备，由 [WindowMode] 探测后决定；这里只做映射。
 */
public object WindowMode {
    /** 不指定窗口模式，交给系统按默认（全屏）处理。 */
    public const val NONE: Int = 0

    public const val FULLSCREEN: Int = 1
    public const val FREEFORM: Int = 5

    /** ColorOS 的原生小窗窗口模式。 */
    public const val ZOOM: Int = 100

    /** bundle 里承载窗口模式的 key，对应 hidden 的 `ActivityOptions.KEY_LAUNCH_WINDOWING_MODE`。 */
    public const val KEY_LAUNCH_WINDOWING_MODE: String = "android.activity.windowingMode"

    /**
     * 计算本次启动该用的 windowingMode。
     *
     * @param mode 用户选的形态
     * @param zoomSupported 设备是否有 ColorOS 的 `OplusZoomWindowManager`
     * @param freeformEnabled 开发者选项里的自由窗口开关是否打开
     * @return 要写入 bundle 的值；[NONE] 表示不写（全屏）
     */
    public fun resolve(
        mode: LaunchMode,
        zoomSupported: Boolean,
        freeformEnabled: Boolean,
    ): Int = when {
        mode == LaunchMode.Fullscreen -> FULLSCREEN
        zoomSupported -> ZOOM
        freeformEnabled -> FREEFORM
        // 两者都没有：退回全屏。调用方会据此提示用户「小窗不可用」。
        else -> FULLSCREEN
    }

    /** 该形态在本机是否真的可用。UI 用它决定要不要把选项置灰。 */
    public fun supports(mode: LaunchMode, zoomSupported: Boolean, freeformEnabled: Boolean): Boolean =
        mode == LaunchMode.Fullscreen || zoomSupported || freeformEnabled
}
