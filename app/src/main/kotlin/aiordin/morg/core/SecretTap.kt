package aiordin.morg.core

/** 一次点击的结果。 */
public sealed interface TapResult {
    /**
     * 还没达成，还差 [remaining] 次。
     *
     * [remaining] 为 1 时下一次点击就达成 —— UI 用它决定要不要给提示。
     */
    public data class Progress(val remaining: Int) : TapResult

    /** 达成。计数已归零，下次点击从头开始。 */
    public data object Unlocked : TapResult
}

/**
 * 「连点 N 次」计数。
 *
 * 主界面那个 ASCII 字标本身没有任何可点的样子，所以计数必须足够宽容：
 * 每次点击都算数，只在两次点击之间停顿超过 [resetAfterMs] 时才清零。
 * 门槛设成 7 次是 Android 开发者选项的惯例，用户已经形成了这个直觉。
 *
 * 时间是外部传入的（`SystemClock.uptimeMillis()`），不在这里读时钟 ——
 * 这样整类可以在 JVM 上直接单测，不需要 Robolectric。
 */
public class SecretTapCounter(
    private val requiredTaps: Int = DEFAULT_REQUIRED_TAPS,
    private val resetAfterMs: Long = DEFAULT_RESET_AFTER_MS,
) {
    init {
        require(requiredTaps > 0) { "requiredTaps must be positive: $requiredTaps" }
        require(resetAfterMs > 0) { "resetAfterMs must be positive: $resetAfterMs" }
    }

    private var taps: Int = 0
    private var lastTapAt: Long = 0L
    private var everTapped: Boolean = false

    /** 记录一次点击，返回当前进度。 */
    public fun tap(nowMs: Long): TapResult {
        if (everTapped && nowMs - lastTapAt > resetAfterMs) taps = 0
        everTapped = true
        lastTapAt = nowMs
        taps++

        if (taps >= requiredTaps) {
            reset()
            return TapResult.Unlocked
        }
        return TapResult.Progress(remaining = requiredTaps - taps)
    }

    /** 清零。离开页面或解锁后调用，避免残留计数在下一次进来时立刻触发。 */
    public fun reset() {
        taps = 0
        everTapped = false
        lastTapAt = 0L
    }

    public companion object {
        /** 解锁需要的点击数。 */
        public const val DEFAULT_REQUIRED_TAPS: Int = 7

        /**
         * 两次点击之间的最大间隔。
         *
         * 1.5 秒足够正常连点，又能挡住「一天里零散点几下就进去了」——
         * 后者会让彩蛋变成误触。
         */
        public const val DEFAULT_RESET_AFTER_MS: Long = 1_500L
    }
}
