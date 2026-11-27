package aiordin.morg.overlay

/**
 * 一条归一化的前台事件。
 *
 * 只表示「某个 Activity 刚进入前台」（`ACTIVITY_RESUMED`）—— 判定只需要这一种事件，
 * `PAUSED` / `STOPPED` 的信息由 [LiveInstance] 的可见性表达。
 */
public data class ForegroundEvent(
    val packageName: String,
    val timeStamp: Long,
    /**
     * Activity 实例 id（反射取自 `UsageEvents.Event.getInstanceId()`）。
     * 用于区分同一个包的多个实例（全屏 / 小窗）。
     */
    val instanceId: Int = 0,
)

/**
 * 一个仍存活（未收到 `STOPPED`）的 Activity 实例。
 *
 * @param visible 当前是否 `RESUMED`
 */
public data class LiveInstance(
    val packageName: String,
    val instanceId: Int,
    val visible: Boolean,
)

/**
 * 前台判定 —— 纯函数，不碰 Android 类型，所以能在 JVM 上直接单测。
 *
 * ## 三条规则（按顺序）
 *
 * 1. **普通应用在前台 → 显示。**
 * 2. **弱前台（桌面 / 系统 UI / 本应用）在前台 → 立即隐藏。**
 *    即使此时还有别的应用实例是 `RESUMED`，也先隐藏 —— 然后才是规则 3。
 * 3. **在弱前台上，确认还留着别的应用窗口（小窗）→ 重新显示。**
 *
 * ## 为什么规则 2 要「先隐藏」而不是「看有没有 RESUMED 实例就显示」
 *
 * 上滑退桌面时，被退走的全屏实例要等约 **1.1 秒**才拿到 `PAUSED`
 * （系统在等退场动画）。这段时间它仍是 `RESUMED`，如果据此判「有窗口」，
 * 手柄就会在桌面上多赖整整一秒 —— 实机体感就是「上划了手柄还挂在上面」。
 *
 * 而真正的小窗**永远不会** `PAUSED`（它一直可见）。所以两者的区别是
 * 「弱前台 RESUMED 之后该实例是否还会退场」，这只能**等**：
 * 等到 [WINDOW_SETTLE_MS] 之后它仍在 `RESUMED`，才认定是小窗。
 *
 * 代价：变小窗时手柄会先消失约 1.2 秒再回来。这是系统事件延迟，不是我们能压的。
 *
 * ## 为什么 `RESUMED` 就代表可见
 *
 * AOSP 多窗口语义：所有可见窗口都是 `RESUMED`，单窗口时前台只能有一个包
 * `RESUMED`。真机实测（2026-10-30，小黑盒）：
 *
 * ```
 * 小窗在桌面上    launcher@RESUMED + xiaoheihe@RESUMED（永不 PAUSED）
 * 全屏退到桌面    launcher@RESUMED → 1.1s → xiaoheihe@PAUSED
 * ```
 */
public object ForegroundDecision {

    /**
     * 确认「弱前台上还有小窗」需要等多久。
     *
     * 真机实测「`RESUMED:<弱前台>` → `PAUSED:<旧应用>`」的延迟：
     * 1132 / 1135 / 1141 / 1142 ms（四次采样，非常稳定）。
     * 取 1170 留约 30ms 余量。
     *
     * **这就是小窗重新显示的速度下限**：早一行就会把「正在退场的全屏」误判成小窗。
     */
    public const val WINDOW_SETTLE_MS: Long = 1_170L

    /**
     * 判定结果。
     *
     * @param foreground 视为「前台」的包名；null 表示从未识别到
     * @param lastStrong 应缓存下去的最后一次强前台（只含该显示手柄的包）
     * @param settling 结果可能翻转，调用方应当用快轮询确认
     */
    public data class Result(
        val foreground: String?,
        val lastStrong: String?,
        val settling: Boolean = false,
    )

    /**
     * @param now 当前时间（毫秒）
     * @param lastStrong 上一次被采纳的强前台包名
     * @param isWeak 该包是否属于「不该显示手柄」的一类（桌面/系统 UI/本应用）
     * @param latestResumed 最后一条 `RESUMED` 事件；null 表示还没见过
     * @param liveInstances 当前存活的 Activity 实例
     */
    public fun decide(
        now: Long,
        lastStrong: String?,
        isWeak: (String) -> Boolean,
        latestResumed: ForegroundEvent?,
        liveInstances: Collection<LiveInstance>,
    ): Result {
        val candidate = latestResumed ?: return Result(lastStrong, lastStrong)

        // 规则 1：普通应用在前台 → 显示。
        if (!isWeak(candidate.packageName)) {
            return Result(candidate.packageName, candidate.packageName, settling = false)
        }

        // 弱前台在前台。先看它自己是否已经退场（手势预览：拉起桌面又撤掉）。
        val candidateAlive = liveInstances.any {
            it.instanceId == candidate.instanceId && it.visible
        }
        if (!candidateAlive) {
            return Result(lastStrong, lastStrong, settling = false)
        }

        // 规则 3：确认弱前台上还留着别的应用窗口（小窗）。
        //
        // 必须等过 WINDOW_SETTLE_MS：刚退场的全屏此刻也还是 RESUMED，
        // 要等它的 PAUSED 到来才能排除。
        val visibleApp = liveInstances.firstOrNull { it.visible && !isWeak(it.packageName) }
        val elapsed = now - candidate.timeStamp
        if (visibleApp != null && elapsed >= WINDOW_SETTLE_MS) {
            return Result(visibleApp.packageName, visibleApp.packageName, settling = false)
        }

        // 规则 2：隐藏。等到 WINDOW_SETTLE_MS 前都可能翻成小窗，保持快轮询。
        return Result(candidate.packageName, lastStrong, settling = elapsed < WINDOW_SETTLE_MS)
    }
}
