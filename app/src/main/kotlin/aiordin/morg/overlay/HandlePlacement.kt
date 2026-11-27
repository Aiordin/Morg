package aiordin.morg.overlay

import kotlin.math.roundToInt

/**
 * 手柄贴靠的屏幕边缘。
 *
 * 只有左右两侧 —— 竖屏手机上顶边被状态栏占着，拖到那里会跟下拉控制中心打架。
 */
public enum class DockSide {
    Left,
    Right,
}

/**
 * 手柄在屏幕上的位置：只保留「贴哪边」与「纵向比例」两个量。
 *
 * 不存绝对像素：换设备、换分辨率、转屏后像素值会失效，比例不会。
 *
 * @param side 贴靠的边缘
 * @param verticalRatio 手柄中心在屏幕高度上的位置，0 = 顶、1 = 底
 */
public data class HandlePlacement(
    val side: DockSide,
    val verticalRatio: Float,
) {
    public companion object {
        /** 出厂位置：右侧、垂直居中偏上一点，拇指够得到又不压住常用区域。 */
        public val Default: HandlePlacement = HandlePlacement(DockSide.Right, 0.45f)
    }
}

/**
 * 手柄位置的纯计算：吸附、夹紧、映射到像素。
 *
 * 全部是纯函数，不碰 Android 类型，所以能在 JVM 上直接单测
 * （可测逻辑不依赖 Android SDK 是这个项目的硬约定）。
 */
public object HandlePlacementMath {

    /** 手柄中心离屏幕上下边缘至少保留这么多比例，避免被状态栏或手势区吃掉。 */
    public const val EDGE_MARGIN_RATIO: Float = 0.04f

    /**
     * 松手时把任意位置吸附到最近的边缘。
     *
     * @param centerX 手柄中心的 x 像素
     * @param centerY 手柄中心的 y 像素
     * @param width 屏幕宽像素
     * @param height 屏幕高像素
     */
    public fun snap(
        centerX: Float,
        centerY: Float,
        width: Int,
        height: Int,
    ): HandlePlacement {
        require(width > 0 && height > 0) { "screen size must be positive: ${width}x$height" }
        val side = if (centerX < width / 2f) DockSide.Left else DockSide.Right
        return HandlePlacement(side, clampVertical(centerY / height))
    }

    /**
     * 把纵向比例夹到允许范围。
     *
     * 手柄有一半会伸到中心点下方（用中心点定位再减去半高），
     * 所以留出 [EDGE_MARGIN_RATIO] 的余地就够。
     */
    public fun clampVertical(ratio: Float): Float =
        ratio.coerceIn(EDGE_MARGIN_RATIO, 1f - EDGE_MARGIN_RATIO)

    /**
     * 把位置换算成手柄中心的像素坐标。
     *
     * 贴左时 x 取手柄自身半宽（手柄紧贴左缘），贴右时取屏幕宽减去半宽。
     */
    public fun centerPx(
        placement: HandlePlacement,
        screenWidth: Int,
        screenHeight: Int,
        handleWidthPx: Int,
    ): Pair<Float, Float> {
        val x = when (placement.side) {
            DockSide.Left -> handleWidthPx / 2f
            DockSide.Right -> screenWidth - handleWidthPx / 2f
        }
        val y = clampVertical(placement.verticalRatio) * screenHeight
        return x to y
    }

    /**
     * 手柄上沿的 y 像素（不含窗口上下容错）。
     *
     * 比例是跨方向的：竖屏 0.45 转成横屏仍是 0.45，所以转屏后不需要换算，
     * 只要按新屏幕高度重新映射一次。但**不能**只按中心点减半高：
     * 横屏高度只有 1080px 时，[EDGE_MARGIN_RATIO]（4% ≈ 43px）比手柄半高
     * （96px）还小，靠边的比例会让手柄有一截跑到屏幕外。
     * 所以这里以「整根手柄都在屏内」为准做夹紧。
     */
    public fun topPx(
        placement: HandlePlacement,
        screenHeight: Int,
        handleHeightPx: Int,
    ): Int {
        require(screenHeight > 0) { "screen height must be positive: $screenHeight" }
        require(handleHeightPx > 0) { "handle height must be positive: $handleHeightPx" }
        val centerY = clampVertical(placement.verticalRatio) * screenHeight
        return (centerY - handleHeightPx / 2f).roundToInt()
            .coerceIn(0, maxOf(0, screenHeight - handleHeightPx))
    }
}

/**
 * 抽屉式手柄的几何与手势换算 —— 纯函数，可在 JVM 上直接单测。
 *
 * ## 为什么手势要用「屏幕坐标」而不是窗口内坐标
 *
 * 悬浮窗贴右边缘时，窗口变宽意味着**左边缘向左移动**。窗口内坐标是相对左边缘的，
 * 所以同一根手指不动，窗口每变宽 1px，它的窗口内 x 就会 +1px。
 * 如果把 `positionChange()` 的窗口内增量累加起来当手指位移，窗口增长会把这份增量
 * 反噬掉 —— 第一帧长了一点之后增量变成 0，抽屉就不跟手了（实机表现是「没有变化」）。
 *
 * 正确做法：手势用 `MotionEvent.rawX`（与窗口几何无关），再和按下时的 raw 坐标
 * 比较（[inwardDx]）。这样窗口怎么变宽都不会反馈进位移。
 *
 * ## 抽屉几何
 *
 * ```
 * 常态     [手柄 12dp]
 * 拖动中   [<---- 抽屉拉伸 pull ---->|手柄]
 * ```
 *
 * [pullForDrag] 让抽屉前段 1:1 跟手，超过上限后按阻尼系数做弹性外扩，
 * 视觉上「拉到头还能再拽一点」，松手回弹。窗口在按下时一次性撑到
 * [envelopeWidth]（手柄 + 最大拉伸 + 回弹余量 + 手指容错），
 * 整个手势期间不再改窗口几何 —— 避免 relayout 抖动和手势被 CANCEL。
 */
public object HandleWindowMath {

    /** 抽屉拉伸距离（像素）：前段 1:1 跟手，超过 [maxPullPx] 后弹性外扩并封顶。 */
    public fun pullForDrag(
        inwardDx: Float,
        maxPullPx: Int,
        overshootMaxPx: Int,
        overshootFactor: Float = 0.3f,
    ): Float {
        require(maxPullPx > 0) { "max pull must be positive: $maxPullPx" }
        require(overshootMaxPx >= 0) { "overshoot must not be negative: $overshootMaxPx" }
        if (inwardDx <= 0f) return 0f
        if (inwardDx <= maxPullPx) return inwardDx
        val extra = ((inwardDx - maxPullPx) * overshootFactor)
            .coerceAtMost(overshootMaxPx.toFloat())
        return maxPullPx + extra
    }

    /**
     * 手势期间窗口需要的一次性宽度。
     *
     * 窗口只在按下时撑开一次，之后手势期间保持不变，所以必须留够：
     * 抽屉最大拉伸 + 弹性余量 + 手指继续内滑的容错（[slackPx]）。
     */
    public fun envelopeWidth(
        baseWidthPx: Int,
        maxPullPx: Int,
        overshootMaxPx: Int,
        slackPx: Int,
    ): Int {
        require(baseWidthPx > 0) { "base width must be positive: $baseWidthPx" }
        require(maxPullPx >= 0 && overshootMaxPx >= 0 && slackPx >= 0) {
            "drawer dimensions must not be negative"
        }
        return baseWidthPx + maxPullPx + overshootMaxPx + slackPx
    }

    /**
     * 内向位移：向右吸附时手指向左为正，向左吸附时向右为正。
     *
     * 传入的必须是 **raw 屏幕坐标**，不能是窗口内坐标 —— 右侧窗口变宽时左边缘
     * 会左移，同一根手指的窗口内 x 会跟着变，增量会被窗口增长反馈掉。
     */
    public fun inwardDx(
        downScreenX: Float,
        currentScreenX: Float,
        side: DockSide,
    ): Float = when (side) {
        DockSide.Left -> currentScreenX - downScreenX
        DockSide.Right -> downScreenX - currentScreenX
    }

    /** 松手时是否达到触发阈值。 */
    public fun isTriggered(pullPx: Float, thresholdPx: Int): Boolean =
        pullPx >= thresholdPx

    /**
     * 窗口左上角 x：贴边且右边缘（右侧手柄）或左边缘（左侧手柄）固定。
     */
    public fun leftForWidth(
        screenWidth: Int,
        windowWidthPx: Int,
        side: DockSide,
    ): Int = when (side) {
        DockSide.Left -> 0
        DockSide.Right -> (screenWidth - windowWidthPx).coerceAtLeast(0)
    }
}
