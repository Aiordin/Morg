package aiordin.morg.launch

/**
 * 一次小窗自检的结论。
 *
 * 为什么需要自检：开发者选项里的开关只是**系统设置里的一个值**，
 * 它打开不代表小窗真的能开出来（`windowingMode` 可能被 ROM 丢弃）。
 * 所以这里以「实际打开的那一扇窗口有多大」为准。
 */
public enum class WindowProbeResult {
    /** 打开的窗口明显小于屏幕 —— 小窗真的生效了。 */
    SmallWindow,

    /** 打开的窗口铺满屏幕 —— 小窗没生效。 */
    Fullscreen,
}

/**
 * 自检记录。
 *
 * @param windowingMode 自检时请求的窗口模式，用来区分原生小窗（[WindowMode.ZOOM]）
 *   与 AOSP freeform（[WindowMode.FREEFORM]）—— 两者的观感不同，UI 要分开说明。
 */
public data class WindowProbeRecord(
    val result: WindowProbeResult,
    val windowingMode: Int,
)

public object WindowProbe {

    /**
     * 窗口任一维度小于屏幕的这个比例，就判定为小窗。
     *
     * 取 0.85 是因为全屏窗口也可能比屏幕略小（状态栏、导航栏），但不会小 15%。
     * 实测 ColorOS 小窗约 0.55×0.4，AOSP freeform 走 0.62×0.58，离阈值都很远。
     */
    public const val SMALL_WINDOW_MAX_RATIO: Float = 0.85f

    public fun isSmallWindow(window: WindowBounds, screen: WindowBounds): Boolean {
        if (screen.width <= 0 || screen.height <= 0) return false
        return window.width < screen.width * SMALL_WINDOW_MAX_RATIO ||
            window.height < screen.height * SMALL_WINDOW_MAX_RATIO
    }

    /**
     * 把自检结果叠加到静态探测上。
     *
     * **实测优先**：能真的开出小窗就按能开算（哪怕设置项读不到）；
     * 实测出来是全屏，就不再相信设置项 —— 那正是「开关打开但小窗不工作」的情形。
     *
     * @param hint 读设置项得到的结论
     * @param record 上一次自检；`null` 表示没测过，沿用 [hint]
     */
    public fun resolveSupport(hint: WindowSupport, record: WindowProbeRecord?): WindowSupport {
        if (record == null) return hint
        return when (record.result) {
            WindowProbeResult.Fullscreen -> WindowSupport.Unavailable
            WindowProbeResult.SmallWindow -> when (record.windowingMode) {
                WindowMode.ZOOM -> WindowSupport.Ready
                else -> WindowSupport.FreeformOnly
            }
        }
    }
}
