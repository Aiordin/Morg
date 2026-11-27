package aiordin.morg.overlay

import aiordin.morg.core.AppSettings
import aiordin.morg.launch.InstanceLauncher
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 悬浮手柄窗口。
 *
 * ## 几何：常态收在屏幕边缘
 *
 * 窗口常态 = 视觉手柄尺寸（12×64dp）。窗口外的触摸全部穿透
 * （`FLAG_NOT_TOUCH_MODAL`），不影响游戏操作。
 *
 * ## 抽屉式跟手
 *
 * 按下时窗口**一次性**从 12dp 撑到 [envelopeWidthPx]（手柄 + 最大拉伸 +
 * 弹性余量 + 手指容错），之后整个手势期间不再改窗口几何：
 *
 * ```
 * 常态     [手柄]
 * 拖动中   [<------ 抽屉拉出 ------>|手柄]
 * ```
 *
 * 抽屉拉出距离由 [HandleWindowMath.pullForDrag] 计算：前段 1:1 跟手，
 * 超过上限后按 0.3 的阻尼继续外扩（最多 [PULL_OVERSHOOT]），松手用弹簧回弹。
 * 达到阈值时给一次触觉反馈，松手越过阈值才真正触发「再开一个实例」。
 *
 * ## 两个实测踩出来的坑
 *
 * 1. **窗口太窄会把手势丢给系统**：12dp 的窗口，手指滑 20px 就出界，
 *    剩下的位移被返回手势接管，前台应用直接被关掉。所以按下就撑到信封宽度。
 * 2. **窗口内坐标会被窗口变宽反噬**：右侧窗口变宽会让左边缘左移，同一根手指的
 *    `positionChange()` 增量被窗口增长抵消，实机表现就是「抽屉完全没有变化」。
 *    所以手势从头到尾都用 `MotionEvent.rawX/rawY`（与窗口几何无关），
 *    见 [HandleWindowMath.inwardDx]。
 *
 * ## 位置设定模式
 *
 * 这时窗口固定加宽到 [EDIT_WIDTH] 便于抓取，手柄跟手自由移动（同样用 raw 坐标），
 * 松手吸附到最近边缘并保存。位置设定开启时手柄**全局可见**，无视前台判断 ——
 * 否则在桌面/最近任务里根本没法调整。
 */
public class HandleOverlay(
    private val context: Context,
    private val settings: AppSettings,
    private val launcher: InstanceLauncher,
    private val tracker: ForegroundTracker,
    private val lifecycleOwner: LifecycleOwner,
    private val savedStateOwner: SavedStateRegistryOwner,
) {
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density = context.resources.displayMetrics.density
    private val touchSlopPx: Int = ViewConfiguration.get(context).scaledTouchSlop

    private val displayManager: DisplayManager? =
        context.getSystemService(DisplayManager::class.java)

    /** 默认显示屏。屏幕尺寸与挖孔 inset 都从它读。 */
    private val display: Display? = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)

    /** 主线程 Handler：DisplayListener 的回调必须与窗口操作在同一个线程。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 当前屏幕像素尺寸（含系统栏，与 `getRealMetrics` 一致），跟随旋转方向。
     *
     * 缓存而不是每次现读：排版用的尺寸必须与窗口参数同源，而且每轮可见性检查
     * 都去碰 Display 不划算。尺寸变化由 [displayListener]（快）与轮询（兜底）刷新。
     */
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var displayListenerRegistered = false

    init {
        val (w, h) = readScreenSize()
        screenWidth = w
        screenHeight = h
    }

    /**
     * 屏幕尺寸变化（旋转、分辨率覆盖、折叠展开）时重新排版。
     *
     * 不靠 `Service.onConfigurationChanged`：服务进程没有 Activity 时配置变更的
     * 分发时机不确定；`DisplayListener` 是系统级回调，旋转完成即到。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) syncScreenSize()
        }
    }

    /**
     * 视觉窗口：只画手柄与抽屉，**几何永远不变**，且不可触摸。
     *
     * 为什么不能让它变尺寸：窗口 resize 的那一帧系统会用旧 buffer / 旧左上角
     * 先画一次，实测手柄会横跳几十甚至几百像素。与弹簧参数无关，
     * 也与 `x` 是否绝对无关 —— 只要 `updateViewLayout` 改宽高就会跳。
     * 所以视觉窗口一律保持在 [envelopeWidthPx] 宽、[windowHeightPx] 高。
     */
    private var visualView: ComposeView? = null

    /**
     * 触摸窗口：全透明，只负责接收手势，可自由改尺寸。
     *
     * 把触摸从视觉窗口里拆出来的原因：手势需要一个大窗口（手指滑出 12dp 的手柄
     * 后不能被系统返回手势抢走），而大窗口又不能一直存在（会吞掉屏幕边缘的触摸）。
     * 既然这个窗口不画任何东西，它撑开/收回就不会被用户看见。
     */
    private var touchView: ComposeView? = null

    private var pollJob: Job? = null

    /** 上次可见性发生变化的时间（[SystemClock.uptimeMillis]），用于快轮询。 */
    private var lastVisibilityChangeAt: Long = 0L

    /**
     * 抽屉拉出距离（像素）。
     *
     * 提升为类字段，因为视觉窗口与触摸窗口是两个独立的 composition：
     * 手势在触摸窗口里跑，视觉窗口订阅这个状态重绘。单一真源，不需要跨窗口传值。
     */
    private val pullPxState = mutableFloatStateOf(0f)

    /** 拖动会话。放在类字段而不是 Composable 内：轮询与 [start] 都要能看到
     * 「现在有手指按着」，否则会在拖动中重排窗口、把正在拉的手势裁掉。
     */
    private val drag = DragState()

    /**
     * 回弹动画的 Job。放到类字段（而不是 Compose 内的 remember）：
     * 轮询需要它来判断「是否正在回弹」，否则会把还未回弹完的抽屉直接裁掉。
     */
    private var closeJob: Job? = null

    private val baseWidthPx: Int = (HANDLE_WIDTH.value * density).roundToInt()
    private val editWidthPx: Int = (EDIT_WIDTH.value * density).roundToInt()
    private val handleHeightPx: Int = (HANDLE_HEIGHT.value * density).roundToInt()
    private val swipeThresholdPx: Int = (SWIPE_THRESHOLD.value * density).roundToInt()
    private val maxPullPx: Int = (MAX_PULL.value * density).roundToInt()
    private val pullOvershootPx: Int = (PULL_OVERSHOOT.value * density).roundToInt()

    /**
     * 窗口纵向容错（每侧）。
     *
     * 手势只能从 `ACTION_DOWN` 之后开始，而 DOWN 必须**已经**落在窗口内，
     * 所以上下必须留出容错，否则按在手柄边界外几像素就捕不到（实测 y=1030 漏），
     * 那几像素直接归系统 —— 实机表现是「从手柄上下边缘按下时变成返回手势」。
     *
     * 常态和手势态用同一个值：高度一旦变化，收窄窗口那一帧手柄会位 36px。
     * 现在视觉窗口的几何是全恒定的（见 [visualView]），更没必要拿高度做文章。
     */
    private val gestureSlackPx: Int = (EDGE_SLACK_GESTURE.value * density).roundToInt()

    /**
     * 窗口高度。视觉窗口与触摸窗口都用它，且**始终不变**。
     *
     * 上下各留 [gestureSlackPx]：手指按在手柄上下边缘外几像素也能落在窗口内，
     * 否则那几像素归系统，变成返回手势。
     */
    private val windowHeightPx: Int = handleHeightPx + gestureSlackPx * 2

    private val envelopeWidthPx: Int = HandleWindowMath.envelopeWidth(
        baseWidthPx = baseWidthPx,
        maxPullPx = maxPullPx,
        overshootMaxPx = pullOvershootPx,
        slackPx = (DRAG_SLACK.value * density).roundToInt(),
    )

    /**
     * 触摸窗口当前宽度（像素）。位置编辑时是 [editWidthPx]。
     *
     * 只跟触摸窗口有关 —— 视觉窗口宽度永远是 [envelopeWidthPx]，不随它变。
     */
    private var touchWidthPx: Int = baseWidthPx

    /** 窗口左上角的绝对坐标，只用于位置设定模式。 */
    private var dragX = 0
    private var dragY = 0

    public fun start() {
        if (pollJob?.isActive != true) startPolling()
        registerDisplayListener()
        // 服务收到任何设置变化都会调 start()：编辑模式切换、贴边侧变化时
        // 需要立即把已有窗口的几何改过来，不能等它被移除再重建。
        // 屏幕尺寸同样在这里对齐：从另一个方向切回来时，窗口还停在旧坐标上。
        syncScreenSize()
        if (visualView != null) applyPlacementLayout(settings.state.value.placement)
        refreshVisibility()
    }

    public fun stop() {
        pollJob?.cancel()
        pollJob = null
        unregisterDisplayListener()
        removeViews()
        scope.cancel()
    }

    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        val manager = displayManager ?: return
        runCatching { manager.registerDisplayListener(displayListener, mainHandler) }
            .onSuccess { displayListenerRegistered = true }
            .onFailure { Log.w(TAG, "registerDisplayListener failed: ${it.message}") }
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        displayListenerRegistered = false
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(refreshVisibility())
            }
        }
    }

    /**
     * 按当前设置与前台刷新可见性。位置设定模式无视前台判断，必须全局可见。
     *
     * @return 下一轮轮询应当等待多少毫秒。
     */
    private fun refreshVisibility(): Long {
        // 兜底：个别系统上 DisplayListener 可能不回调，或者回调早于尺寸真正生效。
        // 尺寸变了却不重排，窗口会整块停在旧方向上 —— 横屏只有 1080px 高，
        // 竖屏 y=942 的手柄直接掉到屏幕外（实测）。
        syncScreenSize()
        // 几何兜底：手势结束、且回弹动画也不在了，窗口就该是常态尺寸。
        //
        // 动画的 `finally` 里已经收过一次，但那条路径不可靠（协程被取消、
        // composition 重建都会跳过它）—— 实测出现过触摸窗口永久卡在 348×288 手势态、
        // 屏幕右下多出一大块吞触摸区域。`applyTouchWidth` 内部有相等性早退，
        // 所以常态下不会真的 update。
        if (!drag.active && closeJob?.isActive != true) {
            applyTouchWidth(baseWidthPx)
        }
        if (settings.state.value.positionEditing) {
            showIfNeeded(visible = true)
            return FAST_POLL_INTERVAL_MS
        }
        val sample = runCatching { tracker.sample() }.getOrNull()
        if (sample == null) {
            showIfNeeded(visible = false)
            return IDLE_POLL_INTERVAL_MS
        }
        val visible = tracker.shouldShowHandle(sample.packageName)
        showIfNeeded(visible)
        // 三档，按「这一刻的延迟有多刺眼」排：
        // - settling：正在确认「是不是小窗」，差一点就要显示，用最快的轮询；
        // - 可见：要尽快发现用户离开了应用；
        // - 隐藏：只需等进应用的时机，慢一点守耗电。
        return when {
            sample.settling -> SETTLE_POLL_INTERVAL_MS
            visible || wasRecentlyActive() -> FAST_POLL_INTERVAL_MS
            else -> IDLE_POLL_INTERVAL_MS
        }
    }

    /** 刚刚切换过可见性？切换往往连着来（桌面→应用），这一段值得用快轮询。 */
    private fun wasRecentlyActive(): Boolean =
        SystemClock.uptimeMillis() - lastVisibilityChangeAt < ACTIVE_WINDOW_MS

    /** 幂等的显示/隐藏。窗口不存在时系统完全不会分配触摸，比透明窗口干净。 */
    private fun showIfNeeded(visible: Boolean) {
        val existing = visualView
        when {
            visible && existing == null -> {
                addViews()
                lastVisibilityChangeAt = SystemClock.uptimeMillis()
            }
            !visible && existing != null -> {
                removeViews()
                lastVisibilityChangeAt = SystemClock.uptimeMillis()
            }
        }
    }

    private fun addViews() {
        val wm = windowManager ?: return
        val placement = settings.state.value.placement
        // 新建窗口时把触摸宽度对齐到当前模式：手柄之前是隐藏的话，
        // 上次留下的宽度是常态值（实现在位置编辑开启后窗口只有 12dp 宽）。
        touchWidthPx = if (settings.state.value.positionEditing) editWidthPx else baseWidthPx
        val visual = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent { VisualContent() }
        }
        val touch = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent { TouchContent() }
        }
        runCatching {
            wm.addView(visual, visualLayoutParams(placement))
            wm.addView(touch, touchLayoutParams(placement))
        }.onSuccess {
            visualView = visual
            touchView = touch
        }.onFailure {
            Log.w(TAG, "addView failed: ${it.javaClass.simpleName}: ${it.message}")
            runCatching { wm.removeView(visual) }
            runCatching { wm.removeView(touch) }
            visualView = null
            touchView = null
        }
    }

    private fun removeViews() {
        val wm = windowManager ?: return
        visualView?.let { runCatching { wm.removeView(it) } }
        touchView?.let { runCatching { wm.removeView(it) } }
        visualView = null
        touchView = null
    }

    // —— 窗口几何 ——

    /** 窗口公共标志：不抢焦点、窗口外触摸穿透、按真实屏幕坐标布局。 */
    private val baseFlags: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    /**
     * 视觉窗口参数。
     *
     * 非编辑模式：**固定** [envelopeWidthPx] × [windowHeightPx]，贴边。
     * 内容靠右对齐，所以手柄贴屏幕边缘、抽屉在手柄左侧向屏幕内展开。
     * 几何与 `pull` 无关，永远不变。
     *
     * 加 `FLAG_NOT_TOUCHABLE`：它只负责画，触摸全部交给 [touchView]。
     * 否则这么大的透明区域会把游戏边缘的触摸吃掉。
     */
    private fun visualLayoutParams(placement: HandlePlacement): WindowManager.LayoutParams {
        val editing = settings.state.value.positionEditing
        val (screenW, _) = screenSize()
        val width = if (editing) editWidthPx else envelopeWidthPx
        val height = if (editing) handleHeightPx else windowHeightPx
        beginDragFrom(placement, placement.side, width)
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = ABSOLUTE_GRAVITY
            x = if (editing) dragX else {
                HandleWindowMath.leftForWidth(screenW, envelopeWidthPx, placement.side)
            }
            // 编辑模式窗口就是手柄高；否则上下各留 gestureSlackPx。
            y = if (editing) dragY else dragY - gestureSlackPx
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * 触摸窗口参数：全透明，只负责接收手势。
     *
     * 常态只有手柄宽（不吞游戏触摸），按下后由 [applyTouchWidth] 撑到信封宽。
     * 它不画任何东西，所以尺寸变化用户完全看不到。
     */
    private fun touchLayoutParams(placement: HandlePlacement): WindowManager.LayoutParams {
        val editing = settings.state.value.positionEditing
        val width = touchWidthPx
        val height = if (editing) handleHeightPx else windowHeightPx
        beginDragFrom(placement, placement.side, width)
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = ABSOLUTE_GRAVITY
            x = dragX
            y = if (editing) dragY else dragY - gestureSlackPx
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** 由位置比例算出窗口纵向位置，并把拖动起点同步过去。 */
    private fun beginDragFrom(placement: HandlePlacement, side: DockSide, widthPx: Int) {
        val (screenW, screenH) = screenSize()
        // 比例跨方向有效：竖屏的 0.45 转成横屏仍是 0.45，只需按新高度重映射。
        dragY = HandlePlacementMath.topPx(placement, screenH, handleHeightPx)
        dragX = HandleWindowMath.leftForWidth(screenW, widthPx, side)
    }

    /**
     * 设置手势态的窗口几何。
     *
     * **只改宽度，不改高度，也不改 y。**
     *
     * 窗口改变尺寸/位置后，旧的内容布局可能晚一帧才重算。那一帧里的屏幕位置
     * 由「新窗口原点 + 旧居中偏移」决定，于是：
     *
     * - **宽度变化是安全的**：内容右锚在窗口右缘，而右缘（[HandleWindowMath.leftForWidth]
     *   与 width 同一次提交）恒为屏幕右缘，误差被对齐吸收。
     * - **高度变化会跳**：内容垂直居中，高度 288→216 时居中偏移从 48 变 12，
     *   而窗口 y 同时 770→806。两侧错一帧就是手柄瞬移 36px —— 实测就是这样
     * 在「抽屉收回、窗口复位」那一瞬跳一下。所以高度必须恒定。
     *
     * 因此 [windowHeightPx] 常态/手势态一致，`y` 恒为 `dragY - gestureSlackPx`，
     * 手势的上下容错在常态就一直在，反而让手柄上下边缘更容易按到。
     *
     * @param widthPx 目标宽度（会被 clamp 到 [baseWidthPx]..[envelopeWidthPx]）
     */
    private fun applyTouchWidth(widthPx: Int) {
        if (settings.state.value.positionEditing) return
        val wm = windowManager ?: return
        val current = touchView ?: return
        val params = (current.layoutParams as? WindowManager.LayoutParams) ?: return
        val (screenW, _) = screenSize()
        val width = widthPx.coerceIn(baseWidthPx, envelopeWidthPx)
        val targetX = HandleWindowMath.leftForWidth(
            screenWidth = screenW,
            windowWidthPx = width,
            side = settings.state.value.placement.side,
        )
        // 只夹下限，不夹上限：视觉窗口本身就是 `dragY - slack`，没有上限。
        // 横屏底部时 `screenH - windowHeightPx`（792）会把触摸窗口抬到 792，
        // 而视觉窗口在 840 —— 两个窗口错开 48px，多出一块透明吐触摸的区。
        // 超出屏幕的那半透明容错区本来就不会分配触摸，不需要夹。
        val targetY = (dragY - gestureSlackPx).coerceAtLeast(0)
        if (width == params.width && targetX == params.x && targetY == params.y) return
        touchWidthPx = width
        params.width = width
        params.x = targetX
        params.y = targetY
        runCatching { wm.updateViewLayout(current, params) }
    }

    /** 位置设定模式下自由移动：这时不贴边，用绝对坐标。 */
    private fun moveTo(x: Int, y: Int) {
        val wm = windowManager ?: return
        val (screenW, screenH) = screenSize()
        val width = editWidthPx
        dragX = x.coerceIn(0, maxOf(0, screenW - width))
        dragY = y.coerceIn(0, maxOf(0, screenH - handleHeightPx))
        // 两个窗口都要跟：视觉窗口画手柄，触摸窗口接收拖动。
        for (target in listOfNotNull(visualView, touchView)) {
            val params = (target.layoutParams as? WindowManager.LayoutParams) ?: continue
            params.gravity = ABSOLUTE_GRAVITY
            params.x = dragX
            params.y = dragY
            runCatching { wm.updateViewLayout(target, params) }
        }
    }

    /** 松手：吸附到最近边缘并保存，随后窗口回到贴边布局。 */
    private fun settleAfterDrag() {
        val (screenW, screenH) = screenSize()
        val placement = HandlePlacementMath.snap(
            centerX = dragX + editWidthPx / 2f,
            centerY = dragY + handleHeightPx / 2f,
            width = screenW,
            height = screenH,
        )
        settings.setPlacement(placement)
        applyPlacementLayout(placement)
    }

    /** 按位置重建两个窗口的参数（贴边 + 正确的绝对坐标）。 */
    private fun applyPlacementLayout(placement: HandlePlacement) {
        val wm = windowManager ?: return
        touchWidthPx = if (settings.state.value.positionEditing) editWidthPx else baseWidthPx
        visualView?.let { runCatching { wm.updateViewLayout(it, visualLayoutParams(placement)) } }
        touchView?.let { runCatching { wm.updateViewLayout(it, touchLayoutParams(placement)) } }
    }

    private fun restoreSavedPosition() = applyPlacementLayout(settings.state.value.placement)

    // —— 触发 ——

    private fun onSwipe() {
        val foreground = runCatching { tracker.currentForeground() }.getOrNull()
        if (!tracker.shouldShowHandle(foreground)) return
        val state = settings.state.value
        Log.i(TAG, "swipe -> reopen $foreground as ${state.launchMode}")
        scope.launch {
            val ok = launcher.launchAnother(
                packageName = requireNotNull(foreground),
                mode = state.launchMode,
                side = state.placement.side,
            )
            if (!ok) Log.w(TAG, "reopen failed for $foreground")
        }
    }

    /**
     * 现读屏幕尺寸（含系统栏，跟随当前旋转方向）。
     *
     * 只在初始化、尺寸变化判定、以及轮询兜底时调用 —— 排版本身用缓存，
     * 保证窗口参数与它在同一次计算里用的是同一组值。
     */
    private fun readScreenSize(): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val fallback = context.resources.displayMetrics
        return (if (metrics.widthPixels > 0) metrics.widthPixels else fallback.widthPixels) to
            (if (metrics.heightPixels > 0) metrics.heightPixels else fallback.heightPixels)
    }

    /** 排版用的屏幕尺寸：[syncScreenSize] 最近一次的结果。 */
    private fun screenSize(): Pair<Int, Int> =
        if (screenWidth > 0 && screenHeight > 0) screenWidth to screenHeight else readScreenSize()

    /**
     * 把缓存的屏幕尺寸对齐到当前显示，变了就重排两个窗口。
     *
     * 转屏后窗口的 `x/y` 仍是旧方向上的绝对像素（系统不会替我们换坐标），
     * 而横屏只剩 1080px 高 —— 不重排的结果就是手柄整块掉到屏幕下方。
     *
     * @return 尺寸是否发生变化
     */
    private fun syncScreenSize(): Boolean {
        val (w, h) = readScreenSize()
        if (w <= 0 || h <= 0) return false
        if (w == screenWidth && h == screenHeight) return false
        Log.i(TAG, "screen ${screenWidth}x$screenHeight -> ${w}x$h")
        screenWidth = w
        screenHeight = h
        abortDrag()
        if (visualView != null) applyPlacementLayout(settings.state.value.placement)
        return true
    }

    /**
     * 打断进行中的手势。
     *
     * 转屏会换掉 raw 坐标空间：按下时记下的 `startRawX/Y` 与新屏幕不是同一套坐标，
     * 继续算只会得到巨大的位移。抽屉归零、触摸窗口收回常态，等用户重新按。
     */
    private fun abortDrag() {
        closeJob?.cancel()
        closeJob = null
        drag.active = false
        drag.moved = false
        drag.crossed = false
        drag.pullPx = 0f
        pullPxState.floatValue = 0f
        touchWidthPx = baseWidthPx
    }

    // —— UI 与手势 ——

    /**
     * 触摸窗口的内容：全透明，只处理手势。
     *
     * 这里不画任何东西 —— 所以它变尺寸用户看不到。视觉全部在 [VisualContent]。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun TouchContent() {
        val settingsState by settings.state.collectAsStateWithLifecycle()
        val editing by rememberUpdatedState(settingsState.positionEditing)
        val side by rememberUpdatedState(settingsState.placement.side)
        val uiScope = rememberCoroutineScope()
        val rootView = LocalView.current

        fun finishGesture(trigger: Boolean) {
            drag.active = false
            if (drag.editing) {
                if (drag.moved) settleAfterDrag() else restoreSavedPosition()
                return
            }
            // 手指已抬起：触摸窗口不需要再那么宽，先收到「手柄 + 当前抽屉」。
            // 这个窗口不画东西，所以改尺寸完全不可见；视觉窗口全程不动。
            applyTouchWidth(widthPx = baseWidthPx + drag.pullPx.roundToInt())
            // 这个 Job 必须能被下一次按下取消：动画持续约 0.5s，若新手势已经
            // 开始、动画还在跑，它的收尾会把窗口收回 12dp，手指当场出界，
            // 事件流被系统取消。连续拉两次就会踩到。
            closeJob = uiScope.launch {
                val anim = Animatable(pullPxState.floatValue)
                try {
                    anim.animateTo(
                        targetValue = 0f,
                        // 回弹必须单调、不能越过 0：
                        // 1. 越过 0 变成负值时 DrawerPanel 直接不渲染、Row 缩回手柄宽，
                        //    手柄瞬间贴边；弹簧再弹回正值时抽屉和手柄又冒出来一点，
                        //    实机表现就是「复位后还跳一下」。
                        // 2. MediumLow 从满拉收回要 0.4-0.5s，体感「不能马上归位」。
                        // 所以用无过冲（临界阻尼）的弹簧，并提高刚度让手柄快速贴边。
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    ) {
                        // 临界阻尼理论上不会为负，浮点误差兜底，避免最后一帧闪一下。
                        pullPxState.floatValue = value.coerceAtLeast(0f)
                    }
                } finally {
                    // 被新手势取消时窗口已由 DOWN 重新撑开，绝不能在这里收窄。
                    if (!drag.active) {
                        pullPxState.floatValue = 0f
                        // 等一帧，让视觉窗口按 pull=0 重绘完再收窄触摸窗口。
                        withFrameNanos { }
                        if (!drag.active) {
                            applyTouchWidth(baseWidthPx)
                        }
                    }
                }
            }
            if (trigger) onSwipe()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 贴边缘的手柄不声明豁免的话，向内滑会被系统返回手势抢走。
                .systemGestureExclusion()
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            // 重新抓住时接管正在进行的回弹动画。
                            closeJob?.cancel()
                            closeJob = null
                            drag.active = true
                            drag.editing = editing
                            drag.side = side
                            drag.startRawX = event.rawX
                            drag.startRawY = event.rawY
                            drag.startWindowX = dragX
                            drag.startWindowY = dragY
                            drag.moved = false
                            drag.crossed = false
                            drag.pullPx = 0f
                            // 上一轮动画被取消时可能留下半开的抽屉。
                            pullPxState.floatValue = 0f
                            // 按下就撑到信封：宽到抽屉上限 + 手指容错。
                            // 之后整个手势期间不再改尺寸。
                            if (!drag.editing) {
                                applyTouchWidth(envelopeWidthPx)
                            }
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (!drag.active) return@pointerInteropFilter false
                            if (!drag.moved) {
                                val travel = abs(event.rawX - drag.startRawX) +
                                    abs(event.rawY - drag.startRawY)
                                if (travel > touchSlopPx) drag.moved = true
                            }
                            if (!drag.moved) return@pointerInteropFilter true

                            if (drag.editing) {
                                moveTo(
                                    x = (drag.startWindowX + (event.rawX - drag.startRawX))
                                        .roundToInt(),
                                    y = (drag.startWindowY + (event.rawY - drag.startRawY))
                                        .roundToInt(),
                                )
                            } else {
                                // raw 坐标与窗口几何无关：窗口再宽也不会吃掉手指位移。
                                val inward = HandleWindowMath.inwardDx(
                                    downScreenX = drag.startRawX,
                                    currentScreenX = event.rawX,
                                    side = drag.side,
                                )
                                drag.pullPx = HandleWindowMath.pullForDrag(
                                    inwardDx = inward,
                                    maxPullPx = maxPullPx,
                                    overshootMaxPx = pullOvershootPx,
                                )
                                if (abs(drag.pullPx - pullPxState.floatValue) > 0.5f) {
                                    pullPxState.floatValue = drag.pullPx
                                }
                                if (!drag.crossed &&
                                    HandleWindowMath.isTriggered(drag.pullPx, swipeThresholdPx)
                                ) {
                                    drag.crossed = true
                                    rootView.performHapticFeedback(
                                        HapticFeedbackConstants.CONTEXT_CLICK,
                                    )
                                }
                            }
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            if (!drag.active) return@pointerInteropFilter false
                            val trigger = !drag.editing && drag.moved &&
                                HandleWindowMath.isTriggered(drag.pullPx, swipeThresholdPx)
                            Log.d(
                                TAG,
                                "up pull=${drag.pullPx.roundToInt()} " +
                                    "threshold=$swipeThresholdPx moved=${drag.moved} " +
                                    "editing=${drag.editing} trigger=$trigger",
                            )
                            finishGesture(trigger = trigger)
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            if (!drag.active) return@pointerInteropFilter false
                            finishGesture(trigger = false)
                            true
                        }

                        // 多指按下/抬起不改变主手指的抽屉状态，但继续吃掉事件。
                        else -> true
                    }
                },
        )
    }

    /**
     * 视觉窗口的内容：手柄 + 抽屉。**几何永远不变**，所以不会跳。
     *
     * 窗口固定 [envelopeWidthPx] 宽，内容靠屏幕那一侧对齐：
     * 右侧手柄时 Row 是「手柄 → 抽屉」，抽屉把手柄往屏幕内推；左侧镜像。
     * 抽屉宽度只由 [pullPxState] 驱动，与窗口尺寸无关。
     */
    @Composable
    private fun VisualContent() {
        val settingsState by settings.state.collectAsStateWithLifecycle()
        val editing = settingsState.positionEditing
        val side = settingsState.placement.side

        Box(
            modifier = Modifier.fillMaxSize(),
            // 视觉手柄始终贴在屏幕那一侧；抽屉从边缘向屏幕内拉出。
            contentAlignment = when {
                editing -> Alignment.Center
                side == DockSide.Left -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            },
        ) {
            if (editing) {
                HandleVisual(
                    Modifier
                        .width(HANDLE_WIDTH)
                        .height(HANDLE_HEIGHT),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (side == DockSide.Left) {
                        DrawerPanel(
                            pullPx = pullPxState.floatValue,
                            maxPullPx = maxPullPx,
                        )
                        HandleVisual(
                            Modifier
                                .width(HANDLE_WIDTH)
                                .height(HANDLE_HEIGHT),
                        )
                    } else {
                        HandleVisual(
                            Modifier
                                .width(HANDLE_WIDTH)
                                .height(HANDLE_HEIGHT),
                        )
                        DrawerPanel(
                            pullPx = pullPxState.floatValue,
                            maxPullPx = maxPullPx,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        private const val TAG = "Morg"

        /**
         * 贴边窗口也用的绝对定位 gravity。
         *
         * 用 `LEFT` 而不是 `START`：`x` 已经是我们自己算好的绝对左边缘，
         * 在 RTL 语言下 `START` 会从右边算起，位置会反。
         */
        @Suppress("RtlHardcoded")
        private val ABSOLUTE_GRAVITY: Int = Gravity.TOP or Gravity.LEFT

        /**
         * 稳态轮询间隔（手柄隐藏时）。
         *
         * 旧值是固定的 1.5s → 700ms。用户反馈「进入应用时显示还行」，
         * 所以这一侧只需要拿掉 700ms 的粗糙度；快轮询负责另外一侧。
         */
        private const val IDLE_POLL_INTERVAL_MS = 300L

        /**
         * 快轮询间隔：手柄可见期间（要尽快发现用户离开了应用），
         * 或刚切换过可见性时。
         *
         * 真进后台时系统写下 `PAUSED/STOPPED:app` 的时刻比 `RESUMED:launcher`
         * 晚约 1.1s（系统侧物理延迟，改不了）。这个值只决定我们**发现**它有多快：
         * 实测「事件写入 → 隐藏」已压到 43–133ms。
         */
        private const val FAST_POLL_INTERVAL_MS = 120L

        /**
         * 确认小窗期间的轮询间隔。
         *
         * 小窗要等 `WINDOW_SETTLE_MS`（实测 1132–1142ms 的系统延迟）才能确认，
         * 而检测「到点了」的粒度就是这个间隔。它只在那 1.17s 内生效。
         */
        private const val SETTLE_POLL_INTERVAL_MS = 30L

        /** 可见性变化后继续快轮询多久：切换往往连着来（桌面 → 应用）。 */
        private const val ACTIVE_WINDOW_MS = 2_000L

        /** 视觉手柄尺寸，也是常态窗口尺寸。 */
        private val HANDLE_WIDTH = 12.dp
        private val HANDLE_HEIGHT = 64.dp

        /** 位置设定模式的窗口宽度：够手指抓住。 */
        private val EDIT_WIDTH = 32.dp

        /** 抽屉最大拉出距离（1:1 跟手段）。 */
        private val MAX_PULL = 64.dp

        /** 拉到头之后还能再拽出来的弹性余量。 */
        private val PULL_OVERSHOOT = 16.dp

        /** 手势窗口比抽屉多留的容错，避免手指滑到窗口外被系统抢走。 */
        private val DRAG_SLACK = 24.dp

        /**
         * 窗口上下各留的纵向容错。
         *
         * 常态和手势态用**同一个**值，且窗口高度始终不变 ——
         * 视觉窗口几何恒定，所以不会出现「改高度那一帧手柄错位」。
         * 太小没效果（手指从上下边缘落到窗口外 → 系统返回手势）；
         * 太大则贴着屏幕上下端时无位置可撑。16dp 在 480dpi 上是 48px，
         * 足够吸收按压偏差。
         */
        private val EDGE_SLACK_GESTURE = 16.dp

        /** 触发阈值：松手时抽屉拉出超过约 28dp 才真正多开。 */
        private val SWIPE_THRESHOLD = 28.dp
    }
}

/** 一次拖动会话的状态，跨 MotionEvent 与重组保留。 */
private class DragState {
    var active = false
    var editing = false
    var side = DockSide.Right
    var startRawX = 0f
    var startRawY = 0f
    var startWindowX = 0
    var startWindowY = 0
    var moved = false
    var crossed = false
    var pullPx = 0f
}

/**
 * 抽屉本体：从屏幕边缘拉出的面板，中间一个「＋」，表示再开一个实例。
 *
 * 直角矩形，不做圆角 —— 面板贴屏幕边缘，圆角会把靠边那一侧切出一条缝。
 *
 * 宽度为 0 时整块不参与布局，所以常态看起来只有手柄。
 */
@Composable
private fun DrawerPanel(pullPx: Float, maxPullPx: Int) {
    if (pullPx <= 0f) return
    val width = with(LocalDensity.current) { pullPx.toDp() }
    val progress = (pullPx / maxPullPx).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .width(width)
            .height(DRAWER_HEIGHT)
            .background(PANEL_COLOR),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val alpha = ((progress - 0.15f) / 0.85f).coerceIn(0f, 1f) * 0.8f
            if (alpha <= 0f) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = 8.dp.toPx()
            val stroke = 1.6.dp.toPx()
            val color = Color.White.copy(alpha = alpha)
            drawCircle(
                color = color,
                radius = radius,
                center = center,
                style = Stroke(width = stroke),
            )
            val arm = radius * 0.45f
            drawLine(
                color = color,
                start = Offset(center.x - arm, center.y),
                end = Offset(center.x + arm, center.y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(center.x, center.y - arm),
                end = Offset(center.x, center.y + arm),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 手柄外观：深色圆角条，中间一条浅色标记。 */
@Composable
internal fun HandleVisual(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xE6191927)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(3.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x66FFFFFF)),
        )
    }
}

/** 抽屉面板的高度，比手柄略矮，上下露出一点手柄的轮廓。 */
private val DRAWER_HEIGHT = 56.dp

/** 抽屉面板底色：与手柄同色系，但要能压在任意应用画面上。 */
private val PANEL_COLOR = Color(0xF01B1B29)
